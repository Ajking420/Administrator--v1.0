package com.example.core.storage

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.core.shell.RootShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class ExportOutcome(
    val success: Boolean,
    val displayPath: String,
    val sha256: String = "",
    val sizeBytes: Long = 0L,
    val shareUri: Uri? = null,
    val error: String? = null
)

/**
 * Real Public Storage Export Manager visible immediately in Samsung My Files:
 * Writes outputs to `/storage/emulated/0/Download/Administrator/<Subfolder>/`
 * via MediaStore.Downloads (or direct file + MediaScannerConnection / su -c cp fallback).
 */
object ExportManager {

    const val ROOT_DOWNLOAD_SUBDIR = "Administrator"

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun createAllFilesAccessIntent(context: Context): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }

    fun publicFolderPath(subfolder: String): String {
        val safeSub = sanitizeSegment(subfolder)
        return "/storage/emulated/0/Download/$ROOT_DOWNLOAD_SUBDIR/$safeSub"
    }

    fun sanitizeSegment(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "Output" }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                md.update(buf, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun exportBytes(
        context: Context,
        subfolder: String,
        fileName: String,
        bytes: ByteArray,
        mimeType: String = "application/octet-stream",
        simulationMode: Boolean
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val safeSub = sanitizeSegment(subfolder)
        val safeName = sanitizeSegment(fileName)
        val expectedPublicPath = "/storage/emulated/0/Download/$ROOT_DOWNLOAD_SUBDIR/$safeSub/$safeName"
        val hash = sha256Hex(bytes)

        // Even in SIM mode, writing to app cache or Downloads keeps Share/Verify flows real,
        // and when simulationMode == false we always write to public Downloads visible in My Files.
        if (simulationMode) {
            val simFile = File(File(context.cacheDir, "sim_exports/$safeSub").apply { mkdirs() }, safeName)
            simFile.writeBytes(bytes)
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", simFile)
            }.getOrNull()
            return@withContext ExportOutcome(
                success = true,
                displayPath = "[SIM] $expectedPublicPath",
                sha256 = hash,
                sizeBytes = bytes.size.toLong(),
                shareUri = uri
            )
        }

        // Primary path 1: Direct write to /storage/emulated/0/Download/Administrator/<Subfolder>/ when All Files Access granted
        if (hasAllFilesAccess()) {
            val directAttempt = runCatching {
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val targetDir = File(downloadsDir, "$ROOT_DOWNLOAD_SUBDIR/$safeSub")
                if (!targetDir.exists()) targetDir.mkdirs()
                val outFile = File(targetDir, safeName)
                FileOutputStream(outFile).use { it.write(bytes) }
                MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf(mimeType), null)
                val shareUri = runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
                }.getOrNull()
                ExportOutcome(
                    success = true,
                    displayPath = outFile.absolutePath,
                    sha256 = hash,
                    sizeBytes = bytes.size.toLong(),
                    shareUri = shareUri
                )
            }
            if (directAttempt.isSuccess) return@withContext directAttempt.getOrThrow()
        }

        // Primary path 2: MediaStore.Downloads (works on Android 10+ even before All Files Access is granted)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mediaStoreAttempt = runCatching {
                val resolver = context.contentResolver
                val relPath = "${Environment.DIRECTORY_DOWNLOADS}/$ROOT_DOWNLOAD_SUBDIR/$safeSub"
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, relPath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("MediaStore.Downloads insert returned null")
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                } ?: error("Unable to open MediaStore output stream")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                ExportOutcome(
                    success = true,
                    displayPath = expectedPublicPath,
                    sha256 = hash,
                    sizeBytes = bytes.size.toLong(),
                    shareUri = uri
                )
            }
            if (mediaStoreAttempt.isSuccess) return@withContext mediaStoreAttempt.getOrThrow()
        }

        // Fallback path 3: Privileged `su -c cp` + MediaScannerConnection.scanFile
        val tmpFile = File(context.cacheDir, "export_tmp_$safeName")
        tmpFile.writeBytes(bytes)
        val quotedDir = RootShellExecutor.shellQuote("/storage/emulated/0/Download/$ROOT_DOWNLOAD_SUBDIR/$safeSub")
        val quotedSrc = RootShellExecutor.shellQuote(tmpFile.absolutePath)
        val quotedDst = RootShellExecutor.shellQuote(expectedPublicPath)
        val res = RootShellExecutor.execRoot(
            "mkdir -p $quotedDir && cp -f $quotedSrc $quotedDst && chmod 0644 $quotedDst",
            simulationMode = false
        )
        tmpFile.delete()
        if (res.isSuccess) {
            MediaScannerConnection.scanFile(context, arrayOf(expectedPublicPath), arrayOf(mimeType), null)
            ExportOutcome(
                success = true,
                displayPath = expectedPublicPath,
                sha256 = hash,
                sizeBytes = bytes.size.toLong()
            )
        } else {
            ExportOutcome(
                success = false,
                displayPath = expectedPublicPath,
                error = "Export failed (exit=${res.exitCode}): ${res.stderr.ifBlank { "Grant All Files Access or Root permission" }}"
            )
        }
    }

    suspend fun exportFileViaRootOrMediaStore(
        context: Context,
        sourceFile: File,
        subfolder: String,
        targetName: String,
        mimeType: String = "application/octet-stream",
        simulationMode: Boolean
    ): ExportOutcome = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) {
            return@withContext ExportOutcome(
                success = false,
                displayPath = publicFolderPath(subfolder),
                error = "Source file not found: ${sourceFile.name}"
            )
        }
        val bytes = sourceFile.readBytes()
        exportBytes(context, subfolder, targetName, bytes, mimeType, simulationMode)
    }

    fun buildShareIntent(uri: Uri, title: String, mimeType: String = "application/octet-stream"): Intent {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(sendIntent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
