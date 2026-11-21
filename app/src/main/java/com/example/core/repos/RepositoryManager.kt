package com.example.core.repos

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class RepoFamily { MAGISK_KSU_APATCH, VECTOR_XPOSED }

data class RepoSource(
    val id: String,
    val name: String,
    val url: String,
    val family: RepoFamily,
    val isCustom: Boolean = false,
    val fallbackUrl: String? = null
)

data class RepoModule(
    val id: String,
    val name: String,
    val author: String,
    val version: String,
    val versionCode: Long,
    val description: String,
    val category: String,
    val stars: Int,
    val updatedAtEpoch: Long,
    val downloadUrl: String,
    val sourceRepoId: String,
    val sourceRepoName: String,
    val family: RepoFamily,
    val changelog: String = "",
    val homepage: String = ""
)

data class RepoFetchSummary(
    val modules: List<RepoModule>,
    val countsByRepoId: Map<String, Int>,
    val errorsByRepoId: Map<String, String>,
    val fromCache: Boolean
)

/**
 * All-in-One Live Multi-Repository Engine.
 * Fetches all 6 Magisk/KSU/APatch feeds + all 3 Vector/LSPosed/Xposed feeds + custom HTTPS feeds
 * concurrently via OkHttp + Coroutines, deduplicates by ID/package, and caches raw JSON locally.
 */
object RepositoryManager {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val PRELOADED_REPOS: List<RepoSource> = listOf(
        // 6 Magisk / KernelSU / APatch Live Repositories
        RepoSource(
            id = "mmrl_v2",
            name = "Magisk Modules Alt Repo (MMRL v2)",
            url = "https://magisk-modules-alt-repo.github.io/json-v2/json/modules.json",
            family = RepoFamily.MAGISK_KSU_APATCH,
            fallbackUrl = "https://raw.githubusercontent.com/Magisk-Modules-Alt-Repo/json/main/modules.json"
        ),
        RepoSource(
            id = "mmrl_v1",
            name = "Magisk Alt Repo (Legacy v1)",
            url = "https://raw.githubusercontent.com/Magisk-Modules-Alt-Repo/json/main/modules.json",
            family = RepoFamily.MAGISK_KSU_APATCH
        ),
        RepoSource(
            id = "gmr",
            name = "Googlers Magisk Repo (GMR)",
            url = "https://gr.dergoogler.com/gmr/json/modules.json",
            family = RepoFamily.MAGISK_KSU_APATCH
        ),
        RepoSource(
            id = "izzy_magisk",
            name = "IzzyOnDroid Magisk Repo",
            url = "https://apt.izzysoft.de/magisk/json/modules.json",
            family = RepoFamily.MAGISK_KSU_APATCH
        ),
        RepoSource(
            id = "ksu_official",
            name = "KernelSU Official Modules Repo",
            url = "https://modules.kernelsu.org/modules.json",
            family = RepoFamily.MAGISK_KSU_APATCH,
            fallbackUrl = "https://api.github.com/orgs/KernelSU-Modules-Repo/repos?per_page=100"
        ),
        RepoSource(
            id = "apatch_kpm_gh",
            name = "APatch / KPM Live GitHub Discovery",
            url = "https://api.github.com/search/repositories?q=topic:apatch+OR+topic:kpm+OR+topic:kernelpatch-module&sort=updated&per_page=100",
            family = RepoFamily.MAGISK_KSU_APATCH
        ),

        // 3 Vector / LSPosed / Xposed Live Repositories (1,600+ modules)
        RepoSource(
            id = "lsposed_official",
            name = "Official LSPosed / Vector Repo",
            url = "https://modules.lsposed.org/modules.json",
            family = RepoFamily.VECTOR_XPOSED
        ),
        RepoSource(
            id = "xposed_directory",
            name = "Xposed Modules Directory (1,600+)",
            url = "https://raw.githubusercontent.com/rushiranpise/xposed-modules/main/data/repos.json",
            family = RepoFamily.VECTOR_XPOSED
        ),
        RepoSource(
            id = "xposed_org_gh",
            name = "Xposed-Modules-Repo GitHub Org",
            url = "https://api.github.com/orgs/Xposed-Modules-Repo/repos?per_page=100",
            family = RepoFamily.VECTOR_XPOSED
        )
    )

    fun buildActiveSources(
        disabledRepoIds: Set<String>,
        customRepoUrls: Set<String>
    ): List<RepoSource> {
        val customs = customRepoUrls.mapIndexed { idx, url ->
            val family = if (url.contains("xposed", true) || url.contains("lsposed", true)) {
                RepoFamily.VECTOR_XPOSED
            } else {
                RepoFamily.MAGISK_KSU_APATCH
            }
            RepoSource(
                id = "custom_${url.hashCode()}_$idx",
                name = "Custom Repo (${url.removePrefix("https://").substringBefore("/")})",
                url = url,
                family = family,
                isCustom = true
            )
        }
        return (PRELOADED_REPOS + customs).filter { it.id !in disabledRepoIds }
    }

    suspend fun loadCachedCatalog(
        context: Context,
        sources: List<RepoSource>
    ): RepoFetchSummary = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "repo_json_cache")
        if (!cacheDir.exists()) return@withContext RepoFetchSummary(emptyList(), emptyMap(), emptyMap(), true)

        val counts = mutableMapOf<String, Int>()
        val dedup = LinkedHashMap<String, RepoModule>()
        for (source in sources) {
            val file = File(cacheDir, "${source.id}.json")
            if (file.exists()) {
                val parsed = runCatching { parsePayload(file.readText(), source) }.getOrDefault(emptyList())
                counts[source.id] = parsed.size
                for (m in parsed) {
                    val key = "${m.family.name}:${m.id.lowercase()}"
                    if (!dedup.containsKey(key)) dedup[key] = m
                }
            }
        }
        RepoFetchSummary(dedup.values.toList(), counts, emptyMap(), fromCache = true)
    }

    suspend fun fetchAllLiveRepos(
        context: Context,
        sources: List<RepoSource>
    ): RepoFetchSummary = withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "repo_json_cache").apply { mkdirs() }
        val results = coroutineScope {
            sources.map { source ->
                async {
                    fetchSingleSource(source, cacheDir)
                }
            }.awaitAll()
        }

        val counts = mutableMapOf<String, Int>()
        val errors = mutableMapOf<String, String>()
        val dedup = LinkedHashMap<String, RepoModule>()

        for ((source, modules, error) in results) {
            counts[source.id] = modules.size
            if (error != null) errors[source.id] = error
            for (m in modules) {
                val key = "${m.family.name}:${m.id.lowercase()}"
                val existing = dedup[key]
                if (existing == null || m.versionCode > existing.versionCode || m.stars > existing.stars) {
                    dedup[key] = m
                }
            }
        }
        RepoFetchSummary(dedup.values.toList(), counts, errors, fromCache = false)
    }

    private fun fetchSingleSource(
        source: RepoSource,
        cacheDir: File
    ): Triple<RepoSource, List<RepoModule>, String?> {
        val cacheFile = File(cacheDir, "${source.id}.json")
        val urlsToTry = listOfNotNull(source.url, source.fallbackUrl)
        var lastErr: String? = null

        for (targetUrl in urlsToTry) {
            if (!targetUrl.startsWith("https://")) {
                lastErr = "Blocked non-HTTPS repo URL"
                continue
            }
            val attempt = runCatching {
                val req = Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", "Administrator-SM-E156B/1.0")
                    .header("Accept", "application/json")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body?.string().orEmpty()
                    if (body.isBlank()) error("Empty response")
                    val parsed = parsePayload(body, source)
                    if (parsed.isNotEmpty()) {
                        cacheFile.writeText(body)
                        return Triple(source, parsed, null)
                    }
                    error("Parsed 0 entries")
                }
            }
            lastErr = attempt.exceptionOrNull()?.message
        }

        // Fall back to local cached JSON if live network failed
        if (cacheFile.exists()) {
            val cached = runCatching { parsePayload(cacheFile.readText(), source) }.getOrDefault(emptyList())
            if (cached.isNotEmpty()) {
                return Triple(source, cached, "Using cached copy ($lastErr)")
            }
        }
        return Triple(source, emptyList(), lastErr ?: "Fetch failed")
    }

    suspend fun downloadModuleFile(
        context: Context,
        module: RepoModule
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = module.downloadUrl.trim()
            if (!url.startsWith("https://")) error("No direct HTTPS package URL available for ${module.name}")
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Administrator-SM-E156B/1.0")
                .build()
            val ext = if (module.family == RepoFamily.VECTOR_XPOSED && url.endsWith(".apk", true)) "apk" else "zip"
            val outDir = File(context.cacheDir, "downloaded_modules").apply { mkdirs() }
            val safeId = module.id.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val target = File(outDir, "${safeId}_${module.versionCode}.$ext")
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Download failed: HTTP ${resp.code}")
                val body = resp.body ?: error("Empty download body")
                body.byteStream().use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
            }
            target
        }
    }

    /**
     * Multi-schema parser handling:
     * 1. MMRL JSON-v2 (`{ "modules": [ { id, name, versions: [...], ... } ] }`)
     * 2. Legacy Magisk JSON-v1 (`{ "modules": [ { id, name, zipUrl, version, ... } ] }`)
     * 3. LSPosed `modules.json` (array of `{ name, description, latestRelease, releases, ... }`)
     * 4. Xposed Directory `repos.json` (array or `{ repos / modules: [...] }`)
     * 5. GitHub REST API Org repos (`[ { full_name, stargazers_count, ... } ]`) & Search API (`{ "items": [...] }`)
     */
    internal fun parsePayload(raw: String, source: RepoSource): List<RepoModule> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return emptyList()

        val list = mutableListOf<RepoModule>()
        if (trimmed.startsWith("[")) {
            val arr = JSONArray(trimmed)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                parseEntryObject(obj, source)?.let { list += it }
            }
        } else if (trimmed.startsWith("{")) {
            val root = JSONObject(trimmed)
            val array = root.optJSONArray("modules")
                ?: root.optJSONArray("items")
                ?: root.optJSONArray("repos")
                ?: root.optJSONArray("data")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    parseEntryObject(obj, source)?.let { list += it }
                }
            } else {
                // Map-style JSON keyed by module ID
                val keys = root.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = root.optJSONObject(key) ?: continue
                    parseEntryObject(obj, source, fallbackId = key)?.let { list += it }
                }
            }
        }
        return list
    }

    private fun parseEntryObject(
        obj: JSONObject,
        source: RepoSource,
        fallbackId: String? = null
    ): RepoModule? {
        // Detect GitHub Repo / Search item schema
        if (obj.has("html_url") && (obj.has("full_name") || obj.has("stargazers_count"))) {
            val fullName = obj.optString("full_name", obj.optString("name", fallbackId ?: return null))
            val name = obj.optString("name", fullName.substringAfter("/"))
            val owner = obj.optJSONObject("owner")?.optString("login")
                ?: fullName.substringBefore("/", "Community")
            val desc = obj.optString("description", "No description provided.").ifBlank { "No description provided." }
            val stars = obj.optInt("stargazers_count", 0)
            val htmlUrl = obj.optString("html_url", "")
            val defaultBranch = obj.optString("default_branch", "main")
            val topicsArr = obj.optJSONArray("topics")
            val cat = inferCategory(name, desc, topicsArr, source.family)
            val zipDownload = if (htmlUrl.startsWith("https://github.com/")) {
                "$htmlUrl/archive/refs/heads/$defaultBranch.zip"
            } else htmlUrl
            val updatedEpoch = parseIsoOrEpoch(obj.optString("pushed_at", obj.optString("updated_at", "")))
            return RepoModule(
                id = fullName,
                name = name,
                author = owner,
                version = obj.optString("latestRelease", "GitHub Main").ifBlank { "GitHub Main" },
                versionCode = (updatedEpoch / 1000L).coerceAtLeast(1L),
                description = desc,
                category = cat,
                stars = stars,
                updatedAtEpoch = updatedEpoch,
                downloadUrl = zipDownload,
                sourceRepoId = source.id,
                sourceRepoName = source.name,
                family = source.family,
                changelog = "Source: $htmlUrl",
                homepage = htmlUrl
            )
        }

        // LSPosed / Xposed / MMRL v2 / Magisk v1 unified object parser
        val id = obj.optString("id")
            .ifBlank { obj.optString("packageName") }
            .ifBlank { obj.optString("name") }
            .ifBlank { fallbackId ?: return null }

        val displayName = obj.optString("name")
            .ifBlank { obj.optString("title") }
            .ifBlank { obj.optString("description").take(36) }
            .ifBlank { id }

        val author = when {
            obj.optString("author").isNotBlank() -> obj.optString("author")
            obj.optJSONArray("collaborators") != null -> {
                val c = obj.optJSONArray("collaborators")
                if (c != null && c.length() > 0) c.optJSONObject(0)?.optString("login", "Community") ?: "Community" else "Community"
            }
            else -> obj.optString("owner", "Community").ifBlank { "Community" }
        }

        val desc = obj.optString("description")
            .ifBlank { obj.optString("summary") }
            .ifBlank { "Community module from ${source.name}" }

        // Extract latest version from MMRL v2 `versions` array or LSPosed `releases` array
        var version = obj.optString("version").ifBlank { obj.optString("latestRelease", "1.0") }
        var versionCode = obj.optLong("versionCode", 1L)
        var downloadUrl = obj.optString("zipUrl")
            .ifBlank { obj.optString("zip_url") }
            .ifBlank { obj.optString("downloadUrl") }
            .ifBlank { obj.optString("url") }
        var changelog = obj.optString("changelog", "")
        var updatedEpoch = obj.optLong("timestamp", obj.optLong("last_update", 0L))
        if (updatedEpoch in 1L..9_999_999_999L) updatedEpoch *= 1000L
        if (updatedEpoch == 0L) {
            updatedEpoch = parseIsoOrEpoch(obj.optString("updatedAt", obj.optString("latestReleaseTime", "")))
        }

        val versionsArr = obj.optJSONArray("versions")
        if (versionsArr != null && versionsArr.length() > 0) {
            val latest = versionsArr.optJSONObject(versionsArr.length() - 1) ?: versionsArr.optJSONObject(0)
            if (latest != null) {
                version = latest.optString("version", version).ifBlank { version }
                versionCode = latest.optLong("versionCode", versionCode)
                downloadUrl = latest.optString("zipUrl", downloadUrl).ifBlank { downloadUrl }
                changelog = latest.optString("changelog", changelog).ifBlank { changelog }
                val ts = latest.optLong("timestamp", 0L)
                if (ts > 0L) updatedEpoch = if (ts < 9_999_999_999L) ts * 1000L else ts
            }
        }

        val releasesArr = obj.optJSONArray("releases")
        if (releasesArr != null && releasesArr.length() > 0) {
            val latestRel = releasesArr.optJSONObject(0)
            if (latestRel != null) {
                version = latestRel.optString("name", version).ifBlank { version }
                changelog = latestRel.optString("descriptionHTML", latestRel.optString("description", changelog))
                    .replace(Regex("<[^>]*>"), " ")
                    .trim()
                val assets = latestRel.optJSONArray("releaseAssets")
                if (assets != null && assets.length() > 0) {
                    val assetUrl = assets.optJSONObject(0)?.optString("downloadUrl", "").orEmpty()
                    if (assetUrl.startsWith("https://")) downloadUrl = assetUrl
                }
            }
        }

        val trackObj = obj.optJSONObject("track")
        val homepage = obj.optString("homepage")
            .ifBlank { obj.optString("source") }
            .ifBlank { trackObj?.optString("source", "").orEmpty() }
            .ifBlank { obj.optString("url", "") }

        val stars = obj.optInt("stars", obj.optInt("stargazers_count", 0))
        val catsArr = obj.optJSONArray("categories")
        val category = inferCategory(displayName, desc, catsArr, source.family)

        return RepoModule(
            id = id,
            name = displayName,
            author = author,
            version = version.ifBlank { "1.0" },
            versionCode = versionCode,
            description = desc,
            category = category,
            stars = stars,
            updatedAtEpoch = updatedEpoch,
            downloadUrl = downloadUrl.ifBlank { homepage },
            sourceRepoId = source.id,
            sourceRepoName = source.name,
            family = source.family,
            changelog = changelog.ifBlank { "Published via ${source.name}" },
            homepage = homepage
        )
    }

    private fun inferCategory(
        name: String,
        desc: String,
        tags: JSONArray?,
        family: RepoFamily
    ): String {
        if (tags != null && tags.length() > 0) {
            val first = tags.optString(0).trim()
            if (first.isNotBlank()) return first.replaceFirstChar { it.uppercase() }
        }
        val combined = "$name $desc".lowercase()
        return when {
            combined.contains("zygisk") || combined.contains("hide") || combined.contains("integrity") || combined.contains("shamiko") -> "Stealth & Integrity"
            combined.contains("audio") || combined.contains("viper") || combined.contains("dolby") -> "Audio"
            combined.contains("kernel") || combined.contains("zram") || combined.contains("thermal") || combined.contains("kpm") -> "Performance & Kernel"
            combined.contains("one ui") || combined.contains("samsung") || combined.contains("knox") -> "Samsung / One UI"
            combined.contains("ad") || combined.contains("host") || combined.contains("dns") || combined.contains("network") -> "Network & Privacy"
            family == RepoFamily.VECTOR_XPOSED -> "UI & Hooks"
            else -> "System Utility"
        }
    }

    private fun parseIsoOrEpoch(raw: String): Long {
        if (raw.isBlank()) return 0L
        raw.toLongOrNull()?.let { return if (it < 9_999_999_999L) it * 1000L else it }
        return runCatching {
            val datePart = raw.substringBefore("T")
            val parts = datePart.split("-")
            if (parts.size == 3) {
                val y = parts[0].toLong()
                val m = parts[1].toLong()
                val d = parts[2].toLong()
                ((y - 1970L) * 365L + m * 30L + d) * 86_400_000L
            } else 0L
        }.getOrDefault(0L)
    }
}
