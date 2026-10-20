package com.photoselectortoolbox.ui.selector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.BrandOnAccent
import com.photoselectortoolbox.ui.theme.StudioCard
import com.photoselectortoolbox.ui.theme.StudioPanel

/**
 * Configuration for which analyses to run during a scan.
 */
data class ScanConfig(
    val sharpness: Boolean = true,
    val noise: Boolean = true,
    val highlightClipping: Boolean = true,
    val shadowClipping: Boolean = true,
)

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
                    text = "Scan Configuration",
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
                    onClick = { onStartScan(config) },
                    enabled = config.sharpness || config.noise ||
                        config.highlightClipping || config.shadowClipping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandAccent,
                        contentColor = BrandOnAccent,
                    ),
                ) {
                    Text("Start Scan")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
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
                    text = "Scan Configuration",
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
                    modifier = Modifier.fillMaxWidth(),
                    enabled = config.sharpness || config.noise ||
                        config.highlightClipping || config.shadowClipping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrandAccent,
                        contentColor = BrandOnAccent,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("Start Scan")
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
            text = "Analysis Types",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(4.dp))

        AnalysisCheckbox(
            label = "Sharpness",
            description = "Detect focus quality via Laplacian variance",
            checked = config.sharpness,
            onCheckedChange = { onConfigChange(config.copy(sharpness = it)) },
        )

        AnalysisCheckbox(
            label = "Noise Level",
            description = "Estimate sensor noise via median absolute deviation",
            checked = config.noise,
            onCheckedChange = { onConfigChange(config.copy(noise = it)) },
        )

        AnalysisCheckbox(
            label = "Highlight Clipping",
            description = "Detect overexposed (blown-out) areas",
            checked = config.highlightClipping,
            onCheckedChange = { onConfigChange(config.copy(highlightClipping = it)) },
        )

        AnalysisCheckbox(
            label = "Shadow Clipping",
            description = "Detect underexposed (crushed black) areas",
            checked = config.shadowClipping,
            onCheckedChange = { onConfigChange(config.copy(shadowClipping = it)) },
        )
    }
}

@Composable
private fun AnalysisCheckbox(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = checked,
                onClick = { onCheckedChange(!checked) },
                role = Role.Checkbox,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null, // handled by row click
            colors = CheckboxDefaults.colors(
                checkedColor = BrandAccent,
                uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
