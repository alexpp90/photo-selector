# Changelog — PhotoTok

Notable changes to PhotoTok, released under the tags `phototok-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

## [0.6.0] - 2026-10-20

### Changed
- The app is now called Jackpix (formerly Photo-Tok), with a new icon, including a themed icon that follows your wallpaper colours on Android 13 and later.
- New colours: neutral grey surfaces around your photos and a lime accent.

### Fixed
- On small phones (5–6" screens, such as a 360×640 dp Sony Xperia XA2) all three sources on the start screen (Phone Camera, SD Card / USB Reader, Folder or Cloud) are visible without scrolling, also with larger text. They are now compact rows, and the logo and photo fan shrink or hide on short screens. "Folder or Cloud" used to sit below the screen edge or half under the navigation bar.
- A copy or move to Selection lands in the `Selection/` subfolder again when "Organize Selection by RAW / JPEG" is off (the default on phones). It used to land in the source folder next to the original as `DSC_0007-2.JPG`, show up in the feed as a new photo, and leave the `.photoselector-selection` marker in the source folder. The left-swipe copy/move (`PhotoTok_LeftSwipe/`) and "Copy favourites to folder…" (`Favourites/`) follow the same rule.
- The Privacy Policy, Legal Notice and Source Code links in Settings open real pages again; they pointed at addresses that did not exist.

