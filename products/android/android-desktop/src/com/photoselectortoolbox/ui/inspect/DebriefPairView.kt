@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.domain.learn.DecidedBy
import com.photoselectortoolbox.domain.learn.SceneComparator
import com.photoselectortoolbox.ui.components.MinTouchTarget
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandAccentDeep
import com.photoselectortoolbox.ui.theme.HintText
import com.photoselectortoolbox.ui.theme.Zinc200
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.ui.theme.Zinc900
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import com.photoselectortoolbox.R
import com.photoselectortoolbox.ui.text.asString

/** The guess in the worked pair: a frame, or *Show me* (no guess). Not scored, not stored. */
enum class PairChoice { A, B, SHOWN }

/** Below this canvas width the two frames stand one over the other (to confirm on a device). */
private val STACK_BELOW = 560.dp

/**
 * Keys while the worked pair shows (SD-5 step 3): `A` / `B` choose, `Esc` closes it; `0`–`5` and
 * `X` do nothing, because the photo they would rate is not on screen. Null: not the pair's key.
 */
internal fun handlePairKey(key: Key, onChoose: (PairChoice) -> Unit, onClose: () -> Unit): Boolean? = when (key) {
    Key.A -> true.also { onChoose(PairChoice.A) }
    Key.B -> true.also { onChoose(PairChoice.B) }
    Key.Escape -> true.also { onClose() }
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.X,
    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5 -> true
    else -> null
}

/**
 * The worked pair in the photo canvas (shoot debrief SD-5): two frames of one scene from the
 * user's own shoot. The question comes first, always ("Which is sharper?"); the frames are A and
 * B in shooting order, so their place gives nothing away. After a choice, or *Show me*, both
 * frames stay, each with its settings under it and the measured value drawn on it as a label (the
 * better one also says so in a word), and at most three sentences explain it, worded by who chose
 * the exposure; the explanation is a polite live region and takes the focus.
 */
@Composable
internal fun DebriefPairView(
    pair: SceneComparator.Difference,
    decidedBy: DecidedBy,
    choice: PairChoice?,
    onChoose: (PairChoice) -> Unit,
    onClose: () -> Unit,
) {
    val frames = remember(pair) { listOf(pair.better, pair.worse).sortedWith(compareBy({ it.lastModified }, { it.fileName })) }
    val revealed = choice != null
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(12.dp).testTag("debrief_pair_view")) {
        val stacked = maxWidth < STACK_BELOW
        // The frames take most of the canvas, never less than a readable minimum: in a short
        // window the view scrolls rather than squeezing a frame to nothing.
        val frameHeight = maxOf(maxHeight * (if (stacked) 0.45f else 0.6f), MIN_FRAME_HEIGHT)
        Column(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        ) {
            Text(
                LearnText.pairQuestion(pair.metric).asString(),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!revealed) DebriefButton(stringResource(R.string.action_show_me), "debrief_pair_reveal") { onChoose(PairChoice.SHOWN) }
                DebriefButton(stringResource(R.string.action_close), "debrief_pair_close", spoken = stringResource(R.string.cd_close_pair), onClick = onClose)
            }
            if (stacked) {
                Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    frames.forEachIndexed { i, photo ->
                        val letter = if (i == 0) PairChoice.A else PairChoice.B
                        PairFrame(photo, letter, pair, revealed, choice == letter, onChoose, Modifier.fillMaxWidth().height(frameHeight))
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth().height(frameHeight).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    frames.forEachIndexed { i, photo ->
                        val letter = if (i == 0) PairChoice.A else PairChoice.B
                        PairFrame(photo, letter, pair, revealed, choice == letter, onChoose, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
            if (revealed) PairExplanation(pair, decidedBy, frames, choice!!)
        }
    }
}

private val MIN_FRAME_HEIGHT = 160.dp

@Composable
private fun PairFrame(
    photo: ImageItem,
    letter: PairChoice,
    pair: SceneComparator.Difference,
    revealed: Boolean,
    chosen: Boolean,
    onChoose: (PairChoice) -> Unit,
    modifier: Modifier,
) {
    val settings = settingsOf(photo)
    val better = photo.uri == pair.better.uri
    val spoken = stringResource(R.string.cd_pair_photo, letter.name, settings)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
                .clipToBounds()
                .background(Color.Black)
                .border(if (chosen) 2.dp else 1.dp, if (chosen) BrandAccent else Zinc700)
                .selectable(selected = chosen, enabled = !revealed, role = Role.RadioButton) { onChoose(letter) }
                .handCursor()
                .semantics { contentDescription = spoken }
                .testTag("debrief_pair_${letter.name.lowercase()}"),
        ) {
            AsyncImage(model = photo.uri, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            Surface(color = Zinc900.copy(alpha = 0.85f), shape = RoundedCornerShape(6.dp), modifier = Modifier.align(Alignment.TopStart).padding(6.dp)) {
                Text(letter.name, style = MaterialTheme.typography.labelLarge, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
            if (revealed) {
                // The measured difference on the photo, as words: never colour alone.
                Surface(
                    color = if (better) BrandAccentDeep else Zinc900.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).testTag("debrief_pair_value_${letter.name.lowercase()}"),
                ) {
                    val value = LearnText.pairValue(pair.metric, photo.scanResult).asString()
                    Text(if (better) stringResource(R.string.label_pair_better, value) else value, style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp))
                }
            }
        }
        if (revealed) Text(settings, style = MaterialTheme.typography.labelSmall, color = HintText, maxLines = 2)
    }
}

/** At most three sentences: the answer, the measurement, and the settings behind it. */
@Composable
private fun PairExplanation(pair: SceneComparator.Difference, decidedBy: DecidedBy, frames: List<ImageItem>, choice: PairChoice) {
    val betterLetter = if (frames[0].uri == pair.better.uri) "A" else "B"
    val betterValue = LearnText.pairValue(pair.metric, pair.better.scanResult).asString()
    // English reads "Sharpness 80 against sharpness 40"; German keeps its capital nouns.
    val worseValue = LearnText.pairValue(pair.metric, pair.worse.scanResult).asString().let { v ->
        if (LocalConfiguration.current.locales[0].language == "en") v.replaceFirstChar { it.lowercase() } else v
    }
    val sb = pair.better.exifData?.shutterSpeed
    val sw = pair.worse.exifData?.shutterSpeed
    val sentences = listOfNotNull(
        when (choice) {
            PairChoice.SHOWN -> stringResource(R.string.learn_pair_better_frame, betterLetter)
            else -> stringResource(if (choice.name == betterLetter) R.string.learn_pair_right else R.string.learn_pair_better_frame, betterLetter)
        },
        stringResource(R.string.learn_pair_values, betterValue, worseValue),
        if (sb != null && sw != null && sb != sw) {
            val who = when (decidedBy) {
                DecidedBy.CAMERA -> R.string.learn_pair_camera_chose
                DecidedBy.PHOTOGRAPHER -> R.string.learn_pair_you_chose
                else -> R.string.learn_pair_shutter_was
            }
            stringResource(who, ExifFormatter.shutterSpeed(sb), betterLetter, ExifFormatter.shutterSpeed(sw))
        } else {
            null
        },
    ).take(3)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Text(
        sentences.joinToString(" "),
        style = MaterialTheme.typography.bodyMedium,
        color = Zinc200,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("debrief_pair_explanation")
            .semantics { liveRegion = LiveRegionMode.Polite }
            .focusRequester(focus)
            .focusable(),
    )
}

/** "1/250s · f/4.0 · ISO 200": what is known of the frame's settings. */
private fun settingsOf(photo: ImageItem): String {
    val exif = photo.exifData
    return listOfNotNull(
        exif?.shutterSpeed?.let(ExifFormatter::shutterSpeed),
        exif?.aperture?.let(ExifFormatter::aperture),
        exif?.iso?.let(ExifFormatter::iso),
    ).joinToString(" · ").ifEmpty { photo.fileName }
}
