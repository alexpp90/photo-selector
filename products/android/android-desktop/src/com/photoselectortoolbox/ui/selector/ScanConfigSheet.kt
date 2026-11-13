package com.photoselectortoolbox.ui.selector

import com.photoselectortoolbox.ui.components.handCursor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.domain.scoring.ScanMeasures
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandOnAccent
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.theme.StudioPanel
import androidx.compose.ui.res.stringResource
import com.photoselectortoolbox.R

/**
 * Configuration for which analyses to run during a scan: the sheet's four rows. [measures] is what
 * the scan computes (REQ-AND-ALGO.07); an unticked row is not computed.
 */
data class ScanConfig(
    val sharpness: Boolean = true,
    val noise: Boolean = true,
    val highlightClipping: Boolean = true,
    val shadowClipping: Boolean = true,
) {
    val measures: ScanMeasures
        get() = ScanMeasures(sharpness, noise, highlightClipping, shadowClipping)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanConfigSheet(
    onStartScan: (ScanConfig) -> Unit,
    onDismiss: () -> Unit,
    isExpanded: Boolean,
) {
    var config by remember { mutableStateOf(ScanConfig()) }

    if (isExpanded) {
        // Dialog for tablet / DeX
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = stringResource(R.string.title_scan_configuration),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                ScanConfigContent(
                    config = config,
                    onConfigChange = { config = it },
                )
            },
            confirmButton = {
                Button(
                    modifier = Modifier.handCursor(),
                    onClick = { onStartScan(config) },
                    enabled = config.sharpness || config.noise ||
                        config.highlightClipping || config.shadowClipping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandAccent,
                        contentColor = BrandOnAccent,
                    ),
                ) {
                    Text(stringResource(R.string.action_start_scan))
                }
            },
            dismissButton = {
                TextButton(modifier = Modifier.handCursor(), onClick = onDismiss) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            containerColor = StudioCard,
            shape = RoundedCornerShape(16.dp),
        )
    } else {
        // Bottom sheet for phone
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            containerColor = StudioPanel,
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
            ) {
                Text(
                    text = stringResource(R.string.title_scan_configuration),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(16.dp))

                ScanConfigContent(
                    config = config,
                    onConfigChange = { config = it },
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { onStartScan(config) },
                    modifier = Modifier.fillMaxWidth().handCursor(),
                    enabled = config.sharpness || config.noise ||
                        config.highlightClipping || config.shadowClipping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandAccent,
                        contentColor = BrandOnAccent,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(stringResource(R.string.action_start_scan))
                }
            }
        }
    }
}

@Composable
private fun ScanConfigContent(
    config: ScanConfig,
    onConfigChange: (ScanConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.label_analysis_types),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        AnalysisCheckbox(
            label = stringResource(ScanSheetText.FOCUS),
            checked = config.sharpness,
            onCheckedChange = { onConfigChange(config.copy(sharpness = it)) },
        )

        AnalysisCheckbox(
            label = stringResource(ScanSheetText.NOISE),
            checked = config.noise,
            onCheckedChange = { onConfigChange(config.copy(noise = it)) },
        )

        AnalysisCheckbox(
            label = stringResource(ScanSheetText.HIGHLIGHTS),
            checked = config.highlightClipping,
            onCheckedChange = { onConfigChange(config.copy(highlightClipping = it)) },
        )

        AnalysisCheckbox(
            label = stringResource(ScanSheetText.SHADOWS),
            checked = config.shadowClipping,
            onCheckedChange = { onConfigChange(config.copy(shadowClipping = it)) },
        )
    }
}

/** The height of a scan-sheet row: one hit target (AD-17). */
internal val ScanRowMinHeight = 48.dp

/**
 * The scan sheet's rows say what each check finds, never how (usability panel 2026-10-24: "Detect
 * focus quality via Laplacian variance" read as data, not explanation). One short label each, no
 * method subtitle (`ai/rules/ui-text.md`: do not explain).
 */
internal object ScanSheetText {
    val FOCUS = R.string.label_scan_focus
    val NOISE = R.string.label_scan_noise
    val HIGHLIGHTS = R.string.label_scan_highlights
    val SHADOWS = R.string.label_scan_shadows
    val all = listOf(FOCUS, NOISE, HIGHLIGHTS, SHADOWS)
}

@Composable
private fun AnalysisCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    // The whole row is the target, 48 dp tall at least (AD-17): with one line of text the
    // padded row was 36 dp, and its neighbours cut the framework's touch-target expansion to a
    // 40 dp pitch (device run 2026-11-13, after the method subtitle went).
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = ScanRowMinHeight)
            .testTag("scan_row")
            .handCursor()
            .selectable(
                selected = checked,
                onClick = { onCheckedChange(!checked) },
                role = Role.Checkbox,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            modifier = Modifier.handCursor(),
            checked = checked,
            onCheckedChange = null, // handled by row click
            colors = CheckboxDefaults.colors(
                checkedColor = BrandAccent,
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
