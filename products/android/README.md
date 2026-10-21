# Android

The Gradle build root for **both** Android products, plus the two modules they share.

```
products/android/
  settings.gradle.kts   Declares :android-desktop, :phototok, :core, :saf
  build.gradle.kts      Plugin versions only (all `apply false`)
  gradle/               Version catalog (libs.versions.toml) and wrapper
  gradlew  gradlew.bat  gradle.properties

  android-desktop/      PRODUCT — :android-desktop, com.photoselectortoolbox
  phototok/             PRODUCT — :phototok, com.phototok
  core/                 LIBRARY — :core, com.photoselector.core (EXIF model and readers)
  saf/                  LIBRARY — :saf, com.photoselector.saf (SAF file operations)
```

Two products, one build. They are not variants of each other: different interaction models,
different themes, different data layers, separate Play listings, separate signing keys and
separate Google Cloud projects. What they share is this Gradle build, `:core` and `:saf`.

## Module layout

Each module uses the repository's uniform product shape rather than Gradle's default:

```
<module>/
  src/                  Kotlin sources (was src/main/java)
  res/                  Resources (was src/main/res)          — apps only
  jniLibs/              Native libraries (was src/main/jniLibs) — :android-desktop only
  AndroidManifest.xml   (was src/main/AndroidManifest.xml)     — apps only
  tests/unit/           JVM unit tests (was src/test/java)
  tests/instrumented/   Emulator tests (was src/androidTest/java)
  build.gradle.kts
```

The `sourceSets { }` block near the top of each `build.gradle.kts` is what makes this work.
If you add a module, copy that block; if you change it, change all four.

## Commands

```bash
cd products/android

./gradlew :android-desktop:testDebugUnitTest
./gradlew :phototok:testDebugUnitTest
./gradlew :core:testDebugUnitTest :saf:testDebugUnitTest

# The step people skip — the only task that compiles tests/instrumented/
./gradlew :android-desktop:assembleDebugAndroidTest :phototok:assembleDebugAndroidTest

./gradlew :android-desktop:assembleDebug
./gradlew :phototok:assembleDebug
```

From the repository root, the CI mirror runs all of the above: `./scripts/run_tests.sh --android`.

## `:core` — what may live here

A file belongs in `core/` when it is identical for both products **and would stay identical**
if either product evolved independently. Files that merely look similar today stay duplicated
on purpose. `ImageItem` is the worked example: Android Desktop's carries a `scanResult` field
Jackpix has no concept of, so it is not shared.

`:core` must not depend on Compose, Room, OpenCV, Vico, or on either app module.

## `:saf` — the SAF file operations

`saf/` holds what both products do to photographs through the Storage Access Framework: the
verified copy/move engine (`SafTransferEngine` — one collision-free `-N` name per companion
bundle, never an overwrite, size verified before a source is deleted, partial outcomes, at most
two transfers at once and one at a time per destination, undo into the folder the photo came
from) and the persisted-grant bookkeeping (`FolderAccessManager`, the only caller of
`take`/`releasePersistableUriPermission` — `SoleCallerRuleTest` enforces it).

The same admission rule as `:core` applies. Where the products differ, the product passes the
difference in as configuration — `SafTransferPolicy` (all-or-nothing or per-file bundles, when
the Selection marker is written, which destinations are fsynced), `SelectionSubfolderRule`
(its own RAW/JPEG sorting) and `SelectionDestination` (folder name, Android Desktop's second
pass) — and never forks the module. `:saf` depends on DocumentFile and coroutines only: no
Compose, Hilt, Room, `:core` or product types. Each product provides it in its own
dependency graph.

## Documentation

- [`../../docs/products/android-desktop/`](../../docs/products/android-desktop/) — requirements, architecture, design
- [`../../docs/products/phototok/`](../../docs/products/phototok/) — requirements, architecture, design
- [`../../docs/shared/ANDROID_PLATFORM.md`](../../docs/shared/ANDROID_PLATFORM.md) — the baseline both apps obey
- [`../../docs/build/ANDROID_BUILD_AND_RELEASE.md`](../../docs/build/ANDROID_BUILD_AND_RELEASE.md) — CI, signing, store publishing
