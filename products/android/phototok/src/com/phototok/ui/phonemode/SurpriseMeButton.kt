package com.phototok.ui.phonemode

import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.phototok.R
import com.phototok.ui.components.NameTooltip

/**
 * Casual's "surprise me" ([USER 2026-11-12] decision #14): an icon-only toggle (REQ-TOK-UX.02),
 * outlined while off and filled while on, so the state is a shape and never colour alone. Spoken
 * "Surprise me" with the state "On" / "Off"; the long-press tooltip names it.
 */
@Composable
internal fun SurpriseMeButton(
    on: Boolean,
    tint: Color,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val name = stringResource(R.string.cd_surprise_me)
    val state = stringResource(if (on) R.string.cd_state_on else R.string.cd_state_off)
    NameTooltip(name = name) {
        OutlinedIconToggleButton(
            checked = on,
            onCheckedChange = { onToggle() },
            modifier = modifier.semantics {
                contentDescription = name
                stateDescription = state
            },
            colors = IconButtonDefaults.outlinedIconToggleButtonColors(
                contentColor = tint,
                checkedContainerColor = colors.secondaryContainer,
                checkedContentColor = colors.onSecondaryContainer,
            ),
            border = if (on) null else BorderStroke(1.dp, tint.copy(alpha = 0.6f)),
        ) {
            Icon(imageVector = Icons.Filled.Shuffle, contentDescription = null)
        }
    }
}
