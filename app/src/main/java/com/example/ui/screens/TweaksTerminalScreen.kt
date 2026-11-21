package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.tweaks.HardwareTweaksEngine
import com.example.ui.components.AdminCard
import com.example.ui.components.AnimatedTelemetryRing
import com.example.ui.components.SectionHeader
import com.example.ui.components.SegmentedControlBar
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalAdminColors
import com.example.ui.theme.MonoCodeStyle
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.TerminalLineTone
import com.example.viewmodel.TweaksTerminalViewModel

@Composable
fun TweaksTerminalScreen(
    adminVm: AdminViewModel,
    tweaksVm: TweaksTerminalViewModel
) {
    val colors = LocalAdminColors.current
    val settings by adminVm.settings.collectAsStateWithLifecycle()
    val hw by tweaksVm.telemetry.collectAsStateWithLifecycle()
    val cfg by tweaksVm.tweaksConfig.collectAsStateWithLifecycle()
    val sessions by tweaksVm.sessions.collectAsStateWithLifecycle()
    val activeSessionId by tweaksVm.activeSessionId.collectAsStateWithLifecycle()
    val ctrlLatched by tweaksVm.ctrlLatched.collectAsStateWithLifecycle()
    val altLatched by tweaksVm.altLatched.collectAsStateWithLifecycle()

    var topSubTab by remember { mutableIntStateOf(0) }
    var expandBootScript by remember { mutableStateOf(false) }

    LaunchedEffect(
        settings.tweaksMasterEnabled, settings.perfProfile, settings.zramMasterEnabled,
        settings.zramSizePreset, settings.zramAlgorithm, settings.swappiness,
        settings.disableSamsungRamPlus, settings.applyTweaksOnBoot
    ) {
        tweaksVm.syncFromSettings(
            settings.tweaksMasterEnabled, settings.perfProfile, settings.zramMasterEnabled,
            settings.zramSizePreset, settings.zramAlgorithm, settings.swappiness,
            settings.disableSamsungRamPlus, settings.applyTweaksOnBoot
        )
    }

    LaunchedEffect(Unit) {
        tweaksVm.startTelemetryLoop { adminVm.settings.value.simulationMode }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.oledBackground)
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            SegmentedControlBar(
                options = listOf("Device Tweaks (mt6835)", "Terminal"),
                selectedIndex = topSubTab,
                onSelect = { topSubTab = it }
            )
        }

        if (topSubTab == 0) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item(key = "tweaks_master", contentType = "card") {
                    AdminCard(borderColor = if (cfg.masterEnabled) colors.accent.copy(alpha = 0.55f) else colors.cardBorder) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Dimensity 6100+ Hardware Engine", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                Text("SM-E156B · 4 GB LPDDR4X · ${hw.thermalZoneTempC}°C · ${hw.ioScheduler}", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                            }
                            Switch(
                                checked = cfg.masterEnabled,
                                onCheckedChange = { on -> tweaksVm.updateTweaksConfig(adminVm) { it.copy(masterEnabled = on) } },
                                colors = SwitchDefaults.colors(checkedThumbColor = colors.accent, checkedTrackColor = colors.accent.copy(alpha = 0.25f)),
                                modifier = Modifier.testTag("tweaks_master_switch")
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Text("Performance Profile", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf("High", "Medium", "Battery", "Auto").forEach { prof ->
                                FilterChip(
                                    selected = cfg.perfProfile == prof,
                                    onClick = { tweaksVm.updateTweaksConfig(adminVm) { it.copy(perfProfile = prof) } },
                                    label = { Text(prof, maxLines = 1) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        FreqMeterBar(
                            label = "Big Cluster (policy6 · 2x Cortex-A76)",
                            currentMhz = hw.bigCoreFreqMhz,
                            maxMhz = hw.bigCoreMaxMhz,
                            color = colors.accent
                        )
                        Spacer(Modifier.height(8.dp))
                        FreqMeterBar(
                            label = "LITTLE Cluster (policy0 · 6x Cortex-A55)",
                            currentMhz = hw.littleCoreFreqMhz,
                            maxMhz = hw.littleCoreMaxMhz,
                            color = colors.violet
                        )
                        Spacer(Modifier.height(8.dp))
                        FreqMeterBar(
                            label = "Mali-G57 MC2 GPU",
                            currentMhz = hw.gpuFreqMhz,
                            maxMhz = 1000,
                            color = colors.emerald
                        )

                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Mali-G57 MC2 GPU Boost", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                            Switch(
                                checked = cfg.gpuBoost,
                                onCheckedChange = { b -> tweaksVm.updateTweaksConfig(adminVm) { it.copy(gpuBoost = b) } }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("mq-deadline", "kyber", "bfq", "none").forEach { sched ->
                                FilterChip(
                                    selected = cfg.ioScheduler == sched,
                                    onClick = { tweaksVm.updateTweaksConfig(adminVm) { it.copy(ioScheduler = sched) } },
                                    label = { Text("I/O: $sched") }
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("Cool", "Balanced", "Performance").forEach { th ->
                                FilterChip(
                                    selected = cfg.thermalPolicy == th,
                                    onClick = { tweaksVm.updateTweaksConfig(adminVm) { it.copy(thermalPolicy = th) } },
                                    label = { Text("Thermal: $th") }
                                )
                            }
                        }
                    }
                }

                // Compressed ZRAM Manager
                item(key = "zram_manager", contentType = "card") {
                    val ramRatio = (hw.usedRamMb.toFloat() / hw.totalRamMb.coerceAtLeast(1)).coerceIn(0.05f, 1f)
                    val zramRatio = (hw.zramUsedMb.toFloat() / hw.zramTotalMb.coerceAtLeast(1)).coerceIn(0.05f, 1f)

                    AdminCard(borderColor = colors.violet.copy(alpha = 0.5f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Compressed ZRAM Manager", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                                Text("4 GB SM-E156B Pipeline: swapoff -> reset -> algo -> disksize -> swapon", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
                            }
                            Switch(
                                checked = cfg.zramMaster,
                                onCheckedChange = { z -> tweaksVm.updateTweaksConfig(adminVm) { it.copy(zramMaster = z) } }
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AnimatedTelemetryRing(
                                progress = ramRatio,
                                primaryColor = colors.accent,
                                secondaryColor = colors.emerald,
                                centerTitle = "${hw.usedRamMb}M",
                                centerSubtitle = "/ ${hw.totalRamMb}M RAM",
                                ringSize = 96.dp,
                                animSpeedScale = settings.animSpeedScale
                            )
                            AnimatedTelemetryRing(
                                progress = if (cfg.zramMaster) zramRatio else 0.05f,
                                primaryColor = colors.violet,
                                secondaryColor = colors.amber,
                                centerTitle = "${hw.zramUsedMb}M",
                                centerSubtitle = "ZRAM (${hw.zramCompAlgo})",
                                ringSize = 96.dp,
                                animSpeedScale = settings.animSpeedScale
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Text("ZRAM Disksize Preset", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("Off", "Auto", "As Per Device", "4 GB", "8 GB", "16 GB").forEach { sz ->
                                FilterChip(
                                    selected = cfg.zramSizePreset == sz,
                                    onClick = { tweaksVm.updateTweaksConfig(adminVm) { it.copy(zramSizePreset = sz) } },
                                    label = { Text(sz) }
                                )
                            }
                        }

                        Text("Compression Algorithm", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("zstd", "lz4", "lzo-rle").forEach { algo ->
                                FilterChip(
                                    selected = cfg.zramAlgo == algo,
                                    onClick = { tweaksVm.updateTweaksConfig(adminVm) { it.copy(zramAlgo = algo) } },
                                    label = { Text(algo, style = MonoCodeStyle) }
                                )
                            }
                        }

                        Spacer(Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("vm.swappiness", style = MonoCodeStyle, color = colors.textPrimary)
                            Text("${cfg.swappiness}", style = MonoCodeStyle, color = colors.accent)
                        }
                        Slider(
                            value = cfg.swappiness.toFloat(),
                            onValueChange = { v -> tweaksVm.updateTweaksConfig(adminVm) { it.copy(swappiness = v.toInt()) } },
                            valueRange = 10f..100f,
                            colors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Disable Samsung RAM Plus", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                                Text("Prevents NAND swap wear on eMMC/UFS storage", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                            }
                            Switch(
                                checked = cfg.disableSamsungRamPlus,
                                onCheckedChange = { dis -> tweaksVm.updateTweaksConfig(adminVm) { it.copy(disableSamsungRamPlus = dis) } }
                            )
                        }

                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { tweaksVm.applyTweaksNow(settings.simulationMode, adminVm) },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                            modifier = Modifier.fillMaxWidth().testTag("apply_mt6835_tweaks_btn")
                        ) {
                            Text("Apply mt6835 & ZRAM Pipeline Now")
                        }
                    }
                }

                // Apply-on-Boot Script Card
                item(key = "apply_on_boot_card", contentType = "card") {
                    AdminCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Apply-on-Boot Service Script", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                                Text("${HardwareTweaksEngine.BOOT_SCRIPT_PATH} (Unix LF · 0755)", style = MonoCodeStyle, color = colors.accent)
                            }
                            Switch(
                                checked = cfg.applyOnBoot,
                                onCheckedChange = { tweaksVm.toggleApplyOnBoot(it, settings.simulationMode, adminVm) }
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { expandBootScript = !expandBootScript },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Preview Generated Boot Script", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                            Icon(if (expandBootScript) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = "Toggle script preview", tint = colors.accent)
                        }
                        if (expandBootScript) {
                            Spacer(Modifier.height(6.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF04060A))
                                    .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Text(HardwareTweaksEngine.generateBootScript(cfg), style = MonoCodeStyle, color = colors.emerald)
                            }
                        }
                    }
                }
            }
        } else {
            // TERMUX-STYLE MULTI-SESSION TERMINAL
            TermuxTerminalPane(
                sessions = sessions,
                activeSessionId = activeSessionId,
                ctrlLatched = ctrlLatched,
                altLatched = altLatched,
                simulationMode = settings.simulationMode,
                tweaksVm = tweaksVm,
                adminVm = adminVm
            )
        }
    }
}

@Composable
private fun FreqMeterBar(label: String, currentMhz: Int, maxMhz: Int, color: Color) {
    val colors = LocalAdminColors.current
    val ratio = (currentMhz.toFloat() / maxMhz.coerceAtLeast(1)).coerceIn(0.04f, 1f)
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            Text("$currentMhz / $maxMhz MHz", style = MonoCodeStyle, color = color)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { ratio },
            color = color,
            trackColor = colors.cardBorder,
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
        )
    }
}

@Composable
private fun TermuxTerminalPane(
    sessions: List<com.example.viewmodel.TerminalSession>,
    activeSessionId: Int,
    ctrlLatched: Boolean,
    altLatched: Boolean,
    simulationMode: Boolean,
    tweaksVm: TweaksTerminalViewModel,
    adminVm: AdminViewModel
) {
    val colors = LocalAdminColors.current
    val clipboard = LocalClipboardManager.current
    val activeSession = sessions.firstOrNull { it.id == activeSessionId } ?: sessions.first()
    var commandInput by remember(activeSessionId) { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(activeSession.lines.size) {
        if (activeSession.lines.isNotEmpty()) {
            listState.animateScrollToItem(activeSession.lines.lastIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 12.dp, end = 12.dp, bottom = 92.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Multi-session tab bar + Privilege pill + Copy/Clear
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                sessions.forEach { sess ->
                    val active = sess.id == activeSession.id
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (active) colors.accent.copy(alpha = 0.16f) else colors.cardSurface)
                            .border(1.dp, if (active) colors.accent else colors.cardBorder, RoundedCornerShape(10.dp))
                            .clickable { tweaksVm.selectSession(sess.id) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(sess.title, style = MaterialTheme.typography.labelMedium, color = if (active) colors.accent else colors.textSecondary)
                        if (sessions.size > 1) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close ${sess.title}",
                                tint = colors.textMuted,
                                modifier = Modifier.size(14.dp).clickable { tweaksVm.closeSession(sess.id) }
                            )
                        }
                    }
                }
                IconButton(onClick = { tweaksVm.addSession() }, modifier = Modifier.size(32.dp).testTag("term_add_session_btn")) {
                    Icon(Icons.Default.Add, contentDescription = "New terminal session", tint = colors.accent)
                }
            }

            // # ROOT vs $ USER privilege pill
            StatusPill(
                text = if (activeSession.isRoot) "# ROOT" else "$ USER",
                color = if (activeSession.isRoot) colors.crimson else colors.emerald,
                modifier = Modifier
                    .clickable { tweaksVm.toggleSessionPrivilege(activeSession.id) }
                    .testTag("term_privilege_pill")
            )
            IconButton(
                onClick = {
                    val dump = activeSession.lines.joinToString("\n") { it.text }
                    clipboard.setText(AnnotatedString(dump))
                    adminVm.emitSnackbar("Copied terminal buffer")
                },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = "Copy terminal output", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = { tweaksVm.clearActiveSession() }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteSweep, contentDescription = "Clear terminal", tint = colors.textSecondary, modifier = Modifier.size(16.dp))
            }
        }

        // ANSI-colored scrollable output console
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xFF04060A))
                .border(1.dp, colors.cardBorder, RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            items(activeSession.lines, key = { it.id }, contentType = { "term_line" }) { line ->
                val tint = when (line.tone) {
                    TerminalLineTone.PROMPT -> colors.accent
                    TerminalLineTone.SUCCESS -> colors.emerald
                    TerminalLineTone.WARNING -> colors.amber
                    TerminalLineTone.ERROR -> colors.crimson
                    TerminalLineTone.CYAN -> colors.accent
                    TerminalLineTone.VIOLET -> colors.violet
                    TerminalLineTone.NORMAL -> colors.textPrimary
                }
                Text(text = line.text, style = MonoCodeStyle, color = tint)
            }
        }

        // Command input bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = commandInput,
                onValueChange = { commandInput = it },
                placeholder = { Text(if (activeSession.isRoot) "root@e15x:# command..." else "u0@e15x:$ command...", style = MonoCodeStyle) },
                textStyle = MonoCodeStyle.copy(color = colors.textPrimary),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    tweaksVm.runTerminalCommand(commandInput, simulationMode)
                    commandInput = ""
                }),
                modifier = Modifier.weight(1f).testTag("term_command_input")
            )
            Button(
                onClick = {
                    tweaksVm.runTerminalCommand(commandInput, simulationMode)
                    commandInput = ""
                },
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                modifier = Modifier.testTag("term_send_btn")
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Execute command", modifier = Modifier.size(18.dp))
            }
        }

        // Pinned Termux Extra Keys Bar
        val extraKeys = listOf("ESC", "TAB", "CTRL", "ALT", "|", "/", "-", "~", "←", "↓", "↑", "→")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(10.dp))
                .background(colors.cardSurface)
                .border(1.dp, colors.cardBorder, RoundedCornerShape(10.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            extraKeys.forEach { key ->
                val active = (key == "CTRL" && ctrlLatched) || (key == "ALT" && altLatched)
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (active) colors.accent.copy(alpha = 0.25f) else colors.oledBackground)
                        .border(1.dp, if (active) colors.accent else colors.cardBorder, RoundedCornerShape(6.dp))
                        .clickable {
                            when (key) {
                                "ESC" -> commandInput = ""
                                "TAB" -> commandInput += "  "
                                "CTRL" -> tweaksVm.toggleCtrlLatch()
                                "ALT" -> tweaksVm.toggleAltLatch()
                                "↑" -> tweaksVm.recallHistory(older = true)?.let { commandInput = it }
                                "↓" -> tweaksVm.recallHistory(older = false)?.let { commandInput = it }
                                "←", "→" -> Unit
                                else -> commandInput += key
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = key,
                        style = MonoCodeStyle.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                        color = if (active) colors.accent else colors.textPrimary
                    )
                }
            }
        }
    }
}
