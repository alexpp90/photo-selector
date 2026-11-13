package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.model.CoachingTip
import com.photoselectortoolbox.domain.model.PhotoCoachReport
import com.photoselectortoolbox.domain.model.TipSeverity
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.HintText
import com.photoselectortoolbox.ui.theme.ScoreBad
import com.photoselectortoolbox.ui.theme.ScoreGood
import com.photoselectortoolbox.ui.theme.ScoreWarn
import com.photoselectortoolbox.ui.theme.Zinc200
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.ui.theme.Zinc900
import com.photoselectortoolbox.ui.theme.Zinc950
import androidx.compose.ui.res.stringResource
import com.photoselectortoolbox.R
import com.photoselectortoolbox.ui.text.asString

// ── Photo Coach Card (U-20: Hand-Holding Guideline wording, no 'shake') ───────

@Composable
internal fun PhotoCoachCard(
    currentImage: ImageItem,
    coachReport: PhotoCoachReport,
    modifier: Modifier = Modifier,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900),
        border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
        modifier = modifier
            .fillMaxWidth()
            .testTag("photo_coach_card"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.School,
                    contentDescription = null,
                    tint = BrandAccent,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.title_photo_coach),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            val risk = coachReport.cameraShakeRisk
            val riskLabel = LearnText.blurRisk(risk)
            val riskColor = when {
                risk >= 0.7f -> ScoreBad
                risk >= 0.3f -> ScoreWarn
                else -> ScoreGood
            }

            // A flow, not a SpaceBetween row: at font scale 2.0 the two unweighted texts
            // squeezed each other and the badge broke mid-word ("Lo/w", F-FD-081). Here the
            // badge drops below the label when both do not fit, and wraps only between words.
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("camera_shake_badge")
                    .testTag("camera_shake_risk"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.label_hand_holding),
                    style = MaterialTheme.typography.bodySmall,
                    color = Zinc400,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = riskColor.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, riskColor.copy(alpha = 0.6f)),
                ) {
                    Text(
                        text = riskLabel.asString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = riskColor,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            // One sentence with both speeds in it, not "1/f_eff Guideline: 1/50s (Actual: 1/60s)".
            LearnText.shutterLine(coachReport.safeShutterSpeed, currentImage.exifData?.shutterSpeed)?.let { line ->
                Text(
                    text = line.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color.White,
                    modifier = Modifier.fillMaxWidth().testTag("shutter_speed_comparison"),
                )
            }

            LearnText.noiseRemark(currentImage.scanResult?.noiseLevel, currentImage.exifData?.iso)?.let { remark ->
                Text(
                    text = remark.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = ScoreWarn,
                    modifier = Modifier.fillMaxWidth().testTag("coach_noise_remark"),
                )
            }

            coachReport.recommendedEvCompensation?.let { ev ->
                val evFormatted = if (ev > 0.0) "+$ev" else "$ev"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("recommended_ev"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.label_suggested_ev),
                        style = MaterialTheme.typography.bodySmall,
                        color = Zinc400,
                    )
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = ScoreWarn.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(1.dp, ScoreWarn.copy(alpha = 0.5f)),
                    ) {
                        Text(
                            text = stringResource(R.string.label_ev_value, evFormatted),
                            style = MaterialTheme.typography.labelSmall,
                            color = ScoreWarn,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
            }

            Text(
                text = stringResource(R.string.label_technique_guidance),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = Zinc400,
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("coaching_tips_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (coachReport.tips.isEmpty()) {
                    Text(
                        text = stringResource(R.string.learn_clean_capture),
                        style = MaterialTheme.typography.bodySmall,
                        color = HintText,
                    )
                } else {
                    for (tip in coachReport.tips) {
                        CoachingTipItem(tip = tip)
                    }
                }
            }
        }
    }
}

@Composable
private fun CoachingTipItem(
    tip: CoachingTip,
    modifier: Modifier = Modifier,
) {
    val (chipColor, chipBg) = when (tip.severity) {
        TipSeverity.CRITICAL -> ScoreBad to ScoreBad.copy(alpha = 0.15f)
        TipSeverity.WARNING -> ScoreWarn to ScoreWarn.copy(alpha = 0.15f)
        TipSeverity.INFO -> BrandAccent to BrandAccent.copy(alpha = 0.15f)
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Zinc950,
        border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
        modifier = modifier
            .fillMaxWidth()
            .testTag("coaching_tip_item"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = tip.title.asString(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("tip_title"),
                )

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = chipBg,
                    border = androidx.compose.foundation.BorderStroke(1.dp, chipColor.copy(alpha = 0.5f)),
                    modifier = Modifier.testTag("tip_severity_chip"),
                ) {
                    Text(
                        text = tip.severity.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = chipColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }

            Text(
                text = tip.description.asString(),
                style = MaterialTheme.typography.bodySmall,
                color = Zinc400,
                modifier = Modifier.testTag("tip_description"),
            )

            Text(
                text = "💡 ${tip.recommendation.asString()}",
                style = MaterialTheme.typography.bodySmall,
                color = Zinc200,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.testTag("tip_recommendation"),
            )
        }
    }
}
