package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdateAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.installer.BootImagePatcher
import com.example.core.installer.PatchConfig
import com.example.core.installer.PatchEngineMode
import com.example.core.shell.RootAccessState
import com.example.core.storage.ExportManager
import com.example.ui.components.AdminCard
import com.example.ui.components.AnimatedTelemetryRing
import com.example.ui.components.MonospaceScriptEditorSheet
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalAdminColors
import com.example.ui.theme.MonoCodeStyle
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.CrossTabEvent
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(
    adminVm: AdminViewModel
) {
    val colors = LocalAdminColors.current
    val context = LocalContext.current
    val settings by adminVm.settings.collectAsStateWithLifecycle()
    val rootState by adminVm.rootState.collectAsStateWithLifecycle()
    val telemetry by adminVm.telemetry.collectAsStateWithLifecycle()
    val hasAllFiles by adminVm.hasAllFilesAccess.collectAsStateWithLifecycle()
    val guardScript by adminVm.guardScript.collectAsStateWithLifecycle()

    var showPatcherSheet by remember { mutableStateOf(false) }
    var showGuardScriptSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val rootGranted = rootState is RootAccessState.Granted || rootState is RootAccessState.Simulated
    val rootRingProgress = when (rootState) {
        is RootAccessState.Granted -> 0.96f
        is RootAccessState.Simulated -> 0.88f
        is RootAccessState.Checking -> 0.50f
        else -> 0.18f
    }
    val rootRingTitle = when (rootState) {
        is RootAccessState.Granted -> "UID 0"
        is RootAccessState.Simulated -> "SIM"
        is RootAccessState.Checking -> "PROBE"
        is RootAccessState.Denied -> "DENIED"
        RootAccessState.Unknown -> "CHECK"
    }
    val rootRingColor = when (rootState) {
        is RootAccessState.Granted -> colors.emerald
        is RootAccessState.Simulated -> colors.amber
        is RootAccessState.Denied -> colors.crimson
        else -> colors.accent
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.oledBackground),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 110.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (!hasAllFiles && !settings.simulationMode) {
            item(key = "storage_banner", contentType = "banner") {
                AdminCard(borderColor = colors.amber.copy(alpha = 0.6f), contentPadding = PaddingValues(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Public Exports Need All Files Access", style = MaterialTheme.typography.titleSmall, color = colors.amber)
                            Text("Enable MANAGE_EXTERNAL_STORAGE so Samsung My Files shows Download/Administrator/ instantly.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { context.startActivity(ExportManager.createAllFilesAccessIntent(context)) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.amber, contentColor = Color.Black),
                            modifier = Modifier.testTag("dashboard_grant_storage_btn")
                        ) {
                            Text("Grant")
                        }
                    }
                }
            }
        }

        // Hero Telemetry Card
        item(key = "hero_telemetry", contentType = "hero") {
            AdminCard(borderColor = colors.accent.copy(alpha = 0.4f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("ROOT & VECTOR TELEMETRY", style = MaterialTheme.typography.labelMedium, color = colors.accent)
                        Text(telemetry.rootCoreLabel, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    }
                    StatusPill(text = "A-only", color = colors.violet)
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedTelemetryRing(
                            progress = rootRingProgress,
                            primaryColor = rootRingColor,
                            secondaryColor = colors.accent,
                            centerTitle = rootRingTitle,
                            centerSubtitle = "ROOT CORE",
                            animSpeedScale = settings.animSpeedScale
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = when (val st = rootState) {
                                is RootAccessState.Granted -> st.suManagerHint
                                is RootAccessState.Denied -> "su exit=${st.exitCode}"
                                is RootAccessState.Simulated -> "SIM Mode Active"
                                else -> "Probing su -c id..."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedTelemetryRing(
                            progress = if (settings.vectorFrameworkEnabled) 0.92f else 0.14f,
                            primaryColor = if (settings.vectorFrameworkEnabled) colors.violet else colors.textMuted,
                            secondaryColor = colors.accent,
                            centerTitle = if (settings.vectorFrameworkEnabled) "API 102" else "OFF",
                            centerSubtitle = "VECTOR",
                            animSpeedScale = settings.animSpeedScale
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "libxposed 101–102",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary
                        )
                    }
                }

                if (rootState is RootAccessState.Denied) {
                    val denied = rootState as RootAccessState.Denied
                    Spacer(Modifier.height(10.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.crimson.copy(alpha = 0.12f))
                            .border(1.dp, colors.crimson.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                            .padding(10.dp)
                    ) {
                        Text(
                            text = denied.reason,
                            style = MonoCodeStyle,
                            color = colors.crimson
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // Kernel / SELinux / ABI Specs Grid
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.oledBackground)
                        .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SpecRow("Kernel", telemetry.kernelVersion, colors.textPrimary)
                    SpecRow(
                        "SELinux",
                        telemetry.selinuxMode,
                        if (telemetry.selinuxMode.equals("Enforcing", true)) colors.emerald else colors.amber
                    )
                    SpecRow("ABI · Layout", "${telemetry.abi} · ${telemetry.partitionScheme}", colors.accent)
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (!rootGranted) {
                        OutlinedButton(
                            onClick = { adminVm.requestRootPermission() },
                            border = BorderStroke(1.dp, colors.amber),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("dashboard_request_root_btn")
                        ) {
                            Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = colors.amber, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Request Root", color = colors.amber)
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1.3f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.horizontalGradient(listOf(colors.accent, colors.violet))
                            )
                            .clickable { showPatcherSheet = true }
                            .padding(vertical = 12.dp, horizontal = 14.dp)
                            .testTag("dashboard_install_patch_btn"),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.SystemUpdateAlt, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "Install / Patch Root",
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.Black,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Core Services Quick Matrix
        item(key = "core_services_header", contentType = "header") {
            SectionHeader("Core Services Quick Matrix", "Live toggles synced to /data/adb")
        }

        item(key = "core_services_matrix", contentType = "matrix") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ServiceMatrixTile(
                    title = "ReZygisk",
                    subtitle = "Zygote Inject",
                    icon = Icons.Default.Memory,
                    enabled = settings.reZygiskEnabled,
                    accent = colors.accent,
                    onToggle = { adminVm.toggleCoreService("rezygisk", it) },
                    modifier = Modifier.weight(1f).testTag("service_toggle_rezygisk")
                )
                ServiceMatrixTile(
                    title = "Hosts",
                    subtitle = "Systemless",
                    icon = Icons.Default.Dns,
                    enabled = settings.systemlessHostsEnabled,
                    accent = colors.emerald,
                    onToggle = { adminVm.toggleCoreService("hosts", it) },
                    modifier = Modifier.weight(1f).testTag("service_toggle_hosts")
                )
                ServiceMatrixTile(
                    title = "Vector",
                    subtitle = "API 102",
                    icon = Icons.Default.Extension,
                    enabled = settings.vectorFrameworkEnabled,
                    accent = colors.violet,
                    onToggle = { adminVm.toggleCoreService("vector", it) },
                    modifier = Modifier.weight(1f).testTag("service_toggle_vector")
                )
            }
        }

        // Anti-Bootloop Guardian Card
        item(key = "antibootloop_card", contentType = "guardian") {
            val isArmed = settings.antiBootloopWatchdog || settings.powerKeyPanicTrigger
            val badgeText = when {
                telemetry.antiBootloopTriggered -> "TRIGGERED"
                isArmed -> "ARMED"
                else -> "DISARMED"
            }
            val badgeColor = when {
                telemetry.antiBootloopTriggered -> colors.crimson
                isArmed -> colors.emerald
                else -> colors.textMuted
            }

            AdminCard(borderColor = badgeColor.copy(alpha = 0.45f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = badgeColor)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("Anti-Bootloop Guardian", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                            Text("/data/adb/post-fs-data.d/admin_antibootloop.sh", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                        }
                    }
                    StatusPill(text = badgeText, color = badgeColor)
                }

                Spacer(Modifier.height(10.dp))

                ToggleLineRow(
                    title = "Auto-Bootloop Watchdog",
                    subtitle = "Disables modules if sys.boot_completed exceeds ${settings.bootloopTimeoutSec}s",
                    checked = settings.antiBootloopWatchdog,
                    onCheckedChange = { enabled ->
                        scope.launch { adminVm.prefs.setAntiBootloopConfig(watchdog = enabled) }
                    }
                )
                ToggleLineRow(
                    title = "Hardware Power-Key Panic Trigger",
                    subtitle = "Hold Power 3s during Samsung boot logo to safe-mode modules",
                    checked = settings.powerKeyPanicTrigger,
                    onCheckedChange = { enabled ->
                        scope.launch { adminVm.prefs.setAntiBootloopConfig(powerPanic = enabled) }
                    }
                )

                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { showGuardScriptSheet = true },
                        modifier = Modifier.weight(1f).testTag("edit_guard_script_btn")
                    ) {
                        Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Edit Guard Script", maxLines = 1)
                    }
                    Button(
                        onClick = { adminVm.emitCrossTab(CrossTabEvent.OpenCrashAnalyzer) },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.violet, contentColor = Color.White),
                        modifier = Modifier.weight(1f).testTag("analyze_last_crash_btn")
                    ) {
                        Icon(Icons.Default.BugReport, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Analyze Last Crash", maxLines = 1)
                    }
                }
            }
        }
    }

    if (showGuardScriptSheet) {
        MonospaceScriptEditorSheet(
            title = "Anti-Bootloop Guard Script",
            subtitle = "/data/adb/post-fs-data.d/admin_antibootloop.sh",
            initialText = guardScript,
            onDismiss = { showGuardScriptSheet = false },
            onSave = { adminVm.saveAntiBootloopScript(it) }
        )
    }

    if (showPatcherSheet) {
        BootImagePatcherModalSheet(
            simulationMode = settings.simulationMode,
            onDismiss = { showPatcherSheet = false },
            onSelectAndPatch = { uri, name, cfg ->
                showPatcherSheet = false
                adminVm.patchSelectedBootImage(uri, name, cfg)
            },
            onDirectInstall = { cfg ->
                showPatcherSheet = false
                adminVm.directInstallBootImage(cfg)
            }
        )
    }
}

@Composable
private fun SpecRow(label: String, value: String, valueColor: Color) {
    val colors = LocalAdminColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
        Spacer(Modifier.width(8.dp))
        Text(value, style = MonoCodeStyle, color = valueColor, maxLines = 1)
    }
}

@Composable
private fun ServiceMatrixTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    enabled: Boolean,
    accent: Color,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAdminColors.current
    AdminCard(
        modifier = modifier.clickable { onToggle(!enabled) },
        borderColor = if (enabled) accent.copy(alpha = 0.55f) else colors.cardBorder,
        contentPadding = PaddingValues(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = title, tint = if (enabled) accent else colors.textMuted, modifier = Modifier.size(20.dp))
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (enabled) accent else colors.textMuted)
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1)
        Text(subtitle, style = MaterialTheme.typography.labelSmall, color = if (enabled) accent else colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun ToggleLineRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = LocalAdminColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.accent,
                checkedTrackColor = colors.accent.copy(alpha = 0.25f)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BootImagePatcherModalSheet(
    simulationMode: Boolean,
    onDismiss: () -> Unit,
    onSelectAndPatch: (Uri, String, PatchConfig) -> Unit,
    onDirectInstall: (PatchConfig) -> Unit
) {
    val colors = LocalAdminColors.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var engine by remember { mutableStateOf(PatchEngineMode.MAGISK_KITSUNE) }
    var targetPart by remember { mutableStateOf("init_boot") }
    var preserveAvb by remember { mutableStateOf(true) }
    var preserveForceEncrypt by remember { mutableStateOf(true) }
    var autoOdinTar by remember { mutableStateOf(true) }
    var superKey by remember { mutableStateOf("") }
    var ksuLkm by remember { mutableStateOf(true) }
    var kallsymsInfo by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: "$targetPart.img"
            onSelectAndPatch(
                uri,
                name,
                PatchConfig(engine, targetPart, preserveAvb, preserveForceEncrypt, autoOdinTar, superKey, ksuLkm)
            )
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Boot Image Patcher · SM-E156B", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
            Text("Supports boot.img, init_boot.img, vendor_boot.img & Samsung AP_*.tar.md5", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                PatchEngineMode.entries.forEach { mode ->
                    FilterChip(
                        selected = engine == mode,
                        onClick = { engine = mode },
                        label = { Text(mode.label.substringBefore(" "), maxLines = 1) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Text(engine.subtitle, style = MaterialTheme.typography.labelSmall, color = colors.accent)

            Text("Target Partition (A-only SM-E156B)", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("init_boot", "boot", "vendor_boot").forEach { p ->
                    FilterChip(selected = targetPart == p, onClick = { targetPart = p }, label = { Text(p) })
                }
            }

            when (engine) {
                PatchEngineMode.MAGISK_KITSUNE -> {
                    CheckOptionRow("Preserve AVB 2.0 / dm-verity", preserveAvb) { preserveAvb = it }
                    CheckOptionRow("Preserve Force-Encrypt", preserveForceEncrypt) { preserveForceEncrypt = it }
                    CheckOptionRow("Auto-Package Samsung Odin .tar", autoOdinTar) { autoOdinTar = it }
                }
                PatchEngineMode.APATCH -> {
                    OutlinedTextField(
                        value = superKey,
                        onValueChange = { superKey = it },
                        label = { Text("Mandatory APatch SuperKey (>= 8 chars)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val (_, msg) = BootImagePatcher.checkKallsymsSupport(simulationMode)
                                kallsymsInfo = msg
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Verify CONFIG_KALLSYMS on mt6835 Kernel")
                    }
                    kallsymsInfo?.let {
                        Text(it, style = MonoCodeStyle, color = colors.emerald)
                    }
                    CheckOptionRow("Auto-Package Samsung Odin .tar", autoOdinTar) { autoOdinTar = it }
                }
                PatchEngineMode.KERNELSU -> {
                    CheckOptionRow("Use LKM (.ko) Mode for mt6835 GKI Kernel", ksuLkm) { ksuLkm = it }
                    CheckOptionRow("Auto-Package Samsung Odin .tar", autoOdinTar) { autoOdinTar = it }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f).testTag("patcher_select_file_btn")
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Select & Patch")
                }
                Button(
                    onClick = {
                        onDirectInstall(
                            PatchConfig(engine, targetPart, preserveAvb, preserveForceEncrypt, autoOdinTar, superKey, ksuLkm)
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                    modifier = Modifier.weight(1f).testTag("patcher_direct_install_btn")
                ) {
                    Text("Direct Install")
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CheckOptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalAdminColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
    }
}
