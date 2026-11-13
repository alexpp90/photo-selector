package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.ui.components.MinTouchTarget
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.theme.Zinc400
import androidx.compose.ui.res.stringResource
import com.photoselectortoolbox.R
import com.photoselectortoolbox.ui.text.asString

/**
 * The start of Learn's control bar: the way back to Cull. Its Beta chip ([USER 2026-11-12]
 * decision #18) went once Compare showed both frames and Learn's progress was stored.
 */
@Composable
internal fun LearnBack(onNavigateToSelector: () -> Unit) {
    IconButton(
        onClick = onNavigateToSelector,
        modifier = Modifier
            .size(MinTouchTarget)
            .testTag("back_to_cull_button")
            .handCursor(),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = stringResource(R.string.cd_back_to_cull),
            tint = Zinc400,
        )
    }
}
