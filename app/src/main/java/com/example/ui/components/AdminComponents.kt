package com.example.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LocalAdminColors
import com.example.ui.theme.MonoCodeStyle
import com.example.viewmodel.ConsoleSheetState
import com.example.viewmodel.DestructiveFlashRequest
import kotlin.math.roundToInt

/**
 * Flat translucent OLED card (#E6111622) with crisp 1.dp #1E293B hairline border.
 * Zero runtime blur or expensive shadow layers for locked 60/120fps scrolling on 4 GB RAM.
 */
@Composable
fun AdminCard(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    backgroundColor: Color? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = LocalAdminColors.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = backgroundColor ?: colors.cardSurface,
        border = BorderStroke(1.dp, borderColor ?: colors.cardBorder),
        shadowElevation = 0.dp,
        tonalElevation = 0.dp
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
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
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * Isolated animated dual telemetry ring for Dashboard Hero Card & ZRAM Memory Gauge.
 * Runs its own rememberInfiniteTransition + graphicsLayer so sibling items never recompose.
 */
@Composable
fun AnimatedTelemetryRing(
    progress: Float,
    primaryColor: Color,
    secondaryColor: Color,
    centerTitle: String,
    centerSubtitle: String,
    ringSize: Dp = 104.dp,
    animSpeedScale: Float = 1.0f
) {
    val colors = LocalAdminColors.current
    val durationMs = (3600 / animSpeedScale.coerceIn(0.25f, 2.0f)).toInt()
    val infinite = rememberInfiniteTransition(label = "telemetry_ring")
    val orbitAngle by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit"
    )

    Box(
        modifier = Modifier.size(ringSize),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = orbitAngle }
        ) {
            val strokePx = 6.dp.toPx()
            val inset = strokePx / 2f
            val arcSize = Size(size.width - strokePx, size.height - strokePx)
            drawArc(
                color = colors.cardBorder,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
            drawArc(
                brush = Brush.sweepGradient(listOf(primaryColor, secondaryColor, primaryColor)),
                startAngle = -90f,
                sweepAngle = (progress.coerceIn(0.05f, 1f) * 320f),
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = centerTitle,
                style = MaterialTheme.typography.titleMedium,
                color = colors.textPrimary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = centerSubtitle,
                style = MaterialTheme.typography.labelSmall,
                color = primaryColor
            )
        }
    }
}

@Composable
fun SegmentedControlBar(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAdminColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.oledBackground)
            .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        options.forEachIndexed { idx, label ->
            val selected = idx == selectedIndex
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) colors.accent.copy(alpha = 0.18f) else Color.Transparent)
                    .border(
                        width = if (selected) 1.dp else 0.dp,
                        color = if (selected) colors.accent.copy(alpha = 0.55f) else Color.Transparent,
                        shape = RoundedCornerShape(9.dp)
                    )
                    .clickable { onSelect(idx) }
                    .testTag("segment_${label.lowercase().replace(" ", "_")}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 11.5.sp),
                    color = if (selected) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
        }
    }
}

@Composable
fun AdminTopBar(
    deviceBadge: String,
    simulationMode: Boolean,
    antiBootloopArmed: Boolean,
    antiBootloopTriggered: Boolean,
    onRebootClick: () -> Unit,
    onShieldClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val colors = LocalAdminColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.oledBackground)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.accent.copy(alpha = 0.14f))
                    .border(1.dp, colors.accent.copy(alpha = 0.45f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "A",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.accent,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.textPrimary,
                        maxLines = 1
                    )
                    // Critical Rule #1 Single Source of Truth: ONLY shown when simulationMode == true
                    if (simulationMode) {
                        Spacer(Modifier.width(6.dp))
                        StatusPill(
                            text = stringResource(R.string.sim_badge_label),
                            color = colors.amber,
                            modifier = Modifier.testTag("top_bar_sim_badge")
                        )
                    }
                }
                Text(
                    text = deviceBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onShieldClick,
                modifier = Modifier.testTag("top_bar_shield_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = stringResource(R.string.cd_antibootloop_shield),
                    tint = when {
                        antiBootloopTriggered -> colors.crimson
                        antiBootloopArmed -> colors.emerald
                        else -> colors.textMuted
                    }
                )
            }
            IconButton(
                onClick = onRebootClick,
                modifier = Modifier.testTag("top_bar_reboot_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PowerSettingsNew,
                    contentDescription = stringResource(R.string.cd_reboot_menu),
                    tint = colors.amber
                )
            }
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.testTag("top_bar_settings_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = stringResource(R.string.cd_settings),
                    tint = colors.textPrimary
                )
            }
        }
    }
}

enum class AdminNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD("dashboard", "Dashboard", Icons.Default.Dashboard),
    SUPERUSER("superuser", "Superuser", Icons.Default.Security),
    MODULES("modules", "Modules", Icons.Default.Extension),
    TOOLKIT("toolkit", "Toolkit", Icons.Default.Build),
    TWEAKS("tweaks", "Tweaks", Icons.Default.Tune)
}

@Composable
fun FloatingGlassBottomBar(
    currentRoute: String,
    onSelect: (AdminNavTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalAdminColors.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = colors.glassBar,
            border = BorderStroke(1.dp, colors.cardBorder),
            shadowElevation = 0.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                AdminNavTab.entries.forEach { tab ->
                    val selected = currentRoute == tab.route
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 52.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (selected) colors.accent.copy(alpha = 0.14f) else Color.Transparent)
                            .clickable { onSelect(tab) }
                            .padding(vertical = 6.dp)
                            .testTag("nav_tab_${tab.route}"),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = tab.icon,
                            contentDescription = tab.label,
                            tint = if (selected) colors.accent else colors.textSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = tab.label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 9.5.sp
                            ),
                            color = if (selected) colors.accent else colors.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RebootActionsModalSheet(
    onDismiss: () -> Unit,
    onSelectReboot: (String) -> Unit
) {
    val colors = LocalAdminColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Reboot Actions (SM-E156B)", style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
            Text("Dispatches privileged reboot targets directly via su.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
            val targets = listOf(
                Triple("System", "Normal One UI reboot", colors.accent),
                Triple("Recovery", "Boot Stock / TWRP / OFOX Recovery", colors.violet),
                Triple("Download Mode", "Samsung Odin Mode (AP/BL/CP/CSC)", colors.amber),
                Triple("FastbootD", "Userspace FastbootD (Dynamic Super)", colors.emerald)
            )
            targets.forEach { (title, desc, tint) ->
                AdminCard(
                    borderColor = tint.copy(alpha = 0.45f),
                    contentPadding = PaddingValues(14.dp),
                    modifier = Modifier
                        .clickable {
                            onDismiss()
                            onSelectReboot(title)
                        }
                        .testTag("reboot_option_${title.lowercase().replace(" ", "_")}")
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.RestartAlt, contentDescription = title, tint = tint)
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * Confirmed Phase 1 Safety Gate:
 * Slide-to-Confirm with integrated Auto-Backup Before Flash option.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SlideToConfirmFlashSheet(
    request: DestructiveFlashRequest,
    onDismiss: () -> Unit
) {
    val colors = LocalAdminColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var autoBackup by remember(request) { mutableStateOf(true) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = "Destructive write warning", tint = colors.crimson)
                Spacer(Modifier.width(10.dp))
                Text(request.title, style = MaterialTheme.typography.titleLarge, color = colors.crimson)
            }

            AdminCard(borderColor = colors.crimson.copy(alpha = 0.45f), contentPadding = PaddingValues(12.dp)) {
                Text("TARGET BLOCK DEVICE", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                Text(request.targetBlockPath, style = MonoCodeStyle, color = colors.amber)
                Spacer(Modifier.height(6.dp))
                Text(request.warningDetail, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                Spacer(Modifier.height(6.dp))
                Text("VERIFICATION: ${request.sha256Preview}", style = MaterialTheme.typography.labelSmall, color = colors.emerald)
            }

            if (request.allowAutoBackup) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Backup Before Flash", style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                        Text("Saves stock dump to Download/Administrator/Backups/ first", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                    }
                    Switch(
                        checked = autoBackup,
                        onCheckedChange = { autoBackup = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = colors.emerald, checkedTrackColor = colors.emerald.copy(alpha = 0.25f))
                    )
                }
            }

            SlideToConfirmTrack(
                label = "SLIDE TO FLASH PARTITION",
                accentColor = colors.crimson,
                onConfirmed = {
                    onDismiss()
                    request.onConfirmed(autoBackup)
                }
            )

            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().testTag("cancel_flash_gate_btn")
            ) {
                Text("Cancel")
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SlideToConfirmTrack(
    label: String,
    accentColor: Color,
    onConfirmed: () -> Unit
) {
    val colors = LocalAdminColors.current
    val thumbSize = 48.dp
    val density = LocalDensity.current
    var dragOffsetPx by remember { mutableFloatStateOf(0f) }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(accentColor.copy(alpha = 0.12f))
            .border(1.dp, accentColor.copy(alpha = 0.6f), RoundedCornerShape(28.dp))
            .padding(4.dp)
            .testTag("slide_to_confirm_track")
    ) {
        val maxSlidePx = with(density) { (maxWidth - thumbSize).toPx().coerceAtLeast(1f) }
        val progress = (dragOffsetPx / maxSlidePx).coerceIn(0f, 1f)

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (progress > 0.8f) "RELEASE TO EXECUTE" else label,
                style = MaterialTheme.typography.labelLarge,
                color = accentColor.copy(alpha = (1f - progress * 0.4f).coerceIn(0.4f, 1f))
            )
        }

        Box(
            modifier = Modifier
                .offset { IntOffset(dragOffsetPx.roundToInt(), 0) }
                .size(thumbSize)
                .clip(CircleShape)
                .background(accentColor)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        dragOffsetPx = (dragOffsetPx + delta).coerceIn(0f, maxSlidePx)
                    },
                    onDragStopped = {
                        if (dragOffsetPx >= maxSlidePx * 0.82f) {
                            onConfirmed()
                        } else {
                            dragOffsetPx = 0f
                        }
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = label, tint = Color.White)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharedLiveConsoleSheet(
    state: ConsoleSheetState,
    onDismiss: () -> Unit
) {
    if (!state.visible) return
    val colors = LocalAdminColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.oledBackground
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 320.dp, max = 560.dp)
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(state.title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                    Text(
                        text = if (state.running) "Streaming shell output..." else "Process finished",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.running) colors.accent else colors.emerald
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close console", tint = colors.textSecondary)
                }
            }
            HorizontalDivider(color = colors.cardBorder, modifier = Modifier.padding(vertical = 8.dp))
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF04060A))
                    .border(1.dp, colors.cardBorder, RoundedCornerShape(12.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                itemsIndexed(state.lines, key = { idx, _ -> idx }) { _, line ->
                    val tint = when {
                        line.contains("[error]", true) || line.contains("FAILED", true) || line.contains("[stderr]", true) -> colors.crimson
                        line.contains("[warn]", true) || line.contains("[SIM]", true) -> colors.amber
                        line.contains("SUCCESS", true) || line.contains("COMPLETE", true) || line.contains("Saved", true) -> colors.emerald
                        else -> colors.textPrimary
                    }
                    Text(text = line, style = MonoCodeStyle, color = tint)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                enabled = !state.running,
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black)
            ) {
                Text(if (state.running) "Running..." else "Done")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonospaceScriptEditorSheet(
    title: String,
    subtitle: String,
    initialText: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    val colors = LocalAdminColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember(initialText) { mutableStateOf(initialText.replace("\r\n", "\n")) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.cardElevated
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = colors.textPrimary)
            Text("$subtitle · Unix LF enforced", style = MaterialTheme.typography.labelSmall, color = colors.accent)
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.replace("\r\n", "\n") },
                textStyle = MonoCodeStyle.copy(color = colors.textPrimary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    onClick = {
                        onSave(text.replace("\r\n", "\n"))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black)
                ) { Text("Save & Apply") }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}
