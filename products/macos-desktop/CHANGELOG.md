# Changelog — Lensjet (macOS Desktop)

Notable changes to Lensjet, released under the tags `macos-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Fixed

- The Selected, Copied and Trashed badges on thumbnails use black or white text, whichever reads better on the badge colour (the white on Selected green was 2.5:1).
- The "?" button uses your accent colour unblended, so its black or white symbol keeps the contrast it was chosen for.
- When an interrupted Move cannot put every file back, the message now names the photo and the folder: "Move interrupted — some files of IMG_0001.CR3 stayed in Selection/ and could not be put back".

## [0.2.0] - 2026-10-20

### Changed

- The app is now **Lensjet** (formerly PhotoSelector Studio): new name in the window title, the menus and the app bundle, which is now `Lensjet.app`. The Homebrew cask keeps its name.
- New bundle id `app.alpatix.lensjet`. Your settings (destination folder, auto-advance, sharpness and aesthetic thresholds, cache and preload sizes) are copied over once on the first launch; the old settings are left in place.
- Interface colours are now named tokens: surfaces are neutral grey, and selection and focus follow your macOS accent colour. Move, copy, trash, focus-quality and rating colours are unchanged.

### Added

- New app icon.

### Fixed

- Undo of a Trash restores exactly the files that were trashed, and a bundle (a photo with its RAW, sidecar or edit) whose trashing half-failed stays undoable instead of losing its record.
- A Move that copied a photo but could not remove the original says so ("Copied to «folder» — original could not be removed"), keeps the copy, and its undo deletes only those copies; a Move whose rollback failed is reported as a partial result with an undo record.
- When a file had to be renamed to avoid a name clash, the message tells you the new name ("Moved to Selection as IMG_0001-2.CR3 (+1 related)"); an undo that had to rename says "Restored as … (original name was in use)".
- Files named `-Enhanced-NR` or `_Enhanced-NR` now travel with their shot, like `-Edit` files.
- Undo survives a folder switch: each folder keeps its own undo history (the newest 8 folders), and a Move, Copy or Trash that finishes after you switched folders updates its own folder and no longer touches the one you opened.
- File operations run on their own queue, so a slow disk or card no longer stalls the app.
- In narrow windows the toolbar mode switcher and the action buttons no longer wrap mid-word; they shorten their labels instead, and the toast and zoom indicator stay off the photo information.
- Pointer targets are at least 24 pt, and the Pan Linked control moved to the toolbar.
- Labels on your accent colour (the CURRENT badge, the selected view mode and prominent buttons) switch to black text where white is hard to read, as on yellow, orange or blue.

### Not carried over

- macOS privacy permissions for Desktop, Documents, Downloads and removable volumes belong to the bundle id, so macOS asks again. The window size and position start from the default once, because only the app settings are copied.
- The old app stays installed until you remove it. Dock and Login Items entries point at the old app; add Lensjet again.
