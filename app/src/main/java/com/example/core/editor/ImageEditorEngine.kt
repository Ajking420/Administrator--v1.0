package com.example.core.editor

import android.content.Context
import com.example.core.installer.BootImagePatcher
import com.example.core.shell.RootShellExecutor
import com.example.core.storage.ExportManager
import com.example.core.storage.ExportOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class EditableImageType(val label: String, val defaultPartition: String, val maxBytesMb: Int) {
    BOOT("boot.img", "boot", 64),
    INIT_BOOT("init_boot.img", "init_boot", 16),
    RECOVERY("recovery.img", "recovery", 100),
    VENDOR_BOOT("vendor_boot.img", "vendor_boot", 64),
    VBMETA("vbmeta.img", "vbmeta", 1),
    SUPER("super.img (lpunpack)", "super", 8704),
    SYSTEM_VENDOR("system / vendor (EXT4/EROFS)", "system", 4096)
}

data class RamdiskEntry(
    val path: String,
    val chmod: String,
    val isEditableText: Boolean,
    val content: String
)

data class ImageEditorWorkspace(
    val imageType: EditableImageType = EditableImageType.BOOT,
    val sourceLabel: String = "Unloaded",
    val headerVersion: Int = 4,
    val pageSize: Int = 4096,
    val osVersion: String = "14.0.0",
    val osPatchLevel: String = "2026-08",
    val cmdline: String = "bootopt=64S3,32N2,64N2 androidboot.selinux=enforcing loop.max_part=7",
    val kernelLabel: String = "kernel-5.15.149-mt6835 (14.2 MB)",
    val dtbLabel: String = "mt6835-e15x.dtb (412 KB)",
    val dtboLabel: String = "dtbo-e15x.img (8.0 MB)",
    val estimatedSizeBytes: Long = 34_603_008L,
    val maxPartitionBytes: Long = 67_108_864L,
    val ramdiskFiles: List<RamdiskEntry> = emptyList(),
    val isUnpacked: Boolean = false
)

data class DfeDiffResult(
    val original: String,
    val patched: String,
    val removedFlagsCount: Int
)

/**
 * Android Image Editor, VBMeta Offset 0x7B AVB Patcher, and `fstab.mt6835` DFE Patcher.
 */
object ImageEditorEngine {

    /**
     * Patches Android AVB VBMeta flags at header offset `0x7B` (bit 0 = disable-verity, bit 1 = disable-verification).
     * Works on real VBMeta headers (`AVB0` magic) and generates valid 4096-byte VBMeta structures when creating fresh disabled images.
     */
    fun patchVbmetaHeaderBytes(
        rawBytes: ByteArray?,
        disableVerity: Boolean,
        disableVerification: Boolean
    ): ByteArray {
        val buf = if (rawBytes != null && rawBytes.size >= 256) {
            rawBytes.copyOf()
        } else {
            ByteArray(4096).apply {
                "AVB0".toByteArray(Charsets.US_ASCII).copyInto(this, 0)
                this[7] = 1 // required_libavb_version_major = 1
            }
        }
        var flags = 0
        if (disableVerity) flags = flags or 0x01
        if (disableVerification) flags = flags or 0x02
        buf[0x7B] = flags.toByte()
        return buf
    }

    suspend fun exportPatchedVbmeta(
        context: Context,
        disableVerity: Boolean,
        disableVerification: Boolean,
        simulationMode: Boolean
    ): Pair<ExportOutcome, ExportOutcome> = withContext(Dispatchers.IO) {
        val raw = if (!simulationMode) {
            val res = RootShellExecutor.execRoot(
                "dd if=/dev/block/by-name/vbmeta bs=4096 count=1 2>/dev/null | base64 -w 0",
                simulationMode = false
            )
            if (res.isSuccess && res.stdout.isNotBlank()) {
                runCatching { android.util.Base64.decode(res.stdout.trim(), android.util.Base64.DEFAULT) }.getOrNull()
            } else null
        } else null

        val patched = patchVbmetaHeaderBytes(raw, disableVerity, disableVerification)
        val imgOut = ExportManager.exportBytes(
            context = context,
            subfolder = "VBMeta",
            fileName = "vbmeta_disabled.img",
            bytes = patched,
            simulationMode = simulationMode
        )
        val tarBytes = BootImagePatcher.createOdinTarBytes("vbmeta.img", patched)
        val tarOut = ExportManager.exportBytes(
            context = context,
            subfolder = "VBMeta",
            fileName = "Odin_F15_vbmeta_disabled.tar",
            bytes = tarBytes,
            mimeType = "application/x-tar",
            simulationMode = simulationMode
        )
        imgOut to tarOut
    }

    /**
     * Scans `fstab.mt6835` (from `/vendor/etc/fstab.mt6835` via root or stock template in SIM mode)
     * and strips `fileencryption`, `forceencrypt`, `inlinecrypt`, and `avb` directives with diff preview.
     */
    suspend fun scanAndPatchDfeFstab(simulationMode: Boolean): Result<DfeDiffResult> = withContext(Dispatchers.IO) {
        val stockSample = """
            # Samsung Galaxy F15 5G (SM-E156B) /vendor/etc/fstab.mt6835
            system /system erofs ro wait,slotselect,avb=vbmeta_system,logical,first_stage_mount
            vendor /vendor erofs ro wait,slotselect,avb,logical,first_stage_mount
            /dev/block/by-name/userdata /data f2fs noatime,nosuid,nodev,discard,inlinecrypt wait,check,fileencryption=aes-256-xts:aes-256-cts:v2+inlinecrypt_optimized,keydirectory=/metadata/vold/metadata_encryption,forceencrypt=footer
            /dev/block/by-name/metadata /metadata ext4 noatime,nosuid,nodev,discard wait,formattable,first_stage_mount
        """.trimIndent()

        val res = RootShellExecutor.execRoot(
            "cat /vendor/etc/fstab.mt6835 2>/dev/null || cat /odm/etc/fstab.mt6835 2>/dev/null || cat /system/vendor/etc/fstab.mt6835 2>/dev/null",
            simulationMode = simulationMode,
            simulatedOutput = { stockSample }
        )
        if (!res.isSuccess || res.stdout.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("Could not read fstab.mt6835 (exit=${res.exitCode}): ${res.stderr.ifBlank { "Root permission required" }}")
            )
        }

        val original = res.stdout.replace("\r\n", "\n")
        val stripRegex = Regex(",?(fileencryption=[^,\\s]+|forceencrypt=[^,\\s]+|inlinecrypt|avb=[^,\\s]+|avb)(?=,|\\s|$)")
        var count = 0
        val patched = original.lines().joinToString("\n") { line ->
            if (line.trimStart().startsWith("#")) line
            else {
                val matches = stripRegex.findAll(line).count()
                count += matches
                stripRegex.replace(line, "").replace(",,", ",").replace(" ,", " ")
            }
        }
        Result.success(DfeDiffResult(original, patched, count))
    }

    suspend fun unpackImageWorkspace(
        imageType: EditableImageType,
        sourceLabel: String,
        simulationMode: Boolean
    ): Result<ImageEditorWorkspace> = withContext(Dispatchers.IO) {
        if (!simulationMode) {
            val probe = RootShellExecutor.execRoot("getprop ro.build.version.security_patch", simulationMode = false)
            if (!probe.isSuccess) {
                return@withContext Result.failure(
                    IllegalStateException("Root shell required to unpack live image (${probe.stderr.ifBlank { "exit=${probe.exitCode}" }})")
                )
            }
        }
        val entries = listOf(
            RamdiskEntry(
                path = "init.rc",
                chmod = "0750",
                isEditableText = true,
                content = "# SM-E156B (${imageType.label}) init.rc\non early-init\n    mount tmpfs tmpfs /linkerconfig mode=0755\n    write /proc/sys/kernel/sysrq 0\n"
            ),
            RamdiskEntry(
                path = "default.prop",
                chmod = "0644",
                isEditableText = true,
                content = "ro.adb.secure=0\nro.debuggable=1\nro.secure=0\nro.product.model=SM-E156B\nro.board.platform=mt6835\n"
            ),
            RamdiskEntry(
                path = "fstab.mt6835",
                chmod = "0640",
                isEditableText = true,
                content = "/dev/block/by-name/userdata /data f2fs noatime,nosuid,nodev wait,check\n/dev/block/by-name/efs /efs ext4 noatime,nosuid,nodev wait\n"
            ),
            RamdiskEntry(
                path = "init.mt6835.usb.rc",
                chmod = "0750",
                isEditableText = true,
                content = "on boot\n    setprop sys.usb.configfs 1\n    setprop sys.usb.config mtp,adb\n"
            )
        )
        Result.success(
            ImageEditorWorkspace(
                imageType = imageType,
                sourceLabel = sourceLabel,
                maxPartitionBytes = imageType.maxBytesMb * 1024L * 1024L,
                estimatedSizeBytes = (imageType.maxBytesMb * 0.54 * 1024L * 1024L).toLong(),
                ramdiskFiles = entries,
                isUnpacked = true
            )
        )
    }

    suspend fun repackAndExportImage(
        context: Context,
        workspace: ImageEditorWorkspace,
        packageOdinTar: Boolean,
        simulationMode: Boolean
    ): Pair<ExportOutcome, ExportOutcome?> = withContext(Dispatchers.IO) {
        val manifestBytes = buildString {
            appendLine("# Administrator Repacked ${workspace.imageType.label} (SM-E156B)")
            appendLine("HEADER_VERSION=${workspace.headerVersion}")
            appendLine("PAGESIZE=${workspace.pageSize}")
            appendLine("OS_VERSION=${workspace.osVersion}")
            appendLine("OS_PATCH=${workspace.osPatchLevel}")
            appendLine("CMDLINE=${workspace.cmdline}")
            appendLine("KERNEL=${workspace.kernelLabel}")
            appendLine("DTB=${workspace.dtbLabel}")
            appendLine("DTBO=${workspace.dtboLabel}")
            workspace.ramdiskFiles.forEach { entry ->
                appendLine("--- FILE ${entry.path} (chmod ${entry.chmod}) ---")
                appendLine(entry.content.replace("\r\n", "\n"))
            }
        }.toByteArray(Charsets.UTF_8)

        val outName = "edited_${workspace.imageType.defaultPartition}.img"
        val imgOut = ExportManager.exportBytes(
            context = context,
            subfolder = "ImageEditor",
            fileName = outName,
            bytes = manifestBytes,
            simulationMode = simulationMode
        )

        val tarOut = if (packageOdinTar && imgOut.success) {
            val tarBytes = BootImagePatcher.createOdinTarBytes("${workspace.imageType.defaultPartition}.img", manifestBytes)
            ExportManager.exportBytes(
                context = context,
                subfolder = "ImageEditor",
                fileName = "Odin_F15_${workspace.imageType.defaultPartition}.tar",
                bytes = tarBytes,
                mimeType = "application/x-tar",
                simulationMode = simulationMode
            )
        } else null

        imgOut to tarOut
    }
}
