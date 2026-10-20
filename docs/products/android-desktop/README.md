# Android Desktop

**Frameduel** (formerly PhotoSelector Studio) — store title "Frameduel – Photo Culling Desk".

The tablet / Samsung DeX / Chromebook product: a comparison workspace that approaches desktop
parity on large screens. Quality scoring with OpenCV, a Room-backed score cache, statistics
charts and a duplicate finder.

| | |
|---|---|
| Code | `products/android/android-desktop/` |
| Gradle module | `:android-desktop` |
| Package | `com.photoselectortoolbox` |
| Target | Samsung DeX, tablets ≥ 840 dp, Chromebooks (Medium and Expanded window size classes) |
| Artifacts | `photo-selector-toolbox-android-release.apk` / `.aab` |

## Supported formats

Every format in the [shared contract](../../shared/SUPPORTED_FORMATS.md): RAW from Sony (`ARW`),
Canon (`CR2`, `CR3`), Nikon (`NEF`, `NRW`), Fujifilm (`RAF`), Panasonic (`RW2`), OM System /
Olympus (`ORF`), Pentax (`PEF`), Samsung (`SRW`) and `DNG`, plus `JPG`/`JPEG`, `HEIC`/`HEIF`,
`PNG`, `TIF`/`TIFF` and `WebP`, in any letter case. Each is discovered, displayed, read for EXIF
and scored. RAW frames are displayed and scored from the camera's embedded JPEG; Android has no
decoder for CR3 at all, and other RAWs vary by device. Phone DNGs embed only a ~672 px preview,
so they fall back to the platform DNG decoder. TIFF is decoded by the app, because Android has
no TIFF codec. The recognised tier (`3FR`, `IIQ`, `RWL`, `X3F`, …) is discovered and sorted as
RAW, and displayed where its embedded preview allows. A RAW and its JPEG appear as two frames;
filing sorts them into `RAW/` and `JPEG/`. The one list lives in `domain/format/ImageFormats.kt`,
and `FormatConformanceTest` holds it to `testdata/formats/manifest.json`.

## Documents

- `REQUIREMENTS.md` (maintainer-private) — authoritative behaviour specification
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — layers, adaptive layout strategy, performance, theme implementation
- [`DESIGN.md`](DESIGN.md) — the visual specification: layout maths, tokens, screens, component sheet
- `DESIGN_PROMPT.md` (maintainer-private) — the same design expressed as a brief for a design tool
- Shared with PhotoTok: [`../../shared/ANDROID_PLATFORM.md`](../../shared/ANDROID_PLATFORM.md)
- Build & release: [`../../build/ANDROID_BUILD_AND_RELEASE.md`](../../build/ANDROID_BUILD_AND_RELEASE.md)

`DESIGN.md` and `DESIGN_PROMPT.md` overlap substantially — the prompt is a derivative of the
specification, kept because it is fed to design tooling verbatim. If they disagree, `DESIGN.md`
wins.

## Build and test

```bash
cd android
./gradlew :android-desktop:testDebugUnitTest        # JVM unit tests (incl. format conformance)
./gradlew :android-desktop:assembleDebugAndroidTest # compiles instrumented tests — the step people skip
./gradlew :android-desktop:assembleDebug
```

Or, from the repository root, the CI mirror: `./scripts/run_tests.sh --android`.

## Relationship to the other products

Android Desktop shares the EXIF model and readers with PhotoTok through the `:core` module
(`products/android/core/`, `com.photoselector.core`). Everything else — layouts, ViewModels, theme,
storage flow — is independent. Do not copy composables between `:android-desktop` and `:phototok`.

Excluded here: aesthetic scoring (Ollama VLM on legacy Desktop; Apple Vision on macOS Desktop), the CLI,
ExifTool bundling and SMB path resolution. See [`../../shared/FEATURE_PARITY.md`](../../shared/FEATURE_PARITY.md).
