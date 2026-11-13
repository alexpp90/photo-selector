# Changelog — Jackpix

Notable changes to Jackpix, released under the tags `phototok-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

## [0.7.0] - 2026-11-13

### Added
- **Casual and Pro mode.** A new install starts in Casual mode: swipe fast with nothing to set up. Swipe right marks a ★ favourite on the phone, RAW + JPEG pairs show as one photo, there is no camera info, and Settings shows only the basics. The **Pro mode** switch below the three sources on the start screen (its help button lists what it adds) and at the top of Settings brings back camera info, swipe actions, folders, the Selection folder, feed order, format filter, RAW + JPEG linking and source profiles; the first time, it also turns camera info on. Switching Pro off pauses these settings without erasing them; switching it back on restores them exactly.
- Casual mode on an SD card or camera: swipe right copies the photo (with its RAW or JPEG twin) into the phone's camera folder, subfolder `Jackpix`. The first time, the system picker asks for access to `DCIM/Camera`; if you cancel, the photo stays on the card and the app says "Not copied". Pro mode keeps the card's own `Selection` folder.
- Deleted phone photos go to Android's Trash (Android 11 and later), where a gallery or files app that shows Android's trash can restore them for about 30 days. When you leave the folder, Android asks once for the whole batch (a batch left over when the app closed is asked for at the next start); declining keeps the photos ("N not deleted", **Retry**). The delete confirmation says per source what happens: "Move to Trash?" on the phone, "Delete permanently?" on an SD card, USB, camera or cloud folder, which have no trash, and "N to Trash · M permanent" when both apply.
- **Surprise me** in Casual: on a phone or cloud folder, the shuffle icon in the top bar deals the folder once with the photos you have not seen yet first, to rediscover old photos ("All seen" when there are none). Tap it again, or open the folder again, and it is newest first. SD cards and cameras keep their order.
- Favourites now reach Android (11 and later): Jackpix sets the system favourite flag for photos on the phone and on an indexed SD card, RAW + JPEG companions included, and clears it when you remove the favourite. The changes go out in batches when you leave the feed, switch folders, open the Selection viewer, or after 25 changes. Favourites set in another app show in Jackpix. Before, favourites never left Jackpix.
- The favourite badge shows a filled star when the system has the favourite too, and an outlined star while it is only in Jackpix.
- Tap the ★ Favourite chip on a photo to open your Favourites.
- **Favourite** / **Remove favourite** in the long-press menu.
- **Copy** and **Move** into the Selection folder in the long-press menu, named after their folder ("Copy · Selection", "Copy · Jackpix"). Move is not offered for a cloud folder, which cannot move.
- The delete confirmation has a **Don't ask** checkbox: tick it and confirm to stop the prompt for this source (the Settings row turns it back on). Cancel never turns it off. The confirmation stays on by default in both modes.
- Switching Pro mode off says what the swipes of the folder you were using do now ("Pro off: left deletes", "Pro off: right ★", "Pro off: right → phone"), with **Undo**; when a left swipe that moved or copied becomes a delete, the next delete asks once even with "Don't ask" saved.
- The **Card done** sheet shows how many of the card's photos are safe on the phone or another drive ("N copied"; "0 copied" with a warning sign when none are). A copy into the card's own Selection folder stays on the card and does not count, and the **Delete all** warning reads "Photos never copied are lost."
- The start screen shows what swipe right does per source before your first swipe (a star on the phone, a download arrow on an SD card that copies to the phone, a folder in Pro), on the source rows and the recent folders. The first SD card or camera opened in Casual says once that swiped photos are saved on this phone, and the Pro mode help lists "Card Selection".
- The start screen shows your totals (photos reviewed, kept, space freed) and, when you come back from a folder, that session's figures. Off with Milestones.
- A folder with videos and no photos says how many videos it hides: Jackpix shows photos, not videos.
- TalkBack can cull: the swipe right and swipe left you configured are custom actions on the photo ("Copy to Selection", "Delete photo"), and a delete asks first like the swipe does. Before, copy, move and delete were reachable only by dragging the photo.
- The info panel shows when the photo was taken first ("Aug 15, 2026, 10:36 AM", German "15.08.2026, 10:36"), as the camera recorded it.
- Settings lists the open-source licences of what Jackpix bundles (**Open-source licences**, below the Impressum), offline. Before, the app passed on no licence text for AndroidX, Hilt, Coil and the other libraries it ships.

### Changed
- Updating keeps your setup. Settings from every earlier version are carried over before the app first reads them, and an existing install that had changed a Pro setting, used camera info or an SD card starts in Pro mode, so nothing you set up disappears behind Casual mode.
- The start screen's "Camera" source is now **Phone photos** ("Handyfotos"): "Camera" means only a camera connected by USB. German calls the phone "Handy" everywhere, folder paths included.
- Pro mode Settings show where photos go: *Selection location* and *Left swipe folder* read as the chosen folder's path ("Phone › Pictures › Keepers", German "Handy › …"; a long one fits one line and the folder dialog shows it whole), or "In source folder · Phone › DCIM › Camera" for the default, instead of "Default" or "Custom". Tapping the row offers **Default** (the Selection subfolder inside the source folder) or **Choose folder**. A chosen folder whose access was lost, or that is gone or unplugged, says "Folder access lost" with **Re-select**.
- Confirm delete is per source: "Don't ask" on an SD card no longer turns the question off for the phone. An existing choice still applies to every source until you change it for one.
- Camera info and the (i) button exist only in Pro mode; the controls guide shows their callouts only there.
- The Selection button is a stack of photos with a star, and it is named after where swipe right puts photos: "Favourites" when swipe right marks a favourite, else the Selection folder's name, in the bar, the controls guide and the viewer it opens alike.
- The first-run tutorial teaches the swipes and points only at Help; Help shows the full guide with every bar button. Before, about fifteen labels came at once.
- After a delete a "Photo deleted" snackbar offers **Undo**, and the bar's undo-delete button is a back arrow instead of a bin with an arrow, which people read as a second delete.
- Marking a favourite no longer shows "Marked as favourite": the star flash and the lit favourite badge already show it. Removing a favourite still says so.
- The swipe indicators use the app's lime instead of a green, with a dark glyph on the lime disc and their label on a dark pill, so they are readable on any photo (white on the green was hard to read). The idle edge hints are clearly visible on a dark pill, and they stand still when animations are turned off.
- The idle edge hints sit on the side the photo moves toward: the delete mark at the left edge, the swipe-right mark at the right edge.
- A favourite is recognised whichever folder you open the photo from (`DCIM` or `DCIM/Camera`, or the system's Images view). Existing favourites are kept.
- Settings' reset rows say what they reset: "Reset seen photos" and "Reset all seen" (German "Gesehene zurücksetzen", "Alle Gesehenen zurücksetzen"); they never touched a folder's files.

### Fixed
- The newest photo is now always 1 of N in newest-first order, when you open a folder and after **Reload**. Before, the photos you had already seen moved to the end, so a newest photo you had looked at ended up near the highest number and you had to scroll back to it. Only shuffle still deals unseen photos first. Reload also no longer shows "No new photos".
- SD card and camera folders now start with the newest photo too, as phone folders do. Before, they started with the oldest. In Pro mode, Settings → Feed order still offers file-name (shooting) order, shuffle and landscape-first, per source.
- Preloading follows your scroll direction: it loads 4 photos ahead in the direction you scroll and 1 behind (2 and 0 for cloud folders), and turns round with you. Before, it loaded the same few photos on both sides, so scrolling back often hit photos that were not loaded yet.
- Pro mode: the **Selection folder** name you type in Settings is now the folder swipe right, Copy and Move file into (and the one the Selection viewer opens). Before, the setting did nothing and photos always went to "Selection". If you never typed a name nothing changes; Casual mode always uses "Selection".
- Pro mode Settings open on the tab of the source you are browsing (a cloud folder opens Phone + cloud, whose settings it follows). Before, they always opened on Phone + cloud, so choosing Move while browsing an SD card changed the phone's setting and the card kept copying.
- Opening another source no longer carries the previous source's Selection and left-swipe folders over. Before, a Pro user with a Selection folder on the SD card who then opened a phone folder moved phone photos into the card's folder (and the phone folder set in Settings caught the card's keepers); each source now uses its own folders, or its own source folder when it has none.
- A Selection location or left-swipe folder chosen for an SD card or camera now keeps its folder access the way the phone's does, and the access to a folder you replace is released.
- Your answer to the RAW + JPEG suggestion on an SD card or camera folder is saved for that source; before, it was always saved for the phone.
- **Delete all** on the **Card done** sheet now counts and deletes the same files: every file on the card, hidden RAW companions, files of another type and `.xmp` sidecars included ("Delete all 5 files?"). Before, the dialog showed the number of cards in the feed. It waits until the card has been read completely, and says "Card cleared" only when nothing is left; if some files could not be deleted they stay in the feed and the message says how many went.
- The "N copied" count on the **Card done** sheet now survives the app being closed or killed; before, it restarted from 0, next to the warning that photos not copied off the card are lost.
- Swiping left on an edited copy (`IMG_1-Edit.jpg`, `IMG_1-Enhanced-NR.dng`) now deletes only that file and its own sidecars. Before, it permanently deleted the whole shot with linked files on, the original RAW included; the original is deleted when you swipe the original.
- A favourite now follows its photo when you move it to the Selection folder (and a copy of a favourite is a favourite too). Before, moving a favourite silently dropped the star.
- Copying and moving are safer when a storage provider misbehaves: a photo whose size the provider does not report is never deleted after its copy (the move is reported as copied, with the original kept); a copy that could not be cleaned up after a failed transfer is reported instead of left unnoticed in the Selection; a photo that is already in the folder it would be filed into is left alone instead of being renamed to `-2`; leaving the screen while a move is under way no longer leaves a bundle half moved.
- A failed move that puts its photo back, and a move that takes one out, no longer overwrite a swipe or a batch of newly found photos that landed at the same moment (a photo you had just deleted could reappear, or a new photo disappear from the feed).
- Photos you had swiped left (not yet deleted) when the app was killed are simply still in their folder at the next start; the folder's access is released then instead of staying pinned for good, which could exhaust Android's limit on remembered folders over time.
- "Copy favourites to folder…" now copies each favourite with its linked files (RAW or JPEG twin, edited copies, `.xmp` sidecars) under one shared name, so a second run keeps the pairs paired, and it keeps going when you close the viewer. Before it copied single files, so sidecars were left behind and a RAW and its JPEG could get different suffixes. With no favourites it copies nothing (before, it copied the Selection list into `Favourites/`).
- Copy and move messages, the controls guide and the swipe label name the folder the photo lands in. With a custom Selection location they said "Copied to Pictures" for a photo in `Pictures/Selection/`; now "Copied to Selection". A folder whose name the storage provider does not report is named after its own path instead of an English "Folder" or "Photos".
- Copy and move feedback no longer says "Copied as 123" after every copy into a provider (Google Drive, some camera and media providers) whose file ids are not file names; it shows the plain "Copied to …" message unless the new name is certain.
- A stray `.photoselector-selection` marker file no longer hides a whole folder from the feed; the app's own output folders (Selection, Favourites, the Jackpix import folder and a Selection folder name you typed in Pro mode) are skipped by name.
- Swiping in big folders is smoother: marking a photo as seen and the review statistics are written in the background, the "seen" counter no longer re-checks the whole folder on every swipe, and folders with many photos no longer re-check them all for each batch while opening. The statistics file is now written safely (a kill mid-write can no longer reset the lifetime totals).
- Swiping a photo into a camera folder with thousands of photos no longer asks the storage provider about every photo first, so queued swipes finish sooner.
- Jackpix no longer opens every photo of a folder in the background to measure it. Only Pro mode's landscape-first order needs that; the file details measure just the photo they show. Before, a 20 000-photo card meant 20 000 background file opens in every mode. A photo whose size read was interrupted by a restart of the pass is now measured again instead of staying at 0 × 0.
- Landscape-first order no longer counts a portrait photo that the camera stored on its side (rotated by its EXIF orientation) as landscape.
- A damaged settings file no longer stops Jackpix from starting; it starts with the default settings instead.
- System Back (and the back gesture) in the Selection viewer now returns to the feed on the photo you came from, instead of leaving the app.
- Closing the Selection viewer, pressing Back or switching folders while it is still reading a slow (cloud or SD) folder no longer reopens it a moment later with the old folder's photos; Back cancels the open instead of leaving the app, and tapping again starts over.
- System Back with the controls guide or the first-run tutorial open closes the guide and keeps you on your photo; before, it closed the app.
- In landscape with 3-button navigation, the top bar, the Selection viewer's bar, the start screen and the photo badges no longer run under the navigation bar or the camera cutout (the Settings gear sat mostly under the Recents button). The Selection viewer's Back, Copy and Share buttons are full 48 dp targets.
- The Settings sheet's drag handle is a full 48 dp target with a name for TalkBack ("Drag handle").
- The landscape photo counter shows "n / m+" while the folder is still loading, as portrait does.
- A Settings message such as "Camera info on" no longer turns up the next time you open Settings when you closed it right away.
- Snackbars no longer cover the top of the viewer's bottom-bar buttons with 3-button navigation; they sit above the bar whatever its height.
- A tip card ("Double-tap to reset", "Tap again to show overlays", "RAW only") no longer stays on screen after you leave the photos: Change folder, opening another folder or unplugging the card closes it, so it no longer covers the start screen's Pro mode switch and recent folders.
- A sideways drag across a Settings row (such as "Reset all seen", which asks nothing) no longer acts as a tap.
- When the format filter hides every photo of a folder, the start screen says "12 hidden by filter" with the filter and **Show all**, on every visit, instead of claiming the folder has no photos.
- Camera info shows the file name as it is on disk (`dsc_0001.arw`, not `DSC_0001.ARW`), and in landscape a long name loses its middle, never its extension.
- At large font sizes the start screen's cards no longer scroll under the top bar's buttons.
- At large font sizes the info panel shows the whole file name instead of "IMG_202…00.JPG", and the portrait camera info wraps a long lens name onto a second line instead of cutting it off.
- The snackbar's **Undo** is a full 48 dp button at large font sizes.
- TalkBack announces the start screen's **Re-select**, **Resume** and recent-folder rows as buttons, and Re-select is a full 48 dp target (it was 44 dp). A disconnected recent folder keeps its "Not connected" line fully readable; only its icon, name and badge fade.
- TalkBack: each settings switch and option is one stop that says its state ("Milestones, on, switch"), not a row and a separate switch, and the bar's Undo button says "disabled" while there is nothing to undo.

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
