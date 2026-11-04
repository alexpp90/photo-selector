# Changelog — Frameduel

Notable changes to Frameduel (Android Desktop), released under the tags `android-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Added

- Sort order: a folder now opens with the newest photo first. The overflow menu switches to oldest first, file name or blurriest first (after a scan), and your choice is kept.
- Go to photo: press `G`, tap the position counter, or use the menu, then type a photo's number or part of its file name. The dialog shows which file it will open.
- Ratings are saved with the photo: embedded in a JPEG, in an `.xmp` sidecar beside a RAW or TIFF. A format that can hold neither (PNG, HEIC, WebP) says so instead of creating files, and the RAW itself is never opened for writing.
- The lens is shown with the exposure on the current frame, on the neighbour frames and in fullscreen. In fullscreen the file name, position, exposure and lens sit in a small panel at the top-left instead of a bar across the photo; `I` (or the pin button) keeps it on screen.
- Fullscreen: the first arrow key pages as soon as the viewer opens, also after the panel has faded, and the panel returns for three seconds on every key press and every page. `Ctrl+Z` (`Cmd+Z`) undoes the last delete or move there, as it does on the selector.
- A screen reader announces the result of a move, copy or delete, and UNDO is a button for it.
- An open folder with no supported photos says so ("<folder> holds no supported photos.") instead of asking for a folder; while a folder is still opening a progress ring shows.
- Statistics and Duplicates open the folder you have open in Cull instead of asking for one again.

### Changed

- Confirmations are quieter: a move shows for about four seconds with Undo, a copy for about three, notices four, errors seven. None draws a countdown, because none has a deadline.
- Hint text (key names under the buttons, "No image", "Not scanned", the filmstrip range, the Learn captions) and the Delete buttons are readable on the dark surfaces (contrast 4.5:1 or better); before, some were at 2:1 to 3.8:1.
- Every control shows the hand cursor under a mouse or trackpad, in Settings, Duplicates and the dialogs too. Error messages are short sentences; the technical detail goes to the log.
- Image analysis now works on a copy of at most 2048 pixels on the long edge for every photo, which uses less memory and makes sharpness comparable across cameras. Scores from earlier versions are recalculated the next time you scan; ratings are kept.
- The documentation no longer claims a "safe to format" check for memory cards: Frameduel has none in any screen yet. The check behind it now requires every kept file, RAW and sidecar included, at the destination with its exact name and size.

### Fixed

- One frame, full size (`1`/`2`/`3`): the photo now takes the whole area, and its file name, exposure, lens and scores lie over it. The new ⓘ button on the frame hides them.
- The analysis cache keeps its limit of 10,000 photos after every scan, not only when Settings is opened, and Learn's stored facts are removed with the photo's scores.
- Detailed Similarity grouping compares the whole frame. It used to look at the top quarter only, so it grouped shots that differed below it.
- A deleted photo's star rating and reject flag are deleted with it, so a new photo with the same name in the same place (a reused card, restarted camera numbering) starts unrated.
- When only some files of a RAW+JPEG pair could be deleted, the photo comes back with the files that are left, and the message says how many were deleted.
- Leaving a folder right after a delete no longer brings up an "uncompleted deletions" prompt for files that were already deleted.
- The duplicate finder no longer groups files it could not read as duplicates of each other.
- Learn's crop suggestion is now built on the subject the scan detected in the photo. Before, every photo got the same suggestion from an assumed box in the centre; a photo with no detected subject (or not scanned yet) now shows none.
- Holding down Delete, Backspace, M or C acts once. Before, the key's auto-repeat deleted, moved or copied photo after photo.
- A delete no longer claims a 30-second deadline. The draining line under the delete card and the "for 30 seconds" hint are gone: a delete can be undone with Ctrl+Z until you leave the folder, which is when the files are actually removed.
- Rating a JPEG can no longer leave a cut-off photo. Before the photo is rewritten, a copy of the original (`<name>.frameduel-backup`) is saved beside it and deleted once the rating is written. If the app, the device or the card reader stops during the write, the photo is restored from that copy the next time the folder opens, and the app tells you.
- After an undo the photo is listed at the file it was restored to, so its rating shows and the next Move or Delete of it works. This affected renamed restores (`IMG-2.jpg`) and RAW+JPEG pairs on Drive, Downloads and camera storage.
- An undo that put the photo back but could not remove the file in Selection now shows the photo and says so, instead of hiding it again and leaving an undo that would create a second copy.
- An undo that fails after you opened another folder no longer changes the folder you are in; it stays undoable in its own folder.
- Writing a rating into an `.xmp` sidecar or a JPEG no longer changes a Windows rating (`MicrosoftPhoto:Rating`) or writes a second, undeclared `xmp:Rating` beside an older `xap:Rating`. XMP the rating cannot be merged into is left alone and the write fails, for JPEGs too, instead of being replaced.
- Delete on a photo shown as its `-Edit` (a shot with no JPEG) removes the edit and its own sidecars only. The RAW stays in the folder and the shot goes on showing it; Undo gives the whole shot back. Delete on the original still removes the shot with its edits.
- The Duplicates screen no longer lets you tick every copy of a photo (the last one left is kept and its card is disabled), deletes a copy together with its sidecars, and its "Original" badge reads "First", which is all it ever knew.
- Settings states how many analysis results are stored; it always said 0.
- The filmstrip's burst marker follows the photo on screen, also on the last photo of a burst; it used to mark the next burst.
- Undo on a move's confirmation can no longer reverse an earlier move when two moves finish out of order.
- Fullscreen and the compact layout no longer jump to another photo when the list is re-ordered or an undo puts a photo back.

## [0.6.0] - 2026-10-22

### Added

- Undo history: Ctrl+Z (or the snackbar's Undo) steps back through your last 20 deletions and moves, newest first. The first delete in a folder asks once; deletions are held back until you leave the folder, and a deletion the app was interrupted in is offered again at the next launch.
- Moving a shot also moves its companions: RAW, JPEG, `-Edit` and `-Enhanced-NR` versions and `.xmp` sidecars travel together and land in `RAW/` or `JPEG/` beside the image they belong to. A partial move (copied, but the original could not be removed) says so and can be undone.
- If a name in the Selection is already taken, the file is saved with a number and the message says which name it got; an undo that had to rename says so too.
- Learn workstation: study technique and composition of your own photos, with subject and technique facts.
- When access to a folder was lost, the app asks you to select it again instead of showing an empty list.

### Changed

- Undo returns files to the folder they came from, even for providers that do not use plain paths (Downloads, cloud drives), and never creates folders. If the folder is gone, they go to the opened folder and the app says so.
- A star rating stays with its photo when it is moved, copied or restored.
- The filmstrip thumbnails, the view toggles and the "Got it" buttons are now 48 dp touch targets.
- The control block measures the screen: it uses short one-line buttons and moves the position counter on small windows, and fits from 1280 x 800 down to 600 x 480. The sidebar scrolls, the guide pages its cards, and the first arrow key navigates photos.
- The app is now called Frameduel (formerly PhotoSelector Studio), in the launcher, Settings and the store listing.
- New app icon, including a themed icon for Android 13+ home screens.
- GitHub releases are now titled "Frameduel X.Y.Z". If you filter release titles in Obtainium, update the filter to `^Frameduel `.
- New colours: a lavender accent replaces the cyan one, and the dark surfaces around your photos are now neutral grey with no colour cast. Keeper amber, score colours and error colours are unchanged.
- Release builds include native symbol tables, so crashes in image analysis can be diagnosed.

### Fixed

- With file sorting on, a shot's sidecars and derivatives always go into the same folder as its RAW (`RAW/`), or beside its image when it has no RAW. `-Enhanced-NR` files (TIFF or JPEG) used to be filed by their own type, apart from their shot.
- On the Learn screen every control is now at least 48 dp to touch: the back button, guide toggles, Flip, tabs, crop ratios, quiz answers, links, Set Goal, the stars and the rating filter. The side panel is a little wider (400 dp).
- A failed undo (a move that could not be put back, a file that could not leave the trash) stays on the undo stack, so you can try again instead of losing it.
- The app no longer requests any Android permission. A library that was never used had added wake-lock, network-state, boot-completed and foreground-service permissions to the installed app.
- Switching folders while a move or delete was still running no longer changes the new folder's list or messages; the undo stays with the folder it belongs to.
- Two moves into the same Selection folder at once can no longer pick the same name or create a second `Selection (1)` folder.
- The Privacy Policy, Legal Notice and Source Code links in Settings open real pages again; they pointed at addresses that did not exist.
- The guide and score readouts no longer list an AI aesthetic score; the app has measured technical quality only since on-device AI scoring was removed.
