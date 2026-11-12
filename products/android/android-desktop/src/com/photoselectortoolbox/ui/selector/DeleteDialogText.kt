package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.viewmodel.CompanionConfirmation

/** The plain title, for a photo that takes no companion file with it. */
internal const val DELETE_DIALOG_TITLE = "Delete image"

/**
 * The delete dialog's title: "Delete IMG_0001 +2?", spoken "Delete IMG_0001.JPG and 2 companion
 * files?", where the delete takes companions ([USER 2026-11-12] decision #33 (1)); "Delete image"
 * otherwise. The name is already shortened in the middle, so "+2" is never the part cut.
 */
@Composable
internal fun DeleteDialogTitle(image: ImageItem?) {
    val title = image?.let(CompanionConfirmation::deleteTitle)
    Text(
        text = title?.visible ?: DELETE_DIALOG_TITLE,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .then(if (title != null) Modifier.semantics { contentDescription = title.spoken } else Modifier)
            .testTag("delete_dialog_title"),
    )
}

/** "Also deletes JPG, XMP" above the dialog's [message], where the delete takes companions. */
@Composable
internal fun DeleteDialogBody(image: ImageItem?, message: String) {
    val also = image?.let(CompanionConfirmation::deleteAlso)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (also != null) Text(text = also, modifier = Modifier.testTag("delete_dialog_also"))
        Text(text = message)
    }
}
