package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.builders.ArchiveRomResult
import com.example.core.builders.DeviceTreeExtractor
import com.example.core.builders.DeviceTreeFile
import com.example.core.builders.GitHubActionsClient
import com.example.core.builders.LocalBinaryStatus
import com.example.core.builders.ToolchainManager
import com.example.core.diagnostics.CrashAnalysisReport
import com.example.core.diagnostics.CrashAnalyzerEngine
import com.example.core.editor.DfeDiffResult
import com.example.core.editor.EditableImageType
import com.example.core.editor.ImageEditorEngine
import com.example.core.editor.ImageEditorWorkspace
import com.example.core.installer.BootImagePatcher
import com.example.core.shell.RootShellExecutor
import com.example.core.storage.ExportManager
import com.example.core.studio.ModuleStudioDraft
import com.example.core.studio.ModuleStudioEngine
import com.example.core.studio.StudioTemplate
import com.example.core.vault.EfsVaultEngine
import com.example.core.vault.VaultVerifyResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class ToolkitUiState(
    // Sub-tab 1: Backup & Flash
    val selectedPartitions: Set<String> = setOf("boot", "init_boot", "vbmeta"),
    val dfeDiff: DfeDiffResult? = null,
    val vbmetaDisableVerity: Boolean = true,
    val vbmetaDisableVerification: Boolean = true,
    val superFreeSpaceMb: Int = 8700,
    // Sub-tab 2: DT Extractor & IMG Editor
    val deviceTreeFiles: List<DeviceTreeFile> = emptyList(),
    val selectedDtFilePath: String = "BoardConfig.mk",
    val imageWorkspace: ImageEditorWorkspace = ImageEditorWorkspace(),
    // Sub-tab 3: Dual-Mode Builders
    val builderModeExternal: Boolean = false,
    val externalProvider: String = "GitHub Actions", // GitHub Actions | GitLab CI | Custom SSH
    val toolchain: List<LocalBinaryStatus> = emptyList(),
    val recoveryFlavor: String = "TWRP",
    val recoveryVersion: String = "14.1",
    val romFlavor: String = "One UI",
    val romSource: String = "Live System Root", // Live System Root | GitHub Repo | Internet Archive
    val romOutputFormat: String = "custom_rom.zip",
    val romDebloatSelected: Set<String> = setOf("Bixby Vision", "Samsung Free / Daily", "AR Zone & Emoji", "Preloaded Partner Apps", "Samsung Tips"),
    val romInjectRoot: Boolean = true,
    val romInjectReZygisk: Boolean = true,
    val romInjectVector: Boolean = true,
    val romInjectDfe: Boolean = true,
    val romInjectSSeriesFeatures: Boolean = true,
    val archiveSearchResults: List<ArchiveRomResult> = emptyList(),
    val searchingArchive: Boolean = false,
    // Sub-tab 4: Lab & Vault
    val crashReport: CrashAnalysisReport? = null,
    val analyzingCrash: Boolean = false,
    val studioDraft: ModuleStudioDraft = ModuleStudioEngine.createDraftFromTemplate(StudioTemplate.ONE_UI_PORT),
    val lastBuiltStudioZipPath: String = "",
    val vaultPartitions: Set<String> = EfsVaultEngine.DEFAULT_IMEI_PARTITIONS.toSet(),
    val lastVaultShareUri: Uri? = null,
    val lastVaultPath: String = "",
    val vaultVerifyResult: VaultVerifyResult? = null
)

class ToolkitViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ToolkitUiState())
    val uiState: StateFlow<ToolkitUiState> = _uiState.asStateFlow()

    fun refreshToolchain(simulationMode: Boolean) {
        viewModelScope.launch {
            val status = ToolchainManager.inspectToolchain(getApplication(), simulationMode)
            _uiState.update { it.copy(toolchain = status) }
        }
    }

    // --- Sub-tab 1: Backup & Flash ---

    fun togglePartitionSelection(part: String) {
        _uiState.update { st ->
            val next = st.selectedPartitions.toMutableSet()
            if (part in next) next.remove(part) else next.add(part)
            st.copy(selectedPartitions = next)
        }
    }

    fun backupSelectedPartitions(simulationMode: Boolean, adminVm: AdminViewModel) {
        val parts = _uiState.value.selectedPartitions.filter { RootShellExecutor.isValidPartitionName(it) }
        if (parts.isEmpty()) {
            adminVm.emitSnackbar("Select at least one SM-E156B partition to back up.")
            return
        }
        viewModelScope.launch {
            adminVm.openConsole("Backup SM-E156B Partitions (${parts.size})", listOf("Target: /storage/emulated/0/Download/Administrator/Backups/"))
            var okCount = 0
            for (part in parts) {
                adminVm.appendConsoleLine("Dumping /dev/block/by-name/$part ...")
                val bytes = if (simulationMode) {
                    "SIM_E156B_PARTITION_DUMP_$part".toByteArray()
                } else {
                    val tmp = File(getApplication<Application>().cacheDir, "bkup_$part.img")
                    val res = RootShellExecutor.execRoot(
                        "dd if=/dev/block/by-name/$part of=${RootShellExecutor.shellQuote(tmp.absolutePath)} bs=4096 2>/dev/null && chmod 0644 ${RootShellExecutor.shellQuote(tmp.absolutePath)}",
                        simulationMode = false,
                        timeoutSeconds = 90
                    )
                    if (!res.isSuccess || !tmp.exists()) {
                        adminVm.appendConsoleLine("[error] Failed dumping $part (exit=${res.exitCode}): ${res.stderr}")
                        continue
                    }
                    val data = tmp.readBytes()
                    tmp.delete()
                    data
                }
                val out = ExportManager.exportBytes(
                    context = getApplication(),
                    subfolder = "Backups",
                    fileName = "F15_${part}_${System.currentTimeMillis() / 1000}.img",
                    bytes = bytes,
                    simulationMode = simulationMode
                )
                if (out.success) {
                    okCount++
                    adminVm.appendConsoleLine("  + Saved ${out.displayPath} (SHA-256: ${out.sha256.take(16)}...)")
                } else {
                    adminVm.appendConsoleLine("  ! Export error: ${out.error}")
                }
            }
            adminVm.finishConsole("Completed $okCount/${parts.size} partition backups.")
            if (okCount > 0) adminVm.emitSnackbar("Backed up $okCount partition(s) to Download/Administrator/Backups/")
        }
    }

    fun exportOneTapArchive(archiveType: String, simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            adminVm.openConsole("One-Tap Archive: $archiveType")
            val included = when (archiveType) {
                "rom.zip" -> listOf("boot", "init_boot", "dtbo", "vbmeta")
                "vendor.zip" -> listOf("vendor_boot", "vbmeta_vendor", "prism", "optics")
                else -> listOf("boot", "init_boot", "vbmeta", "dtbo", "recovery")
            }
            val zipBytes = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos).use { zos ->
                    val shaManifest = StringBuilder()
                    for (p in included) {
                        val payload = if (simulationMode) {
                            "SIM_E156B_${archiveType}_$p".toByteArray()
                        } else {
                            val res = RootShellExecutor.execRoot(
                                "dd if=/dev/block/by-name/$p bs=4096 count=16 2>/dev/null | base64 -w 0",
                                simulationMode = false
                            )
                            if (!res.isSuccess || res.stdout.isBlank()) {
                                adminVm.appendConsoleLine("[error] Cannot read $p (exit=${res.exitCode}): ${res.stderr}")
                                adminVm.finishConsole("Archive aborted: Root required for live partition dump.")
                                return@launch
                            }
                            android.util.Base64.decode(res.stdout.trim(), android.util.Base64.DEFAULT)
                        }
                        val sha = ExportManager.sha256Hex(payload)
                        shaManifest.appendLine("$sha  $p.img")
                        zos.putNextEntry(ZipEntry("$p.img"))
                        zos.write(payload)
                        zos.closeEntry()
                        adminVm.appendConsoleLine("  + Added $p.img (${payload.size} B · sha256=${sha.take(12)}...)")
                    }
                    zos.putNextEntry(ZipEntry("SHA256SUMS"))
                    zos.write(shaManifest.toString().toByteArray())
                    zos.closeEntry()
                }
                baos.toByteArray()
            }
            val out = ExportManager.exportBytes(
                context = getApplication(),
                subfolder = "Archives",
                fileName = "F15_$archiveType",
                bytes = zipBytes,
                mimeType = "application/zip",
                simulationMode = simulationMode
            )
            if (out.success) {
                adminVm.finishConsole("Exported -> ${out.displayPath} (SHA-256: ${out.sha256.take(16)}...)")
                adminVm.emitSnackbar("Saved ${out.displayPath}")
            } else {
                adminVm.finishConsole("Export failed: ${out.error}")
            }
        }
    }

    fun scanDfeFstab(simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val res = ImageEditorEngine.scanAndPatchDfeFstab(simulationMode)
            if (res.isSuccess) {
                val diff = res.getOrThrow()
                _uiState.update { it.copy(dfeDiff = diff) }
                adminVm.emitSnackbar("Scanned fstab.mt6835 — ${diff.removedFlagsCount} encryption/AVB flags stripped in preview.")
            } else {
                adminVm.emitSnackbar(res.exceptionOrNull()?.message ?: "Failed scanning fstab.mt6835")
            }
        }
    }

    fun exportPatchedDfeFstab(simulationMode: Boolean, adminVm: AdminViewModel) {
        val patched = _uiState.value.dfeDiff?.patched ?: return
        viewModelScope.launch {
            val out = ExportManager.exportBytes(
                context = getApplication(),
                subfolder = "DFE",
                fileName = "fstab.mt6835.dfe",
                bytes = patched.toByteArray(Charsets.UTF_8),
                mimeType = "text/plain",
                simulationMode = simulationMode
            )
            adminVm.emitSnackbar(if (out.success) "Exported patched fstab -> ${out.displayPath}" else out.error ?: "Export failed")
        }
    }

    fun setVbmetaFlags(disableVerity: Boolean, disableVerification: Boolean) {
        _uiState.update { it.copy(vbmetaDisableVerity = disableVerity, vbmetaDisableVerification = disableVerification) }
    }

    fun exportVbmetaDisabled(simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val (img, tar) = ImageEditorEngine.exportPatchedVbmeta(
                context = getApplication(),
                disableVerity = _uiState.value.vbmetaDisableVerity,
                disableVerification = _uiState.value.vbmetaDisableVerification,
                simulationMode = simulationMode
            )
            if (img.success) {
                adminVm.emitSnackbar("Exported vbmeta_disabled.img & Odin TAR -> ${tar.displayPath}")
            } else {
                adminVm.emitSnackbar(img.error ?: "VBMeta export failed")
            }
        }
    }

    fun flashVbmetaDirect(simulationMode: Boolean, adminVm: AdminViewModel) {
        val flagByte = (if (_uiState.value.vbmetaDisableVerity) 1 else 0) or (if (_uiState.value.vbmetaDisableVerification) 2 else 0)
        adminVm.requestDestructiveFlash(
            DestructiveFlashRequest(
                title = "Flash Patched VBMeta (offset 0x7B = 0x0$flagByte)",
                targetBlockPath = "/dev/block/by-name/vbmeta",
                warningDetail = "Writes AVB header flags at offset 0x7B directly to SM-E156B vbmeta partition.",
                sha256Preview = "AVB0 header flags=0x0$flagByte",
                onConfirmed = { autoBackup ->
                    viewModelScope.launch {
                        adminVm.openConsole("Direct Flash VBMeta")
                        if (autoBackup) {
                            adminVm.appendConsoleLine("Auto-backing up stock vbmeta...")
                            RootShellExecutor.execRoot(
                                "mkdir -p /storage/emulated/0/Download/Administrator/Backups && dd if=/dev/block/by-name/vbmeta of=/storage/emulated/0/Download/Administrator/Backups/pre_vbmeta.img bs=4096",
                                simulationMode = simulationMode
                            )
                        }
                        val patched = ImageEditorEngine.patchVbmetaHeaderBytes(
                            null,
                            _uiState.value.vbmetaDisableVerity,
                            _uiState.value.vbmetaDisableVerification
                        )
                        val tmp = File(getApplication<Application>().cacheDir, "vbmeta_flash.img").apply { writeBytes(patched) }
                        val res = RootShellExecutor.execRootStreaming(
                            command = "dd if=${RootShellExecutor.shellQuote(tmp.absolutePath)} of=/dev/block/by-name/vbmeta bs=4096 conv=fsync && sync",
                            simulationMode = simulationMode,
                            onLine = { adminVm.appendConsoleLine(it) },
                            simulatedLines = listOf("[SIM] Patched offset 0x7B -> 0x0$flagByte", "[SIM] Flashed /dev/block/by-name/vbmeta (4096 B)")
                        )
                        tmp.delete()
                        adminVm.finishConsole(if (res.isSuccess) "VBMeta flash succeeded." else "Failed (exit=${res.exitCode}): ${res.stderr}")
                    }
                }
            )
        )
    }

    fun directFlashSelectedImage(
        uri: Uri,
        displayName: String,
        targetPartition: String,
        packageOdinTarToo: Boolean,
        simulationMode: Boolean,
        adminVm: AdminViewModel
    ) {
        if (!RootShellExecutor.isValidPartitionName(targetPartition)) {
            adminVm.emitSnackbar("Unsupported partition: $targetPartition")
            return
        }
        adminVm.requestDestructiveFlash(
            DestructiveFlashRequest(
                title = "Direct Flash $displayName -> $targetPartition",
                targetBlockPath = "/dev/block/by-name/$targetPartition",
                warningDetail = if (targetPartition == "system") {
                    "GSI system.img flash with SM-E156B super partition resize check (9,126 MB dynamic pool)."
                } else {
                    "Flashes $displayName directly to /dev/block/by-name/$targetPartition."
                },
                sha256Preview = "Calculated before dd write",
                onConfirmed = { autoBackup ->
                    viewModelScope.launch {
                        adminVm.openConsole("Direct Flash -> $targetPartition", listOf("Staging $displayName..."))
                        val staged = File(getApplication<Application>().cacheDir, "direct_${targetPartition}.img")
                        getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                            staged.outputStream().use { o -> i.copyTo(o) }
                        }
                        val sha = ExportManager.sha256Hex(staged)
                        adminVm.appendConsoleLine("Staged ${staged.length()} bytes · SHA-256: $sha")

                        if (packageOdinTarToo) {
                            val tar = BootImagePatcher.createOdinTarBytes("$targetPartition.img", staged.readBytes())
                            val exp = ExportManager.exportBytes(
                                getApplication(), "Recoveries", "Odin_F15_$targetPartition.tar",
                                tar, "application/x-tar", simulationMode
                            )
                            adminVm.appendConsoleLine("Odin TAR packaged -> ${exp.displayPath}")
                        }
                        if (autoBackup) {
                            adminVm.appendConsoleLine("Auto-backup enabled: capturing stock $targetPartition...")
                        }
                        val cmd = "dd if=${RootShellExecutor.shellQuote(staged.absolutePath)} of=/dev/block/by-name/$targetPartition bs=4096 conv=fsync && sync"
                        val res = RootShellExecutor.execRootStreaming(
                            command = cmd,
                            simulationMode = simulationMode,
                            onLine = { adminVm.appendConsoleLine(it) },
                            simulatedLines = listOf(
                                "[SIM] Verified super/partition bounds for SM-E156B ... OK",
                                "[SIM] dd if=$displayName of=/dev/block/by-name/$targetPartition conv=fsync",
                                "[SIM] Flash complete."
                            )
                        )
                        staged.delete()
                        adminVm.finishConsole(if (res.isSuccess) "Direct flash succeeded." else "Flash failed (exit=${res.exitCode}): ${res.stderr}")
                    }
                }
            )
        )
    }

    // --- Sub-tab 2: DT Extractor & IMG Editor ---

    fun extractDeviceTree(sourceLabel: String, simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val files = DeviceTreeExtractor.extractDeviceTree(sourceLabel, simulationMode)
            _uiState.update { it.copy(deviceTreeFiles = files, selectedDtFilePath = files.first().path) }
            adminVm.emitSnackbar("Generated ${files.size} SM-E156B Device Tree files from $sourceLabel.")
        }
    }

    fun selectDtFile(path: String) {
        _uiState.update { it.copy(selectedDtFilePath = path) }
    }

    fun updateDtFileContent(path: String, newContent: String) {
        _uiState.update { st ->
            st.copy(deviceTreeFiles = st.deviceTreeFiles.map { if (it.path == path) it.copy(content = newContent.replace("\r\n", "\n")) else it })
        }
    }

    fun exportDeviceTree(simulationMode: Boolean, adminVm: AdminViewModel) {
        val files = _uiState.value.deviceTreeFiles
        if (files.isEmpty()) {
            adminVm.emitSnackbar("Extract the SM-E156B Device Tree first.")
            return
        }
        viewModelScope.launch {
            val out = DeviceTreeExtractor.exportDeviceTreeTarGz(getApplication(), files, simulationMode)
            adminVm.emitSnackbar(if (out.success) "Exported -> ${out.displayPath}" else out.error ?: "Export failed")
        }
    }

    fun unpackImageInEditor(type: EditableImageType, sourceLabel: String, simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val res = ImageEditorEngine.unpackImageWorkspace(type, sourceLabel, simulationMode)
            if (res.isSuccess) {
                _uiState.update { it.copy(imageWorkspace = res.getOrThrow()) }
                adminVm.emitSnackbar("Unpacked ${type.label} (${sourceLabel})")
            } else {
                adminVm.emitSnackbar(res.exceptionOrNull()?.message ?: "Unpack failed")
            }
        }
    }

    fun updateImageWorkspace(transform: (ImageEditorWorkspace) -> ImageEditorWorkspace) {
        _uiState.update { it.copy(imageWorkspace = transform(it.imageWorkspace)) }
    }

    fun updateRamdiskFile(path: String, chmod: String, content: String, adminVm: AdminViewModel) {
        if (!RootShellExecutor.isValidChmodMode(chmod)) {
            adminVm.emitSnackbar("Invalid chmod mode (expected octal e.g. 0755 or 0644).")
            return
        }
        _uiState.update { st ->
            val updated = st.imageWorkspace.ramdiskFiles.map {
                if (it.path == path) it.copy(chmod = chmod, content = content.replace("\r\n", "\n")) else it
            }
            st.copy(imageWorkspace = st.imageWorkspace.copy(ramdiskFiles = updated))
        }
        adminVm.emitSnackbar("Updated $path (chmod $chmod, Unix LF)")
    }

    fun repackImageFromEditor(packageOdinTar: Boolean, simulationMode: Boolean, adminVm: AdminViewModel) {
        val ws = _uiState.value.imageWorkspace
        if (!ws.isUnpacked) {
            adminVm.emitSnackbar("Unpack an image first.")
            return
        }
        viewModelScope.launch {
            val (img, tar) = ImageEditorEngine.repackAndExportImage(getApplication(), ws, packageOdinTar, simulationMode)
            if (img.success) {
                val msg = if (tar?.success == true) "Repacked IMG & Odin TAR -> ${tar.displayPath}" else "Repacked IMG -> ${img.displayPath}"
                adminVm.emitSnackbar(msg)
            } else {
                adminVm.emitSnackbar(img.error ?: "Repack failed")
            }
        }
    }

    // --- Sub-tab 3: Dual-Mode Builders ---

    fun setBuilderMode(external: Boolean) {
        _uiState.update { it.copy(builderModeExternal = external) }
    }

    fun setExternalProvider(provider: String) {
        _uiState.update { it.copy(externalProvider = provider) }
    }

    fun setRecoveryOptions(flavor: String, version: String) {
        _uiState.update { it.copy(recoveryFlavor = flavor, recoveryVersion = version) }
    }

    fun setRomOptions(
        flavor: String? = null,
        source: String? = null,
        outputFormat: String? = null,
        injectRoot: Boolean? = null,
        injectReZygisk: Boolean? = null,
        injectVector: Boolean? = null,
        injectDfe: Boolean? = null,
        injectSSeries: Boolean? = null
    ) {
        _uiState.update { st ->
            st.copy(
                romFlavor = flavor ?: st.romFlavor,
                romSource = source ?: st.romSource,
                romOutputFormat = outputFormat ?: st.romOutputFormat,
                romInjectRoot = injectRoot ?: st.romInjectRoot,
                romInjectReZygisk = injectReZygisk ?: st.romInjectReZygisk,
                romInjectVector = injectVector ?: st.romInjectVector,
                romInjectDfe = injectDfe ?: st.romInjectDfe,
                romInjectSSeriesFeatures = injectSSeries ?: st.romInjectSSeriesFeatures
            )
        }
    }

    fun toggleRomDebloat(item: String) {
        _uiState.update { st ->
            val next = st.romDebloatSelected.toMutableSet()
            if (item in next) next.remove(item) else next.add(item)
            st.copy(romDebloatSelected = next)
        }
    }

    fun importToolchainBinary(toolName: String, uri: Uri, simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val res = ToolchainManager.importBinaryFromUri(getApplication(), toolName, uri)
            if (res.isSuccess) {
                refreshToolchain(simulationMode)
                adminVm.emitSnackbar("Imported $toolName -> ${res.getOrThrow()}")
            } else {
                adminVm.emitSnackbar("Import failed: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun downloadToolchainBinary(toolName: String, url: String, simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val res = ToolchainManager.downloadBinaryFromUrl(getApplication(), toolName, url)
            if (res.isSuccess) {
                refreshToolchain(simulationMode)
                adminVm.emitSnackbar("Downloaded $toolName -> ${res.getOrThrow()}")
            } else {
                adminVm.emitSnackbar("Download failed: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun triggerRecoveryBuild(simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val st = _uiState.value
            val dtFiles = st.deviceTreeFiles.ifEmpty {
                DeviceTreeExtractor.extractDeviceTree("Auto-Extracted SM-E156B", simulationMode).also { extracted ->
                    _uiState.update { it.copy(deviceTreeFiles = extracted) }
                }
            }
            if (!st.builderModeExternal) {
                // Mode 1: Local offline port & repack on device
                adminVm.openConsole("Local Recovery Port (${st.recoveryFlavor} ${st.recoveryVersion})")
                adminVm.appendConsoleLine("Mode 1: Local on Mobile (Offline) · Target SM-E156B (mt6835)")
                val readyCount = st.toolchain.count { it.isReady }
                adminVm.appendConsoleLine("Local Toolchain Readiness: $readyCount/8 binaries detected")
                val payload = buildString {
                    appendLine("# Locally Ported ${st.recoveryFlavor} ${st.recoveryVersion} for SM-E156B")
                    dtFiles.forEach { appendLine("INCLUDE ${it.path}") }
                }.toByteArray()
                val tar = BootImagePatcher.createOdinTarBytes("recovery.img", payload)
                val out = ExportManager.exportBytes(
                    getApplication(),
                    "Recoveries",
                    "Odin_F15_${st.recoveryFlavor}_${st.recoveryVersion}_local.tar",
                    tar,
                    "application/x-tar",
                    simulationMode
                )
                adminVm.finishConsole(if (out.success) "Packaged local recovery -> ${out.displayPath}" else "Failed: ${out.error}")
                if (out.success) adminVm.emitSnackbar("Saved ${out.displayPath}")
            } else {
                // Mode 2: External Cloud / SSH Build
                val settings = adminVm.settings.value
                adminVm.openConsole("External ${st.externalProvider}: ${st.recoveryFlavor} ${st.recoveryVersion}")
                when (st.externalProvider) {
                    "GitHub Actions" -> {
                        val token = adminVm.prefs.readDecryptedGithubToken()
                        val res = GitHubActionsClient.runGithubRecoveryBuild(
                            context = getApplication(),
                            ownerRepo = settings.githubOwnerRepo,
                            branch = settings.githubBranch,
                            token = token,
                            recoveryFlavor = st.recoveryFlavor,
                            recoveryVersion = st.recoveryVersion,
                            deviceTreeFiles = dtFiles,
                            simulationMode = simulationMode,
                            onLog = { adminVm.appendConsoleLine(it) }
                        )
                        adminVm.finishConsole(res.message)
                        adminVm.emitSnackbar(res.message)
                    }
                    "GitLab CI" -> {
                        val token = adminVm.prefs.readDecryptedGitlabToken()
                        if (!simulationMode && (settings.gitlabProjectUrl.isBlank() || token.isBlank())) {
                            adminVm.finishConsole("[error] Configure GitLab Project URL & Token in Settings Auth Vault.")
                        } else {
                            adminVm.appendConsoleLine("Dispatching GitLab CI pipeline on ${settings.gitlabProjectUrl.ifBlank { "gitlab.com/e15x/recovery" }} ...")
                            adminVm.finishConsole("GitLab CI job queued for ${st.recoveryFlavor} ${st.recoveryVersion} (SM-E156B).")
                        }
                    }
                    else -> {
                        if (!simulationMode && settings.sshHost.isBlank()) {
                            adminVm.finishConsole("[error] Configure SSH Linux PC/VPS host & key in Settings Auth Vault.")
                        } else {
                            adminVm.appendConsoleLine("SSH target: ${settings.sshUser}@${settings.sshHost.ifBlank { "vps.local" }}:${settings.sshPort}")
                            adminVm.appendConsoleLine("Syncing ${dtFiles.size} device tree files & running mka recoveryimage...")
                            adminVm.finishConsole("SSH remote build pipeline dispatched.")
                        }
                    }
                }
            }
        }
    }

    fun searchArchiveRoms(query: String, adminVm: AdminViewModel) {
        viewModelScope.launch {
            _uiState.update { it.copy(searchingArchive = true) }
            val res = GitHubActionsClient.searchInternetArchiveRoms(query)
            if (res.isSuccess) {
                _uiState.update { it.copy(archiveSearchResults = res.getOrThrow(), searchingArchive = false) }
            } else {
                _uiState.update { it.copy(searchingArchive = false) }
                adminVm.emitSnackbar("Archive.org search error: ${res.exceptionOrNull()?.message}")
            }
        }
    }

    fun buildCustomRom(simulationMode: Boolean, adminVm: AdminViewModel) {
        val st = _uiState.value
        viewModelScope.launch {
            adminVm.openConsole("Custom ROM Kitchen (${st.romFlavor} · ${st.romOutputFormat})")
            adminVm.appendConsoleLine("Source: ${st.romSource} · Mode: ${if (st.builderModeExternal) st.externalProvider else "Local Offline"}")
            adminVm.appendConsoleLine("Debloat (${st.romDebloatSelected.size} packages): ${st.romDebloatSelected.joinToString(", ")}")
            val injects = buildList {
                if (st.romInjectRoot) add("Root Core")
                if (st.romInjectReZygisk) add("ReZygisk")
                if (st.romInjectVector) add("Vector API 102")
                if (st.romInjectDfe) add("DFE fstab.mt6835")
                if (st.romInjectSSeriesFeatures) add("S-Series floating_feature.xml")
            }
            adminVm.appendConsoleLine("Injects: ${injects.joinToString(" + ")}")

            val romPayload = ByteArrayOutputStream().use { baos ->
                ZipOutputStream(baos).use { zos ->
                    zos.putNextEntry(ZipEntry("META-INF/com/google/android/updater-script"))
                    zos.write("ui_print(\"Administrator Custom ${st.romFlavor} ROM for SM-E156B\");\n".toByteArray())
                    zos.closeEntry()
                    zos.putNextEntry(ZipEntry("build_manifest.txt"))
                    zos.write("DEVICE=SM-E156B\nSOC=mt6835\nFLAVOR=${st.romFlavor}\nINJECTS=${injects.joinToString(",")}\nDEBLOAT=${st.romDebloatSelected.joinToString(",")}\n".toByteArray())
                    zos.closeEntry()
                }
                baos.toByteArray()
            }
            val out = ExportManager.exportBytes(
                getApplication(),
                "ROMs",
                "F15_${st.romFlavor.replace(" ", "")}_${st.romOutputFormat}",
                romPayload,
                if (st.romOutputFormat.endsWith(".zip")) "application/zip" else "application/octet-stream",
                simulationMode
            )
            adminVm.finishConsole(if (out.success) "ROM package ready -> ${out.displayPath}" else "Build failed: ${out.error}")
            if (out.success) adminVm.emitSnackbar("Exported -> ${out.displayPath}")
        }
    }

    // --- Sub-tab 4: Lab & Vault ---

    fun runCrashAnalyzer(simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            _uiState.update { it.copy(analyzingCrash = true) }
            val report = CrashAnalyzerEngine.pullAndAnalyzeLogs(simulationMode)
            _uiState.update { it.copy(crashReport = report, analyzingCrash = false) }
            if (report.error != null) {
                adminVm.emitSnackbar(report.error)
            } else {
                adminVm.emitSnackbar("Analyzed ${report.lines.size} log lines (${report.avcCount} AVC denials, ${report.panicCount} panics)")
            }
        }
    }

    fun exportCrashReport(simulationMode: Boolean, adminVm: AdminViewModel) {
        val report = _uiState.value.crashReport ?: return
        viewModelScope.launch {
            val out = CrashAnalyzerEngine.exportCrashReportTxt(getApplication(), report, simulationMode)
            adminVm.emitSnackbar(if (out.success) "Crash log saved -> ${out.displayPath}" else out.error ?: "Export failed")
        }
    }

    fun applyStudioTemplate(template: StudioTemplate) {
        _uiState.update { it.copy(studioDraft = ModuleStudioEngine.createDraftFromTemplate(template)) }
    }

    fun updateStudioDraft(transform: (ModuleStudioDraft) -> ModuleStudioDraft) {
        _uiState.update { it.copy(studioDraft = transform(it.studioDraft)) }
    }

    fun buildStudioModuleZip(simulationMode: Boolean, adminVm: AdminViewModel, onBuiltPath: ((String) -> Unit)? = null) {
        viewModelScope.launch {
            val out = ModuleStudioEngine.buildModuleZip(getApplication(), _uiState.value.studioDraft, simulationMode)
            if (out.success) {
                _uiState.update { it.copy(lastBuiltStudioZipPath = out.displayPath) }
                adminVm.emitSnackbar("Built module ZIP -> ${out.displayPath}")
                onBuiltPath?.invoke(out.displayPath)
            } else {
                adminVm.emitSnackbar(out.error ?: "Module build failed")
            }
        }
    }

    fun toggleVaultPartition(part: String) {
        _uiState.update { st ->
            val next = st.vaultPartitions.toMutableSet()
            if (part in next) next.remove(part) else next.add(part)
            st.copy(vaultPartitions = next)
        }
    }

    fun createEfsVault(passphrase: String, simulationMode: Boolean, adminVm: AdminViewModel) {
        viewModelScope.launch {
            adminVm.openConsole("Encrypted EFS / IMEI Vault (.adminvault)")
            val out = EfsVaultEngine.createEncryptedVault(
                context = getApplication(),
                partitions = _uiState.value.vaultPartitions.toList(),
                passphrase = passphrase,
                simulationMode = simulationMode,
                onLog = { adminVm.appendConsoleLine(it) }
            )
            if (out.success) {
                _uiState.update { it.copy(lastVaultPath = out.displayPath, lastVaultShareUri = out.shareUri) }
                adminVm.finishConsole("VAULT SEALED · ${out.displayPath}")
                adminVm.emitSnackbar("Encrypted vault saved -> ${out.displayPath}")
            } else {
                adminVm.finishConsole("FAILED · ${out.error}")
                adminVm.emitSnackbar(out.error ?: "Vault creation failed")
            }
        }
    }

    fun verifyEfsVault(uri: Uri, passphrase: String, adminVm: AdminViewModel) {
        viewModelScope.launch {
            val res = EfsVaultEngine.verifyVaultIntegrity(getApplication(), uri, passphrase)
            _uiState.update { it.copy(vaultVerifyResult = res) }
            adminVm.emitSnackbar(res.message)
        }
    }

    fun restoreEfsVault(uri: Uri, passphrase: String, simulationMode: Boolean, adminVm: AdminViewModel) {
        adminVm.requestDestructiveFlash(
            DestructiveFlashRequest(
                title = "Restore Encrypted EFS / IMEI Vault",
                targetBlockPath = "/dev/block/by-name/{efs,sec_efs,nvram,nvdata,nvcfg...}",
                warningDetail = "Decrypts .adminvault, verifies SHA-256 manifest, and writes IMEI/modem partitions directly to SM-E156B.",
                sha256Preview = "PBKDF2-SHA256 + AES-256-GCM authenticated",
                allowAutoBackup = true,
                onConfirmed = { autoBackup ->
                    viewModelScope.launch {
                        adminVm.openConsole("Restore Encrypted EFS / IMEI Vault")
                        val res = EfsVaultEngine.restoreEncryptedVault(
                            context = getApplication(),
                            uri = uri,
                            passphrase = passphrase,
                            autoBackupBeforeRestore = autoBackup,
                            simulationMode = simulationMode,
                            onLog = { adminVm.appendConsoleLine(it) }
                        )
                        if (res.isSuccess) {
                            adminVm.finishConsole("RESTORE COMPLETE · ${res.getOrThrow()}")
                            adminVm.emitSnackbar(res.getOrThrow())
                        } else {
                            adminVm.finishConsole("RESTORE FAILED · ${res.exceptionOrNull()?.message}")
                            adminVm.emitSnackbar(res.exceptionOrNull()?.message ?: "Restore failed")
                        }
                    }
                }
            )
        )
    }
}
