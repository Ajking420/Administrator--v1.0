package com.example.core.studio

import android.content.Context
import com.example.core.storage.ExportManager
import com.example.core.storage.ExportOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class StudioTemplate(val label: String, val subtitle: String) {
    BLANK("Blank Module", "Clean Magisk/KSU/APM skeleton"),
    ONE_UI_PORT("One UI Feature Port", "S-Series floating_feature.xml overlay for SM-E156B"),
    DIMENSITY_ZRAM("Dimensity 6100+ ZRAM/Kernel", "mt6835 4 GB RAM ZRAM zstd + scheduler service.sh"),
    SELINUX_PATCH("SELinux Policy Patch", "Pre-configured sepolicy.rule + post-fs-data.sh")
}

data class ModuleOverlayEntry(
    val targetSystemPath: String,
    val content: String
)

data class ModuleStudioDraft(
    val id: String = "f15_custom_mod",
    val name: String = "Galaxy F15 Custom Module",
    val version: String = "v1.0.0",
    val versionCode: Int = 100,
    val author: String = "Administrator",
    val description: String = "Engineered for Samsung Galaxy F15 5G (SM-E156B / mt6835).",
    val preInitMount: Boolean = false,
    val systemProp: String = "# Systemless props (resetprop)\n",
    val sepolicyRule: String = "# Custom SELinux policy rules\n",
    val postFsDataSh: String = "#!/system/bin/sh\nMODDIR=${'$'}{0%/*}\n",
    val serviceSh: String = "#!/system/bin/sh\nMODDIR=${'$'}{0%/*}\n",
    val actionSh: String = "#!/system/bin/sh\necho \"Administrator module action executed\"\n",
    val customizeSh: String = "ui_print \"- Installing module for SM-E156B (mt6835)...\"\nset_perm_recursive \"${'$'}MODPATH\" 0 0 0755 0644\n",
    val overlays: List<ModuleOverlayEntry> = emptyList()
)

/**
 * Magisk / KSU / APM Module Studio Engine with real `ZipOutputStream` packaging
 * (`META-INF/com/google/android/update-binary`, `updater-script`, Unix-LF scripts, and overlays)
 * exported directly to `/storage/emulated/0/Download/Administrator/Studio/`.
 */
object ModuleStudioEngine {

    fun createDraftFromTemplate(template: StudioTemplate): ModuleStudioDraft {
        return when (template) {
            StudioTemplate.BLANK -> ModuleStudioDraft()
            StudioTemplate.ONE_UI_PORT -> ModuleStudioDraft(
                id = "e15x_oneui_s_features",
                name = "One UI S-Series Floating Features (F15)",
                version = "v1.2.0",
                versionCode = 120,
                description = "Unlocks S24/S25 One UI camera/gallery/blur flags on SM-E156B.",
                preInitMount = true,
                systemProp = "ro.config.dha_cached_max=12\npersist.sys.sf.hs_mode=1\n",
                overlays = listOf(
                    ModuleOverlayEntry(
                        targetSystemPath = "system/etc/floating_feature.xml",
                        content = "<!-- SM-E156B S-Series Floating Feature Overlay -->\n<SecFloatingFeatureSet>\n  <SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_3D_SURFACE_TRANSITION_FLAG>TRUE</SEC_FLOATING_FEATURE_GRAPHICS_SUPPORT_3D_SURFACE_TRANSITION_FLAG>\n  <SEC_FLOATING_FEATURE_COMMON_SUPPORT_AI_AGENT>TRUE</SEC_FLOATING_FEATURE_COMMON_SUPPORT_AI_AGENT>\n</SecFloatingFeatureSet>\n"
                    )
                )
            )
            StudioTemplate.DIMENSITY_ZRAM -> ModuleStudioDraft(
                id = "mt6835_zram_tuner",
                name = "Dimensity 6100+ ZRAM & Kernel Tweak",
                version = "v1.0.4",
                versionCode = 104,
                description = "4 GB RAM ZRAM zstd + policy6/policy0 scheduler tuning for SM-E156B.",
                serviceSh = """
                    #!/system/bin/sh
                    while [ "$(getprop sys.boot_completed)" != "1" ]; do sleep 2; done
                    swapoff /dev/block/zram0 2>/dev/null
                    echo 1 > /sys/block/zram0/reset
                    echo zstd > /sys/block/zram0/comp_algorithm
                    echo 4294967296 > /sys/block/zram0/disksize
                    mkswap /dev/block/zram0 && swapon /dev/block/zram0
                    echo 60 > /proc/sys/vm/swappiness
                """.trimIndent() + "\n"
            )
            StudioTemplate.SELINUX_PATCH -> ModuleStudioDraft(
                id = "e15x_sepolicy_patch",
                name = "SM-E156B SELinux Policy Patch",
                version = "v1.0.0",
                versionCode = 100,
                description = "Custom sepolicy.rule fixes generated from Crash Analyzer AVC logs.",
                sepolicyRule = "# Paste or import AVC rules from Crash Analyzer\nallow magisk sysfs_zram file { read write open }\n"
            )
        }
    }

    suspend fun buildModuleZip(
        context: Context,
        draft: ModuleStudioDraft,
        simulationMode: Boolean
    ): ExportOutcome = withContext(Dispatchers.IO) {
        val safeId = draft.id.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "admin_module" }

        val moduleProp = buildString {
            appendLine("id=$safeId")
            appendLine("name=${draft.name.trim()}")
            appendLine("version=${draft.version.trim()}")
            appendLine("versionCode=${draft.versionCode}")
            appendLine("author=${draft.author.trim()}")
            appendLine("description=${draft.description.trim()}")
            if (draft.preInitMount) appendLine("preinit=1")
        }

        val updateBinary = """
            #!/sbin/sh
            umask 022
            ui_print() { echo "${'$'}1"; }
            OUTFD=${'$'}2
            ZIPFILE=${'$'}3
            mount /data 2>/dev/null
            [ -f /data/adb/magisk/util_functions.sh ] && . /data/adb/magisk/util_functions.sh
            install_module
            exit 0
        """.trimIndent() + "\n"

        val zipBytes = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                fun putUnixText(entryPath: String, content: String) {
                    val cleanPath = entryPath.trimStart('/')
                    if (cleanPath.contains("..")) return
                    zos.putNextEntry(ZipEntry(cleanPath))
                    zos.write(content.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }

                putUnixText("META-INF/com/google/android/update-binary", updateBinary)
                putUnixText("META-INF/com/google/android/updater-script", "#MAGISK\n")
                putUnixText("module.prop", moduleProp)
                putUnixText("customize.sh", draft.customizeSh)
                if (draft.systemProp.isNotBlank()) putUnixText("system.prop", draft.systemProp)
                if (draft.sepolicyRule.isNotBlank()) putUnixText("sepolicy.rule", draft.sepolicyRule)
                if (draft.postFsDataSh.isNotBlank()) putUnixText("post-fs-data.sh", draft.postFsDataSh)
                if (draft.serviceSh.isNotBlank()) putUnixText("service.sh", draft.serviceSh)
                if (draft.actionSh.isNotBlank()) putUnixText("action.sh", draft.actionSh)

                for (overlay in draft.overlays) {
                    if (overlay.targetSystemPath.isNotBlank()) {
                        putUnixText(overlay.targetSystemPath, overlay.content)
                    }
                }
            }
            baos.toByteArray()
        }

        ExportManager.exportBytes(
            context = context,
            subfolder = "Studio",
            fileName = "${safeId}_${draft.version.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.zip",
            bytes = zipBytes,
            mimeType = "application/zip",
            simulationMode = simulationMode
        )
    }
}
