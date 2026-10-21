package com.phototok.domain

import android.content.pm.PackageManager
import com.photoselector.core.Requirement
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegalLinksTest {

    @Test
    @Requirement("REQ-TOK-ARCH.20")
    fun `no web address is hard-coded outside LegalLinks (Q-TOK-1)`() {
        val src = listOf(File("src"), File("products/android/phototok/src")).first { it.isDirectory }
        val offenders = src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name != "LegalLinks.kt" }
            .flatMap { f ->
                f.readLines().filter { it.contains("https://") && !it.contains("xmlns") && !it.contains("ns.adobe.com") }
                    .map { "${f.name}: ${it.trim()}" }
            }.toList()
        assertTrue("URLs belong in LegalLinks.kt: $offenders", offenders.isEmpty())
    }

    @Test
    @Requirement("REQ-TOK-ARCH.20")
    fun `privacy policy and impressum are valid https URLs`() {
        assertTrue(LegalLinks.PRIVACY_POLICY.startsWith("https://"))
        assertTrue(LegalLinks.IMPRESSUM.startsWith("https://"))

        // Should parse without malformed URL exception
        val privacyUrl = URL(LegalLinks.PRIVACY_POLICY)
        val impressumUrl = URL(LegalLinks.IMPRESSUM)

        assertTrue(privacyUrl.host.isNotEmpty())
        assertTrue(impressumUrl.host.isNotEmpty())
    }

    @Test
    @Requirement("REQ-TOK-ARCH.19")
    fun `manifest does not request INTERNET permission`() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = packageInfo.requestedPermissions ?: emptyArray()

        assertFalse(
            "PhotoTok must not request INTERNET permission (SAF-only requirement)",
            permissions.contains("android.permission.INTERNET"),
        )
    }

    @Test
    @Requirement("REQ-TOK-ARCH.19")
    fun `merged manifest requests no permission at all`() {
        // A dependency can merge permissions into the shipped manifest (Frameduel got four from an
        // unused WorkManager, audit 2026-10-21); only androidx's own signature permission is allowed.
        val context = RuntimeEnvironment.getApplication()
        val requested = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions ?: emptyArray()
        val unexpected = requested.filterNot { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }
        assertTrue("Zero-permission SAF policy: the merged manifest requests $unexpected", unexpected.isEmpty())
    }
}
