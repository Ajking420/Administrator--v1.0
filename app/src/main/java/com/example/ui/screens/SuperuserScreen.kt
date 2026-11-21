package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.components.AdminCard
import com.example.ui.components.MonospaceScriptEditorSheet
import com.example.ui.components.SegmentedControlBar
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalAdminColors
import com.example.ui.theme.MonoCodeStyle
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.AppRootEntry

private data class SpoofPresetSpec(
    val label: String,
    val model: String,
    val brand: String,
    val fingerprint: String,
    val patch: String
)

private val SPOOF_PRESETS = listOf(
    SpoofPresetSpec("Galaxy S24 Ultra", "SM-S928B", "samsung", "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXS3AXH7:user/release-keys", "2026-08-01"),
    SpoofPresetSpec("Galaxy S25 Ultra", "SM-S938B", "samsung", "samsung/pa3qxxx/pa3q:15/AP3A.240905.015/S938BXXU1AYB2:user/release-keys", "2026-10-01"),
    SpoofPresetSpec("Pixel 9 Pro", "Pixel 9 Pro", "google", "google/caiman/caiman:15/AP3A.241005.015/12366759:user/release-keys", "2026-10-05"),
    SpoofPresetSpec("Stock SM-E156B", "SM-E156B", "samsung", "samsung/e15xnsxx/e15x:14/UP1A.231005.007/E156BXXU3BXH1:user/release-keys", "2026-08-01"),
    SpoofPresetSpec("Custom", "SM-E156B", "samsung", "samsung/e15xnsxx/e15x:14/UP1A.231005.007/E156BXXU3BXH1:user/release-keys", "2026-08-01")
)

@Composable
fun SuperuserScreen(adminVm: AdminViewModel) {
    val colors = LocalAdminColors.current
    val apps by adminVm.installedApps.collectAsStateWithLifecycle()
    val auditLogs by adminVm.auditLogs.collectAsStateWithLifecycle()
    val knoxSpoofed by adminVm.knoxSpoofed.collectAsStateWithLifecycle()
    val hideReZygisk by adminVm.hideReZygiskMounts.collectAsStateWithLifecycle()
    val cloakedName by adminVm.cloakedAppName.collectAsStateWithLifecycle()
    val hostsContent by adminVm.hostsContent.collectAsStateWithLifecycle()
    val integrity by adminVm.integrityReport.collectAsStateWithLifecycle()
    val settings by adminVm.settings.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var filterMode by remember { mutableStateOf("All") }
    var selectedProfileApp by remember { mutableStateOf<AppRootEntry?>(null) }
    var showAuditLogSheet by remember { mutableStateOf(false) }
    var showHostsEditor by remember { mutableStateOf(false) }
    var cloakInput by remember(cloakedName) { mutableStateOf(cloakedName) }

    // Spoof state synced with DataStore
    var selectedPreset by remember(settings.spoofPreset) { mutableStateOf(settings.spoofPreset) }
    var propModel by remember(settings.spoofModel) { mutableStateOf(settings.spoofModel) }
    var propBrand by remember(settings.spoofBrand) { mutableStateOf(settings.spoofBrand) }
    var propFingerprint by remember(settings.spoofFingerprint) { mutableStateOf(settings.spoofFingerprint) }
    var propPatch by remember(settings.spoofSecurityPatch) { mutableStateOf(settings.spoofSecurityPatch) }
    var expandIntegrityJson by remember { mutableStateOf(false) }

    val grantedCount by remember(apps) { derivedStateOf { apps.count { it.rootGranted } } }
    val hiddenCount by remember(apps) { derivedStateOf { apps.count { it.inDenyList } } }

    val filteredApps by remember(apps, searchQuery, filterMode) {
        derivedStateOf {
            apps.filter { app ->
                val matchesText = searchQuery.isBlank() ||
                    app.appName.contains(searchQuery, true) ||
                    app.packageName.contains(searchQuery, true)
                val matchesCat = when (filterMode) {
                    "Granted" -> app.rootGranted
                    "System" -> app.isSystem
                    "One UI" -> app.isOneUi
                    else -> true
                }
                matchesText && matchesCat
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.oledBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Compact 3-Stat Top Strip (Granted · Hidden · Integrity)
        item(key = "su_stats_strip", contentType = "stats") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatChipCard("GRANTED", "$grantedCount", colors.emerald, Modifier.weight(1f))
                StatChipCard("HIDDEN", "$hiddenCount", colors.accent, Modifier.weight(1f))
                val integrityLabel = when {
                    !integrity.checked -> "UNCHECKED"
                    integrity.meetsStrong -> "STRONG"
                    integrity.meetsDevice -> "DEVICE"
                    integrity.meetsBasic -> "BASIC"
                    else -> "FAIL"
                }
                StatChipCard(
                    "INTEGRITY",
                    integrityLabel,
                    if (integrity.meetsDevice) colors.emerald else colors.amber,
                    Modifier.weight(1f)
                )
            }
        }

        item(key = "su_segments", contentType = "segments") {
            SegmentedControlBar(
                options = listOf("Root Access", "Hide Root", "Device Spoof"),
                selectedIndex = subTab,
                onSelect = { subTab = it }
            )
        }

        when (subTab) {
            0 -> {
                item(key = "su_search_filters", contentType = "search") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                placeholder = { Text("Search installed packages...") },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                singleLine = true,
                                modifier = Modifier.weight(1f).testTag("su_search_input")
                            )
                            OutlinedButton(
                                onClick = { showAuditLogSheet = true },
                                modifier = Modifier.testTag("su_audit_log_btn")
                            ) {
                                Icon(Icons.Default.History, contentDescription = "Audit Log", modifier = Modifier.size(18.dp))
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("All", "Granted", "System", "One UI").forEach { f ->
                                FilterChip(
                                    selected = filterMode == f,
                                    onClick = { filterMode = f },
                                    label = { Text(f) }
                                )
                            }
                        }
                    }
                }

                items(filteredApps, key = { "root_${it.packageName}" }, contentType = { "app_root_row" }) { app ->
                    AdminCard(contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.appName, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (app.isOneUi) {
                                        Spacer(Modifier.width(6.dp))
                                        StatusPill("One UI", colors.violet)
                                    }
                                }
                                Text("${app.packageName} · uid=${app.uid}", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(
                                onClick = { selectedProfileApp = app },
                                modifier = Modifier.testTag("ksu_profile_${app.packageName}")
                            ) {
                                Icon(Icons.Default.Tune, contentDescription = "Per-App KSU Profile", tint = colors.accent)
                            }
                            Switch(
                                checked = app.rootGranted,
                                onCheckedChange = { adminVm.toggleAppRootGrant(app.packageName, it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = colors.emerald, checkedTrackColor = colors.emerald.copy(alpha = 0.25f))
                            )
                        }
                    }
                }
            }

            1 -> {
                item(key = "hide_root_controls", contentType = "stealth") {
                    AdminCard(borderColor = colors.accent.copy(alpha = 0.45f)) {
                        Text("Stealth & Cloaking Matrix", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                        Spacer(Modifier.height(10.dp))

                        Button(
                            onClick = { adminVm.autoIsolateBankingAndUpi() },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.emerald, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().testTag("auto_isolate_banking_btn")
                        ) {
                            Icon(Icons.Default.AccountBalance, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("One-Tap Auto-Isolate Banking & UPI Apps")
                        }

                        Spacer(Modifier.height(10.dp))
                        StealthToggleRow("Samsung Knox Warranty Bit (0x1 -> 0x0)", "Applies resetprop spoof on ro.boot.warranty_bit", knoxSpoofed) {
                            adminVm.toggleKnoxSpoof(it)
                        }
                        StealthToggleRow("Hide ReZygisk & Overlay Mounts", "Unmounts /data/adb and module mounts for DenyList UIDs", hideReZygisk) {
                            adminVm.toggleHideReZygiskMounts(it)
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = cloakInput,
                                onValueChange = { cloakInput = it },
                                label = { Text("Hide Administrator App Label") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = { adminVm.cloakAdministratorApp(cloakInput) }) {
                                Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Cloak")
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            onClick = { showHostsEditor = true },
                            modifier = Modifier.fillMaxWidth().testTag("open_hosts_editor_btn")
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Edit Systemless Hosts (/etc/hosts)")
                        }
                    }
                }

                items(apps, key = { "deny_${it.packageName}" }, contentType = { "denylist_row" }) { app ->
                    AdminCard(contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(app.appName, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1)
                                    if (app.isBankingOrUpi) {
                                        Spacer(Modifier.width(6.dp))
                                        StatusPill("BANK / UPI", colors.amber)
                                    }
                                }
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Switch(
                                checked = app.inDenyList,
                                onCheckedChange = { adminVm.toggleAppDenyList(app.packageName, it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = colors.accent, checkedTrackColor = colors.accent.copy(alpha = 0.25f))
                            )
                        }
                    }
                }
            }

            2 -> {
                item(key = "spoof_editor_card", contentType = "spoof") {
                    AdminCard(borderColor = colors.violet.copy(alpha = 0.5f)) {
                        Text("Device Identity Spoofer (resetprop)", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            SPOOF_PRESETS.take(3).forEach { p ->
                                FilterChip(
                                    selected = selectedPreset == p.label,
                                    onClick = {
                                        selectedPreset = p.label
                                        propModel = p.model
                                        propBrand = p.brand
                                        propFingerprint = p.fingerprint
                                        propPatch = p.patch
                                    },
                                    label = { Text(p.label.removePrefix("Galaxy "), maxLines = 1) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            SPOOF_PRESETS.drop(3).forEach { p ->
                                FilterChip(
                                    selected = selectedPreset == p.label,
                                    onClick = {
                                        selectedPreset = p.label
                                        propModel = p.model
                                        propBrand = p.brand
                                        propFingerprint = p.fingerprint
                                        propPatch = p.patch
                                    },
                                    label = { Text(p.label, maxLines = 1) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(value = propModel, onValueChange = { propModel = it; selectedPreset = "Custom" }, label = { Text("ro.product.model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = propBrand, onValueChange = { propBrand = it; selectedPreset = "Custom" }, label = { Text("ro.product.brand") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = propFingerprint, onValueChange = { propFingerprint = it; selectedPreset = "Custom" }, label = { Text("ro.build.fingerprint") }, textStyle = MonoCodeStyle, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(value = propPatch, onValueChange = { propPatch = it; selectedPreset = "Custom" }, label = { Text("ro.build.version.security_patch") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { adminVm.applyDeviceSpoof(selectedPreset, propModel, propBrand, propFingerprint, propPatch) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().testTag("apply_resetprop_btn")
                        ) {
                            Text("Apply via resetprop")
                        }
                    }
                }

                item(key = "play_integrity_card", contentType = "integrity") {
                    AdminCard(borderColor = colors.emerald.copy(alpha = 0.45f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = colors.emerald)
                                Spacer(Modifier.width(8.dp))
                                Text("Play Integrity Verification", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                            }
                            Button(
                                onClick = { adminVm.runPlayIntegrityCheck() },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.emerald, contentColor = Color.Black),
                                modifier = Modifier.testTag("verify_play_integrity_btn")
                            ) {
                                Text("Check")
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            StatusPill(if (integrity.meetsBasic) "BASIC: PASS" else "BASIC: --", if (integrity.meetsBasic) colors.emerald else colors.textMuted, Modifier.weight(1f))
                            StatusPill(if (integrity.meetsDevice) "DEVICE: PASS" else "DEVICE: --", if (integrity.meetsDevice) colors.emerald else colors.textMuted, Modifier.weight(1f))
                            StatusPill(if (integrity.meetsStrong) "STRONG: PASS" else "STRONG: NO", if (integrity.meetsStrong) colors.emerald else colors.amber, Modifier.weight(1f))
                        }
                        if (integrity.checked) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { expandIntegrityJson = !expandIntegrityJson },
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("JSON Attestation Breakdown", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                                Icon(
                                    if (expandIntegrityJson) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "Toggle JSON",
                                    tint = colors.accent
                                )
                            }
                            if (expandIntegrityJson) {
                                Spacer(Modifier.height(6.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF04060A))
                                        .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                                        .padding(10.dp)
                                ) {
                                    Text(integrity.rawJson, style = MonoCodeStyle, color = colors.textPrimary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    selectedProfileApp?.let { app ->
        KsuAppProfileModalSheet(
            entry = app,
            onDismiss = { selectedProfileApp = null },
            onSave = {
                adminVm.updateAppKsuProfile(it)
                selectedProfileApp = null
            }
        )
    }

    if (showAuditLogSheet) {
        RootAuditLogSheet(
            logs = auditLogs,
            onDismiss = { showAuditLogSheet = false }
        )
    }

    if (showHostsEditor) {
        MonospaceScriptEditorSheet(
            title = "Systemless Hosts Editor",
            subtitle = "/data/adb/modules/hosts/system/etc/hosts",
            initialText = hostsContent,
            onDismiss = { showHostsEditor = false },
            onSave = { adminVm.saveSystemlessHosts(it) }
        )
    }
}

@Composable
private fun StatChipCard(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    val colors = LocalAdminColors.current
    AdminCard(modifier = modifier, borderColor = tint.copy(alpha = 0.4f), contentPadding = PaddingValues(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = tint, maxLines = 1)
    }
}

@Composable
private fun StealthToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAdminColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KsuAppProfileModalSheet(
    entry: AppRootEntry,
    onDismiss: () -> Unit,
    onSave: (AppRootEntry) -> Unit
) {
    val colors = LocalAdminColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var uidText by remember(entry) { mutableStateOf(entry.customUid.toString()) }
    var gidText by remember(entry) { mutableStateOf(entry.customGid.toString()) }
    var namespace by remember(entry) { mutableStateOf(entry.mountNamespace) }
    var unmountDefault by remember(entry) { mutableStateOf(entry.unmountModulesDefault) }
    var rootLog by remember(entry) { mutableStateOf(entry.rootLogEnabled) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.cardElevated) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Per-App KSU Profile", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
            Text("${entry.appName} (${entry.packageName})", style = MaterialTheme.typography.labelSmall, color = colors.accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = uidText, onValueChange = { uidText = it.filter(Char::isDigit) }, label = { Text("Custom UID") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = gidText, onValueChange = { gidText = it.filter(Char::isDigit) }, label = { Text("Custom GID") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Text("Mount Namespace", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Inherited", "Global", "Isolated").forEach { ns ->
                    FilterChip(selected = namespace == ns, onClick = { namespace = ns }, label = { Text(ns) })
                }
            }
            StealthToggleRow("Unmount Modules by Default", "Isolates /data/adb overlayfs mounts", unmountDefault) { unmountDefault = it }
            StealthToggleRow("Root Access Audit Log", "Record su invocations in live audit stream", rootLog) { rootLog = it }
            Button(
                onClick = {
                    onSave(
                        entry.copy(
                            customUid = uidText.toIntOrNull() ?: 0,
                            customGid = gidText.toIntOrNull() ?: 0,
                            mountNamespace = namespace,
                            unmountModulesDefault = unmountDefault,
                            rootLogEnabled = rootLog
                        )
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text("Save App Profile") }
            Spacer(Modifier.height(10.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootAuditLogSheet(
    logs: List<com.example.viewmodel.RootAuditEntry>,
    onDismiss: () -> Unit
) {
    val colors = LocalAdminColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.cardElevated) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, contentDescription = null, tint = colors.accent)
                Spacer(Modifier.width(8.dp))
                Text("Live Root Access Audit Log", style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().height(320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(logs) { log ->
                    AdminCard(contentPadding = PaddingValues(10.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(log.packageName, style = MaterialTheme.typography.labelMedium, color = colors.accent)
                            Text(log.timestamp, style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                        }
                        Text(log.command, style = MonoCodeStyle, color = if (log.allowed) colors.emerald else colors.crimson)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
