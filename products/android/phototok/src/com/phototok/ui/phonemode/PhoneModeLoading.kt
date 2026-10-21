package com.phototok.ui.phonemode

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phototok.R

/**
 * Shown while a (possibly large / remote) folder is loading, so the user stays on a
 * branded loading view with the sample-photo mockups instead of bouncing back to the
 * landing screen. Replaced by the viewer as soon as the first image is available.
 */
@Composable
fun PhoneModeLoading(
    folderName: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
        contentAlignment = Alignment.Center,
    ) {
        // Decorative background blur
        Box(
            modifier = Modifier
                .size(300.dp)
                .background(colors.primary.copy(alpha = 0.08f), CircleShape)
                .blur(80.dp),
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Sample image mockup cards (same as the landing hero)
            Row(
                horizontalArrangement = Arrangement.spacedBy((-16).dp),
                modifier = Modifier.padding(vertical = 12.dp),
            ) {
                SampleCard(R.drawable.placeholder_landscape, -8f, 0f, 80, 110, 0.15f)
                SampleCard(R.drawable.placeholder_portrait, 0f, -8f, 90, 120, 0.25f)
                SampleCard(R.drawable.placeholder_architecture, 8f, 0f, 80, 110, 0.15f)
            }

            Spacer(modifier = Modifier.height(40.dp))

            val loadingName = stringResource(R.string.cd_loading)
            CircularProgressIndicator(
                color = colors.primary,
                modifier = Modifier.semantics { contentDescription = loadingName },
            )

            if (folderName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = folderName,
                    style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.sp),
                    color = colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun SampleCard(
    drawableId: Int,
    rotation: Float,
    translateY: Float,
    width: Int,
    height: Int,
    borderAlpha: Float,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier
            .size(width = width.dp, height = height.dp)
            .graphicsLayer {
                rotationZ = rotation
                translationY = translateY
            }
            .border(2.dp, Color.White.copy(alpha = borderAlpha), RoundedCornerShape(8.dp))
            .shadow(if (translateY != 0f) 8.dp else 0.dp, RoundedCornerShape(8.dp)),
        shape = RoundedCornerShape(8.dp),
        color = colors.surfaceVariant,
    ) {
        Image(
            painter = painterResource(id = drawableId),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
