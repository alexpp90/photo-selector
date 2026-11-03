# Changelog — Blitzlens

Notable changes to Blitzlens, released under the tags `linux-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Fixed
- A copy to the Selection folder is written under a hidden temporary name and takes its real name only when complete, so a crash or power loss can no longer leave a truncated photo there. A Trash on another drive whose original cannot be removed no longer leaves a stray file in the Trash.
- "Open with Blitzlens" on a photo (or several) opens the photo's folder with that photo selected. Before, one photo showed an empty "Loaded 0 photographs" and several photos failed with a usage error.
- A start-up error is no longer reported as "Core Engine Ready" with exit status 0. It stops with the error and a non-zero status.
- **Lowest sharpness first** now orders the whole folder. Before, only the few photos you had already looked at were sorted and the rest stayed in file order behind them. Choosing the order scores every photo in the background (on a cloud folder: choose it a second time to confirm), keeps the photo on screen where it is while the order settles, and starts at the lowest-scored photo if you have not moved on.
- Duplicate Finder and Library Statistics work on a cloud or remote folder. The first click now offers **Scan anyway**; before, the confirmation was asked for but nothing could give it (Statistics even opened as an empty window). Hashing for duplicates no longer freezes the window.
- Moving a symlinked photo to the Trash trashes the link, not the file it points at; Undo puts the link back. Before, the original in the other folder was trashed and a dangling link stayed.
- Closing the window while file operations are queued no longer drops them. Press M four times and quit, and all four photos are moved first; the window says "Finishing N file operations…" and closes when the last one is done. Closing a second time quits at once.
- A darktable-style sidecar (`DSC_0001.NEF.xmp`, `IMG_1.JPG.xmp`) now travels with its shot on Move, Copy and Trash whichever file of the shot it belongs to. Before, it stayed behind when a JPG sorted before the RAW (NEF, RAF, RW2, ORF, PEF and others).
- Opening a big folder no longer freezes the window. The scan (including the folder-name look-up on a cloud mount) runs in the background and photos appear as they are found. A folder of 2000 RAW+JPEG pairs took 46 seconds to list, and the window did not respond meanwhile; it now takes about 0.3 seconds.

## [0.2.0] - 2026-10-22

### Changed
- The app is now called **Blitzlens** (formerly Photo Selector Linux): launcher, window title, About dialog, package description and the APT repository pages.
- New application id `app.alpatix.Blitzlens`. After the upgrade the app appears as a new launcher entry and the old launcher entry is removed. Pin or favourite it again if you had done so. Your settings in `~/.config/photo-selector-linux/` are kept.
- Selection and focus colours (active slot border, Copy button, sharpness meter) follow the system accent colour. Large surfaces are neutral grey. The keep, acceptable and cull colours are unchanged. The app stays dark.
- Package and AppStream metadata point at the current project page and issue tracker, and describe the app in plain words.

### Added
- New icon, with a symbolic variant for the shell and dedicated 16 and 24 pixel sizes.
- An About dialog in the main menu.

### Fixed
- White text on the emerald **Move** button and the "sharp" score badge was hard to read (2.3:1); it is now dark on the same green (8:1).
- If Undo of a Move could not put every file back, the error now names the files that stayed; before, they were dropped silently.
- A Trash undo that failed half-way can be tried again: the files already restored are recognised instead of blocking the retry.
- Exposure, ISO, aperture and lens were empty on systems with a newer Pillow (the MakerNote tag was renamed).
- The Debian package is built reproducibly: file dates follow the top `debian/changelog` entry (they said 2025) and the checksum list has a fixed order.
- A Move to a folder on another drive whose original cannot be removed keeps the verified copy, says "Copied to … — original could not be removed" and stays undoable; renames on the same drive no longer need free space.
- A photo moved to the Trash is always recorded (or put back) before an error is shown, and trash folders on memory cards follow the FreeDesktop rules; Undo restores only from the recorded Trash location.
- Undo of a Move that left its original behind deletes the copy it made instead of creating a `-2` duplicate.
- Edited `-Enhanced-NR` versions travel with their shot on Move, Copy and Trash.
- When a name was taken, the message says so: "Moved to Selection/ as IMG_0001-2.JPG"; an undo that had to rename says "Restored as …".
- A Move, Copy or Trash that finishes after you switched folders keeps its undo record.
- Move, Copy and Trash run in the background, so a slow card or network folder no longer freezes the window during a transfer; the outcome shows beside the action buttons without moving them; 3-Up layout and HUD fixes from the device lab.
- When a failed Move or Copy cannot undo its own steps, the files left in the Selection folder are reported and can be undone, instead of the app saying everything was rolled back.
- The package installs one launcher entry and one icon instead of two of each.
- The APT setup script stops with an error instead of hiding a failed download.
- The launcher opens every supported photo format, including more RAW formats, WebP and HEIF.
