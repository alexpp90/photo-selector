# Android Desktop — Architecture

> Product: **Android Desktop** (`products/android/android-desktop/`, `:android-desktop`, `com.photoselectortoolbox`).
> How the app is built internally: layers, adaptive layout strategy, interaction model,
> performance strategy and theme implementation.
> What it must do: `REQUIREMENTS.md` (maintainer-private). What it looks like:
> [`DESIGN.md`](DESIGN.md). Platform baseline shared with Jackpix:
> [`../../shared/ANDROID_PLATFORM.md`](../../shared/ANDROID_PLATFORM.md).

## 3. Architecture

```
┌─────────────────────────────────────────────────────┐
│                    Compose UI Layer                  │
│  ┌──────────┐ ┌──────────┐ ┌────────┐ ┌──────────┐ │
│  │ Selector │ │  Stats   │ │ Dupes  │ │ Settings │ │
│  └────┬─────┘ └────┬─────┘ └───┬────┘ └────┬─────┘ │
│       │             │           │            │       │
│  ┌────┴─────────────┴───────────┴────────────┴────┐ │
│  │              ViewModels (StateFlow)            │ │
│  └────────────────────┬──────────────────────────┘ │
├───────────────────────┼─────────────────────────────┤
│                Domain Layer                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│  │ UseCases │ │ Analysis │ │ Grouping │            │
│  └────┬─────┘ └────┬─────┘ └────┬─────┘            │
├───────┼─────────────┼────────────┼──────────────────┤
│                Data Layer                           │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│  │   Room   │ │  EXIF    │ │   SAF    │            │
│  │  Cache   │ │ Readers  │ │ Sources  │            │
│  └──────────┘ └──────────┘ └──────────┘            │
└─────────────────────────────────────────────────────┘
```

### Layer Responsibilities

**UI Layer:** Compose screens, adaptive layouts, gesture handling, Material 3 theming. No business logic. ViewModels expose `StateFlow` consumed via `collectAsStateWithLifecycle()`.

**Domain Layer:** Pure Kotlin use cases and analysis algorithms. No Android framework dependencies (except coroutine dispatchers). Portable and unit-testable.

**Data Layer:** Android-specific implementations — Room DAOs, ExifInterface wrappers, SAF/MediaStore queries, Coil integration. Implements repository interfaces defined in the domain layer.

## 4. Adaptive Layout Strategy

The app uses Material 3 Window Size Classes to adapt across three form factors:

### 4.1 Expanded (≥840dp) — Tablet Landscape / Samsung DeX

This is the **primary target**. The layout mirrors the desktop app's capability with adaptations for touch.

```
┌─────────────────────────────────────────────────────────┐
│ NavigationRail │              Content Area               │
│                │                                         │
│  📷 Selector   │  ┌─────────────────┬──────────────────┐ │
│  📊 Stats      │  │  Image Viewer   │  Metadata Panel  │ │
│  🔍 Duplicates │  │  (large)        │  + Score Cards   │ │
│  ⚙️ Settings   │  │                 │  + Actions       │ │
│                │  ├────────┬────────┤                  │ │
│                │  │  Prev  │  Next  │                  │ │
│                │  └────────┴────────┴──────────────────┘ │
│                │  ┌─────────────────────────────────────┐ │
│                │  │  Candidate Strip (horizontal scroll)│ │
│                │  └─────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

- **NavigationRail** on the left for tool switching
- **Two-pane content** with image viewer + metadata/actions panel
- **Previous/Next thumbnails** below the main image
- **Horizontal candidate strip** at the bottom for quick scrubbing
- Keyboard shortcuts active when hardware keyboard connected (DeX)

### 4.2 Medium (600–840dp) — Tablet Portrait / Small Tablet

```
┌──────────────────────────────┐
│ NavigationRail │   Content    │
│                │              │
│  📷            │  ┌────────┐ │
│  📊            │  │ Image  │ │
│  🔍            │  │(large) │ │
│  ⚙️            │  ├────────┤ │
│                │  │Metadata│ │
│                │  │compact │ │
│                │  ├────────┤ │
│                │  │Cand.   │ │
│                │  │strip   │ │
│                │  └────────┘ │
└──────────────────────────────┘
```

- NavigationRail with icon-only labels
- Single-pane content, metadata collapsed below image
- Candidate strip as horizontal scrollable row

### 4.3 Compact (<600dp) — Phone

```
┌──────────────────┐
│    App Bar       │
├──────────────────┤
│                  │
│   Image Viewer   │
│   (swipeable)    │
│                  │
├──────────────────┤
│ Score chips row  │
├──────────────────┤
│ Quick actions    │
│ (select/delete)  │
├──────────────────┤
│ BottomNavigation │
│ 📷  📊  🔍  ⚙️   │
└──────────────────┘
```

- **BottomNavigation** replaces NavigationRail
- Full-width swipeable image viewer (HorizontalPager)
- Metadata condensed to **score chips** (icons + values only)
- Quick action buttons (Select, Delete) always visible
- Statistics show simplified single-column charts
- Duplicate finder uses a grid with checkboxes instead of detailed comparison
- **Omitted on phone:** Focus mode comparison view, detailed EXIF metadata panel (accessible via tap-to-expand)

## 6. Touch Interaction Design

### 6.1 Selector Gestures

| Gesture | Action | Context |
|---------|--------|---------|
| Horizontal swipe | Navigate prev/next image | Image viewer |
| Vertical swipe up | Move to Selection | Image viewer (configurable) |
| Vertical swipe down | Dismiss / back | Fullscreen viewer |
| Pinch | Zoom in/out | Fullscreen viewer |
| Double-tap | Toggle zoom (fit ↔ 100%) | Fullscreen viewer |
| Long-press | Show context menu (delete, move, copy, info) | Image viewer, candidate strip |
| Tap on candidate strip | Jump to image | Candidate strip |
| Two-finger swipe | Scroll candidate strip | When strip is focused |

### 6.2 Keyboard Shortcuts (DeX / Hardware Keyboard)

When a hardware keyboard is detected, the same shortcuts as desktop apply:

| Key | Action |
|-----|--------|
| ← / → | Previous / Next image |
| Delete / Backspace | Delete (with confirmation) |
| M | Move to Selection |
| C | Copy to Selection |
| Escape | Exit fullscreen / comparison mode |
| F | Enter fullscreen viewer |
| Space | Toggle Focus/comparison mode (tablet only) |

## 7. Android-Specific UX Deviations from Desktop

### 7.1 Navigation
- **Desktop:** Menu bar with "Tools" dropdown for switching between Selector, Statistics, Duplicate Finder
- **Android:** NavigationRail (tablet/DeX) or BottomNavigation (phone) — always visible, one-tap switching

### 7.2 Folder Selection
- **Desktop:** Native OS file dialog (`filedialog.askdirectory`)
- **Android:** SAF `Intent.ACTION_OPEN_DOCUMENT_TREE` — grants persistent URI permission. Recent folders shown in a quick-access list.

### 7.3 Scan Configuration
- **Desktop:** Modal dialog with checkboxes and options
- **Android (tablet):** Side sheet or dialog with Material 3 switches and sliders
- **Android (phone):** Bottom sheet with compact vertical layout

### 7.4 Progress & Status
- **Desktop:** Log tab with scrollable text output
- **Android:** Compact progress bar in app bar + expandable notification for background scans via WorkManager. Minimal log view accessible via "Details" expansion.

### 7.5 Delete Confirmation
- **Desktop:** Dialog with second Delete keypress to confirm
- **Android:** Snackbar with "Undo" (30s timeout) for single deletes on Tablet/DeX; Dialog confirmation for batch deletes. On the Jackpix, swiping left triggers a non-blocking temporary deletion where the image is immediately hidden from the viewer and a "Revert Deletion" button is shown. Navigating away from the image or leaving the viewer finalizes the deletion on disk. Leverages Android's trash/recycle via MediaStore `createTrashRequest()` on Android 11+.

### 7.6 Settings
- **Desktop:** Embedded in scan config dialog + Help menu
- **Android:** Dedicated Settings screen following Material 3 settings patterns. Grouped sections: Storage, Analysis, Display, About.

### 7.7 Image Loading Strategy
- **Desktop:** Synchronous preload of all images in folder on selection
- **Android:** Lazy loading via Coil with progressive placeholder. Only metadata and thumbnails loaded initially. Full-resolution decoded on-demand with aggressive recycling.

## 8. Performance & Battery Strategy

### 8.1 Thread Pool Sizing
```kotlin
val analysisWorkers = minOf(4, Runtime.getRuntime().availableProcessors())
// Reduced from desktop's min(8, cpuCount + 4) to conserve battery
```

### 8.2 Image Decode Optimization
- Use `BitmapFactory.Options.inSampleSize` to decode at analysis resolution (center crop → grid doesn't need full resolution)
- Use hardware bitmaps (`Bitmap.Config.HARDWARE`) for display, software bitmaps only for analysis
- Release `Mat` objects immediately after OpenCV operations

### 8.3 Battery-Aware Scanning
- Monitor `BatteryManager.EXTRA_STATUS` during long scans
- Reduce worker thread count when battery < 20%
- Pause scan and notify user when battery < 10%
- Use `WorkManager` with battery-not-low constraint for background scans

### 8.4 Memory Management
- Coil handles display image caching with configurable memory/disk limits
- Analysis images decoded at reduced resolution (max 2048px on longest edge for analysis)
- SHA-256 hashing uses streaming `DigestInputStream` — never loads entire file into memory
- Room cursor windows for large result sets

## 9. File Access & Storage

## Theme Implementation

### 10.1 Frameduel Theme (:android-desktop)
A dark-only theme: neutral-grey studio surfaces (R = G = B) and a lavender brand accent
(`ui/theme/Color.kt`, `Theme.kt`). Status bar, navigation bar and the window background follow
`StudioCanvas`, mirrored in `res/values/colors.xml` for the splash.

```kotlin
val DarkColorScheme = darkColorScheme(
    primary = BrandAccent,               // #C4B8FF lavender
    onPrimary = BrandOnAccent,           // #2D2570, 7.2:1 on primary
    primaryContainer = BrandAccentDeep,  // #4A3FA6
    surface = StudioCanvas,              // #0D0D0D
    surfaceVariant = StudioPanel,        // #151515
    surfaceContainer = StudioCard,       // #1E1E1E
    onSurface = Zinc50,                  // #FAFAFA
    onSurfaceVariant = Zinc400,          // #A1A1A1
    outline = StudioBorder,              // #313131
    background = StudioCanvas,
)
```
