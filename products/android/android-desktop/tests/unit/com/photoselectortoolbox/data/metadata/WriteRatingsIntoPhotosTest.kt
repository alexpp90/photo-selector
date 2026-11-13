package com.photoselectortoolbox.data.metadata

import android.graphics.Bitmap
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.exifinterface.media.ExifInterface
import com.photoselector.core.Requirement
import com.photoselector.saf.FolderAccessManager
import com.photoselector.saf.SafGrantBackend
import com.photoselectortoolbox.data.repository.SettingsRepository
import com.photoselectortoolbox.data.repository.dataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * *Write ratings into photos* (`[USER 2026-11-12]` decision #16, REQ-AND-RATING.01): the writer
 * the app is built with (Hilt's constructor, reading [SettingsRepository]) embeds a rating in a
 * JPEG only while the setting is on. Off — the fresh-install default — the photo is not touched
 * and the rating stays in the app; RAW and TIFF sidecars are written either way, because they
 * never change the photo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@Requirement("REQ-AND-RATING.01")
class WriteRatingsIntoPhotosTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private object NoGrants : SafGrantBackend {
        override fun takePersistableGrant(uri: Uri, modeFlags: Int) = Unit
        override fun releasePersistableGrant(uri: Uri, modeFlags: Int) = Unit
        override fun persistedGrantUris(): Set<String> = emptySet()
    }

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var settings: SettingsRepository
    private lateinit var writer: MetadataWriterImpl

    @Before
    fun setUp() {
        // Process-wide DataStore singleton (agent rule, 2026-10-17).
        runBlocking { context.dataStore.edit { it.clear() } }
        settings = SettingsRepository(context, FolderAccessManager(NoGrants))
        writer = MetadataWriterImpl(Dispatchers.IO, settings)
    }

    @After
    fun tearDown() {
        runBlocking { context.dataStore.edit { it.clear() } }
    }

    private fun jpeg(name: String): File = File(tmp.root, name).also { f ->
        FileOutputStream(f).use { Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 90, it) }
    }

    @Test
    fun `off by default, a rated JPEG is left byte for byte as it was`() = runTest {
        assertFalse(SettingsRepository.DEFAULT_WRITE_RATINGS_INTO_PHOTOS)
        assertFalse(settings.writeRatingsIntoPhotos.first())
        val photo = jpeg("shot.jpg")
        val before = photo.readBytes()

        val result = writer.writeRating(photo, 4)

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue("the original must not change", before.contentEquals(photo.readBytes()))
        assertEquals(listOf("shot.jpg"), tmp.root.list()!!.toList())
    }

    @Test
    fun `on, the rating is embedded in the JPEG`() = runTest {
        settings.setWriteRatingsIntoPhotos(true)
        val photo = jpeg("shot.jpg")

        val result = writer.writeRating(photo, 4)

        assertTrue(result.exceptionOrNull()?.message, result.isSuccess)
        assertTrue(ExifInterface(photo.absolutePath).getAttribute(ExifInterface.TAG_XMP)!!.contains("<xmp:Rating>4</xmp:Rating>"))
    }

    @Test
    fun `a RAW gets its sidecar whatever the setting, and the RAW is never changed`() = runTest {
        val raw = File(tmp.root, "DSC_1.ARW").apply { writeBytes(byteArrayOf(0x49, 0x49, 0x2A, 0x00)) }
        val before = raw.readBytes()

        assertTrue(writer.writeRating(raw, 3).isSuccess)

        assertTrue(File(tmp.root, "DSC_1.ARW.xmp").readText().contains("<xmp:Rating>3</xmp:Rating>"))
        assertTrue(before.contentEquals(raw.readBytes()))
    }
}
