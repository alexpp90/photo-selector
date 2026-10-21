# Changelog — macOS Desktop

Notable changes to macOS Desktop, released under the tags `macos-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

## [0.2.0] - 2026-10-20

### Changed

- The app is now **Lensjet** (formerly PhotoSelector Studio): new name in the window title, the menus and the app bundle, which is now `Lensjet.app`. The Homebrew cask keeps its name.
- New bundle id `app.alpatix.lensjet`. Your settings (destination folder, auto-advance, sharpness and aesthetic thresholds, cache and preload sizes) are copied over once on the first launch; the old settings are left in place.
- Interface colours are now named tokens: surfaces are neutral grey, and selection and focus follow your macOS accent colour. Move, copy, trash, focus-quality and rating colours are unchanged.

### Added

- New app icon.

### Fixed

- Labels on your accent colour (the CURRENT badge, the selected view mode and prominent buttons) switch to black text where white is hard to read, as on yellow, orange or blue.

### Not carried over

- macOS privacy permissions for Desktop, Documents, Downloads and removable volumes belong to the bundle id, so macOS asks again, and any saved folder access has to be granted again.
- The old app stays installed until you remove it. Dock and Login Items entries point at the old app; add Lensjet again.
