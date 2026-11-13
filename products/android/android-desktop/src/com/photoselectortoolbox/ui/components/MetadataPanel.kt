package com.photoselectortoolbox.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.photoselector.core.model.ExifData
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import com.photoselectortoolbox.R
import com.photoselectortoolbox.ui.text.asString

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataPanel(
    exifData: ExifData?,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    if (exifData == null) return

    if (compact) {
        FlowRow(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            exifData.iso?.let { iso ->
                MetadataValue(value = "ISO $iso") // ui-text: ignore (a unit, the same in every language)
            }
            exifData.shutterSpeed?.let { speed ->
                MetadataValue(value = formatShutterSpeed(speed))
            }
            exifData.aperture?.let { aperture ->
                MetadataValue(value = "f/%.1f".format(java.util.Locale.US, aperture))
            }
            exifData.focalLength?.let { focal ->
                MetadataValue(value = "${focal.toInt()}mm")
            }
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            exifData.iso?.let { iso ->
                MetadataRow(label = stringResource(R.string.label_exif_iso), value = iso.toString())
            }
            exifData.shutterSpeed?.let { speed ->
                MetadataRow(label = stringResource(R.string.label_exif_shutter_speed), value = formatShutterSpeed(speed))
            }
            exifData.aperture?.let { aperture ->
                MetadataRow(label = stringResource(R.string.label_exif_aperture), value = "f/%.1f".format(java.util.Locale.US, aperture))
            }
            exifData.focalLength?.let { focal ->
                MetadataRow(label = stringResource(R.string.label_exif_focal_length), value = "${focal.toInt()}mm")
            }
            exifData.focalLength35mm?.let { focal35 ->
                MetadataRow(label = stringResource(R.string.label_exif_focal_35mm), value = "${focal35.toInt()}mm")
            }
            if (exifData.lens != "Unknown") {
                MetadataRow(label = stringResource(R.string.label_exif_lens), value = exifData.lens)
            }
        }
    }
}

@Composable
private fun MetadataRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun MetadataValue(
    value: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = value,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier,
    )
}

private fun formatShutterSpeed(speed: Double): String {
    return if (speed < 1.0 && speed > 0.0) {
        val denominator = (1.0 / speed).toInt()
        "1/${denominator}s"
    } else {
        "${speed}s"
    }
}
