# Changelog — Frameduel

Notable changes to Frameduel (Android Desktop), released under the tags `android-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Added

- Before a copy, or a move to another card or drive, Frameduel checks that the destination has room and, if not, refuses with "Not enough space: needs 48 MB, 12 MB free. Nothing was copied." instead of failing halfway. Destinations that do not report their free space are not checked.
- Settings lists the open-source licences of what Frameduel bundles (**Open-source licences**, below the Impressum), offline, including OpenCV and the libraries built into it. The licence text of each library built into OpenCV is reproduced there (libpng, libjpeg-turbo, libtiff, libwebp, OpenJPEG, Protocol Buffers, KleidiCV, Carotene, Intel IPP, MLAS, Intel ITT, cpufeatures, the Rubik and WenQuanYi fonts, oneTBB, FlatBuffers, libc++), as those licences require. Before, the app passed on no licence text for them.
- A screen reader announces the result of a move, copy or delete, and UNDO is a button for it.
- An open folder with no supported photos says so ("<folder> holds no supported photos.") instead of asking for a folder; while a folder is still opening a progress ring shows.
- Statistics and Duplicates open the folder you have open in Cull instead of asking for one again.
- **Undo** is the first row of the More menu, with the number of steps left: touch and mouse undo all of the last 20 moves and deletes, as `Ctrl+Z` does, after the card has gone. The delete dialog no longer names a key ("Undo works until you leave the folder, then it is permanent.").
- A 100 % check without touching the screen: `Space` toggles fit and 100 % in fullscreen and on an enlarged frame (`1` / `2` / `3`), the mouse wheel zooms at the pointer in fullscreen, and a drag or `Shift` + arrows pans while zoomed. `←` / `→` keep the zoom and the pan, and `Esc` leaves the zoom first. A pill shows the zoom ("100 %").
- When you have moved or deleted every photo of a folder, Cull says **Done** with how many were moved, copied and deleted and where the kept ones went, instead of "No photos found". **Open Selection** there opens the Selection folder for a second pass. Culling a memory card into a Selection on the tablet, it says **Safe to format** once every kept file is there with its size, or how many are missing. Next on the last photo says "Last photo".
- A folder reopens at the photo you left it on — after a restart too, when Android had closed the app — instead of at photo 1. If that photo is gone it starts at photo 1.
- Below 600 dp (a narrow split-screen pane or DeX window) a More button at the end of the action row opens the same menu as the sidebar: Undo, Open folder, Scan images, the sort order, Go to photo and Clear scores.

### Changed

- A move or copy confirms with the photo and where it went, "Moved IMG_0001 · Selection", and counts the files that went with it: "Moved IMG_0001 +2 · Selection" (a screen reader hears "and 2 companion files"). The fullscreen flash says the same. The delete dialog of a photo with companions reads "Delete IMG_0001 +2?" and names their kinds ("Also deletes JPG, XMP").
- The sidebar, the side rail and the bottom bar hold Cull, Stats, Tools and Setup. **Tools** opens a menu with Learn and Dupes (arrows, Enter and `Esc` work in it); `L` still opens Learn. Learn is labelled **Beta** until its Compare tab shows both frames and its progress is kept.
- Each screen has one name everywhere — Cull, Learn, Stats, Dupes, Setup — on the sidebar, the side rail and the bottom bar and for a screen reader (the rail said Selector, Statistics, Duplicates and Settings, and Learn said "Return to Culling"; it now says "Back to Cull"). The burst feature is **Bursts** in Setup too. The guide's More and Setup lines name what is there.
- Copy moves on to the next frame, as Move and Delete do, and a photo you copied carries a small copy mark on its frame and its filmstrip tile for the rest of the session. Copying it again writes nothing and says "Already copied" (before, it made a second file, `-2`).
- Deleting duplicates no longer removes the files at once: the copies leave the list, "Deleted N files" offers UNDO, and the files go when you leave Duplicates, pick another folder or scan again. A copy that is no longer the file you deleted (another photo now has its name) is kept and named.
- The scan sheet's measurement checkboxes now decide what is computed: an unticked one is skipped and shows no value, and a later scan computes only what a photo still lacks. Before, every scan ran all four. The Setup description no longer promises a measurement choice.
- After a delete the card says "Deleted", or "Deleted 3 files" when the photo took its RAW or sidecar with it; the photo count beside the frames falls by one per photo. The card's UNDO is a filled button.
- The four view toggles under the action buttons carry a one-word label (Info, Strip, Overlay, Keys) where it fits, and show whether they are on by a filled or outlined button; a screen reader hears each as a switch that is on or off.
- Learn and the scan sheet speak plainly: "Fast enough to hand-hold: 1/60s beats the 1/50s minimum" instead of "1/f_eff Guideline", "Golden ratio" and "Flip spiral" instead of "Phi" and "Flip (TL)", a "Grainy at ISO 3200" remark when noise is high, and scan options named for what they find ("Focus quality", "Noise level") without the method.
- Blurriest first, greyed out before a scan, says "Scan first".
- Keys match Lensjet and Blitzlens: `T` shows or hides the filmstrip, `I` shows or hides the current frame's values (it used to open Learn; `L` still does, and in Learn `L` or `Esc` returns), and `J` / `K` step to the next / previous photo. The guide lists them, and calls `1` / `2` / `3` "enlarge left / middle / right frame". Learn's guide buttons (Thirds, Golden ratio, …) tell a screen reader whether they are on.
- Hint text (key names under the buttons, "No image", "Not scanned", the filmstrip range, the Learn captions) and the Delete buttons are readable on the dark surfaces (contrast 4.5:1 or better); before, some were at 2:1 to 3.8:1.
- Every control shows the hand cursor under a mouse or trackpad, in Settings, Duplicates and the dialogs too. Error messages are short sentences; the technical detail goes to the log.
- Image analysis now works on a copy of at most 2048 pixels on the long edge for every photo, which uses less memory and makes sharpness comparable across cameras. Scores from earlier versions are recalculated the next time you scan; ratings are kept.
- The check behind "safe to format" now requires every kept file, RAW and sidecar included, at the destination with its exact name and size.

### Fixed

- A sideways drag that ends on a Setup row (Reset Guidance, a switch, a choice) no longer counts as a tap and changes nothing; a tap still acts.

- A star filter picked in Learn no longer limits Cull, which shows no filter: Cull's arrows, filmstrip, burst marks and Go to photo always cover every photo of the folder, and Learn keeps its filter.
- Reopening the last folder while the app is rebuilt in a running process (a window resize on DeX, a rotation) can no longer crash at start: the photo-data cache it clears existed only after the reopen began.
- Leaving a folder no longer deletes a file that another photo replaced under the same name since you deleted it (a refilled card); it is kept and named, as after a restart.
- At the largest system font the action buttons keep "Delete" and the other words whole: a button that is too small drops its icon first and its key name last (the key stays in the spoken label and in the guide). Learn's tabs and guide buttons scroll instead of breaking words ("Lesson/s", "Hori/zon"), and the Photo Coach's blur badge and the rating card's key caption move to the next line instead of breaking.
- The first-run "Tap either side to browse" pill no longer sits under the undo card; it moves above it.
- Moving or copying in fullscreen into a custom destination flashes that folder's name, as the card does, instead of "Selection".
- On a second pass (culling inside a Selection folder) the confirmation, the filing hint and the fullscreen flash name the folder the photo goes to ("Moved to Final") instead of "Selection".
- The layout guide no longer claims that every control shows its key; the narrow Go and Sort chips do not.
- Under a mouse pointer, the key names and the red Delete word on the buttons stay readable (4.5:1); the hover colour put them at 3.5:1 and 3.3:1.
- A screen reader announces that a scan or grouping is queued behind the other.
- "Delete now" in the "uncompleted deletions" prompt after a restart deletes a photo only if the file is still the one you deleted (same size and date). A different photo that took its name in the meantime (restarted camera numbering, a refilled folder) is kept, and the message names it. So is an entry saved by an earlier version, which has no size and date to check.
- With a custom destination set and that destination's Selection folder open, Copy and Move file into `Final` inside it, as they do in any Selection folder, and Settings shows that path. Before, a Move renamed each photo in place (`IMG_0001-2.JPG`).
- Learn draws the leading lines and the visual weight of the photo shown, computed when you open it and kept for next time. Before, the analysis existed but nothing ran it.
- When another app creates a rating sidecar or a JPEG's backup copy under the same name at the same moment, Frameduel leaves that file alone and reports the rating as not written. Before, it could write over that file or delete it.
- A scan that is stopped no longer stores photos it did not finish as having no sharpness, noise or clipping score.
- Settings: each Grouping Level and Filing Action choice is one control with its name, so a screen reader reads "Time & Filename, radio button, not selected" instead of an unnamed radio button, and tapping anywhere on the row selects it.
- System Back closes the layout guide, a maximised frame or the context menu, one layer per press, like `Esc`; it used to close the whole app. `Esc` also closes the guide when it opened by itself at first launch.
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

## [0.7.0] - 2026-11-04

### Added

- Sort order: a folder now opens with the newest photo first. The new Sort button right of the current photo (or `S`, or the overflow menu) switches to oldest first, file name or blurriest first (after a scan), and your choice is kept.
- Go to photo: tap the position counter beside the Sort button, press `G`, or use the menu, then type a photo's number, a date (`12.10.2026`, `12.10.26`, `2026-10-12` or `12.10.`) or part of its file name, or tap the calendar button and pick a day. A date goes to the first photo of that day in the current order, or to the nearest day with photos. The dialog shows which file it will open and when it was taken.
- Settings → Where photos go: choose Working folder (the Selection folder inside the folder you are culling) or a Custom folder. Under the choice, Settings shows the full path your next Copy or Move will use, say when a custom folder has lost its permission or is missing, and mark a second pass. A move or copy into a custom folder now names it in the confirmation (`Moved to «Keepers › Selection»`).
- The lens is shown under the exposure on the current photo, on Previous and Next and in fullscreen, at every window width.

### Changed

- Settings: "File Sorting" is now "RAW and JPEG folders", and "Custom Selection Location" is replaced by the Working folder / Custom folder choice. Choosing Working folder forgets the custom folder.
- In windows too short for the full control block (1024 × 720 on the default DeX window), the buttons shrink to their compact form so the Sort and position buttons stay visible; in windows too narrow for them (720 × 560 and below) the position counter, `G`, `S` and the overflow menu remain.
- Fullscreen shows the photo on the whole screen: the status bar and taskbar are hidden, and the file name, exposure and lens sit in a small panel at the top left instead of a bar across the top. The panel comes back for 3 seconds on every key press and photo change; `I` (or its pin button) keeps it on screen.
- After a Move or Copy the message no longer counts down 30 seconds: a move shows for 4 seconds with Undo (Ctrl+Z keeps working afterwards), a copy for 3. Only Delete keeps its 30-second countdown. The message now also appears in fullscreen.
- The debug and lab builds use OpenCV's own 1.2 MB `libc++_shared.so` instead of four unstripped 7-9 MB copies kept in the repository (two of them for ABIs the app never ships). Release builds are unchanged.

### Fixed

- One frame, full size (`1`/`2`/`3`): the photo now takes the whole area, and its file name, exposure, lens and scores lie over it. The new ⓘ button on the frame hides them.
- Moving and copying are safer when a storage provider misbehaves: a photo whose size the provider does not report is never deleted after its copy (the move is reported as copied, with the original kept); copies that could not be removed after a failed bundle are reported ("Move interrupted - copies of ... stayed in Selection/ ... the original is untouched") instead of being left unnoticed; a bundle that is already in the folders it would be filed into is left as it is instead of being renamed to `-2`; leaving the screen while a move is under way no longer leaves a bundle half moved; an Undo that fails half-way can be tried again.
- Copies written to an SD card or USB drive are now flushed to the card, so pulling it right after a move cannot lose the copy.
- Fullscreen and the compact layout no longer jump to another photo when the list is re-ordered or an undo puts a photo back.
- Settings no longer says "Using custom folder" for a folder picked in a version before 0.6.0, which Copy and Move never used: photos keep going where they went, and Settings now says so.
- Arrow keys in fullscreen work from the first press, also after the controls hide and after a move. `Ctrl`+`C` there no longer files a copy.
- A star rating on a RAW or TIFF is written to its `.xmp` sidecar (an existing `IMG.CR3.xmp` or `IMG.xmp` is updated, otherwise `IMG.CR3.xmp` is created); the RAW itself is never changed. A JPEG gets the rating inside the file, checked and restored if the write fails. PNG, HEIC, WebP and other formats keep the rating in the app. A rating that could not be saved now says so.

## [0.6.0] - 2026-10-22

### Added

- Undo history: Ctrl+Z (or the snackbar's Undo) steps back through your last 20 deletions and moves, newest first. The first delete in a folder asks once; deletions are held back until you leave the folder or the undo window passes, and a deletion the app was interrupted in is offered again at the next launch.
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
