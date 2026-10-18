# PhotoTok Design Integration Spec

Maps each element from the 4 HTML mockups to the Kotlin files that need to change. This is the implementation guide.

---

## 1. Color System Overhaul

**File:** `ui/theme/Color.kt`

The mockups use a Material 3 tonal palette centered on a lavender primary instead of the current Indigo/Zinc. Replace the entire palette.

| Token | Current | New (from mockup) |
|-------|---------|-------------------|
| Primary | `#6366F1` (Indigo500) | `#C0C1FF` |
| Primary Container | `#4F46E5` (Indigo600) | `#8083FF` |
| On Primary | `Color.White` | `#1000A9` |
| On Primary Container | `Zinc50` | `#0D0096` |
| Surface | `#18181B` (Zinc900) | `#131316` |
| Surface Container | `#27272A` (Zinc800) | `#1F1F22` |
| Surface Container High | `#3F3F46` (Zinc700) | `#2A2A2D` |
| Surface Container Low | `#18181B` (Zinc900) | `#1B1B1E` |
| Surface Container Lowest | `#09090B` (Zinc950) | `#0E0E11` |
| Surface Variant | | `#353438` |
| Outline | `#3F3F46` (Zinc700) | `#908FA0` |
| Outline Variant | `#52525B` (Zinc600) | `#464554` |
| On Surface | `Zinc50` | `#E4E1E6` |
| On Surface Variant | `Zinc400` | `#C7C4D7` |
| Secondary | | `#C8C6C9` |
| Secondary Container | | `#47464A` |
| Tertiary | | `#C7C5CE` |
| Error | `#EF4444` | `#FFB4AB` |
| Error Container | `#93000A` | `#93000A` (same) |
| On Error Container | | `#FFDAD6` |
| Inverse Surface | | `#E4E1E6` |
| Surface Tint | | `#C0C1FF` |

**New colors to add:**

```kotlin
// M3 Tonal Palette
val Primary = Color(0xFFC0C1FF)
val PrimaryContainer = Color(0xFF8083FF)
val OnPrimary = Color(0xFF1000A9)
val OnPrimaryContainer = Color(0xFF0D0096)
val PrimaryFixed = Color(0xFFE1E0FF)
val PrimaryFixedDim = Color(0xFFC0C1FF)

val Secondary = Color(0xFFC8C6C9)
val SecondaryContainer = Color(0xFF47464A)
val OnSecondary = Color(0xFF303033)
val OnSecondaryContainer = Color(0xFFB6B4B8)

val Tertiary = Color(0xFFC7C5CE)
val TertiaryContainer = Color(0xFF77767E)

val SurfaceDim = Color(0xFF131316)
val SurfaceBright = Color(0xFF39393C)
val SurfaceContainerLowest = Color(0xFF0E0E11)
val SurfaceContainerLow = Color(0xFF1B1B1E)
val SurfaceContainer = Color(0xFF1F1F22)
val SurfaceContainerHigh = Color(0xFF2A2A2D)
val SurfaceContainerHighest = Color(0xFF353438)

val OutlineColor = Color(0xFF908FA0)
val OutlineVariant = Color(0xFF464554)
val OnSurfaceVariant = Color(0xFFC7C4D7)
```

**File:** `ui/theme/Theme.kt`

Update `DarkColorScheme` to use the new palette values. Also update `window.statusBarColor` and `window.navigationBarColor` to `SurfaceContainerLowest` (`#0E0E11`).

**File:** `ui/theme/Type.kt`

The mockups specify Inter font. Add Inter via Google Fonts in `build.gradle` or use system sans-serif (already close enough). The type scale is already M3-aligned — keep it, but bump `headlineMedium.fontWeight` to `FontWeight.Bold` (mockup uses 700 for headlines).

---

## 2. Landing Screen Redesign

**File:** `ui/phonemode/PhoneModeLanding.kt`

### Current → New

The mockup replaces the vertical column of `FolderPickerCard` rows with a centered hero + 3-column source grid + filter chips + CTA.

### Layout structure (top to bottom):

1. **Hero section** (centered):
   - 80×80dp rounded container (`RoundedCornerShape(16.dp)`) with `primaryContainer.copy(alpha=0.2f)` background
   - `photo_library` Material Symbol inside (48dp, filled variant, `primary` tint)
   - "PhotoTok" headline (`headlineMedium`, `onSurface`)
   - "Swipe. Select. Snap." subtitle (`labelSmall`, `onSurfaceVariant`, uppercase, tracking wide)

2. **Source selection**:
   - Section label: "SELECT SOURCE" (`labelLarge`, `onSurfaceVariant`, uppercase, tracking)
   - **3-column grid** (`LazyVerticalGrid` or `Row` with equal weights):
     - Each card: `Surface` with `surfaceContainer` fill, `outlineVariant` border, `RoundedCornerShape(12.dp)`
     - Content: centered icon (Material Symbol) + label (`labelMedium`)
     - Active state: border → `primary`, background → `primary.copy(alpha=0.1f)`
     - Cards: `folder_open` / "Local Folder", `sd_card` / "SD Card", `cloud` / "Cloud Storage (SAF)"
   - **Remove** the separate `FolderPickerCard` composable and `externalVolumes` list
   - **Remove** the separate Collection folder picker card — collection is now in Settings

3. **Format filter**:
   - Section label: "FORMAT FILTER"
   - **Pill chips** (`RoundedCornerShape(50)`) instead of `FilterChip`:
     - Active: `primaryContainer` fill, `onPrimaryContainer` text
     - Inactive: `surfaceContainer` fill, `onSurfaceVariant` text, `outlineVariant` border
   - Options: "Both" (default), "RAW", "JPG"

4. **CTA button**:
   - Full-width, 56dp height, `primaryContainer` fill, `onPrimaryContainer` text
   - `RoundedCornerShape(12.dp)`
   - Text: "Start Browsing" + `arrow_forward` icon
   - Shadow: `shadow(8.dp, primaryContainer.copy(alpha=0.1f))`

5. **Subtitle**: "Connect a source to begin your high-velocity curation." (`bodyMedium`, subdued)

### Decorative background

Add to the `Box` parent:
```kotlin
// Two large blurred circles behind content
Box(Modifier.fillMaxSize()) {
    Box(
        Modifier
            .offset(x = (-150).dp, y = 200.dp)
            .size(300.dp)
            .background(
                primary.copy(alpha = 0.1f),
                CircleShape
            )
            .blur(60.dp)
    )
    // Second circle bottom-right with tertiary tint
}
```

### Bottom navigation bar (new component)

**New file:** `ui/components/BottomNavBar.kt`

The viewer bottom bar (`ViewerBottomBar`) shows 3 persistent action items paired with visible `10.sp` (`labelSmall`) micro-labels:

```
[FolderOpen: Sources]  [CollectionsBookmark: Selection]  [RestoreFromTrash: Revert]
```

- Container: `surfaceContainerLowest.copy(alpha=0.8f)`, backdrop blur, `RoundedCornerShape(topStart=12.dp, topEnd=12.dp)`
- Active item: `primaryContainer` circle background, `onPrimaryContainer` icon (filled variant)
- Inactive items: `secondary` tint; `Revert` uses `RestoreFromTrash` (trash bin with upward restore arrow) so it is visually distinct from the top-bar `Sync` Reload icon
- Padding: 32dp horizontal, 32dp bottom (for nav bar insets)

**Integrate in:** `ui/phonemode/PhoneModeScreen.kt` — wrap the content in a `Scaffold` or add the nav bar as a fixed overlay at `Alignment.BottomCenter`.

### Top app bar

The top app bar shows the "PhotoTok" logo + wordmark in `primary` color (bold) on the left, and four action buttons on the right: `Sync` (Reload folder), `HelpOutline` (Controls guide), `Info` (EXIF metadata toggle), and `Settings`. In landscape orientation, the left and right side panels render these actions as `SidePanelButton` items with visible `9.sp` micro-labels (`Sources`, `Selection`, `Revert`, `Help`, `Reload`, `Info`, `Settings`).

**Add to:** `PhoneModeScreen.kt` — fixed `Row` at `Alignment.TopStart` with statusBarsPadding.

---

## 3. Settings Bottom Sheet Redesign

**File:** `ui/settings/SettingsScreen.kt` + `ui/settings/SettingsItem.kt`

### Structure (from mockup)

The bottom sheet has a drag handle, header row with "Settings" headline + close button, then 3 sections.

**Sheet container** (in `PhoneModeScreen.kt`):
- `surfaceContainerLow` background
- Top corners: `RoundedCornerShape(32.dp)` (currently uses default)
- Add drag handle: 32×4dp pill, `outlineVariant`, centered at top with 16dp top padding

**Section headers** (update `SettingsSection`):
- Current: just label text in `Indigo500`
- New: `Row` with Material Symbol icon + label, both in `primary` color
- Icon/label pairs: `database` + "STORAGE", `browse_gallery` + "BROWSING", `info` + "ABOUT"
- Text: `labelLarge`, uppercase, wide tracking

**Section cards** (update `SettingsSection` card):
- Current: `Zinc800` fill, `RoundedCornerShape(16.dp)`
- New: `surfaceContainerHigh` fill, `RoundedCornerShape(12.dp)`, `outlineVariant.copy(alpha=0.3f)` border
- Dividers between items: `outlineVariant.copy(alpha=0.2f)` instead of `Zinc700.copy(alpha=0.5f)`

**Toggle items** (update `SettingsToggleItem`):
- Remove leading icon (mockup shows no icons on individual toggle rows)
- Title: `labelLarge`, `onSurface`
- Description: `labelMedium`, `onSurfaceVariant`
- The M3 `Switch` already maps well — update colors to use new palette

**Click items** (update `SettingsClickItem`):
- Source folder row: show folder icon on right in `primary` tint (not left)
- "Change" trailing button with `primaryContainer` color + chevron

**Subfolder input**:
- `surfaceDim` background on input field
- `outlineVariant` border
- Focus ring: `primary`

**About section**:
- Version row: label/value in same row
- Source code: `primaryContainer` colored link with `open_in_new` icon

**Footer**:
- "PhotoTok" + "Professional Curation Engine" centered, 30% opacity

### Settings moved from Landing to Settings

The file type filter (RAW/JPG/Both) now appears ONLY on the landing screen as format filter chips. **Remove** the radio button version from `SettingsScreen.kt`. Also remove the "Double-Tap Action" radio buttons from Settings — keep them, but move to the Browsing section as a simpler toggle or keep as-is since the mockup doesn't show them.

---

## 4. Viewer Redesign

**File:** `ui/phonemode/PhoneModeViewer.kt`

### Swipe-to-delete feedback (major visual change)

**Current:** Small growing red circle at `Alignment.CenterEnd` with delete icon, 48-64dp.

**New (from mockup):** Large dramatic trash indicator:
- Positioned at `Alignment.CenterEnd`, right padding 16dp
- Outer glow: 96dp circle, `errorContainer.copy(alpha=0.4f)`, scale 1.25x, pulsing animation, blur
- Inner circle: 80dp, `errorContainer` fill, `onErrorContainer` icon tint
- Icon: `delete` (filled variant), 36dp
- Scale: 1.5x (grows during swipe)
- "DISCARD" label below: `labelLarge`, `error` color, uppercase, wide tracking
- `boxShadow` equivalent: `Modifier.shadow(20.dp, CircleShape, ambientColor = ErrorRed.copy(0.3f))`

**Pulsing animation:**
```kotlin
val pulseAnim = rememberInfiniteTransition()
val pulseScale by pulseAnim.animateFloat(
    initialValue = 1f,
    targetValue = 1.1f,
    animationSpec = infiniteRepeatable(
        animation = tween(2000),
        repeatMode = RepeatMode.Reverse,
    ),
)
```

### Card rotation during swipe

**Current:** Image translates horizontally only.

**New:** Image rotates as it translates:
```kotlin
.graphicsLayer {
    translationX = horizontalDragOffset
    val progress = (abs(horizontalDragOffset) / abs(deleteThreshold)).coerceIn(0f, 1f)
    rotationZ = -2f * progress  // slight counter-clockwise tilt
    alpha = 1f - progress * 0.3f
}
```

### Swipe direction chevrons

Add subtle left-pointing chevrons on the left edge during swipe:
- Two stacked `chevron_left` icons, 30% opacity
- Only visible when `horizontalDragOffset < -40f`

### Gesture hint pill (bottom center)

**New element** — shown briefly on first few images:
- Position: `Alignment.BottomCenter`, above bottom nav, centered
- Container: `surfaceContainerHigh.copy(alpha=0.8f)`, backdrop blur, `outlineVariant.copy(0.3f)` border, `RoundedCornerShape(50)`
- Text: "Swipe left to discard photo" (`labelMedium`, `onSurfaceVariant`)
- Auto-dismiss after 3 seconds or first swipe

### Collection flash (double-tap)

**Current:** 80dp green circle with checkmark.

**New (from mockup):** Larger with glow effect:
- Outer glow: `SuccessGreen.copy(alpha=0.2f)` circle with backdrop blur, border `SuccessGreen.copy(0.3f)`
- Icon: `check_circle` (filled), 80dp, `SuccessGreen` tint
- Drop shadow filter: `SuccessGreen.copy(0.6f)`, 20dp blur
- Pop-in animation: scale 0.5→1.0 with bounce easing

### EXIF metadata overlay (bottom-left)

**Current:** Simple `Surface` with black alpha, text-only layout.

**New (from mockup):** Glassmorphic card:
- Background: `surfaceContainerLowest.copy(alpha=0.5f)`, backdrop blur (`BlurEffect` if API 31+)
- Border: `outlineVariant.copy(alpha=0.3f)`, `RoundedCornerShape(12.dp)`
- Padding: 16dp
- Layout:
  - Filename: `labelLarge`, `onSurface`, uppercase, 70% opacity, wide tracking
  - Camera row: `camera` icon (16dp, `primary`) + EXIF string (`bodyMedium`, `onSurfaceVariant`)
  - Lens row: `lens` icon (16dp, `tertiary`) + lens name (`labelMedium`, `onTertiaryContainer.copy(0.8f)`)
- Tappable: `active:scale-95` → add `clickable` with `Modifier.animateContentSize()` or toggle visibility

### Page counter (bottom-right)

**Current:** `Surface` with black alpha, "24/150" format.

**New:** Pill badge:
- Background: `surfaceContainerHigh.copy(alpha=0.8f)`, backdrop blur
- Border: `outline.copy(alpha=0.2f)`
- `RoundedCornerShape(50)`
- Text: `labelMedium`, `primary`, `FontWeight.Bold`
- Format: "24 / 150" (spaces around slash)

### Gradient vignettes

**New** — add to image overlay for readability:
```kotlin
// Bottom gradient
Box(
    Modifier
        .align(Alignment.BottomCenter)
        .fillMaxWidth()
        .fillMaxHeight(0.33f)
        .background(
            Brush.verticalGradient(
                colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
            )
        )
)
// Top gradient (subtler)
Box(
    Modifier
        .align(Alignment.TopCenter)
        .fillMaxWidth()
        .fillMaxHeight(0.25f)
        .background(
            Brush.verticalGradient(
                colors = listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
            )
        )
)
```

### HUD toggle on tap

**New:** Single tap toggles visibility of all overlays (metadata, counter, nav bars):
```kotlin
var hudVisible by remember { mutableStateOf(true) }
// In detectTapGestures:
onTap = { hudVisible = !hudVisible }
```
Use `AnimatedVisibility` with fade for all overlay elements.

### Trash peek animation (idle state)

**New** — subtle hint on the right edge when not swiping:
- Small `delete` icon in a circle at `Alignment.CenterEnd`
- `surfaceContainerHigh.copy(alpha=0.4f)`, backdrop blur
- Animated sliding left/right subtly (translateX oscillating ±15dp)
- Only shows on first few images or when idle for >2 seconds

---

## 5. Gesture Tutorial Overlay Redesign

**File:** `ui/phonemode/GestureTutorialOverlay.kt`

### Overlay background

**Current:** `Color.Black.copy(alpha = 0.88f)`
**New:** `Color(0xFF09090B).copy(alpha = 0.88f)` + backdrop blur (close enough, keep as-is)

### Header

**Current:** "How it works" (`headlineSmall`)
**New:** "How to PhotoTok" (`headlineMedium`, bold), white + "Master the curation flow with these simple gestures" subtitle (`bodyMedium`, `onSurfaceVariant`)

### Gesture cards

**Current:** `GestureRow` — simple Row with 56dp circle + text.

**New:** Glassmorphic cards:
- Container: gradient background (`surfaceContainerHigh.copy(0.5f)` → `surfaceContainerLow.copy(0.8f)` at 135°)
- Border: `outlineVariant.copy(alpha=0.3f)`, `RoundedCornerShape(12.dp)`
- Padding: 24dp
- Active: `Modifier.clickable` with scale-95 animation
- Icon circle: 64dp, `primary.copy(alpha=0.1f)` fill, `primary.copy(0.2f)` border
- Icon: 32dp Material Symbol, `primary` tint (or `error` tint for swipe-left)
- Title: `titleLarge` (22sp, semibold), white
- Description: `bodyMedium`, `onSurfaceVariant`
- Staggered bounce animation delays: 0s, 0.5s, 1.0s per card

### "Got it" button

**Current:** `Button` with `Indigo500`, `RoundedCornerShape(14.dp)`, 52dp height.

**New:**
- Fixed at bottom with gradient fade-out above (`Color(0xFF09090B)` → transparent)
- `primaryContainer` fill, `onPrimaryContainer` text
- `RoundedCornerShape(12.dp)`, full width
- Text: "GOT IT" uppercase, wide tracking (`labelLarge`)
- `active:scale-[0.98]` feel

---

## 6. New Components Needed

### `ui/components/BottomNavBar.kt` (new file)

Three-tab navigation bar shared across all screens. See Landing section above for spec.

### `ui/components/TopAppBar.kt` (new file or inline)

Shared transparent top bar: "PhotoTok" branding left, settings icon right. Can be a simple `Row` composable.

### Navigation architecture change

The mockup implies 3 screens/tabs: Sources (folder_open), Viewer/Cards (style), History (history). The current app has no navigation graph — it's just landing vs viewer in `PhoneModeScreen.kt`.

For now, keep the 2-state approach but add the bottom nav bar visually. The `history` tab can be a placeholder or disabled. The active tab switches automatically: `folder_open` when on landing, `style` when viewing.

---

## 7. Implementation Priority

1. **Color.kt + Theme.kt** — foundation for everything else
2. **BottomNavBar** + **TopAppBar** — shared chrome
3. **PhoneModeLanding.kt** — new source grid + filter chips
4. **PhoneModeViewer.kt** — swipe feedback, metadata card, vignettes
5. **GestureTutorialOverlay.kt** — glassmorphic cards
6. **SettingsScreen.kt + SettingsItem.kt** — section headers, toggle styling
7. Polish: HUD toggle, trash peek animation, gesture hint pill

---

## 8. Copy & messaging

PhotoTok is a gesture app: people look at photos, not at text. Every dialog, hint card and
snackbar follows these rules. They are binding for new UI as well (REQ-TOK-UX.01).

### Rules

1. **Don't ask when there is nothing to decide.** If one choice is clearly right, take it. For
   example, reopening a folder with no new photos resumes at the saved photo without a dialog.
2. **The title carries the message.** A dialog title states the fact or the question in ≤ 5 words; a hint headline states one rule in ≤ 6. Use a
   number instead of a sentence: "3 new photos", not "3 newer photos were added since you
   last left".
3. **Body text is optional.** Use at most one short sentence, and only when the title and
   buttons cannot stand alone (for example, "This location has no trash." explains
   *permanently*). Drop "Choose how you would like to…" paragraphs.
4. **Buttons name the outcome.** Write "Show new", "Stay", "Show both, linked", "RAW",
   "JPEG", "Leave as is", never "Yes"/"OK". No subline under a button, except under the
   single recommended or non-obvious option ("1 card per shot": "Swipes act on both files").
5. **Point at Settings with an action, not a sentence.** Hint cards for configurable behaviour
   carry a `SETTINGS` action. "You can change this in Settings." is not written anywhere. The
   one exception is the direct-delete dialog: its confirm/cancel slots are taken, so a single
   small `bodySmall` line reads "Turn off this prompt in Settings." (REQ-TOK-GESTURE.03).
6. **Say only what the user cannot see.** No snackbar confirms a jump the user just watched
   happen. A hint names the *rule* ("Swipe right copies to Selection"), because the copy/move
   snackbar already reports the event ("Copied to Selection").
7. **Snackbars use ≤ 4 words**, for example "No new photos", "RAW + JPEG linked" or
   "Showing RAW only". Error feedback may name the folder and the count.
8. **One neutral exit.** Multi-choice dialogs have exactly one neutral button ("Leave as is",
   "Stay"). Back and tap-outside do the same thing, so there is no extra close ✕ next to it.
9. **Be truthful.** Never say "trash" for a delete that does not go to a trash. Keep the
   configured verb (copy/move) and the folder name, and never write "Keep".
10. **No marketing copy** inside the app ("high-velocity curation", "Master the curation flow").
11. **Sentence case** for titles and buttons. The ALL-CAPS action labels on hint cards
    (`GOT IT`, `SETTINGS`, `SHOW ALL`) are the only exception.

### Reference wording

| Surface | Title | Body | Actions |
|---|---|---|---|
| New photos on reload/reopen (`FolderResumeDialog`) | "1 new photo" / "N new photos" (+ photo icon) | — | **Show new** · Stay |
| No new photos | *no dialog* | — | explicit Reload only: snackbar "No new photos" |
| RAW + JPEG pairs (`RawJpegSuggestionDialog`) | "RAW + JPEG pairs" | — | **1 card per shot** (subline "Swipes act on both files") · Show both, linked · *Show only*: RAW · JPEG · Leave as is |
| Direct delete (`DirectDeleteDialog`) | "Delete permanently?" | "This location has no trash." + small "Turn off this prompt in Settings." | Cancel · **Delete** |
| Hint: swipe right / left to folder | "Swipe right copies to «folder»" | — | SETTINGS · GOT IT |
| Hint: swipe left delete | "Swipe left deletes" | "Tap Revert to undo." | SETTINGS · GOT IT |
| Hint: filter mismatch | "RAW only" / "JPEG only" | "Most photos here are JPEG." | SHOW ALL · GOT IT |
| Hints: vertical, tap, zoom, revert | "Your place is saved per folder" · "Tap again to show the overlays" · "Drag to pan, double-tap to reset" · "Revert undoes the last delete only" | — | GOT IT |
| SD / USB card done (`showCardDoneSheet`) | "Done with this card" | — | **Keep all photos** · Delete rejects · Delete all (N) · Cancel |
| Clear whole card confirm | "Delete all N photos?" | "Photos not copied off the card are lost." | Cancel · **Delete all** |
| Snackbars | — | "No new photos" · "RAW + JPEG linked" · "Showing RAW only" · "Safe to unplug" · "Rejects deleted" · "Cloud folder: copying, not moving" | — |
| Landing: folder access lost | "Folder Access Needed" | "Access to this folder was lost." | Re-select Folder |
| Landing: empty folder | "No Photos Found" | "«Folder» has no supported photos" | — |

Hint cards auto-dismiss after 7 s. A card with an action stays for 12 s, so there is time to
reach the button. The pure `com.phototok.domain.FirstRunHintText` owns the hint wording, and
`FirstRunHintTextTest` enforces the length and no-Settings-sentence rules.
