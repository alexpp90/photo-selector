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

The original mockup replaced the vertical column of `FolderPickerCard` rows with a centered hero + 3-column source grid + filter chips + CTA. The shipped landing keeps a centered hero but lists the sources as compact rows, so that all three fit above the fold on a 360×640 dp phone (lab TOK-11a, 2026-10-19; a 3-column grid wraps "SD Card / USB Reader" at 1.3× font and drops the supporting line).

### Layout structure (top to bottom):

1. **Hero section** (centered, sized from the window height via `BoxWithConstraints`):
   - Launcher-foreground logo (decorative): 56 dp below a 700 dp window height, 72 dp from 700 dp; 16 dp top padding
   - "Photo-Tok" headline (`headlineMedium`, `onSurface`); no tagline (REQ-TOK-UX.01)
   - Photo fan (three tilted sample photos, decorative) only from a 760 dp window height

2. **Source selection** (no section label):
   - **Three list rows** in this order: "Camera", "SD card / USB", "Folder or cloud" (`title_source_*`); 8 dp apart
     - Each row: full width, `heightIn(min = 64.dp)`, 16/12 dp padding, `surfaceContainer` fill, `outlineVariant` border, `RoundedCornerShape(12.dp)`
     - Leading 24 dp icon in a 40 dp circle (`secondary` at 12 % alpha), title only (`titleSmall`, no description line, REQ-TOK-UX.04), trailing arrow
     - Active state: border → `primaryContainer`, fill → `primaryContainer.copy(alpha=0.1f)`, icon → `primary`
     - One click target (`Role.Button`); the icon is decorative, the row's text names it
   - Must fit without scrolling at 360×640 dp at 1.0× and 1.3× font (`PhoneModeLandingLayoutTest`)
   - While the column can scroll further (recents, Resume), a 24 dp bottom fade into `background` sits above the navigation bar (`landing_scroll_fade`, decorative)

3. **Format filter**:
   - Section label: "FORMAT FILTER"
   - **Pill chips** (`RoundedCornerShape(50)`) instead of `FilterChip`:
     - Active: `primaryContainer` fill, `onPrimaryContainer` text
     - Inactive: `surfaceContainer` fill, `onSurfaceVariant` text, `outlineVariant` border
   - Options: "Both" (default), "RAW", "JPG"

4. **CTA button**:
   - Full-width, 56dp height, `primaryContainer` fill, `onPrimaryContainer` text
   - `RoundedCornerShape(12.dp)`
   - Text: "Resume" (the folder name is shown above the button as a value) or "Start"; no icon
   - Shadow: `shadow(8.dp, primaryContainer.copy(alpha=0.1f))`

5. *(removed)* The old "Connect a source to begin your high-velocity curation." subtitle is marketing copy and is not shown.

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

### Viewer bottom bar (icon-only)

**File:** `ui/components/BottomNavBar.kt` (holds `ViewerBottomBar`; the unused three-tab `BottomNavBar` was removed).

The viewer bottom bar (`ViewerBottomBar`) shows 3 persistent, **icon-only** controls (REQ-TOK-UX.02):

```
[FolderOpen]  [CollectionsBookmark]  [RestoreFromTrash]
 Change folder     Selection            Undo delete
```

(The names under the icons are the spoken `contentDescription` and the long-press tooltip; they are not drawn.)

- Each control: a 24 dp icon in a 72 × 48 dp target, `contentDescription` from a `cd_` string, and a long-press tooltip (`NameTooltip`, Material3 `TooltipBox` + `PlainTooltip`) with the same name. The tooltip only listens for the long press, so taps are unaffected. Removing the micro-label did not shrink the target, so `PhoneModeChromeMetrics` and the controls-guide geometry are unchanged.
- Container: `surfaceContainerLowest.copy(alpha=0.8f)`, `RoundedCornerShape(topStart=12.dp, topEnd=12.dp)`
- Active item: `primaryContainer` background, `onPrimaryContainer` icon (filled variant)
- Inactive items: `secondary` tint; Revert uses `RestoreFromTrash` (trash bin with upward restore arrow) so it is visually distinct from the top-bar `Sync` Reload icon
- Padding: 24 dp horizontal, 6 dp vertical, plus the navigation-bar inset

### Top app bar

The top app bar shows the Photo-Tok logo on the left, and four 48 dp **icon-only** action buttons on the right (`PhoneModeTopBar`): `Sync` (Reload folder), `HelpOutline` (Controls guide), `Info` (EXIF metadata toggle), and `Settings`; an SD/USB source adds `SdCard` (Done with this card) before them. Every button has a spoken name, a long-press tooltip with the same text and a 48 dp target. The status and navigation bars always draw light icons over the dark UI (also after a rotation; system light mode must not flip them). In landscape orientation, the left and right side panels render the same actions as icon-only `SidePanelButton` items (64 × 48 dp) and `TopBarIconButton`s; the micro-labels are withdrawn.

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
- Shares one bottom row with the EXIF card (card at the start, `weight(1f, fill = false)`; counter at the end), so the two never overlap at large font scales
- Fades out with the EXIF card while a first-run hint card or the controls guide is shown

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
**New:** no header at all. The callouts name the controls and the gestures explain themselves; a title only adds text (REQ-TOK-UX.04). "Master the curation flow…" style subtitles are marketing copy and are not shown.

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
- `primaryContainer` fill, `onPrimaryContainer` check icon (no text; spoken name "Got it")
- `RoundedCornerShape(12.dp)`, full width, 48 dp high

---

## 6. New Components Needed

### `ui/components/BottomNavBar.kt`

Holds `ViewerBottomBar` (see the viewer bottom bar section above) and `NameTooltip.kt` next to it, the long-press tooltip every icon-only control uses. The three-tab `BottomNavBar` of the first mockup was never wired in and has been removed.

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

PhotoTok is a gesture app: people look at photos, not at text. Every dialog, hint card, snackbar,
bar, chip, settings row and callout follows these rules. They are binding for new UI as well
(REQ-TOK-UX.01–.05). The budgets (words and characters per element, EN and DE) and the review
checklist are in [`docs/shared/UI_TEXT.md`](../../shared/UI_TEXT.md); `scripts/check_ui_text_budget.py`
fails the build on any finding. This section applies them to PhotoTok.

### Rules

1. **Icon first.** A control is an icon with no visible text. Bars (bottom bar, top bar, landscape
   side panels) are icon-only: a spoken `contentDescription` (`cd_` string), a long-press tooltip
   with the same name (`NameTooltip`) and a touch target of at least 48 dp. The only visible words
   next to an icon are these exceptions: the three landing source cards (icon above "Camera" /
   "SD card / USB" / "Folder or cloud": a first-run choice the icons alone cannot tell apart) and
   the controls guide's callouts, whose job is to name the icons once.
2. **Don't ask when there is nothing to decide.** If one choice is clearly right, take it. For
   example, reopening a folder with no new photos resumes at the saved photo without a dialog.
3. **The title carries the message.** A dialog title states the fact or the question in ≤ 5 words;
   a hint headline states one rule in ≤ 6. Use a number instead of a sentence: "3 new photos".
4. **Body text only for data loss, storage access, first run or an error that needs a decision**,
   then one line ("This location has no trash."). Settings rows have a title and at most a value
   (folder name) or a data-loss warning; no subtitle explains a row. Landing cards, guide callouts
   and chips have no description line.
5. **Buttons name the outcome** in ≤ 2 words ("Show new", "Stay", "Link both", "Don't change"),
   never "Yes"/"OK"; no subline except under the single non-obvious option ("Merge pairs": "Swipes act
   on both files").
6. **Point at Settings with an action, not a sentence.** Hint cards for configurable behaviour carry
   a "Settings" action. The one exception is the direct-delete dialog: its confirm/cancel slots are
   taken, so a single small line reads "Turn off this prompt in Settings." (REQ-TOK-GESTURE.03).
7. **Say only what the user cannot see, once.** No snackbar or hint confirms what the user just
   watched. Swipes, page changes and Undo get no hint: the controls guide shows them once and the
   result is on screen; the copy/move snackbar reports where a file went ("Copied to Selection").
   Hints remain only for effects that are not visible (a tap hid the overlays; how to leave zoom).
8. **Snackbars use ≤ 4 words**; errors ≤ 8 words and never a raw exception message. Milestones
   carry no "!".
9. **One neutral exit.** Multi-choice dialogs have exactly one neutral button ("Don't change",
   "Stay"); back and tap-outside do the same.
10. **Be truthful and consistent.** Never say "trash" for a delete that does not go to a trash. Keep
    the configured verb (copy/move) and the folder name; never write "Keep". One word per action:
    "Undo delete" (not Revert/Restore), "Merge pairs"/"Pairs merged".
11. **No marketing copy**: no tagline, no "engine"/"curation" language.
12. **Resources, EN and DE.** Every string is in `res/values/strings.xml` with an element prefix
    (`action_`, `chip_`, `label_`, `title_`, `dialog_title_`, `dialog_body_`, `snackbar_`, `error_`,
    `empty_`, `hint_title_`, `hint_body_`, `setting_title_`, `setting_summary_`, `guide_`, `cd_`) and
    in `values-de/strings.xml`; counts are `<plurals>`. Domain wording classes return `UiText`
    (resource id + arguments), resolved in Compose (`UiText.asString()`); ViewModels hold no `Context`.
    Sentence case in English; German follows German capitalisation. Layouts must fit EN and DE at
    font scale 2.0 (`TextLoadScreenshotTest`).

### Exceptions: a word next to a control

| Where | Text | Why |
|---|---|---|
| Landing source cards | "Camera", "SD card / USB", "Folder or cloud" | Cards, not bar controls; three sources with one icon each need a name once, on the landing page only. |
| Swipe indicators and guide swipe callouts | COPY / MOVE / DELETE (+ the folder name as a value) | The only thing telling a gesture-first user what a swipe does (REQ-TOK-GESTURE.01/.07). |
| Controls-guide callouts | one or two words per control | The guide exists to name the controls; ≤ 2 words, no description. |
| Hint-card action | "Settings", "Show all" | A pointer to Settings is an action, not a sentence. |

### Reference wording (EN · DE)

| Surface | Title | Body | Actions |
|---|---|---|---|
| Bottom bar | *icons only* | — | spoken/tooltip: "Change folder" · "Selection" · "Undo last delete" (DE "Ordner wechseln" · "Auswahl" · "Letztes Löschen rückgängig machen") |
| Top bar | *icons only* | — | spoken/tooltip: "Done with this card" · "Reload folder" · "Show/Hide controls guide" · "Show or hide camera info" · "Settings" |
| Controls guide | callouts: "Camera info" · "Card done" · "Reload" · "Help" · "Info" · "Settings" · "Change folder" · "Selection" · "Undo delete" · "Next" · "Previous" · "Hide overlays" · "Zoom" (DE "Kamera-Info" · "Karte fertig" · "Neu laden" · "Hilfe" · "Info" · "Einstellungen" · "Ordner" · "Auswahl" · "Rückgängig" · "Weiter" · "Zurück" · "Anzeigen aus" · "Zoom") | swipe captions: Copy / Move / Delete with the configured folder name beneath (★ Favourites when the source kind favourites) | dismiss: ✓ icon ("Got it" spoken) |
| New photos on reload/reopen (`FolderResumeDialog`) | "1 new photo" / "N new photos" (DE "N neue Fotos") | — | **Show new** · Stay (DE **Neue zeigen** · Bleiben) |
| No new photos | *no dialog* | — | explicit Reload only: snackbar "No new photos" |
| RAW + JPEG pairs (`RawJpegSuggestionDialog`) | "RAW + JPEG pairs" | — | **Merge pairs** (subline "Swipes act on both files") · Link both · *Show only*: RAW · JPEG · Don't change (DE **Paare bündeln** · Beide verknüpfen · Nur zeigen: RAW · JPEG · Nicht ändern) |
| Direct delete (`DirectDeleteDialog`) | "Delete permanently?" (DE "Endgültig löschen?") | "This location has no trash." + small "Turn off this prompt in Settings." (DE "In den Einstellungen abschaltbar.") | Cancel · **Delete** |
| Hint: filter mismatch | "RAW only" / "JPEG only" | "Most photos here are JPEG." | Show all ✕ |
| Hints: tap, zoom | "Tap again to show overlays" · "Double-tap to reset" (DE "Doppeltippen setzt zurück") | — | ✕ |
| *No hint* | swipes (the guide shows them once), first page change, first revert: the user just watched the result | — | — |
| SD / USB card done (`showCardDoneSheet`) | "Card done" | — | **Keep all** · Delete rejects · Delete all (N) · Cancel |
| Clear whole card confirm | "Delete all N photos?" | "Photos not copied off the card are lost." | Cancel · **Delete all** |
| Snackbars | — | "Copied to «folder»" · "Moved to «folder» +K" · "Moved as «name»" · "Marked as favourite" · "No new photos" · "Pairs merged" · "RAW + JPEG linked" · "Safe to unplug" · "Rejects deleted" · "N deleted" · "Cloud: copy only" · "Card disconnected" · "50 MB freed" · "100 reviewed" | — |
| Errors | — | "Copy to «folder» failed" · "Moved 2 of 3 to «folder»" · "Copied to «folder», original not removed" · "Couldn't delete «file»" · "Can't open this folder" | — |
| Landing | *no tagline, no section labels* | — | folder name as a value · **Resume** / Start |
| Landing: folder access lost | "Folder access lost" | — | Re-select |
| Landing: empty folder | "«Folder»: no photos" | — | — |
| Settings (first screen) | "Source folder" (value: folder name) · "Reload folder" · "Selection folder" · "RAW / JPEG folders" · "Selection location" (value: Custom/Default) · "Reset this folder" · "Reset all folders" · "Clear cache" · "Confirm delete" (warning "Deletes without asking") · "Milestones" · "Link RAW + JPEG" · "Privacy Policy" · "Legal Notice (Impressum)" | — | — |

Hint cards auto-dismiss after 7 s. A card with an action stays for 12 s, so there is time to
reach the button. The pure `com.phototok.domain.FirstRunHintText` owns the hint wording,
`FirstRunHintTextTest` enforces the length and no-Settings-sentence rules, and
`StringResourcesTest` keeps EN and DE in step.
