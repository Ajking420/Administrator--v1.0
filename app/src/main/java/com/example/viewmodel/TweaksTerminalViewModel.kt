package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.shell.RootShellExecutor
import com.example.core.tweaks.HardwareTweaksEngine
import com.example.core.tweaks.LiveHardwareTelemetry
import com.example.core.tweaks.TweaksConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TerminalLineTone { NORMAL, PROMPT, SUCCESS, WARNING, ERROR, CYAN, VIOLET }

data class TerminalLine(
    val id: Long,
    val text: String,
    val tone: TerminalLineTone = TerminalLineTone.NORMAL
)

data class TerminalSession(
    val id: Int,
    val title: String,
    val isRoot: Boolean = true,
    val cwd: String = "/storage/emulated/0",
    val lines: List<TerminalLine> = emptyList(),
    val history: List<String> = emptyList(),
    val historyCursor: Int = -1
)

class TweaksTerminalViewModel(application: Application) : AndroidViewModel(application) {

    private var lineSeq = 1L
    private var sessionSeq = 1

    private val _telemetry = MutableStateFlow(LiveHardwareTelemetry())
    val telemetry: StateFlow<LiveHardwareTelemetry> = _telemetry.asStateFlow()

    private val _tweaksConfig = MutableStateFlow(TweaksConfig())
    val tweaksConfig: StateFlow<TweaksConfig> = _tweaksConfig.asStateFlow()

    private val _sessions = MutableStateFlow(
        listOf(
            createFreshSession(1)
        )
    )
    val sessions: StateFlow<List<TerminalSession>> = _sessions.asStateFlow()

    private val _activeSessionId = MutableStateFlow(1)
    val activeSessionId: StateFlow<Int> = _activeSessionId.asStateFlow()

    private val _ctrlLatched = MutableStateFlow(false)
    val ctrlLatched: StateFlow<Boolean> = _ctrlLatched.asStateFlow()

    private val _altLatched = MutableStateFlow(false)
    val altLatched: StateFlow<Boolean> = _altLatched.asStateFlow()

    private var pollingStarted = false

    fun syncFromSettings(
        master: Boolean,
        profile: String,
        zramMaster: Boolean,
        zramSize: String,
        zramAlgo: String,
        swappiness: Int,
        disableRamPlus: Boolean,
        applyOnBoot: Boolean
    ) {
        _tweaksConfig.update {
            it.copy(
                masterEnabled = master,
                perfProfile = profile,
                zramMaster = zramMaster,
                zramSizePreset = zramSize,
                zramAlgo = zramAlgo,
                swappiness = swappiness,
                disableSamsungRamPlus = disableRamPlus,
                applyOnBoot = applyOnBoot
            )
        }
    }

    fun startTelemetryLoop(simulationModeProvider: () -> Boolean) {
        if (pollingStarted) return
        pollingStarted = true
        viewModelScope.launch {
            while (isActive) {
                _telemetry.value = HardwareTweaksEngine.sampleTelemetry(simulationModeProvider())
                delay(2500)
            }
        }
    }

    fun updateTweaksConfig(
        adminVm: AdminViewModel,
        transform: (TweaksConfig) -> TweaksConfig
    ) {
        val updated = transform(_tweaksConfig.value)
        _tweaksConfig.value = updated
        viewModelScope.launch {
            adminVm.prefs.saveTweaksState(
                master = updated.masterEnabled,
                profile = updated.perfProfile,
                zramMaster = updated.zramMaster,
                zramSize = updated.zramSizePreset,
                zramAlgo = updated.zramAlgo,
                swappiness = updated.swappiness,
                disableRamPlus = updated.disableSamsungRamPlus,
                applyOnBoot = updated.applyOnBoot
            )
        }
    }

    fun applyTweaksNow(simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val (ok, msg) = HardwareTweaksEngine.applyNow(_tweaksConfig.value, simulationMode)
            adminVm.emitSnackbar(msg)
            if (ok) {
                _telemetry.value = HardwareTweaksEngine.sampleTelemetry(simulationMode)
            }
        }
    }

    fun toggleApplyOnBoot(enabled: Boolean, simulationMode: Boolean, adminVm: AdminViewModel) {
        updateTweaksConfig(adminVm) { it.copy(applyOnBoot = enabled) }
        viewModelScope.launch {
            val (_, msg) = HardwareTweaksEngine.syncApplyOnBootScript(_tweaksConfig.value, enabled, simulationMode)
            adminVm.emitSnackbar(msg)
        }
    }

    // --- Termux-Style Multi-Session Terminal ---

    private fun createFreshSession(id: Int): TerminalSession {
        return TerminalSession(
            id = id,
            title = "Session $id",
            isRoot = true,
            lines = listOf(
                TerminalLine(lineSeq++, "Administrator Shell · SM-E156B (mt6835 · ARM64)", TerminalLineTone.CYAN),
                TerminalLine(lineSeq++, "Type any command (e.g. id, uname -a, getprop ro.board.platform, free -m)", TerminalLineTone.VIOLET)
            )
        )
    }

    fun addSession() {
        sessionSeq++
        val fresh = createFreshSession(sessionSeq)
        _sessions.update { it + fresh }
        _activeSessionId.value = fresh.id
    }

    fun selectSession(id: Int) {
        _activeSessionId.value = id
    }

    fun closeSession(id: Int) {
        val current = _sessions.value
        if (current.size <= 1) return
        val remaining = current.filterNot { it.id == id }
        _sessions.value = remaining
        if (_activeSessionId.value == id) {
            _activeSessionId.value = remaining.last().id
        }
    }

    fun toggleSessionPrivilege(id: Int) {
        _sessions.update { list ->
            list.map { if (it.id == id) it.copy(isRoot = !it.isRoot) else it }
        }
    }

    fun clearActiveSession() {
        val id = _activeSessionId.value
        _sessions.update { list ->
            list.map { if (it.id == id) it.copy(lines = emptyList()) else it }
        }
    }

    fun toggleCtrlLatch() {
        _ctrlLatched.update { !it }
    }

    fun toggleAltLatch() {
        _altLatched.update { !it }
    }

    fun recallHistory(older: Boolean): String? {
        val id = _activeSessionId.value
        val sess = _sessions.value.firstOrNull { it.id == id } ?: return null
        if (sess.history.isEmpty()) return null
        val nextCursor = if (older) {
            if (sess.historyCursor < 0) sess.history.lastIndex else (sess.historyCursor - 1).coerceAtLeast(0)
        } else {
            if (sess.historyCursor < 0) -1 else (sess.historyCursor + 1).coerceAtMost(sess.history.lastIndex)
        }
        _sessions.update { list ->
            list.map { if (it.id == id) it.copy(historyCursor = nextCursor) else it }
        }
        return if (nextCursor in sess.history.indices) sess.history[nextCursor] else ""
    }

    fun runTerminalCommand(rawInput: String, simulationMode: Boolean) {
        val cmd = rawInput.trim()
        if (cmd.isEmpty()) return
        val id = _activeSessionId.value
        val sess = _sessions.value.firstOrNull { it.id == id } ?: return

        if (cmd == "clear") {
            clearActiveSession()
            return
        }

        val promptPrefix = if (sess.isRoot) "root@e15x:#" else "u0@e15x:$"
        appendLines(id, listOf(TerminalLine(lineSeq++, "$promptPrefix $cmd", TerminalLineTone.PROMPT)), addHistory = cmd)

        viewModelScope.launch {
            val result = if (sess.isRoot) {
                RootShellExecutor.execRoot(
                    command = cmd,
                    simulationMode = simulationMode,
                    simulatedOutput = { simulateTerminalCommand(cmd, true) }
                )
            } else {
                RootShellExecutor.execUser(cmd)
            }

            val outLines = mutableListOf<TerminalLine>()
            if (result.stdout.isNotBlank()) {
                result.stdout.lines().forEach { line ->
                    val tone = when {
                        line.contains("uid=0") -> TerminalLineTone.SUCCESS
                        line.contains("error", true) || line.contains("denied", true) -> TerminalLineTone.ERROR
                        line.contains("warn", true) -> TerminalLineTone.WARNING
                        else -> TerminalLineTone.NORMAL
                    }
                    outLines += TerminalLine(lineSeq++, line, tone)
                }
            }
            if (result.stderr.isNotBlank()) {
                result.stderr.lines().forEach { line ->
                    outLines += TerminalLine(lineSeq++, line, TerminalLineTone.ERROR)
                }
            }
            if (!result.isSuccess) {
                outLines += TerminalLine(lineSeq++, "[exit=${result.exitCode}]", TerminalLineTone.ERROR)
            }
            appendLines(id, outLines)
        }
    }

    private fun appendLines(sessionId: Int, newLines: List<TerminalLine>, addHistory: String? = null) {
        _sessions.update { list ->
            list.map { s ->
                if (s.id != sessionId) s
                else s.copy(
                    lines = (s.lines + newLines).takeLast(350),
                    history = if (addHistory != null) (s.history + addHistory).takeLast(60) else s.history,
                    historyCursor = -1
                )
            }
        }
    }

    private fun simulateTerminalCommand(cmd: String, isRoot: Boolean): String = when {
        cmd == "id" -> if (isRoot) "uid=0(root) gid=0(root) groups=0(root) context=u:r:magisk:s0" else "uid=10244(u0_a244) gid=10244"
        cmd.startsWith("uname") -> "Linux localhost 5.15.149-android13-8-mt6835 #1 SMP PREEMPT aarch64 Android"
        cmd.startsWith("getprop") -> "mt6835"
        cmd.startsWith("free") -> "              total        used        free      shared  buff/cache   available\nMem:           3892        2412         318          44        1162        1480\nSwap:          4096        1120        2976"
        cmd.startsWith("ls") -> "boot  init_boot  vendor_boot  recovery  dtbo  vbmeta  super  efs  sec_efs  nvram  nvdata  nvcfg"
        else -> "[SIM] Executed ($cmd) · exit=0"
    }
}
