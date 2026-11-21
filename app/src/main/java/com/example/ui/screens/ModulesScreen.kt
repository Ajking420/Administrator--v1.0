package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.repos.RepoFamily
import com.example.core.repos.RepoModule
import com.example.ui.components.AdminCard
import com.example.ui.components.SegmentedControlBar
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalAdminColors
import com.example.ui.theme.MonoCodeStyle
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.InstalledModule
import com.example.viewmodel.InstalledModuleKind
import com.example.viewmodel.ModulesViewModel
import com.example.viewmodel.RepoSortMode
import kotlinx.coroutines.launch

@Composable
fun ModulesScreen(
    adminVm: AdminViewModel,
    modulesVm: ModulesViewModel
) {
    val colors = LocalAdminColors.current
    val scope = rememberCoroutineScope()
    val settings by adminVm.settings.collectAsStateWithLifecycle()
    val state by modulesVm.uiState.collectAsStateWithLifecycle()
    val installedApps by adminVm.installedApps.collectAsStateWithLifecycle()

    var subTab by remember { mutableIntStateOf(0) }
    var installedFilter by remember { mutableStateOf("All") }
    var searchQuery by remember { mutableStateOf("") }
    var sortMode by remember { mutableStateOf(RepoSortMode.UPDATED) }
    var selectedCategory by remember { mutableStateOf("All") }
    var selectedSourceRepo by remember { mutableStateOf("All") }

    var showAddRepoDialog by remember { mutableStateOf(false) }
    var selectedRepoModule by remember { mutableStateOf<RepoModule?>(null) }
    var superKeyTarget by remember { mutableStateOf<Pair<InstalledModule, Boolean>?>(null) }
    var scopeTarget by remember { mutableStateOf<InstalledModule?>(null) }

    val localFlashPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast("/") ?: "module.zip"
            modulesVm.flashLocalUri(uri, name, settings.simulationMode, adminVm)
        }
    }

    LaunchedEffect(settings.simulationMode, settings.disabledRepoIds, settings.customRepoUrls) {
        modulesVm.initialize(
            simulationMode = settings.simulationMode,
            disabledRepoIds = settings.disabledRepoIds,
            customRepoUrls = settings.customRepoUrls,
            autoCheckUpdates = settings.autoUpdatesMaster,
            checkMagisk = settings.autoUpdateMagiskCore,
            checkVector = settings.autoUpdateVectorService,
            checkModules = settings.autoUpdateInstalledModules
        )
    }

    val filteredInstalled by remember(state.installed, installedFilter) {
        derivedStateOf {
            state.installed.filter { m ->
                when (installedFilter) {
                    "Magisk" -> m.kind == InstalledModuleKind.MAGISK
                    "APM & KPM" -> m.kind == InstalledModuleKind.APM_KPM
                    "Vector" -> m.kind == InstalledModuleKind.VECTOR
                    else -> true
                }
            }
        }
    }

    val targetFamily = if (subTab == 1) RepoFamily.MAGISK_KSU_APATCH else RepoFamily.VECTOR_XPOSED

    val familyCatalog by remember(state.liveModules, targetFamily) {
        derivedStateOf { state.liveModules.filter { it.family == targetFamily } }
    }

    val categories by remember(familyCatalog) {
        derivedStateOf { listOf("All") + familyCatalog.map { it.category }.distinct().sorted() }
    }

    val repoSourceNames by remember(familyCatalog) {
        derivedStateOf { listOf("All") + familyCatalog.map { it.sourceRepoName }.distinct().sorted() }
    }

    val filteredCatalog by remember(familyCatalog, searchQuery, sortMode, selectedCategory, selectedSourceRepo) {
        derivedStateOf {
            val base = familyCatalog.filter { mod ->
                val matchesSearch = searchQuery.isBlank() ||
                    mod.name.contains(searchQuery, true) ||
                    mod.id.contains(searchQuery, true) ||
                    mod.description.contains(searchQuery, true) ||
                    mod.author.contains(searchQuery, true)
                val matchesCat = selectedCategory == "All" || mod.category == selectedCategory
                val matchesRepo = selectedSourceRepo == "All" || mod.sourceRepoName == selectedSourceRepo
                matchesSearch && matchesCat && matchesRepo
            }
            when (sortMode) {
                RepoSortMode.UPDATED -> base.sortedByDescending { it.updatedAtEpoch }
                RepoSortMode.STARS -> base.sortedByDescending { it.stars }
                RepoSortMode.NAME -> base.sortedBy { it.name.lowercase() }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(colors.oledBackground)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (state.availableUpdates.isNotEmpty()) {
                item(key = "updates_banner", contentType = "banner") {
                    AdminCard(borderColor = colors.emerald.copy(alpha = 0.65f), contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("${state.availableUpdates.size} Upstream Update(s) Available", style = MaterialTheme.typography.titleSmall, color = colors.emerald)
                                Text(state.availableUpdates.joinToString(" · ") { "${it.title} (${it.latestVersion})" }, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2)
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    adminVm.openConsole(
                                        "Auto-Update Engine (${state.availableUpdates.size} packages)",
                                        state.availableUpdates.map { "-> ${it.title}: ${it.currentVersion} -> ${it.latestVersion} (${it.downloadUrl})" },
                                        running = false
                                    )
                                    adminVm.emitSnackbar("Queued ${state.availableUpdates.size} upstream updates")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = colors.emerald, contentColor = Color.Black),
                                modifier = Modifier.testTag("update_all_banner_btn")
                            ) {
                                Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Update All")
                            }
                        }
                    }
                }
            }

            item(key = "modules_segments", contentType = "segments") {
                SegmentedControlBar(
                    options = listOf("Installed", "Magisk/KSU/AP", "Vector/Xposed"),
                    selectedIndex = subTab,
                    onSelect = {
                        subTab = it
                        selectedCategory = "All"
                        selectedSourceRepo = "All"
                    }
                )
            }

            if (subTab == 0) {
                item(key = "installed_filter_row", contentType = "filters") {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("All", "Magisk", "APM & KPM", "Vector").forEach { f ->
                            FilterChip(selected = installedFilter == f, onClick = { installedFilter = f }, label = { Text(f) })
                        }
                    }
                }

                if (filteredInstalled.isEmpty()) {
                    item(key = "empty_installed", contentType = "empty") {
                        AdminCard {
                            Text("No Modules Detected", style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
                            Text("Grant root permission or browse the Live Multi-Repo tabs to download & flash modules.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                    }
                } else {
                    items(filteredInstalled, key = { "inst_${it.id}" }, contentType = { "installed_card" }) { mod ->
                        InstalledModuleCard(
                            mod = mod,
                            onToggleEnabled = { enabled ->
                                modulesVm.toggleModuleEnabled(mod, enabled, settings.simulationMode) { adminVm.emitSnackbar(it) }
                            },
                            onTogglePreInit = { pre ->
                                modulesVm.togglePreInitMount(mod, pre) { adminVm.emitSnackbar(it) }
                            },
                            onToggleKpm = { load ->
                                superKeyTarget = mod to load
                            },
                            onHotReload = {
                                modulesVm.hotReloadVectorModule(mod, settings.simulationMode) { adminVm.emitSnackbar(it) }
                            },
                            onEditScope = { scopeTarget = mod }
                        )
                    }
                }
            } else {
                // Live Multi-Repo Header & Controls
                item(key = "repo_header_controls", contentType = "repo_controls") {
                    AdminCard(contentPadding = PaddingValues(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (subTab == 1) "Magisk / KSU / APatch Live Catalog" else "Vector / LSPosed / Xposed Catalog",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = "${familyCatalog.size} live modules · ${if (state.fromCache) "cached + syncing" else "live synced"}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.accent
                                )
                            }
                            if (state.isRefreshingRepos) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent)
                            } else {
                                IconButton(
                                    onClick = {
                                        modulesVm.loadAndRefreshRepos(settings.disabledRepoIds, settings.customRepoUrls)
                                    },
                                    modifier = Modifier.testTag("refresh_repos_btn")
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Live Repos", tint = colors.accent)
                                }
                            }
                            OutlinedButton(
                                onClick = { showAddRepoDialog = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.testTag("add_custom_repo_header_btn")
                            ) {
                                Text("+ Custom URL", maxLines = 1)
                            }
                        }

                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Search ${familyCatalog.size} live modules...") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("repo_search_input")
                        )

                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            RepoSortMode.entries.forEach { sm ->
                                FilterChip(selected = sortMode == sm, onClick = { sortMode = sm }, label = { Text(sm.label) })
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            categories.forEach { cat ->
                                FilterChip(selected = selectedCategory == cat, onClick = { selectedCategory = cat }, label = { Text(cat) })
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            repoSourceNames.forEach { src ->
                                FilterChip(
                                    selected = selectedSourceRepo == src,
                                    onClick = { selectedSourceRepo = src },
                                    label = { Text(src.take(26)) }
                                )
                            }
                        }
                    }
                }

                if (filteredCatalog.isEmpty()) {
                    item(key = "repo_empty", contentType = "empty") {
                        AdminCard {
                            Text(
                                text = if (state.isRefreshingRepos) "Fetching live JSON repositories concurrently..." else "No modules matched current filters",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                            Text("All 6 Magisk/KSU/APatch feeds and 3 Vector/LSPosed/Xposed feeds are fetched live via OkHttp and cached locally.", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                        }
                    }
                } else {
                    items(filteredCatalog, key = { "live_${it.family.name}_${it.id}" }, contentType = { "repo_module" }) { mod ->
                        AdminCard(
                            modifier = Modifier.clickable { selectedRepoModule = mod },
                            contentPadding = PaddingValues(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mod.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${mod.author} · ${mod.version} · ${mod.sourceRepoName.take(24)}", style = MaterialTheme.typography.labelSmall, color = colors.accent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                if (mod.stars > 0) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Star, contentDescription = null, tint = colors.amber, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("${mod.stars}", style = MaterialTheme.typography.labelSmall, color = colors.amber)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(mod.description, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusPill(mod.category, if (mod.family == RepoFamily.VECTOR_XPOSED) colors.violet else colors.accent)
                                Button(
                                    onClick = { modulesVm.downloadAndFlashRepoModule(mod, settings.simulationMode, adminVm) },
                                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Download & Flash")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (subTab == 0) {
            FloatingActionButton(
                onClick = { localFlashPicker.launch(arrayOf("*/*")) },
                containerColor = colors.accent,
                contentColor = Color.Black,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 16.dp, bottom = 96.dp)
                    .testTag("fab_flash_local_zip")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Flash Local ZIP or KPM")
                    Spacer(Modifier.width(6.dp))
                    Text("Flash ZIP / KPM", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }

    selectedRepoModule?.let { mod ->
        RepoModuleDetailSheet(
            module = mod,
            onDismiss = { selectedRepoModule = null },
            onDownloadAndFlash = {
                selectedRepoModule = null
                modulesVm.downloadAndFlashRepoModule(mod, settings.simulationMode, adminVm)
            }
        )
    }

    superKeyTarget?.let { (mod, load) ->
        var keyInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { superKeyTarget = null },
            title = { Text("APatch SuperKey Required") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${if (load) "Load" else "Unload"} KPM binary for ${mod.name}.", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = keyInput,
                        onValueChange = { keyInput = it },
                        label = { Text("SuperKey") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    modulesVm.verifyApatchSuperKeyAndToggleKpm(mod, keyInput, load, settings.simulationMode) { adminVm.emitSnackbar(it) }
                    superKeyTarget = null
                }) { Text(if (load) "Load KPM" else "Unload KPM") }
            },
            dismissButton = { TextButton(onClick = { superKeyTarget = null }) { Text("Cancel") } }
        )
    }

    scopeTarget?.let { mod ->
        var selectedScopes by remember(mod) { mutableStateOf(mod.vectorScopeApps.toSet()) }
        AlertDialog(
            onDismissRequest = { scopeTarget = null },
            title = { Text("Vector App Scope (${mod.name})") },
            text = {
                LazyColumn(modifier = Modifier.height(260.dp)) {
                    items(installedApps.take(30), key = { it.packageName }) { app ->
                        val checked = app.packageName in selectedScopes
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selectedScopes = if (checked) selectedScopes - app.packageName else selectedScopes + app.packageName
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = {
                                selectedScopes = if (checked) selectedScopes - app.packageName else selectedScopes + app.packageName
                            })
                            Column {
                                Text(app.appName, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    modulesVm.updateVectorScope(mod.id, selectedScopes.toList()) { adminVm.emitSnackbar(it) }
                    scopeTarget = null
                }) { Text("Save Scope") }
            },
            dismissButton = { TextButton(onClick = { scopeTarget = null }) { Text("Cancel") } }
        )
    }

    if (showAddRepoDialog) {
        var customUrl by remember { mutableStateOf("https://") }
        AlertDialog(
            onDismissRequest = { showAddRepoDialog = false },
            title = { Text("Add Custom JSON Repo URL") },
            text = {
                OutlinedTextField(
                    value = customUrl,
                    onValueChange = { customUrl = it },
                    label = { Text("HTTPS JSON Repo Endpoint") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        val ok = adminVm.prefs.addCustomRepoUrl(customUrl)
                        adminVm.emitSnackbar(if (ok) "Added custom live repository" else "URL must start with https://")
                        showAddRepoDialog = false
                    }
                }) { Text("Add Repo") }
            },
            dismissButton = { TextButton(onClick = { showAddRepoDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun InstalledModuleCard(
    mod: InstalledModule,
    onToggleEnabled: (Boolean) -> Unit,
    onTogglePreInit: (Boolean) -> Unit,
    onToggleKpm: (Boolean) -> Unit,
    onHotReload: () -> Unit,
    onEditScope: () -> Unit
) {
    val colors = LocalAdminColors.current
    val kindColor = when (mod.kind) {
        InstalledModuleKind.MAGISK -> colors.accent
        InstalledModuleKind.APM_KPM -> colors.amber
        InstalledModuleKind.VECTOR -> colors.violet
    }

    AdminCard(borderColor = if (mod.enabled) kindColor.copy(alpha = 0.45f) else colors.cardBorder) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(mod.name, style = MaterialTheme.typography.titleSmall, color = colors.textPrimary)
                    Spacer(Modifier.width(6.dp))
                    StatusPill(mod.kind.label, kindColor)
                }
                Text("${mod.version} · ${mod.author}", style = MaterialTheme.typography.labelSmall, color = colors.textSecondary)
            }
            Switch(
                checked = mod.enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(checkedThumbColor = kindColor, checkedTrackColor = kindColor.copy(alpha = 0.25f))
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(mod.description, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(8.dp))

        when (mod.kind) {
            InstalledModuleKind.MAGISK -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Pre-Init Mount (Kitsune / Early Stage)", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    Switch(checked = mod.preInitMount, onCheckedChange = onTogglePreInit)
                }
            }
            InstalledModuleKind.APM_KPM -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onToggleKpm(!mod.kpmLoaded) }) {
                        Icon(Icons.Default.Key, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (mod.kpmLoaded) "Unload KPM (SuperKey)" else "Load KPM (SuperKey)")
                    }
                }
            }
            InstalledModuleKind.VECTOR -> {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onHotReload,
                        colors = ButtonDefaults.buttonColors(containerColor = colors.violet, contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Hot-Reload API 102")
                    }
                    OutlinedButton(onClick = onEditScope) {
                        Text("Scope (${mod.vectorScopeApps.size})")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepoModuleDetailSheet(
    module: RepoModule,
    onDismiss: () -> Unit,
    onDownloadAndFlash: () -> Unit
) {
    val colors = LocalAdminColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = colors.cardElevated) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(module.name, style = MaterialTheme.typography.headlineSmall, color = colors.textPrimary)
            Text("${module.id} · ${module.version} · by ${module.author}", style = MonoCodeStyle, color = colors.accent)
            StatusPill("Source: ${module.sourceRepoName}", colors.violet)
            Text(module.description, style = MaterialTheme.typography.bodyMedium, color = colors.textPrimary)
            Text("Changelog / Release Notes", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
            Text(module.changelog.take(600), style = MonoCodeStyle, color = colors.textSecondary)
            Button(
                onClick = onDownloadAndFlash,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Download & Flash Package")
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
