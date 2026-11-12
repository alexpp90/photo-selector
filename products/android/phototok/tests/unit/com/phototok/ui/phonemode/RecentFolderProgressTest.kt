package com.phototok.ui.phonemode

import com.photoselector.core.Requirement
import com.phototok.R
import com.phototok.de
import com.phototok.domain.UiText
import com.phototok.en
import com.phototok.knownDefect
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-TOK-026: the recent-folder card said "4 / 15" for a folder that then opened on photo 15 of 15.
 * The card counts photos *seen* (REQ-TOK-FEATURE.25, "seen / total seen"), but it uses the very
 * shape of the viewer's position counter ("n / m"), so it reads as a position. The two must not be
 * mistakable, in English and German.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentFolderProgressTest {

    @Test
    @Requirement("REQ-TOK-FEATURE.25")
    fun seenProgressDoesNotReadLikeAPosition() {
        val seen = UiText.of(R.string.label_seen_progress, 4, 15)
        val position = UiText.of(R.string.label_page_counter, 4, 15)
        knownDefect("F-TOK-026", "the recent-folder card's seen count reads like the viewer position") {
            assertNotEquals("[en] the seen count looks like the position counter", position.en(), seen.en())
            assertNotEquals("[de] the seen count looks like the position counter", position.de(), seen.de())
        }
    }
}
