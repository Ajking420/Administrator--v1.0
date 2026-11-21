package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.editor.EditableImageType
import com.example.core.editor.RamdiskEntry
import com.example.ui.components.AdminCard
import com.example.ui.components.MonospaceScriptEditorSheet
import com.example.ui.components.SectionHeader
import com.example.ui.components.SegmentedControlBar
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalAdminColors
import com.example.ui.theme.MonoCodeStyle
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.ToolkitViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ToolkitScreen(
    adminVm: AdminViewModel,
    toolkitVm: ToolkitViewModel,
    requestedSubTab: Int? = null,
    onConsumedRequestedSubTab: () -> Unit = {},
    onOpenSettingsVault: () -> Unit = {}
) {
    val colors = LocalAdminColors.current
    val settings by adminVm.settings.collectAsStateWithLifecycle()
    val state by toolkitVm.uiState.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) }
    var editingDtPath by remember { mutableStateOf<String?>(null) }
    var editingRamdiskEntry by remember { mutableStateOf<RamdiskEntry?>(null) }
    var pendingToolchainImport by remember { mutableStateOf<String?>(null) }
    var downloadToolDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var archiveQuery by remember { mutableStateOf("SM-E156B OneUI GSI") }

    LaunchedEffect(requestedSubTab) {
        if (requestedSubTab != null) {
            subTab = requestedSubTab.coerceIn(0, 3)
            onConsumedRequestedSubTab()
        }
    }

    LaunchedEffect(settings.simulationMode) {
        toolkitVm.refreshToolchain(settings.simulationMode)
    }

    val gsiPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toolkitVm.directFlashSelectedImage(
                uri, uri.lastPathSegment?.substringAfterLast("/") ?: "system.img",
                "system", false, settings.simulationMode, adminVm
            )
        }
    }
    val recoveryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            toolkitVm.directFlashSelectedImage(
                uri, uri.lastPathSegment?.substringAfterLast("/") ?: "recovery.img",
                "recovery", true, settings.simulationMode, adminVm
            )
        }
    }
    val dtStockPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: "stock_recovery.img"
            toolkitVm.extractDeviceTree(name, settings.simulationMode, adminVm)
        }
    }
    val toolchainImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val tool = pendingToolchainImport
        if (uri != null && tool != null) {
            toolkitVm.importToolchainBinary(tool, uri, settings.simulationMode, adminVm)
        }
        pendingToolchainImport = null
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.oledBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "toolkit_subtabs", contentType = "segments") {
            SegmentedControlBar(
                options = listOf("Backup/Flash", "DT & IMG", "Builders", "Lab & Vault"),
                selectedIndex = subTab,
                onSelect = { subTab = it }
            )
        }

        when (subTab) {
            // SUB-TAB 1: BACKUP & FLASH
            0 -> {
                item(key = "partition_selector", contentType = "card") {
                    AdminCard {
                        SectionHeader("SM-E156B Partition Selector", "/dev/block/by-name/ (A-only Samsung layout)")
                        Spacer(Modifier.height(6.dp))
                        PartitionChipGroup("Boot", listOf("boot", "init_boot", "vendor_boot", "recovery", "dtbo"), state.selectedPartitions, colors.accent) { toolkitVm.togglePartitionSelection(it) }
                        PartitionChipGroup("AVB", listOf("vbmeta", "vbmeta_system", "vbmeta_vendor"), state.selectedPartitions, colors.violet) { toolkitVm.togglePartitionSelection(it) }
                        PartitionChipGroup("Dynamic", listOf("super", "prism", "optics", "persist"), state.selectedPartitions, colors.emerald) { toolkitVm.togglePartitionSelection(it) }
                        PartitionChipGroup("IMEI / EFS (Critical)", listOf("efs", "sec_efs", "nvram", "nvdata", "nvcfg"), state.selectedPartitions, colors.amber) { toolkitVm.togglePartitionSelection(it) }

                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { toolkitVm.backupSelectedPartitions(settings.simulationMode, adminVm) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().testTag("backup_partitions_btn")
                        ) {
                            Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Backup ${state.selectedPartitions.size} Selected (.img + SHA-256)")
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf("rom.zip", "vendor.zip", "firmware.zip").forEach { archive ->
                                OutlinedButton(
                                    onClick = { toolkitVm.exportOneTapArchive(archive, settings.simulationMode, adminVm) },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(archive, maxLines = 1)
                                }
                            }
                        }
                    }
                }

                item(key = "dfe_patcher", contentType = "card") {
                    AdminCard(borderColor = colors.violet.copy(alpha = 0.45f)) {
                        SectionHeader("DFE Patcher (fstab.mt6835)", "Strips fileencryption / forceencrypt / inlinecrypt / avb")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { toolkitVm.scanDfeFstab(settings.simulationMode, adminVm) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.violet, contentColor = Color.White),
                                modifier = Modifier.weight(1f).testTag("scan_dfe_btn")
                            ) { Text("Scan & Diff Preview") }
                            if (state.dfeDiff != null) {
                                OutlinedButton(
                                    onClick = { toolkitVm.exportPatchedDfeFstab(settings.simulationMode, adminVm) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Export Patched") }
                            }
                        }
                        state.dfeDiff?.let { diff ->
                            Spacer(Modifier.height(8.dp))
                            StatusPill("Stripped ${diff.removedFlagsCount} encryption/AVB flags", colors.emerald)
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF04060A))
                                    .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                                    .padding(8.dp)
                            ) {
                                Text(diff.patched, style = MonoCodeStyle, color = colors.emerald)
                            }
                        }
                    }
                }

                item(key = "vbmeta_patcher", contentType = "card") {
                    AdminCard(borderColor = colors.amber.copy(alpha = 0.45f)) {
                        SectionHeader("VBMeta AVB Patcher (Offset 0x7B)", "Outputs vbmeta_disabled.img + Odin .tar or Direct Flash")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = state.vbmetaDisableVerity, onCheckedChange = { toolkitVm.setVbmetaFlags(it, state.vbmetaDisableVerification) })
                            Text("--disable-verity (0x01)", style = MonoCodeStyle, color = colors.textPrimary)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = state.vbmetaDisableVerification, onCheckedChange = { toolkitVm.setVbmetaFlags(state.vbmetaDisableVerity, it) })
                            Text("--disable-verification (0x02)", style = MonoCodeStyle, color = colors.textPrimary)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { toolkitVm.exportVbmetaDisabled(settings.simulationMode, adminVm) },
                                modifier = Modifier.weight(1f).testTag("export_vbmeta_btn")
                            ) { Text("Export IMG + TAR") }
                            Button(
                                onClick = { toolkitVm.flashVbmetaDirect(settings.simulationMode, adminVm) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.amber, contentColor = Color.Black),
                                modifier = Modifier.weight(1f).testTag("flash_vbmeta_btn")
                            ) { Text("Direct Flash") }
                        }
                    }
                }

                item(key = "direct_flasher", contentType = "card") {
                    AdminCard(borderColor = colors.crimson.copy(alpha = 0.45f)) {
                        SectionHeader("Direct Flasher (Slide-to-Confirm Protected)", "Super pool: ${state.superFreeSpaceMb} MB · Odin .tar packager")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { gsiPicker.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f).testTag("flash_gsi_btn")
                            ) { Text("Flash GSI system.img") }
                            Button(
                                onClick = { recoveryPicker.launch(arrayOf("*/*")) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.crimson, contentColor = Color.White),
                                modifier = Modifier.weight(1f).testTag("flash_recovery_btn")
                            ) { Text("Flash recovery.img") }
                        }
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(
                            onClick = { adminVm.executeRebootAction("Recovery") },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Reboot to Recovery") }
                    }
                }
            }

            // SUB-TAB 2: DT EXTRACTOR & IMG EDITOR
            1 -> {
                item(key = "dt_extractor_card", contentType = "card") {
                    AdminCard(borderColor = colors.accent.copy(alpha = 0.45f)) {
                        SectionHeader("Galaxy F15 Device Tree Extractor", "Generates 10 editable files (Android 15/16+ /linkerconfig & APEX fixes)")
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(
                                onClick = { toolkitVm.extractDeviceTree("Live SM-E156B Partition Dump", settings.simulationMode, adminVm) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                                modifier = Modifier.weight(1f).testTag("extract_dt_live_btn")
                            ) { Text("Extract Live") }
                            OutlinedButton(
                                onClick = { dtStockPicker.launch(arrayOf("*/*")) },
                                modifier = Modifier.weight(1f).testTag("extract_dt_picker_btn")
                            ) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Pick Stock IMG")
                            }
                        }

                        if (state.deviceTreeFiles.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                state.deviceTreeFiles.forEach { f ->
                                    FilterChip(
                                        selected = state.selectedDtFilePath == f.path,
                                        onClick = { toolkitVm.selectDtFile(f.path) },
                                        label = { Text(f.path) }
                                    )
                                }
                            }
                            val activeFile = state.deviceTreeFiles.firstOrNull { it.path == state.selectedDtFilePath }
                            if (activeFile != null) {
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF04060A))
                                        .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(activeFile.content.lines().take(12).joinToString("\n"), style = MonoCodeStyle, color = colors.textPrimary)
                                }
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(onClick = { editingDtPath = activeFile.path }, modifier = Modifier.weight(1f)) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Edit File")
                                    }
                                    OutlinedButton(onClick = { toolkitVm.exportDeviceTree(settings.simulationMode, adminVm) }, modifier = Modifier.weight(1f)) {
                                        Text("Export .tar.gz")
                                    }
                                    Button(
                                        onClick = { subTab = 2 },
                                        colors = ButtonDefaults.buttonColors(containerColor = colors.violet, contentColor = Color.White),
                                        modifier = Modifier.weight(1f)
                                    ) { Text("To Builder") }
                                }
                            }
                        }
                    }
                }

                item(key = "img_editor_card", contentType = "card") {
                    val ws = state.imageWorkspace
                    AdminCard(borderColor = colors.violet.copy(alpha = 0.45f)) {
                        SectionHeader("Full Android IMG Editor", "Header/Cmdline, Kernel/DTB/DTBO swap, Ramdisk chmod & Unix-LF")
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            EditableImageType.entries.forEach { t ->
                                FilterChip(
                                    selected = ws.imageType == t,
                                    onClick = { toolkitVm.unpackImageInEditor(t, "Live /dev/block/by-name/${t.defaultPartition}", settings.simulationMode, adminVm) },
                                    label = { Text(t.label) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        if (!ws.isUnpacked) {
                            Button(
                                onClick = { toolkitVm.unpackImageInEditor(ws.imageType, "Live SM-E156B", settings.simulationMode, adminVm) },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.violet, contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth().testTag("unpack_img_btn")
                            ) { Text("Unpack ${ws.imageType.label}") }
                        } else {
                            StatusPill(
                                "Size Check: ${ws.estimatedSizeBytes / (1024 * 1024)} MB / ${ws.maxPartitionBytes / (1024 * 1024)} MB OK",
                                colors.emerald
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = ws.cmdline,
                                onValueChange = { c -> toolkitVm.updateImageWorkspace { it.copy(cmdline = c) } },
                                label = { Text("Kernel Cmdline (Header v${ws.headerVersion} · Page ${ws.pageSize})") },
                                textStyle = MonoCodeStyle,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = {
                                        toolkitVm.updateImageWorkspace { it.copy(kernelLabel = "custom-mt6835-ksu-5.15.img") }
                                        adminVm.emitSnackbar("Swapped Kernel payload in workspace")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Swap Kernel", maxLines = 1) }
                                OutlinedButton(
                                    onClick = {
                                        toolkitVm.updateImageWorkspace { it.copy(dtbLabel = "mt6835-oc-dtbo.dtb") }
                                        adminVm.emitSnackbar("Swapped DTB/DTBO payload in workspace")
                                    },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Swap DTB/O", maxLines = 1) }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text("Ramdisk Files (Tap to edit chmod + Unix-LF)", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                            ws.ramdiskFiles.forEach { entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { editingRamdiskEntry = entry }
                                        .padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(entry.path, style = MonoCodeStyle, color = colors.accent)
                                    Text("chmod ${entry.chmod}", style = MonoCodeStyle, color = colors.amber)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { toolkitVm.repackImageFromEditor(true, settings.simulationMode, adminVm) },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                                    modifier = Modifier.weight(1f)
                                ) { Text("Repack + Odin TAR") }
                                OutlinedButton(
                                    onClick = { toolkitVm.repackImageFromEditor(false, settings.simulationMode, adminVm) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("Repack IMG Only") }
                            }
                        }
                    }
                }
            }

            // SUB-TAB 3: DUAL-MODE BUILDERS
            2 -> {
                item(key = "builder_mode_selector", contentType = "card") {
                    AdminCard(borderColor = colors.accent.copy(alpha = 0.5f)) {
                        SectionHeader("Build Execution Mode", "Offline on SM-E156B vs. External CI / SSH")
                        Spacer(Modifier.height(8.dp))
                        SegmentedControlBar(
                            options = listOf("Mode 1: Local Mobile", "Mode 2: External CI/SSH"),
                            selectedIndex = if (state.builderModeExternal) 1 else 0,
                            onSelect = { toolkitVm.setBuilderMode(it == 1) }
                        )
                        Spacer(Modifier.height(10.dp))

                        if (!state.builderModeExternal) {
                            val ready = state.toolchain.count { it.isReady }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Local 8-Binary ARM64 Toolchain", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                                StatusPill("$ready / 8 READY", if (ready == 8) colors.emerald else colors.amber)
                            }
                            Spacer(Modifier.height(6.dp))
                            state.toolchain.forEach { bin ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(bin.name, style = MonoCodeStyle, color = if (bin.isReady) colors.emerald else colors.amber)
                                            Spacer(Modifier.width(6.dp))
                                            Text(if (bin.isReady) "OK" else "MISSING", style = MaterialTheme.typography.labelSmall, color = if (bin.isReady) colors.emerald else colors.crimson)
                                        }
                                        Text(bin.resolvedPath, style = MaterialTheme.typography.labelSmall, color = colors.textMuted, maxLines = 1)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                pendingToolchainImport = bin.name
                                                toolchainImportPicker.launch(arrayOf("*/*"))
                                            },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.UploadFile, contentDescription = "Import ${bin.name}", modifier = Modifier.size(14.dp))
                                        }
                                        OutlinedButton(
                                            onClick = { downloadToolDialog = bin.name to bin.downloadHintUrl },
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Default.Download, contentDescription = "Download ${bin.name}", modifier = Modifier.size(14.dp))
                                        }
                                    }
                                }
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                listOf("GitHub Actions", "GitLab CI", "Custom SSH").forEach { prov ->
                                    FilterChip(
                                        selected = state.externalProvider == prov,
                                        onClick = { toolkitVm.setExternalProvider(prov) },
                                        label = { Text(prov, maxLines = 1) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            val configured = when (state.externalProvider) {
                                "GitHub Actions" -> settings.hasGithubToken && settings.githubOwnerRepo.isNotBlank()
                                "GitLab CI" -> settings.hasGitlabToken && settings.gitlabProjectUrl.isNotBlank()
                                else -> settings.sshHost.isNotBlank()
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(
                                    if (configured) "VAULT AUTH READY" else "CONFIGURE VAULT CREDENTIALS",
                                    if (configured) colors.emerald else colors.amber
                                )
                                OutlinedButton(onClick = onOpenSettingsVault) {
                                    Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Auth Vault")
                                }
                            }
                        }
                    }
                }

                item(key = "recovery_builder_card", contentType = "card") {
                    AdminCard(borderColor = colors.violet.copy(alpha = 0.5f)) {
                        SectionHeader("Custom Recovery Builder (SM-E156B)", "TWRP / OFOX / PBRP / SHRP · Exports Odin_F15_*.tar")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("TWRP", "OFOX", "PBRP", "SHRP").forEach { f ->
                                FilterChip(selected = state.recoveryFlavor == f, onClick = { toolkitVm.setRecoveryOptions(f, state.recoveryVersion) }, label = { Text(f) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("12.1", "14.1").forEach { v ->
                                FilterChip(selected = state.recoveryVersion == v, onClick = { toolkitVm.setRecoveryOptions(state.recoveryFlavor, v) }, label = { Text("Android $v") })
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { toolkitVm.triggerRecoveryBuild(settings.simulationMode, adminVm) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.violet, contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().testTag("start_recovery_build_btn")
                        ) {
                            Icon(if (state.builderModeExternal) Icons.Default.Cloud else Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (state.builderModeExternal) "Trigger ${state.externalProvider} Recovery Build" else "Build Local Offline Recovery TAR")
                        }
                    }
                }

                item(key = "rom_kitchen_card", contentType = "card") {
                    AdminCard(borderColor = colors.emerald.copy(alpha = 0.45f)) {
                        SectionHeader("Custom ROM Kitchen (One UI / AOSP)", "Safe Samsung Debloat + Root/ReZygisk/Vector/DFE/S-Series Injects")
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("One UI", "AOSP").forEach { fl ->
                                FilterChip(selected = state.romFlavor == fl, onClick = { toolkitVm.setRomOptions(flavor = fl) }, label = { Text(fl) })
                            }
                            listOf("custom_rom.zip", "super.img").forEach { fmt ->
                                FilterChip(selected = state.romOutputFormat == fmt, onClick = { toolkitVm.setRomOptions(outputFormat = fmt) }, label = { Text(fmt) })
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Live System Root", "GitHub Repo", "Internet Archive").forEach { src ->
                                FilterChip(selected = state.romSource == src, onClick = { toolkitVm.setRomOptions(source = src) }, label = { Text(src) })
                            }
                        }

                        if (state.romSource == "Internet Archive") {
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(
                                    value = archiveQuery,
                                    onValueChange = { archiveQuery = it },
                                    label = { Text("archive.org Query") },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(onClick = { toolkitVm.searchArchiveRoms(archiveQuery, adminVm) }) {
                                    Icon(Icons.Default.Search, contentDescription = "Search Archive.org")
                                }
                            }
                            state.archiveSearchResults.take(4).forEach { item ->
                                Text("• ${item.title} (${item.downloads} dl)", style = MonoCodeStyle, color = colors.accent, modifier = Modifier.padding(top = 4.dp))
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        Text("Safe Samsung Debloat Checklist", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Bixby Vision", "Samsung Free / Daily", "AR Zone & Emoji", "Preloaded Partner Apps", "Samsung Tips", "Knox Analytics").forEach { pkg ->
                                FilterChip(
                                    selected = pkg in state.romDebloatSelected,
                                    onClick = { toolkitVm.toggleRomDebloat(pkg) },
                                    label = { Text(pkg) }
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Text("ROM Injects", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = state.romInjectRoot, onClick = { toolkitVm.setRomOptions(injectRoot = !state.romInjectRoot) }, label = { Text("Root Core") })
                            FilterChip(selected = state.romInjectReZygisk, onClick = { toolkitVm.setRomOptions(injectReZygisk = !state.romInjectReZygisk) }, label = { Text("ReZygisk") })
                            FilterChip(selected = state.romInjectVector, onClick = { toolkitVm.setRomOptions(injectVector = !state.romInjectVector) }, label = { Text("Vector") })
                            FilterChip(selected = state.romInjectDfe, onClick = { toolkitVm.setRomOptions(injectDfe = !state.romInjectDfe) }, label = { Text("DFE") })
                            FilterChip(selected = state.romInjectSSeriesFeatures, onClick = { toolkitVm.setRomOptions(injectSSeries = !state.romInjectSSeriesFeatures) }, label = { Text("S-Series Features") })
                        }

                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { toolkitVm.buildCustomRom(settings.simulationMode, adminVm) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.emerald, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().testTag("build_custom_rom_btn")
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Build ${state.romFlavor} (${state.romOutputFormat})")
                        }
                    }
                }
            }

            // SUB-TAB 4: LAB & VAULT (Delegated cleanly to ToolkitLabsSection.kt)
            3 -> {
                toolkitLabsItems(
                    adminVm = adminVm,
                    toolkitVm = toolkitVm,
                    state = state,
                    simulationMode = settings.simulationMode
                )
            }
        }
    }

    editingDtPath?.let { path ->
        val file = state.deviceTreeFiles.firstOrNull { it.path == path }
        if (file != null) {
            MonospaceScriptEditorSheet(
                title = "Edit ${file.path}",
                subtitle = "device/samsung/e15x/${file.path}",
                initialText = file.content,
                onDismiss = { editingDtPath = null },
                onSave = { toolkitVm.updateDtFileContent(path, it) }
            )
        }
    }

    editingRamdiskEntry?.let { entry ->
        var chmodInput by remember(entry) { mutableStateOf(entry.chmod) }
        var bodyInput by remember(entry) { mutableStateOf(entry.content) }
        AlertDialog(
            onDismissRequest = { editingRamdiskEntry = null },
            title = { Text("Ramdisk: ${entry.path}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = chmodInput, onValueChange = { chmodInput = it }, label = { Text("chmod (octal)") }, singleLine = true)
                    OutlinedTextField(value = bodyInput, onValueChange = { bodyInput = it.replace("\r\n", "\n") }, label = { Text("Unix-LF Content") }, textStyle = MonoCodeStyle, modifier = Modifier.height(180.dp))
                }
            },
            confirmButton = {
                Button(onClick = {
                    toolkitVm.updateRamdiskFile(entry.path, chmodInput, bodyInput, adminVm)
                    editingRamdiskEntry = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingRamdiskEntry = null }) { Text("Cancel") } }
        )
    }

    downloadToolDialog?.let { (toolName, hintUrl) ->
        var urlInput by remember(toolName) { mutableStateOf(hintUrl) }
        AlertDialog(
            onDismissRequest = { downloadToolDialog = null },
            title = { Text("Download ARM64 Binary: $toolName") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Provide a direct HTTPS ARM64 ELF download URL for $toolName.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = urlInput, onValueChange = { urlInput = it }, label = { Text("HTTPS Binary URL") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(onClick = {
                    toolkitVm.downloadToolchainBinary(toolName, urlInput, settings.simulationMode, adminVm)
                    downloadToolDialog = null
                }) { Text("Download") }
            },
            dismissButton = { TextButton(onClick = { downloadToolDialog = null }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PartitionChipGroup(
    label: String,
    partitions: List<String>,
    selected: Set<String>,
    tint: Color,
    onToggle: (String) -> Unit
) {
    val colors = LocalAdminColors.current
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            partitions.forEach { p ->
                val isSel = p in selected
                FilterChip(
                    selected = isSel,
                    onClick = { onToggle(p) },
                    label = { Text(p, style = MonoCodeStyle) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = tint.copy(alpha = 0.2f),
                        selectedLabelColor = tint
                    )
                )
            }
        }
    }
}
