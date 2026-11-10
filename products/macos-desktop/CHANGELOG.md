# Changelog — Lensjet (macOS Desktop)

Notable changes to Lensjet, released under the tags `macos-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Added

- Settings → General → Culling Destination now shows where Move and Copy put your photos. Choose **Working folder** (the Selection folder inside the folder you are culling, as before) or **Custom folder** (pick a folder, for example on an SSD), and read the full destination path right below, such as `~/Pictures/Trip/Selection/`. With no folder open it says so. A custom folder that is gone or not writable shows a warning, and Move and Copy refuse instead of creating it. If you already used a custom destination, it stays selected.
- macOS asks for access to Desktop, Documents, Downloads, removable and network volumes with an explanation ("Lensjet shows the photos in the folders you open and moves, copies or trashes the ones you cull.").
- The README and the Homebrew cask now say how to open Lensjet the first time: it is not notarized by Apple yet, so macOS blocks a downloaded copy until you click **Open Anyway** in System Settings → Privacy & Security (or run `xattr -dr com.apple.quarantine /Applications/Lensjet.app`).

- **View > Sort By**: Newest First, Oldest First, File Name and Lowest Sharpness First, with a checkmark on the active order. The choice is kept. A new install opens newest first; an install that already existed keeps the file-name order it had. A non-default order shows as a small chip in the toolbar. Lowest Sharpness First scores every photo in the background first (the toolbar shows "Scoring 12 / 17"), then orders the list; **Settings → Scoring → Stop at sharpness** (0 = off) toasts when you reach a photo at or above that sharpness.
- **Go to Photo** (`G`, View menu): type a number (the position in "Photo 12 of 240") or part of a file name; the sheet shows the file name it will land on, and "No match" when there is none.
- A photo that travels with a RAW, JPEG, XMP sidecar or edit shows **chips** (`JPG`, `XMP`, …) beside its file type, and Move, Copy, Trash and Undo of such a set say how many files moved: "Moved 3 files", "Copied 3 files", "Trashed 3 files", "Restored 3 files".
- The bottom tally shows **Copied N** once you have copied something, and Undo takes it off again.
- Each score shows its scale: "Sharpness 59.6 / 100" and "Aesthetics 8.4 / 10" in the info panel, a tooltip on the badges saying what they measure and where the bands are, and a spoken label that says "aesthetics score", never a star rating.
- Library Statistics scores the whole library in the background while it is open ("12 / 17 scored", with a progress bar), so the sharpness figures and the distribution cover every photo; closing the sheet stops the pass.
- Toasts are read out by VoiceOver.
- Stepping backwards through a shoot now prefetches the frames behind you (the prefetch window leans the way you browse, same size).

### Fixed

- After Undo, the restored photo appears at once from memory instead of as a black pane with a spinner; the spinner only appears after 300 ms.
- A custom destination folder placed inside the folder you are culling no longer lets the photos you filed there come back as candidates.
- A Copy that loses a race for its destination file name no longer deletes the file that other writer put there.
- A build whose code signing fails now stops instead of packaging an unsigned app.
- The README no longer promises "zero data loss" or shows a pre-rebrand screenshot caption, and its test command is the CI one (`./scripts/run_tests.sh --macos`).

## [0.2.0] - 2026-10-22

### Changed

- The app is now **Lensjet** (formerly PhotoSelector Studio): new name in the window title, the menus and the app bundle, which is now `Lensjet.app`. The Homebrew cask keeps its name.
- New bundle id `app.alpatix.lensjet`. Your settings (destination folder, auto-advance, sharpness and aesthetic thresholds, cache and preload sizes) are copied over once on the first launch; the old settings are left in place.
- Interface colours are now named tokens: surfaces are neutral grey, and selection and focus follow your macOS accent colour. Move, copy, trash, focus-quality and rating colours are unchanged.

### Added

- New app icon.

### Fixed

- The Selected, Copied and Trashed badges on thumbnails use black or white text, whichever reads better on the badge colour (the white on Selected green was 2.5:1).
- The "?" button uses your accent colour unblended, so its black or white symbol keeps the contrast it was chosen for.
- When an interrupted Move cannot put every file back, the message now names the photo and the folder: "Move interrupted — some files of IMG_0001.CR3 stayed in Selection/ and could not be put back".
- Homebrew: the cask installs Lensjet from its own release tag (`macos-desktop-v…`) with the new name, and `brew uninstall --zap` also removes the new preferences file. Until this release the stable cask pointed at a download that did not exist.
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
