package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.data.cache.LessonProgressEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.domain.learn.LessonCatalog
import com.photoselectortoolbox.domain.learn.MicroLesson
import com.photoselectortoolbox.ui.components.TouchTarget
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandAccentDeep
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.Zinc200
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.ui.theme.Zinc900
import com.photoselectortoolbox.ui.theme.Zinc950

// ── Lessons Tab (U-26) ────────────────────────────────────────────────────────

@Composable
internal fun LessonsTabContent(
    selectedLessonId: String?,
    onSelectLesson: (String?) -> Unit,
    factsByUri: Map<String, List<TechniqueFactEntity>>,
    completedLessons: Map<String, LessonProgressEntity>,
    onLessonCompleted: (LessonProgressEntity) -> Unit,
    onSetPracticeGoal: (String) -> Unit,
) {
    val activeLesson: MicroLesson? = selectedLessonId?.let { LessonCatalog.getById(it) }
    if (activeLesson != null) {
        val questions = remember(activeLesson, factsByUri) {
            LessonCatalog.buildSpotItQuiz(activeLesson, factsByUri)
        }
        var answers by remember(activeLesson.id) { mutableStateOf<Map<Int, Int>>(emptyMap()) }

        Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Zinc900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
            modifier = Modifier.fillMaxWidth().testTag("lesson_detail_card"),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TouchTarget(onClick = { onSelectLesson(null) }, testTag = "all_lessons_link") {
                    Text(
                        text = "← All Lessons (${activeLesson.track.title})",
                        style = MaterialTheme.typography.labelSmall,
                        color = BrandAccent,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
                Text(
                    text = activeLesson.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = activeLesson.conceptSummary,
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc200,
                )
                Text(
                    text = "Spot It Quiz (2 Questions)",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = BrandAccent,
                )
                questions.forEachIndexed { qIdx, q ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "${qIdx + 1}. ${q.prompt}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        q.options.forEachIndexed { optIdx, optText ->
                            val chosen = answers[qIdx] == optIdx
                            val isCorrect = optIdx == q.correctIndex
                            val borderCol = when {
                                !chosen -> Zinc800
                                isCorrect -> ScoreGood
                                else -> ScoreBad
                            }
                            TouchTarget(
                                onClick = {
                                    val updated = answers + (qIdx to optIdx)
                                    answers = updated
                                    if (updated.size == questions.size) {
                                        val correctCount = questions.indices.count { i ->
                                            updated[i] == questions[i].correctIndex
                                        }
                                        onLessonCompleted(
                                            LessonProgressEntity(
                                                lessonId = activeLesson.id,
                                                completedAt = System.currentTimeMillis(),
                                                spotItCorrect = correctCount,
                                                spotItTotal = questions.size,
                                            )
                                        )
                                    }
                                },
                                testTag = "quiz_option_${qIdx}_$optIdx",
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Zinc950,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, borderCol),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = optText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Zinc200,
                                        modifier = Modifier.padding(8.dp),
                                    )
                                }
                            }
                        }
                        // The result in words, not the border colour alone (SD-1b step 6).
                        answers[qIdx]?.let { chosen ->
                            Text(
                                text = LearnText.quizFeedback(chosen == q.correctIndex, q.explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = Zinc200,
                                modifier = Modifier
                                    .testTag("quiz_explanation_$qIdx")
                                    .semantics { liveRegion = LiveRegionMode.Polite },
                            )
                        }
                    }
                }

                TouchTarget(
                    onClick = { onSetPracticeGoal(activeLesson.id) },
                    testTag = "set_practice_goal_button",
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = BrandAccentDeep,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = "Practice this next shoot",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }
        return
    }

    LessonCatalog.lessonsByTrack().forEach { (track, lessons) ->
        Text(
            text = track.title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = Zinc400,
        )
        lessons.forEach { lesson ->
            val done = completedLessons.containsKey(lesson.id)
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Zinc900),
                border = androidx.compose.foundation.BorderStroke(1.dp, if (done) ScoreGood.copy(alpha = 0.5f) else Zinc800),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelectLesson(lesson.id) }
                    .testTag("lesson_item_${lesson.id}"),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = lesson.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text(
                            text = lesson.tryThisPrompt,
                            style = MaterialTheme.typography.labelSmall,
                            color = Zinc400,
                            maxLines = 2,
                        )
                    }
                    if (done) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Completed",
                            tint = ScoreGood,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}
