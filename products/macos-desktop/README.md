# macOS Desktop

High-performance native macOS photo selection and comparison culling application built in Swift 6 and SwiftUI. Designed specifically for lightning-fast SD-card photo ingestion, large-scale RAW/JPEG triage, and instant decision-making.

```
products/macos-desktop/
  Package.swift            SPM build manifest (PhotoSelectorKit, PhotoSelectorApp, PhotoSelectorKitTests)
  README.md                What the product is, how to build it, how to test it
  src/
    PhotoSelectorKit/      Headless core: domain models, EXIF parser, Vision aesthetics, Accelerate focus, file culling
    PhotoSelectorApp/      Native SwiftUI application: @main, culling workspace, comparison view, settings, tools
  tests/
    unit/                  Automated unit tests for PhotoSelectorKit (Swift Testing / XCTest)
  scripts/                 build_app.sh (assemble PhotoSelector.app), ui_smoke_test.sh (running-app self-test), make_fixtures.swift
```

## Key Capabilities

- **Pure Native Apple Vision Aesthetics**: On-device aesthetic scoring via `VNCalculateImageAestheticsScoresRequest` utilizing Apple Neural Engine hardware with zero Python, Ollama, or external network dependencies.
- **Metadata never covers the photo**: EXIF and scores live in their own strip/side column (chosen by a pure layout solver) and the image fits the remaining space; 2-Up/3-Up panes stack header, image and EXIF strip.
- **Instant EXIF, lazy AI score**: EXIF shows with the first photo; focus/clipping follow in milliseconds; the Vision aesthetic score fills in last and can be switched off in Settings (`⌘,`), in which case the Vision request never runs.
- **Hardware-Accelerated Focus Metric**: Sub-millisecond 2D Laplacian variance computation via Apple Accelerate (`vImageConvolve_PlanarF` + `vDSP`), processing >1,500 images/second per thread.
- **Multi-Tier RAW & Image Preloading**: Sub-sampling and orientation correction via ImageIO (`CGImageSourceCreateThumbnailAtIndex`) for stutter-free scrolling and instant navigation.
- **Supported Formats**: camera RAW — Sony ARW, Canon CR2/CR3, Nikon NEF/NRW, Fujifilm RAF, Panasonic RW2, OM System/Olympus ORF, Pentax PEF, DNG (cameras and phones), Samsung SRW — plus JPEG, HEIC/HEIF, PNG, TIFF and WebP, matched case-insensitively. Older RAW formats (Hasselblad 3FR/FFF, Phase One IIQ, Leica RWL/RAW, Sony SR2/SRF, Minolta MRW, Sigma X3F, Epson ERF, Mamiya MEF/MOS) are scanned and paired; whether they display depends on macOS's RAW support. RAW files show their embedded preview first. If that preview is smaller than the display size, the app decodes the RAW itself; if macOS cannot decode a RAW (for example Samsung SRW), the embedded preview is used instead. The list lives in `PhotoSelectorKit/Scanning/SupportedFormats.swift` and must match the shared contract `testdata/formats/manifest.json` (see [`docs/shared/SUPPORTED_FORMATS.md`](../../docs/shared/SUPPORTED_FORMATS.md)). `FormatConformanceTests` checks this against real camera files: run `python3 scripts/fetch_format_corpus.py` to download them, or set `PST_REQUIRE_FORMAT_CORPUS=1` to make missing files fail the run instead of skipping.
- **Maximized Viewport Culling**: Borderless comparison layouts (single, side-by-side, triplet) allocating >85% of window area to photo previews without clutter or chrome.
- **Fits the smallest window**: down to the 960 pt minimum width no toolbar or action-bar label wraps or truncates; the controls step down to short labels (`3-Up`, `3 / 120`, `Move`) and then to icons, keeping a tooltip with the full name and shortcut. Action toasts and the zoom-exit pill appear inside the active photo, never over a slot header or EXIF strip.
- **Zero-Latency Keyboard Navigation**: Arrow keys for navigation, `M` (Move to `Selection/`), `C` (Copy to `Selection/`), and `Delete` (System Trash) with automatic companion file pairing (RAW+JPEG, XMP sidecars).
- **Native macOS Interface**: Clean workspace; configuration moved to standard macOS Settings scene (`⌘,`), secondary utilities (Duplicate Finder, Library Stats) housed in the Menu Bar `Tools` menu.

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
products/macos-desktop/scripts/build_app.sh          # prints .build/app/PhotoSelector.app
open products/macos-desktop/.build/app/PhotoSelector.app
```

The bundle holds the host architecture only. Release packaging adds `--universal` to build
arm64 and x86_64 slices and merge them with `lipo`, so the published zip runs on Apple Silicon
and Intel Macs alike.

Directly from SPM also works; the app promotes itself to a regular foreground app at launch
(an unbundled binary otherwise receives no key events at all):

```bash
cd products/macos-desktop
swift run PhotoSelectorApp                       # optional: -- --open /path/to/folder
```

## Test

To run the full automated test suite:

```bash
cd products/macos-desktop
swift test
```

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
- **Homebrew cask** — in [`alexpp90/homebrew-tap`](https://github.com/alexpp90/homebrew-tap), updated by `macos.yml` on release.
- **Legacy Python Desktop** — Archived at Git tag/branch `archive/legacy-desktop` and retained in `products/desktop/`.
