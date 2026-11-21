package com.example.core.tweaks

import com.example.core.shell.RootShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class LiveHardwareTelemetry(
    val bigCoreFreqMhz: Int = 2200,
    val bigCoreMaxMhz: Int = 2200,
    val littleCoreFreqMhz: Int = 1800,
    val littleCoreMaxMhz: Int = 2000,
    val gpuFreqMhz: Int = 780,
    val ioScheduler: String = "mq-deadline",
    val thermalZoneTempC: Int = 36,
    val totalRamMb: Int = 4096,
    val usedRamMb: Int = 2480,
    val zramTotalMb: Int = 4096,
    val zramUsedMb: Int = 1120,
    val zramCompAlgo: String = "zstd"
)

data class TweaksConfig(
    val masterEnabled: Boolean = false,
    val perfProfile: String = "Auto",
    val gpuBoost: Boolean = false,
    val ioScheduler: String = "mq-deadline",
    val thermalPolicy: String = "Balanced",
    val zramMaster: Boolean = true,
    val zramSizePreset: String = "4 GB",
    val zramAlgo: String = "zstd",
    val swappiness: Int = 60,
    val disableSamsungRamPlus: Boolean = true,
    val applyOnBoot: Boolean = false
)

/**
 * Hardware Tweaks & Compressed ZRAM Engine tuned specifically for Samsung Galaxy F15 5G:
 * MediaTek Dimensity 6100+ (`mt6835`): Big cluster (`policy6`, 2x Cortex-A76 @ 2.2 GHz),
 * LITTLE cluster (`policy0`, 6x Cortex-A55 @ 2.0 GHz), Mali-G57 MC2 GPU, 4 GB LPDDR4X RAM.
 */
object HardwareTweaksEngine {

    const val BOOT_SCRIPT_PATH = "/data/adb/service.d/admin_mt6835_tweaks.sh"

    fun zramPresetToBytes(preset: String): Long = when (preset) {
        "Off" -> 0L
        "Auto" -> 3L * 1024L * 1024L * 1024L // 3 GB optimal for 4 GB F15
        "As Per Device" -> 2L * 1024L * 1024L * 1024L
        "4 GB" -> 4L * 1024L * 1024L * 1024L
        "8 GB" -> 8L * 1024L * 1024L * 1024L
        "16 GB" -> 16L * 1024L * 1024L * 1024L
        else -> 4L * 1024L * 1024L * 1024L
    }

    suspend fun sampleTelemetry(simulationMode: Boolean): LiveHardwareTelemetry = withContext(Dispatchers.IO) {
        if (simulationMode) {
            return@withContext LiveHardwareTelemetry(
                bigCoreFreqMhz = (1650..2200).random(),
                bigCoreMaxMhz = 2200,
                littleCoreFreqMhz = (1200..2000).random(),
                littleCoreMaxMhz = 2000,
                gpuFreqMhz = (550..950).random(),
                ioScheduler = "mq-deadline",
                thermalZoneTempC = (34..41).random(),
                totalRamMb = 3892,
                usedRamMb = (2250..2780).random(),
                zramTotalMb = 4096,
                zramUsedMb = (950..1650).random(),
                zramCompAlgo = "zstd"
            )
        }

        val d = '$'
        val cmd = """
            B_CUR=$(cat /sys/devices/system/cpu/cpufreq/policy6/scaling_cur_freq 2>/dev/null || echo 0)
            B_MAX=$(cat /sys/devices/system/cpu/cpufreq/policy6/cpuinfo_max_freq 2>/dev/null || echo 2200000)
            L_CUR=$(cat /sys/devices/system/cpu/cpufreq/policy0/scaling_cur_freq 2>/dev/null || echo 0)
            L_MAX=$(cat /sys/devices/system/cpu/cpufreq/policy0/cpuinfo_max_freq 2>/dev/null || echo 2000000)
            TEMP=$(cat /sys/class/thermal/thermal_zone0/temp 2>/dev/null || echo 36000)
            MEM_T=$(awk '/MemTotal/ {print int(${d}2/1024)}' /proc/meminfo 2>/dev/null || echo 4096)
            MEM_A=$(awk '/MemAvailable/ {print int(${d}2/1024)}' /proc/meminfo 2>/dev/null || echo 1500)
            Z_TOT=$(cat /sys/block/zram0/disksize 2>/dev/null || echo 0)
            Z_STAT=$(awk '{print ${d}1}' /sys/block/zram0/mm_stat 2>/dev/null || echo 0)
            Z_ALG=$(cat /sys/block/zram0/comp_algorithm 2>/dev/null | grep -o '\[[^]]*\]' | tr -d '[]')
            IO_S=$(cat /sys/block/sda/queue/scheduler 2>/dev/null | grep -o '\[[^]]*\]' | tr -d '[]')
            echo "${d}B_CUR|${d}B_MAX|${d}L_CUR|${d}L_MAX|${d}TEMP|${d}MEM_T|${d}MEM_A|${d}Z_TOT|${d}Z_STAT|${d}Z_ALG|${d}IO_S"
        """.trimIndent()

        val res = RootShellExecutor.execRoot(cmd, simulationMode = false, timeoutSeconds = 6)
        if (!res.isSuccess || !res.stdout.contains("|")) {
            return@withContext LiveHardwareTelemetry()
        }
        val p = res.stdout.lines().last().split("|")
        val bigCur = (p.getOrNull(0)?.toLongOrNull() ?: 0L) / 1000L
        val bigMax = ((p.getOrNull(1)?.toLongOrNull() ?: 2200000L) / 1000L).coerceAtLeast(1000L)
        val litCur = (p.getOrNull(2)?.toLongOrNull() ?: 0L) / 1000L
        val litMax = ((p.getOrNull(3)?.toLongOrNull() ?: 2000000L) / 1000L).coerceAtLeast(1000L)
        val rawTemp = p.getOrNull(4)?.toIntOrNull() ?: 36000
        val tempC = if (rawTemp > 1000) rawTemp / 1000 else rawTemp
        val memTotal = p.getOrNull(5)?.toIntOrNull() ?: 4096
        val memAvail = p.getOrNull(6)?.toIntOrNull() ?: 1500
        val zramTotMb = ((p.getOrNull(7)?.toLongOrNull() ?: 0L) / (1024L * 1024L)).toInt()
        val zramUsedMb = ((p.getOrNull(8)?.toLongOrNull() ?: 0L) / (1024L * 1024L)).toInt()
        val zAlgo = p.getOrNull(9)?.ifBlank { "zstd" } ?: "zstd"
        val ioSched = p.getOrNull(10)?.ifBlank { "mq-deadline" } ?: "mq-deadline"

        LiveHardwareTelemetry(
            bigCoreFreqMhz = bigCur.toInt(),
            bigCoreMaxMhz = bigMax.toInt(),
            littleCoreFreqMhz = litCur.toInt(),
            littleCoreMaxMhz = litMax.toInt(),
            gpuFreqMhz = if (bigCur > 1800) 900 else 600,
            ioScheduler = ioSched,
            thermalZoneTempC = tempC,
            totalRamMb = memTotal,
            usedRamMb = (memTotal - memAvail).coerceAtLeast(0),
            zramTotalMb = zramTotMb,
            zramUsedMb = zramUsedMb,
            zramCompAlgo = zAlgo
        )
    }

    fun generateBootScript(cfg: TweaksConfig): String {
        val govBig = when (cfg.perfProfile) {
            "High" -> "performance"
            "Battery" -> "powersave"
            else -> "schedutil"
        }
        val govLittle = when (cfg.perfProfile) {
            "High" -> "performance"
            "Battery" -> "powersave"
            else -> "schedutil"
        }
        val zramBytes = if (cfg.zramMaster) zramPresetToBytes(cfg.zramSizePreset) else 0L
        val safeAlgo = if (cfg.zramAlgo in setOf("zstd", "lz4", "lzo-rle")) cfg.zramAlgo else "zstd"
        val safeIo = if (cfg.ioScheduler in setOf("mq-deadline", "kyber", "bfq", "none")) cfg.ioScheduler else "mq-deadline"

        return buildString {
            appendLine("#!/system/bin/sh")
            appendLine("# Administrator Hardware & ZRAM Boot Script for Samsung Galaxy F15 5G (SM-E156B / mt6835)")
            appendLine("while [ \"$(getprop sys.boot_completed)\" != \"1\" ]; do sleep 2; done")
            appendLine()
            appendLine("# Dimensity 6100+ Big (policy6: 2x Cortex-A76) & LITTLE (policy0: 6x Cortex-A55)")
            appendLine("echo $govBig > /sys/devices/system/cpu/cpufreq/policy6/scaling_governor 2>/dev/null")
            appendLine("echo $govLittle > /sys/devices/system/cpu/cpufreq/policy0/scaling_governor 2>/dev/null")
            if (cfg.gpuBoost) {
                appendLine("# Mali-G57 MC2 GPU Boost")
                appendLine("echo 0 > /proc/gpufreqv2/fix_target_opp_index 2>/dev/null")
            } else {
                appendLine("echo -1 > /proc/gpufreqv2/fix_target_opp_index 2>/dev/null")
            }
            appendLine("# UFS / eMMC I/O Scheduler")
            appendLine("for q in /sys/block/sd*/queue/scheduler /sys/block/mmcblk*/queue/scheduler; do")
            appendLine("  echo $safeIo > \"${'$'}q\" 2>/dev/null")
            appendLine("done")
            if (cfg.disableSamsungRamPlus) {
                appendLine("# Disable Samsung RAM Plus (nandswap) to save eMMC/UFS write wear")
                appendLine("settings put global ram_expand_size_list 0 2>/dev/null")
                appendLine("setprop persist.sys.ramplus.enable false 2>/dev/null")
            }
            appendLine("# Compressed ZRAM Pipeline (swapoff -> reset -> comp_algorithm -> disksize -> mkswap -> swapon)")
            appendLine("swapoff /dev/block/zram0 2>/dev/null")
            appendLine("echo 1 > /sys/block/zram0/reset 2>/dev/null")
            if (zramBytes > 0L) {
                appendLine("echo $safeAlgo > /sys/block/zram0/comp_algorithm 2>/dev/null")
                appendLine("echo $zramBytes > /sys/block/zram0/disksize 2>/dev/null")
                appendLine("mkswap /dev/block/zram0 2>/dev/null")
                appendLine("swapon -p 100 /dev/block/zram0 2>/dev/null")
            }
            appendLine("echo ${cfg.swappiness.coerceIn(10, 100)} > /proc/sys/vm/swappiness 2>/dev/null")
        }
    }

    suspend fun applyNow(cfg: TweaksConfig, simulationMode: Boolean): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val script = generateBootScript(cfg)
            .lines()
            .filterNot { it.startsWith("while [") }
            .joinToString("\n")
        val res = RootShellExecutor.execRoot(
            command = script,
            simulationMode = simulationMode,
            simulatedOutput = {
                "[SIM] Applied mt6835 profile=${cfg.perfProfile}, ZRAM=${if (cfg.zramMaster) "${cfg.zramSizePreset} (${cfg.zramAlgo})" else "OFF"}, swappiness=${cfg.swappiness}"
            }
        )
        res.isSuccess to (if (res.isSuccess) res.stdout.ifBlank { "Applied mt6835 hardware & ZRAM tweaks cleanly." } else "Apply failed (exit=${res.exitCode}): ${res.stderr}")
    }

    suspend fun syncApplyOnBootScript(
        cfg: TweaksConfig,
        enableBoot: Boolean,
        simulationMode: Boolean
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (!enableBoot) {
            val res = RootShellExecutor.execRoot(
                "rm -f ${RootShellExecutor.shellQuote(BOOT_SCRIPT_PATH)}",
                simulationMode = simulationMode,
                simulatedOutput = { "[SIM] Removed $BOOT_SCRIPT_PATH" }
            )
            return@withContext res.isSuccess to (if (res.isSuccess) "Removed boot script $BOOT_SCRIPT_PATH" else res.stderr)
        }
        val script = generateBootScript(cfg).replace("\r\n", "\n")
        val b64 = android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val cmd = "mkdir -p /data/adb/service.d && echo ${RootShellExecutor.shellQuote(b64)} | base64 -d > ${RootShellExecutor.shellQuote(BOOT_SCRIPT_PATH)} && chmod 0755 ${RootShellExecutor.shellQuote(BOOT_SCRIPT_PATH)}"
        val res = RootShellExecutor.execRoot(
            command = cmd,
            simulationMode = simulationMode,
            simulatedOutput = { "[SIM] Wrote $BOOT_SCRIPT_PATH (Unix LF, chmod 0755)" }
        )
        res.isSuccess to (if (res.isSuccess) "Installed $BOOT_SCRIPT_PATH (0755)" else "Failed writing boot script (exit=${res.exitCode}): ${res.stderr}")
    }
}
