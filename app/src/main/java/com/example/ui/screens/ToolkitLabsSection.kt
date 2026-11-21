package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.core.diagnostics.FaultCategory
import com.example.core.storage.ExportManager
import com.example.core.studio.ModuleOverlayEntry
import com.example.core.studio.StudioTemplate
import com.example.core.vault.EfsVaultEngine
import com.example.ui.components.AdminCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalAdminColors
import com.example.ui.theme.MonoCodeStyle
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.CrossTabEvent
import com.example.viewmodel.ToolkitUiState
import com.example.viewmodel.ToolkitViewModel

/**
 * Tab 4 Sub-Tab 4: Lab & Vault
 * 1. Crash Analyzer (pstore/last_kmsg/dmesg/logcat crash + color-coded faults + AVC->sepolicy.rule handoff)
 * 2. Magisk / APM Module Studio (4 F15 templates, module.prop, Pre-Init, Overlays, Unix-LF scripts, ZIP + Flash Immediately)
 * 3. Encrypted EFS / IMEI Vault (PBKDF2-SHA256 65,536 iter + AES-256-GCM .adminvault, Verify Integrity, Share, Restore)
 */
fun LazyListScope.toolkitLabsItems(
    adminVm: AdminViewModel,
    toolkitVm: ToolkitViewModel,
    state: ToolkitUiState,
    simulationMode: Boolean
) {
    item(key = "crash_analyzer_card", contentType = "lab_crash") {
        CrashAnalyzerCard(adminVm, toolkitVm, state, simulationMode)
    }

    item(key = "module_studio_card", contentType = "lab_studio") {
        ModuleStudioCard(adminVm, toolkitVm, state, simulationMode)
    }

    item(key = "efs_vault_card", contentType = "lab_vault") {
        EfsVaultCard(adminVm, toolkitVm, state, simulationMode)
    }
}

@Composable
private fun CrashAnalyzerCard(
    adminVm: AdminViewModel,
    toolkitVm: ToolkitViewModel,
    state: ToolkitUiState,
    simulationMode: Boolean
) {
    val colors = LocalAdminColors.current
    AdminCard(borderColor = colors.crimson.copy(alpha = 0.45f)) {
        SectionHeader("Crash Analyzer", "pstore · last_kmsg · dmesg · logcat -b crash")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { toolkitVm.runCrashAnalyzer(simulationMode, adminVm) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.crimson, contentColor = Color.White),
                modifier = Modifier.weight(1f).testTag("pull_crash_logs_btn")
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (state.analyzingCrash) "Pulling..." else "Pull & Analyze Logs")
            }
            if (state.crashReport != null) {
                OutlinedButton(
                    onClick = { toolkitVm.exportCrashReport(simulationMode, adminVm) },
                    modifier = Modifier.weight(0.75f)
                ) { Text("Export .txt") }
            }
        }

        state.crashReport?.let { rep ->
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatusPill("Panic: ${rep.panicCount}", colors.crimson)
                StatusPill("AVC: ${rep.avcCount}", colors.amber)
                StatusPill("Linker/Vector: ${rep.linkerVectorCount}", colors.violet)
                StatusPill("Module/KPM: ${rep.moduleKpmCount}", colors.accent)
            }
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF04060A))
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                rep.lines.take(14).forEach { line ->
                    val tint = when (line.category) {
                        FaultCategory.KERNEL_PANIC -> colors.crimson
                        FaultCategory.SELINUX_AVC -> colors.amber
                        FaultCategory.LINKER_APEX_VECTOR -> colors.violet
                        FaultCategory.MODULE_KPM -> colors.accent
                        FaultCategory.INFO -> colors.textSecondary
                    }
                    Text(line.raw, style = MonoCodeStyle, color = tint)
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    toolkitVm.updateStudioDraft { it.copy(sepolicyRule = rep.generatedSepolicyRules) }
                    adminVm.emitSnackbar("Injected SELinux AVC rules into Module Studio sepolicy.rule")
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.amber, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth().testTag("convert_avc_to_sepolicy_btn")
            ) {
                Text("Convert Denials to sepolicy.rule -> Module Studio")
            }
        }
    }
}

@Composable
private fun ModuleStudioCard(
    adminVm: AdminViewModel,
    toolkitVm: ToolkitViewModel,
    state: ToolkitUiState,
    simulationMode: Boolean
) {
    val colors = LocalAdminColors.current
    val draft = state.studioDraft
    var selectedScript by remember { mutableStateOf("service.sh") }
    var newOverlayPath by remember { mutableStateOf("system/etc/floating_feature.xml") }

    AdminCard(borderColor = colors.accent.copy(alpha = 0.45f)) {
        SectionHeader("Magisk / APM Module Studio", "ZipOutputStream -> Download/Administrator/Studio/")
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            StudioTemplate.entries.forEach { tpl ->
                FilterChip(
                    selected = false,
                    onClick = { toolkitVm.applyStudioTemplate(tpl) },
                    label = { Text(tpl.label) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft.id,
                onValueChange = { v -> toolkitVm.updateStudioDraft { it.copy(id = v) } },
                label = { Text("module id") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = draft.version,
                onValueChange = { v -> toolkitVm.updateStudioDraft { it.copy(version = v) } },
                label = { Text("version") },
                singleLine = true,
                modifier = Modifier.weight(0.7f)
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = draft.name,
            onValueChange = { v -> toolkitVm.updateStudioDraft { it.copy(name = v) } },
            label = { Text("Module Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = draft.preInitMount,
                onCheckedChange = { c -> toolkitVm.updateStudioDraft { it.copy(preInitMount = c) } }
            )
            Text("Enable Pre-Init Mount (module.prop preinit=1)", style = MaterialTheme.typography.bodySmall, color = colors.textPrimary)
        }

        // Overlays manager
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newOverlayPath,
                onValueChange = { newOverlayPath = it },
                label = { Text("Overlay target path") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = {
                if (newOverlayPath.isNotBlank()) {
                    toolkitVm.updateStudioDraft {
                        it.copy(overlays = it.overlays + ModuleOverlayEntry(newOverlayPath.trim(), "# SM-E156B overlay\n"))
                    }
                }
            }) { Text("+ Overlay") }
        }
        if (draft.overlays.isNotEmpty()) {
            Text("Overlays (${draft.overlays.size}): ${draft.overlays.joinToString { it.targetSystemPath }}", style = MaterialTheme.typography.labelSmall, color = colors.accent)
        }

        Spacer(Modifier.height(8.dp))
        val scriptTabs = listOf("system.prop", "sepolicy.rule", "post-fs-data.sh", "service.sh", "action.sh", "customize.sh")
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            scriptTabs.forEach { tab ->
                FilterChip(selected = selectedScript == tab, onClick = { selectedScript = tab }, label = { Text(tab) })
            }
        }

        val currentScriptBody = when (selectedScript) {
            "system.prop" -> draft.systemProp
            "sepolicy.rule" -> draft.sepolicyRule
            "post-fs-data.sh" -> draft.postFsDataSh
            "service.sh" -> draft.serviceSh
            "action.sh" -> draft.actionSh
            else -> draft.customizeSh
        }
        OutlinedTextField(
            value = currentScriptBody,
            onValueChange = { text ->
                val lf = text.replace("\r\n", "\n")
                toolkitVm.updateStudioDraft {
                    when (selectedScript) {
                        "system.prop" -> it.copy(systemProp = lf)
                        "sepolicy.rule" -> it.copy(sepolicyRule = lf)
                        "post-fs-data.sh" -> it.copy(postFsDataSh = lf)
                        "service.sh" -> it.copy(serviceSh = lf)
                        "action.sh" -> it.copy(actionSh = lf)
                        else -> it.copy(customizeSh = lf)
                    }
                }
            },
            label = { Text("$selectedScript (Unix LF)") },
            textStyle = MonoCodeStyle,
            modifier = Modifier.fillMaxWidth().height(130.dp)
        )

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { toolkitVm.buildStudioModuleZip(simulationMode, adminVm) },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                modifier = Modifier.weight(1f).testTag("build_studio_zip_btn")
            ) {
                Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Build ZIP")
            }
            Button(
                onClick = {
                    toolkitVm.buildStudioModuleZip(simulationMode, adminVm) { builtPath ->
                        adminVm.emitCrossTab(CrossTabEvent.FlashLocalModulePath(builtPath))
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.emerald, contentColor = Color.Black),
                modifier = Modifier.weight(1f).testTag("flash_studio_immediately_btn")
            ) { Text("Flash Immediately") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EfsVaultCard(
    adminVm: AdminViewModel,
    toolkitVm: ToolkitViewModel,
    state: ToolkitUiState,
    simulationMode: Boolean
) {
    val colors = LocalAdminColors.current
    val context = LocalContext.current
    var passphrase by remember { mutableStateOf("") }
    var pendingVaultAction by remember { mutableStateOf<String?>(null) }

    val vaultFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            when (pendingVaultAction) {
                "verify" -> toolkitVm.verifyEfsVault(uri, passphrase, adminVm)
                "restore" -> toolkitVm.restoreEfsVault(uri, passphrase, simulationMode, adminVm)
            }
        }
        pendingVaultAction = null
    }

    AdminCard(borderColor = colors.amber.copy(alpha = 0.55f)) {
        SectionHeader(
            "Encrypted EFS / IMEI Vault (.adminvault)",
            "PBKDF2-SHA256 (65,536 iter + 16B salt) · AES-256-GCM (12B IV)"
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EfsVaultEngine.DEFAULT_IMEI_PARTITIONS.forEach { p ->
                val sel = p in state.vaultPartitions
                FilterChip(
                    selected = sel,
                    onClick = { toolkitVm.toggleVaultPartition(p) },
                    label = { Text(p, style = MonoCodeStyle) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.amber.copy(alpha = 0.22f),
                        selectedLabelColor = colors.amber
                    )
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = { Text("Vault Passphrase (>= 6 chars)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("efs_vault_passphrase_input")
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { toolkitVm.createEfsVault(passphrase, simulationMode, adminVm) },
            colors = ButtonDefaults.buttonColors(containerColor = colors.amber, contentColor = Color.Black),
            modifier = Modifier.fillMaxWidth().testTag("create_efs_vault_btn")
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Seal Encrypted .adminvault Snapshot")
        }

        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    pendingVaultAction = "verify"
                    vaultFilePicker.launch(arrayOf("*/*"))
                },
                modifier = Modifier.weight(1f).testTag("verify_efs_vault_btn")
            ) {
                Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Verify", maxLines = 1)
            }
            OutlinedButton(
                onClick = {
                    val uri = state.lastVaultShareUri
                    if (uri != null) {
                        context.startActivity(ExportManager.buildShareIntent(uri, "Share Encrypted SM-E156B EFS Vault"))
                    } else {
                        adminVm.emitSnackbar("Create an encrypted .adminvault snapshot first to share.")
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Share", maxLines = 1)
            }
            Button(
                onClick = {
                    pendingVaultAction = "restore"
                    vaultFilePicker.launch(arrayOf("*/*"))
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.crimson, contentColor = Color.White),
                modifier = Modifier.weight(1f).testTag("restore_efs_vault_btn")
            ) {
                Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Restore", maxLines = 1)
            }
        }

        state.vaultVerifyResult?.let { vr ->
            Spacer(Modifier.height(8.dp))
            StatusPill(vr.message, if (vr.valid) colors.emerald else colors.crimson)
        }
    }
}
