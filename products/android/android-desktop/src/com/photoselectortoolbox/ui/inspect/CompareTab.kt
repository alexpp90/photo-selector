package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.learn.ComparableScene
import com.photoselectortoolbox.domain.learn.SceneComparator
import com.photoselectortoolbox.ui.components.dragDoesNotClick
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.HintText
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.Zinc200
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.ui.theme.Zinc900

// ── Compare Tab (U-27, `[USER 2026-11-12]` decision #17) ──────────────────────
//
// Compare shows the two frames it pairs. The canvas holds them side by side, the frame that is
// better on the deciding measurement first; the side panel lists the scenes, and a card picks the
// pair the canvas shows. The composition guides are not offered here: they belong to one photo.

/** The empty state: the comparator found no pair of one scene with a measured difference. */
internal const val COMPARE_EMPTY_TEXT = "No measured difference"

/**
 * The scenes of [images], computed only while Compare is open: the comparator walks the folder,
 * which no other tab needs.
 */
@Composable
internal fun rememberCompareScenes(images: List<ImageItem>, active: Boolean): List<ComparableScene> =
    if (active) remember(images) { SceneComparator.findComparableScenes(images) } else emptyList()

/** The scene the canvas shows: the one picked by [selectedSceneId], else the first. */
internal fun selectedScene(scenes: List<ComparableScene>, selectedSceneId: String?): ComparableScene? =
    scenes.firstOrNull { it.sceneId == selectedSceneId } ?: scenes.firstOrNull()

/** The canvas on Compare: both frames of [scene] side by side, or the empty state. */
@Composable
internal fun ComparePairCanvas(scene: ComparableScene?, modifier: Modifier = Modifier) {
    if (scene == null) {
        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.CompareArrows,
                contentDescription = null,
                tint = HintText,
                modifier = Modifier.size(32.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = COMPARE_EMPTY_TEXT,
                style = MaterialTheme.typography.bodyMedium,
                color = HintText,
                modifier = Modifier.testTag("compare_empty"),
            )
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CompareFrame(image = scene.primaryPhoto, better = true, modifier = Modifier.weight(1f))
        CompareFrame(image = scene.comparisonPhoto, better = false, modifier = Modifier.weight(1f))
    }
}

/**
 * One frame of the pair, the same size as the other (equal weights), with its file name under it.
 * The better one carries a "Better" chip — a word, so the state is never colour alone.
 */
@Composable
private fun CompareFrame(image: ImageItem, better: Boolean, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clipToBounds(),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = image.uri,
                contentDescription = if (better) "Better frame, ${image.fileName}" else "Other frame, ${image.fileName}",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag(if (better) "compare_frame_better" else "compare_frame_other"),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        ) {
            if (better) {
                Surface(shape = RoundedCornerShape(4.dp), color = ScoreGood.copy(alpha = 0.16f)) {
                    Text(
                        text = "Better",
                        style = MaterialTheme.typography.labelSmall,
                        color = ScoreGood,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = image.fileName,
                style = MaterialTheme.typography.labelMedium,
                color = Zinc200,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The side panel on Compare: one card per scene; a card picks the pair the canvas shows. */
@Composable
internal fun CompareTabContent(
    scenes: List<ComparableScene>,
    selected: ComparableScene?,
    onSelectScene: (String) -> Unit,
) {
    scenes.forEach { scene ->
        val isSelected = scene.sceneId == selected?.sceneId
        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Zinc900),
            border = BorderStroke(1.dp, if (isSelected) BrandAccent else Zinc800),
            modifier = Modifier
                .fillMaxWidth()
                .dragDoesNotClick()
                .selectable(selected = isSelected, onClick = { onSelectScene(scene.sceneId) }, role = Role.RadioButton)
                .handCursor()
                .testTag("scene_compare_card"),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = scene.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = "Why one worked better:",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = BrandAccent,
                )
                scene.differenceBullets.forEach { bullet ->
                    Text(
                        text = "• $bullet",
                        style = MaterialTheme.typography.bodySmall,
                        color = Zinc200,
                    )
                }
            }
        }
    }
}
