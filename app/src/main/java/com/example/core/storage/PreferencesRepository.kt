package com.example.core.storage

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.core.shell.RootShellExecutor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.adminDataStore: DataStore<Preferences> by preferencesDataStore(name = "administrator_prefs")

data class AdminSettings(
    val simulationMode: Boolean,
    val simulationInitialized: Boolean,
    val accentPresetKey: String = "cyan",
    val animSpeedScale: Float = 1.0f,
    val reZygiskEnabled: Boolean = true,
    val systemlessHostsEnabled: Boolean = true,
    val vectorFrameworkEnabled: Boolean = true,
    val antiBootloopWatchdog: Boolean = true,
    val powerKeyPanicTrigger: Boolean = true,
    val bootloopTimeoutSec: Int = 60,
    val autoUpdatesMaster: Boolean = true,
    val autoUpdateMagiskCore: Boolean = true,
    val autoUpdateVectorService: Boolean = true,
    val autoUpdateInstalledModules: Boolean = true,
    val disabledRepoIds: Set<String> = emptySet(),
    val customRepoUrls: Set<String> = emptySet(),
    val tweaksMasterEnabled: Boolean = false,
    val perfProfile: String = "Auto",
    val zramMasterEnabled: Boolean = true,
    val zramSizePreset: String = "4 GB",
    val zramAlgorithm: String = "zstd",
    val swappiness: Int = 60,
    val disableSamsungRamPlus: Boolean = true,
    val applyTweaksOnBoot: Boolean = false,
    val spoofPreset: String = "Stock SM-E156B",
    val spoofModel: String = "SM-E156B",
    val spoofBrand: String = "samsung",
    val spoofFingerprint: String = "samsung/e15xnsxx/e15x:14/UP1A.231005.007/E156BXXU3BXH1:user/release-keys",
    val spoofSecurityPatch: String = "2026-08-01",
    val githubOwnerRepo: String = "",
    val githubBranch: String = "main",
    val gitlabProjectUrl: String = "",
    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUser: String = "builder",
    val hasGithubToken: Boolean = false,
    val hasGitlabToken: Boolean = false,
    val hasSshKey: Boolean = false
)

class PreferencesRepository(private val context: Context) {

    private object Keys {
        val SIMULATION_MODE = booleanPreferencesKey("simulation_mode")
        val SIMULATION_INIT = booleanPreferencesKey("simulation_initialized")
        val ACCENT_KEY = stringPreferencesKey("accent_preset")
        val ANIM_SPEED = floatPreferencesKey("anim_speed_scale")
        val REZYGISK = booleanPreferencesKey("rezygisk_enabled")
        val SYSTEMLESS_HOSTS = booleanPreferencesKey("systemless_hosts_enabled")
        val VECTOR_FRAMEWORK = booleanPreferencesKey("vector_framework_enabled")
        val ANTIBOOTLOOP_WATCHDOG = booleanPreferencesKey("antibootloop_watchdog")
        val POWER_KEY_PANIC = booleanPreferencesKey("power_key_panic")
        val BOOTLOOP_TIMEOUT = intPreferencesKey("bootloop_timeout_sec")
        val AUTO_UPDATE_MASTER = booleanPreferencesKey("auto_update_master")
        val AUTO_UPDATE_MAGISK = booleanPreferencesKey("auto_update_magisk")
        val AUTO_UPDATE_VECTOR = booleanPreferencesKey("auto_update_vector")
        val AUTO_UPDATE_MODULES = booleanPreferencesKey("auto_update_modules")
        val DISABLED_REPOS = stringSetPreferencesKey("disabled_repo_ids")
        val CUSTOM_REPOS = stringSetPreferencesKey("custom_repo_urls")
        val TWEAKS_MASTER = booleanPreferencesKey("tweaks_master")
        val PERF_PROFILE = stringPreferencesKey("perf_profile")
        val ZRAM_MASTER = booleanPreferencesKey("zram_master")
        val ZRAM_SIZE = stringPreferencesKey("zram_size")
        val ZRAM_ALGO = stringPreferencesKey("zram_algo")
        val SWAPPINESS = intPreferencesKey("swappiness")
        val DISABLE_RAM_PLUS = booleanPreferencesKey("disable_ram_plus")
        val APPLY_ON_BOOT = booleanPreferencesKey("apply_tweaks_on_boot")
        val SPOOF_PRESET = stringPreferencesKey("spoof_preset")
        val SPOOF_MODEL = stringPreferencesKey("spoof_model")
        val SPOOF_BRAND = stringPreferencesKey("spoof_brand")
        val SPOOF_FP = stringPreferencesKey("spoof_fingerprint")
        val SPOOF_PATCH = stringPreferencesKey("spoof_security_patch")
        val GH_REPO = stringPreferencesKey("gh_owner_repo")
        val GH_BRANCH = stringPreferencesKey("gh_branch")
        val GH_TOKEN_ENC = stringPreferencesKey("gh_token_enc")
        val GL_URL = stringPreferencesKey("gl_project_url")
        val GL_TOKEN_ENC = stringPreferencesKey("gl_token_enc")
        val SSH_HOST = stringPreferencesKey("ssh_host")
        val SSH_PORT = intPreferencesKey("ssh_port")
        val SSH_USER = stringPreferencesKey("ssh_user")
        val SSH_KEY_ENC = stringPreferencesKey("ssh_key_enc")
    }

    private val defaultSimMode: Boolean = RootShellExecutor.isRunningOnEmulator()

    val settingsFlow: Flow<AdminSettings> = context.adminDataStore.data.map { p ->
        val initialized = p[Keys.SIMULATION_INIT] ?: false
        val simMode = if (initialized) {
            p[Keys.SIMULATION_MODE] ?: defaultSimMode
        } else {
            defaultSimMode
        }
        AdminSettings(
            simulationMode = simMode,
            simulationInitialized = initialized,
            accentPresetKey = p[Keys.ACCENT_KEY] ?: "cyan",
            animSpeedScale = p[Keys.ANIM_SPEED] ?: 1.0f,
            reZygiskEnabled = p[Keys.REZYGISK] ?: true,
            systemlessHostsEnabled = p[Keys.SYSTEMLESS_HOSTS] ?: true,
            vectorFrameworkEnabled = p[Keys.VECTOR_FRAMEWORK] ?: true,
            antiBootloopWatchdog = p[Keys.ANTIBOOTLOOP_WATCHDOG] ?: true,
            powerKeyPanicTrigger = p[Keys.POWER_KEY_PANIC] ?: true,
            bootloopTimeoutSec = p[Keys.BOOTLOOP_TIMEOUT] ?: 60,
            autoUpdatesMaster = p[Keys.AUTO_UPDATE_MASTER] ?: true,
            autoUpdateMagiskCore = p[Keys.AUTO_UPDATE_MAGISK] ?: true,
            autoUpdateVectorService = p[Keys.AUTO_UPDATE_VECTOR] ?: true,
            autoUpdateInstalledModules = p[Keys.AUTO_UPDATE_MODULES] ?: true,
            disabledRepoIds = p[Keys.DISABLED_REPOS] ?: emptySet(),
            customRepoUrls = p[Keys.CUSTOM_REPOS] ?: emptySet(),
            tweaksMasterEnabled = p[Keys.TWEAKS_MASTER] ?: false,
            perfProfile = p[Keys.PERF_PROFILE] ?: "Auto",
            zramMasterEnabled = p[Keys.ZRAM_MASTER] ?: true,
            zramSizePreset = p[Keys.ZRAM_SIZE] ?: "4 GB",
            zramAlgorithm = p[Keys.ZRAM_ALGO] ?: "zstd",
            swappiness = p[Keys.SWAPPINESS] ?: 60,
            disableSamsungRamPlus = p[Keys.DISABLE_RAM_PLUS] ?: true,
            applyTweaksOnBoot = p[Keys.APPLY_ON_BOOT] ?: false,
            spoofPreset = p[Keys.SPOOF_PRESET] ?: "Stock SM-E156B",
            spoofModel = p[Keys.SPOOF_MODEL] ?: "SM-E156B",
            spoofBrand = p[Keys.SPOOF_BRAND] ?: "samsung",
            spoofFingerprint = p[Keys.SPOOF_FP]
                ?: "samsung/e15xnsxx/e15x:14/UP1A.231005.007/E156BXXU3BXH1:user/release-keys",
            spoofSecurityPatch = p[Keys.SPOOF_PATCH] ?: "2026-08-01",
            githubOwnerRepo = p[Keys.GH_REPO] ?: "",
            githubBranch = p[Keys.GH_BRANCH] ?: "main",
            gitlabProjectUrl = p[Keys.GL_URL] ?: "",
            sshHost = p[Keys.SSH_HOST] ?: "",
            sshPort = p[Keys.SSH_PORT] ?: 22,
            sshUser = p[Keys.SSH_USER] ?: "builder",
            hasGithubToken = !p[Keys.GH_TOKEN_ENC].isNullOrEmpty(),
            hasGitlabToken = !p[Keys.GL_TOKEN_ENC].isNullOrEmpty(),
            hasSshKey = !p[Keys.SSH_KEY_ENC].isNullOrEmpty()
        )
    }

    suspend fun ensureSimulationDefaultPersisted() {
        context.adminDataStore.edit { p ->
            if (p[Keys.SIMULATION_INIT] != true) {
                p[Keys.SIMULATION_MODE] = defaultSimMode
                p[Keys.SIMULATION_INIT] = true
            }
        }
    }

    suspend fun setSimulationMode(enabled: Boolean) {
        context.adminDataStore.edit { p ->
            p[Keys.SIMULATION_MODE] = enabled
            p[Keys.SIMULATION_INIT] = true
        }
    }

    suspend fun setAccentPreset(key: String) {
        context.adminDataStore.edit { it[Keys.ACCENT_KEY] = key }
    }

    suspend fun setAnimSpeedScale(scale: Float) {
        context.adminDataStore.edit { it[Keys.ANIM_SPEED] = scale.coerceIn(0.25f, 2.0f) }
    }

    suspend fun setCoreService(service: String, enabled: Boolean) {
        context.adminDataStore.edit { p ->
            when (service) {
                "rezygisk" -> p[Keys.REZYGISK] = enabled
                "hosts" -> p[Keys.SYSTEMLESS_HOSTS] = enabled
                "vector" -> p[Keys.VECTOR_FRAMEWORK] = enabled
            }
        }
    }

    suspend fun setAntiBootloopConfig(
        watchdog: Boolean? = null,
        powerPanic: Boolean? = null,
        timeoutSec: Int? = null
    ) {
        context.adminDataStore.edit { p ->
            watchdog?.let { p[Keys.ANTIBOOTLOOP_WATCHDOG] = it }
            powerPanic?.let { p[Keys.POWER_KEY_PANIC] = it }
            timeoutSec?.let { p[Keys.BOOTLOOP_TIMEOUT] = it.coerceIn(30, 120) }
        }
    }

    suspend fun setAutoUpdateFlags(
        master: Boolean? = null,
        magisk: Boolean? = null,
        vector: Boolean? = null,
        modules: Boolean? = null
    ) {
        context.adminDataStore.edit { p ->
            master?.let { p[Keys.AUTO_UPDATE_MASTER] = it }
            magisk?.let { p[Keys.AUTO_UPDATE_MAGISK] = it }
            vector?.let { p[Keys.AUTO_UPDATE_VECTOR] = it }
            modules?.let { p[Keys.AUTO_UPDATE_MODULES] = it }
        }
    }

    suspend fun toggleRepoEnabled(repoId: String, enabled: Boolean) {
        context.adminDataStore.edit { p ->
            val current = p[Keys.DISABLED_REPOS]?.toMutableSet() ?: mutableSetOf()
            if (enabled) current.remove(repoId) else current.add(repoId)
            p[Keys.DISABLED_REPOS] = current
        }
    }

    suspend fun addCustomRepoUrl(url: String): Boolean {
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://")) return false
        context.adminDataStore.edit { p ->
            val current = p[Keys.CUSTOM_REPOS]?.toMutableSet() ?: mutableSetOf()
            current.add(trimmed)
            p[Keys.CUSTOM_REPOS] = current
        }
        return true
    }

    suspend fun removeCustomRepoUrl(url: String) {
        context.adminDataStore.edit { p ->
            val current = p[Keys.CUSTOM_REPOS]?.toMutableSet() ?: mutableSetOf()
            current.remove(url)
            p[Keys.CUSTOM_REPOS] = current
        }
    }

    suspend fun saveTweaksState(
        master: Boolean? = null,
        profile: String? = null,
        zramMaster: Boolean? = null,
        zramSize: String? = null,
        zramAlgo: String? = null,
        swappiness: Int? = null,
        disableRamPlus: Boolean? = null,
        applyOnBoot: Boolean? = null
    ) {
        context.adminDataStore.edit { p ->
            master?.let { p[Keys.TWEAKS_MASTER] = it }
            profile?.let { p[Keys.PERF_PROFILE] = it }
            zramMaster?.let { p[Keys.ZRAM_MASTER] = it }
            zramSize?.let { p[Keys.ZRAM_SIZE] = it }
            zramAlgo?.let { p[Keys.ZRAM_ALGO] = it }
            swappiness?.let { p[Keys.SWAPPINESS] = it.coerceIn(10, 100) }
            disableRamPlus?.let { p[Keys.DISABLE_RAM_PLUS] = it }
            applyOnBoot?.let { p[Keys.APPLY_ON_BOOT] = it }
        }
    }

    suspend fun saveSpoofProps(
        preset: String,
        model: String,
        brand: String,
        fingerprint: String,
        securityPatch: String
    ) {
        context.adminDataStore.edit { p ->
            p[Keys.SPOOF_PRESET] = preset
            p[Keys.SPOOF_MODEL] = model
            p[Keys.SPOOF_BRAND] = brand
            p[Keys.SPOOF_FP] = fingerprint
            p[Keys.SPOOF_PATCH] = securityPatch
        }
    }

    suspend fun saveGithubAuth(ownerRepo: String, branch: String, tokenPlain: String?) {
        context.adminDataStore.edit { p ->
            p[Keys.GH_REPO] = ownerRepo.trim()
            p[Keys.GH_BRANCH] = branch.trim().ifBlank { "main" }
            if (tokenPlain != null) {
                p[Keys.GH_TOKEN_ENC] = if (tokenPlain.isBlank()) "" else KeystoreCryptoVault.encrypt(tokenPlain.trim())
            }
        }
    }

    suspend fun saveGitlabAuth(projectUrl: String, tokenPlain: String?) {
        context.adminDataStore.edit { p ->
            p[Keys.GL_URL] = projectUrl.trim()
            if (tokenPlain != null) {
                p[Keys.GL_TOKEN_ENC] = if (tokenPlain.isBlank()) "" else KeystoreCryptoVault.encrypt(tokenPlain.trim())
            }
        }
    }

    suspend fun saveSshAuth(host: String, port: Int, user: String, privateKeyPlain: String?) {
        context.adminDataStore.edit { p ->
            p[Keys.SSH_HOST] = host.trim()
            p[Keys.SSH_PORT] = port.coerceIn(1, 65535)
            p[Keys.SSH_USER] = user.trim().ifBlank { "builder" }
            if (privateKeyPlain != null) {
                p[Keys.SSH_KEY_ENC] = if (privateKeyPlain.isBlank()) "" else KeystoreCryptoVault.encrypt(privateKeyPlain)
            }
        }
    }

    suspend fun readDecryptedGithubToken(): String {
        var enc = ""
        context.adminDataStore.edit { p -> enc = p[Keys.GH_TOKEN_ENC] ?: "" }
        return KeystoreCryptoVault.decrypt(enc)
    }

    suspend fun readDecryptedGitlabToken(): String {
        var enc = ""
        context.adminDataStore.edit { p -> enc = p[Keys.GL_TOKEN_ENC] ?: "" }
        return KeystoreCryptoVault.decrypt(enc)
    }

    suspend fun readDecryptedSshKey(): String {
        var enc = ""
        context.adminDataStore.edit { p -> enc = p[Keys.SSH_KEY_ENC] ?: "" }
        return KeystoreCryptoVault.decrypt(enc)
    }
}
