# Changelog — Frameduel

Notable changes to Frameduel (Android Desktop), released under the tags `android-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Fixed
- On the Learn screen every control is now at least 48 dp to touch: the back button, guide toggles, Flip, tabs, crop ratios, quiz answers, links, Set Goal, the stars and the rating filter. The side panel is a little wider (400 dp).
- A failed undo (a move that could not be put back, a file that could not leave the trash) stays on the undo stack, so you can try again instead of losing it.
- The app no longer requests any Android permission. A library that was never used had added wake-lock, network-state, boot-completed and foreground-service permissions to the installed app.

## [0.6.0] - 2026-10-20

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
- Switching folders while a move or delete was still running no longer changes the new folder's list or messages; the undo stays with the folder it belongs to.
- Two moves into the same Selection folder at once can no longer pick the same name or create a second `Selection (1)` folder.
- The Privacy Policy, Legal Notice and Source Code links in Settings open real pages again; they pointed at addresses that did not exist.
- The guide and score readouts no longer list an AI aesthetic score; the app has measured technical quality only since on-device AI scoring was removed.
