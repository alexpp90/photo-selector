# Lensjet – Mac Photo Culling

Lensjet (formerly PhotoSelector Studio) is a native macOS photo selection and comparison culling application built in Swift 6 and SwiftUI, for going through SD cards and large RAW/JPEG folders and comparing frames side by side.

```
products/macos-desktop/
  Package.swift            SPM build manifest (PhotoSelectorKit, PhotoSelectorApp, PhotoSelectorKitTests)
  README.md                What the product is, how to build it, how to test it
  src/
    PhotoSelectorKit/      Headless core: domain models, EXIF parser, Vision aesthetics, Accelerate focus, file culling
    PhotoSelectorApp/      Native SwiftUI application: @main, culling workspace, comparison view, settings, tools
  tests/
    unit/                  Automated unit tests for PhotoSelectorKit (Swift Testing / XCTest)
  scripts/                 build_app.sh (assemble Lensjet.app), ui_smoke_test.sh (running-app self-test), make_fixtures.swift
```

## Key Capabilities

- **Step through a shoot from the keyboard**: one photo at a time or two or three side by side (`1` / `2` / `3`), `Space` for 100 % zoom, `G` to go to a photo. A window-level key monitor acts on the keys before any button takes them, so the arrows, `M`, `C`, `Delete` and `⌘Z` work even while a button has focus; a held key acts once. All keys: [`docs/shared/KEYBOARD.md`](../../docs/shared/KEYBOARD.md).
- **File keepers with their RAW, JPEG and sidecars**: `M` moves and `C` copies into `Selection/`, `Delete` sends to the system Trash, each with the companion files paired automatically (RAW+JPEG, XMP sidecars, edits); a name clash gets a `-2` suffix, nothing is overwritten.
- **Undo**: `⌘Z` undoes the last operation; every folder keeps its own undo history, including an operation that finished while you switched folders.
- **Metadata never covers the photo**: EXIF and scores live in their own strip/side column (chosen by a pure layout solver) and the image fits the remaining space; 2-Up/3-Up panes stack header, image and EXIF strip.
- **Instant EXIF, lazy AI score**: EXIF shows with the first photo; focus/clipping follow in milliseconds; the Vision aesthetic score fills in last and can be switched off in Settings (`⌘,`), in which case the Vision request never runs.
- **Multi-Tier RAW & Image Preloading**: Sub-sampling and orientation correction via ImageIO (`CGImageSourceCreateThumbnailAtIndex`) for stutter-free scrolling and instant navigation.
- **Supported Formats**: camera RAW — Sony ARW, Canon CR2/CR3, Nikon NEF/NRW, Fujifilm RAF, Panasonic RW2, OM System/Olympus ORF, Pentax PEF, DNG (cameras and phones), Samsung SRW — plus JPEG, HEIC/HEIF, PNG, TIFF and WebP, matched case-insensitively. Older RAW formats (Hasselblad 3FR/FFF, Phase One IIQ, Leica RWL/RAW, Sony SR2/SRF, Minolta MRW, Sigma X3F, Epson ERF, Mamiya MEF/MOS) are scanned and paired; whether they display depends on macOS's RAW support. RAW files show their embedded preview first. If that preview is smaller than the display size, the app decodes the RAW itself; if macOS cannot decode a RAW (for example Samsung SRW), the embedded preview is used instead. The list lives in `PhotoSelectorKit/Scanning/SupportedFormats.swift` and must match the shared contract `testdata/formats/manifest.json` (see [`docs/shared/SUPPORTED_FORMATS.md`](../../docs/shared/SUPPORTED_FORMATS.md)). `FormatConformanceTests` checks this against real camera files: run `python3 scripts/fetch_format_corpus.py` to download them, or set `PST_REQUIRE_FORMAT_CORPUS=1` to make missing files fail the run instead of skipping.
- **Sort and jump**: **View > Sort By** (Newest First by default for a new install, Oldest First, File Name, Lowest Sharpness First — scored in the background, with **Settings → Scoring → Stop at sharpness**) and **Go to Photo** (`G`, Go menu: a position number or part of a file name).
- **The set is visible**: a photo that travels with a RAW, JPEG, XMP sidecar or edit shows chips for them, and Move, Copy, Trash and Undo say how many files moved ("Moved 3 files"); the bottom tally keeps a **Copied N** count; scores are shown with their scale ("Sharpness 59.6 / 100", "Aesthetics 8.4 / 10"); Library Statistics scores the whole library in the background and says "12 / 17 scored".
- **Maximized Viewport Culling**: Borderless comparison layouts (single, side-by-side, triplet) allocating >85% of window area to photo previews without clutter or chrome.
- **Fits the smallest window**: down to the 960 pt minimum width no toolbar or action-bar label wraps or truncates; the controls step down to short labels (`3-Up`, `3 / 120`, `Move`) and then to icons, keeping a tooltip with the full name and shortcut. Action toasts and the zoom-exit pill appear inside the active photo, never over a slot header or EXIF strip.
- **Hardware-Accelerated Focus Metric**: 2D Laplacian variance on Apple Accelerate (`vImageConvolve_PlanarF` + `vDSP`); `AdversarialScoringStressTests` holds it under 5 ms for a 512 × 512 preview (release build).
- **Pure Native Apple Vision Aesthetics**: On-device aesthetic scoring via `VNCalculateImageAestheticsScoresRequest` utilizing Apple Neural Engine hardware with zero Python, Ollama, or external network dependencies.
- **Native macOS Interface**: Clean workspace; configuration moved to standard macOS Settings scene (`⌘,`), where **Culling Destination** chooses between the working folder and a custom folder and shows the resolved destination as a full path, secondary utilities (Duplicate Finder, Library Stats) housed in the Menu Bar `Tools` menu.

## Architecture & Layering

The product is partitioned into two clean SPM targets following the repository's uniform product layout:

1. **`PhotoSelectorKit` (Domain, Infrastructure & Headless ViewModel Framework)**:
   - Lives in `src/PhotoSelectorKit/`.
   - Core domain services depend strictly on system frameworks (`Foundation`, `ImageIO`, `Vision`, `Accelerate`, `CryptoKit`, `UniformTypeIdentifiers`).
   - May import `AppKit` and `SwiftUI` only for dedicated platform adapter utilities and view models (`KeyboardShortcutRouter`, `CullingWorkspaceViewModel`, `DuplicateFinderViewModel`) that bridge UI events to domain operations for headless unit testability.
   - Must never import presentation views, window controllers, or scene hierarchies.
2. **`PhotoSelectorApp` (Native SwiftUI macOS Executable)**:
   - Lives in `src/PhotoSelectorApp/`.
   - Depends on `PhotoSelectorKit`, `SwiftUI`, and `AppKit`.
   - Implements `@main`, window management, workspace views, keyboard event routing, and menus.
3. **`tests/unit/` (Unit Test Suite)**:
   - Target `PhotoSelectorKitTests` testing algorithms, EXIF parsing, focus metrics, and file operations.

## Build

```bash
cd products/macos-desktop
swift build
```

To build an optimized release binary:

```bash
swift build -c release
```

The UI test modes (`--ui-scenario`, `--ui-self-test`, `--screenshot`) drive the app and can trash
files, so a release build does not contain them and refuses those flags (exit code 64). Debug
builds have them; for a release-optimized build with them (the device lab and the smoke test use
it) run `scripts/build_app.sh release --test-hooks`, which writes to `.build/hooks/` and never to
the distributable `.build/app/`. `scripts/check_no_test_hooks.sh` verifies a binary carries none.

## Run

Recommended — assemble and open a proper app bundle (Dock icon, normal keyboard focus):

```bash
products/macos-desktop/scripts/build_app.sh          # prints .build/app/Lensjet.app
open products/macos-desktop/.build/app/Lensjet.app
```

Settings from the former bundle id `com.photoselectortoolbox.macos` are copied once on the first launch (nothing is overwritten or deleted). macOS asks again for folder and removable-volume access, which belongs to the bundle id `app.alpatix.lensjet`.

The bundle holds the host architecture only. Release packaging adds `--universal` to build
arm64 and x86_64 slices and merge them with `lipo`, so the published zip runs on Apple Silicon
and Intel Macs alike.

The bundle is signed ad hoc and not notarized yet, so macOS blocks the first launch of a downloaded
copy: System Settings → Privacy & Security → **Open Anyway**, or
`xattr -dr com.apple.quarantine /Applications/Lensjet.app`. The Homebrew cask prints the same note.

Directly from SPM also works; the app promotes itself to a regular foreground app at launch
(an unbundled binary otherwise receives no key events at all):

```bash
cd products/macos-desktop
swift run PhotoSelectorApp                       # optional: -- --open /path/to/folder
```

## Test

To run the full automated test suite the way CI does (`swift test -c release --no-parallel`, plus
the macro-plugin flags Command Line Tools need), use the repository mirror from the repository root:

```bash
./scripts/run_tests.sh --macos
```

A bare `swift test` runs a different configuration (debug, parallel) and can fail or diverge from the gate.

UI smoke test of the **running** app (keyboard delivery, real key events, Focus 3-Up
geometry, clicks while zoomed, time-to-first-photo, single-line toolbar/action-bar labels and
toast/zoom-pill placement at 1024 pt and the 960 pt minimum width) — needs a logged-in desktop session:

```bash
products/macos-desktop/scripts/ui_smoke_test.sh
```

The optional window-server key probe uses System Events and needs Accessibility permission
for your terminal (System Settings → Privacy & Security → Accessibility); without it that one
probe reports `⊘ SKIPPED`.

From the repository root, the CI mirror includes all products (and runs the UI smoke test
under `--macos` / `--all`):
```bash
./scripts/run_tests.sh
```

## Documentation

- `../../docs/products/macos-desktop/REQUIREMENTS.md` (maintainer-private) — Authoritative behaviour specification.
- [`../../docs/products/macos-desktop/ARCHITECTURE.md`](../../docs/products/macos-desktop/ARCHITECTURE.md) — Layering rules, concurrency models, and service interfaces.

## Things That Deliberately Live Elsewhere

- **`docs/products/macos-desktop/`** — Product requirements and architectural specifications.
- **`assets/`** — Shared branding assets, icons, and logos at the repository root.
- **Homebrew cask** — in [`alexpp90/homebrew-tap`](https://github.com/alexpp90/homebrew-tap), updated by `macos.yml` on release: version and hash, then `scripts/lensjet_cask.py` (the `macos-desktop-v<version>` download tag, `Lensjet.app`, name, description and the preferences `zap` removes). The cask token `photo-selector-toolbox` stays, so installed casks keep updating.
- **Legacy Python Desktop** — Archived at Git tag/branch `archive/legacy-desktop` and retained in `products/desktop/`.
