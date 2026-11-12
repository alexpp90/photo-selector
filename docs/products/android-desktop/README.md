# Android Desktop

**Frameduel** (formerly PhotoSelector Studio) — store title "Frameduel – Photo Culling Desk".

The tablet / Samsung DeX / Chromebook product: a comparison workspace that approaches desktop
parity on large screens.

- **Cull** — three equal frames, one over two, the current one centred, with EXIF and the lens
  beside each; quality scoring with OpenCV (sharpness, exposure), burst grouping, sort orders and
  Go to photo. `M` moves, `C` copies and `Del` deletes with its companions (RAW, JPEG, `-Edit`,
  `.xmp`) into the Selection folder; `Ctrl+Z`, or **Undo** in the More menu, steps back through
  the last 20 deletes and moves, also in fullscreen. In a window narrower than 600 dp the More
  button at the end of the action row keeps Open folder, Scan, Sort, Go to photo and Undo.
- **Learn** — technique and composition of your own photos, from the scan's facts, with the
  leading lines and visual weight of the photo shown; star ratings, a reject flag and a star
  filter that limits Learn only (Cull always shows every photo).
- **Stats** and **Dupes** — EXIF charts of the open folder and a finder for byte-identical
  copies (it never lets you delete the last copy of a photo). A delete there is held like one in
  Cull: UNDO brings the copies back until you leave Dupes. **Setup** holds the settings.
- **Scan** — choose which measurements run (focus, noise, highlight and shadow clipping); an
  unticked one is not computed and shows no value.
- A Room-backed score cache; ratings are kept in the app and written to an `.xmp` sidecar beside
  RAW and TIFF. Embedding them in a JPEG is an opt-in setting (**Write ratings into photos**), off
  on a new install; an install that already wrote ratings into JPEGs keeps doing so.
- Interface language: English only for now.

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
RAW, and displayed where its embedded preview allows. A RAW, its JPEG, its `-Edit`/`-Enhanced-NR`
versions and its `.xmp` sidecars are **one frame**, led by the JPEG (else the edit, else the RAW);
filing sorts them into `RAW/` and `JPEG/`, and Delete on a frame led by an edit removes the edit
and its own sidecars only. The one list lives in `domain/format/ImageFormats.kt`,
and `FormatConformanceTest` holds it to `testdata/formats/manifest.json`.

## Documents

- `REQUIREMENTS.md` (maintainer-private) — authoritative behaviour specification
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — layers, adaptive layout strategy, performance, theme implementation
- [`DESIGN.md`](DESIGN.md) — the visual specification: layout maths, tokens, screens, component sheet
- `DESIGN_PROMPT.md` (maintainer-private) — the same design expressed as a brief for a design tool
- Shared with Jackpix: [`../../shared/ANDROID_PLATFORM.md`](../../shared/ANDROID_PLATFORM.md)
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

The instrumented screen tests of the expanded layout carry `@ExpandedWindow`: `WindowGeometryRule`
puts the device (an emulator or a phone) into the reference geometry, 1480 x 924 dp, for the test and
restores it afterwards, so they assert on CI's phone-class emulator instead of returning early
(`docs/build/CI_PARITY.md`, rule 7).

## Relationship to the other products

Android Desktop shares the EXIF model and readers with Jackpix through the `:core` module
(`products/android/core/`, `com.photoselector.core`). Everything else — layouts, ViewModels, theme,
storage flow — is independent. Do not copy composables between `:android-desktop` and `:phototok`.

Excluded here: aesthetic scoring (Ollama VLM on legacy Desktop; Apple Vision on macOS Desktop), the CLI,
ExifTool bundling and SMB path resolution. See [`../../shared/FEATURE_PARITY.md`](../../shared/FEATURE_PARITY.md).
