package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.repos.RepositoryManager
import com.example.core.shell.RootAccessState
import com.example.core.storage.ExportManager
import com.example.ui.components.AdminCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.LocalAdminColors
import com.example.ui.theme.MonoCodeStyle
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.ModulesViewModel
import com.example.viewmodel.ToolkitViewModel
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    adminVm: AdminViewModel,
    modulesVm: ModulesViewModel,
    toolkitVm: ToolkitViewModel,
    onBack: () -> Unit
) {
    BackHandler { onBack() }

    val colors = LocalAdminColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val settings by adminVm.settings.collectAsStateWithLifecycle()
    val rootState by adminVm.rootState.collectAsStateWithLifecycle()
    val hasAllFiles by adminVm.hasAllFilesAccess.collectAsStateWithLifecycle()
    val modulesState by modulesVm.uiState.collectAsStateWithLifecycle()
    val toolkitState by toolkitVm.uiState.collectAsStateWithLifecycle()
    val githubAuthRes by adminVm.githubAuthResult.collectAsStateWithLifecycle()

    var customRepoInput by remember { mutableStateOf("https://") }
    var ghOwnerRepo by remember(settings.githubOwnerRepo) { mutableStateOf(settings.githubOwnerRepo) }
    var ghBranch by remember(settings.githubBranch) { mutableStateOf(settings.githubBranch) }
    var ghTokenInput by remember { mutableStateOf("") }
    var glProjectUrl by remember(settings.gitlabProjectUrl) { mutableStateOf(settings.gitlabProjectUrl) }
    var glTokenInput by remember { mutableStateOf("") }
    var sshHost by remember(settings.sshHost) { mutableStateOf(settings.sshHost) }
    var sshPort by remember(settings.sshPort) { mutableStateOf(settings.sshPort.toString()) }
    var sshUser by remember(settings.sshUser) { mutableStateOf(settings.sshUser) }
    var sshKeyLoadedLabel by remember(settings.hasSshKey) {
        mutableStateOf(if (settings.hasSshKey) "Encrypted key stored in Android Keystore" else "No SSH private key imported")
    }

    val sshKeyPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val keyText = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
                if (keyText.isNotBlank()) {
                    adminVm.prefs.saveSshAuth(sshHost, sshPort.toIntOrNull() ?: 22, sshUser, keyText)
                    sshKeyLoadedLabel = "SSH key encrypted via Android Keystore (${keyText.length} bytes)"
                    adminVm.emitSnackbar("SSH private key encrypted & stored in Auth Vault")
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.oledBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "settings_header", contentType = "header") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_btn")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.textPrimary)
                }
                Column {
                    Text("Administrator Settings", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
                    Text("SM-E156B · Keystore Vault · Live Multi-Repos", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                }
            }
        }

        // Root & Storage Status Cards
        item(key = "settings_root_storage", contentType = "card") {
            AdminCard(borderColor = colors.accent.copy(alpha = 0.45f)) {
                SectionHeader("Privileges & Public Storage")
                Spacer(Modifier.height(6.dp))
                val rootText = when (val r = rootState) {
                    is RootAccessState.Granted -> "GRANTED (${r.idOutput})"
                    is RootAccessState.Denied -> "DENIED (exit=${r.exitCode})"
                    is RootAccessState.Simulated -> "SIMULATION MODE"
                    else -> "CHECKING..."
                }
                val rootTint = when (rootState) {
                    is RootAccessState.Granted -> colors.emerald
                    is RootAccessState.Denied -> colors.crimson
                    else -> colors.amber
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Superuser Access (su -c id)", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                        Text(rootText, style = MonoCodeStyle, color = rootTint)
                    }
                    Button(
                        onClick = { adminVm.requestRootPermission() },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                        modifier = Modifier.testTag("settings_request_root_btn")
                    ) {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Request Root")
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Samsung My Files Public Export", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                        Text(
                            if (hasAllFiles) "MANAGE_EXTERNAL_STORAGE Granted" else "Grant All Files Access for /Download/Administrator/",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (hasAllFiles) colors.emerald else colors.amber
                        )
                    }
                    OutlinedButton(
                        onClick = { context.startActivity(ExportManager.createAllFilesAccessIntent(context)) },
                        modifier = Modifier.testTag("settings_grant_storage_btn")
                    ) {
                        Text(if (hasAllFiles) "Manage" else "Grant")
                    }
                }
            }
        }

        // Auto-Updates & Vector Framework
        item(key = "settings_updates_vector", contentType = "card") {
            AdminCard {
                SectionHeader("Auto-Updates & Vector Framework")
                SettingsSwitchRow("Auto-Updates Master Switch", settings.autoUpdatesMaster) { v ->
                    scope.launch { adminVm.prefs.setAutoUpdateFlags(master = v) }
                }
                SettingsSwitchRow("  • Magisk / Kitsune Root Core", settings.autoUpdateMagiskCore) { v ->
                    scope.launch { adminVm.prefs.setAutoUpdateFlags(magisk = v) }
                }
                SettingsSwitchRow("  • Vector Framework & ReZygisk", settings.autoUpdateVectorService) { v ->
                    scope.launch { adminVm.prefs.setAutoUpdateFlags(vector = v) }
                }
                SettingsSwitchRow("  • Installed Community Modules", settings.autoUpdateInstalledModules) { v ->
                    scope.launch { adminVm.prefs.setAutoUpdateFlags(modules = v) }
                }
                SettingsSwitchRow("Vector Framework Master Switch (API 102)", settings.vectorFrameworkEnabled) { v ->
                    adminVm.toggleCoreService("vector", v)
                }
            }
        }

        // Anti-Bootloop Config (30-120s Slider)
        item(key = "settings_antibootloop", contentType = "card") {
            AdminCard(borderColor = colors.emerald.copy(alpha = 0.4f)) {
                SectionHeader("Anti-Bootloop Guardian Config")
                SettingsSwitchRow("Auto-Bootloop Watchdog", settings.antiBootloopWatchdog) { v ->
                    scope.launch { adminVm.prefs.setAntiBootloopConfig(watchdog = v) }
                }
                SettingsSwitchRow("Power-Key 3s Panic Trigger", settings.powerKeyPanicTrigger) { v ->
                    scope.launch { adminVm.prefs.setAntiBootloopConfig(powerPanic = v) }
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Boot Watchdog Timeout", style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    Text("${settings.bootloopTimeoutSec}s", style = MonoCodeStyle, color = colors.accent)
                }
                Slider(
                    value = settings.bootloopTimeoutSec.toFloat(),
                    onValueChange = { v -> scope.launch { adminVm.prefs.setAntiBootloopConfig(timeoutSec = v.toInt()) } },
                    valueRange = 30f..120f,
                    colors = SliderDefaults.colors(thumbColor = colors.emerald, activeTrackColor = colors.emerald)
                )
            }
        }

        // Live Repository Manager
        item(key = "settings_repo_manager", contentType = "card") {
            AdminCard(borderColor = colors.violet.copy(alpha = 0.45f)) {
                SectionHeader(
                    "Live Multi-Repository Manager",
                    "All 9 Magisk/KSU/APatch & Vector/Xposed JSON feeds",
                    trailing = {
                        IconButton(onClick = { modulesVm.loadAndRefreshRepos(settings.disabledRepoIds, settings.customRepoUrls) }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh All Repos", tint = colors.accent)
                        }
                    }
                )
                RepositoryManager.PRELOADED_REPOS.forEach { repo ->
                    val enabled = repo.id !in settings.disabledRepoIds
                    val count = modulesState.repoCounts[repo.id] ?: 0
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(repo.name, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                            Text("$count live modules · ${repo.family.name}", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { on -> scope.launch { adminVm.prefs.toggleRepoEnabled(repo.id, on) } }
                        )
                    }
                }

                settings.customRepoUrls.forEach { url ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(url, style = MonoCodeStyle, color = colors.emerald, modifier = Modifier.weight(1f), maxLines = 1)
                        IconButton(onClick = { scope.launch { adminVm.prefs.removeCustomRepoUrl(url) } }) {
                            Icon(Icons.Default.Delete, contentDescription = "Remove custom repo", tint = colors.crimson, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = customRepoInput,
                        onValueChange = { customRepoInput = it },
                        label = { Text("Add Custom JSON Repo URL (https://)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        scope.launch {
                            val ok = adminVm.prefs.addCustomRepoUrl(customRepoInput)
                            adminVm.emitSnackbar(if (ok) "Added custom repo URL" else "URL must start with https://")
                            if (ok) customRepoInput = "https://"
                        }
                    }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Repo")
                    }
                }
            }
        }

        // External Build Auth Vault (Android Keystore AES-GCM Encrypted DataStore)
        item(key = "settings_auth_vault", contentType = "card") {
            AdminCard(borderColor = colors.amber.copy(alpha = 0.45f)) {
                SectionHeader("External Build Auth Vault", "Android Keystore AES-256-GCM Encrypted DataStore")
                Spacer(Modifier.height(6.dp))

                Text("GitHub Actions (PAT / Device OAuth)", style = MaterialTheme.typography.titleSmall, color = colors.accent)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = ghOwnerRepo, onValueChange = { ghOwnerRepo = it }, label = { Text("owner/repo") }, singleLine = true, modifier = Modifier.weight(1.4f))
                    OutlinedTextField(value = ghBranch, onValueChange = { ghBranch = it }, label = { Text("branch") }, singleLine = true, modifier = Modifier.weight(0.7f))
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = ghTokenInput,
                    onValueChange = { ghTokenInput = it },
                    label = { Text(if (settings.hasGithubToken) "GitHub PAT (Stored · enter to replace)" else "GitHub PAT (repo + workflow)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                adminVm.prefs.saveGithubAuth(ghOwnerRepo, ghBranch, ghTokenInput.ifBlank { null })
                                ghTokenInput = ""
                                adminVm.emitSnackbar("Saved GitHub Auth in Keystore Vault")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save GitHub") }
                    OutlinedButton(
                        onClick = { adminVm.testGithubAuthVault() },
                        modifier = Modifier.weight(1f).testTag("test_github_scopes_btn")
                    ) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Test Scopes")
                    }
                }
                githubAuthRes?.let { res ->
                    Spacer(Modifier.height(4.dp))
                    StatusPill(res.message, if (res.ok) colors.emerald else colors.crimson)
                }

                Spacer(Modifier.height(10.dp))
                Text("GitLab CI", style = MaterialTheme.typography.titleSmall, color = colors.violet)
                OutlinedTextField(value = glProjectUrl, onValueChange = { glProjectUrl = it }, label = { Text("GitLab Project URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = glTokenInput,
                        onValueChange = { glTokenInput = it },
                        label = { Text(if (settings.hasGitlabToken) "GitLab Token (Stored)" else "GitLab Trigger Token") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(onClick = {
                        scope.launch {
                            adminVm.prefs.saveGitlabAuth(glProjectUrl, glTokenInput.ifBlank { null })
                            glTokenInput = ""
                            adminVm.emitSnackbar("Saved GitLab CI Vault credentials")
                        }
                    }) { Text("Save") }
                }

                Spacer(Modifier.height(10.dp))
                Text("Custom SSH Linux PC / VPS", style = MaterialTheme.typography.titleSmall, color = colors.emerald)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(value = sshHost, onValueChange = { sshHost = it }, label = { Text("Host / IP") }, singleLine = true, modifier = Modifier.weight(1.3f))
                    OutlinedTextField(value = sshPort, onValueChange = { sshPort = it.filter(Char::isDigit) }, label = { Text("Port") }, singleLine = true, modifier = Modifier.weight(0.6f))
                    OutlinedTextField(value = sshUser, onValueChange = { sshUser = it }, label = { Text("User") }, singleLine = true, modifier = Modifier.weight(0.8f))
                }
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { sshKeyPicker.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Pick SSH Key")
                    }
                    Button(
                        onClick = {
                            scope.launch {
                                adminVm.prefs.saveSshAuth(sshHost, sshPort.toIntOrNull() ?: 22, sshUser, null)
                                adminVm.emitSnackbar("Saved SSH host settings")
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Save SSH") }
                }
                Text(sshKeyLoadedLabel, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary, modifier = Modifier.padding(top = 4.dp))

                Spacer(Modifier.height(10.dp))
                val readyBins = toolkitState.toolchain.count { it.isReady }
                StatusPill("Local 8-Binary ARM64 Toolchain: $readyBins / 8 Ready", if (readyBins == 8) colors.emerald else colors.amber)
            }
        }

        // UI & Developer (Accent Color Picker, Anim Scale, Simulation Mode Toggle)
        item(key = "settings_ui_dev", contentType = "card") {
            AdminCard(borderColor = if (settings.simulationMode) colors.amber.copy(alpha = 0.65f) else colors.cardBorder) {
                SectionHeader("UI & Developer Controls")
                Spacer(Modifier.height(6.dp))
                Text("Accent Color", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccentPreset.entries.forEach { preset ->
                        val selected = settings.accentPresetKey == preset.key
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(preset.color)
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) Color.White else colors.cardBorder,
                                    shape = CircleShape
                                )
                                .clickable { scope.launch { adminVm.prefs.setAccentPreset(preset.key) } }
                                .testTag("accent_picker_${preset.key}")
                        )
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Animation Speed Scale", style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
                    Text("${"%.2f".format(settings.animSpeedScale)}x", style = MonoCodeStyle, color = colors.accent)
                }
                Slider(
                    value = settings.animSpeedScale,
                    onValueChange = { v -> scope.launch { adminVm.prefs.setAnimSpeedScale(v) } },
                    valueRange = 0.25f..2.0f,
                    colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent)
                )

                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Simulation Mode (Single Source of Truth)", style = MaterialTheme.typography.titleSmall, color = colors.amber)
                        Text("OFF by default on physical devices. Turning OFF hides SIM badge and immediately probes su -c id.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                    Switch(
                        checked = settings.simulationMode,
                        onCheckedChange = { adminVm.setSimulationMode(it) },
                        colors = SwitchDefaults.colors(checkedThumbColor = colors.amber, checkedTrackColor = colors.amber.copy(alpha = 0.28f)),
                        modifier = Modifier.testTag("settings_simulation_mode_switch")
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAdminColors.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
