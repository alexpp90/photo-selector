package com.phototok.ui.phonemode

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.photoselector.core.Requirement
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.data.repository.FakeMediaStoreTrash
import com.phototok.data.repository.StatsRepository
import com.phototok.data.repository.SystemTrashRepository
import com.phototok.data.repository.SystemTrashRequest
import com.phototok.domain.TrashItem
import com.phototok.ui.theme.PhotoTokTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Activity's side of the system bin ([USER 2026-11-12] decision #13): a request finalised while
 * no Activity showed is launched once one does, once; a decline shows "N not deleted" with Retry.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "de-w393dp-h851dp-xxhdpi")
@Requirement("REQ-TOK-GESTURE.03")
class SystemTrashHostTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val doc = "content://com.android.externalstorage.documents/tree/primary%3ADCIM/document/primary%3ADCIM%2FIMG_1.jpg"
    private val media = "content://media/external/images/media/7"
    private val gateway = FakeMediaStoreTrash(mapOf(doc to media))
    private val repo = SystemTrashRepository(gateway, StatsRepository(), Dispatchers.Unconfined)
    private val launched = mutableListOf<SystemTrashRequest>()

    private fun host() {
        rule.setContent {
            PhotoTokTheme {
                val state by repo.state.collectAsState()
                val hostState = remember { SnackbarHostState() }
                Box(Modifier.fillMaxSize()) {
                    SystemTrashRequests(
                        request = state.request,
                        notice = state.notice,
                        hostState = hostState,
                        onLaunched = repo::onLaunched,
                        launch = { launched += it },
                        onResult = { runBlocking { repo.onResult(it) } },
                        onRetry = repo::retry,
                        onNoticeShown = repo::clearNotice,
                    )
                    FeedSnackbarHost(hostState = hostState, bottomBarHeight = 0.dp)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun aBatchFinalisedWithoutAnActivityIsLaunchedOnceOneShows() {
        runBlocking { assertEquals(1, repo.binTargets(listOf(doc), SourceKind.PHONE_LIBRARY).size) }
        // Finalised on the application scope after the ViewModel was gone: no host yet.
        repo.submit(listOf(TrashItem(doc, media, 1)))
        host()
        assertEquals("launched as soon as a host collects it", 1, launched.size)
        rule.waitForIdle()
        assertEquals("not again on recomposition", 1, launched.size)
    }

    @Test
    fun aDeclineSaysHowManyStayAndRetryAsksAgainForTheSameFiles() {
        repo.submit(listOf(TrashItem(doc, media, 1)))
        host()
        runBlocking { repo.onResult(accepted = false) }
        rule.waitForIdle()
        val ctx = rule.activity
        rule.onAllNodesWithText(ctx.getString(R.string.snackbar_not_deleted, 1)).onFirst().assertExists()
        rule.onNodeWithTag("feed_snackbar_action").assertHeightIsAtLeast(48.dp).performClick()
        rule.waitForIdle()
        assertEquals("Retry asks for the same file", listOf(listOf(media), listOf(media)), gateway.requests)
        assertEquals(2, launched.size)
    }
}
