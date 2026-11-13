@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.domain.learn.CameraClass
import com.photoselectortoolbox.domain.learn.Debrief
import com.photoselectortoolbox.domain.learn.DebriefBuilder
import com.photoselectortoolbox.domain.learn.DebriefCard
import com.photoselectortoolbox.domain.learn.CameraId
import com.photoselectortoolbox.domain.learn.MetricCount
import com.photoselectortoolbox.ui.components.MinTouchTarget
import com.photoselectortoolbox.ui.components.TouchTarget
import com.photoselectortoolbox.ui.components.handCursor
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandAccentDeep
import com.photoselectortoolbox.ui.theme.HintText
import com.photoselectortoolbox.ui.theme.Zinc200
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc700
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.ui.theme.Zinc900

/** What the Debrief tab does outside itself: the cull's scan, a camera's class, tracking a focus. */
class DebriefActions(
    val onScan: () -> Unit = {},
    val onSetCameraClass: (List<CameraId>, CameraClass) -> Unit = { _, _ -> },
    val onTrack: (DebriefCard) -> Unit = {},
)

/**
 * The Debrief tab (shoot debrief SD-4): one card per camera class of the open folder, inside the
 * side panel, so the photo keeps its size beside it. With three or more classes only the largest
 * starts open. A card names its class on a 48 dp chip that changes it, its cameras and photo
 * count; up to three counts ("104 of 132", a 6 dp bar that never carries the value alone, and
 * "Before: 71 %" from the previous shoot of the class — a plain figure, never a verdict); and the
 * focus: one "Next time" line, *Show N* (the filmstrip shows those photos), its lesson, *Track*
 * and the worked pair.
 */
@Composable
internal fun DebriefTabContent(
    debrief: Debrief,
    goal: PracticeGoalEntity?,
    actions: DebriefActions,
    shownUris: Set<String>?,
    onShow: (Set<String>?) -> Unit,
    onOpenLesson: (String) -> Unit,
    onOpenPair: (DebriefCard) -> Unit,
) {
    if (!debrief.scanned) {
        Text(LearnText.NOT_SCANNED, style = MaterialTheme.typography.bodySmall, color = Zinc200, modifier = Modifier.testTag("debrief_not_scanned"))
        DebriefButton("Scan", "debrief_scan", onClick = actions.onScan)
        return
    }
    if (debrief.partial) {
        Text(LearnText.partial(debrief.scannedCount, debrief.photoCount), style = MaterialTheme.typography.labelSmall, color = HintText, modifier = Modifier.testTag("debrief_partial"))
    }
    if (debrief.noCameraData) {
        Text(LearnText.NO_CAMERA_DATA, style = MaterialTheme.typography.labelMedium, color = Zinc400, modifier = Modifier.testTag("debrief_no_camera_data"))
    }
    val expanded = remember(debrief.cards.map { it.group.cameraClass }) {
        mutableStateMapOf<CameraClass, Boolean>().apply {
            debrief.cards.forEachIndexed { i, card -> put(card.group.cameraClass, debrief.cards.size < 3 || i == 0) }
        }
    }
    debrief.cards.forEach { card ->
        val c = card.group.cameraClass
        DebriefGroupCard(
            card = card,
            goal = goal,
            expanded = expanded[c] == true,
            onExpand = { expanded[c] = true },
            actions = actions,
            shownUris = shownUris,
            onShow = onShow,
            onOpenLesson = onOpenLesson,
            onOpenPair = onOpenPair,
        )
    }
}

@Composable
private fun DebriefGroupCard(
    card: DebriefCard,
    goal: PracticeGoalEntity?,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: DebriefActions,
    shownUris: Set<String>?,
    onShow: (Set<String>?) -> Unit,
    onOpenLesson: (String) -> Unit,
    onOpenPair: (DebriefCard) -> Unit,
) {
    val group = card.group
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900),
        border = BorderStroke(1.dp, Zinc800),
        modifier = Modifier.fillMaxWidth().testTag("debrief_group_${group.cameraClass.key}"),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The chip and the expand control on one line, the names and the count under them: at
            // font scale 2.0 a row of all four left the names a few letters wide.
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                CameraChip(group.cameraClass) { chosen -> actions.onSetCameraClass(card.cameras, chosen) }
                Box(modifier = Modifier.weight(1f))
                if (!expanded) {
                    TouchTarget(onClick = onExpand, testTag = "debrief_expand_${group.cameraClass.key}") {
                        Icon(Icons.Default.ExpandMore, contentDescription = "Show ${LearnText.cameraClass(group.cameraClass)}", tint = Zinc400)
                    }
                }
            }
            if (group.cameraNames.isNotEmpty()) {
                Text(group.cameraNames.joinToString(", "), style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
            Text(LearnText.photos(group.photoCount), style = MaterialTheme.typography.labelSmall, color = Zinc400)
            if (!expanded) return@Column
            group.metrics.forEach { MetricRow(it, card.before[it.metric]) }
            DebriefBuilder.goalLine(goal, card)?.let { line ->
                Text(
                    "Goal: ${line.nowPercent} % (was ${line.wasPercent} %)",
                    style = MaterialTheme.typography.labelMedium,
                    color = Zinc200,
                    modifier = Modifier.testTag("debrief_goal"),
                )
            }
            val focus = group.focus
            if (focus == null) {
                // A small shoot has no focus, and may still have a pair that tells two frames apart.
                if (card.pair != null) DebriefButton("Worked pair", "debrief_open_pair") { onOpenPair(card) }
                return@Column
            }
            Column(modifier = Modifier.fillMaxWidth().testTag("debrief_focus"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                group.advice.forEachIndexed { i, line ->
                    Text(if (i == 0) "Next time: $line" else line, style = MaterialTheme.typography.bodySmall, color = Zinc200)
                }
                val problems = group.problemUris[focus].orEmpty()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (problems.isNotEmpty()) {
                        val showing = shownUris == problems.toSet()
                        DebriefButton(
                            if (showing) "Show all" else "Show ${problems.size}",
                            "debrief_show_problems",
                            icon = Icons.Default.FilterList,
                            spoken = if (showing) "Show every photo in the filmstrip" else "Show the ${problems.size} photos in the filmstrip",
                        ) { onShow(if (showing) null else problems.toSet()) }
                    }
                    DebriefButton("Lesson", "debrief_focus_lesson") { onOpenLesson(LearnText.lessonFor(focus)) }
                    val tracked = DebriefBuilder.tracks(goal, card)
                    DebriefButton(if (tracked) "Tracked" else "Track", "debrief_track") { if (!tracked) actions.onTrack(card) }
                    if (card.pair != null) DebriefButton("Worked pair", "debrief_open_pair") { onOpenPair(card) }
                }
            }
        }
    }
}

/** One count: label and "104 of 132", a 6 dp bar, and "Before: 71 %"; spoken as one phrase. */
@Composable
private fun MetricRow(count: MetricCount, before: Int?) {
    val label = LearnText.metric(count.metric)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("debrief_metric_${count.metric.key}")
            .clearAndSetSemantics { contentDescription = LearnText.metricSpoken(label, count.matching, count.total, before) },
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Zinc200)
            Text("${count.matching} of ${count.total}", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
        }
        val share = if (count.total > 0) count.matching.toFloat() / count.total else 0f
        Box(modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Zinc800)) {
            Box(modifier = Modifier.fillMaxWidth(share).height(6.dp).background(BrandAccent))
        }
        if (before != null) Text("Before: $before %", style = MaterialTheme.typography.labelSmall, color = HintText)
    }
}

/** The 48 dp chip that names the class and changes it (stored per camera). */
@Composable
private fun CameraChip(cameraClass: CameraClass, onChoose: (CameraClass) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TouchTarget(
            onClick = { open = true },
            testTag = "debrief_camera_chip",
            modifier = Modifier.semantics { contentDescription = LearnText.cameraChipSpoken(cameraClass) },
        ) {
            Surface(shape = RoundedCornerShape(8.dp), color = BrandAccentDeep, border = BorderStroke(1.dp, BrandAccent)) {
                Text(
                    LearnText.cameraClass(cameraClass),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag("debrief_class_menu")) {
            CameraClass.entries.forEach { c ->
                DropdownMenuItem(
                    text = { Text(LearnText.cameraClass(c)) },
                    onClick = {
                        open = false
                        if (c != cameraClass) onChoose(c)
                    },
                    modifier = Modifier.heightIn(min = MinTouchTarget).handCursor().testTag("debrief_class_${c.key}"),
                )
            }
        }
    }
}

@Composable
internal fun DebriefButton(
    label: String,
    tag: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    spoken: String? = null,
    onClick: () -> Unit,
) {
    TouchTarget(
        onClick = onClick,
        testTag = tag,
        modifier = if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier,
        shape = RoundedCornerShape(8.dp),
    ) {
        Surface(shape = RoundedCornerShape(8.dp), color = Zinc900, border = BorderStroke(1.dp, Zinc700)) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (icon != null) Icon(icon, contentDescription = null, tint = BrandAccent, modifier = Modifier.size(16.dp))
                Text(label, style = MaterialTheme.typography.labelMedium, color = BrandAccent, maxLines = 1, softWrap = false)
            }
        }
    }
}
