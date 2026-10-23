package com.photoselectortoolbox.domain.grouping

import org.junit.Assert.assertTrue
import org.junit.Test

class GroupingLevelTest {

    @Test
    fun `every GroupingLevel has a non-blank label and description`() {
        GroupingLevel.entries.forEach { level ->
            assertTrue(
                "Level ${level.name} should have a non-blank label",
                level.label.isNotBlank()
            )
            assertTrue(
                "Level ${level.name} should have a non-blank description",
                level.description.isNotBlank()
            )
        }
    }
}
