package com.photoselectortoolbox.data.repository

import android.content.Context
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.photoselectortoolbox.domain.session.SelectorSortOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The persisted selector sort order (`selector_sort_order`, `[USER 2026-11-02]`).
 *
 * The read rule is checked on [SettingsRepository.selectorSortOrderOf] with
 * hand-built [androidx.datastore.preferences.core.Preferences], because the
 * DataStore is private to the repository and process-wide: a test cannot put an
 * unknown string into it, nor be sure no earlier test class has written the
 * key. The round trip through the real DataStore is checked separately.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SelectorSortOrderSettingTest {

    /** The stored key name is part of the contract: renaming it resets every user. */
    private val key = stringPreferencesKey("selector_sort_order")

    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() {
        val context: Context = RuntimeEnvironment.getApplication()
        settings = SettingsRepository(context)
    }

    @Test
    fun `no stored value reads as newest first`() {
        assertEquals(SelectorSortOrder.NEWEST_FIRST, SettingsRepository.selectorSortOrderOf(preferencesOf()))
    }

    @Test
    fun `every order reads back by its name`() {
        SelectorSortOrder.entries.forEach { order ->
            assertEquals(order, SettingsRepository.selectorSortOrderOf(preferencesOf(key to order.name)))
        }
    }

    @Test
    fun `an unknown stored value reads as the default`() {
        listOf("", "WORST_FIRST", "newest_first", "BY_DATE").forEach { stored ->
            assertEquals(
                stored,
                SettingsRepository.DEFAULT_SELECTOR_SORT_ORDER,
                SettingsRepository.selectorSortOrderOf(preferencesOf(key to stored)),
            )
        }
    }

    @Test
    fun `the setter persists and the flow emits it`() = runTest {
        try {
            SelectorSortOrder.entries.forEach { order ->
                settings.setSelectorSortOrder(order)
                assertEquals(order, settings.selectorSortOrder.first())
            }
        } finally {
            // The DataStore outlives this test class; leave the default behind.
            settings.setSelectorSortOrder(SettingsRepository.DEFAULT_SELECTOR_SORT_ORDER)
        }
    }
}
