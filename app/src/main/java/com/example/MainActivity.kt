package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.ui.components.AdminNavTab
import com.example.ui.components.AdminTopBar
import com.example.ui.components.FloatingGlassBottomBar
import com.example.ui.components.RebootActionsModalSheet
import com.example.ui.components.SharedLiveConsoleSheet
import com.example.ui.components.SlideToConfirmFlashSheet
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ModulesScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SuperuserScreen
import com.example.ui.screens.ToolkitScreen
import com.example.ui.screens.TweaksTerminalScreen
import com.example.ui.theme.AccentPreset
import com.example.ui.theme.AdministratorTheme
import com.example.ui.theme.LocalAdminColors
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.CrossTabEvent
import com.example.viewmodel.ModulesViewModel
import com.example.viewmodel.ToolkitViewModel
import com.example.viewmodel.TweaksTerminalViewModel
import java.io.File

private const val ROUTE_SETTINGS = "settings"

class MainActivity : ComponentActivity() {

    private var adminViewModelRef: AdminViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            val adminVm: AdminViewModel = viewModel()
            val modulesVm: ModulesViewModel = viewModel()
            val toolkitVm: ToolkitViewModel = viewModel()
            val tweaksVm: TweaksTerminalViewModel = viewModel()
            adminViewModelRef = adminVm

            val settings by adminVm.settings.collectAsStateWithLifecycle()

            AdministratorTheme(
                accentPreset = AccentPreset.fromKey(settings.accentPresetKey),
                animSpeedScale = settings.animSpeedScale
            ) {
                AdministratorAppRoot(
                    adminVm = adminVm,
                    modulesVm = modulesVm,
                    toolkitVm = toolkitVm,
                    tweaksVm = tweaksVm
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        adminViewModelRef?.refreshStoragePermission()
    }
}

@Composable
private fun AdministratorAppRoot(
    adminVm: AdminViewModel,
    modulesVm: ModulesViewModel,
    toolkitVm: ToolkitViewModel,
    tweaksVm: TweaksTerminalViewModel
) {
    val colors = LocalAdminColors.current
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route ?: AdminNavTab.DASHBOARD.route

    val settings by adminVm.settings.collectAsStateWithLifecycle()
    val telemetry by adminVm.telemetry.collectAsStateWithLifecycle()
    val consoleState by adminVm.console.collectAsStateWithLifecycle()
    val flashGate by adminVm.flashGate.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var showRebootSheet by remember { mutableStateOf(false) }
    var requestedToolkitSubTab by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        adminVm.snackbar.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) {
        adminVm.crossTabEvents.collect { event ->
            when (event) {
                CrossTabEvent.OpenCrashAnalyzer -> {
                    requestedToolkitSubTab = 3
                    navController.navigate(AdminNavTab.TOOLKIT.route) {
                        popUpTo(AdminNavTab.DASHBOARD.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                    toolkitVm.runCrashAnalyzer(adminVm.settings.value.simulationMode, adminVm)
                }
                is CrossTabEvent.OpenModuleStudioWithSepolicy -> {
                    toolkitVm.updateStudioDraft { it.copy(sepolicyRule = event.rules) }
                    requestedToolkitSubTab = 3
                    navController.navigate(AdminNavTab.TOOLKIT.route) { launchSingleTop = true }
                }
                CrossTabEvent.OpenRecoveryBuilderWithDt -> {
                    requestedToolkitSubTab = 2
                    navController.navigate(AdminNavTab.TOOLKIT.route) { launchSingleTop = true }
                }
                is CrossTabEvent.FlashLocalModulePath -> {
                    navController.navigate(AdminNavTab.MODULES.route) {
                        popUpTo(AdminNavTab.DASHBOARD.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                    val f = File(event.displayPath.removePrefix("[SIM] "))
                    adminVm.openConsole("Flash Studio Module", listOf("Target: ${event.displayPath}"))
                    modulesVm.flashLocalFile(f, false, adminVm.settings.value.simulationMode, adminVm)
                }
            }
        }
    }

    val isSettingsRoute = currentRoute == ROUTE_SETTINGS

    Scaffold(
        containerColor = colors.oledBackground,
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(bottom = if (isSettingsRoute) 16.dp else 96.dp)
            )
        }
    ) { _ ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().background(colors.oledBackground)) {
            val useNavRail = maxWidth >= 840.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (useNavRail && !isSettingsRoute) {
                    NavigationRail(
                        containerColor = colors.glassBar,
                        modifier = Modifier.fillMaxHeight().navigationBarsPadding()
                    ) {
                        AdminNavTab.entries.forEach { tab ->
                            val selected = currentRoute == tab.route
                            NavigationRailItem(
                                selected = selected,
                                onClick = {
                                    navController.navigate(tab.route) {
                                        popUpTo(AdminNavTab.DASHBOARD.route) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(tab.icon, contentDescription = tab.label) },
                                label = { Text(tab.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = colors.accent,
                                    selectedTextColor = colors.accent,
                                    unselectedIconColor = colors.textSecondary,
                                    unselectedTextColor = colors.textSecondary,
                                    indicatorColor = colors.accent.copy(alpha = 0.16f)
                                )
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (!isSettingsRoute) {
                            AdminTopBar(
                                deviceBadge = telemetry.deviceBadge,
                                simulationMode = settings.simulationMode,
                                antiBootloopArmed = settings.antiBootloopWatchdog || settings.powerKeyPanicTrigger,
                                antiBootloopTriggered = telemetry.antiBootloopTriggered,
                                onRebootClick = { showRebootSheet = true },
                                onShieldClick = {
                                    adminVm.emitSnackbar(
                                        if (settings.antiBootloopWatchdog) "Anti-Bootloop Guardian ARMED (${settings.bootloopTimeoutSec}s timeout)"
                                        else "Anti-Bootloop Watchdog is disabled"
                                    )
                                },
                                onSettingsClick = {
                                    navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true }
                                }
                            )
                        }

                        NavHost(
                            navController = navController,
                            startDestination = AdminNavTab.DASHBOARD.route,
                            modifier = Modifier.weight(1f)
                        ) {
                            composable(AdminNavTab.DASHBOARD.route) {
                                DashboardScreen(adminVm = adminVm)
                            }
                            composable(AdminNavTab.SUPERUSER.route) {
                                SuperuserScreen(adminVm = adminVm)
                            }
                            composable(AdminNavTab.MODULES.route) {
                                ModulesScreen(adminVm = adminVm, modulesVm = modulesVm)
                            }
                            composable(AdminNavTab.TOOLKIT.route) {
                                ToolkitScreen(
                                    adminVm = adminVm,
                                    toolkitVm = toolkitVm,
                                    requestedSubTab = requestedToolkitSubTab,
                                    onConsumedRequestedSubTab = { requestedToolkitSubTab = null },
                                    onOpenSettingsVault = { navController.navigate(ROUTE_SETTINGS) { launchSingleTop = true } }
                                )
                            }
                            composable(AdminNavTab.TWEAKS.route) {
                                TweaksTerminalScreen(adminVm = adminVm, tweaksVm = tweaksVm)
                            }
                            composable(ROUTE_SETTINGS) {
                                Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(androidx.compose.foundation.layout.WindowInsets.statusBars)) {
                                    SettingsScreen(
                                        adminVm = adminVm,
                                        modulesVm = modulesVm,
                                        toolkitVm = toolkitVm,
                                        onBack = { navController.popBackStack() }
                                    )
                                }
                            }
                        }
                    }

                    if (!useNavRail && !isSettingsRoute) {
                        FloatingGlassBottomBar(
                            currentRoute = currentRoute,
                            onSelect = { tab ->
                                navController.navigate(tab.route) {
                                    popUpTo(AdminNavTab.DASHBOARD.route) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                }
            }
        }
    }

    if (showRebootSheet) {
        RebootActionsModalSheet(
            onDismiss = { showRebootSheet = false },
            onSelectReboot = { mode -> adminVm.executeRebootAction(mode) }
        )
    }

    flashGate?.let { req ->
        SlideToConfirmFlashSheet(
            request = req,
            onDismiss = { adminVm.dismissFlashGate() }
        )
    }

    SharedLiveConsoleSheet(
        state = consoleState,
        onDismiss = { adminVm.dismissConsole() }
    )
}
