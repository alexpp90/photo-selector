package com.phototok.data.repository

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.photoselector.core.Requirement
import com.photoselector.core.storage.SourceKind
import com.phototok.R
import com.phototok.domain.SwipeAction
import com.phototok.domain.SwipeRightVerb
import com.phototok.domain.UiText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Writes that belong to one source kind stay with it: Confirm delete per source
 * (REQ-TOK-GESTURE.03), the Pro-off message for the source that is open (REQ-TOK-FEATURE.20),
 * and cloud's "inherits phone" key, which no screen writes but every reader still honours
 * (REQ-TOK-FEATURE.13, REQ-TOK-FEATURE.22).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PerSourceSettingsWritesTest {

    private lateinit var context: Context
    private lateinit var repository: SettingsRepository

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        runBlocking { context.dataStore.edit { it.clear() } }
        repository = SettingsRepository(context)
    }

    private suspend fun confirm(kind: SourceKind) = repository.sourceSettingsFlow(kind).first().directDeleteConfirmEnabled

    @Test
    @Requirement("REQ-TOK-GESTURE.03")
    fun `Confirm delete set on an SD card stays with the card, and the phone's stays with the phone`() = runTest {
        repository.setSourceDirectDeleteConfirmEnabled(SourceKind.REMOVABLE, false)

        assertFalse("the card reads back its own choice", confirm(SourceKind.REMOVABLE))
        assertTrue("the phone is unchanged", confirm(SourceKind.PHONE_LIBRARY))
        assertTrue("the phone slot is unchanged", repository.phoneDirectDeleteConfirmEnabled.first())
        assertTrue("the camera is unchanged", confirm(SourceKind.CAMERA_MTP))

        repository.setPhoneDirectDeleteConfirmEnabled(false)
        repository.setSourceDirectDeleteConfirmEnabled(SourceKind.REMOVABLE, true)

        assertFalse(confirm(SourceKind.PHONE_LIBRARY))
        assertFalse("cloud follows the phone", confirm(SourceKind.CLOUD_OTHER))
        assertTrue(confirm(SourceKind.REMOVABLE))
        assertTrue("a camera without a choice of its own keeps asking", confirm(SourceKind.CAMERA_MTP))
    }

    @Test
    @Requirement("REQ-TOK-GESTURE.03", "REQ-TOK-FEATURE.22")
    fun `an install that saved Don't ask before the per-source keys keeps it on every source`() = runTest {
        context.dataStore.edit { it[booleanPreferencesKey("direct_delete_confirm")] = false }

        SourceKind.entries.forEach { assertFalse("$it", confirm(it)) }
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.20")
    fun `Pro off says left deletes when the open card's left swipe moved, and the next delete asks once`() = runTest {
        repository.setProMode(true)
        repository.setSourceLeftSwipeAction(SourceKind.REMOVABLE, SwipeAction.MOVE)
        repository.setSourceDirectDeleteConfirmEnabled(SourceKind.REMOVABLE, false)

        val message = repository.setProMode(false, SourceKind.REMOVABLE)

        assertEquals(UiText.of(R.string.snackbar_pro_off_left_delete), message)
        assertTrue(repository.shouldAskDeleteOnce())
        assertEquals("the stored verb is gated, not erased", SwipeAction.DELETE, repository.sourceSettingsFlow(SourceKind.REMOVABLE).first().swipeLeftVerb)
        repository.setProMode(true)
        assertFalse("Pro on again: no extra question", repository.shouldAskDeleteOnce())
        assertEquals(SwipeAction.MOVE, repository.sourceSettingsFlow(SourceKind.REMOVABLE).first().swipeLeftVerb)
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.20")
    fun `Pro off names the new swipe right, and says only paused when no swipe changes`() = runTest {
        repository.setProMode(true)
        repository.setSourceSwipeRightVerb(SourceKind.PHONE_LIBRARY, SwipeRightVerb.COPY)
        assertEquals(UiText.of(R.string.snackbar_pro_off_right_favourite), repository.setProMode(false, SourceKind.PHONE_LIBRARY))
        assertFalse("a left swipe that already deleted asks as before", repository.shouldAskDeleteOnce())

        repository.setProMode(true)
        assertEquals(UiText.of(R.string.snackbar_pro_off_right_phone), repository.setProMode(false, SourceKind.CAMERA_MTP))

        repository.setProMode(true)
        repository.setSourceSwipeRightVerb(SourceKind.PHONE_LIBRARY, SwipeRightVerb.FAVOURITE)
        assertEquals(UiText.of(R.string.snackbar_pro_paused), repository.setProMode(false, SourceKind.PHONE_LIBRARY))
        repository.setProMode(true)
        assertEquals("no source known", UiText.of(R.string.snackbar_pro_paused), repository.setProMode(false, null))
    }

    @Test
    @Requirement("REQ-TOK-FEATURE.13", "REQ-TOK-FEATURE.22")
    fun `cloud inherits phone has no setter, and a stored opt-out is still read`() = runTest {
        assertTrue(
            "nothing writes cloud_inherits_phone any more",
            SettingsRepository::class.java.methods.none { it.name.startsWith("setCloudInheritsPhone") },
        )
        repository.setProMode(true)
        context.dataStore.edit {
            it[booleanPreferencesKey("cloud_inherits_phone")] = false
            it[stringPreferencesKey("source_cloud_swipe_left_verb")] = SwipeAction.COPY.key
        }
        assertEquals(SwipeAction.COPY, repository.sourceSettingsFlow(SourceKind.CLOUD_OTHER).first().swipeLeftVerb)

        context.dataStore.edit { it.remove(booleanPreferencesKey("cloud_inherits_phone")) }
        assertEquals("cloud follows the phone by default", SwipeAction.DELETE, repository.sourceSettingsFlow(SourceKind.CLOUD_OTHER).first().swipeLeftVerb)
    }
}
