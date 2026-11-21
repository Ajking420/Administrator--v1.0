package com.example.core.diagnostics

import android.content.Context
import com.example.core.shell.RootShellExecutor
import com.example.core.storage.ExportManager
import com.example.core.storage.ExportOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FaultCategory(val label: String) {
    KERNEL_PANIC("Kernel Panic"),
    SELINUX_AVC("SELinux AVC Denial"),
    LINKER_APEX_VECTOR("Linker / APEX / Zygisk / Vector"),
    MODULE_KPM("Module / KPM Fault"),
    INFO("Diagnostic Log")
}

data class CrashLogLine(
    val index: Int,
    val raw: String,
    val category: FaultCategory
)

data class CrashAnalysisReport(
    val lines: List<CrashLogLine>,
    val panicCount: Int,
    val avcCount: Int,
    val linkerVectorCount: Int,
    val moduleKpmCount: Int,
    val generatedSepolicyRules: String,
    val rawCombinedText: String,
    val error: String? = null
)

/**
 * Crash Analyzer Engine pulling pstore (`console-ramoops*`), `/proc/last_kmsg`, `dmesg`,
 * and `logcat -b crash -d`, categorizing faults and converting `avc: denied` entries
 * into valid Magisk/KSU `sepolicy.rule` directives.
 */
object CrashAnalyzerEngine {

    private val SIMULATED_CRASH_DUMP = """
        [    2.814201] mt6835-pstore: recovered console-ramoops-0 from warm reset
        [    3.102944] Kernel panic - not syncing: Attempted to kill init! exitcode=0x0000000b
        [    3.102990] CPU: 6 PID: 1 Comm: init Tainted: G        OE     5.15.149-mt6835 #1
        [    4.442110] audit: type=1400 audit(17240012.31:88): avc: denied { read write } for pid=612 comm="magiskd" name="zram0" dev="sysfs" scontext=u:r:magisk:s0 tcontext=u:object_r:sysfs_zram:s0 tclass=file permissive=0
        [    4.519002] audit: type=1400 audit(17240012.40:91): avc: denied { execute } for pid=804 comm="zygiskd64" name="libvector_hook.so" scontext=u:r:zygote:s0 tcontext=u:object_r:adb_data_file:s0 tclass=file permissive=0
        [    5.012883] linker64: CANNOT LINK EXECUTABLE "/apex/com.android.runtime/bin/linker64": library "libxposed_art.so" not found in Vector namespace
        [    5.331040] ReZygisk: dlopen failed for /data/adb/modules/oneui_s24_port/zygisk/arm64-v8a.so (APEX ABI mismatch)
        [    5.890412] KPM: apatch_kpm_hide_mount returned -EINVAL on mt6835_emmc_blk
        [    6.112004] logcat -b crash: FATAL EXCEPTION: VectorService (libxposed API 102)
    """.trimIndent()

    suspend fun pullAndAnalyzeLogs(simulationMode: Boolean): CrashAnalysisReport = withContext(Dispatchers.IO) {
        val cmd = """
            echo "=== PSTORE CONSOLE-RAMOOPS ==="
            cat /sys/fs/pstore/console-ramoops* 2>/dev/null | tail -n 120
            echo "=== LAST_KMSG ==="
            cat /proc/last_kmsg 2>/dev/null | tail -n 80
            echo "=== DMESG (FAULTS) ==="
            dmesg 2>/dev/null | grep -iE 'panic|oops|avc: denied|zygisk|vector|kpm|linker|apex|magisk|segfault' | tail -n 120
            echo "=== LOGCAT CRASH BUFFER ==="
            logcat -b crash -d -t 80 2>/dev/null
        """.trimIndent()

        val res = RootShellExecutor.execRoot(
            command = cmd,
            simulationMode = simulationMode,
            timeoutSeconds = 20,
            simulatedOutput = { SIMULATED_CRASH_DUMP }
        )

        if (!res.isSuccess && !simulationMode) {
            return@withContext CrashAnalysisReport(
                lines = listOf(CrashLogLine(0, res.stderr.ifBlank { "Root access denied (exit=${res.exitCode})" }, FaultCategory.KERNEL_PANIC)),
                panicCount = 0,
                avcCount = 0,
                linkerVectorCount = 0,
                moduleKpmCount = 0,
                generatedSepolicyRules = "",
                rawCombinedText = res.combinedOutput,
                error = "Failed to pull kernel/pstore logs (exit=${res.exitCode}): ${res.stderr}"
            )
        }

        val rawLines = res.stdout.lines().filter { it.isNotBlank() }
        var panicCount = 0
        var avcCount = 0
        var linkerCount = 0
        var modCount = 0

        val categorized = rawLines.mapIndexed { idx, line ->
            val lower = line.lowercase()
            val cat = when {
                lower.contains("kernel panic") || lower.contains("bug:") || lower.contains("oops") || lower.contains("fatal exception") -> {
                    panicCount++; FaultCategory.KERNEL_PANIC
                }
                lower.contains("avc: denied") -> {
                    avcCount++; FaultCategory.SELINUX_AVC
                }
                lower.contains("linker") || lower.contains("apex") || lower.contains("zygisk") || lower.contains("vector") || lower.contains("libxposed") -> {
                    linkerCount++; FaultCategory.LINKER_APEX_VECTOR
                }
                lower.contains("kpm") || lower.contains("/data/adb/modules") || lower.contains("apatch") || lower.contains("magisk") -> {
                    modCount++; FaultCategory.MODULE_KPM
                }
                else -> FaultCategory.INFO
            }
            CrashLogLine(idx, line, cat)
        }

        val rules = convertAvcLinesToSepolicyRules(rawLines)
        CrashAnalysisReport(
            lines = categorized,
            panicCount = panicCount,
            avcCount = avcCount,
            linkerVectorCount = linkerCount,
            moduleKpmCount = modCount,
            generatedSepolicyRules = rules,
            rawCombinedText = res.stdout
        )
    }

    /**
     * Parses `avc: denied { perm } ... scontext=u:r:<src>:s0 tcontext=u:object_r:<tgt>:s0 tclass=<cls>`
     * into valid Magisk/KSU `allow <src> <tgt> <cls> { <perm> }` rules.
     */
    fun convertAvcLinesToSepolicyRules(lines: List<String>): String {
        val permRegex = Regex("""avc:\s+denied\s+\{\s*([^}]+)\s*\}""")
        val scontextRegex = Regex("""scontext=[^:\s]+:[^:\s]+:([^:\s]+)""")
        val tcontextRegex = Regex("""tcontext=[^:\s]+:[^:\s]+:([^:\s]+)""")
        val tclassRegex = Regex("""tclass=([^\s]+)""")

        val uniqueRules = LinkedHashSet<String>()
        for (line in lines) {
            if (!line.contains("avc: denied")) continue
            val perms = permRegex.find(line)?.groupValues?.get(1)?.trim() ?: continue
            val src = scontextRegex.find(line)?.groupValues?.get(1)?.trim() ?: continue
            val tgt = tcontextRegex.find(line)?.groupValues?.get(1)?.trim() ?: continue
            val cls = tclassRegex.find(line)?.groupValues?.get(1)?.trim() ?: continue
            uniqueRules += "allow $src $tgt $cls { $perms }"
        }
        if (uniqueRules.isEmpty()) {
            return "# No avc: denied entries detected in current log capture.\n"
        }
        return buildString {
            appendLine("# Auto-generated by Administrator Crash Analyzer (SM-E156B)")
            uniqueRules.forEach { appendLine(it) }
        }
    }

    suspend fun exportCrashReportTxt(
        context: Context,
        report: CrashAnalysisReport,
        simulationMode: Boolean
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val payload = buildString {
            appendLine("=== Administrator Crash Analyzer Report (SM-E156B / mt6835) ===")
            appendLine("Panics: ${report.panicCount} | AVC Denials: ${report.avcCount} | Linker/Vector: ${report.linkerVectorCount} | Module/KPM: ${report.moduleKpmCount}")
            appendLine()
            appendLine("--- GENERATED SEPOLICY.RULE ---")
            appendLine(report.generatedSepolicyRules)
            appendLine("--- RAW LOG CAPTURE ---")
            appendLine(report.rawCombinedText)
        }
        ExportManager.exportBytes(
            context = context,
            subfolder = "CrashLogs",
            fileName = "f15_crash_report_${System.currentTimeMillis() / 1000}.txt",
            bytes = payload.replace("\r\n", "\n").toByteArray(Charsets.UTF_8),
            mimeType = "text/plain",
            simulationMode = simulationMode
        )
    }
}
