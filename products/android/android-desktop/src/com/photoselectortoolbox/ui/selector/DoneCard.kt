package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.domain.format.DestinationLabels
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.theme.ScoreGood

/**
 * What a session did to the open folder, for the card shown once it is empty (backlog
 * 2026-11-11): moved, copied and deleted photos, and where the kept ones went.
 */
data class DoneSummary(
    val moved: Int,
    val copied: Int,
    val deleted: Int,
    /** The Selection folder the kept photos went to, as a path; null when nothing was kept. */
    val destination: String?,
) {
    val any: Boolean get() = moved + copied + deleted > 0

    /** The one sentence a screen reader hears, e.g. "Done. 12 moved, 3 copied, 4 deleted." */
    val spoken: String
        get() = "Done. " + cells.joinToString(", ") { (_, count, word) -> "$count ${word.lowercase()}" } + "."

    /** Count cells drawn, zeros left out. */
    internal val cells: List<Triple<ImageVector, Int, String>>
        get() = listOf(
            Triple(Icons.AutoMirrored.Filled.DriveFileMove, moved, "Moved"),
            Triple(Icons.Filled.ContentCopy, copied, "Copied"),
            Triple(Icons.Filled.Delete, deleted, "Deleted"),
        ).filter { it.second > 0 }

    companion object {
        /** The destination path the confirmations name: the custom folder's, else the open folder's. */
        fun destinationOf(folderName: String, filingFolderName: String, customDestinationName: String?): String =
            if (customDestinationName.isNullOrBlank()) {
                listOf(folderName, filingFolderName).filter { it.isNotBlank() }.joinToString(DestinationLabels.SEPARATOR)
            } else {
                DestinationLabels.customDestinationName(customDestinationName, filingFolderName)
            }
    }
}

/**
 * The end of a cull: the folder is empty because the photographer filed or deleted every photo,
 * which is not "No photos found". Title, count cells (glyph, number, one word; zeros hidden), the
 * destination, and Open Folder. Announced once, politely, as one sentence.
 */
@Composable
internal fun DoneCard(summary: DoneSummary, onOpenFolder: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.widthIn(max = 520.dp).padding(24.dp).testTag("done_card"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clearAndSetSemantics {
                    contentDescription = summary.spoken
                    liveRegion = LiveRegionMode.Polite
                    testTag = "done_summary"
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ScoreGood, modifier = Modifier.size(32.dp))
                    Text("Done", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    summary.cells.forEach { (icon, count, word) ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.testTag("done_count_${word.lowercase()}"),
                        ) {
                            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(count.toString(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                            Text(word, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                summary.destination?.let { path ->
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = path,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().testTag("done_destination"),
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = onOpenFolder, modifier = Modifier.handCursor().testTag("done_open_folder")) {
                Text("Open Folder")
            }
        }
    }
}
