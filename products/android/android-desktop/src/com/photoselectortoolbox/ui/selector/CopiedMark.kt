package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.StudioCanvas

/** The copied mark's size on a frame (three-up and maximised). */
internal val CopiedMarkFrameSize: Dp = 28.dp

/** The copied mark's size on a filmstrip tile. */
internal val CopiedMarkTileSize: Dp = 16.dp

/**
 * A photo copied to the Selection in this session (`SelectorUiState.sessionCopiedUris`): a filled
 * copy glyph in the good-score colour on a dark plate, so "already copied" reads apart from "not
 * yet decided" once the confirmation card has gone (backlog 2026-11-11). Not a control: it takes
 * no input and sits in a corner no control uses. [spoken] is null where the mark lives inside a
 * merged control that says it already (the filmstrip tile).
 */
@Composable
internal fun CopiedMark(size: Dp, spoken: String?, testTag: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(if (size > 20.dp) 6.dp else 3.dp))
            .background(StudioCanvas.copy(alpha = 0.78f))
            .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
            .testTag(testTag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = null,
            tint = ScoreGood,
            modifier = Modifier.size(size * 0.7f),
        )
    }
}
