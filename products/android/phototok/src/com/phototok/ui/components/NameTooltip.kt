package com.phototok.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Long-press tooltip that names an icon-only control (`REQ-TOK-UX.02`): a sighted user can find out
 * what an icon does without opening the controls guide. The tooltip is the same text as the
 * control's `contentDescription`. It only listens for the long press, so taps still reach the
 * control inside [content].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameTooltip(
    name: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(name) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box { content() }
    }
}
