package com.photoselectortoolbox.ui.inspect

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.photoselectortoolbox.data.model.ImageItem
import com.photoselectortoolbox.domain.format.ExifFormatter
import com.photoselectortoolbox.ui.theme.BrandAccent
import com.photoselectortoolbox.ui.theme.Zinc400
import com.photoselectortoolbox.ui.theme.Zinc800
import com.photoselectortoolbox.ui.theme.Zinc900
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.res.stringResource
import com.photoselectortoolbox.R
import com.photoselectortoolbox.ui.text.asString

// ── EXIF Technical Readout ────────────────────────────────────────────────────

@Composable
internal fun ExifTechnicalReadoutCard(
    currentImage: ImageItem,
    modifier: Modifier = Modifier,
) {
    val exif = currentImage.exifData
    val rows = ExifFormatter.detailRows(exif)

    val unknown = stringResource(R.string.label_unknown)
    val formattedDate = remember(currentImage.lastModified, unknown) {
        if (currentImage.lastModified > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(currentImage.lastModified))
        } else {
            unknown
        }
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Zinc900),
        border = androidx.compose.foundation.BorderStroke(1.dp, Zinc800),
        modifier = modifier
            .fillMaxWidth()
            .testTag("exif_readout"),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.CameraAlt,
                    contentDescription = null,
                    tint = BrandAccent,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.title_exif_readout),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
            }

            for ((label, value) in rows) {
                ReadoutRow(label = label.asString(), value = value.asString())
            }

            ReadoutRow(label = stringResource(R.string.label_exif_dimensions), value = "${currentImage.imageWidth} × ${currentImage.imageHeight}")
            ReadoutRow(label = stringResource(R.string.label_exif_timestamp), value = formattedDate)
        }
    }
}

@Composable
private fun ReadoutRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Zinc400,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            fontWeight = FontWeight.Medium,
        )
    }
}
