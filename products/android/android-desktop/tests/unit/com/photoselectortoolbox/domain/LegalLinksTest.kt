package com.photoselectortoolbox.domain

import android.content.pm.PackageManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegalLinksTest {

    @Test
    fun `privacy policy, impressum and source code are valid https URLs`() {
        assertTrue(LegalLinks.PRIVACY_POLICY.startsWith("https://"))
        assertTrue(LegalLinks.IMPRESSUM.startsWith("https://"))
        assertTrue(LegalLinks.SOURCE_CODE.startsWith("https://"))

        val privacyUrl = URL(LegalLinks.PRIVACY_POLICY)
        val impressumUrl = URL(LegalLinks.IMPRESSUM)
        val sourceUrl = URL(LegalLinks.SOURCE_CODE)

        assertTrue(privacyUrl.host.isNotEmpty())
        assertTrue(impressumUrl.host.isNotEmpty())
        assertTrue(sourceUrl.host.isNotEmpty())
    }

    @Test
    fun `manifest does not request INTERNET permission`() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val permissions = packageInfo.requestedPermissions ?: emptyArray()

        assertFalse(
            "Photo Selector Toolbox must not request INTERNET permission (100% on-device SAF requirement)",
            permissions.contains("android.permission.INTERNET"),
        )
    }

    @Test
    fun `merged manifest requests no permission except the dynamic receiver one`() {
        val context = RuntimeEnvironment.getApplication()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val unexpected = (packageInfo.requestedPermissions ?: emptyArray())
            .filterNot { it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }

        assertTrue(
            "Zero-permission SAF policy (REQ-AND-PLATFORM.08): a dependency merged in $unexpected",
            unexpected.isEmpty(),
        )
    }
}
