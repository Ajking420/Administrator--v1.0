package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.repos.AutoUpdateEngine
import com.example.core.repos.AvailableUpdate
import com.example.core.repos.RepoFamily
import com.example.core.repos.RepoModule
import com.example.core.repos.RepositoryManager
import com.example.core.shell.RootShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

enum class InstalledModuleKind(val label: String) {
    MAGISK("Magisk"),
    APM_KPM("APM & KPM"),
    VECTOR("Vector")
}

data class InstalledModule(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Long,
    val author: String,
    val description: String,
    val kind: InstalledModuleKind,
    val enabled: Boolean = true,
    val preInitMount: Boolean = false,
    val kpmLoaded: Boolean = false,
    val vectorScopeApps: List<String> = emptyList()
)

enum class RepoSortMode(val label: String) {
    UPDATED("Recently Updated"),
    STARS("Stars"),
    NAME("Name")
}

data class ModulesUiState(
    val installed: List<InstalledModule> = emptyList(),
    val liveModules: List<RepoModule> = emptyList(),
    val repoCounts: Map<String, Int> = emptyMap(),
    val repoErrors: Map<String, String> = emptyMap(),
    val isRefreshingRepos: Boolean = false,
    val fromCache: Boolean = false,
    val availableUpdates: List<AvailableUpdate> = emptyList(),
    val checkingUpdates: Boolean = false,
    val apatchSuperKeyVerified: Boolean = false
)

class ModulesViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ModulesUiState())
    val uiState: StateFlow<ModulesUiState> = _uiState.asStateFlow()

    fun initialize(
        simulationMode: Boolean,
        disabledRepoIds: Set<String>,
        customRepoUrls: Set<String>,
        autoCheckUpdates: Boolean,
        checkMagisk: Boolean,
        checkVector: Boolean,
        checkModules: Boolean
    ) {
        loadInstalledModules(simulationMode)
        loadAndRefreshRepos(
            disabledRepoIds = disabledRepoIds,
            customRepoUrls = customRepoUrls,
            autoCheckUpdates = autoCheckUpdates,
            checkMagisk = checkMagisk,
            checkVector = checkVector,
            checkModules = checkModules
        )
    }

    fun loadInstalledModules(simulationMode: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (simulationMode) {
                _uiState.update {
                    it.copy(
                        installed = listOf(
                            InstalledModule(
                                id = "rezygisk",
                                name = "ReZygisk",
                                version = "v1.0.0-rc2",
                                versionCode = 1002,
                                author = "PerformanC",
                                description = "Standalone open-source Zygisk implementation tuned for Magisk/KSU/APatch.",
                                kind = InstalledModuleKind.MAGISK,
                                enabled = true,
                                preInitMount = true
                            ),
                            InstalledModule(
                                id = "playintegrityfix",
                                name = "Play Integrity Fix (NEXT)",
                                version = "v18.2",
                                versionCode = 18200,
                                author = "chiteroman",
                                description = "Patches Play Integrity & Device attestation props on Samsung SM-E156B.",
                                kind = InstalledModuleKind.MAGISK,
                                enabled = true,
                                preInitMount = false
                            ),
                            InstalledModule(
                                id = "kpm_hide_mount",
                                name = "KPM Mount Cloak (mt6835)",
                                version = "v0.9.4",
                                versionCode = 94,
                                author = "APatch Community",
                                description = "KernelPatch Module hiding overlayfs mounts from banking detectors.",
                                kind = InstalledModuleKind.APM_KPM,
                                enabled = true,
                                kpmLoaded = true
                            ),
                            InstalledModule(
                                id = "vector_oneui_tweaker",
                                name = "One UI Vector Hooks",
                                version = "v2.4.1",
                                versionCode = 241,
                                author = "Vector Mods",
                                description = "libxposed API 102 hot-reload hooks for Samsung SystemUI & Launcher.",
                                kind = InstalledModuleKind.VECTOR,
                                enabled = true,
                                vectorScopeApps = listOf("com.android.systemui", "com.sec.android.app.launcher")
                            )
                        )
                    )
                }
                return@launch
            }

            // Real scan of /data/adb/modules/*/module.prop via su
            val res = RootShellExecutor.execRoot(
                """
                for d in /data/adb/modules/*; do
                  [ -f "${'$'}d/module.prop" ] || continue
                  ID=$(basename "${'$'}d")
                  DIS=$([ -f "${'$'}d/disable" ] && echo 0 || echo 1)
                  NAME=$(grep -m1 '^name=' "${'$'}d/module.prop" | cut -d= -f2-)
                  VER=$(grep -m1 '^version=' "${'$'}d/module.prop" | cut -d= -f2-)
                  CODE=$(grep -m1 '^versionCode=' "${'$'}d/module.prop" | cut -d= -f2-)
                  AUTH=$(grep -m1 '^author=' "${'$'}d/module.prop" | cut -d= -f2-)
                  DESC=$(grep -m1 '^description=' "${'$'}d/module.prop" | cut -d= -f2-)
                  echo "${'$'}ID|${'$'}NAME|${'$'}VER|${'$'}CODE|${'$'}AUTH|${'$'}DESC|${'$'}DIS"
                done
                """.trimIndent(),
                simulationMode = false,
                timeoutSeconds = 12
            )
            if (!res.isSuccess || res.stdout.isBlank()) {
                _uiState.update { it.copy(installed = emptyList()) }
                return@launch
            }
            val parsed = res.stdout.lines().mapNotNull { line ->
                val p = line.split("|")
                if (p.size < 7) return@mapNotNull null
                val id = p[0]
                val kind = when {
                    id.contains("kpm", true) || id.contains("apatch", true) -> InstalledModuleKind.APM_KPM
                    id.contains("xposed", true) || id.contains("vector", true) || id.contains("lsposed", true) -> InstalledModuleKind.VECTOR
                    else -> InstalledModuleKind.MAGISK
                }
                InstalledModule(
                    id = id,
                    name = p[1].ifBlank { id },
                    version = p[2].ifBlank { "1.0" },
                    versionCode = p[3].toLongOrNull() ?: 1L,
                    author = p[4].ifBlank { "Unknown" },
                    description = p[5].ifBlank { "Installed in /data/adb/modules/$id" },
                    kind = kind,
                    enabled = p[6] == "1"
                )
            }
            _uiState.update { it.copy(installed = parsed) }
        }
    }

    fun loadAndRefreshRepos(
        disabledRepoIds: Set<String>,
        customRepoUrls: Set<String>,
        autoCheckUpdates: Boolean = false,
        checkMagisk: Boolean = true,
        checkVector: Boolean = true,
        checkModules: Boolean = true
    ) {
        viewModelScope.launch {
            val sources = RepositoryManager.buildActiveSources(disabledRepoIds, customRepoUrls)
            val cached = RepositoryManager.loadCachedCatalog(getApplication(), sources)
            if (cached.modules.isNotEmpty()) {
                _uiState.update {
                    it.copy(
                        liveModules = cached.modules,
                        repoCounts = cached.countsByRepoId,
                        fromCache = true
                    )
                }
            }
            _uiState.update { it.copy(isRefreshingRepos = true) }
            val live = RepositoryManager.fetchAllLiveRepos(getApplication(), sources)
            _uiState.update {
                it.copy(
                    liveModules = if (live.modules.isNotEmpty()) live.modules else it.liveModules,
                    repoCounts = live.countsByRepoId,
                    repoErrors = live.errorsByRepoId,
                    isRefreshingRepos = false,
                    fromCache = live.modules.isEmpty() && cached.modules.isNotEmpty()
                )
            }
            if (autoCheckUpdates) {
                runAutoUpdateCheck(checkMagisk, checkVector, checkModules)
            }
        }
    }

    fun runAutoUpdateCheck(checkMagisk: Boolean, checkVector: Boolean, checkModules: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(checkingUpdates = true) }
            val installedPairs = _uiState.value.installed.map { it.id to it.versionCode }
            val updates = AutoUpdateEngine.checkUpstreamUpdates(
                checkMagisk = checkMagisk,
                checkVector = checkVector,
                checkModules = checkModules,
                installedModules = installedPairs,
                liveCatalog = _uiState.value.liveModules
            )
            _uiState.update { it.copy(availableUpdates = updates, checkingUpdates = false) }
        }
    }

    fun toggleModuleEnabled(mod: InstalledModule, enabled: Boolean, simulationMode: Boolean, onMsg: (String) -> Unit) {
        viewModelScope.launch {
            val safeId = mod.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cmd = if (enabled) "rm -f /data/adb/modules/$safeId/disable" else "touch /data/adb/modules/$safeId/disable"
            val res = RootShellExecutor.execRoot(cmd, simulationMode = simulationMode)
            if (res.isSuccess) {
                _uiState.update { st ->
                    st.copy(installed = st.installed.map { if (it.id == mod.id) it.copy(enabled = enabled) else it })
                }
                onMsg("${mod.name} ${if (enabled) "enabled" else "disabled"}")
            } else {
                onMsg("Toggle failed (exit=${res.exitCode}): ${res.stderr}")
            }
        }
    }

    fun togglePreInitMount(mod: InstalledModule, enabled: Boolean, onMsg: (String) -> Unit) {
        _uiState.update { st ->
            st.copy(installed = st.installed.map { if (it.id == mod.id) it.copy(preInitMount = enabled) else it })
        }
        onMsg("Pre-Init Mount ${if (enabled) "armed" else "cleared"} for ${mod.name}")
    }

    fun verifyApatchSuperKeyAndToggleKpm(
        mod: InstalledModule,
        superKey: String,
        load: Boolean,
        simulationMode: Boolean,
        onMsg: (String) -> Unit
    ) {
        if (superKey.length < 6) {
            onMsg("Enter a valid APatch SuperKey (>= 6 chars).")
            return
        }
        viewModelScope.launch {
            val qKey = RootShellExecutor.shellQuote(superKey)
            val safeId = mod.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cmd = if (load) "kpatch $qKey kpm load /data/adb/ap/kpm/$safeId.kpm" else "kpatch $qKey kpm unload $safeId"
            val res = RootShellExecutor.execRoot(
                cmd,
                simulationMode = simulationMode,
                simulatedOutput = { "[SIM] kpatch SuperKey OK · ${if (load) "Loaded" else "Unloaded"} KPM $safeId" }
            )
            if (res.isSuccess) {
                _uiState.update { st ->
                    st.copy(
                        apatchSuperKeyVerified = true,
                        installed = st.installed.map { if (it.id == mod.id) it.copy(kpmLoaded = load) else it }
                    )
                }
                onMsg(if (load) "KPM ${mod.name} loaded into kernel" else "KPM ${mod.name} unloaded")
            } else {
                onMsg("KPM action failed (exit=${res.exitCode}): ${res.stderr}")
            }
        }
    }

    fun hotReloadVectorModule(mod: InstalledModule, simulationMode: Boolean, onMsg: (String) -> Unit) {
        viewModelScope.launch {
            val res = RootShellExecutor.execRoot(
                "am broadcast -a org.lsposed.vector.HOT_RELOAD --es module ${RootShellExecutor.shellQuote(mod.id)}",
                simulationMode = simulationMode,
                simulatedOutput = { "[SIM] Vector libxposed API 102 hot-reloaded ${mod.name} in ${mod.vectorScopeApps.size} scopes" }
            )
            onMsg(if (res.isSuccess) "Hot-reloaded ${mod.name} (libxposed API 102)" else "Hot-reload failed (exit=${res.exitCode})")
        }
    }

    fun updateVectorScope(modId: String, scopes: List<String>, onMsg: (String) -> Unit) {
        _uiState.update { st ->
            st.copy(installed = st.installed.map { if (it.id == modId) it.copy(vectorScopeApps = scopes) else it })
        }
        onMsg("Updated Vector scope (${scopes.size} apps)")
    }

    fun downloadAndFlashRepoModule(
        module: RepoModule,
        simulationMode: Boolean,
        adminVm: AdminViewModel
    ) {
        viewModelScope.launch {
            adminVm.openConsole("Download & Flash: ${module.name}", listOf("Source: ${module.sourceRepoName}", "URL: ${module.downloadUrl}"))
            val dl = RepositoryManager.downloadModuleFile(getApplication(), module)
            if (dl.isFailure) {
                adminVm.finishConsole("[error] ${dl.exceptionOrNull()?.message}")
                adminVm.emitSnackbar("Download failed: ${dl.exceptionOrNull()?.message}")
                return@launch
            }
            val file = dl.getOrThrow()
            adminVm.appendConsoleLine("Downloaded ${file.name} (${file.length()} bytes)")
            flashLocalFile(file, module.family == RepoFamily.VECTOR_XPOSED, simulationMode, adminVm)
        }
    }

    fun flashLocalUri(uri: Uri, displayName: String, simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            adminVm.openConsole("Flash Local Package: $displayName", listOf("Copying package to staging cache..."))
            val staged = File(getApplication<Application>().cacheDir, "flash_${System.currentTimeMillis()}_${displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}")
            val copyOk = runCatching {
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                    staged.outputStream().use { out -> input.copyTo(out) }
                } ?: error("Cannot read URI")
            }
            if (copyOk.isFailure) {
                adminVm.finishConsole("[error] ${copyOk.exceptionOrNull()?.message}")
                return@launch
            }
            flashLocalFile(staged, displayName.endsWith(".apk", true), simulationMode, adminVm)
        }
    }

    fun flashLocalFile(file: File, isApk: Boolean, simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val qPath = RootShellExecutor.shellQuote(file.absolutePath)
            val cmd = when {
                file.name.endsWith(".kpm", true) -> "cp $qPath /data/adb/ap/kpm/ && echo 'Staged KPM binary'"
                isApk -> "pm install -r $qPath"
                else -> "magisk --install-module $qPath || ksud module install $qPath || apd module install $qPath"
            }
            val res = RootShellExecutor.execRootStreaming(
                command = cmd,
                simulationMode = simulationMode,
                onLine = { adminVm.appendConsoleLine(it) },
                simulatedLines = listOf(
                    "[SIM] Unpacking ${file.name} ...",
                    "[SIM] Verifying SM-E156B arm64-v8a compatibility ...",
                    "[SIM] Installing module payload -> /data/adb/modules/",
                    "[SIM] Setting permissions 0755/0644 ... Done (exit=0)"
                )
            )
            if (res.isSuccess) {
                adminVm.finishConsole("FLASH COMPLETE · Reboot to activate module.")
                adminVm.emitSnackbar("Flashed ${file.name} cleanly.")
                loadInstalledModules(simulationMode)
            } else {
                adminVm.finishConsole("FLASH FAILED (exit=${res.exitCode}) · ${res.stderr}")
                adminVm.emitSnackbar("Flash failed (exit=${res.exitCode}): ${res.stderr.ifBlank { "Root required" }}")
            }
        }
    }
}
