# macOS Desktop (Lensjet)

Lensjet is the high-performance native macOS photo selection and comparison culling application built in Swift 6
and SwiftUI for macOS 14+ / macOS 15+ (Apple Silicon & Intel). Designed specifically for
lightning-fast SD-card photo ingestion, large-scale RAW/JPEG triage, on-device Apple Vision
aesthetic scoring, and instant zero-latency decision making.

| | |
|---|---|
| Code | `products/macos-desktop/` |
| SPM Targets | `PhotoSelectorKit` (core/domain), `PhotoSelectorApp` (SwiftUI executable) |
| Tests | `products/macos-desktop/tests/unit/` (`PhotoSelectorKitTests`) |
| Target | macOS 14+ (Sonoma) / macOS 15+ (Sequoia), Apple Silicon & Intel |
| Artifacts | `Lensjet.app` (macOS application bundle, bundle id `app.alpatix.lensjet`) |
| Entry point | `swift run PhotoSelectorApp` |

## Documents

- `REQUIREMENTS.md` (maintainer-private) — Authoritative behaviour specification
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — SPM target layering, concurrency model, and service interfaces
- Cross-product feature policy: [`../../shared/FEATURE_PARITY.md`](../../shared/FEATURE_PARITY.md)
- CI: [`../../build/CI_PARITY.md`](../../build/CI_PARITY.md)

## Run from source

```bash
cd products/macos-desktop
swift run PhotoSelectorApp
```

To build an optimized release binary:

```bash
cd products/macos-desktop
swift build -c release
```

## Test

```bash
# Repository CI mirror (recommended):
./scripts/run_tests.sh --macos

# Direct SPM test execution:
cd products/macos-desktop
swift test -c release
```

## Architecture & Layering

The product is partitioned into two clean SPM targets following the repository's uniform product layout:

1. **`PhotoSelectorKit` (Domain, Infrastructure & Headless ViewModel Framework)**:
   - Lives in `src/PhotoSelectorKit/`.
   - Core domain services depend strictly on system frameworks (`Foundation`, `ImageIO`, `Vision`, `Accelerate`, `CryptoKit`, `UniformTypeIdentifiers`).
   - May import `AppKit` and `SwiftUI` only for dedicated platform adapter utilities and view models (`KeyboardShortcutRouter`, `CullingWorkspaceViewModel`, `DuplicateFinderViewModel`) that bridge UI events to domain operations for headless unit testability.
   - Must never import presentation views, window controllers, or scene hierarchies.
2. **`PhotoSelectorApp` (Native SwiftUI macOS Executable)**:
   - Lives in `src/PhotoSelectorApp/`.
   - Pure SwiftUI presentation layer containing `@main`, application commands, window scenes, canvas layouts, modal sheets, and the macOS Settings scene (`⌘,`).
   - Delegates all image analysis, metadata parsing, and file operations to `PhotoSelectorKit`.

## Key Capabilities & Design Constraints

- **SD Card Direct Culling**: Direct, non-blocking asynchronous scanning of mounted camera storage with automatic sibling pairing (RAW + JPEG pairs, XMP sidecars, and Lightroom exports).
- **Maximized Viewport**: Borderless 1-Up, 2-Up (King of the Hill), and 3-Up (Sliding Triplet) comparison views allocating >85% of window area to photos without margin clutter.
- **Zero-Latency Keyboard Navigation**: Arrow keys (`←`/`→`), Vim keys (`h`/`l` or `j`/`k`), `M` (Move to `Selection/`), `C` (Copy to `Selection/`), and `Delete` (Trash) with optimistic UI status updates and atomic undo (`⌘Z`).
- **Pure Native Apple Intelligence**: On-device aesthetic evaluation via `VNCalculateImageAestheticsScoresRequest` utilizing Apple Neural Engine hardware, and sub-millisecond Laplacian focus variance via Apple Accelerate (`vImageConvolve_PlanarF` + `vDSP`). 100% offline, zero cloud, zero Python/Ollama dependencies.
- **Decluttered Interface**: No persistent parameter sliders or configuration panels in the culling workspace; configuration lives in the native macOS Settings scene (`⌘,`), and secondary tools (Duplicate Finder, Library Statistics) reside in the menu bar under "Tools".

## Relationship to the Other Products

macOS Desktop is an independent Swift implementation. It shares photographic concepts (the EXIF contract, quality score semantics, what "Selection" means) with the legacy Desktop, Frameduel, Jackpix and Blitzlens, but shares no implementation code:

- **Distinct from legacy Python Desktop (`products/desktop/`)**: Native Swift 6 / SwiftUI rewrite replacing Tkinter, OpenCV, and Ollama with Apple Vision and Accelerate.
- **Distinct from Frameduel & Jackpix (`products/android/`)**: Pure macOS desktop experience targeting macOS HIG, multi-slot comparison, and keyboard-first workflow, rather than touch or SAF.
- See [`../../shared/FEATURE_PARITY.md`](../../shared/FEATURE_PARITY.md).

## Not in This Product

- No Python runtime, Tkinter, PyInstaller, or Ollama server dependencies (Desktop-only).
- No Android Storage Access Framework (SAF), Jetpack Compose, or Room database.
- Apple Vision and Accelerate vImage hardware acceleration are macOS-exclusive.
