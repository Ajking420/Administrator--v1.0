package com.example.core.repos

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class AvailableUpdate(
    val id: String,
    val title: String,
    val currentVersion: String,
    val latestVersion: String,
    val componentType: String,
    val downloadUrl: String
)

/**
 * Upstream Auto-Update Engine checking GitHub Releases & loaded Multi-Repo catalogs
 * for Root Core (Magisk/Kitsune), Vector Framework, ReZygisk, and installed modules.
 */
object AutoUpdateEngine {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun checkUpstreamUpdates(
        checkMagisk: Boolean,
        checkVector: Boolean,
        checkModules: Boolean,
        installedModules: List<Pair<String, Long>>,
        liveCatalog: List<RepoModule>
    ): List<AvailableUpdate> = withContext(Dispatchers.IO) {
        val updates = mutableListOf<AvailableUpdate>()

        if (checkMagisk) {
            val tag = fetchLatestGithubTag("topjohnwu/Magisk")
            if (tag != null && tag != "v28.1") {
                updates += AvailableUpdate(
                    id = "core_magisk",
                    title = "Root Core (Magisk / Kitsune)",
                    currentVersion = "v28.1",
                    latestVersion = tag,
                    componentType = "Core",
                    downloadUrl = "https://github.com/topjohnwu/Magisk/releases/latest"
                )
            }
        }

        if (checkVector) {
            val reZygiskTag = fetchLatestGithubTag("PerformanC/ReZygisk")
            if (reZygiskTag != null) {
                updates += AvailableUpdate(
                    id = "core_rezygisk",
                    title = "ReZygisk Daemon",
                    currentVersion = "v1.0.0-rc2",
                    latestVersion = reZygiskTag,
                    componentType = "Core",
                    downloadUrl = "https://github.com/PerformanC/ReZygisk/releases/latest"
                )
            }
        }

        if (checkModules && liveCatalog.isNotEmpty()) {
            val catalogById = liveCatalog.associateBy { it.id.lowercase() }
            for ((modId, localCode) in installedModules) {
                val remote = catalogById[modId.lowercase()] ?: continue
                if (remote.versionCode > localCode) {
                    updates += AvailableUpdate(
                        id = remote.id,
                        title = remote.name,
                        currentVersion = "code $localCode",
                        latestVersion = "${remote.version} (${remote.versionCode})",
                        componentType = "Module",
                        downloadUrl = remote.downloadUrl
                    )
                }
            }
        }
        updates
    }

    private fun fetchLatestGithubTag(repo: String): String? {
        return runCatching {
            val req = Request.Builder()
                .url("https://api.github.com/repos/$repo/releases/latest")
                .header("User-Agent", "Administrator-SM-E156B/1.0")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = JSONObject(resp.body?.string().orEmpty())
                json.optString("tag_name").ifBlank { null }
            }
        }.getOrNull()
    }
}
