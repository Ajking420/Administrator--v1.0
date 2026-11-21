package com.example.core.vault

import android.content.Context
import android.net.Uri
import com.example.core.shell.RootShellExecutor
import com.example.core.storage.ExportManager
import com.example.core.storage.ExportOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class VaultVerifyResult(
    val valid: Boolean,
    val partitionsFound: List<String>,
    val manifestShaSummary: String,
    val message: String
)

/**
 * Encrypted EFS / IMEI Vault Engine for Samsung Galaxy F15 5G (SM-E156B).
 *
 * Crypto specification:
 * - Magic header: `ADMVLT01` (8 bytes)
 * - Key derivation: `PBKDF2WithHmacSHA256` (65,536 iterations, random 16-byte salt -> 256-bit AES key)
 * - Cipher: `AES/GCM/NoPadding` with random 12-byte IV & 128-bit authentication tag
 * - Inner payload: ZIP containing raw dumps of `efs`, `sec_efs`, `nvram`, `nvdata`, `nvcfg`,
 *   `protect1`, `protect2` + `manifest.sha256`
 */
object EfsVaultEngine {

    val DEFAULT_IMEI_PARTITIONS = listOf(
        "efs", "sec_efs", "nvram", "nvdata", "nvcfg", "protect1", "protect2"
    )

    private val MAGIC = "ADMVLT01".toByteArray(Charsets.US_ASCII)
    private const val PBKDF2_ITERATIONS = 65_536
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    private fun deriveAesKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun encryptPayload(plainZipBytes: ByteArray, passphrase: String): ByteArray {
        val rng = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also { rng.nextBytes(it) }
        val iv = ByteArray(IV_BYTES).also { rng.nextBytes(it) }
        val key = deriveAesKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainZipBytes)

        return ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { dos ->
                dos.write(MAGIC)
                dos.writeInt(PBKDF2_ITERATIONS)
                dos.write(salt)
                dos.write(iv)
                dos.writeInt(cipherBytes.size)
                dos.write(cipherBytes)
            }
            baos.toByteArray()
        }
    }

    fun decryptPayload(vaultBytes: ByteArray, passphrase: String): ByteArray {
        DataInputStream(ByteArrayInputStream(vaultBytes)).use { dis ->
            val magicBuf = ByteArray(MAGIC.size)
            dis.readFully(magicBuf)
            require(magicBuf.contentEquals(MAGIC)) { "Invalid .adminvault header magic" }
            val iterations = dis.readInt()
            require(iterations in 10_000..500_000) { "Unsupported PBKDF2 iteration count" }
            val salt = ByteArray(SALT_BYTES).also { dis.readFully(it) }
            val iv = ByteArray(IV_BYTES).also { dis.readFully(it) }
            val cipherLen = dis.readInt()
            require(cipherLen in 16..200_000_000) { "Corrupted vault ciphertext length" }
            val cipherBytes = ByteArray(cipherLen).also { dis.readFully(it) }

            val spec = PBEKeySpec(passphrase.toCharArray(), salt, iterations, KEY_BITS)
            val key = SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES"
            )
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(cipherBytes)
        }
    }

    suspend fun createEncryptedVault(
        context: Context,
        partitions: List<String>,
        passphrase: String,
        simulationMode: Boolean,
        onLog: (String) -> Unit
    ): ExportOutcome = withContext(Dispatchers.IO) {
        if (passphrase.length < 6) {
            return@withContext ExportOutcome(false, "", error = "Vault passphrase must be at least 6 characters.")
        }
        val validParts = partitions.filter { RootShellExecutor.isValidPartitionName(it) }
        if (validParts.isEmpty()) {
            return@withContext ExportOutcome(false, "", error = "Select at least one valid IMEI/EFS partition.")
        }

        val manifest = StringBuilder()
        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                for (part in validParts) {
                    onLog("Reading /dev/block/by-name/$part ...")
                    val partBytes: ByteArray = if (simulationMode) {
                        "SIM_E156B_IMEI_DUMP_${part}_${System.currentTimeMillis()}".toByteArray()
                    } else {
                        val tmpPath = "${context.cacheDir.absolutePath}/vault_dump_$part.img"
                        val qTmp = RootShellExecutor.shellQuote(tmpPath)
                        val res = RootShellExecutor.execRoot(
                            "dd if=/dev/block/by-name/$part of=$qTmp bs=4096 2>/dev/null && chmod 0644 $qTmp",
                            simulationMode = false,
                            timeoutSeconds = 60
                        )
                        val file = java.io.File(tmpPath)
                        if (!res.isSuccess || !file.exists()) {
                            onLog("[error] Failed dumping $part (exit=${res.exitCode}): ${res.stderr}")
                            return@withContext ExportOutcome(
                                false,
                                "",
                                error = "Failed dumping $part (exit=${res.exitCode}): ${res.stderr.ifBlank { "Root required" }}"
                            )
                        }
                        val bytes = file.readBytes()
                        file.delete()
                        bytes
                    }

                    val hash = ExportManager.sha256Hex(partBytes)
                    manifest.appendLine("$hash  $part.img")
                    zos.putNextEntry(ZipEntry("$part.img"))
                    zos.write(partBytes)
                    zos.closeEntry()
                    onLog("  + $part.img (${partBytes.size} bytes · sha256=${hash.take(12)}...)")
                }
                zos.putNextEntry(ZipEntry("manifest.sha256"))
                zos.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            baos.toByteArray()
        }

        onLog("Encrypting with PBKDF2WithHmacSHA256 (65,536 iter) + AES-256-GCM ...")
        val encrypted = encryptPayload(zipBytes, passphrase)
        val out = ExportManager.exportBytes(
            context = context,
            subfolder = "Vault",
            fileName = "F15_EFS_IMEI_${System.currentTimeMillis() / 1000}.adminvault",
            bytes = encrypted,
            mimeType = "application/octet-stream",
            simulationMode = simulationMode
        )
        if (out.success) {
            onLog("Encrypted vault exported -> ${out.displayPath}")
        }
        out
    }

    suspend fun verifyVaultIntegrity(
        context: Context,
        uri: Uri,
        passphrase: String
    ): VaultVerifyResult = withContext(Dispatchers.IO) {
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to open selected .adminvault file")
            val decryptedZip = decryptPayload(raw, passphrase)
            val entries = mutableMapOf<String, ByteArray>()
            ZipInputStream(ByteArrayInputStream(decryptedZip)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    if (!e.isDirectory) entries[e.name] = zis.readBytes()
                    e = zis.nextEntry
                }
            }
            val manifestText = entries["manifest.sha256"]?.toString(Charsets.UTF_8)
                ?: error("manifest.sha256 missing inside vault")
            val verifiedParts = mutableListOf<String>()
            for (line in manifestText.lines().filter { it.isNotBlank() }) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 2) continue
                val expectedHash = parts[0]
                val fileName = parts[1]
                val data = entries[fileName] ?: error("Missing partition image $fileName")
                val actualHash = ExportManager.sha256Hex(data)
                if (!actualHash.equals(expectedHash, ignoreCase = true)) {
                    error("SHA-256 mismatch on $fileName")
                }
                verifiedParts += fileName.removeSuffix(".img")
            }
            VaultVerifyResult(
                valid = true,
                partitionsFound = verifiedParts,
                manifestShaSummary = manifestText.trim(),
                message = "GCM auth tag & SHA-256 verified for ${verifiedParts.size} partitions."
            )
        }.getOrElse {
            VaultVerifyResult(
                valid = false,
                partitionsFound = emptyList(),
                manifestShaSummary = "",
                message = "Verification failed: ${it.message ?: "Wrong passphrase or corrupted file"}"
            )
        }
    }

    suspend fun restoreEncryptedVault(
        context: Context,
        uri: Uri,
        passphrase: String,
        autoBackupBeforeRestore: Boolean,
        simulationMode: Boolean,
        onLog: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val verify = verifyVaultIntegrity(context, uri, passphrase)
            if (!verify.valid) error(verify.message)

            if (autoBackupBeforeRestore) {
                onLog("Auto-backup enabled: creating pre-restore safety snapshot...")
                createEncryptedVault(
                    context = context,
                    partitions = verify.partitionsFound,
                    passphrase = passphrase,
                    simulationMode = simulationMode,
                    onLog = onLog
                )
            }

            val raw = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to open vault URI")
            val decryptedZip = decryptPayload(raw, passphrase)

            ZipInputStream(ByteArrayInputStream(decryptedZip)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val partName = e.name.removeSuffix(".img")
                    if (e.name.endsWith(".img") && RootShellExecutor.isValidPartitionName(partName)) {
                        val bytes = zis.readBytes()
                        if (simulationMode) {
                            onLog("[SIM] dd if=$partName.img (${bytes.size} B) of=/dev/block/by-name/$partName bs=4096 conv=fsync")
                        } else {
                            val tmp = java.io.File(context.cacheDir, "restore_$partName.img")
                            tmp.writeBytes(bytes)
                            val res = RootShellExecutor.execRoot(
                                "dd if=${RootShellExecutor.shellQuote(tmp.absolutePath)} of=/dev/block/by-name/$partName bs=4096 conv=fsync && sync",
                                simulationMode = false,
                                timeoutSeconds = 60
                            )
                            tmp.delete()
                            if (!res.isSuccess) error("Restore failed on $partName (exit=${res.exitCode}): ${res.stderr}")
                            onLog("Restored /dev/block/by-name/$partName (${bytes.size} B)")
                        }
                    }
                    e = zis.nextEntry
                }
            }
            "Restored ${verify.partitionsFound.size} IMEI/EFS partitions cleanly."
        }
    }
}
