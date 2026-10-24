package com.phototok.data.source

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real MediaStore side of system favourites (`U-17`) on a device, with Jackpix's zero
 * permissions:
 * - a photo in a SAF tree Jackpix holds a persisted grant for resolves to its MediaStore item,
 *   and the read grant MediaProvider copies onto it lets Jackpix read `IS_FAVORITE` back;
 * - a favourite request launched as an `IntentSender` sets and clears the flag without a dialog;
 * - MediaProvider refuses the `.../file/<id>` URI `getMediaUri` returns, so the gateway converts it.
 *
 * Needs Android 11. The tree test needs a persisted grant on a primary-storage folder that allows
 * writes (pick any folder once in the app, e.g. the lab's `PSTLab/work`); without one it is
 * skipped, not passed. It writes one small photo into that folder and deletes it afterwards.
 */
@RunWith(AndroidJUnit4::class)
class MediaStoreFavouritesTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val cleanup = mutableListOf<() -> Unit>()

    @Before
    fun requireAndroid11() {
        assumeTrue("IS_FAVORITE and createFavoriteRequest need Android 11", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
    }

    @After
    fun cleanUp() {
        cleanup.reversed().forEach { runCatching(it) }
    }

    private fun jpegBytes(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 80, out)
        return out.toByteArray()
    }

    private fun testName() = "jackpix_fav_test_${SystemClock.uptimeMillis()}.jpg"

    private fun launch(sender: IntentSender) {
        context.startIntentSender(sender, null, Intent.FLAG_ACTIVITY_NEW_TASK, Intent.FLAG_ACTIVITY_NEW_TASK, 0)
    }

    /** Poll until [probe] returns [expected]; MediaProvider runs requests and scans asynchronously. */
    private fun <T> await(what: String, expected: T, probe: () -> T) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        var last = probe()
        while (last != expected && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(200)
            last = probe()
        }
        if (last != expected) fail("$what stayed $last, expected $expected")
    }

    @Test
    fun aTreeDocumentResolvesAndItsFlagIsSetClearedAndReadBack() {
        val tree = context.contentResolver.persistedUriPermissions.firstOrNull {
            it.isWritePermission && it.uri.authority == "com.android.externalstorage.documents" &&
                DocumentsContract.getTreeDocumentId(it.uri).startsWith("primary:")
        }?.uri
        assumeTrue("no persisted, writable primary-storage tree grant: pick a folder in the app once", tree != null)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val document = DocumentsContract.createDocument(context.contentResolver, parent, "image/jpeg", testName())
        assertNotNull("could not create a photo in $tree", document)
        cleanup += { DocumentsContract.deleteDocument(context.contentResolver, document!!) }
        context.contentResolver.openOutputStream(document!!)!!.use { it.write(jpegBytes()) }

        val gateway = MediaStoreFavouritesImpl(context)
        assertTrue(gateway.canResolve(document.toString()))
        // MediaProvider scans the new file after it is closed.
        await("resolution of $document", true) { gateway.resolve(listOf(document.toString())).isNotEmpty() }
        val resolved = gateway.resolve(listOf(document.toString())).getValue(document.toString())
        assertTrue(resolved.mediaUri, resolved.mediaUri.startsWith("content://media/external/images/media/"))
        assertEquals(false, resolved.isFavourite)

        launch(gateway.favoriteRequest(listOf(resolved.mediaUri), favourite = true))
        await("IS_FAVORITE after the request", true) { gateway.readFlags(listOf(resolved.mediaUri))[resolved.mediaUri] }

        // A second process-wide resolve reads the flag back, as a later folder load does.
        assertEquals(true, MediaStoreFavouritesImpl(context).resolve(listOf(document.toString()))[document.toString()]?.isFavourite)

        launch(gateway.favoriteRequest(listOf(resolved.mediaUri), favourite = false))
        await("IS_FAVORITE after the clear", false) { gateway.readFlags(listOf(resolved.mediaUri))[resolved.mediaUri] }
    }

    @Test
    fun aFavouriteRequestForOwnMediaNeedsNoDialog() {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, testName())
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/")
        }
        val media = context.contentResolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        assertNotNull(media)
        cleanup += { context.contentResolver.delete(media!!, null, null) }
        context.contentResolver.openOutputStream(media!!)!!.use { it.write(jpegBytes()) }
        val requestUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(media)).toString()

        val gateway = MediaStoreFavouritesImpl(context)
        launch(gateway.favoriteRequest(listOf(requestUri), favourite = true))
        await("IS_FAVORITE of own media", true) { gateway.readFlags(listOf(requestUri))[requestUri] }
    }

    @Test
    fun aFilesTableUriIsRefusedForRequestsSoTheGatewayConvertsIt() {
        val filesUri = ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL), 1L)
        try {
            MediaStore.createFavoriteRequest(context.contentResolver, listOf(filesUri), true)
            fail("MediaProvider accepted a .../file/<id> URI for a favourite request")
        } catch (expected: IllegalArgumentException) {
            // "All requested items must be Media items": why MediaStoreFavouritesImpl.requestUriFor exists.
        }
    }

    @Test
    fun documentsOfOtherProvidersStayJackpixOnly() {
        val gateway = MediaStoreFavouritesImpl(context)
        val cloud = "content://com.google.android.apps.docs.storage/document/acc%3D1%3Bdoc%3Dabc"
        val downloads = "content://com.android.providers.downloads.documents/document/msf%3A12"
        val mtp = "content://com.android.mtp.documents/document/7"
        for (uri in listOf(cloud, downloads, mtp)) assertFalse(uri, gateway.canResolve(uri))
        assertTrue(gateway.resolve(listOf(cloud, downloads, mtp)).isEmpty())
    }

    @Test
    fun requestUrisNameTheImageOrVideoCollection() {
        assertEquals(
            "content://media/external/images/media/42",
            MediaStoreFavouritesImpl.requestUriFor(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE, 42),
        )
        assertEquals(
            "content://media/external/video/media/42",
            MediaStoreFavouritesImpl.requestUriFor(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, 42),
        )
        assertEquals(null, MediaStoreFavouritesImpl.requestUriFor(MediaStore.Files.FileColumns.MEDIA_TYPE_NONE, 42))
    }
}
