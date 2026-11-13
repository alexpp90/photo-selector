package com.photoselectortoolbox.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.photoselectortoolbox.domain.learn.LearnHistoryCounts
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.theme.DestructiveFill
import com.photoselectortoolbox.ui.theme.OnDestructiveFill
import com.photoselectortoolbox.ui.theme.Zinc800

/**
 * The Setup row *Reset Learn* beside *Reset Guidance* (shoot debrief SD-3 step 4): the only way to
 * remove Learn's history — shoot summaries, camera classes the user set, completed lessons and the
 * goal — which *Clear Cache* keeps on purpose (REQ-AND-CACHE.04). It deletes, so it asks first, and
 * the question says what goes ([resetLearnQuestion]). [counts] is null until [onOpen] has read them.
 */
@Composable
fun ResetLearnRow(
    counts: LearnHistoryCounts?,
    onOpen: () -> Unit,
    onReset: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    val show = {
        onOpen()
        open = true
    }
    SettingsClickItem(
        title = "Reset Learn",
        description = "Shoots, lessons and goal",
        onClick = show,
        icon = Icons.Default.RestartAlt,
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("Reset Learn") },
        text = { Text(counts?.let(::resetLearnQuestion) ?: "Delete Learn's history?") },
        confirmButton = {
            Button(
                modifier = Modifier.handCursor().testTag("reset_learn_confirm"),
                onClick = {
                    open = false
                    onReset()
                },
                colors = ButtonDefaults.buttonColors(containerColor = DestructiveFill, contentColor = OnDestructiveFill),
            ) {
                Text("Reset")
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.handCursor().testTag("reset_learn_cancel"), onClick = { open = false }) {
                Text("Cancel")
            }
        },
        containerColor = Zinc800,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("reset_learn_dialog"),
    )
}

/** "Delete 14 shoots, 3 lessons, 1 goal?" — counts first, each noun in its number. */
internal fun resetLearnQuestion(counts: LearnHistoryCounts): String {
    fun count(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
    return "Delete " + listOf(
        count(counts.shoots, "shoot", "shoots"),
        count(counts.lessons, "lesson", "lessons"),
        count(counts.goals, "goal", "goals"),
    ).joinToString(", ") + "?"
}
