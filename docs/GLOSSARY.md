# Glossary — Canonical Product Names

The repository ships five products (four active native solutions alongside the archived
legacy Desktop) that have historically been called by multiple different names. This file fixes
the vocabulary. Use the **canonical name** in commits, pull requests, documentation, agent
output and issue titles. "The Android app" is ambiguous and therefore wrong — there are two of them.

## The five products

| Canonical name | Slug | Code | Build target / module | Package / distribution | Target |
|---|---|---|---|---|---|
| **Desktop** | `desktop` | `products/desktop/src/photo_selector_toolbox/` | — | `photo-selector-toolbox` (PyPI-style dist name), Homebrew Formula + Cask | macOS, Linux, Windows workstations |
| **macOS Desktop** | `macos-desktop` | `products/macos-desktop/` | `PhotoSelectorKit`, `PhotoSelectorApp` (SPM) | `PhotoSelectorApp.app`, Homebrew Cask | macOS 14+ / 15+ Apple Silicon & Intel workstations |
| **Linux Desktop** | `linux-desktop` | `products/linux-desktop/` | `photo-selector-linux` | `.deb` package via the APT repository `alexpp90/apt` | Debian 13 (Trixie), GNOME 46+, Linux workstations |
| **Android Desktop** | `android-desktop` | `products/android/android-desktop/` | `:android-desktop` | `com.photoselectortoolbox` | Samsung DeX, tablets ≥ 840 dp, Chromebooks |
| **PhotoTok** | `phototok` | `products/android/phototok/` | `:phototok` | `com.phototok` | Phones of every tier (compact to high-end), portrait, gesture-first |

Plus two shared libraries, which are not products:

| Canonical name | Code | Gradle module | Package |
|---|---|---|---|
| **Android Core** | `products/android/core/` | `:core` | `com.photoselector.core` |
| **Android SAF** | `products/android/saf/` | `:saf` | `com.photoselector.saf` |

## Deprecated names — do not use

| Do not write | Write instead | Why |
|---|---|---|
| "the Android app" | Android Desktop, or PhotoTok | There are two Android products |
| "Toolbox" (on its own) | Android Desktop, or Desktop | `:android-desktop`'s artifacts are named `photo-selector-toolbox-android-*`, but so is the Python product |
| "the Mac app", "the Linux app" or "the desktop app" (without product qualifier) | Desktop (for Python), macOS Desktop (for Swift), or Linux Desktop (for GNOME/GTK4) | The repository ships multiple desktop apps across distinct platforms |
| `Android Phone`, `Phone mode`, `Phone Tok` | PhotoTok | One product, one name |
| `Photo Tok`, `Photo-Tok` | PhotoTok | One spelling, no space, capital T |
| "the desktop app" for `:android-desktop` | Android Desktop | `:android-desktop` runs on Android in a desktop-shaped window; the Python product is Desktop |
| "tablet mode" / "phone mode" as product names | Android Desktop / PhotoTok | These are window size classes, not products |

The names baked into build identifiers (`:android-desktop`, `com.photoselectortoolbox`,
`photo-selector-toolbox-android-release.apk`, the `TOOLBOX_*` CI secrets) are **not** being
renamed: they are load-bearing for Google Play, Firebase and the signing pipeline, where a
rename means a new app listing. The table above is the mapping between the readable name and
those identifiers. Prose uses the canonical name; build configuration keeps the identifier.

## Terms of art

| Term | Meaning |
|---|---|
| **Selection** | The destination subfolder (default name `Selection`) that kept photos are moved or copied into. Same concept across all products. |
| **Selector** | The comparison workspace: previous / current / next, with quality scores. Desktop, Android Desktop, macOS Desktop, and Linux Desktop. |
| **Feed** | PhotoTok's vertically paged stream of photos. PhotoTok only. |
| **Score** | One of sharpness, noise, highlight clipping, shadow clipping, aesthetics — computed identically in Desktop and Android Desktop (see [`shared/FEATURE_PARITY.md`](shared/FEATURE_PARITY.md)), natively via Apple Accelerate and Vision in macOS Desktop, and natively via OpenCV Laplacian focus variance in Linux Desktop. PhotoTok computes none of them. |
| **Window size class** | Compact (< 600 dp), Medium (600–840 dp), Expanded (≥ 840 dp). A layout dimension, never a product name. |
