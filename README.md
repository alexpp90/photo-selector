<div align="center">
  <img src="assets/logo.png" alt="Photo Selector Toolbox Logo" width="160" />

  # Photo Selector Toolbox

  **The ultimate multi-platform ecosystem to cull, analyze, and organize your photo libraries with blazing speed — built natively for Mobile, Tablet, Mac, and Linux.**

  [![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
  [![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
  [![Swift 6.0](https://img.shields.io/badge/Swift-6.0-F05138?logo=swift&logoColor=white)](https://swift.org/)
  [![Python 3.12+](https://img.shields.io/badge/Python-3.12%2B-3776AB?logo=python&logoColor=white)](https://www.python.org/)
  [![Android CI](https://img.shields.io/github/actions/workflow/status/alexpp90/photo-selector/android.yml?branch=main&label=Android&logo=github)](https://github.com/alexpp90/photo-selector/actions/workflows/android.yml)
  [![macOS CI](https://img.shields.io/github/actions/workflow/status/alexpp90/photo-selector/macos.yml?branch=main&label=macOS&logo=github)](https://github.com/alexpp90/photo-selector/actions/workflows/macos.yml)
  [![Linux CI](https://img.shields.io/github/actions/workflow/status/alexpp90/photo-selector/linux.yml?branch=main&label=Linux&logo=github)](https://github.com/alexpp90/photo-selector/actions/workflows/linux.yml)
  [![Releases](https://img.shields.io/badge/Releases-per%20product-181717?logo=github)](docs/build/RELEASING.md)
  [![Android](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)](#-android-installation)
  [![macOS](https://img.shields.io/badge/macOS-000000?logo=apple&logoColor=white)](#-macos-desktop-installation)
  [![Linux](https://img.shields.io/badge/Linux-FCC624?logo=linux&logoColor=black)](#-linux-desktop-installation)

  <br />
  <img src="assets/banner.png" alt="Photo Selector Toolbox Banner" width="800" style="border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.3);" />
</div>

---

## 🌟 The Ecosystem at a Glance

**Photo Selector Toolbox** is a privacy-first, high-velocity photo culling and metadata analysis suite designed for professional photographers, enthusiasts, and creators. Whether sorting on the go on your phone, reviewing burst sequences on a tablet, or performing intensive multi-thousand RAW triage at your desktop workstation, every product is built natively for its platform.

> 🔒 **100% Private & Strictly Offline:** Your photos never leave your device. All EXIF parsing, duplicate detection, and optical/aesthetic quality scoring (including Apple Neural Engine and local TFLite models) execute strictly on-device. Zero cloud uploads, zero telemetry, and no account required.

---

## 📦 Five Products, One Shared Repository

The repository ships **five products** (four active native solutions alongside the archived legacy Desktop), each optimized for its environment and UX model. They share photographic *concepts* — the EXIF contract, score semantics, what "Selection" means — and, between the two Android products, one shared core library. They do not share implementations.

| Product | Directory | Tech Stack | Target Environment | Primary UX Paradigm | Docs |
| :--- | :--- | :--- | :--- | :--- | :--- |
| 📱 **PhotoTok** | `products/android/phototok/` | Kotlin, Compose, DataStore | Portrait phones ($< 600$dp) | Gesture-first vertical swipe feed | [docs](docs/products/phototok/README.md) |
| 🖥️ **Android Desktop** | `products/android/android-desktop/` | Kotlin, Compose, Room, OpenCV | Samsung DeX, tablets ($\ge 840$dp) | 1-over-2 three-frame layout, sidebar & controls | [docs](docs/products/android-desktop/README.md) |
| 🍎 **macOS Desktop** | `products/macos-desktop/` | Swift 6, SwiftUI, Vision, Accelerate | macOS 14+ / 15+ Apple Silicon & Intel | Maximized preview (>85%), Apple Vision aesthetics | [docs](docs/products/macos-desktop/README.md) |
| 🐧 **Linux Desktop** | `products/linux-desktop/` | Python 3.12+, GTK4, Libadwaita | Debian 13 (Trixie), GNOME 46+ | Libadwaita dark studio, 1/2/3-Up canvas, NumPy (<2ms) | [docs](docs/products/linux-desktop/README.md) |
| 📦 **Desktop (Legacy)** | `products/desktop/src/` | Python 3.10+, Tkinter | macOS, Windows, Linux | Split view, menu/hotkeys (archived reference) | [docs](docs/products/desktop/README.md) |
| *(shared library)* | `products/android/core/` | Kotlin | — | EXIF model + readers used by both Android products | [docs](docs/shared/README.md) |

Naming matters here — "the Android app" is ambiguous. Canonical names, slugs, and package mappings are defined in [`docs/GLOSSARY.md`](docs/GLOSSARY.md).

### Repository Map

All product code lives under `products/`, one directory per product, sharing the same shape:
`src/` for sources, `tests/` for tests, its own build configuration, and its own `README.md`.
Everything else at the repository root is genuinely cross-product.

```
products/
  android/                   Gradle build root for both Android products
    phototok/                  PhotoTok (Phone)       — src/ tests/ res/ AndroidManifest.xml
    android-desktop/           Android Desktop (DeX)  — src/ tests/ res/ AndroidManifest.xml
    core/                      :core — the only code shared between Android products
  macos-desktop/             Native macOS Desktop   — src/ tests/ Package.swift
  linux-desktop/             Native Linux Desktop   — src/ tests/ debian/ scripts/ pyproject.toml
  desktop/                   Archived Legacy Desktop— src/ tests/ benchmarks/ scripts/ pyproject.toml
docs/                        Product documentation, per product -> docs/README.md
scripts/                     Cross-product tooling — run_tests.sh (local CI mirror)
assets/                      Shared visual branding and screenshots
```

---

## 📱 1. PhotoTok (Phone)

A lightweight, touch-optimized portrait client designed for lightning-fast, single-handed photo curation on your phone.

<div align="center">
  <img src="assets/phototok_02_viewer.png" alt="PhotoTok Mobile Culling Interface" width="360" style="border-radius: 12px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); margin-bottom: 12px;" />
  <p><em>Vertical gesture culling with a glassmorphic EXIF HUD, responsive swipe feedback, and coach marks</em></p>
</div>

### Key Features
* **TikTok-Style Vertical Feed:** Browse local collections seamlessly via Jetpack Compose native `VerticalPager` with background prefetching and progressive SAF loading (sub-second time-to-first-swipe).
* **Tactile Culling Swipes:**
  * **Swipe-Right to File:** Instantly copy or move photos to your designated `Selection/` folder with an animated green confirmation glow.
  * **Swipe-Left to Discard or Route:** Configurable action (Trash/Delete, Copy, or Move to secondary folder) with progressive card tilt ($2^\circ$) and non-blocking **Revert** (`RestoreFromTrash`) across scrolls.
* **Session Resume & Newest Prompt:** Reopening a folder or reloading (`Sync`) reports whether newer photos were added since you last left and lets you choose between continuing where you left off or starting with the newest photo.
* **Fluid Zoom & Pan:** Pinch-to-zoom (up to $6\times$) and double-tap to 2.5x zoom with single-finger panning; gestures automatically disable paging conflicts while zoomed.
* **One-Tap Glassmorphic HUD:** Single tap toggles the translucent camera metadata panel (shutter, aperture, ISO, focal length, lens) and micro-labelled navigation controls for unobstructed viewing.
* **In-Place Guidance & Pairing:** Interactive coach-mark overlay anchored directly to UI controls, automatic RAW+JPEG companion pairing, and orientation-aware feed ordering.

---

## 🖥️ 2. Android Desktop (Tablet & Samsung DeX)

Built specifically for large-screen tablets (≥ 840 dp) and desktop environments like Samsung DeX, providing a complete studio workstation on Android.

<div align="center">
  <img src="assets/android_desktop_screenshot.png" alt="Android Desktop Layout" width="750" style="border-radius: 10px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); margin-bottom: 12px;" />
  <p><em>Galaxy Tab S11 Ultra 1-over-2 layout: Current frame centered with optical readouts and controls; Previous/Next below</em></p>
</div>

### Key Features
* **1-over-2 Three-Frame Layout:** Height-optimized comparison architecture allocating maximum display area to the Current frame, flanked by named metric readouts to the left and primary action buttons to the right, with Previous and Next frames centered as a pair beneath.
* **Zero-Area-Cost Columnar Chrome:** 88dp labeled left sidebar and 72dp vertical filmstrip running top-to-bottom in horizontal display slack, preserving 100% of vertical photo height without adding top or bottom bars.
* **On-Device Optical Scoring:** Real-time Center Sharpness (OpenCV Laplacian variance on center 50% ROI), MAD noise floor estimation, highlight/shadow clipping histograms, and optional on-device TFLite aesthetic scoring.
* **Star Ratings & Composition Feedback:** 1–5 star rating controls, reject tags, and composition heuristics persisted in a local Room SQLite database with a 10,000-image MRU cache.
* **Desktop Ergonomics:** Mouse pointer hover states (`PointerIcon.Hand`), physical keyboard shortcuts (`←`/`→`, `M`, `C`, `Del`, `1`/`2`/`3`, `Esc`), and drag-and-drop folder import from Android file managers.
* **Every Camera Format:** Discovers, displays, reads EXIF from and scores Sony, Canon (incl. CR3), Nikon, Fujifilm, Panasonic, OM System, Pentax, Samsung and DNG RAW files plus JPEG, HEIC, PNG, TIFF and WebP. RAWs are shown from their embedded camera JPEG; phone DNGs fall back to the platform decoder. Proven against real camera files ([supported formats](docs/shared/SUPPORTED_FORMATS.md)).
* **Zero Network Permissions:** Declares no Android permissions (`INTERNET` included) and requires zero Google Play Services dependencies for complete privacy.

---

## 🍎 3. Native macOS Desktop (Swift & SwiftUI)

A high-performance native macOS photo selection and comparison culling suite built with Swift 6 and SwiftUI, engineered for high-throughput SD-card ingestion and instant triage.

<div align="center">
  <img src="assets/macos_desktop_screenshot.png" alt="macOS Desktop Culling Workspace" width="750" style="border-radius: 10px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); margin-bottom: 12px;" />
  <p><em>Native macOS studio workspace with floating Apple Vision HUD pill, 1-Up maximized canvas, and tactile action bar</em></p>
</div>

### Key Features
* **Pure Native Apple Vision Aesthetics:** On-device aesthetic scoring via `VNCalculateImageAestheticsScoresRequest` utilizing Apple Neural Engine hardware with zero Python or external network dependencies.
* **Hardware-Accelerated Focus Metric:** Sub-millisecond 2D Laplacian variance computation via Apple Accelerate (`vImageConvolve_PlanarF` + `vDSP`), processing >1,500 images/second per thread with noise-floor subtraction.
* **Maximized Viewport Canvas:** Borderless comparison layouts (1-Up with 1:1 pixel zoom on `Space`, 2-Up side-by-side, and Focus 3-Up sliding triplet) allocating >85% of window area to photo previews.
* **Zero-Latency Keyboard Navigation:** Window-level `NSEvent` interceptor routing arrow keys, `M` (Move to `Selection/`), `C` (Copy), `Delete` (System Trash), and `⌘Z` (Instant Undo) with zero AppKit focus lag.
* **Actor-Isolated Transactional Culling:** Serial FIFO disk mutations on `AsyncCullingActor` guaranteeing zero data loss and automated companion file pairing (RAW+JPEG, XMP sidecars).
* **macOS Design Standards:** Studio dark theme, configuration housed in standard macOS Settings (`⌘,`), and secondary utilities housed under the Menu Bar `Tools` menu.

---

## 🐧 4. Linux Desktop (GNOME 46+ & Libadwaita)

A native GNOME photograph culling and selection suite targeting Debian 13 (Trixie) and modern Linux desktop environments adhering strictly to GNOME Human Interface Guidelines (HIG).

<div align="center">
  <img src="assets/linux_desktop_logo.svg" alt="Photo Selector Linux — Optical Cobalt Lens Logo" width="96" style="margin-bottom: 10px;" />
  <br />
  <img src="assets/linux_desktop_screenshot.png" alt="Linux Desktop Culling Workspace" width="750" style="border-radius: 10px; box-shadow: 0 4px 20px rgba(0,0,0,0.3); margin-bottom: 12px;" />
  <p><em>Native GNOME 46 / Libadwaita Obsidian Darkroom &amp; Optical Cobalt workspace with segmented comparison controls, glassmorphic optical HUD with sharpness goodness bar, and semantic bottom action bar</em></p>
</div>

### Key Features
* **Obsidian Darkroom & Optical Cobalt Theme:** Built with Python 3.12+, PyGObject, GTK4, and Libadwaita featuring a color-neutral Obsidian Darkroom surface (`#141417` / `#181920`), Optical Cobalt (`#3584E4`) & Optical Cyan (`#06B6D4`) focus accents, and $\ge 7:1$ AAA contrast tokens.
* **Vectorized NumPy Focus Engine & Goodness HUD:** 2D discrete Laplacian convolution with 3x3 Gaussian smoothing and strided Median Absolute Deviation (MAD) noise floor subtraction (<2.0 ms compute latency), paired with a glassmorphic HUD (`I`) displaying a normalized sharpness goodness meter, semantic quality badges (`Sharp` / `Acceptable` / `Blurry`), and `RAW+JPG` / `XMP` companion pills.
* **Responsive Canvas Viewports:** Seamlessly switch between 1-Up (1:1 actual-pixel zoom toggle on `Space`), 2-Up side-by-side comparison (`CHAMPION` / `CHALLENGER`), and 3-Up Focus Mode (`PREVIOUS` / `ACTIVE` / `NEXT` sliding triplet) with boundary slot indicators.
* **Every Camera Format & Google Drive:** Displays, reads EXIF from and scores Sony, Canon (incl. CR3), Nikon, Fujifilm, Panasonic, OM System, Pentax, Samsung and DNG RAW files plus JPEG, HEIC, PNG, TIFF and WebP, proven against real camera files ([supported formats](docs/shared/SUPPORTED_FORMATS.md)). RAWs display through their embedded camera preview (phone DNGs through LibRaw), decoded in the background; folders on Google Drive (GNOME Online Accounts) open like local folders with their real file names, and Move, Copy, Trash and Undo work there too (trashed Drive files go to the local GNOME Trash, so Undo can bring them back). File operations run in the background, so the window stays responsive during a Drive upload, and 1:1 zoom on a RAW shows full sensor resolution.
* **Semantic Action Bar:** Bottom `Gtk.ActionBar` with semantic status counter pills (`Selected`, `Trashed`, `Remaining`), tactile `Move (M)`, `Copy (C)`, `Trash (Del)` via FreeDesktop Trash (`Gio.File.trash()`), and atomic reversal (`Ctrl+Z`).
* **Debian 13 APT Distribution:** Packaged as a native Debian `.deb` with GNOME HIG squircle SVG/PNG icons and AppStream branding, in a signed APT repository ([alexpp90/apt](https://github.com/alexpp90/apt)) for seamless updates.

---

## 📦 5. Archived Legacy Desktop (Python & Tkinter)

The original multi-platform desktop culling application built with Python and Tkinter, preserved as an archived reference implementation.

<div align="center">
  <img src="assets/desktop_main_app.png" alt="Legacy Desktop Initial Layout" width="750" style="border-radius: 8px; box-shadow: 0 4px 15px rgba(0,0,0,0.2); margin-bottom: 12px;" />
  <p><em>The original zinc-themed Python/Tkinter Desktop workspace</em></p>
</div>

### Key Features
* **Dynamic Review Layouts:** Toggle between Standard split view and Focus mode.
* **Optical & VLM Algorithms:** Laplacian variance sharpness, MAD noise calculation, highlight/shadow clipping, and optional offline local Ollama vision model scoring.
* **Library Utilities:** Matplotlib EXIF histograms, dHash burst grouping, and SHA-256 duplicate detection.

---

## 📊 Platform Feature Sync Matrix

| Feature | PhotoTok (Phone) | Android Desktop (Tablet/DeX) | macOS Desktop (Swift) | Linux Desktop (GTK4/Adw) | Legacy Desktop (Py/Tk) | Notes |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **Review Layouts** | Vertical Pager | 1-over-2 Three-Frame | 1-Up / 2-Up / 3-Up | 1-Up / 2-Up / 3-Up | Standard / Focus | PhotoTok is gesture-first; Desktops provide multi-frame comparison. |
| **Center Sharpness Score** | ❌ Omitted | ✅ (OpenCV) | ✅ (vImage + vDSP) | ✅ (NumPy Laplacian) | ✅ (OpenCV/NumPy) | 50% ROI Laplacian variance; omitted on PhotoTok for sub-second launch. |
| **Laplacian Noise (MAD)** | ❌ Omitted | ✅ (MAD) | ✅ (vImage MAD) | ✅ (NumPy MAD) | ✅ (MAD) | Median Absolute Deviation sensor noise floor subtraction. |
| **Highlights/Shadows Clipping** | ❌ Omitted | ✅ (Histograms) | ✅ (vImage Hist) | ✅ (NumPy Hist) | ✅ (Histograms) | Hardware histogram thresholds $\ge 254$ and $\le 2$. |
| **Aesthetic Scoring** | ❌ Excluded | ✅ (TFLite NIMA) | ✅ (Apple Vision ANE) | ❌ Excluded | ✅ (Local Ollama) | macOS uses Apple Neural Engine; Android Desktop uses optional TFLite. |
| **Star Ratings (1–5)** | ❌ Omitted | ✅ (Room DB v3) | ❌ Omitted | ❌ Omitted | ❌ Omitted | Rating, reject, and composition tracking in Room DB. |
| **Score Caching** | ❌ Lightweight | ✅ (Room DB) | ❌ (Memory LRU) | ❌ (Memory LRU) | ✅ (SQLite) | Desktops rely on sub-2ms compute & memory LRU; Android uses Room. |
| **Duplicate Detection** | ❌ Omitted | ✅ (Room DB) | ✅ (CryptoKit SHA) | ✅ (Streaming SHA) | ✅ (SHA-256) | Two-stage size pre-filtering and SHA-256 duplicate finder. |
| **Tactile Keyboard Shortcuts** | ❌ Touch-first | ✅ (DeX / Physical) | ✅ (Zero-latency NSEvent)| ✅ (GTK Capture) | ✅ (Tkinter Events) | Full hotkey routing (`←`/`→`, `M`, `C`, `Del`, `Undo`). |
| **100% Offline Privacy** | ✅ (SAF Only) | ✅ (Zero Permissions) | ✅ (Strictly Local) | ✅ (Strictly Local) | ✅ (Local) | Zero telemetry, zero cloud dependencies, strict privacy guarantee. |

---

## 🚀 Installation

### 📱 Android Installation (PhotoTok & Android Desktop)

#### Option A: Obtainium (Direct Auto-Updates)
1. Install [Obtainium](https://github.com/ImranOmarRashid/Obtainium) on your Android device.
2. In Obtainium, click **Add App** and paste this repository's URL: `https://github.com/alexpp90/photo-selector`.
3. Each product has its own releases, named `PhotoTok X.Y.Z` and `Android Desktop X.Y.Z`: set Obtainium's **Filter release titles by regular expression** to `^PhotoTok ` or `^Android Desktop ` (or allow pre-releases for the `nightly` build). Obtainium will download and install APK updates directly from GitHub Releases.

#### Option B: GitHub Releases (Direct APK)
Download the APK from the latest [PhotoTok](https://github.com/alexpp90/photo-selector/releases?q=phototok-v&expanded=true) or [Android Desktop](https://github.com/alexpp90/photo-selector/releases?q=android-desktop-v&expanded=true) release.

---

### 🍎 macOS Desktop Installation

#### Homebrew Cask
Install the native macOS desktop application via our Homebrew tap:

```bash
# Install directly in a single command
brew install --cask alexpp90/tap/photo-selector-toolbox

# Or tap first, then install:
brew tap alexpp90/tap
brew install --cask photo-selector-toolbox
```

#### Standalone ZIP
Download the pre-compiled `photo-selector-macos-apple-silicon.zip` from the latest [macOS Desktop release](https://github.com/alexpp90/photo-selector/releases?q=macos-desktop-v&expanded=true), unpack it, and drag `PhotoSelector.app` into `/Applications`.

---

### 🐧 Linux Desktop Installation (Debian 13 Trixie)

One line (adds the signed APT repository, then installs):

```bash
curl -fsSL https://alexpp90.github.io/apt/setup.sh | sudo bash
sudo apt install photo-selector-linux
```

Or add the repository keyring and deb822 source by hand:

```bash
# 1. Install repository keyring
sudo mkdir -p /etc/apt/keyrings
curl -fsSL https://alexpp90.github.io/apt/photo-selector-archive-keyring.gpg | sudo tee /etc/apt/keyrings/photo-selector-archive-keyring.gpg > /dev/null

# 2. Add repository source
sudo tee /etc/apt/sources.list.d/photo-selector.sources << EOF
Types: deb
URIs: https://alexpp90.github.io/apt
Suites: trixie
Components: main
Signed-By: /etc/apt/keyrings/photo-selector-archive-keyring.gpg
EOF

# 3. Update and install
sudo apt update
sudo apt install photo-selector-linux
```

---

### 💻 Archived Legacy Desktop Installation

For Windows or systems running the legacy Python/Tkinter application (or running both native macOS and legacy Python side-by-side):

#### macOS Homebrew Cask (Legacy Python Desktop)
Install the legacy Python/Tkinter application into `/Applications` alongside the native app:
```bash
brew install --cask alexpp90/tap/photo-selector-toolbox-legacy
```

#### macOS / Linux CLI via Homebrew Formula
```bash
brew install alexpp90/tap/photo-selector-toolbox
```

Standalone archives are available on [GitHub Releases](https://github.com/alexpp90/photo-selector/releases):
* **macOS Apple Silicon:** `photo-selector-toolbox-macos-apple-silicon.zip`
* **Windows x64:** `photo-selector-toolbox-windows-x64.zip`
* **Linux x64:** `photo-selector-toolbox-linux-x64.zip`

---

## 🛠️ Run from Source

### 📱 Android Setup (PhotoTok & Android Desktop)
1. Open the repository root in **Android Studio Ladybug** (or newer).
2. Ensure JDK 17 and Android SDK 36 are configured.
3. Build the debug binaries:
   ```bash
   cd products/android
   ./gradlew assembleDebug
   ```
   Debug APKs are output to:
   - PhotoTok: `products/android/phototok/build/outputs/apk/debug/`
   - Android Desktop: `products/android/android-desktop/build/outputs/apk/debug/`

### 🍎 macOS Desktop Setup (Swift 6)
1. Ensure Xcode 16+ or Command Line Tools with Swift 6 are installed.
2. Build and run from `products/macos-desktop`:
   ```bash
   cd products/macos-desktop
   swift build
   swift run PhotoSelectorApp
   ```

### 🐧 Linux Desktop Setup (Python & GTK4)
1. Ensure Python 3.12+ and system GTK4 / Libadwaita libraries are installed:
   ```bash
   sudo apt install python3-gi python3-gi-cairo gir1.2-gtk-4.0 gir1.2-adw-1 libgexiv2-2
   ```
2. Run directly from `products/linux-desktop`:
   ```bash
   cd products/linux-desktop
   python3 -m photo_selector_linux [path/to/photos]
   ```

### 💻 Legacy Desktop Setup (Python 3.10+)
1. Ensure Python 3.10+ and Tkinter are installed:
   ```bash
   cd products/desktop
   poetry install
   poetry run photo-selector-gui
   ```

---

## 🧪 Testing

### The CI Parity Mirror
To verify all products against the exact gates CI enforces:
```bash
./scripts/run_tests.sh          # Auto-detects modified products
./scripts/run_tests.sh --all    # Verifies all products unconditionally
```

### Individual Product Test Suites
* **Android (JVM Unit & Instrumented):**
  ```bash
  cd products/android
  ./gradlew testDebugUnitTest
  ./gradlew connectedDebugAndroidTest
  ```
* **macOS Desktop (Swift 6):**
  ```bash
  cd products/macos-desktop
  swift test
  ```
* **Linux Desktop (Pytest):**
  ```bash
  cd products/linux-desktop
  pytest tests/unit/
  ```
* **Legacy Desktop (Pytest):**
  ```bash
  cd products/desktop
  poetry run pytest
  ```

---

## 📄 License
The source code is open source under the [MIT License](LICENSE).
The names **Photo Selector Toolbox** and **PhotoTok** and the logos are not covered by that license — see [TRADEMARKS.md](TRADEMARKS.md) before distributing a modified build.
Contributions are accepted under the [Contributor License Agreement](CLA.md), signed once with a PR comment.
Third-party notices and licenses are detailed in [THIRDPARTY_NOTICES.txt](THIRDPARTY_NOTICES.txt).
