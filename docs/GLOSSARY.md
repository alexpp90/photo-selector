# Glossary — Canonical Product Names

The repository ships five products (four active native apps alongside the archived legacy
Desktop) that have historically been called by several names. This file fixes the
vocabulary. Use the **canonical name** in commits, pull requests, documentation, agent
output and issue titles. "The Android app" is ambiguous and therefore wrong — there are two
of them. Names, icons and colours: [`shared/BRANDING.md`](shared/BRANDING.md).

## The five products

| Canonical name | Platform descriptor | Slug | Code | Build target / module | Package / distribution | Target |
|---|---|---|---|---|---|---|
| **Jackpix** | Android phone | `phototok` | `products/android/phototok/` | `:phototok` | `com.phototok` | Phones of every tier (compact to high-end), portrait, gesture-first |
| **Frameduel** | Android Desktop | `android-desktop` | `products/android/android-desktop/` | `:android-desktop` | `com.photoselectortoolbox` | Samsung DeX, tablets ≥ 840 dp, Chromebooks |
| **Lensjet** | macOS Desktop | `macos-desktop` | `products/macos-desktop/` | `PhotoSelectorKit`, `PhotoSelectorApp` (SPM) | `Lensjet.app` (`app.alpatix.lensjet`), Homebrew Cask | macOS 14+ / 15+ Apple Silicon & Intel workstations |
| **Blitzlens** | Linux Desktop | `linux-desktop` | `products/linux-desktop/` | `photo_selector_linux` | `photo-selector-linux` `.deb` (`app.alpatix.Blitzlens`) via the APT repository `alexpp90/apt` | Debian 13 (Trixie), GNOME 46+ |
| **Desktop (legacy)** — "Photo Selector Toolbox (Legacy)" | legacy Desktop | `desktop` | `products/desktop/src/photo_selector_toolbox/` | — | `photo-selector-toolbox`, Homebrew Formula + Cask | macOS, Linux, Windows workstations |

The platform descriptor may follow the name ("Frameduel, the Android Desktop app") but never
replaces it in user-facing text. The slug names tags (`<slug>-vX.Y.Z`), folders and agents.

Plus two shared libraries, which are not products:

| Canonical name | Code | Gradle module | Package |
|---|---|---|---|
| **Android Core** | `products/android/core/` | `:core` | `com.photoselector.core` |
| **Android SAF** | `products/android/saf/` | `:saf` | `com.photoselector.saf` |

## Former names — aliases, do not use in new text

| Do not write | Write instead | Why |
|---|---|---|
| PhotoTok, Photo-Tok, Photo Tok, `Phone Tok`, `Phone mode` | Jackpix | Renamed 2026-10; "PhotoTok" survives as the code name (`:phototok`, `PhotoTokTheme`) |
| PhotoSelector, PhotoSelector Studio (on Android) | Frameduel | Renamed 2026-10 |
| PhotoSelector Studio (on the Mac), `PhotoSelector.app` | Lensjet, `Lensjet.app` | Renamed 2026-10 |
| Photo Selector, Photo Selector Linux | Blitzlens | Renamed 2026-10 |
| Photo Selector Toolbox (for anything but the legacy app or the repository) | the app's name, or the list of the four apps | There is no umbrella name |
| "the Android app" | Frameduel, or Jackpix | There are two Android products |
| "Toolbox" (on its own) | Frameduel, or Desktop (legacy) | `:android-desktop`'s artifacts are named `photo-selector-toolbox-android-*`, but so is the Python product |
| "the Mac app", "the Linux app", "the desktop app" | Lensjet, Blitzlens, Desktop (legacy) | The repository ships several desktop apps |
| "tablet mode" / "phone mode" as product names | Frameduel / Jackpix | These are window size classes, not products |

## Identifiers that keep the old names

Identifiers bound to stores, package managers, signing or user data are **not** renamed;
a rename there means a new store listing, a broken update path or lost settings. Prose uses
the app name, build configuration keeps the identifier.

| Identifier | Where | Kept because |
|---|---|---|
| `com.phototok`, `com.photoselectortoolbox` (incl. `.lab`), Kotlin namespaces and packages | Android `build.gradle.kts`, sources | Play listing, Firebase, signing |
| Firebase projects `phototok-app`, `photo-selector-tb-dist`; CI variables and secrets `PHOTOTOK_*`, `TOOLBOX_*`; keystore aliases | `.github/workflows/android.yml`, signing | distribution pipeline (separate migration) |
| Release asset names `phototok-android-release.*`, `photo-selector-toolbox-android-release.*`, `photo-selector-macos-*.zip` | release workflows | download links and update tools |
| Tags `phototok-v*`, `android-desktop-v*`, `macos-desktop-v*`, `linux-desktop-v*` | releases | release history and `scripts/release.py` |
| Folder `PhotoTok_LeftSwipe`, legacy `PhotoTok_Selection` exclusion | Jackpix, every product's scanner | user files already on disk |
| Marker file `.photoselector-selection` | every product | Selection folders users already have |
| Debian package `photo-selector-linux`, settings `~/.config/photo-selector-linux/`, APT Origin/Label | Blitzlens | installed systems keep updating; settings kept |
| Homebrew cask token | `alexpp90/homebrew-tap` | installed casks keep updating |
| Gradle modules, Swift targets, Python packages, folder names | code | internal |

## Terms of art

| Term | Meaning |
|---|---|
| **Selection** | The destination subfolder (default name `Selection`) that kept photos are moved or copied into. Same concept across all products. |
| **Selector** | The comparison workspace: previous / current / next, with quality scores. Desktop (legacy), Frameduel, Lensjet and Blitzlens. |
| **Feed** | Jackpix's vertically paged stream of photos. Jackpix only. |
| **Score** | One of sharpness, noise, highlight clipping, shadow clipping, aesthetics — computed identically in Desktop (legacy) and Frameduel (see [`shared/FEATURE_PARITY.md`](shared/FEATURE_PARITY.md)), natively via Apple Accelerate and Vision in Lensjet, and natively via a NumPy Laplacian focus variance in Blitzlens. Jackpix computes none of them. |
| **Window size class** | Compact (< 600 dp), Medium (600–840 dp), Expanded (≥ 840 dp). A layout dimension, never a product name. |
