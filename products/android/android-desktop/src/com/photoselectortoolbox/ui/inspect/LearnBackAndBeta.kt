package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.ui.components.MinTouchTarget
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.navigation.BetaChip
import com.photoselectortoolbox.ui.navigation.BetaLabel
import com.photoselectortoolbox.ui.navigation.Screen
import com.photoselectortoolbox.ui.theme.Zinc400

/**
 * The start of Learn's control bar: the way back to Cull, then the Beta chip. Learn is labelled
 * Beta until Compare shows both frames and its progress is stored ([USER 2026-11-12] decision
 * #18); Learn has no title, so the chip stands where a title would.
 */
@Composable
internal fun LearnBackAndBeta(onNavigateToSelector: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IconButton(
            onClick = onNavigateToSelector,
            modifier = Modifier
                .size(MinTouchTarget)
                .testTag("back_to_cull_button")
                .handCursor(),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back to Cull (L / Escape)",
                tint = Zinc400,
            )
        }
        BetaChip(spoken = BetaLabel.spoken(Screen.Inspect))
    }
}
