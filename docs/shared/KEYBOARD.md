# Shared — Keyboard map of the desk apps

One key, one meaning, in every desk app that has the feature: a photographer who moves between
Frameduel (Android Desktop: DeX, keyboard docks, Chromebooks), Lensjet (macOS) and Blitzlens
(Linux) keeps their muscle memory. Jackpix is touch-first and has no key map; the archived legacy
Desktop is frozen and out of scope.

This file has two parts:

1. **Today** — what each app's key handler does, read from the code (not from requirements,
   sheets or READMEs), with `file:line` evidence.
2. **Proposal** — one aligned table. It is a proposal until `@shared-ux-agent` has reviewed it
   and the maintainer has accepted it; each app then aligns in its own change, together with its
   shortcut sheet, README, requirements and key-map test.

Read against `origin/main` `f77b060a` (2026-11-12): all three columns and every line reference.
Paths below are relative to:

- Frameduel: `products/android/android-desktop/src/com/photoselectortoolbox/`
- Lensjet: `products/macos-desktop/src/`
- Blitzlens: `products/linux-desktop/src/photo_selector_linux/`

## 1. Today

Main culling view, no modifier unless the key says so. `—` means the key is not bound.
⌘ is Lensjet's modifier; Frameduel accepts Ctrl or Meta.

| Key | Frameduel (selector) | Lensjet (workspace) | Blitzlens (workspace) | Same meaning? |
|---|---|---|---|---|
| `←` / `→` | previous / next (`ui/selector/SelectorScreen.kt:877-878`) | previous / next; `↑` / `↓` too (`PhotoSelectorKit/Navigation/KeyboardShortcutRouter.swift:175-202`) | previous / next (`ui/keyboard_router.py:179-184`) | ✅ |
| `K` | previous (`SelectorScreen.kt:877`) | previous (`KeyboardShortcutRouter.swift:244`) | previous (`keyboard_router.py:179`) | ✅ |
| `J` | next (`SelectorScreen.kt:878`) | next (`:241`) | next (`:182`) | ✅ |
| `H` | — | previous, going away after one release (`:244`) | previous, going away after one release (`:179`) | ◐ Frameduel unbound |
| `L` | **opens Learn** (`SelectorScreen.kt:871-874`) | **next photo**, going away after one release (`:241`) | **next photo**, going away after one release (`:182`) | ❌ |
| `M` | move to Selection; also in fullscreen (`SelectorScreen.kt:879`, `ui/selector/FullscreenViewer.kt:500`) | move to Selection (`:235`, menu `PhotoSelectorApp/AppCommands.swift`) | move to Selection (`:168`) | ✅ |
| `C` | copy to Selection (`SelectorScreen.kt:880`) | copy to Selection (`:238`) | copy to Selection (`:171`) | ✅ |
| `Delete` / `Backspace` | delete, with confirmation (`SelectorScreen.kt:881`) | move to Trash (`:205-208`) | move to Trash (`:174`) | ✅ same intent |
| Ctrl/⌘ `Z` | undo (`SelectorScreen.kt:843-849`; fullscreen `FullscreenViewer.kt:473`) | undo (`:148`); Edit menu item "Undo Move of IMG_0001.CR3", greyed out when there is nothing to undo (`AppCommands.swift`); a text field keeps its own undo | undo (`:141`) | ✅ |
| `1` `2` `3` | **maximise the previous / current / next frame** (`SelectorScreen.kt:885-887`) | **1-Up / 2-Up / 3-Up layout** (`:220-228`) | **1-Up / 2-Up / 3-Up layout**, keypad too (`:187-195`) | ❌ different model |
| `Space` | fit ↔ 100 % on a maximised frame and in fullscreen; nothing in three-up (`SelectorScreen.kt:860-867`, `FullscreenViewer.kt:498`) | toggle 100 % zoom (`:212`) | toggle 100 % zoom; presses a focused button instead (`:198-202`) | ✅ where a single photo is shown |
| `Shift` + arrows | while zoomed, pan 20 % of the view (maximised: `←` / `→`, `SelectorScreen.kt:853-857`; fullscreen: all four, `FullscreenViewer.kt:483-493`); unzoomed they step like the arrows | — | — | Frameduel only |
| `Esc` | close the top layer: sheet, context menu, zoom, fullscreen, maximised frame (`SelectorScreen.kt:833-839`; in fullscreen the zoom before the viewer, `FullscreenViewer.kt:497`) | closes one layer, in order: the sharpness banner or the end card, then zoom back to Fit; consumed only when one is open (`:217-219`) | leaves 1:1 zoom first, then full screen (`:203`, `on_reset_zoom` `:323-333`) | ✅ "back out one layer" |
| `Tab` | — (focus traversal) | **Switch Photo, 2-Up only**: steps through undecided photos; in 1-Up and 3-Up the key is not consumed and the View-menu item is disabled (`:215-216`, `AppCommands.swift`) | not consumed: focus traversal (`dispatch` has no `Tab` branch) | ◐ |
| `F` | **open the fullscreen viewer** (`SelectorScreen.kt:882`) | **toggle the filmstrip**, as an alias of `T` until the next release, when `F` becomes full screen (`:247-251`, menu `AppCommands.swift`) | **window full screen**, `F11` too (`:209-211`) | ◐ Lensjet differs |
| `I` | **toggle the info (readout) block** (`SelectorScreen.kt:875`); in fullscreen pins the info panel (`FullscreenViewer.kt:502`) | toggle Info (menu "Show Info" / "Hide Info", `:252-256`) | toggle the info HUD (`:206`) | ✅ |
| `T` | toggle the filmstrip (`SelectorScreen.kt:876`) | toggle the filmstrip (`:247`) | — (no filmstrip) | ✅ where there is a filmstrip |
| `S` | **opens the Sort menu**, never cycles (`SelectorScreen.kt:899-906`) | **toggle linked pan** (`:257-259`); the menu item is Link Pan, `⌥⌘P` (`AppCommands.swift`) | **opens the View order menu** (Newest, Oldest, File Name, Lowest Sharpness) (`:212`) | ◐ Lensjet differs |
| `B` | — | — | toggles the view order lowest sharpness first / natural, going away after one release (`:221`) | Blitzlens only |
| `G` | Go to photo (`SelectorScreen.kt:889-896`) | Go to Photo… (`:260`, Go menu `AppCommands.swift`) | Go to Photo (`:215`) | ✅ |
| `?` | the guide; bound as `Key.Slash`, so `/` too (`SelectorScreen.kt:888`) | shortcuts sheet; `/` too (`:263`); ⌘`/` in the Help menu (`AppCommands.swift`) | shortcuts sheet (`:218`); Ctrl+`?` (`:156`) | ✅ |
| Ctrl/⌘ `O` | — | open folder (`:151`); File > Open Recent lists the last ten, and the last folder reopens at launch at the photo that was on screen | open folder (`:144`) | ◐ Frameduel unbound |
| Ctrl/⌘ `D` | — | Find Duplicates (`:154`, Tools menu) | Duplicate Finder (`:147`) | ◐ Frameduel unbound |
| Ctrl/⌘ `L` | — | Statistics (`:157`, Tools menu) | Library Statistics (`:150`) | ◐ Frameduel unbound |
| Ctrl/⌘ `,` | — | Settings (the SwiftUI `Settings` scene, `PhotoSelectorApp/PhotoSelectorApp.swift:19`) | Preferences (`:153`) | ◐ Frameduel unbound |
| ⌘ `[` / ⌘ `]` | — | previous / next (`:163-168`) | — | Lensjet only (macOS Go-menu idiom) |
| ⇧⌘ `A` | — | toggle Auto-Advance (`AppCommands.swift:85-89`; the same stored setting as Settings) | — | Lensjet only |
| ⌥⌘ `P` | — | toggle linked pan (menu `AppCommands.swift:137`) | — | Lensjet only |
| ⇧⌘ `R` / ⌘ `Y` | — | Show in Finder / Quick Look for the photo on screen (File menu, `AppCommands.swift`) | — | Lensjet only |
| Ctrl/⌘ `Q` | — (Android) | quit (system) | quit (`ui/application.py:89`) | platform |

Frameduel binds no Ctrl/Meta chord but `Z` (`SelectorScreen.kt:843-849`: any other chord is not
consumed).

Other screens of Frameduel, scoped to that screen:

- **Learn** (`handleInspectKey`, `ui/inspect/InspectScreen.kt:173-221`): `L` and `Esc` back to the
  selector (`I` no longer does); `←` / `→`; `0`–`5` set the rating (0 clears, keypad too); `X` rejects.
- **Fullscreen viewer** (`ui/selector/FullscreenViewer.kt`): `Esc` (the zoom first, then the viewer),
  `Delete`/`Backspace`, `M`, `C`, `I` (pin info panel), `←` / `→` (keep the zoom and the pan),
  `Space` (fit ↔ 100 %), `Shift` + arrows (pan while zoomed), Ctrl/Meta `Z`; the mouse wheel zooms
  at the pointer, 1.25× a notch, and a drag pans while zoomed.

Behaviour that is not a key but belongs to the map:

| Rule | Frameduel | Lensjet | Blitzlens |
|---|---|---|---|
| A held `Delete`, `M` or `C` acts once, not once per auto-repeat | ✅ `ONE_SHOT_KEYS` (`SelectorScreen.kt:797-806`; fullscreen `FullscreenViewer.kt:480-483`) | ✅ `isARepeat` consumed without acting (`KeyboardShortcutRouter.swift:205-208`, `:235-240`) | ✅ `ONE_SHOT_KEYS` with `is_repeat` (`keyboard_router.py:98-99`, `:166-167`); GTK 4 has no repeat flag, so a press of a key already held counts as a repeat (`_held`, `:235-271`) |
| Letters ignore Caps Lock and Num Lock | ✅ Compose `Key` | ✅ `charactersIgnoringModifiers?.lowercased()` (`:143`) | ✅ `normalize_keyval`, `clean_modifiers` (`:134-135`) |
| Keys yield to a focused text field | ✅ dialogs own their keys (`ui/selector/GoToPhotoDialog.kt:161-176`) | ✅ `isTextInputActive` (`:132-134`) | ✅ `is_text_editing` (`:131-132`) |

### What the sheets say against the code

- Lensjet's help sheet now lists `← ↑ / K`, `→ ↓ / J` and the going-away `H` / `L`; every row's name and key is read from the Kit's `LensjetCommand` table (`PhotoSelectorKit/Navigation/LensjetNames.swift`), which a test compares with `AppCommands.swift`.
- Frameduel's guide lists every selector key from one table, `SelectorShortcut`
  (`domain/interaction/SelectorGestures.kt:221-240`: `J / K`, `I`, `T`, `L`, `Ctrl+Z` among them);
  a test asserts that the guide and `handleSelectorKey` agree.
- Blitzlens' sheet (`ui/shortcuts_dialog.py:26-48`) lists `G`, `S` (View Order Menu), `F / F11`
  (Full Screen) and `B` with its one-release note — the same as the handler.
- The open backlog briefs for Go to photo in Lensjet and Blitzlens suggest ⌘`L` / Ctrl+`L`.
  Both apps already use that chord for Library Statistics; Go to photo takes `G` (below).

## 2. Proposal

### Principles

1. **A letter means one thing in every desk app.** An app without the feature leaves the key
   unbound: never a different meaning, never a silent no-op. A key that only one app binds is
   reserved for that meaning in the others.
2. **Conventions of photo tools break ties**: `F` full screen, `G` go to, `I` info, `0`–`5`
   rating, `X` reject, `Space` zoom, `?` help.
3. **The platform owns the modifier**: ⌘ on Lensjet, Ctrl on Blitzlens and Frameduel (which
   also accepts Meta, as DeX and ChromeOS keyboards send it). Platform idioms that only one
   system has (⌘`[`/⌘`]`, Ctrl+`Q`) stay platform-specific.
4. **A sort key opens the choice, it never cycles**, so a stray press cannot reorder a folder
   mid-cull (Frameduel's rule today).
5. **A destructive key acts once per press**: held `Delete`, `M` or `C` repeat nothing.
6. **Moving a key keeps the old one for one release where it does not collide**: the old key
   keeps its old meaning for one release while the new key is shown in the sheet; it changes
   meaning only in the release after.

### The aligned table

| Key | Meaning | Frameduel | Lensjet | Blitzlens |
|---|---|---|---|---|
| `←` / `→` | previous / next photo | keep | keep (`↑`/`↓` keep as Lensjet extras) | keep |
| `J` / `K` | next / previous photo | done | keep | keep |
| `H` | — (retired) | — | **retire** after one release | **retire** after one release |
| `L` | open Learn (Frameduel only; reserved elsewhere) | keep | **retire** "next" after one release | **retire** "next" after one release |
| `M` / `C` | move / copy to Selection | keep | keep | keep |
| `Delete` / `Backspace` | delete (Trash on desktop) | keep | keep | keep |
| held `Delete`, `M`, `C` | act once per press (principle 5) | keep (`ONE_SHOT_KEYS`) | done (`isARepeat`) | done (`_held`) |
| Ctrl/⌘ `Z` | undo | keep | keep | keep |
| `1` `2` `3` | view number (see recorded reason) | keep: enlarge the left / middle / right frame | keep: 1-Up / 2-Up / 3-Up | keep: 1-Up / 2-Up / 3-Up |
| `0`–`5`, `X` | rating, reject (where an app rates) | keep, Learn only | reserved | reserved |
| `Space` | toggle 100 % zoom | done 2026-11-12: maximised frame and fullscreen | keep | keep |
| `Esc` | back out one layer | keep | keep | keep |
| `Tab` | Lensjet: cycle the comparison slot; elsewhere focus traversal | focus traversal | keep | focus traversal (never captured) |
| `F` | **full screen** | keep (fullscreen viewer) | **change in the release after next**: this release adds `T` and `F` stays the filmstrip (sheet: "Filmstrip T (F until next release)"); then `F` toggles window full screen (as ⌃⌘F) | done: toggles window full screen, `F11` too; `Esc` leaves full screen after it has left zoom |
| `T` | **toggle the filmstrip** | done | done; `F` keeps the filmstrip for one release | reserved (no filmstrip) |
| `I` | **toggle the info overlay** | done: toggles the readout block; in fullscreen it keeps pinning the info panel; Learn keeps `L` | keep | keep |
| `S` | **choose the sort order** (opens the choice) | keep | reserved until Lensjet has a sort UI (open backlog entry), then `S` | done: `S` opens the View order choice; `B` stays one release |
| `P` | reserved for Pick (photo-tool convention) | reserved | reserved | reserved |
| ⌥⌘ `P` | link pan across slots (Lensjet only) | — | done (View → Link Pan); `S` keeps linked pan one release | — (no sync lock); done: `S` is the View order |
| `G` | **go to photo** | keep | done (`G`) | done (`G`) |
| `?` | shortcut sheet / guide | keep (`/` too) | keep (`/`, ⌘`/` too) | keep (Ctrl+`?` too) |
| Ctrl/⌘ `O` | open folder | **add** (folder picker) | keep | keep |
| Ctrl/⌘ `D` | Duplicate Finder | **add** (Duplicates screen) | keep | **remove** with the Duplicate Finder (maintainer decision 2026-11-12) |
| Ctrl/⌘ `L` | Library Statistics | **add** (Statistics screen) | keep | keep |
| Ctrl/⌘ `,` | settings / preferences | **add** (Settings screen) | keep | keep |
| ⌘ `[` / ⌘ `]` | previous / next | — | keep (macOS idiom) | — |
| ⇧⌘ `A` | auto-advance on/off | — | keep (Lensjet-only setting) | — |
| Ctrl/⌘ `Q` | quit | — (Android) | system | keep (GNOME idiom) |
| Ctrl/⌘ `W` | close a secondary window or sheet | — | keep (system) | keep |

Rows in **bold** resolve the conflicts of part 1: `F`, `S`, `L`, `I`. `T` and ⌥⌘`P` are the new
homes for the two Lensjet features that lose their letters; bare `P` stays free for Pick. Frameduel's Ctrl chords are lower
priority than the conflicts and can ship separately.

### Recorded reasons for keys that stay different

- **`1` `2` `3`.** Lensjet and Blitzlens switch between three layouts; Frameduel has one
  layout (previous, current, next on one band, sized by its frame solver) and a number
  maximises the frame in that position, left to right. Both answer "which view fills the
  window" for the same three photos, so the digits stay a view-number family. Open question
  below.
- **Digits on Frameduel's Learn screen** set ratings, because Learn has no layouts; the
  selector has no ratings key. Screen-scoped, as in photo tools that rate in one view only.
- **`Tab`** is the platform's focus key. An app takes it only to cycle comparison slots;
  Blitzlens and Frameduel leave it to focus traversal, which keyboard-only users need.
- **`Delete` confirms on Frameduel**: its delete goes through the Storage Access Framework,
  which has no system Trash, while the desktop apps move to the system Trash. Same key, same
  intent.

### UX review (2026-11-10, `@shared-ux-agent`)

1. `L`: Frameduel's Learn keeps `L`; `H`/`L` navigation on the Mac and Linux retires after one
   release (`L` is not in Lensjet's sheet today). `J`/`K` stay, as documented secondary keys.
2. `F`: full screen — on Lensjet in two steps (above), on Blitzlens at once.
3. `P`: not for linked pan. `P` means Pick in Lightroom, Photo Mechanic and Bridge, and Frameduel's
   Learn already uses Lightroom-style `X` and `0`–`5`; linked pan gets ⌥⌘`P` on Lensjet only.
4. `1` on Frameduel stays positional (left / middle / right): making it "current" would leave `2`
   and `3` meaningless and lose the enlarge-previous / next comparison.
5. Blitzlens `S` opens the View order menu; `B` is an alias for one release, then dropped.
6. Principle 5 is a row of its own: held `Delete`/`M`/`C` on Lensjet and Blitzlens repeated on
   2026-11-10 (code reading) — the highest-risk item; arrows keep repeating. Done since in both
   (part 1, behaviour table).
7. Blitzlens does not capture `Tab`: capturing it at the window traps keyboard-only users.

### Done when (per app, in its own change)

- The app's key handler matches its column; the app's key-map test
  (`tests/unit/com/photoselectortoolbox/ui/selector/Selector*KeyTest.kt` and
  `ui/inspect/InspectScreenTest.kt`; `tests/unit/KeyboardShortcutRouterTests.swift`;
  `tests/unit/test_keyboard_router.py`) covers every changed key and its one-release alias.
- The shortcut sheet, README and requirements say the same as the handler.
- This file's part 1 is updated to the new state.
