# Changelog — Blitzlens

Notable changes to Blitzlens, released under the tags `linux-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Fixed
- White text on the emerald **Move** button and the "sharp" score badge was hard to read (2.3:1); it is now dark on the same green (8:1).
- If Undo of a Move could not put every file back, the error now names the files that stayed; before, they were dropped silently.
- A Trash undo that failed half-way can be tried again: the files already restored are recognised instead of blocking the retry.
- Exposure, ISO, aperture and lens were empty on systems with a newer Pillow (the MakerNote tag was renamed).
- The Debian package is built reproducibly: file dates follow the top `debian/changelog` entry (they said 2025) and the checksum list has a fixed order.

## [0.2.0] - 2026-10-20

### Changed
- The app is now called **Blitzlens** (formerly Photo Selector Linux): launcher, window title, About dialog, package description and the APT repository pages.
- New application id `app.alpatix.Blitzlens`. After the upgrade the app appears as a new launcher entry and the old launcher entry is removed. Pin or favourite it again if you had done so. Your settings in `~/.config/photo-selector-linux/` are kept.
- Selection and focus colours (active slot border, Copy button, sharpness meter) follow the system accent colour. Large surfaces are neutral grey. The keep, acceptable and cull colours are unchanged. The app stays dark.
- Package and AppStream metadata point at the current project page and issue tracker, and describe the app in plain words.

### Added
- New icon, with a symbolic variant for the shell and dedicated 16 and 24 pixel sizes.
- An About dialog in the main menu.

### Fixed
- When a failed Move or Copy cannot undo its own steps, the files left in the Selection folder are reported and can be undone, instead of the app saying everything was rolled back.
- The package installs one launcher entry and one icon instead of two of each.
- The APT setup script stops with an error instead of hiding a failed download.
- The launcher opens every supported photo format, including more RAW formats, WebP and HEIF.
