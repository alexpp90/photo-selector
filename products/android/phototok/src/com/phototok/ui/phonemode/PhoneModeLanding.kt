package com.phototok.ui.phonemode

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.photoselector.core.storage.SourceKind
import com.phototok.data.model.RecentPath

/**
 * Landing screen: hero branding, source pickers (Phone Camera / SD Card / Folder or Cloud),
 * recent folders with source-kind badges, unmounted dimming, and seen/total progress.
 */
@Composable
fun PhoneModeLanding(
    sourceFolderUri: String?,
    sourceFolderName: String,
    collectionFolderName: String,
    isLoading: Boolean,
    emptyFolderMessage: String? = null,
    missingGrantPrompt: Boolean = false,
    onReselectFolder: (() -> Unit)? = null,
    onSelectCamera: () -> Unit = {},
    onSelectSdCard: ((Uri?) -> Unit) -> Unit = { launchPicker -> launchPicker(null) },
    onSelectSource: (Uri) -> Unit,
    onSelectCollection: (Uri) -> Unit,
    onStart: () -> Unit,
    recentPaths: List<RecentPath> = emptyList(),
    recentPathsEnabled: Boolean = true,
    recentPathsCount: Int = 3,
    unmountedRecentUris: Set<String> = emptySet(),
    folderSeenProgress: Map<String, Pair<Int, Int>> = emptyMap(),
    onSelectRecentPath: (RecentPath) -> Unit = {},
) {
    val sourcePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? -> uri?.let { onSelectSource(it) } }

    val hasSourceFolder = sourceFolderUri != null
    val colors = MaterialTheme.colorScheme

    val scrollState = rememberScrollState()

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // Window height drives the hero size so all three source rows fit on small phones.
        val logoSize = landingLogoSize(maxHeight)
        val showPhotoFan = maxHeight >= LANDING_FAN_MIN_HEIGHT

        // Decorative background blurs
        Box(
            modifier = Modifier
                .size(300.dp)
                .offset(x = (-100).dp, y = 150.dp)
                .background(colors.primary.copy(alpha = 0.08f), CircleShape)
                .blur(80.dp),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(300.dp)
                .offset(x = 100.dp, y = (-100).dp)
                .background(colors.tertiary.copy(alpha = 0.06f), CircleShape)
                .blur(80.dp),
        )

        // Scrollable content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp)
                .padding(top = 16.dp, bottom = 100.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Hero branding ────────────────────────────────────────
            Image(
                painter = painterResource(id = com.phototok.R.mipmap.ic_launcher_foreground),
                contentDescription = "Logo",
                modifier = Modifier.size(logoSize),
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Photo-Tok",
                style = MaterialTheme.typography.headlineMedium,
                color = colors.onSurface,
            )
            Text(
                text = "SWIPE. SELECT. SNAP.",
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 3.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                color = colors.onSurfaceVariant,
            )

            if (showPhotoFan) {
                Spacer(modifier = Modifier.height(24.dp))

                // ── Sample Image Mockup Cards ────────────────────────────
                Row(
                    horizontalArrangement = Arrangement.spacedBy((-16).dp),
                    modifier = Modifier.padding(vertical = 12.dp)
                ) {
                    Surface(
                        modifier = Modifier
                            .size(width = 80.dp, height = 110.dp)
                            .graphicsLayer { rotationZ = -8f }
                            .border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                        shape = RoundedCornerShape(8.dp),
                        color = colors.surfaceVariant
                    ) {
                        Image(
                            painter = painterResource(id = com.phototok.R.drawable.placeholder_landscape),
                            contentDescription = "Sample Landscape",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .size(width = 90.dp, height = 120.dp)
                            .graphicsLayer { translationY = -8f }
                            .border(2.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                            .shadow(8.dp, RoundedCornerShape(8.dp)),
                        shape = RoundedCornerShape(8.dp),
                        color = colors.surfaceVariant
                    ) {
                        Image(
                            painter = painterResource(id = com.phototok.R.drawable.placeholder_portrait),
                            contentDescription = "Sample Lens",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Surface(
                        modifier = Modifier
                            .size(width = 80.dp, height = 110.dp)
                            .graphicsLayer { rotationZ = 8f }
                            .border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                        shape = RoundedCornerShape(8.dp),
                        color = colors.surfaceVariant
                    ) {
                        Image(
                            painter = painterResource(id = com.phototok.R.drawable.placeholder_architecture),
                            contentDescription = "Sample Architecture",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(if (showPhotoFan) 24.dp else 16.dp))

            // ── Source selection ──────────────────────────────────────
            Text(
                text = "SELECT SOURCE",
                style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, bottom = 8.dp),
            )

            SourceCard(
                icon = Icons.Default.PhotoCamera,
                label = "Phone Camera",
                supportingText = "Default camera roll (DCIM/Camera)",
                isActive = false,
                onClick = onSelectCamera,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("landing_phone_camera_card"),
            )

            Spacer(modifier = Modifier.height(8.dp))

            SourceCard(
                icon = Icons.Default.SdCard,
                label = "SD Card / USB Reader",
                supportingText = "Removable camera card or USB drive",
                isActive = false,
                onClick = { onSelectSdCard { seedUri -> sourcePickerLauncher.launch(seedUri) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("landing_sd_card_card"),
            )

            Spacer(modifier = Modifier.height(8.dp))

            SourceCard(
                icon = Icons.Default.FolderOpen,
                label = "Folder or Cloud",
                supportingText = "Phone storage, SD card or cloud",
                isActive = hasSourceFolder,
                onClick = { sourcePickerLauncher.launch(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("landing_custom_folder_card"),
            )

            if (missingGrantPrompt) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("missing_grant_prompt_card"),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.errorContainer.copy(alpha = 0.85f),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.FolderOpen,
                                contentDescription = null,
                                tint = colors.onErrorContainer,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Folder Access Needed",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onErrorContainer,
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Access to this folder was lost.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onErrorContainer.copy(alpha = 0.9f),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clickable {
                                    onReselectFolder?.invoke() ?: sourcePickerLauncher.launch(null)
                                }
                                .testTag("reselect_folder_button"),
                            shape = RoundedCornerShape(8.dp),
                            color = colors.onErrorContainer,
                        ) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "Re-select Folder",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = colors.errorContainer,
                                )
                            }
                        }
                    }
                }
            } else if (emptyFolderMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("empty_folder_warning_card"),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.errorContainer.copy(alpha = 0.85f),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = colors.onErrorContainer,
                            modifier = Modifier.size(24.dp),
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "No Photos Found",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = colors.onErrorContainer,
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = emptyFolderMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onErrorContainer.copy(alpha = 0.9f),
                            )
                        }
                    }
                }
            } else if (hasSourceFolder && sourceFolderName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Current: $sourceFolderName",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary.copy(alpha = 0.8f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // ── Recent folders (quick re-select) ─────────────────────
            val recentToShow = recentPaths.take(recentPathsCount.coerceAtLeast(1))
            if (recentPathsEnabled && recentToShow.isNotEmpty()) {
                Spacer(modifier = Modifier.height(32.dp))
                Text(
                    text = "RECENT FOLDERS",
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, bottom = 12.dp),
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    recentToShow.forEach { recent ->
                        val isUnmounted = recent.uri in unmountedRecentUris
                        val progressPair = folderSeenProgress[recent.uri]
                        val progressText = progressPair?.let { (seen, total) ->
                            "$seen / $total seen"
                        }
                        RecentPathRow(
                            recent = recent,
                            isUnmounted = isUnmounted,
                            progressText = progressText,
                            onClick = { onSelectRecentPath(recent) },
                        )
                    }
                }
            }

            // ── CTA button (Only shown when there is an active/valid session to resume) ──
            val canResume = hasSourceFolder && !isLoading && emptyFolderMessage == null
            if (canResume) {
                Spacer(modifier = Modifier.height(32.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clickable(enabled = canResume) { onStart() }
                        .testTag("landing_start_browsing_button"),
                    shape = RoundedCornerShape(12.dp),
                    color = colors.primaryContainer,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (sourceFolderName.isNotEmpty()) "Resume $sourceFolderName" else "Start Browsing",
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onPrimaryContainer,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            tint = colors.onPrimaryContainer,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Decorative scroll affordance: fades the content into the background while more follows.
        if (scrollState.canScrollForward) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .fillMaxWidth()
                    .height(24.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, colors.background),
                        ),
                    )
                    .testTag("landing_scroll_fade"),
            )
        }
    }
}

/** Below this window height the photo fan is dropped; below [LANDING_LARGE_LOGO_MIN_HEIGHT] the logo shrinks. */
private val LANDING_FAN_MIN_HEIGHT = 760.dp
private val LANDING_LARGE_LOGO_MIN_HEIGHT = 700.dp

private fun landingLogoSize(windowHeight: Dp): Dp =
    if (windowHeight < LANDING_LARGE_LOGO_MIN_HEIGHT) 56.dp else 72.dp

@Composable
private fun SourceCard(
    icon: ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val borderColor = if (isActive) colors.primaryContainer else colors.outlineVariant
    val shape = RoundedCornerShape(12.dp)

    Surface(
        modifier = modifier
            .clip(shape)
            .border(1.dp, borderColor, shape)
            // One click target; descendants merge so the row is announced once ("label, supporting").
            .clickable(role = Role.Button, onClick = onClick),
        shape = shape,
        color = if (isActive) colors.primaryContainer.copy(alpha = 0.1f) else colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 64.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        (if (isActive) colors.primary else colors.secondary).copy(alpha = 0.12f),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    // Decorative: the row's text already names it.
                    contentDescription = null,
                    tint = if (isActive) colors.primary else colors.secondary,
                    modifier = Modifier.size(24.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                )
                if (supportingText != null) {
                    Text(
                        text = supportingText,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

private fun sourceKindBadgeLabel(kind: SourceKind): String = when (kind) {
    SourceKind.PHONE_LIBRARY -> "Phone"
    SourceKind.REMOVABLE -> "SD / USB"
    SourceKind.CAMERA_MTP -> "Camera"
    SourceKind.CLOUD_OTHER -> "Cloud"
}

@Composable
private fun RecentPathRow(
    recent: RecentPath,
    isUnmounted: Boolean = false,
    progressText: String? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isUnmounted) 0.55f else 1f)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                tint = if (isUnmounted) colors.onSurfaceVariant else colors.primary,
                modifier = Modifier.size(20.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recent.name.ifEmpty { "Folder" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = when {
                    isUnmounted -> "Not connected"
                    progressText != null -> progressText
                    else -> null
                }
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isUnmounted) colors.error else colors.onSurfaceVariant,
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = colors.surfaceContainerHighest,
            ) {
                Text(
                    text = sourceKindBadgeLabel(recent.kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
