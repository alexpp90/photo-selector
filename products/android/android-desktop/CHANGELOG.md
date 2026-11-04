# Changelog — Frameduel

Notable changes to Frameduel (Android Desktop), released under the tags `android-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

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

### Changed

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
