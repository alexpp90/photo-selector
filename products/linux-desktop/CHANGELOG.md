# Changelog — Blitzlens

Notable changes to Blitzlens, released under the tags `linux-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

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
