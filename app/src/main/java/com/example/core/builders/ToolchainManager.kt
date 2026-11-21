package com.example.core.builders

import android.content.Context
import android.net.Uri
import com.example.core.shell.RootShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class LocalBinaryStatus(
    val name: String,
    val purpose: String,
    val isReady: Boolean,
    val resolvedPath: String,
    val downloadHintUrl: String
)

/**
 * 8-Binary Local Toolchain Readiness Checker & Provisioning Manager:
 * Detects binaries across app nativeLibraryDir, imported toolchain dir, and /data/adb,
 * plus supports manual SAF import or direct HTTPS ARM64 download.
 */
object ToolchainManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private data class BinarySpec(
        val name: String,
        val nativeLibName: String,
        val purpose: String,
        val adbCandidates: List<String>,
        val downloadUrl: String
    )

    private val SPECS = listOf(
        BinarySpec(
            name = "magiskboot",
            nativeLibName = "libmagiskboot.so",
            purpose = "Unpack/repack boot, init_boot, recovery, vendor_boot",
            adbCandidates = listOf("/data/adb/magisk/magiskboot", "/data/adb/ksu/bin/magiskboot"),
            downloadUrl = "https://github.com/topjohnwu/Magisk/releases/latest"
        ),
        BinarySpec(
            name = "kptools",
            nativeLibName = "libkptools.so",
            purpose = "APatch kernel patching & KPM injection",
            adbCandidates = listOf("/data/adb/ap/bin/kptools"),
            downloadUrl = "https://github.com/bmax121/KernelPatch/releases/latest"
        ),
        BinarySpec(
            name = "lpunpack",
            nativeLibName = "liblpunpack.so",
            purpose = "Extract dynamic partitions from Samsung super.img",
            adbCandidates = listOf("/data/adb/administrator/bin/lpunpack", "/system/bin/lpunpack"),
            downloadUrl = "https://github.com/LonelyFool/lpunpack_and_lpmake/releases"
        ),
        BinarySpec(
            name = "simg2img",
            nativeLibName = "libsimg2img.so",
            purpose = "Convert sparse Android images to raw ext4/super",
            adbCandidates = listOf("/data/adb/administrator/bin/simg2img", "/system/bin/simg2img"),
            downloadUrl = "https://github.com/anestisb/android-simg2img/releases"
        ),
        BinarySpec(
            name = "extract.erofs",
            nativeLibName = "libextract_erofs.so",
            purpose = "Extract One UI EROFS system/vendor/product images",
            adbCandidates = listOf("/data/adb/administrator/bin/extract.erofs"),
            downloadUrl = "https://github.com/sekaiacg/erofs-utils/releases"
        ),
        BinarySpec(
            name = "mkfs.erofs",
            nativeLibName = "libmkfs_erofs.so",
            purpose = "Rebuild compressed EROFS partitions for SM-E156B",
            adbCandidates = listOf("/data/adb/administrator/bin/mkfs.erofs"),
            downloadUrl = "https://github.com/sekaiacg/erofs-utils/releases"
        ),
        BinarySpec(
            name = "busybox",
            nativeLibName = "libbusybox.so",
            purpose = "POSIX tar/gzip/cpio/sed/awk applets for offline builds",
            adbCandidates = listOf("/data/adb/magisk/busybox", "/data/adb/ksu/bin/busybox", "/data/adb/ap/bin/busybox"),
            downloadUrl = "https://github.com/topjohnwu/ndk-busybox/releases"
        ),
        BinarySpec(
            name = "zip",
            nativeLibName = "libzip.so",
            purpose = "Package flashable recovery/ROM/module ZIP archives",
            adbCandidates = listOf("/data/adb/administrator/bin/zip", "/system/bin/zip"),
            downloadUrl = "https://github.com/Zackptg5/Cross-Compiled-Binaries-Android/tree/master/zip"
        )
    )

    suspend fun inspectToolchain(
        context: Context,
        simulationMode: Boolean
    ): List<LocalBinaryStatus> = withContext(Dispatchers.IO) {
        val nativeDir = context.applicationInfo.nativeLibraryDir
        val importedDir = File(context.filesDir, "toolchain").apply { mkdirs() }

        SPECS.map { spec ->
            val nativeFile = File(nativeDir, spec.nativeLibName)
            val importedFile = File(importedDir, spec.name)

            var resolved = ""
            var ready = false

            when {
                nativeFile.exists() -> {
                    ready = true
                    resolved = nativeFile.absolutePath
                }
                importedFile.exists() && importedFile.length() > 0L -> {
                    ready = true
                    resolved = importedFile.absolutePath
                }
                simulationMode -> {
                    // Built-in ZIP + BusyBox + Magiskboot simulated ready for preview, others clearly shown
                    ready = spec.name in setOf("magiskboot", "kptools", "busybox", "zip", "lpunpack", "simg2img")
                    resolved = if (ready) "[SIM] /data/adb/administrator/bin/${spec.name}" else "Not installed"
                }
                else -> {
                    val checkCmd = spec.adbCandidates.joinToString(" || ") { path ->
                        "[ -x ${RootShellExecutor.shellQuote(path)} ] && echo ${RootShellExecutor.shellQuote(path)}"
                    }
                    val res = RootShellExecutor.execRoot(checkCmd, simulationMode = false, timeoutSeconds = 5)
                    if (res.isSuccess && res.stdout.isNotBlank()) {
                        ready = true
                        resolved = res.stdout.lines().first().trim()
                    } else {
                        resolved = "Not detected (import or download)"
                    }
                }
            }

            LocalBinaryStatus(
                name = spec.name,
                purpose = spec.purpose,
                isReady = ready,
                resolvedPath = resolved,
                downloadHintUrl = spec.downloadUrl
            )
        }
    }

    suspend fun importBinaryFromUri(
        context: Context,
        toolName: String,
        uri: Uri
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = SPECS.firstOrNull { it.name == toolName }?.name
                ?: error("Unknown toolchain binary: $toolName")
            val dir = File(context.filesDir, "toolchain").apply { mkdirs() }
            val dst = File(dir, safeName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                dst.outputStream().use { out -> input.copyTo(out) }
            } ?: error("Cannot read selected binary URI")
            dst.setExecutable(true, false)
            dst.absolutePath
        }
    }

    suspend fun downloadBinaryFromUrl(
        context: Context,
        toolName: String,
        httpsUrl: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val safeName = SPECS.firstOrNull { it.name == toolName }?.name
                ?: error("Unknown toolchain binary: $toolName")
            if (!httpsUrl.startsWith("https://")) error("Binary download URL must use https://")
            val req = Request.Builder()
                .url(httpsUrl)
                .header("User-Agent", "Administrator-SM-E156B/1.0")
                .build()
            val dir = File(context.filesDir, "toolchain").apply { mkdirs() }
            val dst = File(dir, safeName)
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed (HTTP ${resp.code})")
                val body = resp.body ?: error("Empty download body")
                body.byteStream().use { input ->
                    dst.outputStream().use { out -> input.copyTo(out) }
                }
            }
            dst.setExecutable(true, false)
            dst.absolutePath
        }
    }
}
