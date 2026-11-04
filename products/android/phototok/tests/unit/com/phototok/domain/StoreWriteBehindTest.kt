package com.phototok.domain

import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.ImageItem
import com.phototok.data.repository.StatsStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A swipe marks a photo seen and counts it as reviewed; neither may wait for the disk on the
 * thread that swiped. The stores answer from memory and hand the file write to their executor
 * (a recording one here, one background thread in the app).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StoreWriteBehindTest {

    private class Recording : Executor {
        val queued = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) {
            queued.addLast(command)
        }
        fun runAll() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private fun image(name: String) = ImageItem(
        uri = "content://tree/photos/$name",
        fileName = name,
        fileSize = 10,
        lastModified = 5,
        mimeType = "image/jpeg",
    )

    @Test
    fun `marking a photo seen answers at once and writes the log later`() {
        val dir = Files.createTempDirectory("seen_wb").toFile()
        try {
            val log = File(dir, "seen_photos.log")
            val executor = Recording()
            val store = SeenStore(logFile = log, ioExecutor = executor)
            val photo = image("a.jpg")

            assertTrue(store.markSeen(photo, "content://tree/photos"))

            assertTrue("seen is known in memory immediately", store.isSeen(photo, "content://tree/photos"))
            assertFalse("the calling thread did not touch the file", log.exists())
            executor.runAll()
            assertEquals(1, log.readLines().size)
            assertTrue(SeenStore(logFile = log).isSeen(photo, "content://tree/photos"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stats count a reviewed photo at once and persist later through a temporary file`() {
        val dir = Files.createTempDirectory("stats_wb").toFile()
        try {
            val file = File(dir, "culling_stats.json")
            val executor = Recording()
            val store = StatsStore(statsFile = file, ioExecutor = executor)

            store.recordPhotoReviewed(SourceKind.PHONE_LIBRARY, celebrationsEnabled = false)

            assertEquals(1, store.stats.value.lifetimePhotosReviewedPhone)
            assertFalse("the calling thread did not touch the file", file.exists())
            executor.runAll()
            assertTrue(file.exists())
            assertFalse("no temporary file is left behind", File(dir, "culling_stats.json.tmp").exists())
            assertEquals(1, StatsStore(statsFile = file).stats.value.lifetimePhotosReviewedPhone)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a failed stats write is reported, not swallowed`() {
        val dir = Files.createTempDirectory("stats_err").toFile()
        try {
            // The parent "directory" is a regular file, so the write cannot succeed.
            val blocker = File(dir, "blocker").also { it.writeText("x") }
            val errors = mutableListOf<String>()
            val store = StatsStore(
                statsFile = File(blocker, "culling_stats.json"),
                onError = { message, _ -> errors += message },
            )

            store.recordKeeper(SourceKind.PHONE_LIBRARY)

            assertEquals(1, errors.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
