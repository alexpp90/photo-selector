# Shared — Feature Parity & Sync Policy

> How a feature added to one product is evaluated across all five products
> (four active native solutions alongside the archived legacy Desktop).
> The at-a-glance ecosystem summary lives in the root [`README.md`](../../README.md);
> this file holds the authoritative sync policy, the Dual-Desktop Parity & Feature Triage Protocol,
> and the cross-product feature mapping matrix.

---

## 1. Feature Sync Policy

Sync is **multidirectional**. A feature or pattern proven in any of the five products is
evaluated for the others — Desktop → Android was the historical direction, but PhotoTok,
Android Desktop, macOS Desktop, and Linux Desktop originate work too, and it must travel across. The evaluation is a mandatory step of the
`retrospective` (maintainer-private agent framework) skill, not an optional courtesy.

When a new feature is added to any product, it must be evaluated for inclusion in the others:
*   **macOS Desktop:** Should implement natively utilizing Swift 6, SwiftUI, Accelerate, and Apple Vision.
*   **Linux Desktop:** Should implement natively utilizing Python 3.12+, PyGObject, GTK4, Libadwaita, GExiv2, and NumPy, adhering strictly to GNOME Human Interface Guidelines (HIG).
*   **Android Desktop (Tablet/DeX mode):** Should include the feature if technically feasible on Android tablets ($\ge 840$ dp) and Samsung DeX.
*   **PhotoTok (Phone mode):** Should include the feature if it works well on small portrait screens ($< 600$ dp); may omit with documented rationale.
*   **Excluded features** (e.g. Ollama VLM on mobile/macOS/Linux, ExifTool on mobile/macOS/Linux, Apple Vision on non-macOS platforms) are permanently excluded from those platforms.
*   Feature sync evaluations are documented in the mapping table below, and the resulting
    behaviour in the owning product's `docs/products/<product>/REQUIREMENTS.md`.

---

## 2. Dual-Desktop Parity & Feature Triage Protocol (macOS Desktop & Linux Desktop)

The repository provides two premier desktop workstation products:
- **macOS Desktop** (`products/macos-desktop/`, Swift 6 + SwiftUI + AppKit)
- **Linux Desktop** (`products/linux-desktop/`, Python 3.12+ + PyGObject + GTK4 / Libadwaita)

Both desktop applications serve professional and hobbyist photographers demanding extreme throughput, tactile culling, and instant decision-making on high-resolution displays. To maintain conceptual integrity and effortless muscle memory transition between Mac and Linux workstations while respecting native platform design philosophies, the following protocol governs all desktop feature planning and implementation.

### 2.1 Core Architectural Principles

1.  **Workstation Parity Without Artificial Homogeneity**:
    macOS Desktop and Linux Desktop share photographic mental models, file system contracts, optical data structures, and keyboard muscle memory. However, each is an authentic, first-class citizen of its native desktop environment. Code is **never** copied or shared between desktop products; only contracts, invariants, and behaviors are synchronized:
    *   **Sub-2ms Focus Scoring**: Optical scoring achieves $< 2.0\text{ms}$ reference compute SLA (< 2.0 ms median on dedicated CPU; CI runner tolerance up to 4.0 ms) via algorithmic acceleration: center 50% ROI cropping ($960 \times 540$) and strided low-discrepancy MAD sampling ($\le 2,048$ samples).
    *   **Dynamic Scanner Exclusion**: Directory traversal dynamically excludes both standard selection folders (`Selection`, `Selected`, `PhotoTok_Selection`, `PhotoTok_LeftSwipe`) and user-configured custom targets (`R-LINUX-PREF-02`) by exact component matching.
    *   **Two-Phase Staged Transactional Moves**: Cross-mount moves (`EXDEV`) enforce a two-phase staged protocol with atomic destination rollback on failure, guaranteeing zero source media data loss and preventing torn companion sets.
    *   **Robust Keyboard Routing**: Window-level key event controllers intercept hotkeys with case-insensitivity and modifier lock masking (`state & ~(LOCK_MASK | MOD2_MASK)`), preventing silent failure under CapsLock or NumLock.
2.  **GNOME HIG Look-and-Feel Supremacy Over Feature Bloat**:
    On Linux, user experience strictly conforms to GNOME Human Interface Guidelines (HIG) and Libadwaita conventions:
    * Clean `AdwHeaderBar` with unified window controls and view switchers.
    * `AdwPreferencesWindow` (`Ctrl+,`) for application settings.
    * Integrated Libadwaita studio dark styling via `AdwStyleManager`.
    * System font rendering, standard GNOME spacing tokens, and `AdwToast` non-blocking notifications.
    * *Core Rule*: If a secondary macOS feature or control layout would require awkward, non-native widgets, persistent drawers, or violate GNOME HIG conventions, **GNOME HIG elegance, simplicity, and distraction-free viewing take precedence over forced visual parity**.
3.  **Platform-Exclusive Capabilities Recognized**:
    Platform-specific hardware and OS frameworks (such as Apple Vision Neural Engine or Apple Accelerate) must not be forcibly replicated on Linux using brittle, bloated, or external server dependencies.

---

### 2.2 Four-Tier Feature Triage Matrix

Every desktop feature proposal or modification is classified into one of four distinct tiers:

```
┌────────────────────────────────────────────────────────────────────────┐
│                        FEATURE TRIAGE PIPELINE                         │
├────────────────────────────────────────────────────────────────────────┤
│ Tier 1: Core Photographic Invariants (Mandatory 100% Parity)          │
│  - Ingestion, companion RAW+JPEG binding, .xmp preservation,           │
│    Selection/ subfolder exclusion, atomic Move/Copy, Trash,            │
│    transactional copy-undo safety, zero-latency keyboard routing,      │
│    APEX EXIF optical metadata, focus scoring 0.0–100.0 (Laplacian+MAD).│
│  ==> RULE: Non-negotiable 100% parity across both desktops.            │
├────────────────────────────────────────────────────────────────────────┤
│ Tier 2: Platform-Native Idiomatic Parity (Adapted to Native HIG)       │
│  - Settings presentation (macOS Settings ⌘, vs AdwPreferencesWindow)   │
│  - Secondary tools access (macOS Menu Bar vs GNOME Primary Menu)       │
│  - Window chrome & studio dark palette (Apple HIG vs Libadwaita)       │
│  - System Trash (NSWorkspace vs GFile.trash / FreeDesktop Trash spec)  │
│  ==> RULE: Functional parity achieved via native platform idioms.       │
├────────────────────────────────────────────────────────────────────────┤
│ Tier 3: Platform-Exclusive Capabilities (Documented Exclusions)        │
│  - Apple Vision Neural Engine aesthetic evaluation on macOS 15+        │
│  - Linux FreeDesktop / Wayland portal & GIO integrations               │
│  ==> RULE: Formally documented in FEATURE_PARITY.md; no fake shims.    │
├────────────────────────────────────────────────────────────────────────┤
│ Tier 4: High-Friction User Consultation Triggers                       │
│  - Features requiring heavy background services or daemons             │
│  - Features introducing >100MB runtime bloat (e.g. PyTorch, Ollama)    │
│  - Controls or layouts that conflict with GNOME HIG                    │
│  ==> RULE: STOP AND CONSULT USER before implementation.                │
└────────────────────────────────────────────────────────────────────────┘
```

#### Detailed Tier Specifications

*   **Tier 1: Core Photographic Invariants (100% Mandatory Parity)**
    These invariants are non-negotiable across macOS Desktop and Linux Desktop:
    *   **Directory Ingestion**: Asynchronous directory scanning of mounted SD cards (`/Volumes/<Card>` on macOS; `/media/$USER/<Card>` or `/run/media/$USER/<Card>` on Linux) and local folders with natural alphanumeric sorting (e.g. `DSC_0002` before `DSC_0010`). Supported formats follow the shared contract in [`SUPPORTED_FORMATS.md`](SUPPORTED_FORMATS.md) (11 required RAW formats incl. CR3, NRW, SRW; JPEG, HEIC/HEIF, PNG, TIFF, WebP; a recognized tier of rarer RAWs), proven per product against a real-camera corpus.
    *   **Companion RAW+JPEG & Sidecar Binding**: Automatic grouping of primary images with companion files in the same directory: RAW+JPEG pairs, `.xmp` sidecars (`<file>.xmp` and `<stem>.xmp`), and Lightroom derivatives (`<stem>-Edit.*`, `<stem>_Edit.*`).
    *   **Selection Subfolder Exclusion**: Strict case-insensitive exclusion of default subfolders (`Selection`, `Selected`, `PhotoTok_Selection`, `PhotoTok_LeftSwipe`) and any user-configured custom selection folder (`R-LINUX-PREF-02`), matching exact directory components to prevent circular re-scanning while preserving substring folders (`Trip_Selection/`), unless explicitly chosen as the scan root.
    *   **Atomic Move / Copy Operations**: Culling a photo via Move (`M`) or Copy (`C`) to `Selection/` obeys the non-destructive contract in [`FILE_OPERATIONS.md`](FILE_OPERATIONS.md): it never overwrites an existing file (applying a shared numeric suffix `-2`, `-3`, … across the entire companion bundle on collision), operates atomically on the primary image and all bound companion files simultaneously, and verifies the destination before unlinking any source file.
    *   **FreeDesktop / OS Trash Integration**: Trashing (`Delete` / `Backspace`) safely moves the primary image and all bound companions to the operating system trash (macOS `NSWorkspace` / `FileManager.trashItem`, Linux FreeDesktop Trash specification via `Gio.File.trash()`), recording exact trashed paths for deterministic restoration. Permanent file deletion is never performed without explicit user intervention.
    *   **Transactional Undo Safety**: The undo stack (`⌘Z` on macOS, `Ctrl+Z` on Linux) obeys [`FILE_OPERATIONS.md`](FILE_OPERATIONS.md): undoing a copy operation removes only the destination replica in `Selection/` (original source files on SD cards or local disks are **never** touched or deleted), and undoing a move or trash operation never overwrites an occupied original path, restoring the bundle under a shared numeric suffix instead.
    *   **Zero-Latency Keyboard Routing**: Window-level key event interception capturing keystrokes before widget focus traversal: Left/Right arrows, `H`/`L`, `J`/`K` (navigation); `M` (Move to Selection); `C` (Copy to Selection); `Delete` / `Backspace` (Trash); `1`, `2`, `3` (1-Up, 2-Up, 3-Up Focus Mode); `Space` (100% 1:1 pixel zoom toggle); `Escape` (reset zoom / dismiss); `Tab` (cycle slot focus); `F` (filmstrip toggle); `I` (HUD toggle); `S` (synchronized zoom/pan lock). Keystrokes yield to default behavior only when an editable text entry is actively focused. Keystroke evaluation must be case-insensitive (treating 'm' and 'M', 'c' and 'C' identically) and mask out modifier lock states (ignoring CapsLock and NumLock) to prevent silent culling failures.
    *   **Standardized APEX EXIF Metadata**: Standardized optical properties (`shutterSpeed`, `aperture`, `iso`, `focalLength`, `lens`, `cameraModel`, `isFallback`). Exposure values use APEX conversions when raw tags are missing ($T_v \to 2^{-T_v}$, $A_v \to 2^{A_v/2}$). Fractions format $< 1.0\text{s}$ (e.g. `1/250s`) and decimals $\ge 1.0\text{s}$ (e.g. `1.5s`). Missing tags produce clean omissions rather than "Unknown" placeholder text.
    *   **Focus Quality Scoring & Noise Subtraction**: Optical focus score normalized to 0.0–100.0 computed via 2D Laplacian convolution with 3x3 Gaussian pre-filter smoothing on a center 50% ROI proxy ($960 \times 540$), spatial variance calculation, and sensor noise floor subtraction estimated via strided low-discrepancy Median Absolute Deviation (MAD, $\le 2,048$ samples), ensuring the $< 2.0\text{ms}$ reference compute SLA is met on modern CPUs (with explicit tolerance up to $< 4.0\text{ms}$ median on shared/virtualized CI runners). Standardized thresholds:
        - **Blurry**: Score $< 35.0$
        - **Acceptable**: $35.0 \le \text{Score} < 70.0$
        - **Sharp / Crisp**: $\text{Score} \ge 70.0$
    *   **Metric Omission Rule**: Quality metrics not yet computed must be completely omitted from UI badges, cards, and HUD overlays (never showing "N/A" or "0.0" placeholders).

*   **Tier 2: Platform-Native Idiomatic Parity (Adapted to Native HIG)**
    Functional capabilities map directly to the idiomatic conventions of each desktop platform:
    *   **Visual Atmosphere**: Studio dark palette highlighting photo colors. macOS uses `.preferredColorScheme(.dark)` with system vibrancy; Linux uses `AdwStyleManager` with dark palette surfaces (`#141417`, `#1C1C20`, `#3A3A42`) and high-contrast typography (`#FFFFFF` headings, `#D9D9D9` metadata).
    *   **Window Chrome**: macOS uses native AppKit title bar and toolbar items; Linux uses standard `AdwHeaderBar` with view switcher and window buttons.
    *   **Settings Window**: macOS houses settings in the standard Settings scene (`⌘,`); Linux presents a modal `AdwPreferencesWindow` (`Ctrl+,`) using standard `AdwPreferencesGroup` and `AdwActionRow` widgets.
    *   **Secondary Utilities**: macOS exposes Duplicate Finder (`⌘D`) and Library Statistics (`⌘L`) under the Menu Bar "Tools" menu; Linux exposes them via the GNOME Primary Menu button (`AdwMenuButton`) and global accelerators (`Ctrl+D`, `Ctrl+L`).

*   **Tier 3: Platform-Exclusive Capabilities (Documented Exclusions)**
    Features leveraging platform-exclusive hardware or system runtime frameworks:
    *   **Apple Vision Aesthetic Scoring**: macOS 15+ provides `VNCalculateImageAestheticsScoresRequest` accelerated on-device via Apple Neural Engine (ANE) with zero external dependencies. Linux has no built-in operating system aesthetic scoring API.
    *   **Exclusion Policy**: We do **not** bundle gigabytes of PyTorch, CUDA, or local Ollama server dependencies into Linux Desktop. On Linux, aesthetic scoring is omitted from the core culling workflow, preserving an ultra-compact package footprint (~2 MB `.deb`), instant cold start, and sub-second execution. If aesthetic evaluation is introduced on Linux in the future, it must be an optional, lightweight plug-in (e.g. ONNX Runtime with MobileNet NIMA) that never impedes core culling throughput.

*   **Tier 4: High-Friction User Consultation Triggers**
    An explicit protocol that prevents developer agents from unilaterally introducing bloated, awkward, or non-native features to Linux.

---

### 2.3 User Consultation Protocol

When evaluating a feature for Linux Desktop or porting from macOS, the developer agent **MUST STOP AND CONSULT THE USER** if any of the following trigger conditions are met:

1.  **Heavy Dependency Trigger**: Realizing the feature on Linux requires introducing background daemons, external server runtimes (e.g. Ollama, Docker), non-standard system packages, or $> 100\text{ MB}$ runtime dependencies (e.g. PyTorch, TensorFlow) not available in the base Debian 13 (Trixie) main repository.
2.  **GNOME HIG Contradiction Trigger**: The feature demands persistent multi-column drawer sidebars, cluttered status bar panels, or non-native UI widgets that contradict GNOME HIG's minimalist, content-focused design principles.
3.  **Disproportionate Engineering Overhead Trigger**: Implementing the feature on Linux requires $> 3\times$ the implementation effort or creates ongoing maintenance risks due to platform divergences (e.g. specialized Wayland protocol extensions not universally supported).

#### Structured Consultation Template

When triggered, the agent must present the user with a structured assessment:
```
**Feature Name**: [Name of proposed feature]
**Originating Product**: [macOS Desktop / Other]
**Friction Trigger**: [Heavy Dependencies / GNOME HIG Conflict / High Overhead]
**Detailed Hurdle**: [Specific explanation of why the feature resists clean Linux implementation]
**Proposed Options**:
- Option A (Recommended): Omit from Linux Desktop; record as macOS-exclusive in FEATURE_PARITY.md.
- Option B (Lightweight Native): Implement a simplified GNOME HIG-compliant alternative.
- Option C (Full Port With Costs): Implement full feature accepting the runtime/UI tradeoffs.
**Team Recommendation**: [Explicit justification prioritizing speed, stability, and GNOME HIG elegance]
```

---

### 2.4 Comparative Desktop Subsystem Parity Table

| Subsystem / Feature | macOS Desktop (`products/macos-desktop/`) | Linux Desktop (`products/linux-desktop/`) | Triage Tier | Parity Status |
|---|---|---|---|---|
| **Tech Stack** | Swift 6 + SwiftUI + AppKit | Python 3.12+ + PyGObject + GTK4 / Libadwaita | Native Stacks | Independent native codebases |
| **Directory Ingestion** | `DirectoryScanner` async stream | Progressive asynchronous directory scanner | Tier 1 | ✅ 100% Invariant Parity |
| **Companion Pairing** | RAW+JPEG, `.xmp`, `-Edit.*` binding | RAW+JPEG, `.xmp`, `-Edit.*` binding | Tier 1 | ✅ 100% Invariant Parity |
| **Selection Subfolder Exclusion** | Case-insensitive exclusion of `Selection` & custom | Dynamic exclusion of `Selection` & custom | Tier 1 | ✅ 100% Invariant Parity |
| **Atomic Culling (M, C, Del)** | `AsyncCullingActor` FIFO queue | Asynchronous culling queue with EXDEV rollback | Tier 1 | ✅ 100% Invariant Parity |
| **Transactional Copy-Undo** | Removes Selection copy, protects source | Removes Selection copy, protects source | Tier 1 | ✅ 100% Invariant Parity |
| **Zero-Latency Keyboard Routing** | `NSEvent` local monitor + `.regular` activation policy (verified by `ui_smoke_test.sh`) | `GtkEventControllerKey` root capture | Tier 1 | ✅ 100% Invariant Parity |
| **Comparison Modes (1/2/3-Up)** | 1-Up, 2-Up, 3-Up one-over-two (`FocusTripletLayout`: current full-width top, prev bottom-left, next bottom-right) | 1-Up, 2-Up, 3-Up one-over-two (`Gtk.Grid`: current full-width top, prev bottom-left, next bottom-right, `R-LINUX-UI-06`) | Tier 1 | ✅ 100% Invariant Parity |
| **Boundary Slot Indicators** | `BoundarySlotPane` ("First" / "Last") | `BoundarySlotPane` ("First" / "Last") | Tier 1 | ✅ 100% Invariant Parity |
| **1:1 Actual-Pixel Zoom** | Space toggle, Escape reset | Space toggle, Escape reset | Tier 1 | ✅ 100% Invariant Parity |
| **Optical EXIF Model** | `ExifData` with APEX fallbacks | `ExposureMetadata` with APEX fallbacks | Tier 1 | ✅ 100% Invariant Parity |
| **Focus Metric (Laplacian+MAD)** | Accelerate `vImage` / `vDSP` ($< 2.0\text{ms}$) | Vectorized NumPy with 50% ROI & strided MAD ($< 2.0\text{ms}$ ref, CI tol) | Tier 1 | ✅ 100% Invariant Parity |
| **Exposure Clipping Analysis** | 256-bin hardware histogram ($\ge 254, \le 2$) | 256-bin NumPy histogram ($\ge 254, \le 2$) | Tier 1 | ✅ 100% Invariant Parity |
| **Aesthetic Scoring (AI)** | Native Apple Vision (`VNCalculateImageAestheticsScores`) | Omitted (No native OS neural engine runtime) | Tier 3 | ❌ macOS-Exclusive Capability |
| **Window Chrome & Palette** | Studio Dark `.preferredColorScheme(.dark)` | Studio Dark Libadwaita (`#141417`, `#1C1C20`) | Tier 2 | ✅ Platform HIG Idiomatic |
| **Settings Presentation** | macOS Settings Scene (`⌘,`) | `AdwPreferencesWindow` (`Ctrl+,`) | Tier 2 | ✅ Platform HIG Idiomatic |
| **Secondary Tools Access** | macOS Menu Bar "Tools" (`⌘D`, `⌘L`) | GNOME Primary Menu / Shortcuts (`Ctrl+D`, `Ctrl+L`) | Tier 2 | ✅ Platform HIG Idiomatic |
| **Duplicate Finder** | CryptoKit streaming SHA-256 | Streaming SHA-256 duplicate engine | Tier 2 | ✅ Platform HIG Idiomatic |
| **Library Statistics** | SwiftUI EXIF distribution charts | Libadwaita EXIF distribution charts | Tier 2 | ✅ Platform HIG Idiomatic |
| **Distribution Pipeline** | Homebrew Tap `alexpp90/homebrew-tap` (`Cask` & `Formula`) | Debian 13 APT Repository `alexpp90/apt` (`.deb`) | Tier 2 | ✅ Platform Native Distribution |

---

## 3. Cross-Product Feature Mapping Matrix

The full matrix covers all five products in the repository:

| Feature | Desktop (Python/Tk) | macOS Desktop (Swift) | Linux Desktop (GTK4/Adw) | Android Desktop (Tablet/DeX) | PhotoTok (Phone) | Notes |
|---|---|---|---|---|---|---|
| **Photo Selector (Review & Cull)** | ✅ Full | ✅ Full | ✅ Full | ✅ Full | ✅ Simplified | PhotoTok uses gesture feed instead of buttons |
| **Focus Mode (Comparison)** | ✅ Standard / Focus | ✅ 1-Up / 2-Up / 3-Up Triplet | ✅ 1-Up / 2-Up / 3-Up Triplet | ✅ Side-by-side | ❌ Omitted | Desktops: current on top, neighbours below (Linux pending, see §4) |
| **Fullscreen / 1:1 Pixel Zoom** | ✅ Full | ✅ Full (>85% canvas) | ✅ Full (>85% canvas) | ✅ Full + gestures | ✅ Full + gestures | Spacebar toggle on desktop; pinch on mobile |
| **Sharpness Analysis** | ✅ Full (OpenCV) | ✅ Full (Accelerate vImage) | ✅ Full (NumPy Laplacian) | ✅ Full (OpenCV) | ❌ Omitted | Noise-corrected Laplacian variance (<2ms ref SLA via 50% ROI & strided MAD, CI tol) |
| **Noise Analysis (MAD)** | ✅ Full (MAD) | ✅ Full (vImage MAD) | ✅ Full (NumPy MAD) | ✅ Full (MAD) | ❌ Omitted | Median Absolute Deviation sensor noise floor |
| **Highlight & Shadow Clipping** | ✅ Full (Grayscale) | ✅ Full (vImage 256-bin) | ✅ Full (NumPy 256-bin) | ✅ Full | ❌ Omitted | Thresholds $\ge 254$ (blown) and $\le 2$ (crushed) |
| **Library Statistics** | ✅ Full (Matplotlib) | ✅ Full (SwiftUI Charts) | ✅ Full (Libadwaita Charts) | ✅ Full (Vico) | ✅ Simplified | Optical EXIF distribution histograms |
| **Duplicate Finder** | ✅ Full (SHA-256) | ✅ Full (CryptoKit SHA-256) | ✅ Full (Streaming SHA-256) | ✅ Full | ✅ Grid view | Streaming chunked hashing with size pre-filter |
| **Move / Copy to Selection** | ✅ Full | ✅ Full (AsyncCullingActor) | ✅ Full (GIO Async) | ✅ Full (SAF) | ✅ Full (SAF) | Atomic companion syncing with rollback safety |
| **Companion Pairing (RAW/JPEG/XMP)** | ✅ Full | ✅ Full (ImageMetadataReader) | ✅ Full (GExiv2 Reader) | ✅ Full | ✅ Full | Automated companion grouping and culling |
| **Image Grouping (Similarity)** | ✅ Full (dHash) | ✅ Full (Duplicate clusters) | ✅ Full (Duplicate clusters) | ✅ Full | ✅ Time-only | Burst sequence grouping |
| **Persistent Score Cache** | ✅ SQLite MRU | ❌ (In-Memory LRU) | ❌ (In-Memory LRU) | ✅ Room DB | ❌ (Lightweight) | Desktops rely on sub-2ms compute & memory LRU |
| **Apple Vision Aesthetics** | ✅ (PyObjC Bridge) | ✅ (Pure Native ANE) | ❌ Excluded (Tier 3) | ❌ Excluded | ❌ Excluded | On-device Neural Engine via Apple Vision |
| **Local AI (Ollama VLM)** | ✅ Full | ❌ Excluded (Tier 3) | ❌ Excluded (Tier 3/4) | ❌ Excluded | ❌ Excluded | Excluded from native apps to prevent runtime bloat |
| **Zero-Latency Keyboard Routing** | ✅ Tkinter Bindings | ✅ NSEvent Local Monitor | ✅ GtkEventControllerKey | ✅ Hardware Listener | ❌ N/A (Touch) | Intercepts keys before widget focus traps |
| **Native Settings Window** | ❌ (Inline Panel) | ✅ macOS Settings (`⌘,`) | ✅ AdwPreferences (`Ctrl+,`)| ✅ Dialog | ✅ Dialog | Clean separation from main culling canvas |
| **Secondary Tools Separation** | ❌ (Main Window) | ✅ Menu Bar "Tools" | ✅ Primary Menu Button | ✅ Menu | ❌ N/A | Kept off primary workspace to prevent clutter |
| **CLI / Headless Mode** | ✅ Full | ❌ Headless Kit Only | ❌ Standalone App | ❌ N/A | ❌ N/A | Scripting entry points |
| **Package Distribution** | ✅ Brew Formula+Cask | ✅ Native Homebrew Cask | ✅ Debian 13 APT (.deb) | ❌ Play Store / APK | ❌ Play Store / APK | Platform-native package management |
| **SMB / Remote Path Resolution** | ✅ Network share path | ✅ Darwin POSIX | ✅ GIO / GVFS Mounts | ❌ (SAF Provider) | ❌ (SAF Provider) | Handled transparently by OS file system / GVFS |
| **EXIF Extraction** | ✅ Bundled ExifTool | ✅ Apple ImageIO Native | ✅ GExiv2 Native C++ | ❌ (AndroidX) | ❌ (AndroidX) | Robust optical metadata parsing with APEX math |

---

## 4. Pattern Parity: Interaction & Performance

Features are not the only thing that ports. A pattern proven in any product — an interaction
model, a data-access fix, a guidance approach — is evaluated for the other products and the
decision recorded here, including a decision not to port.

Ported means **reimplemented in the target's own stack and UX model**. Copying a file between
products is a defect (the separation rule of the maintainer's agent framework, `ai/ROUTING.md`).

| Pattern | Source | Desktop | macOS Desktop | Linux Desktop | Android Desktop | PhotoTok | Decided |
|---|---|---|---|---|---|---|---|
| Progressive SAF enumeration | PhotoTok 2026-07-31 | ❌ N/A | ❌ N/A | ❌ N/A | ✅ ported 2026-08-08 | ✅ origin | 2026-08-08 |
| Optimistic filing + deferred deletion with undo | PhotoTok 2026-07-31 | ◐ partial | ✅ ported 2026-10-07 | ✅ ported 2026-10-09 | ✅ ported 2026-08-08 | ✅ origin | 2026-08-08 |
| Coach marks in place, replacing shortcut list | PhotoTok 2026-07-31 | ❌ solved differently | ❌ solved differently | ❌ solved differently | ✅ ported 2026-08-08 | ✅ origin | 2026-08-08 |
| First-run explanations derived from live settings | PhotoTok 2026-07-24 | ❌ solved differently | ❌ solved differently | ❌ solved differently | ✅ ported 2026-08-08 | ✅ origin | 2026-08-08 |
| Symmetric queuing of conflicting long passes | Desktop 2026-07-24 | ✅ origin | ❌ N/A (Actor queue) | ❌ N/A (Async queue) | ✅ ported 2026-08-08 | ❌ N/A | 2026-08-08 |
| Neighbour image prefetch | Desktop (`preload`) | ✅ origin | ✅ independent | ✅ ported 2026-10-09 | ✅ ported 2026-08-08 | ✅ independent | 2026-08-08 |
| Filing wording names configured Selection folder | Android Desktop 2026-08-08 | ◐ gap | ✅ ported 2026-10-07 | ✅ ported 2026-10-09 | ✅ origin | ◐ gap | 2026-08-08 |
| Shared "Selection" folder standard & exclusions | Cross-product 2026-10-03 | ✅ default "Selection" | ✅ default "Selection" | ✅ default "Selection" | ✅ default "Selection" | ✅ default "Selection" | 2026-10-03 |
| Bounded LRU image cache with teardown on exit | Desktop 2026-10-03 | ✅ origin | ✅ independent | ✅ ported 2026-10-09 | ✅ independent | ✅ independent | 2026-10-03 |
| Noise-robust sharpness (Laplacian + MAD noise floor) | Desktop 2026-10-05 | ✅ origin | ✅ ported 2026-10-07 | ✅ ported 2026-10-09 | ⬜ evaluate | ❌ N/A | 2026-10-05 |
| Borderless maximized preview (>80–85% window space) | macOS Desktop 2026-10-07 | ⬜ evaluate | ✅ origin | ✅ ported 2026-10-09 | ✅ independent | ❌ N/A | 2026-10-07 |
| Actor-isolated / async transactional culling undo | macOS Desktop 2026-10-07 | ⬜ evaluate | ✅ origin | ✅ ported 2026-10-15 (the 2026-10-09 port was synchronous on the GTK main loop; now one background worker, busy state in the action bar) | ⬜ evaluate | ⬜ evaluate | 2026-10-07 |
| One-over-two 3-Up (current full-width on top, previous bottom-left, next bottom-right) with boundary slots | macOS Desktop 2026-10-07, corrected 2026-10-10 | ✅ independent (top/bottom grid, REQ-DESK-VIEW.02) | ✅ origin | ✅ ported 2026-10-17 (`U-08`, `R-LINUX-UI-06` two-row `Gtk.Grid` with 55/45 split) | ✅ independent (REQ-AND-LAYOUT.03) | ❌ N/A | 2026-10-17 |
| Keyboard delivery verified in the running app (activation/focus precondition asserted, real key events posted) | macOS Desktop 2026-10-10 | ◐ partial (focus re-assert after delegation, palette 2026-07-24; no running-app test) | ✅ origin | ⬜ evaluate (Wayland focus; no real-event test) | ⬜ evaluate (hardware-keyboard instrumented test on DeX) | ❌ N/A (touch) | 2026-10-10 |
| Zoom gestures on the untransformed viewport; controls clickable while zoomed; 1:1 computed from pixel size | macOS Desktop 2026-10-10 | ❌ N/A (Tk canvas cannot overflow its widget) | ✅ origin | ⬜ evaluate | ◐ partial (clipToBounds + pointerInput(Unit), palette 2026-10-03; verify controls while zoomed and the 2.5× "100%") | ⬜ evaluate | 2026-10-10 |
| One directory listing per folder, companions grouped in memory, first batch of 1, deferred EXIF, thumbnail-first decode | macOS Desktop 2026-10-10 | ◐ mitigated (mtime-keyed lru_cache listing in `find_related_files`) | ✅ origin | ✅ ported 2026-10-14 (one listing per directory, companions grouped in memory; first batch still 30) | ✅ independent (one SAF query per directory, REQ-AND-PERF.03) | ✅ independent (SAF origin, bolt 2026-07-31) | 2026-10-10 |
| Zero-latency window-level keyboard shortcut routing | macOS Desktop 2026-10-07 | ◐ partial | ✅ origin | ✅ ported 2026-10-09 | ✅ independent | ❌ N/A | 2026-10-07 |
| Parent-scoped sibling pairing to prevent data loss | PhotoTok 2026-10-09 | ⬜ evaluate | ✅ ported 2026-10-07 | ✅ ported 2026-10-09 | ⬜ evaluate | ✅ origin | 2026-10-09 |
| Studio dark theme with pure white text hierarchy | macOS Desktop 2026-10-07 | ⬜ evaluate | ✅ origin | ✅ ported 2026-10-09 | ⬜ evaluate | ⬜ evaluate | 2026-10-07 |
| Self-move guard, transactional companion rollback & APEX EXIF fallbacks | Cross-product 2026-10-14 | ✅ ported 2026-10-14 | ✅ ported 2026-10-14 | ✅ origin | ✅ ported 2026-10-14 | ✅ ported 2026-10-14 | 2026-10-14 |
| Camera RAW displayed through its embedded JPEG preview (no toolkit RAW loader assumed), decoded off the UI thread | Linux Desktop 2026-10-14 | ✅ independent (embedded preview, rawpy fallback when smaller than the view) | ❌ N/A (ImageIO decodes RAW natively; embedded preview first) | ✅ origin | ✅ ported 2026-10-15 (`:core` EmbeddedPreviewExtractor incl. CR3, via Coil fetcher) | ✅ ported 2026-10-15 (same `:core` extractor, own Coil fetcher) | 2026-10-15 |
| Supported-format contract (`testdata/formats/manifest.json`) proven by per-product conformance tests against a real-camera corpus; preview smaller than `min(1024, native)` falls back to a real decode | Cross-product 2026-10-15 | ✅ 2026-10-15 (gaps: HEIC display/score; CR3/RAF/RW2 EXIF without ExifTool) | ✅ 2026-10-15 (no gaps) | ✅ 2026-10-15 (no gaps; GTK paths CI-only) | ✅ 2026-10-15 (gaps: HEIF, Pixel DNG — Robolectric only) | ✅ 2026-10-15 (gaps: HEIF, Pixel DNG — Robolectric only) | 2026-10-15 |
| Camera make & model in the EXIF data contract (trimmed of padding) | Cross-product 2026-10-15 | ❌ N/A — archived (legacy `ExifData` has neither field; conformance test asserts they stay uncompared until added) | ✅ 2026-10-15 (`cameraMake` + `cameraModel`) | ✅ 2026-10-15 (metadata reader) | ✅ 2026-10-15 (`:core` `ExifData.make/model`) | ✅ 2026-10-15 (`:core`) | 2026-10-15 |
| Cloud folders whose on-disk names are opaque IDs (GVFS Google Drive): format, pairing, exclusion and destination names from the display name; network files parsed from one in-memory read | Linux Desktop 2026-10-14 | ⬜ evaluate (same GVFS paths when run on Linux) | ❌ N/A (File Provider exposes real names) | ✅ origin | ❌ N/A (SAF reports DISPLAY_NAME; already used) | ❌ N/A (SAF, as Android Desktop) | 2026-10-14 |
| Undoable Trash keeps the file where the app itself can restore it (cloud-provider trash is not undoable); copies tolerate mounts that reject timestamps/permissions | Linux Desktop 2026-10-15 (GVFS Google Drive → local FreeDesktop Trash, verified on a real Drive folder) | ⬜ evaluate (`send2trash` on a GVFS path when run on Linux) | ✅ ported 2026-10-17 (`U-29`, `TrashService` fallback to explicit permanent-delete confirmation on File Provider refusal) | ✅ origin | ✅ ported 2026-10-17 (`U-30`, explicit permanent-delete warning on `CLOUD_OTHER` SAF providers) | ✅ ported 2026-10-17 (`U-30`, explicit permanent-delete warning on `CLOUD_OTHER` SAF providers) | 2026-10-17 |
| A failing culling action shows the user a message and leaves queue and undo stack unchanged (no exception dying in a UI handler) | Linux Desktop 2026-10-15 | ⬜ evaluate (Tk callbacks also swallow exceptions) | ⬜ evaluate (`try?` in SwiftUI button actions) | ✅ origin | ⬜ evaluate (Compose `onClick` coroutines) | ⬜ evaluate (as Android Desktop) | 2026-10-15 |
| A dismissal control is clicked by its own test tag and its size asserted — text matchers also match the overlay's merged node, so a click can pass against a 0 dp control | Android Desktop 2026-10-15 (coach guide's "Got it" was 0 dp tall at reference geometry; CI green throughout) | ⬜ evaluate (Tk tests assert widget geometry, no merged-semantics layer) | ⬜ evaluate (XCUITest hittable checks) | ⬜ evaluate (GTK tests drive widgets directly) | ✅ origin | ❌ N/A — already scoped: `GestureTutorialOverlayTest.inGuide()` requires `hasAnyAncestor(hasTestTag("controls_guide"))`, which cannot match the overlay root, and uses `onNode` so ambiguity throws | 2026-10-15 |
| Release artifact covers every CPU architecture the product claims (or the installer refuses the rest) | macOS Desktop 2026-10-15 (universal arm64 + x86_64 via `build_app.sh --universal`) | ⬜ gap: PyInstaller macOS zip is arm64-only and the legacy cask has no `depends_on arch:`; minimal fix is `depends_on arch: :arm64` in the tap | ✅ origin | ✅ `Architecture: all` (pure Python) | ✅ all four ABIs packaged | ✅ no native libraries | 2026-10-15 |
| Distinct visual metaphors and visible micro-labels for Reload (`Sync`) vs. Revert/Undo (`RestoreFromTrash`) so curved arrow glyphs cannot be confused | PhotoTok 2026-10-17 | ❌ N/A (text buttons / menu items) | ❌ N/A (Menu Bar / `⌘Z`, labelled action bar) | ❌ N/A (labelled `Gtk.ActionBar` buttons) | ✅ independent (88 dp labelled sidebar + labelled control block) | ✅ origin | 2026-10-17 |
| Reopen / reload prompt "N new photos" — **Show new** vs. **Stay**, shown only when newer photos exist (silent resume otherwise) | PhotoTok 2026-10-17 | ❌ N/A — archived | ⬜ evaluate (currently restores or starts at 0 without prompting) | ⬜ evaluate (currently starts at 0 without prompting) | ⬜ evaluate (Room/DataStore folder session resume prompt) | ✅ origin | 2026-10-17 |
| Minimal UI copy: prompt only when there is a decision, title carries the message, buttons name outcomes, Settings pointer as an action (PhotoTok `DESIGN.md` § 8) | PhotoTok 2026-10-17 | ❌ N/A — archived | ⬜ evaluate | ⬜ evaluate | ⬜ evaluate | ✅ origin | 2026-10-17 |
| Non-destructive file operations (`docs/shared/FILE_OPERATIONS.md`): bundle-wide collision-free `-2`/`-3` naming, byte-size verification before source delete, recorded trash/undo paths (no filename guessing), partial-failure rollback/reporting, and 20-deep LIFO undo | Cross-product 2026-10-17 (`PRP WP-01…04`, `U-01…05`) | ❌ N/A — archived (documented in `REQ-DESK-FUNC.07`) | ✅ 2026-10-17 (`TrashService` protocol, `(originalURL, trashedURL)` pairs, half-trash rollback) | ✅ 2026-10-17 (`O_CREAT\|O_EXCL`, `fsync`, removable-card `.Trash-$UID`, exact `.trashinfo` paths) | ✅ 2026-10-17 (single SAF path, companion grouping at discovery, `user_ratings` v4, 20-deep `Ctrl+Z` undo, `pending_delete_journal`) | ✅ 2026-10-17 (collision-free SAF transfer, linked bundle delete/revert across `images`+`allImages`, failed-delete recovery) | 2026-10-17 |
| Undo of a partial Move (verified copy, original not removable) deletes only the copies that Move created and never duplicates a surviving original (`docs/shared/FILE_OPERATIONS.md` §3) | Cross-product review 2026-10-17 (finding D-3, task B-3) | ❌ N/A — archived | ✅ 2026-10-17 (`AsyncCullingActor` records `unremovedSourceURLs`) | ✅ 2026-10-17 (`CullingRecord.unremoved_sources`) | ✅ 2026-10-17 (`copiesOfSurvivingOriginals`, `Ctrl+Z`) | ❌ N/A — PhotoTok has no undo for copy/move (only **Revert** for a pending delete), so there is nothing to undo; a partial move keeps the photo in the feed and the verified copy in `Selection/`, hence a retry writes a `-2` copy (tracked as backlog item) | 2026-10-17 |
| Companion grouping follows one shared table (`testdata/formats/companions.json`): sidecars and `-Edit` / `_Edit` / `-Enhanced-NR` / `_Enhanced-NR` derivatives travel with their shot on Move, Trash and Undo | Cross-product review 2026-10-17 (finding D-6, task B-6) | ❌ N/A — archived | ✅ 2026-10-17 (`findCompanionFiles`, `DirectoryScanner.editBaseStem`; `CompanionGroupingTableTests`) | ✅ 2026-10-17 (`scanner.py`, `file_ops.py`; `test_scanner.py`) | ✅ 2026-10-17 (`ImageFormats.derivativeSuffixOf`; `CompanionGroupingTest`) | ⬜ known gap — the feed pairs only same-stem files of different extensions (`RelatedFiles.siblings`): `-Edit` / `-Enhanced-NR` derivatives and `.xmp` sidecars are not linked, so a linked move/delete leaves them behind (no data loss; transfer *naming* already knows the suffixes) | 2026-10-17 |
| A failed rollback of a multi-file Move is reported as a partial result with an undo record — never swallowed (`docs/shared/FILE_OPERATIONS.md` §4) | Cross-product review 2026-10-17 (finding D-12, task B-9) | ❌ N/A — archived | ✅ 2026-10-17 (`FileCullingError.partialMove(…, rollbackFailed:)`, `CullingFeedback.partialMoveTitle`) | ⬜ known gap — the rollback paths in `core/file_ops.py` still use `except Exception: pass` and then report "rolled back" (backlog item in `code_health.md`) | ❌ N/A — the SAF transfer verifies copies before deleting sources and never rolls back by moving files | ❌ N/A — same SAF transfer design | 2026-10-17 |
| A user rating follows its photograph when the file is moved, copied or restored | Cross-product review 2026-10-17 (finding D-13, task B-9) | ❌ N/A — archived | ❌ N/A — ratings are written into the file (XMP) and travel with it | ❌ N/A — ratings are written into the file (XMP) and travel with it | ✅ 2026-10-17 (`CacheRepository.moveRating` / `copyRating`; `user_ratings` is keyed by URI). A *partial* move does not carry the rating (the original keeps its row) | ❌ N/A — PhotoTok keeps no URI-keyed rating store | 2026-10-17 |
| Undo records survive a folder switch: an operation finishing after the folder changed stays undoable, per folder | Cross-product review 2026-10-17 (finding Q-7, task B-9) | ❌ N/A — archived | ✅ 2026-10-18 (per-folder stacks parked by standardized root URL, newest 8 folders; a late job updates its own folder's stack and touches the photo list only while that folder is open; `UndoAcrossFolderSwitchTests`). After reopening, undo restores the file on disk but cannot re-select it in the rescanned list (new photo ids) | ✅ 2026-10-17 (`_folder_undo_history`, newest 8 folders) | ✅ 2026-10-18 (`ParkedUndo`: finished moves and trash restores parked per folder URI, newest 8 folders; a late transfer parks its record under its own folder and skips list, snackbar and counters; parked folders stay pinned for D-10; `ParkedUndoTest`, `SelectorViewModelTest`). Pending deletes still commit when the folder is left — by design: leaving the folder is the commit point of the deferred delete | ❌ N/A — keeping the record would be wrong: PhotoTok's only undoable action is the deferred delete, and leaving the folder is its commit point (a permanent SAF delete), so after the switch there is nothing an undo could restore; copy/move have no undo. The stale-result half applies and is fixed 2026-10-18: a transfer that fails after a folder switch or card unmount no longer puts the old folder's photos into the new feed | 2026-10-18 |
| Persisted storage grants stay while undo history or the pending-delete journal still needs them (WP-07 / `FolderAccessManager`) | Cross-product review 2026-10-17 (finding D-10, task B-8) | ❌ N/A — archived | ❌ N/A — no SAF; the app reads the folders directly | ❌ N/A — no SAF; GIO/FUSE paths need no grants | ✅ 2026-10-17 (`PENDING_DELETE_JOURNAL`, `UNDO_HISTORY` pin, normalized URIs) | ✅ 2026-10-17 (same, plus journal entries removed individually on commit) | 2026-10-17 |
| Source-kind classification (`PHONE_LIBRARY`, `REMOVABLE`, `CAMERA_MTP`, `CLOUD_OTHER`) & per-kind settings (`ONE_PER_SHOT`, shooting order on cards, shuffle on phone) | PhotoTok & `:core` 2026-10-17 (`U-09…12`, `U-30`) | ❌ N/A — archived | ✅ 2026-10-17 (`U-29` File Provider remote detection + ask-before-pass) | ✅ 2026-10-17 (`U-31` GIO/FUSE/GVFS remote detection + ask-before-pass) | ✅ 2026-10-17 (`:core` `SourceKindClassifier`, `U-30` cloud badge & ask-before-scan) | ✅ origin (`U-09…12`, `U-30`) | 2026-10-17 |
| Direct system Share, "Open with…" chooser, and `MediaStore` ★ Favourites / Recycle Bin (`createFavoriteRequest`, `createTrashRequest`) | PhotoTok 2026-10-17 (`U-13`, `U-17`, `U-18`) | ❌ N/A — not applicable on desk products, because desktop culling writes to OS Trash (`NSWorkspace` / `Gio.File.trash`) and filesystem `Selection/` folders rather than a phone gallery database | ❌ N/A — not applicable on desk products (uses Finder / OS Trash directly) | ❌ N/A — not applicable on desk products (uses Files / FreeDesktop Trash directly) | ❌ N/A — tablet workstation uses `Selection/` + *Learn* ratings (`U-21`) | ✅ origin (`U-13`, `U-17`, `U-18`) | 2026-10-17 |
| Removable card-removal safety (`VolumeMonitor`, `fsync`, transfer journal, "Done with this card" summary) | PhotoTok 2026-10-17 (`U-14a…c`) | ❌ N/A — archived | ✅ 2026-10-17 (`U-33` card summary & "safe to format" verification) | ✅ 2026-10-17 (`U-02` `fsync` + `U-33` card summary & "safe to format" verification) | ✅ 2026-10-17 (`U-33` card summary & "safe to format" verification) | ✅ origin (`U-14a…c`) | 2026-10-17 |
| Seen-photo memory (`SeenStore` append-only fingerprint log), global unseen-first shuffle rounds, and quiet session milestones | PhotoTok 2026-10-17 (`U-15`, `U-16`, `U-19`) | ❌ N/A — not applicable on desk products, because desk culling is linear/burst-ordered over newly shot project folders rather than multi-year camera-roll rediscovery | ❌ N/A — not applicable on desk products (linear/score triage over project folders) | ❌ N/A — not applicable on desk products (linear/score triage over project folders) | ❌ N/A — not applicable on tablet workstation (structured culling & *Learn* curriculum) | ✅ origin (`U-15`, `U-16`, `U-19`) | 2026-10-17 |
| Dedicated *Learn* experience (`technique_facts`, 8 beginner lessons with *Spot it*, *What if?* crop preview, *Compare*, and Holm-corrected base-rate insights) | Android Desktop 2026-10-17 (`U-20…28`) | ❌ N/A — archived | ❌ N/A — tablet-exclusive educational mode (`MD-9`) | ❌ N/A — tablet-exclusive educational mode (`MD-9`) | ✅ origin (`U-20…28`) | ❌ N/A — phone feed stays fast & distraction-free (`MD-9`) | 2026-10-17 |
| Explicit `.photoselector-selection` marker file in every Selection folder, external keeper destination + "Safe to format" check, "Lowest sharpness first" triage, and EXIF × sharpness statistics | Cross-product 2026-10-17 (`U-32…36`) | ❌ N/A — archived | ✅ 2026-10-17 (`U-32…35`) | ✅ 2026-10-17 (`U-32…35`) | ✅ 2026-10-17 (`U-32…36`, incl. DeX TV-distance legibility scale) | ✅ 2026-10-17 (`U-32` `.photoselector-selection` marker) | 2026-10-17 |
| Narrow-window chrome steps down (full → short labels → icon + tooltip) instead of wrapping or truncating; transient feedback (toast, zoom-exit pill) drawn inside the active photo, never over slot headers, EXIF rows or action controls | macOS Desktop 2026-10-18 (device lab MAC-1…3) | ❌ N/A — archived | ✅ origin 2026-10-18 (`ChromeDensity` + `ViewThatFits`; slot-anchored overlay) | ◐ evaluate — the same class of defect is LIN-7 (toast over the action bar) in the 2026-10-17 lab report, handled by the Linux lab-fix branch in Libadwaita idiom (`AdwToastOverlay` placement), not by porting | ◐ evaluate — AD-1 (control block clipped below ~410 dp) and AD-7 (snackbar width) are the Compose counterparts, handled by the Android Desktop lab-fix branch | ◐ evaluate — TOK-9 (counter vs EXIF card) is the phone counterpart, handled by the PhotoTok lab-fix branch | 2026-10-18 |
| With File Sorting on, a stem sidecar (`<stem>.xmp`) is filed into the subfolder of its bundle's RAW (else of its primary image), never the Selection root — a filename-only rule cannot know a stem sidecar's image | Android Desktop 2026-10-18 (device lab AD-6: `DSC_0003.xmp` landed in `Selection/` apart from `RAW/DSC_0003.ARW`) | ✅ by spec (REQ-DESK-VIEW.09: sidecars sorted with their image) | ❌ N/A — no RAW/JPEG subfolder sorting | ❌ N/A — no RAW/JPEG subfolder sorting | ✅ origin (`ImageFormats.selectionSubfolderInBundle`; `ImageFormatsBundleSortingTest`, `SafTransferTest` AD-6) | ⬜ evaluate — same filename-only rule in `LocalImageSource.determineTargetFolder`, moot until the feed links `.xmp` sidecars (see the companion-grouping row) | 2026-10-18 |
| Chrome beside the photographs has a height budget: below the height the full control block needs it switches to a compact variant (then scrolls, position readout first) instead of clipping its last children; the frames never pay for it | Android Desktop 2026-10-18 (device lab AD-1/AD-2: counter and toggles clipped at 1280×800 and 1024×768, sidebar destinations unreachable below ~785 dp) | ❌ N/A — archived | ⬜ evaluate — handled by the macOS lab-fix session (MAC-1, labels wrapping at 1024 pt) | ⬜ evaluate — handled by the Linux lab-fix session | ✅ origin (`ControlBlockFit`, scrolling `SelectorSidebar`; `SelectorForcedSizeTest`) | ❌ N/A — phone layout has no flank chrome; bar overlap handled by the PhotoTok lab-fix session (TOK-1, TOK-9) | 2026-10-18 |
| A guide or coach overlay never covers a control: callouts live only where no control is, and page (never scroll) when they do not fit | Android Desktop 2026-10-18 (device lab AD-3: guide heading over Copy/Move, "Got it" over the controls at every tablet size) | ❌ N/A — archived | ⬜ evaluate | ⬜ evaluate | ✅ origin (`CoachPaging`, `SelectorCoachOverlay`; per-page non-intersection tests) | ⬜ evaluate — the PhotoTok lab-fix session owns TOK-1 (controls guide overlapping both bars) | 2026-10-18 |
| The first physical arrow key after launch, a resize or a closed dialog reaches the culling view: the view owns key focus explicitly instead of relying on the platform's initial-focus pass | Android Desktop 2026-10-18 (device lab AD-11, confirmed with a real keyboard on a Tab S11 Ultra in DeX: the first → only moved focus to the sidebar) | ❌ N/A — archived | ⬜ evaluate (first key after window activation) | ⬜ evaluate (first key after window activation) | ✅ origin (`SelectorScreen` focusable root + preview key handler; `firstRightArrow…` instrumented tests) | ⬜ evaluate — keyboards are rare on phones, but the same Compose focus behaviour applies | 2026-10-18 |

---

## 5. Permanent Exclusions Rationale

To prevent future agents from re-litigating settled architectural decisions:

*   **Ollama VLM on macOS, Linux, and Mobile**: Permanently excluded from macOS Desktop, Linux Desktop, Android Desktop, and PhotoTok. Loading multi-gigabyte neural weights or demanding local daemon servers creates prohibitive battery drain, thermal throttling, and multi-gigabyte package footprints incompatible with streamlined native distributions.
*   **ExifTool on macOS, Linux, and Mobile**: Permanently excluded from macOS Desktop, Linux Desktop, Android Desktop, and PhotoTok. Native platform frameworks (Apple ImageIO on macOS, GExiv2 on Linux, AndroidX ExifInterface on Android) execute in-process without Perl runtime dependencies or child process spawning overhead.
*   **Apple Vision on Linux Desktop**: Permanently excluded as a core dependency. Apple Vision is proprietary to macOS/iOS. Linux Desktop relies on CPU-vectorized NumPy Laplacian convolution with center 50% ROI cropping and strided MAD noise subtraction ($\le 2,048$ samples), delivering $<2.0\text{ms}$ reference latency (< 4.0ms on shared CI runners) without external neural runtime bloat.
*   **Persistent SQLite/Room Score Database on Desktop Native Apps**: macOS Desktop and Linux Desktop rely on ultra-fast sub-2ms compute SLAs and bounded in-memory LRU caches. Serializing quality scores to persistent disk databases adds disk I/O contention without user-perceptible benefits for fast SD card culling workflows.
