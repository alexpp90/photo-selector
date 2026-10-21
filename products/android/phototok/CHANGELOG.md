# Changelog — Jackpix

Notable changes to Jackpix, released under the tags `phototok-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Fixed
- When one file of a photo with companions could not be copied or moved (for example the storage provider failed midway), the files that had already moved are reported as moved. Before, the whole shot was reported as failed and came back into the feed although part of it was already in Selection.

### Changed
- Much less text. The viewer bottom bar, the top bar and the landscape side panels are icon-only now: each control keeps its spoken name, shows it as a tooltip on long-press, and keeps its 48 dp target. The controls guide names each control in one or two words and shows only the configured action and folder under the swipe callouts; the first-launch guide has no title and an icon-only dismiss button. Settings rows have a title and, at most, a value or a data-loss warning; the landing screen has no tagline, section labels or card descriptions. Dialog buttons are short ("Merge pairs", "Link both", "Don't change", "Keep all"), hints and snackbars are a few words, milestones no longer shout, and error messages never show a raw exception text.
- Jackpix is available in German. Every visible string is a resource in English and German.
- The Selection chip in the Selection viewer shows the Selection folder's name on the Favourites tab as well (it showed "Favourites" twice).

## [0.6.0] - 2026-10-20

### Changed
- The app is now called Jackpix (formerly Photo-Tok), with a new icon, including a themed icon that follows your wallpaper colours on Android 13 and later.
- New colours: neutral grey surfaces around your photos and a lime accent.
- GitHub releases are now titled "Jackpix X.Y.Z". If you filter release titles in Obtainium, update the filter to `^Jackpix `.

### Fixed
- On small phones (5–6" screens, such as a 360×640 dp Sony Xperia XA2) all three sources on the start screen (Phone Camera, SD Card / USB Reader, Folder or Cloud) are visible without scrolling, also with larger text. They are now compact rows, and the logo and photo fan shrink or hide on short screens. "Folder or Cloud" used to sit below the screen edge or half under the navigation bar.
- A copy or move to Selection lands in the `Selection/` subfolder again when "Organize Selection by RAW / JPEG" is off (the default on phones). It used to land in the source folder next to the original as `DSC_0007-2.JPG`, show up in the feed as a new photo, and leave the `.photoselector-selection` marker in the source folder. The left-swipe copy/move (`PhotoTok_LeftSwipe/`) and "Copy favourites to folder…" (`Favourites/`) follow the same rule.
- The Privacy Policy, Legal Notice and Source Code links in Settings open real pages again; they pointed at addresses that did not exist.

