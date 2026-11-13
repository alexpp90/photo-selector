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
import androidx.compose.ui.res.stringResource
import com.photoselectortoolbox.R
import com.photoselectortoolbox.domain.learn.LearnHistoryCounts
import com.photoselectortoolbox.domain.text.UiText
import com.photoselectortoolbox.domain.text.plural
import com.photoselectortoolbox.domain.text.text
import com.photoselectortoolbox.ui.text.asString
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
        title = stringResource(R.string.setting_title_reset_learn),
        description = stringResource(R.string.setting_summary_reset_learn),
        onClick = show,
        icon = Icons.Default.RestartAlt,
    )
    if (!open) return
    AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.dialog_title_reset_learn)) },
        text = { Text((counts?.let(::resetLearnQuestion) ?: text(R.string.dialog_body_reset_learn_unknown)).asString()) },
        confirmButton = {
            Button(
                modifier = Modifier.handCursor().testTag("reset_learn_confirm"),
                onClick = {
                    open = false
                    onReset()
                },
                colors = ButtonDefaults.buttonColors(containerColor = DestructiveFill, contentColor = OnDestructiveFill),
            ) {
                Text(stringResource(R.string.action_reset))
            }
        },
        dismissButton = {
            TextButton(modifier = Modifier.handCursor().testTag("reset_learn_cancel"), onClick = { open = false }) {
                Text(stringResource(R.string.action_cancel))
            }
        },
        containerColor = Zinc800,
        titleContentColor = MaterialTheme.colorScheme.onSurface,
        textContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("reset_learn_dialog"),
    )
}

/** "Delete 14 shoots, 3 lessons, 1 goal?" — counts first, each noun in its number. */
internal fun resetLearnQuestion(counts: LearnHistoryCounts): UiText = text(
    R.string.dialog_body_reset_learn,
    plural(R.plurals.label_count_shoots, counts.shoots),
    plural(R.plurals.label_count_lessons, counts.lessons),
    plural(R.plurals.label_count_goals, counts.goals),
)

