package com.phototok.ui.phonemode

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.phototok.R
import com.phototok.domain.LandingStatsLine
import com.phototok.domain.SwipeRightOutcome

/** The glyph of what swipe right does for a source (REQ-TOK-FEATURE.20/.21). */
internal fun SwipeRightOutcome.icon(): ImageVector = when (kind) {
    SwipeRightOutcome.Kind.FAVOURITE -> Icons.Filled.Star
    SwipeRightOutcome.Kind.COPY_TO_PHONE -> Icons.Filled.FileDownload
    SwipeRightOutcome.Kind.COPY_TO_FOLDER -> Icons.Filled.Folder
    SwipeRightOutcome.Kind.MOVE_TO_FOLDER -> Icons.AutoMirrored.Filled.DriveFileMove
}

/** Its spoken name: "Swipe right: favourite", "… copy to phone", "… copy to Selection". */
@Composable
internal fun SwipeRightOutcome.spoken(): String = when (kind) {
    SwipeRightOutcome.Kind.FAVOURITE -> stringResource(R.string.cd_swipe_right_outcome_favourite)
    SwipeRightOutcome.Kind.COPY_TO_PHONE -> stringResource(R.string.cd_swipe_right_outcome_phone)
    SwipeRightOutcome.Kind.COPY_TO_FOLDER -> stringResource(R.string.cd_swipe_right_outcome_copy, folder)
    SwipeRightOutcome.Kind.MOVE_TO_FOLDER -> stringResource(R.string.cd_swipe_right_outcome_move, folder)
}

/** A 16 dp glyph that names the outcome to TalkBack; merged into the card or row around it. */
@Composable
internal fun SwipeRightOutcomeGlyph(outcome: SwipeRightOutcome, tag: String, modifier: Modifier = Modifier) {
    val spoken = outcome.spoken()
    Icon(
        imageVector = outcome.icon(),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(16.dp)
            .testTag(tag)
            .clearAndSetSemantics { contentDescription = spoken },
    )
}

/**
 * Between the app name and the sources: three glyph + number pairs (reviewed, selected, freed), no
 * words. After the feed was left it is this session's, on a tinted card with a check glyph; else
 * the lifetime figures, muted. TalkBack reads one sentence.
 */
@Composable
internal fun LandingStatsStrip(line: LandingStatsLine, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val f = line.figures
    val freed = Formatter.formatShortFileSize(context, f.freedBytes)
    val spoken = stringResource(
        if (line.isSession) R.string.cd_landing_stats_session else R.string.cd_landing_stats_total,
        f.reviewed,
        f.selected,
        freed,
    )
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        ) {
            if (line.isSession) {
                Icon(Icons.Filled.TaskAlt, contentDescription = null, tint = colors.primary, modifier = Modifier.size(18.dp))
            }
            StatPair(Icons.Filled.Layers, f.reviewed.toString())
            StatPair(Icons.Filled.Star, f.selected.toString())
            StatPair(Icons.Filled.Storage, freed)
        }
    }
    val semanticsModifier = modifier
        .testTag(if (line.isSession) "landing_stats_session" else "landing_stats_total")
        .clearAndSetSemantics { contentDescription = spoken }
    if (line.isSession) {
        Surface(
            modifier = semanticsModifier,
            shape = RoundedCornerShape(12.dp),
            color = colors.primaryContainer.copy(alpha = 0.6f),
            content = content,
        )
    } else {
        Row(modifier = semanticsModifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { content() }
    }
}

@Composable
private fun StatPair(icon: ImageVector, value: String) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(text = value, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}
