# Changelog — Blitzlens

Notable changes to Blitzlens, released under the tags `linux-desktop-vX.Y.Z`.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this product adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). History before per-product releases: the root `CHANGELOG.md`.

## [Unreleased]

### Added
- Preferences > Culling Destination: choose **Working folder** (Selection inside the folder you cull, as before) or a **Custom folder**, and see the full path your photos will go to, updated as you open another folder. A custom folder that is gone or not writable is shown as a warning.
- **Lowest sharpness first** is in the main menu (View order) and on the **B** key, next to the natural order; the menu shows which one is active.

- **Newest first** is the default view order for new installs, with **Oldest first**, **File name** and **Lowest sharpness first** in the main menu (View order). The choice is remembered; an install that already has settings keeps the file-name order it had. While the folder is still being scanned, the photo on screen stays on screen, and an untouched session shows the newest photo so far. A sort icon beside "Photo N of M" shows any order other than the default.
- **Go to photo** (`G`): type a photo's position ("Photo N of M") or part of its file name; the dialog names the target as you type, Enter goes, Escape cancels.
- Preferences > Scoring has **Stop at sharpness** (0 = off): with lowest sharpness first, the sweep stops at the first photo at or above that score.
- Escape and Ctrl+W close Library Statistics, Duplicates, Keyboard Shortcuts and Preferences, and the focus returns to the main window. Their scroll areas show a scrollbar, and they are never taller than 90 % of the main window.
- A screen reader announces queued, undone and finished operations and finished folder loads, and the Undo button's spoken name says which action it would undo and how many are available.

### Changed
- Move and Copy in an opened **Selection folder** are refused with a reason ("This is a Selection folder. Open its parent folder to keep photos.") instead of filing into a second `Selection/Selection/` the scan hides. Trash and Undo work as before.
- Keys follow the shared desk-app map (`docs/shared/KEYBOARD.md`): `F` and `F11` toggle full screen (`Escape` leaves zoom first, then full screen), `S` opens the View order menu. `H` and `L` (navigation) and `B` (sharpness sweep) still work for this release and are marked in the `?` sheet as going away; use `K`/`J` and the View order menu.
- `B` returns from lowest sharpness first to the order the folder opens in (newest first by default), not always to file-name order.
- The unused filmstrip and sync-lock handlers are gone. `Tab` stays keyboard focus traversal.
- The Duplicate Finder window is titled "Duplicates".
- Settings written by a newer version keep their extra keys when this version saves.
- Move and Copy no longer recreate a custom destination folder that has disappeared (for example an unplugged drive); they report the problem and leave the photo where it is. An existing custom destination is read as the Custom folder choice.
- The empty start screen lost its tagline, and its key hints are generated from the same key table the app uses.
- The action buttons read **Keep**, **Copy**, **Trash** and **Undo**; the keys moved into the tooltips and the `?` shortcut sheet. The tooltip of **Undo** names the action it would undo.
- The view switcher (1-Up, 2-Up, 3-Up) is a toggle group, so a screen reader announces which view is pressed.
- Counters, badges, captions and the info card follow the system text size; before, they stayed the same size at 150 % text.

### Fixed
- Holding `M`, `C`, `Delete` or `Backspace` acts once; before, each auto-repeat moved, copied or trashed the next photo too.
- Preferences titles that contain `<` or `&` ("Blurry Threshold (< 35.0)", "Memory & Prefetch") are shown as written; before, GTK parsed them as markup and could show them empty.
- A custom `XDG_DATA_HOME` (Nix, containers) no longer switches Trash from the desktop's Trash (GIO) to the app's own implementation.
- The lab's artifact folder in `/tmp` is refused when it is a symlink or belongs to another user.
- Errors in observers and callbacks that were silently dropped are logged.
- After a Move, Copy or Trash the action bar says what happened: the photo's name (shortened in the middle when long), where it went (Selection or Trash) and how many companion files went along, for example `Moved IMG_…_064000 + 2 to Selection/`. The line no longer shifts the centred buttons.
- The action bar follows the window width without a permanent frame callback, so an idle window no longer redraws every frame.
- The `.deb` is now really reproducible: the package on the GitHub release and the one in the APT repository are the same file (same checksum). Before, two builds of the same version differed in the build time stored in the archive, so the repository and the release carried different bytes.
- Opening another folder cancels the photos still queued for decoding in the folder you left, and a photo that was already decoding no longer fills the new folder's cache.
- When a Move, Copy or Trash leaves files in an unexpected place ("Move interrupted — … stayed in Selection/", "original could not be removed"), the message stays on screen until the next file operation. Before, it was a 3-second toast and a 4-second line in the action bar.
- Tab moves the keyboard focus through the header and the action bar again, and Space presses a focused button, instead of both being swallowed by the photo view.
- Duplicate Finder, Library Statistics and the shortcut list have a header bar with a close button and close with Escape.
- Closing the folder picker without choosing no longer shows "Open folder cancelled: Dismissed by user". A folder that cannot be opened says "Could not open <folder>" and why.
- A Google Drive (or other cloud mount) connected while Blitzlens is already running now shows real file names; before, its files stayed unreadable until the app was restarted.
- Settings are saved atomically, so a crash while saving can no longer reset them to the defaults. A settings file that cannot be read is logged and a copy is kept as `settings.json.corrupt`.
- A copy to the Selection folder is written under a hidden temporary name and takes its real name only when complete, so a crash or power loss can no longer leave a truncated photo there. A Trash on another drive whose original cannot be removed no longer leaves a stray file in the Trash.
- "Open with Blitzlens" on a photo (or several) opens the photo's folder with that photo selected. Before, one photo showed an empty "Loaded 0 photographs" and several photos failed with a usage error.
- A start-up error is no longer reported as "Core Engine Ready" with exit status 0. It stops with the error and a non-zero status.
- **Lowest sharpness first** now orders the whole folder. Before, only the few photos you had already looked at were sorted and the rest stayed in file order behind them. Choosing the order scores every photo in the background (on a cloud folder: choose it a second time to confirm), keeps the photo on screen where it is while the order settles, and starts at the lowest-scored photo if you have not moved on.
- Duplicate Finder and Library Statistics work on a cloud or remote folder. The first click now offers **Scan anyway**; before, the confirmation was asked for but nothing could give it (Statistics even opened as an empty window). Hashing for duplicates no longer freezes the window.
- Moving a symlinked photo to the Trash trashes the link, not the file it points at; Undo puts the link back. Before, the original in the other folder was trashed and a dangling link stayed.
- Closing the window while file operations are queued no longer drops them. Press M four times and quit, and all four photos are moved first; the window says "Finishing N file operations…" and closes when the last one is done. Closing a second time quits at once.
- A darktable-style sidecar (`DSC_0001.NEF.xmp`, `IMG_1.JPG.xmp`) now travels with its shot on Move, Copy and Trash whichever file of the shot it belongs to. Before, it stayed behind when a JPG sorted before the RAW (NEF, RAF, RW2, ORF, PEF and others).
- Opening a big folder no longer freezes the window. The scan (including the folder-name look-up on a cloud mount) runs in the background and photos appear as they are found. A folder of 2000 RAW+JPEG pairs took 46 seconds to list, and the window did not respond meanwhile; it now takes about 0.3 seconds.

## [0.2.0] - 2026-10-22

### Changed
- The app is now called **Blitzlens** (formerly Photo Selector Linux): launcher, window title, About dialog, package description and the APT repository pages.
- New application id `app.alpatix.Blitzlens`. After the upgrade the app appears as a new launcher entry and the old launcher entry is removed. Pin or favourite it again if you had done so. Your settings in `~/.config/photo-selector-linux/` are kept.
- Selection and focus colours (active slot border, Copy button, sharpness meter) follow the system accent colour. Large surfaces are neutral grey. The keep, acceptable and cull colours are unchanged. The app stays dark.
- Package and AppStream metadata point at the current project page and issue tracker, and describe the app in plain words.

### Added
- New icon, with a symbolic variant for the shell and dedicated 16 and 24 pixel sizes.
- An About dialog in the main menu.

### Fixed
- White text on the emerald **Move** button and the "sharp" score badge was hard to read (2.3:1); it is now dark on the same green (8:1).
- If Undo of a Move could not put every file back, the error now names the files that stayed; before, they were dropped silently.
- A Trash undo that failed half-way can be tried again: the files already restored are recognised instead of blocking the retry.
- Exposure, ISO, aperture and lens were empty on systems with a newer Pillow (the MakerNote tag was renamed).
- The Debian package is built reproducibly: file dates follow the top `debian/changelog` entry (they said 2025) and the checksum list has a fixed order.
- A Move to a folder on another drive whose original cannot be removed keeps the verified copy, says "Copied to … — original could not be removed" and stays undoable; renames on the same drive no longer need free space.
- A photo moved to the Trash is always recorded (or put back) before an error is shown, and trash folders on memory cards follow the FreeDesktop rules; Undo restores only from the recorded Trash location.
- Undo of a Move that left its original behind deletes the copy it made instead of creating a `-2` duplicate.
- Edited `-Enhanced-NR` versions travel with their shot on Move, Copy and Trash.
- When a name was taken, the message says so: "Moved to Selection/ as IMG_0001-2.JPG"; an undo that had to rename says "Restored as …".
- A Move, Copy or Trash that finishes after you switched folders keeps its undo record.
- Move, Copy and Trash run in the background, so a slow card or network folder no longer freezes the window during a transfer; the outcome shows beside the action buttons without moving them; 3-Up layout and HUD fixes from the device lab.
- When a failed Move or Copy cannot undo its own steps, the files left in the Selection folder are reported and can be undone, instead of the app saying everything was rolled back.
- The package installs one launcher entry and one icon instead of two of each.
- The APT setup script stops with an error instead of hiding a failed download.
- The launcher opens every supported photo format, including more RAW formats, WebP and HEIF.
