# macOS Desktop — Architecture

> Product: **macOS Desktop** (`products/macos-desktop/`, Swift 6 + SwiftUI).
> How the native macOS application is structured internally: Swift Package Manager (SPM) layering,
> actor-isolated concurrency model, image decoding pipeline, hardware-accelerated scoring engines,
> transactional file culling, zero-latency keyboard routing, and automated test organization.
> Authoritative behaviour specification: `REQUIREMENTS.md` (maintainer-private).
> Terminology & taxonomy: [`../../GLOSSARY.md`](../../GLOSSARY.md).
> Cross-product feature sync: [`../../shared/FEATURE_PARITY.md`](../../shared/FEATURE_PARITY.md).

---

## 1. Architectural Principles & Tenets

The macOS Desktop application is designed for extreme throughput, responsiveness, and aesthetic clarity:

1. **Pure Native Apple Frameworks**: Zero external runtime dependencies. No Python bridges, no PyObjC shims, no Ollama servers, and no third-party C++ libraries. Metadata extraction is performed via Apple `ImageIO`, focus scoring via Apple `Accelerate` (`vImage` and `vDSP`), and aesthetic evaluation via Apple `Vision` (`VNCalculateImageAestheticsScoresRequest`).
2. **Strict Layer Separation**: Headless domain logic resides in `PhotoSelectorKit`; presentation, windowing, and SwiftUI scenes reside in `PhotoSelectorApp`. `PhotoSelectorKit` must never depend on `PhotoSelectorApp`.
3. **Swift 6 Strict Concurrency**: All models are immutable `Sendable` value types. Mutable state and I/O are actor-isolated (`AsyncCullingActor`, `ThumbnailLoader`, `ThumbnailCache`, `VisionAestheticsService`). UI bindings and view models are isolated to `@MainActor`.
4. **Zero-Latency Interaction (< 1.0 ms UI Thread Budget)**: All file mutations, disk reads, decodes, and quality scoring passes execute on cooperative background tasks or dedicated actors. User keyboard gestures dispatch optimistic UI updates immediately, accompanied by background queue processing and non-blocking transactional rollback capabilities.
5. **Maximized Viewport Canvas**: Visual chrome is minimized. Over 85% of window area is allocated to photo previews across 1-Up, 2-Up (Challenger vs Champion), and 3-Up (Sliding Triplet) comparison modes. All secondary configuration is offloaded to the native macOS Settings scene (`⌘,`), and secondary tools (Duplicate Finder, Library Statistics) live in the macOS Menu Bar `Tools` menu.

---

## 2. Package & Module Layout

The codebase is partitioned into two SPM targets and one test target under `products/macos-desktop/`:

```
products/macos-desktop/
  Package.swift                          SPM manifest (Swift tools version 6.0, platform macOS 14+)
  README.md                              Product overview, build commands, test instructions
  src/
    PhotoSelectorKit/                    Headless Core Framework (Domain, Logic, Hardware Engines)
      PhotoSelectorKit.swift             Public module entry point and version metadata
      Models/                            Domain models (Sendable, Codable, Equatable)
        ExifData.swift                   Optical metadata model, APEX formatters, exposure summary
        PhotoItem.swift                  Photo candidate wrapper, PhotoStatus enum, UUID identity
        QualityScores.swift              Composite scores (sharpness, noise, clipping, aesthetics)
        ScanResult.swift                 Ingestion aggregate model
      Metadata/                          Optical metadata extraction
        ImageMetadataReader.swift        ImageIO CGImageSource extraction & APEX math resolvers
      Scanning/                          Filesystem traversal & companion pairing
        DirectoryScanner.swift           One listing per directory, in-memory companion grouping, ScanBatcher (first batch 1, ×4 ramp)
      Scoring/                           Hardware-accelerated analysis engines
        FocusMetricService.swift         Accelerate vImage/vDSP 2D Laplacian variance (<2ms SLA)
        ExposureMetricService.swift      Accelerate vImage 256-bin highlight/shadow clipping
        VisionAestheticsService.swift    Apple Vision Neural Engine aesthetic scorer (actor)
        QualityScoringService.swift      Unified orchestrator aggregating all quality metrics
      FileOperations/                    Disk mutations & culling engine
        FileCullingManager.swift         Atomic move, copy, trash & companion file discovery
        AsyncCullingActor.swift          Actor-isolated FIFO disk queue & transactional undo
        DuplicateFinder.swift            CryptoKit streaming SHA-256 duplicate engine
      Thumbnails/                        Multi-tier downsampled decoding & cache
        ThumbnailCache.swift             Actor-isolated memory-bounded cache (NSCache, byte cost)
        ThumbnailLoader.swift            ImageIO decoder: quick (embedded) → preview stream, full-res for zoom, prefetch window
      Statistics/                        Metadata distribution engine
        FormatStatistic.swift            Histogram bin data models
        LibraryStatistics.swift          Aggregated library metrics model
        LibraryStatisticsEngine.swift    EXIF distribution aggregator
      Settings/                          Application persistence
        AppSettings.swift                UserDefaults backed strongly typed configuration
      Navigation/                        Window-level event routing
        KeyboardShortcutRouter.swift     NSEvent local event monitor for zero-latency hotkeys (sheet-aware)
        ForegroundActivation.swift       Activation-policy rule: the app must be .regular to receive keys
      ViewModels/                        Shared workspace and tool view models
        CullingWorkspaceViewModel.swift  @MainActor workspace state, navigation, culling queues
        DuplicateFinderViewModel.swift   @MainActor duplicate analysis and cleanup coordinator
        FocusTripletLayout.swift         Focus 3-Up one-over-two geometry (single source of truth)
        ZoomGeometry.swift               Honest 1:1 scale, aspect-fit and clamped pan math

    PhotoSelectorApp/                    Native SwiftUI Application Executable
      PhotoSelectorApp.swift             @main App struct, AppDelegate (activation policy, --open / --ui-self-test), scenes
      ContentView.swift                  Primary culling workspace canvas, top bar, HUD, filmstrip
      ComparisonView.swift               2-Up and Focus 3-Up (one-over-two) multi-pane comparison view
      SinglePhotoView.swift              1-Up preview renderer
      ZoomableImageViewport.swift        Progressive image state, fit/100 %/pinch viewport, Fit exit pill
      UISelfTestRunner.swift             In-app UI self-test (keys, layout frames, clicks while zoomed, timings)
      UITestFrameRegistry.swift          Test-only rendered-frame registry (`uiTestFrame` modifier)
      FilmstripView.swift                Collapsible bottom thumbnail scrubber track
      CullingHUDView.swift               Floating action toast overlay
      EmptyDropTargetView.swift          Drag-and-drop ingestion placeholder
      AppCommands.swift                  macOS Menu Bar commands (Tools menu, Shortcuts, Settings)
      Views/
        CullingActionBar.swift           Tactile persistent bottom action bar (Move, Copy, Trash, Undo)
        FolderLoadingView.swift          Ingestion progress overlay with animated spinner and cancel
        StatusBadge.swift                Color-coded sharpness and status pills
        ShortcutsHelpSheet.swift         Interactive keyboard shortcuts cheat sheet modal
        Settings/
          SettingsView.swift             macOS Settings tabbed interface (⌘,)
        Tools/
          DuplicateFinderView.swift      Duplicate review studio sheet (⌘D)
          LibraryStatisticsView.swift    EXIF metadata distribution dashboard (⌘L)

  scripts/
    build_app.sh                         Assembles Lensjet.app (Info.plist, ad-hoc signed)
    ui_smoke_test.sh                     Running-app smoke test (raw binary + .app), local CI gate
    make_fixtures.swift                  Landscape JPEG fixtures for the smoke test

  tests/
    unit/                                Automated Test Suite (Swift Testing & XCTest)
      PhotoSelectorKitTests.swift        Framework baseline sanity tests
      ImageMetadataReaderTests.swift     ImageIO optical extraction and APEX fallbacks
      DirectoryScannerTests.swift        Scanning traversal and Selection/ exclusion
      FocusMetricServiceTests.swift      Accelerate focus calculation and noise subtraction
      ExposureMetricServiceTests.swift   Histogram clipping calculations
      VisionAestheticsServiceTests.swift Vision aesthetic scoring and OS availability
      FileCullingManagerTests.swift      Atomic file operations and companion pairing
      AsyncCullingActorTests.swift       FIFO disk queue and transactional undo rollback
      DuplicateFinderTests.swift         Streaming SHA-256 hashing and duplicate clustering
      ThumbnailLoaderTests.swift         ImageIO downsampling and sliding prefetch window
      LibraryStatisticsEngineTests.swift EXIF distribution calculations
      SettingsPersistenceTests.swift     UserDefaults persistence and sanitization
      KeyboardShortcutRouterTests.swift  NSEvent monitoring and hotkey dispatching
      CullingWorkspaceViewModelTests.swift Workspace state mutations and navigation
      DuplicateFinderViewModelTests.swift Duplicate state machine and batch resolution
      FocusViewAndCullingAffordanceTests.swift 3-Up sliding triplet and slot focus
      WorkspaceResponsivenessTests.swift One-over-two layout, zoom exits, activation policy, progressive scan/decode
      E2EIntegrationTests.swift          End-to-end integration and directory validation
      EndToEndCullingWorkflowTests.swift Full photographer workflow simulation
      AdversarialTier5HardeningTests.swift SLA latency and corrupt buffer hardening
      AdversarialScoringStressTests.swift Latency benchmarks under heavy concurrency
      AdversarialM2RemedyChallengeTests.swift Edge case and companion validation
      AdversarialM3CullingStressTests.swift Rapid burst keystrokes and in-flight undo
      AdversarialM3ScannerAndThumbnailTests.swift Deep DCIM trees and thumbnail concurrency
      FileCullingAndDuplicateAdversarialTests.swift Read-only filesystem and hash collision
      SyntheticImageFactory.swift        Test fixture generator for procedural bitmap images
```

---

## 3. High-Level Architecture & Layering

```
┌────────────────────────────────────────────────────────────────────────┐
│                        PhotoSelectorApp (SwiftUI)                      │
│  ┌─────────────────────────┐  ┌───────────────────┐  ┌──────────────┐  │
│  │   PhotoSelectorApp      │  │    ContentView    │  │ SettingsView │  │
│  │   (@main, AppCommands)  │  │   (Preview Canvas)│  │     (⌘,)     │  │
│  └────────────┬────────────┘  └─────────┬─────────┘  └───────┬──────┘  │
│               │                         │                    │         │
│               ▼                         ▼                    ▼         │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │     ViewModels (@MainActor: CullingWorkspaceViewModel)           │  │
│  └──────────────────────────────────┬───────────────────────────────┘  │
├─────────────────────────────────────┼──────────────────────────────────┤
│                                     ▼                                  │
│                        PhotoSelectorKit (Headless Core)                │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │       Domain Models: PhotoItem, ExifData, QualityScores          │  │
│  └──────┬─────────────┬─────────────┬─────────────┬─────────────┬───┘  │
│         │             │             │             │             │      │
│         ▼             ▼             ▼             ▼             ▼      │
│  ┌─────────────┐┌─────────────┐┌───────────┐┌───────────┐┌───────────┐ │
│  │  Directory  ││  Metadata   ││ Scoring   ││ File Ops  ││Thumbnail  │ │
│  │   Scanner   ││   Reader    ││ (Accel /  ││ (Async    ││ Loader &  │ │
│  │ (Companions)││  (ImageIO)  ││  Vision)  ││  Actor)   ││  Cache    │ │
│  └─────────────┘└─────────────┘└───────────┘└───────────┘└───────────┘ │
└────────────────────────────────────────────────────────────────────────┘
```

### Layer Dependency Rules
1. **`PhotoSelectorKit` is Strictly Headless**:
   - Depends only on Apple system frameworks (`Foundation`, `ImageIO`, `Vision`, `Accelerate`, `CryptoKit`, `UniformTypeIdentifiers`).
   - May import `AppKit` and `SwiftUI` only for dedicated platform utilities that bridge AppKit events to domain view models (such as `KeyboardShortcutRouter` and `CullingWorkspaceViewModel`).
   - Must never import presentation views, window controllers, or scenes.
2. **`PhotoSelectorApp` is Presentation-Only**:
   - Contains views, menus, layout geometry, modal sheets, and the `@main` application entry point.
   - Depends on `PhotoSelectorKit`, `SwiftUI`, and `AppKit`.
   - Never directly manipulates files on disk or computes Accelerate convolutions; delegates all operations to `PhotoSelectorKit` services.

---

## 4. Core Subsystems & Mechanisms

### 4.1 Ingestion & Scanning Pipeline
- **Recursive & Shallow Scanning (`DirectoryScanner`)**:
  - Traverses directory trees asynchronously using `FileManager.DirectoryEnumerator`.
  - Enforces strict subfolder exclusion: Subdirectories named `Selection`, `Selected`, `PhotoTok_Selection`, and `PhotoTok_LeftSwipe` (case-insensitively) are strictly bypassed to prevent re-scanning previously culled images, unless explicitly chosen as the scan root.
- **Companion File Grouping**:
  - Automatically identifies and pairs companion files associated with primary photo captures:
    - Camera RAW + JPEG pairs (e.g. `DSC0001.ARW` + `DSC0001.JPG`).
    - Standard XMP sidecars (`DSC0001.xmp` or `DSC0001.JPG.xmp`).
    - Lightroom editing exports (`DSC0001-Edit.tif` or `DSC0001_Edit.jpg`).
  - Emits exactly one primary `PhotoItem` candidate while preserving references to all companion URLs for atomic culling.

### 4.2 Optical Metadata Extraction (`ImageMetadataReader`)
- **Container & Image Level Merging**:
  - Reads metadata using Apple `ImageIO`: merges container properties (`CGImageSourceCopyProperties`) with frame properties (`CGImageSourceCopyPropertiesAtIndex(0)`).
  - Extracts APEX values with mathematical fallbacks:
    - Shutter speed: Direct value or APEX time value: $t = 2^{-T_v}$.
    - Aperture: Direct value or APEX aperture value: $f = 2^{A_v / 2}$.
    - ISO: Extracted from `ISOSpeedRatings` array or `PhotographicSensitivity`.
- **String Formatting Protocol**:
  - Shutter speeds $< 1.0\text{s}$ format as fractions (e.g. `1/2000s`); values $\ge 1.0\text{s}$ format as decimal seconds (e.g. `1.5s`).
  - Missing values are cleanly omitted from the concise summary strip (`1/2000s · f/1.4 · ISO 100 · 85mm`) without displaying unsightly "Unknown" placeholders.

### 4.3 Hardware-Accelerated Quality Scoring
- **Apple Accelerate Focus Metric (`FocusMetricService`)**:
  - Computes photographic sharpness via a vectorized 2D discrete Laplacian convolution over a Planar8-to-PlanarF grayscale buffer using Apple Accelerate:
    1. Grayscale conversion: `vImageConvert_Planar8toPlanarF`.
    2. Gaussian pre-filter smoothing: 3x3 convolution via `vImageConvolve_PlanarF` with `gaussianKernel` (sum = 1.0).
    3. Laplacian edge detection: 3x3 4-connected convolution via `vImageConvolve_PlanarF` with `laplacianKernel`.
    4. Vectorized spatial variance: Row-by-row `vDSP.mean` and `vDSP.meanSquare` respecting buffer `rowBytes` padding.
    5. Sensor noise floor estimation: Pairwise Median Absolute Deviation (MAD) over a low-discrepancy golden-ratio sampling distribution:
       $$\sigma = \frac{\text{median}(|\Delta - \text{median}(\Delta)|)}{0.6745 \cdot \sqrt{2}}$$
    6. Noise floor correction: Subtracts compound noise variance $0.40625 \cdot \sigma^2$ from raw Laplacian variance.
    7. Compressive non-linear mapping: Compresses variance into 0.0–100.0 score range:
       $$\text{Score} = 100.0 \cdot \left(1.0 - \exp\left(-\frac{\sqrt{\text{Variance}_{\text{corrected}}}}{12.0}\right)\right)$$
  - **Performance SLA**: Measured compute latency is $< 2.0\text{ ms}$ per 1080p frame on Apple Silicon in release builds (>1,500 fps per thread).
- **Exposure Clipping Analysis (`ExposureMetricService`)**:
  - Uses hardware-accelerated 256-bin `vImage` histograms to measure blown highlights ($\text{pixel} \ge 254$) and crushed shadows ($\text{pixel} \le 2$).
- **Pure Apple Vision Aesthetics (`VisionAestheticsService`)**:
  - Executes `VNCalculateImageAestheticsScoresRequest` on Apple Neural Engine hardware (macOS 15+).
  - Retrieves `overallScore` (range $-1.0 \dots 1.0$), `isUtility` classification (detects receipts, documents, screenshots), and maps overall aesthetics linearly to the canonical 1.0–10.0 photographic scale.

### 4.4 Actor-Isolated Culling Engine & Transactional Undo
- **FIFO Disk Queue (`AsyncCullingActor`)**:
  - Serializes all disk operations (`Move to Selection/`, `Copy to Selection/`, `Move to Trash`) onto an actor-isolated serial executor.
  - Generates immutable `CullingExecutionRecord` audit trails containing all affected primary and companion URLs.
- **Transactional Undo Guarantees**:
  - Reverses operations atomically via `AsyncCullingActor.undo(record:)`.
  - **Zero Data Loss Guarantee on Copy Undo**: Undoing a copy operation strictly deletes only the replica in `Selection/` and guarantees the original file on the source SD card or local disk is never deleted or corrupted.
  - **In-Flight Cancellation**: Pending jobs marked for cancellation prior to disk I/O are dropped from the queue cleanly.

### 4.5 Caching & Image Preloading (`ThumbnailLoader` & `ThumbnailCache`)
- **Memory-Bounded Cache (`ThumbnailCache`)**:
  - Wraps `NSCache` with uncompressed bitmap byte-cost accounting (`bytesPerRow * height`).
  - Clamped between 256 MB and 4 GB (default 512 MB), responding dynamically to OS memory pressure notifications.
- **Sliding Prefetch Window (`ThumbnailLoader`)**:
  - Implements an asymmetric sliding prefetch window $[i-2 \dots i+3]$ around the current candidate index.
  - Automatically cancels out-of-window decode tasks when navigating rapidly across the album.
  - Uses `CGImageSourceCreateThumbnailAtIndex` with `kCGImageSourceCreateThumbnailWithTransform` to ensure hardware-accelerated downsampling and automatic EXIF orientation correction.

### 4.6 Zero-Latency Keyboard Event Routing (`KeyboardShortcutRouter`)
- **Architecture Rationale**: Standard SwiftUI `.onKeyPress` modifiers can be intercepted or swallowed when child UI controls (such as buttons or segmented pickers) gain focus.
- **Implementation**:
  - Registers a window-level local event monitor via `NSEvent.addLocalMonitorForEvents(matching: .keyDown)`.
  - Intercepts keystrokes prior to AppKit first-responder or SwiftUI button-focus routing:
    - Arrow keys (`←`, `→`, `↑`, `↓`) and Vim keys (`H`, `J`, `K`, `L`): Immediate navigation.
    - `M`: Move candidate and companions to `Selection/`.
    - `C`: Copy candidate and companions to `Selection/`.
    - `Delete` / `Backspace`: Move candidate and companions to System Trash.
    - `⌘Z`: Transactional undo.
    - `1`, `2`, `3`: Instant layout mode toggle (1-Up, 2-Up, 3-Up).
    - `Space`: 100% 1:1 zoom toggle; `Escape`: Reset zoom to fit.
    - `Tab`: Advance comparison slot focus.
    - `S`: Toggle synchronized zoom / pan lock.
  - **Responder Safety**: Automatically yields events if the active first responder is an `NSTextView` or `NSTextField`, ensuring user typing in search bars or text boxes is never intercepted.

---

## 5. Concurrency & Threading Model (Swift 6)

```
┌────────────────────────────────────────────────────────────────────────┐
│ Main Thread (@MainActor)                                              │
│  - SwiftUI View hierarchy rendering                                   │
│  - CullingWorkspaceViewModel & DuplicateFinderViewModel state         │
│  - KeyboardShortcutRouter event interception                          │
│  - Immediate optimistic UI updates (< 1.0 ms latency)                 │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
        ┌──────────────────────────┼──────────────────────────┐
        │ async / await            │ Task.detached            │ actor calls
        ▼                          ▼                          ▼
┌───────────────────┐    ┌───────────────────┐    ┌──────────────────────┐
│ AsyncCullingActor │    │ FocusMetricService│    │ VisionAesthetics-    │
│ (Actor)           │    │ (Accelerate pool) │    │ Service (Actor)      │
│  - Serial FIFO I/O│    │  - vImage convolve│    │  - Vision Neural     │
│  - Atomic moves   │    │  - vDSP variance  │    │    Engine request    │
│  - Safe undo/redo │    │  - Noise MAD      │    │  - Utility detection │
└───────────────────┘    └───────────────────┘    └──────────────────────┘
```

1. **Strict Concurrency Enforcement**: The project compiles with Swift 6 strict concurrency checks enabled.
2. **Value Types Across Boundaries**: All data structures crossing concurrency domains (`PhotoItem`, `ExifData`, `QualityScores`, `CullingJob`, `CullingExecutionRecord`) conform to `Sendable`.
3. **Actor Isolation for Mutable Resources**:
   - `AsyncCullingActor`: Isolates file mutations and undo stacks.
   - `ThumbnailCache` & `ThumbnailLoader`: Isolates in-memory decoded image caches and prefetch tasks.
   - `VisionAestheticsService`: Isolates Vision framework request handlers.
4. **Cooperative Multitasking**: Heavy numerical routines (`FocusMetricService`, `DirectoryScanner`, `DuplicateFinder`) execute via `Task.detached(priority: .userInitiated)` with frequent `Task.isCancelled` checkpoints to allow instant aborts on folder changes.

---

## 6. Test Organization & Verification Strategy

The automated test suite lives in `products/macos-desktop/tests/unit/` and executes via the Swift Testing framework (`@Suite`, `@Test`) and XCTest:

```bash
swift test --package-path products/macos-desktop
```

### 6.1 Test Suite Taxonomy (204 Tests across 24 Suites)
1. **Core Domain & Algorithm Unit Tests**:
   - `PhotoSelectorKitTests`: Basic library initialization and version contract.
   - `ImageMetadataReaderTests`: APEX formula calculations, rational formatting, and camera model fallbacks.
   - `FocusMetricServiceTests`: Laplacian convolution accuracy, noise floor estimation, and compressive score mapping.
   - `ExposureMetricServiceTests`: 256-bin histogram highlight and shadow clipping calculations.
   - `VisionAestheticsServiceTests`: Apple Vision request creation, score normalization, and OS version gating.
   - `DirectoryScannerTests`: Recursive traversal, companion pairing, and strict `Selection/` subfolder exclusions.
   - `DuplicateFinderTests`: Chunked streaming SHA-256 hashing and identical file clustering.
   - `ThumbnailLoaderTests`: Downsampling dimension constraints, ImageIO options, and in-memory caching.
   - `LibraryStatisticsEngineTests`: EXIF distribution histograms and summary aggregations.
   - `SettingsPersistenceTests`: UserDefaults serialization, default fallbacks, and input sanitization.
2. **ViewModel & User Interaction Tests**:
   - `CullingWorkspaceViewModelTests`: Optimistic UI mutations, culling counters, undo availability, and folder state.
   - `DuplicateFinderViewModelTests`: Duplicate review state machine, keep-newest/keep-highest selection, and batch resolution.
   - `FocusViewAndCullingAffordanceTests`: 1-Up, 2-Up, and 3-Up sliding triplet mechanics and slot boundary handling.
   - `KeyboardShortcutRouterTests`: Keystroke code resolution, modifier matching, and text responder yield safety.
3. **End-to-End Workflow & Integration Tests**:
   - `E2EIntegrationTests`: Multi-module end-to-end integration and directory structure validation.
   - `EndToEndCullingWorkflowTests`: Full photographer simulation: folder ingestion, sliding triplet triage, focus scoring, companion preservation, instant undo, duplicate cleanup, and statistics generation.
4. **Adversarial & Concurrency Stress Tests**:
   - `AdversarialTier5HardeningTests`: Corrupt image buffer resilience, non-power-of-two dimensions, and extreme boundary clamping.
   - `AdversarialScoringStressTests`: Validates the sub-2.0ms 1080p focus metric compute SLA under multithreaded loads.
   - `AdversarialM2RemedyChallengeTests`: Edge case companion preservation, deep nested DCIM folder trees, and read-only media rollback.
   - `AdversarialM3CullingStressTests`: Rapid burst keystrokes (100 culls at < 1.0ms each), in-flight undo bursts, and ABA race prevention.
   - `AdversarialM3ScannerAndThumbnailTests`: Rapid sequential navigation task cancellation and concurrent thumbnail loader throughput.
   - `FileCullingAndDuplicateAdversarialTests`: Filesystem error recovery, hash collision safety, and permission failure handling.

---

## 7. Known Structural Observations & Evolution

1. **View Model Placement**: `CullingWorkspaceViewModel` and `KeyboardShortcutRouter` currently reside inside `PhotoSelectorKit/` for comprehensive unit testability without requiring application-host bundling. A future cleanup may relocate pure SwiftUI view models into `PhotoSelectorApp` while retaining headless domain controllers in `PhotoSelectorKit`.
2. **Settings Window Architecture**: The settings interface leverages the standard SwiftUI `Settings` scene (`⌘,`). On macOS 14+, SwiftUI manages settings window lifecycle natively.
