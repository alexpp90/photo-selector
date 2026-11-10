package com.photoselectortoolbox.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.R

/**
 * The Settings row that opens the third-party licence notices of what this build bundles
 * (`REQ-AND-PLATFORM.11`). The text is `res/raw/third_party_notices.txt`, generated from the
 * release classpath by `scripts/android_licenses.py`; it is read in the app, so it works offline.
 */
@Composable
fun LicencesRow() {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsClickItem(
        title = "Open-source licences",
        onClick = { open = true },
        icon = Icons.Default.Description,
    )
    if (open) LicencesDialog(onDismiss = { open = false })
}

@Composable
fun LicencesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val notices = remember { context.resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .widthIn(max = 640.dp)
            .testTag("licences_dialog"),
        title = { Text("Open-source licences") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = notices,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("licences_text"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("licences_close")) {
                Text("Close")
            }
        },
    )
}
