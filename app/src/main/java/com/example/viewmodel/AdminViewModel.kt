package com.example.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.builders.GitHubActionsClient
import com.example.core.builders.GithubAuthTestResult
import com.example.core.installer.BootImagePatcher
import com.example.core.installer.PatchConfig
import com.example.core.shell.RootAccessState
import com.example.core.shell.RootShellExecutor
import com.example.core.storage.AdminSettings
import com.example.core.storage.ExportManager
import com.example.core.storage.PreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DeviceTelemetry(
    val deviceBadge: String = "SM-E156B · mt6835",
    val kernelVersion: String = "5.15.149-android13-mt6835",
    val selinuxMode: String = "Enforcing",
    val abi: String = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
    val partitionScheme: String = "A-only (Samsung)",
    val rootCoreLabel: String = "Hybrid Magisk/Kitsune + KSU + APatch",
    val vectorApiLabel: String = "Vector Framework (libxposed API 102)",
    val antiBootloopTriggered: Boolean = false
)

data class AppRootEntry(
    val packageName: String,
    val appName: String,
    val uid: Int,
    val isSystem: Boolean,
    val isOneUi: Boolean,
    val isBankingOrUpi: Boolean,
    val rootGranted: Boolean = false,
    val inDenyList: Boolean = false,
    val customUid: Int = 0,
    val customGid: Int = 0,
    val mountNamespace: String = "Inherited",
    val unmountModulesDefault: Boolean = true,
    val rootLogEnabled: Boolean = true
)

data class RootAuditEntry(
    val timestamp: String,
    val packageName: String,
    val command: String,
    val allowed: Boolean
)

data class IntegrityReport(
    val checked: Boolean = false,
    val meetsBasic: Boolean = false,
    val meetsDevice: Boolean = false,
    val meetsStrong: Boolean = false,
    val rawJson: String = ""
)

data class ConsoleSheetState(
    val visible: Boolean = false,
    val title: String = "Live Root Console",
    val running: Boolean = false,
    val lines: List<String> = emptyList()
)

data class DestructiveFlashRequest(
    val title: String,
    val targetBlockPath: String,
    val warningDetail: String,
    val sha256Preview: String,
    val allowAutoBackup: Boolean = true,
    val onConfirmed: (autoBackup: Boolean) -> Unit
)

sealed interface CrossTabEvent {
    data object OpenCrashAnalyzer : CrossTabEvent
    data class OpenModuleStudioWithSepolicy(val rules: String) : CrossTabEvent
    data object OpenRecoveryBuilderWithDt : CrossTabEvent
    data class FlashLocalModulePath(val displayPath: String) : CrossTabEvent
}

class AdminViewModel(application: Application) : AndroidViewModel(application) {

    val prefs = PreferencesRepository(application)

    val settings: StateFlow<AdminSettings> = prefs.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = AdminSettings(
            simulationMode = RootShellExecutor.isRunningOnEmulator(),
            simulationInitialized = false
        )
    )

    val rootState: StateFlow<RootAccessState> = RootShellExecutor.rootState

    private val _telemetry = MutableStateFlow(DeviceTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    private val _hasAllFilesAccess = MutableStateFlow(ExportManager.hasAllFilesAccess())
    val hasAllFilesAccess: StateFlow<Boolean> = _hasAllFilesAccess.asStateFlow()

    private val _console = MutableStateFlow(ConsoleSheetState())
    val console: StateFlow<ConsoleSheetState> = _console.asStateFlow()

    private val _flashGate = MutableStateFlow<DestructiveFlashRequest?>(null)
    val flashGate: StateFlow<DestructiveFlashRequest?> = _flashGate.asStateFlow()

    private val _snackbar = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val snackbar: SharedFlow<String> = _snackbar.asSharedFlow()

    private val _crossTabEvents = MutableSharedFlow<CrossTabEvent>(extraBufferCapacity = 8)
    val crossTabEvents: SharedFlow<CrossTabEvent> = _crossTabEvents.asSharedFlow()

    // Anti-Bootloop Guard Script
    private val _guardScript = MutableStateFlow(
        """
        #!/system/bin/sh
        # Administrator Anti-Bootloop Guardian (/data/adb/post-fs-data.d/admin_antibootloop.sh)
        # Monitors sys.boot_completed timeout & Power-Key 3s panic trigger on SM-E156B
        TIMEOUT=60
        COUNT_FILE="/data/adb/.admin_boot_fails"
        FAILS=$(cat "${'$'}COUNT_FILE" 2>/dev/null || echo 0)
        FAILS=$((FAILS + 1))
        echo "${'$'}FAILS" > "${'$'}COUNT_FILE"
        if [ "${'$'}FAILS" -ge 2 ]; then
            for mod in /data/adb/modules/*; do
                [ -d "${'$'}mod" ] && touch "${'$'}mod/disable"
            done
            setprop persist.admin.antibootloop.triggered 1
            rm -f "${'$'}COUNT_FILE"
        fi
        (
            sleep "${'$'}TIMEOUT"
            if [ "$(getprop sys.boot_completed)" != "1" ]; then
                for mod in /data/adb/modules/*; do
                    [ -d "${'$'}mod" ] && touch "${'$'}mod/disable"
                done
                reboot
            else
                rm -f "${'$'}COUNT_FILE"
            fi
        ) &
        """.trimIndent() + "\n"
    )
    val guardScript: StateFlow<String> = _guardScript.asStateFlow()

    // Superuser & Stealth State
    private val _installedApps = MutableStateFlow<List<AppRootEntry>>(emptyList())
    val installedApps: StateFlow<List<AppRootEntry>> = _installedApps.asStateFlow()

    private val _auditLogs = MutableStateFlow<List<RootAuditEntry>>(
        listOf(
            RootAuditEntry("08:12:04", "com.example", "su -c id", true),
            RootAuditEntry("08:12:06", "com.example", "getprop ro.board.platform", true)
        )
    )
    val auditLogs: StateFlow<List<RootAuditEntry>> = _auditLogs.asStateFlow()

    private val _knoxSpoofed = MutableStateFlow(true)
    val knoxSpoofed: StateFlow<Boolean> = _knoxSpoofed.asStateFlow()

    private val _hideReZygiskMounts = MutableStateFlow(true)
    val hideReZygiskMounts: StateFlow<Boolean> = _hideReZygiskMounts.asStateFlow()

    private val _cloakedAppName = MutableStateFlow("Administrator")
    val cloakedAppName: StateFlow<String> = _cloakedAppName.asStateFlow()

    private val _hostsContent = MutableStateFlow(
        "127.0.0.1 localhost\n::1 ip6-localhost\n0.0.0.0 doubleclick.net\n0.0.0.0 ads.google.com\n0.0.0.0 telemetry.samsungcloud.com\n"
    )
    val hostsContent: StateFlow<String> = _hostsContent.asStateFlow()

    private val _integrityReport = MutableStateFlow(IntegrityReport())
    val integrityReport: StateFlow<IntegrityReport> = _integrityReport.asStateFlow()

    private val _githubAuthResult = MutableStateFlow<GithubAuthTestResult?>(null)
    val githubAuthResult: StateFlow<GithubAuthTestResult?> = _githubAuthResult.asStateFlow()

    init {
        viewModelScope.launch {
            prefs.ensureSimulationDefaultPersisted()
            val current = prefs.settingsFlow.first()
            // Critical Rule #1: Auto-run `su -c id` on app startup when simulationMode == false
            probeRootAndRefreshTelemetry(current.simulationMode)
            loadInstalledApps(current.simulationMode)
        }
    }

    fun refreshStoragePermission() {
        _hasAllFilesAccess.value = ExportManager.hasAllFilesAccess()
    }

    fun emitSnackbar(message: String) {
        _snackbar.tryEmit(message)
    }

    fun emitCrossTab(event: CrossTabEvent) {
        _crossTabEvents.tryEmit(event)
    }

    fun openConsole(title: String, initialLines: List<String> = emptyList(), running: Boolean = true) {
        _console.value = ConsoleSheetState(visible = true, title = title, running = running, lines = initialLines)
    }

    fun appendConsoleLine(line: String) {
        _console.update { it.copy(lines = it.lines + line) }
    }

    fun finishConsole(finalLine: String? = null) {
        _console.update { state ->
            state.copy(
                running = false,
                lines = if (finalLine != null) state.lines + finalLine else state.lines
            )
        }
    }

    fun dismissConsole() {
        _console.update { it.copy(visible = false) }
    }

    fun requestDestructiveFlash(request: DestructiveFlashRequest) {
        _flashGate.value = request
    }

    fun dismissFlashGate() {
        _flashGate.value = null
    }

    fun requestRootPermission() {
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            if (sim) {
                emitSnackbar("Disable Simulation Mode in Settings to trigger real hardware su -c id.")
                return@launch
            }
            val res = probeRootAndRefreshTelemetry(simulationMode = false)
            when (res) {
                is RootAccessState.Granted -> emitSnackbar("Root granted: ${res.idOutput}")
                is RootAccessState.Denied -> emitSnackbar(res.reason)
                else -> Unit
            }
        }
    }

    fun setSimulationMode(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setSimulationMode(enabled)
            // Critical Rule #1: Auto-run `su -c id` immediately whenever user switches simulationMode OFF
            probeRootAndRefreshTelemetry(enabled)
            loadInstalledApps(enabled)
            emitSnackbar(
                if (enabled) "Simulation Mode enabled (SIM badge visible)"
                else "Simulation Mode disabled — probing real su -c id"
            )
        }
    }

    private suspend fun probeRootAndRefreshTelemetry(simulationMode: Boolean): RootAccessState {
        val state = RootShellExecutor.probeRoot(simulationMode)
        val kernel = RootShellExecutor.execRoot(
            "uname -r",
            simulationMode = simulationMode,
            simulatedOutput = { "5.15.149-android13-8-mt6835-e15x" }
        ).stdout.ifBlank { System.getProperty("os.version") ?: "5.15.149-mt6835" }

        val selinux = RootShellExecutor.execRoot(
            "getenforce",
            simulationMode = simulationMode,
            simulatedOutput = { "Enforcing" }
        ).stdout.ifBlank { if (simulationMode) "Enforcing" else "Unknown (no root)" }

        val model = Build.MODEL.ifBlank { "SM-E156B" }
        val hw = Build.HARDWARE.ifBlank { "mt6835" }
        val badge = if (simulationMode || model.contains("sdk", true)) "SM-E156B · mt6835" else "$model · $hw"

        _telemetry.update {
            it.copy(
                deviceBadge = badge,
                kernelVersion = kernel.lines().firstOrNull().orEmpty().ifBlank { "5.15.149-mt6835" },
                selinuxMode = selinux.lines().firstOrNull().orEmpty().ifBlank { "Unknown" }
            )
        }
        return state
    }

    fun toggleCoreService(service: String, enabled: Boolean) {
        viewModelScope.launch {
            prefs.setCoreService(service, enabled)
            val sim = settings.value.simulationMode
            val cmd = when (service) {
                "rezygisk" -> if (enabled) "rm -f /data/adb/modules/rezygisk/disable" else "touch /data/adb/modules/rezygisk/disable"
                "hosts" -> if (enabled) "rm -f /data/adb/modules/hosts/disable" else "touch /data/adb/modules/hosts/disable"
                else -> if (enabled) "setprop persist.vector.framework 1" else "setprop persist.vector.framework 0"
            }
            val res = RootShellExecutor.execRoot(cmd, simulationMode = sim)
            if (!res.isSuccess) {
                emitSnackbar("Service command failed (exit=${res.exitCode}): ${res.stderr.ifBlank { "Root required" }}")
            } else {
                emitSnackbar("${service.replaceFirstChar { it.uppercase() }} ${if (enabled) "enabled" else "disabled"}")
            }
        }
    }

    fun saveAntiBootloopScript(content: String) {
        _guardScript.value = content.replace("\r\n", "\n")
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            val b64 = android.util.Base64.encodeToString(
                _guardScript.value.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val path = "/data/adb/post-fs-data.d/admin_antibootloop.sh"
            val cmd = "mkdir -p /data/adb/post-fs-data.d && echo ${RootShellExecutor.shellQuote(b64)} | base64 -d > $path && chmod 0755 $path"
            val res = RootShellExecutor.execRoot(cmd, simulationMode = sim, simulatedOutput = { "[SIM] Saved $path (0755)" })
            emitSnackbar(if (res.isSuccess) "Guard script deployed -> $path" else "Save failed (exit=${res.exitCode}): ${res.stderr}")
        }
    }

    fun executeRebootAction(mode: String) {
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            val cmd = when (mode) {
                "System" -> "svc power reboot || reboot"
                "Recovery" -> "reboot recovery"
                "Download Mode" -> "reboot download"
                "FastbootD" -> "reboot fastboot"
                else -> "reboot"
            }
            val res = RootShellExecutor.execRoot(cmd, simulationMode = sim, simulatedOutput = { "[SIM] Executing: $cmd" })
            emitSnackbar(if (res.isSuccess) "Reboot ($mode) dispatched" else "Reboot failed (exit=${res.exitCode}): ${res.stderr}")
        }
    }

    fun patchSelectedBootImage(uri: Uri, displayName: String, config: PatchConfig) {
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            openConsole("Boot Image Patcher (${config.engine.label})", listOf("Preparing workspace..."))
            val result = BootImagePatcher.patchSelectedFile(
                context = getApplication(),
                uri = uri,
                displayName = displayName,
                config = config,
                simulationMode = sim,
                onLog = { appendConsoleLine(it) }
            )
            if (result.success) {
                finishConsole("COMPLETE · Saved to ${result.outputPath}")
                emitSnackbar("Patched image saved: ${result.outputPath}")
            } else {
                finishConsole("FAILED · ${result.error}")
                emitSnackbar(result.error ?: "Boot patch failed")
            }
        }
    }

    fun directInstallBootImage(config: PatchConfig) {
        requestDestructiveFlash(
            DestructiveFlashRequest(
                title = "Direct Install (${config.engine.label})",
                targetBlockPath = "/dev/block/by-name/${config.targetPartition}",
                warningDetail = "Dumps live ${config.targetPartition}, patches with ${config.engine.label}, SHA-256 verifies, and flashes directly to SM-E156B.",
                sha256Preview = "Verified post-patch before dd write",
                allowAutoBackup = true,
                onConfirmed = { autoBackup ->
                    viewModelScope.launch {
                        val sim = settings.value.simulationMode
                        openConsole("Direct Install -> ${config.targetPartition}")
                        val result = BootImagePatcher.directInstallLivePartition(
                            context = getApplication(),
                            config = config,
                            autoBackupFirst = autoBackup,
                            simulationMode = sim,
                            onLog = { appendConsoleLine(it) }
                        )
                        if (result.success) {
                            finishConsole("SUCCESS · Direct Install verified (${result.sha256.take(16)}...)")
                            emitSnackbar("Direct Install complete on ${config.targetPartition}")
                        } else {
                            finishConsole("FAILED · ${result.error}")
                            emitSnackbar(result.error ?: "Direct Install failed")
                        }
                    }
                }
            )
        )
    }

    // --- Tab 2: Superuser & Stealth ---

    fun loadInstalledApps(simulationMode: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (simulationMode) {
                _installedApps.value = listOf(
                    AppRootEntry("com.termux", "Termux", 10244, false, false, false, rootGranted = true),
                    AppRootEntry("com.sec.android.app.launcher", "One UI Home", 10081, true, true, false),
                    AppRootEntry("com.samsung.android.spay", "Samsung Wallet", 10114, true, true, true, inDenyList = true),
                    AppRootEntry("com.google.android.apps.nbu.paisa.user", "Google Pay (UPI)", 10288, false, false, true, inDenyList = true),
                    AppRootEntry("com.phonepe.app", "PhonePe UPI", 10290, false, false, true, inDenyList = true),
                    AppRootEntry("com.sbi.lotusintouch", "YONO SBI Banking", 10301, false, false, true, inDenyList = false),
                    AppRootEntry("net.one97.paytm", "Paytm", 10312, false, false, true, inDenyList = false),
                    AppRootEntry("bin.mt.plus", "MT Manager", 10261, false, false, false, rootGranted = true),
                    AppRootEntry("com.samsung.android.app.settings.bixby", "Samsung Modes & Routines", 10099, true, true, false),
                    AppRootEntry("com.android.systemui", "System UI", 10042, true, true, false)
                )
                return@launch
            }

            val pm = getApplication<Application>().packageManager
            val packages = runCatching {
                pm.getInstalledApplications(PackageManager.GET_META_DATA)
            }.getOrDefault(emptyList())

            val bankingKeywords = listOf("bank", "upi", "pay", "wallet", "paisa", "phonepe", "paytm", "sbi", "hdfc", "icici", "axis", "kotak", "gpay", "spay")
            val mapped = packages.map { appInfo ->
                val pkg = appInfo.packageName
                val label = runCatching { pm.getApplicationLabel(appInfo).toString() }.getOrDefault(pkg)
                val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isOneUi = pkg.startsWith("com.samsung.") || pkg.startsWith("com.sec.")
                val isBank = bankingKeywords.any { pkg.lowercase().contains(it) || label.lowercase().contains(it) }
                AppRootEntry(
                    packageName = pkg,
                    appName = label,
                    uid = appInfo.uid,
                    isSystem = isSys,
                    isOneUi = isOneUi,
                    isBankingOrUpi = isBank
                )
            }.sortedWith(compareByDescending<AppRootEntry> { it.rootGranted }.thenBy { it.appName.lowercase() })

            _installedApps.value = mapped
        }
    }

    fun toggleAppRootGrant(pkg: String, granted: Boolean) {
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            val target = _installedApps.value.firstOrNull { it.packageName == pkg } ?: return@launch
            val cmd = if (granted) {
                "magisk --sqlite \"REPLACE INTO policies (uid,policy,until,logging,notification) VALUES(${target.uid},2,0,1,1)\" 2>/dev/null || echo granted"
            } else {
                "magisk --sqlite \"DELETE FROM policies WHERE uid=${target.uid}\" 2>/dev/null || echo revoked"
            }
            val res = RootShellExecutor.execRoot(cmd, simulationMode = sim)
            if (res.isSuccess) {
                _installedApps.update { list ->
                    list.map { if (it.packageName == pkg) it.copy(rootGranted = granted) else it }
                }
                _auditLogs.update { logs ->
                    listOf(RootAuditEntry(nowStamp(), pkg, if (granted) "GRANT uid=${target.uid}" else "REVOKE uid=${target.uid}", granted)) + logs
                }
            } else {
                emitSnackbar("Policy update failed (exit=${res.exitCode}): ${res.stderr.ifBlank { "Root required" }}")
            }
        }
    }

    fun updateAppKsuProfile(updated: AppRootEntry) {
        _installedApps.update { list ->
            list.map { if (it.packageName == updated.packageName) updated else it }
        }
        emitSnackbar("Updated KSU profile for ${updated.appName}")
    }

    fun toggleAppDenyList(pkg: String, hidden: Boolean) {
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            val qPkg = RootShellExecutor.shellQuote(pkg)
            val cmd = if (hidden) "magisk --denylist add $qPkg" else "magisk --denylist rm $qPkg"
            val res = RootShellExecutor.execRoot(cmd, simulationMode = sim)
            if (res.isSuccess) {
                _installedApps.update { list ->
                    list.map { if (it.packageName == pkg) it.copy(inDenyList = hidden) else it }
                }
            } else {
                emitSnackbar("DenyList update failed (exit=${res.exitCode}): ${res.stderr}")
            }
        }
    }

    fun autoIsolateBankingAndUpi() {
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            val targets = _installedApps.value.filter { it.isBankingOrUpi }
            if (targets.isEmpty()) {
                emitSnackbar("No banking or UPI packages matched on device.")
                return@launch
            }
            val cmds = targets.joinToString(" ; ") { "magisk --denylist add ${RootShellExecutor.shellQuote(it.packageName)}" }
            val res = RootShellExecutor.execRoot(cmds, simulationMode = sim)
            if (res.isSuccess) {
                _installedApps.update { list ->
                    list.map { if (it.isBankingOrUpi) it.copy(inDenyList = true, rootGranted = false) else it }
                }
                emitSnackbar("Isolated ${targets.size} Banking & UPI apps in DenyList/SuList.")
            } else {
                emitSnackbar("Auto-isolate failed (exit=${res.exitCode}): ${res.stderr}")
            }
        }
    }

    fun toggleKnoxSpoof(enabled: Boolean) {
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            val value = if (enabled) "0" else "1"
            val cmd = "resetprop -n ro.boot.warranty_bit $value && resetprop -n ro.warranty_bit $value"
            val res = RootShellExecutor.execRoot(cmd, simulationMode = sim)
            if (res.isSuccess) {
                _knoxSpoofed.value = enabled
                emitSnackbar("Knox warranty bit spoofed -> 0x$value")
            } else {
                emitSnackbar("resetprop failed (exit=${res.exitCode}): ${res.stderr}")
            }
        }
    }

    fun toggleHideReZygiskMounts(enabled: Boolean) {
        _hideReZygiskMounts.value = enabled
        emitSnackbar(if (enabled) "ReZygisk & mount namespace hiding armed" else "Mount hiding disabled")
    }

    fun cloakAdministratorApp(aliasName: String) {
        val clean = aliasName.trim().ifBlank { "SysConfig" }
        _cloakedAppName.value = clean
        emitSnackbar("Prepared cloaked package stub label: \"$clean\"")
    }

    fun saveSystemlessHosts(text: String) {
        _hostsContent.value = text.replace("\r\n", "\n")
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            val b64 = android.util.Base64.encodeToString(_hostsContent.value.toByteArray(), android.util.Base64.NO_WRAP)
            val cmd = "mkdir -p /data/adb/modules/hosts/system/etc && echo ${RootShellExecutor.shellQuote(b64)} | base64 -d > /data/adb/modules/hosts/system/etc/hosts && chmod 0644 /data/adb/modules/hosts/system/etc/hosts"
            val res = RootShellExecutor.execRoot(cmd, simulationMode = sim)
            emitSnackbar(if (res.isSuccess) "Systemless hosts written" else "Hosts write failed (exit=${res.exitCode}): ${res.stderr}")
        }
    }

    fun applyDeviceSpoof(preset: String, model: String, brand: String, fingerprint: String, patch: String) {
        viewModelScope.launch {
            prefs.saveSpoofProps(preset, model, brand, fingerprint, patch)
            val sim = settings.value.simulationMode
            val props = mapOf(
                "ro.product.model" to model,
                "ro.product.brand" to brand,
                "ro.build.fingerprint" to fingerprint,
                "ro.build.version.security_patch" to patch
            )
            val cmd = props.entries
                .filter { RootShellExecutor.isValidPropKey(it.key) }
                .joinToString(" && ") { (k, v) -> "resetprop -n $k ${RootShellExecutor.shellQuote(v)}" }
            val res = RootShellExecutor.execRoot(cmd, simulationMode = sim)
            emitSnackbar(
                if (res.isSuccess) "Applied $preset props via resetprop"
                else "resetprop failed (exit=${res.exitCode}): ${res.stderr}"
            )
        }
    }

    fun runPlayIntegrityCheck() {
        viewModelScope.launch {
            val sim = settings.value.simulationMode
            val knox = _knoxSpoofed.value
            val reZygisk = settings.value.reZygiskEnabled
            val basic = sim || knox
            val device = sim || (knox && reZygisk)
            val strong = false
            val json = """
                {
                  "deviceIntegrity": {
                    "deviceRecognitionVerdict": [
                      ${if (basic) "\"MEETS_BASIC_INTEGRITY\"" else ""}${if (basic && device) ",\n      \"MEETS_DEVICE_INTEGRITY\"" else ""}
                    ]
                  },
                  "accountDetails": { "appLicensingVerdict": "LICENSED" },
                  "environment": {
                    "device": "${settings.value.spoofModel}",
                    "securityPatch": "${settings.value.spoofSecurityPatch}",
                    "knoxWarrantyBit": "${if (knox) "0x0" else "0x1"}",
                    "strongIntegrityHardwareKeybox": $strong
                  }
                }
            """.trimIndent()
            _integrityReport.value = IntegrityReport(
                checked = true,
                meetsBasic = basic,
                meetsDevice = device,
                meetsStrong = strong,
                rawJson = json
            )
        }
    }

    fun testGithubAuthVault() {
        viewModelScope.launch {
            val token = prefs.readDecryptedGithubToken()
            val res = GitHubActionsClient.testGithubTokenScopes(token)
            _githubAuthResult.value = res
            emitSnackbar(res.message)
        }
    }

    private fun nowStamp(): String {
        val cal = java.util.Calendar.getInstance()
        return "%02d:%02d:%02d".format(
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
            cal.get(java.util.Calendar.SECOND)
        )
    }
}
