package com.example

import com.example.core.diagnostics.CrashAnalyzerEngine
import com.example.core.editor.ImageEditorEngine
import com.example.core.repos.RepoFamily
import com.example.core.repos.RepoSource
import com.example.core.repos.RepositoryManager
import com.example.core.shell.RootShellExecutor
import com.example.core.vault.EfsVaultEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdministratorCoreEnginesTest {

    @Test
    fun `efs vault PBKDF2 AES-GCM encrypt decrypt roundtrip succeeds`() {
        val sample = "SM-E156B-EFS-NVRAM-PAYLOAD-12345".toByteArray()
        val encrypted = EfsVaultEngine.encryptPayload(sample, "StrongVaultKey!2026")
        assertFalse(sample.contentEquals(encrypted))
        val decrypted = EfsVaultEngine.decryptPayload(encrypted, "StrongVaultKey!2026")
        assertArrayEquals(sample, decrypted)
    }

    @Test
    fun `vbmeta patcher sets offset 0x7B flags accurately`() {
        val patchedBoth = ImageEditorEngine.patchVbmetaHeaderBytes(null, disableVerity = true, disableVerification = true)
        assertEquals(0x03.toByte(), patchedBoth[0x7B])
        val patchedVerityOnly = ImageEditorEngine.patchVbmetaHeaderBytes(null, disableVerity = true, disableVerification = false)
        assertEquals(0x01.toByte(), patchedVerityOnly[0x7B])
    }

    @Test
    fun `crash analyzer converts avc denied lines into sepolicy rules`() {
        val log = listOf(
            """audit: avc: denied { read write } for pid=12 comm="magiskd" scontext=u:r:magisk:s0 tcontext=u:object_r:sysfs_zram:s0 tclass=file permissive=0"""
        )
        val rules = CrashAnalyzerEngine.convertAvcLinesToSepolicyRules(log)
        assertTrue(rules.contains("allow magisk sysfs_zram file { read write }"))
    }

    @Test
    fun `repository manager parses MMRL v2 and GitHub schemas`() {
        val src = RepoSource("test", "Test Repo", "https://example.com/modules.json", RepoFamily.MAGISK_KSU_APATCH)
        val mmrlJson = """{"modules":[{"id":"rezygisk","name":"ReZygisk","author":"PerformanC","description":"Zygisk","versions":[{"version":"v1.0","versionCode":100,"zipUrl":"https://example.com/a.zip"}]}]}"""
        val parsed = RepositoryManager.parsePayload(mmrlJson, src)
        assertEquals(1, parsed.size)
        assertEquals("rezygisk", parsed.first().id)
        assertEquals("https://example.com/a.zip", parsed.first().downloadUrl)
    }

    @Test
    fun `shell validation blocks unsafe partition names and invalid chmod`() {
        assertTrue(RootShellExecutor.isValidPartitionName("init_boot"))
        assertTrue(RootShellExecutor.isValidPartitionName("sec_efs"))
        assertFalse(RootShellExecutor.isValidPartitionName("boot; rm -rf /"))
        assertTrue(RootShellExecutor.isValidChmodMode("0755"))
        assertFalse(RootShellExecutor.isValidChmodMode("777; id"))
    }
}
