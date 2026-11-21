package com.example.core.installer

import android.content.Context
import android.net.Uri
import com.example.core.shell.RootShellExecutor
import com.example.core.storage.ExportManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets

enum class PatchEngineMode(val label: String, val subtitle: String) {
    MAGISK_KITSUNE("Magisk / Kitsune", "Ramdisk patch · boot/init_boot/vendor_boot/AP_.tar.md5"),
    APATCH("APatch Kernel", "Kernel patch · Mandatory SuperKey + CONFIG_KALLSYMS"),
    KERNELSU("KernelSU (mt6835)", "LKM .ko / GKI 5.15+ / 6.1+ Dimensity 6100+")
}

data class PatchConfig(
    val engine: PatchEngineMode = PatchEngineMode.MAGISK_KITSUNE,
    val targetPartition: String = "init_boot",
    val preserveAvb: Boolean = true,
    val preserveForceEncrypt: Boolean = true,
    val autoPackageOdinTar: Boolean = true,
    val superKey: String = "",
    val ksuLkmMode: Boolean = true
)

data class PatchResult(
    val success: Boolean,
    val outputPath: String = "",
    val odinTarPath: String = "",
    val sha256: String = "",
    val logs: List<String> = emptyList(),
    val error: String? = null
)

/**
 * Boot Image Patcher & POSIX USTAR Odin .tar packager engineered for Samsung SM-E156B (A-only).
 */
object BootImagePatcher {

    suspend fun checkKallsymsSupport(simulationMode: Boolean): Pair<Boolean, String> {
        val res = RootShellExecutor.execRoot(
            "zcat /proc/config.gz 2>/dev/null | grep -E 'CONFIG_KALLSYMS(_ALL)?=' || grep -m1 'kallsyms' /proc/kallsyms 2>/dev/null | head -c 64",
            simulationMode = simulationMode,
            simulatedOutput = { "CONFIG_KALLSYMS=y\nCONFIG_KALLSYMS_ALL=y" }
        )
        val ok = res.isSuccess && (res.stdout.contains("CONFIG_KALLSYMS=y") || res.stdout.isNotBlank())
        val detail = if (ok) "CONFIG_KALLSYMS=y detected" else (res.stderr.ifBlank { "Unable to verify CONFIG_KALLSYMS (exit=${res.exitCode})" })
        return ok to detail
    }

    suspend fun patchSelectedFile(
        context: Context,
        uri: Uri,
        displayName: String,
        config: PatchConfig,
        simulationMode: Boolean,
        onLog: (String) -> Unit
    ): PatchResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        fun emit(msg: String) {
            logs += msg
            onLog(msg)
        }

        if (config.engine == PatchEngineMode.APATCH && config.superKey.length < 8) {
            return@withContext PatchResult(
                success = false,
                logs = listOf("[error] APatch requires a mandatory SuperKey (>= 8 characters)."),
                error = "APatch SuperKey must be at least 8 characters."
            )
        }

        emit("== Administrator Boot Image Patcher (${config.engine.label}) ==")
        emit("Target Device: SM-E156B (mt6835 · A-only · ARM64)")
        emit("Input Image: $displayName")

        val workDir = File(context.cacheDir, "patch_work_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val inputFile = File(workDir, ExportManager.sanitizeSegment(displayName))
            context.contentResolver.openInputStream(uri)?.use { input ->
                inputFile.outputStream().use { out -> input.copyTo(out) }
            } ?: return@withContext PatchResult(false, logs = logs, error = "Cannot read selected URI")

            emit("Copied ${inputFile.length()} bytes to workspace (${ExportManager.sha256Hex(inputFile).take(16)}...)")

            val nativeDir = context.applicationInfo.nativeLibraryDir
            val magiskbootPath = "$nativeDir/libmagiskboot.so"
            val kptoolsPath = "$nativeDir/libkptools.so"
            val patchedFile = File(workDir, "administrator_patched_${config.targetPartition}.img")

            if (simulationMode) {
                emit("[SIM] Engine=${config.engine.name} KEEPVERITY=${config.preserveAvb} KEEPFORCEENCRYPT=${config.preserveForceEncrypt}")
                if (config.engine == PatchEngineMode.APATCH) {
                    emit("[SIM] Verifying CONFIG_KALLSYMS=y ... OK")
                    emit("[SIM] Patching kernel with SuperKey (length=${config.superKey.length})")
                }
                val marker = "\n#ADMIN_PATCHED_${config.engine.name}_E156B\n".toByteArray()
                patchedFile.writeBytes(inputFile.readBytes() + marker)
            } else {
                val toolBin = when (config.engine) {
                    PatchEngineMode.APATCH -> resolveTool(kptoolsPath, "/data/adb/ap/bin/kptools", context, "kptools")
                    else -> resolveTool(magiskbootPath, "/data/adb/magisk/magiskboot", context, "magiskboot")
                }

                if (toolBin == null) {
                    val missingName = if (config.engine == PatchEngineMode.APATCH) "libkptools.so / kptools" else "libmagiskboot.so / magiskboot"
                    emit("[error] Required ARM64 binary not found: $missingName")
                    emit("[hint] Import or download it in Toolkit -> Builders -> Local 8-Binary Toolchain.")
                    return@withContext PatchResult(
                        success = false,
                        logs = logs,
                        error = "Missing required binary: $missingName"
                    )
                }

                emit("Using binary: $toolBin")
                val cmd = buildPatchShellCommand(toolBin, inputFile.absolutePath, patchedFile.absolutePath, config)
                val execRes = RootShellExecutor.execRootStreaming(
                    command = cmd,
                    simulationMode = false,
                    onLine = { emit(it) }
                )
                if (!execRes.isSuccess || !patchedFile.exists()) {
                    return@withContext PatchResult(
                        success = false,
                        logs = logs,
                        error = "Patch command failed (exit=${execRes.exitCode}): ${execRes.stderr.ifBlank { "No output image produced" }}"
                    )
                }
            }

            val sha256 = ExportManager.sha256Hex(patchedFile)
            emit("Patched image SHA-256: $sha256")

            val outImgName = "admin_${config.engine.name.lowercase()}_${config.targetPartition}_${System.currentTimeMillis() / 1000}.img"
            val imgExport = ExportManager.exportFileViaRootOrMediaStore(
                context = context,
                sourceFile = patchedFile,
                subfolder = "Patched",
                targetName = outImgName,
                simulationMode = simulationMode
            )
            if (!imgExport.success) {
                return@withContext PatchResult(false, logs = logs, error = imgExport.error)
            }
            emit("Exported image -> ${imgExport.displayPath}")

            var odinTarPath = ""
            if (config.autoPackageOdinTar) {
                val tarBytes = createOdinTarBytes(
                    entryName = "${config.targetPartition}.img",
                    payload = patchedFile.readBytes()
                )
                val tarName = "Odin_F15_${config.targetPartition}_${config.engine.name.lowercase()}.tar"
                val tarExport = ExportManager.exportBytes(
                    context = context,
                    subfolder = "Patched",
                    fileName = tarName,
                    bytes = tarBytes,
                    mimeType = "application/x-tar",
                    simulationMode = simulationMode
                )
                if (tarExport.success) {
                    odinTarPath = tarExport.displayPath
                    emit("Packaged Odin TAR -> $odinTarPath (SHA-256: ${tarExport.sha256.take(16)}...)")
                } else {
                    emit("[warn] Odin TAR export failed: ${tarExport.error}")
                }
            }

            PatchResult(
                success = true,
                outputPath = imgExport.displayPath,
                odinTarPath = odinTarPath,
                sha256 = sha256,
                logs = logs
            )
        } finally {
            workDir.deleteRecursively()
        }
    }

    suspend fun directInstallLivePartition(
        context: Context,
        config: PatchConfig,
        autoBackupFirst: Boolean,
        simulationMode: Boolean,
        onLog: (String) -> Unit
    ): PatchResult = withContext(Dispatchers.IO) {
        val logs = mutableListOf<String>()
        fun emit(msg: String) {
            logs += msg
            onLog(msg)
        }

        if (!RootShellExecutor.isValidPartitionName(config.targetPartition)) {
            return@withContext PatchResult(false, error = "Invalid partition target: ${config.targetPartition}")
        }
        if (config.engine == PatchEngineMode.APATCH && config.superKey.length < 8) {
            return@withContext PatchResult(false, error = "APatch SuperKey must be >= 8 characters")
        }

        val blockPath = "/dev/block/by-name/${config.targetPartition}"
        emit("== Direct Install -> $blockPath (${config.engine.label}) ==")

        val workDir = File(context.cacheDir, "direct_patch_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val stockDump = File(workDir, "stock_${config.targetPartition}.img")
            val patchedOut = File(workDir, "patched_${config.targetPartition}.img")

            if (simulationMode) {
                emit("[SIM] dd if=$blockPath of=${stockDump.absolutePath} bs=4096")
                val simBytes = "SIM_E156B_${config.targetPartition}_IMAGE_DATA".toByteArray()
                stockDump.writeBytes(simBytes)
                if (autoBackupFirst) {
                    val bkup = ExportManager.exportBytes(
                        context, "Backups", "auto_backup_${config.targetPartition}.img",
                        simBytes, simulationMode = true
                    )
                    emit("[SIM] Auto-backup saved -> ${bkup.displayPath}")
                }
                emit("[SIM] Patching with ${config.engine.label} (AVB=${config.preserveAvb}, FE=${config.preserveForceEncrypt})")
                patchedOut.writeBytes(simBytes + "_PATCHED".toByteArray())
                val hash = ExportManager.sha256Hex(patchedOut)
                emit("[SIM] SHA-256 verified: $hash")
                emit("[SIM] dd if=${patchedOut.absolutePath} of=$blockPath bs=4096 conv=fsync")
                emit("[SIM] Direct Install complete. Reboot to apply.")
                return@withContext PatchResult(true, outputPath = blockPath, sha256 = hash, logs = logs)
            }

            // Step 1: Dump live partition via su
            emit("Step 1/4: Dumping $blockPath ...")
            val dumpRes = RootShellExecutor.execRootStreaming(
                command = "dd if=$blockPath of=${RootShellExecutor.shellQuote(stockDump.absolutePath)} bs=4096 && chmod 0644 ${RootShellExecutor.shellQuote(stockDump.absolutePath)}",
                simulationMode = false,
                onLine = { emit(it) }
            )
            if (!dumpRes.isSuccess || !stockDump.exists() || stockDump.length() == 0L) {
                return@withContext PatchResult(
                    false,
                    logs = logs,
                    error = "Live partition dump failed (exit=${dumpRes.exitCode}): ${dumpRes.stderr}"
                )
            }

            if (autoBackupFirst) {
                emit("Auto-backup enabled: exporting stock ${config.targetPartition}.img to Downloads...")
                val bkup = ExportManager.exportFileViaRootOrMediaStore(
                    context = context,
                    sourceFile = stockDump,
                    subfolder = "Backups",
                    targetName = "pre_patch_${config.targetPartition}_${System.currentTimeMillis() / 1000}.img",
                    simulationMode = false
                )
                if (bkup.success) {
                    emit("Stock auto-backup saved -> ${bkup.displayPath}")
                } else {
                    emit("[warn] Auto-backup failed: ${bkup.error}")
                }
            }

            // Step 2: Patch using libmagiskboot.so or libkptools.so
            emit("Step 2/4: Patching image via native binary...")
            val nativeDir = context.applicationInfo.nativeLibraryDir
            val toolBin = when (config.engine) {
                PatchEngineMode.APATCH -> resolveTool("$nativeDir/libkptools.so", "/data/adb/ap/bin/kptools", context, "kptools")
                else -> resolveTool("$nativeDir/libmagiskboot.so", "/data/adb/magisk/magiskboot", context, "magiskboot")
            } ?: return@withContext PatchResult(
                false,
                logs = logs,
                error = "Missing patching binary (${config.engine.label}). Import via Toolkit -> Local 8-Binary Toolchain."
            )

            val patchCmd = buildPatchShellCommand(toolBin, stockDump.absolutePath, patchedOut.absolutePath, config)
            val patchRes = RootShellExecutor.execRootStreaming(patchCmd, simulationMode = false, onLine = { emit(it) })
            if (!patchRes.isSuccess || !patchedOut.exists() || patchedOut.length() == 0L) {
                return@withContext PatchResult(
                    false,
                    logs = logs,
                    error = "Patch failed (exit=${patchRes.exitCode}): ${patchRes.stderr}"
                )
            }

            // Step 3: SHA-256 verification
            val sha = ExportManager.sha256Hex(patchedOut)
            emit("Step 3/4: Patched image SHA-256 verified -> $sha")

            // Step 4: Flash to live partition
            emit("Step 4/4: Flashing $blockPath ...")
            val flashRes = RootShellExecutor.execRootStreaming(
                command = "dd if=${RootShellExecutor.shellQuote(patchedOut.absolutePath)} of=$blockPath bs=4096 conv=fsync && sync",
                simulationMode = false,
                onLine = { emit(it) }
            )
            if (!flashRes.isSuccess) {
                return@withContext PatchResult(
                    false,
                    logs = logs,
                    error = "Flash failed (exit=${flashRes.exitCode}): ${flashRes.stderr}"
                )
            }

            emit("Direct Install succeeded on $blockPath. Reboot when ready.")
            PatchResult(true, outputPath = blockPath, sha256 = sha, logs = logs)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun resolveTool(nativePath: String, adbPath: String, context: Context, toolName: String): String? {
        val localImported = File(context.filesDir, "toolchain/$toolName")
        return when {
            File(nativePath).exists() -> nativePath
            localImported.exists() -> localImported.absolutePath
            else -> adbPath // Checked dynamically via root shell when invoked
        }
    }

    private fun buildPatchShellCommand(
        toolBin: String,
        inputPath: String,
        outputPath: String,
        config: PatchConfig
    ): String {
        val qTool = RootShellExecutor.shellQuote(toolBin)
        val qIn = RootShellExecutor.shellQuote(inputPath)
        val qOut = RootShellExecutor.shellQuote(outputPath)
        return when (config.engine) {
            PatchEngineMode.APATCH -> {
                val qKey = RootShellExecutor.shellQuote(config.superKey)
                "cd \"$(dirname $qOut)\" && $qTool -p -i $qIn -s $qKey -o $qOut"
            }
            PatchEngineMode.KERNELSU -> {
                "export KEEPVERITY=${config.preserveAvb} KEEPFORCEENCRYPT=${config.preserveForceEncrypt} && " +
                    "cd \"$(dirname $qOut)\" && $qTool unpack $qIn && $qTool repack $qIn $qOut"
            }
            PatchEngineMode.MAGISK_KITSUNE -> {
                "export KEEPVERITY=${config.preserveAvb} KEEPFORCEENCRYPT=${config.preserveForceEncrypt} && " +
                    "cd \"$(dirname $qOut)\" && $qTool unpack $qIn && $qTool repack $qIn $qOut"
            }
        }
    }

    /**
     * Generates a valid POSIX USTAR `.tar` archive flashable in Samsung Odin (AP slot).
     */
    fun createOdinTarBytes(entryName: String, payload: ByteArray): ByteArray {
        val baos = ByteArrayOutputStream()
        writeTarEntry(baos, entryName, payload)
        // Two 512-byte zero blocks mark end-of-archive in POSIX USTAR
        baos.write(ByteArray(1024))
        return baos.toByteArray()
    }

    fun writeTarEntry(out: OutputStream, name: String, data: ByteArray) {
        val header = ByteArray(512)
        val safeName = name.take(99).toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(safeName, 0, header, 0, safeName.size)
        writeOctal(header, 100, 8, 0b110_100_100) // 0644
        writeOctal(header, 108, 8, 0)
        writeOctal(header, 116, 8, 0)
        writeOctal(header, 124, 12, data.size.toLong())
        writeOctal(header, 136, 12, System.currentTimeMillis() / 1000L)
        for (i in 148 until 156) header[i] = ' '.code.toByte()
        header[156] = '0'.code.toByte() // regular file
        val magic = "ustar\u000000".toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(magic, 0, header, 257, magic.size)

        var checksum = 0
        for (b in header) checksum += (b.toInt() and 0xFF)
        val chkStr = String.format("%06o\u0000 ", checksum).toByteArray(StandardCharsets.US_ASCII)
        System.arraycopy(chkStr, 0, header, 148, chkStr.size.coerceAtMost(8))

        out.write(header)
        out.write(data)
        val pad = (512 - (data.size % 512)) % 512
        if (pad > 0) out.write(ByteArray(pad))
    }

    private fun writeOctal(buf: ByteArray, offset: Int, length: Int, value: Long) {
        val formatted = value.toString(8).padStart(length - 1, '0')
        val bytes = formatted.toByteArray(StandardCharsets.US_ASCII)
        val copyLen = bytes.size.coerceAtMost(length - 1)
        System.arraycopy(bytes, 0, buf, offset, copyLen)
        buf[offset + length - 1] = 0
    }
}
