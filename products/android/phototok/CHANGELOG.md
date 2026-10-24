# Changelog — Jackpix

Notable changes to Jackpix, released under the tags `phototok-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Fixed
- System Back (and the back gesture) in the Selection viewer now returns to the feed on the photo you came from, instead of leaving the app.

### Added
- Favourites now reach Android (11 and later): Jackpix sets the system favourite flag for photos on the phone and on an indexed SD card, RAW+JPEG companions included, and clears it when you remove the favourite. The changes go out in batches when you leave the feed, switch folders, open the Selection viewer, or after 25 changes. Favourites set in another app show in Jackpix. Before, favourites never left Jackpix.
- **Favourite** / **Remove favourite** in the long-press menu.
- The favourite badge shows a filled star when the system has the favourite too, and an outlined star while it is only in Jackpix.

### Changed
- A favourite is recognised whichever folder you open the photo from (`DCIM` or `DCIM/Camera`, or the system's Images view). Existing favourites are kept.

### Fixed
- **Reload** now shows the newest photo as 1 of N. Before, once you had seen the newest photo, Reload landed behind all the photos you had not seen yet, so the position changed with every reload. Reload also no longer shows "No new photos".

## [0.6.0] - 2026-10-23

### Added
- A visible **Share** button for the current photo in the viewer bottom bar and the landscape left panel (it was only in the long-press menu); the controls guide labels it ("Share" / "Teilen").
- When you reopen a folder you have been through before, the app offers to resume where you stopped; it does not ask when there are no new photos since.
- Copy and move feedback names the file when it got a collision suffix ("Moved as IMG_0007-2.JPG").
- Several delete swipes can be undone one after another, newest first.

### Changed
- **Reload** (top bar, landscape panel, Settings) now always rescans and takes you to the latest photo, whether or not anything new appeared, and no longer asks "N new photos". The prompt stays for reopening a folder. When nothing new turned up the snackbar still says so.
- The Selection viewer opens on the list your swipe right fills (★ Favourite → Favourites, Copy or Move → Selection) instead of always on Favourites. The two tabs carry an icon (`Star`, `Folder`) and the photo count ("Favourites 3", "Selection 12") and appear only when both lists have photos; with one list a plain title replaces them.
- The bottom bar's side padding is 8 dp (was 24 dp) so four buttons fit a 320 dp phone; the controls guide puts the four bottom callouts on two rows where the labels would not fit side by side.
- Much less text. The viewer bottom bar, the top bar and the landscape side panels are icon-only now: each control keeps its spoken name, shows it as a tooltip on long-press, and keeps its 48 dp target. The controls guide names each control in one or two words and shows only the configured action and folder under the swipe callouts; the first-launch guide has no title and an icon-only dismiss button. Settings rows have a title and, at most, a value or a data-loss warning; the landing screen has no tagline, section labels or card descriptions. Dialog buttons are short ("Merge pairs", "Link both", "Don't change", "Keep all"), hints and snackbars are a few words, milestones no longer shout, and error messages never show a raw exception text.
- Jackpix is available in German. Every visible string is a resource in English and German.
- The Selection chip in the Selection viewer shows the Selection folder's name on the Favourites tab as well (it showed "Favourites" twice).
- The app is now called Jackpix (formerly Photo-Tok), with a new icon, including a themed icon that follows your wallpaper colours on Android 13 and later.
- New colours: neutral grey surfaces around your photos and a lime accent.
- GitHub releases are now titled "Jackpix X.Y.Z". If you filter release titles in Obtainium, update the filter to `^Jackpix `.

### Fixed
- With file sorting on, a photo's sidecar (`IMG.xmp` as well as `IMG.CR3.xmp`) and its edits (`-Edit`, `_Edit`, `-Enhanced-NR`, any extension) now go into the same subfolder as its RAW (or its JPEG when there is no RAW), instead of some of them landing loose in the Selection folder.
- When one file of a photo with companions could not be copied or moved (for example the storage provider failed midway), the files that had already moved are reported as moved. Before, the whole shot was reported as failed and came back into the feed although part of it was already in Selection.
- On small phones (5–6" screens, such as a 360×640 dp Sony Xperia XA2) all three sources on the start screen (Phone Camera, SD Card / USB Reader, Folder or Cloud) are visible without scrolling, also with larger text. They are now compact rows, and the logo and photo fan shrink or hide on short screens. "Folder or Cloud" used to sit below the screen edge or half under the navigation bar.
- A copy or move to Selection lands in the `Selection/` subfolder again when "Organize Selection by RAW / JPEG" is off (the default on phones). It used to land in the source folder next to the original as `DSC_0007-2.JPG`, show up in the feed as a new photo, and leave the `.photoselector-selection` marker in the source folder. The left-swipe copy/move (`PhotoTok_LeftSwipe/`) and "Copy favourites to folder…" (`Favourites/`) follow the same rule.
- The Privacy Policy, Legal Notice and Source Code links in Settings open real pages again; they pointed at addresses that did not exist.
- Phone Camera opens the folder picker inside DCIM/Camera instead of at the top of the storage.
- Moving, copying or deleting a photo takes its companions along (the RAW or JPEG of the same shot, derivatives and `.xmp` sidecars), and a delete of a photo with companions can be undone as one.
- A failed delete puts the photo back and says so; a move whose original could not be removed keeps the photo in the feed and says the original stayed.
- Two quick swipes into the same Selection folder can no longer pick the same file name or create a second `Selection (1)` folder.
- Photos waiting to be deleted keep their folder's access when you switch to another folder, and results still coming in from a previous folder never land in the new one.
- The controls guide labels the controls that are really on screen, and Revert shows the restored photo.
- Layout fixes for tablets and Chromebooks.
- Newest photos come first in the feed.
