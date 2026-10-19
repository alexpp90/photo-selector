# Shared

Rules that must hold for **more than one product**. This directory is intentionally small.

The default is *not* shared. The products are independent implementations that happen to
live in one repository; they share photographic concepts, not code. A rule belongs here only if
breaking it in one product would make the products inconsistent in a way users or maintainers
would notice.

## What is here

| File | Applies to | Contents |
|---|---|---|
| [`ANDROID_PLATFORM.md`](ANDROID_PLATFORM.md) | Android Desktop + PhotoTok | The Android baseline both apps obey: tech stack, EXIF extraction contract, storage and file access, project structure |
| [`FEATURE_PARITY.md`](FEATURE_PARITY.md) | All products | The feature sync policy, the permanent exclusions, and cross-product feature mapping |
| [`FILE_OPERATIONS.md`](FILE_OPERATIONS.md) | All products | The non-destructive file operation contract: collision-free bundle naming, verified move before source removal, non-destructive undo, and partial failure reporting |
| [`SUPPORTED_FORMATS.md`](SUPPORTED_FORMATS.md) | All products | The supported file-format contract, the real-camera test corpus, and how every product's format conformance tests keep it working |
| [`UI_TEXT.md`](UI_TEXT.md) | All products (checked for PhotoTok and Android Desktop) | Icon-first controls, visible-text budgets per element in EN and DE, when a description is allowed, accessibility and localization rules, and the review checklist for UI text |

## What is shared in code

Exactly two modules, both used by both Android products:

- `products/android/core/` (`:core`, `com.photoselector.core`): the EXIF data class, the EXIF
  reader strategies, embedded-preview extraction and storage/source-kind detection.
- `products/android/saf/` (`:saf`, `com.photoselector.saf`): the SAF file operations that
  [`FILE_OPERATIONS.md`](FILE_OPERATIONS.md) specifies. That covers the copy/move engine
  (`SafTransferEngine`): collision-free bundle naming, size verification before a source is
  deleted, partial outcomes, at most two transfers at once and one at a time per destination,
  and undo into the original folder. It also covers the persisted-grant bookkeeping
  (`FolderAccessManager`, the only caller of `take`/`releasePersistableUriPermission`).
  Where the two products differ, the product passes the difference in as configuration —
  `SafTransferPolicy` (all-or-nothing or per-file bundles, when the Selection marker is
  written, fsync for removable media), `SelectionSubfolderRule` (its own RAW/JPEG sorting),
  `SelectionDestination` (folder name, Android Desktop's second pass into `Final`) — and
  never forks the module.

**Admission rule.** A file belongs in `:core` or `:saf` when it is identical for both Android products
*and would stay identical* if either product evolved independently. Files that merely look
similar today stay duplicated on purpose. `ImageItem`, for instance, is **not** shared:
Android Desktop's carries a `scanResult` field that PhotoTok has no concept of.

`:core` and `:saf` must not depend on Compose, Room, OpenCV, Vico or on either app module;
`:saf` also has no Hilt and no product types (each product provides it in its own graph).

The Desktop and macOS Desktop products share no code with the Android products — only the data *contract*.
`core/models.py`'s `ExifData` and `com.photoselector.core.model.ExifData` must describe the
same fields; changing one is a cross-product change.

## What is deliberately not shared

- **Themes.** Android Desktop and PhotoTok have different visual identities. Their `Color.kt`,
  `Theme.kt` and `Type.kt` differ and will keep differing.
- **Settings screens.** Same name, different products, different content.
- **Image sources and repositories.** Android Desktop scans and caches scores; PhotoTok
  discovers progressively and never scores. The similar file names are a coincidence of
  vocabulary, not a shared abstraction.
- **Anything in `products/desktop/src/`.** The Desktop product is a separate implementation in a separate
  language.
- **Anything in `products/macos-desktop/src/`.** The macOS Desktop product is a separate implementation in Swift.
- **Anything in `products/linux-desktop/src/`.** The Linux Desktop product is a separate implementation in Python / GTK4 / Libadwaita.
