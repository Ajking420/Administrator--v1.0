package com.example.core.shell

import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val isSimulated: Boolean = false
) {
    val isSuccess: Boolean get() = exitCode == 0
    val combinedOutput: String
        get() = buildString {
            if (stdout.isNotBlank()) append(stdout.trimEnd())
            if (stderr.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(stderr.trimEnd())
            }
        }
}

sealed interface RootAccessState {
    data object Unknown : RootAccessState
    data object Checking : RootAccessState
    data class Granted(
        val idOutput: String,
        val suManagerHint: String = "Hybrid Root Core"
    ) : RootAccessState
    data class Denied(
        val exitCode: Int,
        val stderr: String,
        val reason: String
    ) : RootAccessState
    data class Simulated(val reason: String = "Simulation Mode enabled") : RootAccessState
}

/**
 * Real Root Shell Executor.
 *
 * Strictly adheres to Critical Engineering Rule #1:
 * - NEVER detects root via static path checks like File("/system/bin/su").exists() or `which su`.
 * - Detects & triggers Magisk/KSU/APatch Superuser prompts ONLY by running
 *   `ProcessBuilder("su", "-c", "id")` on Dispatchers.IO and verifying stdout contains `uid=0`.
 * - NEVER silently falls back to mock/fake "Complete" results when simulationMode == false.
 */
object RootShellExecutor {

    private val _rootState = MutableStateFlow<RootAccessState>(RootAccessState.Unknown)
    val rootState: StateFlow<RootAccessState> = _rootState.asStateFlow()

    val F15_ALLOWED_PARTITIONS = setOf(
        "boot", "init_boot", "vendor_boot", "recovery", "dtbo",
        "vbmeta", "vbmeta_system", "vbmeta_vendor",
        "super", "prism", "optics", "persist",
        "efs", "sec_efs", "nvram", "nvdata", "nvcfg", "protect1", "protect2",
        "system", "vendor"
    )

    fun isRunningOnEmulator(): Boolean {
        val hw = Build.HARDWARE.lowercase()
        val fp = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        return hw.contains("ranchu") ||
            hw.contains("goldfish") ||
            fp.contains("generic") ||
            fp.contains("emulator") ||
            model.contains("sdk_gphone")
    }

    fun shellQuote(raw: String): String {
        return "'" + raw.replace("'", "'\\''") + "'"
    }

    fun isValidPartitionName(name: String): Boolean {
        return name in F15_ALLOWED_PARTITIONS
    }

    fun isValidPropKey(key: String): Boolean {
        return key.matches(Regex("^[a-zA-Z0-9._-]{2,120}$"))
    }

    fun isValidChmodMode(mode: String): Boolean {
        return mode.matches(Regex("^0?[0-7]{3,4}$"))
    }

    /**
     * Triggers Magisk/KSU/APatch Superuser popup directly via `ProcessBuilder("su", "-c", "id")`.
     * Updates rootState as single source of truth.
     */
    suspend fun probeRoot(simulationMode: Boolean): RootAccessState = withContext(Dispatchers.IO) {
        if (simulationMode) {
            val sim = RootAccessState.Simulated("Simulation Mode active (toggle OFF in Settings for hardware su)")
            _rootState.value = sim
            return@withContext sim
        }

        _rootState.value = RootAccessState.Checking
        val result = execSuRaw("id", timeoutSeconds = 15)
        val state = if (result.exitCode == 0 && result.stdout.contains("uid=0")) {
            val managerHint = detectRootManagerVariantReal()
            RootAccessState.Granted(
                idOutput = result.stdout.trim(),
                suManagerHint = managerHint
            )
        } else {
            val msg = when {
                result.stderr.contains("denied", ignoreCase = true) ||
                    result.stderr.contains("permission", ignoreCase = true) ||
                    result.exitCode == 1 || result.exitCode == 13 ->
                    "Root denied by Magisk / KSU / APatch (${result.stderr.ifBlank { "exit=${result.exitCode}" }})"
                result.stderr.contains("No such file", ignoreCase = true) || result.exitCode == 127 ->
                    "su binary did not respond to ProcessBuilder(\"su\", \"-c\", \"id\"): ${result.stderr}"
                else ->
                    "Root request failed (exit=${result.exitCode}): ${result.stderr.ifBlank { result.stdout.ifBlank { "No uid=0 returned" } }}"
            }
            RootAccessState.Denied(
                exitCode = result.exitCode,
                stderr = result.stderr.ifBlank { result.stdout },
                reason = msg
            )
        }
        _rootState.value = state
        state
    }

    private fun detectRootManagerVariantReal(): String {
        val ksu = execSuRaw("ksud -V 2>/dev/null || cat /proc/kernelsu_version 2>/dev/null", 4)
        val apatch = execSuRaw("apd -V 2>/dev/null", 4)
        val magisk = execSuRaw("magisk -v 2>/dev/null", 4)
        val parts = mutableListOf<String>()
        if (magisk.isSuccess && magisk.stdout.isNotBlank()) parts += "Magisk ${magisk.stdout.trim()}"
        if (ksu.isSuccess && ksu.stdout.isNotBlank()) parts += "KSU ${ksu.stdout.trim()}"
        if (apatch.isSuccess && apatch.stdout.isNotBlank()) parts += "APatch ${apatch.stdout.trim()}"
        return if (parts.isEmpty()) "Root Core (uid=0)" else parts.joinToString(" + ")
    }

    /**
     * Executes a root command when simulationMode == false; NEVER silently returns fake success.
     * When simulationMode == true, invokes [simulatedOutput] if provided.
     */
    suspend fun execRoot(
        command: String,
        simulationMode: Boolean,
        timeoutSeconds: Long = 30,
        simulatedOutput: (() -> String)? = null
    ): ShellResult = withContext(Dispatchers.IO) {
        if (simulationMode) {
            val out = simulatedOutput?.invoke() ?: "[SIM] Executed: $command"
            return@withContext ShellResult(
                command = command,
                exitCode = 0,
                stdout = out,
                stderr = "",
                isSimulated = true
            )
        }
        execSuRaw(command, timeoutSeconds)
    }

    /**
     * Executes a non-root shell command (`sh -c`).
     */
    suspend fun execUser(
        command: String,
        timeoutSeconds: Long = 20
    ): ShellResult = withContext(Dispatchers.IO) {
        execProcess(listOf("sh", "-c", command), command, timeoutSeconds)
    }

    /**
     * Streams stdout/stderr line-by-line for Live Console views.
     */
    suspend fun execRootStreaming(
        command: String,
        simulationMode: Boolean,
        onLine: (String) -> Unit,
        simulatedLines: List<String> = emptyList()
    ): ShellResult = withContext(Dispatchers.IO) {
        if (simulationMode) {
            val lines = simulatedLines.ifEmpty { listOf("[SIM] # $command", "[SIM] exit=0") }
            val sb = StringBuilder()
            for (line in lines) {
                onLine(line)
                sb.appendLine(line)
            }
            return@withContext ShellResult(command, 0, sb.toString(), "", isSimulated = true)
        }

        try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(false)
                .start()

            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()

            val errThread = Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val text = line!!
                        stderrBuilder.appendLine(text)
                        onLine("[stderr] $text")
                    }
                }
            }
            errThread.start()

            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val text = line!!
                    stdoutBuilder.appendLine(text)
                    onLine(text)
                }
            }

            errThread.join(5000)
            val exitCode = process.waitFor()
            if (exitCode != 0 && stderrBuilder.isBlank()) {
                onLine("[exit=$exitCode] Command terminated with non-zero status")
            }
            ShellResult(
                command = command,
                exitCode = exitCode,
                stdout = stdoutBuilder.toString().trimEnd(),
                stderr = stderrBuilder.toString().trimEnd(),
                isSimulated = false
            )
        } catch (e: Exception) {
            val err = e.message ?: "ProcessBuilder(su) failed"
            onLine("[error] $err")
            ShellResult(command, 127, "", err, isSimulated = false)
        }
    }

    private fun execSuRaw(command: String, timeoutSeconds: Long): ShellResult {
        return execProcess(listOf("su", "-c", command), command, timeoutSeconds)
    }

    private fun execProcess(
        argv: List<String>,
        originalCommand: String,
        timeoutSeconds: Long
    ): ShellResult {
        return try {
            val process = ProcessBuilder(argv).start()
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val outThread = Thread {
                BufferedReader(InputStreamReader(process.inputStream)).use { r ->
                    r.lineSequence().forEach { stdout.appendLine(it) }
                }
            }
            val errThread = Thread {
                BufferedReader(InputStreamReader(process.errorStream)).use { r ->
                    r.lineSequence().forEach { stderr.appendLine(it) }
                }
            }
            outThread.start()
            errThread.start()

            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return ShellResult(
                    command = originalCommand,
                    exitCode = 124,
                    stdout = stdout.toString().trimEnd(),
                    stderr = "Command timed out after ${timeoutSeconds}s"
                )
            }
            outThread.join(2000)
            errThread.join(2000)

            ShellResult(
                command = originalCommand,
                exitCode = process.exitValue(),
                stdout = stdout.toString().trimEnd(),
                stderr = stderr.toString().trimEnd(),
                isSimulated = false
            )
        } catch (e: Exception) {
            ShellResult(
                command = originalCommand,
                exitCode = 127,
                stdout = "",
                stderr = e.message ?: e.javaClass.simpleName,
                isSimulated = false
            )
        }
    }
}
