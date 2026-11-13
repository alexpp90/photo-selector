package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.data.cache.PracticeGoalEntity
import com.photoselectortoolbox.data.cache.TechniqueFactEntity
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.learn.LessonCatalog
import com.photoselectortoolbox.domain.learn.PracticeAndInsightsEngine
import com.photoselectortoolbox.ui.components.TouchTarget
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.HintText
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.Zinc200
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.ui.theme.Zinc900
import com.photoselectortoolbox.ui.theme.Zinc950

// ── Practice & Insights Tab (U-28) ────────────────────────────────────────────

@Composable
internal fun PracticeTabContent(
    images: List<ImageItem>,
    factsByUri: Map<String, List<TechniqueFactEntity>>,
    activePracticeGoal: PracticeGoalEntity?,
    onSelectLessonForGoal: (String) -> Unit,
    onOpenLesson: (String) -> Unit,
) {
    val goalStatus = remember(activePracticeGoal, images, factsByUri) {
        activePracticeGoal?.let { PracticeAndInsightsEngine.evaluateGoalProgress(it, images, factsByUri) }
    }
    val insights = remember(images, factsByUri) {
        PracticeAndInsightsEngine.computeInsights(images, factsByUri)
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900),
        border = androidx.compose.foundation.BorderStroke(1.dp, BrandAccent.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().testTag("practice_goal_card"),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Active Practice Goal",
                style = MaterialTheme.typography.labelSmall,
                color = BrandAccent,
                fontWeight = FontWeight.Bold,
            )
            if (goalStatus != null) {
                Text(
                    text = goalStatus.lessonTitle,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Text(
                    text = goalStatus.tryThisPrompt,
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc200,
                )
                Text(
                    text = goalStatus.summaryText,
                    style = MaterialTheme.typography.labelSmall,
                    color = ScoreGood,
                )
            } else {
                Text(
                    text = "Choose one technique to focus on during your next shoot:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc400,
                )
                LessonCatalog.ALL_LESSONS.take(3).forEach { lesson ->
                    TouchTarget(
                        onClick = { onSelectLessonForGoal(lesson.id) },
                        testTag = "set_goal_${lesson.id}",
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Zinc950,
                            border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = "Set Goal: ${lesson.title}",
                                style = MaterialTheme.typography.labelSmall,
                                color = BrandAccent,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    Text(
        text = "Personal Pattern Insights",
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = Zinc400,
    )

    if (insights.isEmpty()) {
        Text(
            text = "Scan at least ${PracticeAndInsightsEngine.MIN_SAMPLE_SIZE} photos in a shoot folder to unlock statistically grounded personal pattern insights.",
            style = MaterialTheme.typography.bodySmall,
            color = HintText,
        )
    } else {
        insights.forEach { insight ->
            Card(
                shape = RoundedCornerShape(10.dp),
                colors = CardDefaults.cardColors(containerColor = Zinc900),
                border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenLesson(insight.recommendedLessonId) }
                    .testTag("pattern_insight_card"),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = insight.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = insight.observation,
                        style = MaterialTheme.typography.bodySmall,
                        color = Zinc200,
                    )
                }
            }
        }
    }
}
