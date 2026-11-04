package com.phototok.data.source

import com.photoselector.core.storage.ExternalStorageDetector
import com.photoselector.core.storage.ExternalVolume
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VolumeMonitorTest {

    private val card = ExternalVolume("SD", "/storage/1A2B-3C4D", isRemovable = true, isPrimary = false, uuid = "1A2B-3C4D")

    @Test
    fun `two refreshes racing over one unmount announce it once`() = runTest(UnconfinedTestDispatcher()) {
        val calls = AtomicInteger()
        val slowDetectionStarted = CountDownLatch(1)
        val releaseSlowDetection = CountDownLatch(1)
        val detector = mockk<ExternalStorageDetector> {
            every { detectRemovableVolumes() } answers {
                when (calls.incrementAndGet()) {
                    1 -> listOf(card) // the monitor's first look: the card is there
                    2 -> {
                        // A detection that started before the card was pulled and finishes last.
                        slowDetectionStarted.countDown()
                        assertTrue(releaseSlowDetection.await(5, TimeUnit.SECONDS))
                        emptyList()
                    }
                    else -> emptyList() // the faster refresh: the card is gone
                }
            }
        }
        val monitor = VolumeMonitor(null, detector)
        val events = mutableListOf<String?>()
        val collector = launch { monitor.unmountEvents.collect { events += it } }

        val slow = thread { monitor.refreshVolumes() }
        assertTrue(slowDetectionStarted.await(5, TimeUnit.SECONDS))
        monitor.refreshVolumes() // sees the card gone and announces it
        releaseSlowDetection.countDown()
        slow.join()

        collector.cancel()
        assertEquals(listOf<String?>("1A2B-3C4D"), events)
        assertEquals(emptyList<ExternalVolume>(), monitor.mountedVolumes.value)
    }
}
