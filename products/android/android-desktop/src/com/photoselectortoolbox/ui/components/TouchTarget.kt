package com.photoselectortoolbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.RoundedCornerShape

/** The smallest touch target the product allows, in dp (Material, WCAG 2.5.8 AAA, REQ-AND-LAYOUT). */
val MinTouchTarget = 48.dp

/**
 * A control whose touch target is at least 48 x 48 dp while its visual stays as small as [content]
 * draws it. The node that is as big as the target is the node that carries the `clickable` and the
 * [testTag], which is what the lab, uiautomator and `onNodeWithTag` measure. Put the visible chip
 * inside [content] as a plain, non-clickable surface.
 */
@Composable
fun TouchTarget(
    onClick: () -> Unit,
    testTag: String?,
    modifier: Modifier = Modifier,
    role: Role = Role.Button,
    shape: Shape = RoundedCornerShape(6.dp),
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .clip(shape)
            .clickable(role = role, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .let { if (testTag != null) it.testTag(testTag) else it }
            .semantics { this.role = role },
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}
