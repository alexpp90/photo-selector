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

Read against `origin/main` `cd160305` (2026-11-10); the Lensjet column was re-read at `bedfa7f9`
(2026-11-12). Paths below are relative to:

- Frameduel: `products/android/android-desktop/src/com/photoselectortoolbox/`
- Lensjet: `products/macos-desktop/src/`
- Blitzlens: `products/linux-desktop/src/photo_selector_linux/`

## 1. Today

Main culling view, no modifier unless the key says so. `—` means the key is not bound.
⌘ is Lensjet's modifier; Frameduel accepts Ctrl or Meta.

| Key | Frameduel (selector) | Lensjet (workspace) | Blitzlens (workspace) | Same meaning? |
|---|---|---|---|---|
| `←` / `→` | previous / next (`ui/selector/SelectorScreen.kt:846-847`) | previous / next; `↑` / `↓` too (`PhotoSelectorKit/Navigation/KeyboardShortcutRouter.swift:175-203`) | previous / next (`ui/keyboard_router.py:169-174`) | ✅ |
| `H` / `K` | — | previous; `H` marked as going away in the sheet (`KeyboardShortcutRouter.swift:244`) | previous (`keyboard_router.py:169`) | ◐ Frameduel unbound |
| `J` | — | next (`:241`) | next (`:172`) | ◐ Frameduel unbound |
| `L` | **opens Learn** (`SelectorScreen.kt:842-845`) | **next photo**, marked as going away in the sheet (`:241`) | **next photo** (`:172`) | ❌ |
| `M` | move to Selection; also in fullscreen (`SelectorScreen.kt:848`, `ui/selector/FullscreenViewer.kt:470`) | move to Selection (`:235-237`, menu `PhotoSelectorApp/AppCommands.swift:24`) | move to Selection (`:158`) | ✅ |
| `C` | copy to Selection (`:849`) | copy to Selection (`:238-240`, menu `AppCommands.swift:30`) | copy to Selection (`:161`) | ✅ |
| `Delete` / `Backspace` | delete, with confirmation (`:850`) | move to Trash (`:193`, `:206-209`, menu `AppCommands.swift:36`) | move to Trash (`:164`) | ✅ same intent |
| Ctrl/⌘ `Z` | undo (`:831-835`; fullscreen `FullscreenViewer.kt:456`) | undo (`:148-150`, menu `AppCommands.swift:44`) | undo (`:133`) | ✅ |
| `1` `2` `3` | **maximise the previous / current / next frame** (`:854-856`) | **1-Up / 2-Up / 3-Up layout** (`:220-228`, menu `AppCommands.swift:94-104`) | **1-Up / 2-Up / 3-Up layout** (`:177-185`) | ❌ different model |
| `Space` | — | toggle 100 % zoom (`:212-214`, menu `AppCommands.swift:111`) | toggle 100 % zoom; presses a focused button instead (`:188-192`) | ◐ Frameduel unbound |
| `Esc` | close the top layer: sheet, context menu, fullscreen, maximised frame (`:821-827`) | leave any zoom (100 % or pinch), consumed only when zoomed (`:218-219`) | reset zoom (`:193`) | ✅ "back out one layer" |
| `Tab` | — (focus traversal) | cycle the comparison slot (`:215-217`, menu `AppCommands.swift:86`) | not consumed: focus traversal (`:196-199`, `on_cycle_slot` returns `False` at `:302`) | ◐ |
| `F` | **open the fullscreen viewer** (`:851`) | **toggle the filmstrip**, an alias of `T` marked in the sheet as becoming full screen next release (`:247-251`) | **dispatched, does nothing**: `on_toggle_filmstrip` is `pass` (`:203`, `:318-319`); Blitzlens has no filmstrip | ❌ |
| `I` | **opens Learn** (`:842`); in fullscreen pins the info panel (`FullscreenViewer.kt:472`) | toggle the info HUD (`:252-256`, menu `AppCommands.swift:133`) | toggle the info HUD (`:200`) | ❌ in the selector |
| `S` | **opens the Sort menu**, never cycles (`:866-875`) | **toggle linked pan**, kept until the next release; the menu item is ⌥⌘`P` (`:257-259`, menu `AppCommands.swift:114-117`) | **dispatched, does nothing**: `on_toggle_sync_lock` is `pass` (`:206`, `:321-322`) | ❌ |
| `T` | — | toggle the filmstrip (`:247-251`, menu `AppCommands.swift:121-126`) | — | Lensjet only |
| ⌥⌘ `P` | — | toggle linked pan (menu `AppCommands.swift:114-117`) | — | Lensjet only |
| `B` | — | — | toggles the view order lowest sharpness first / natural (`:212`) | Blitzlens only |
| `G` | Go to photo (`:858-865`) | Go to photo (`:260-262`, menu `AppCommands.swift:137-140`) | — (no go-to) | ◐ Blitzlens unbound |
| `?` | the guide; bound as `Key.Slash`, so `/` too (`:857`) | shortcuts sheet; `/` too (`:263-265`); ⌘`?` / ⌘`/` (`:160-162`), ⌘`/` in the Help menu (`AppCommands.swift:157-160`) | shortcuts window (`:209`); Ctrl+`?` (`:148`) | ✅ |
| Ctrl/⌘ `O` | — | open folder (`:151-153`, menu `AppCommands.swift:17`) | open folder (`:136`) | ◐ Frameduel unbound |
| Ctrl/⌘ `D` | — | Duplicate Finder (`:154-156`, menu `AppCommands.swift:148`) | Duplicate Finder (`:139`) | ◐ Frameduel unbound |
| Ctrl/⌘ `L` | — | Library Statistics (`:157-159`, menu `AppCommands.swift:153`) | Library Statistics (`:142`) | ◐ Frameduel unbound |
| Ctrl/⌘ `,` | — | Settings (the SwiftUI `Settings` scene, `PhotoSelectorApp/PhotoSelectorApp.swift:19`) | Preferences (`:145`) | ◐ Frameduel unbound |
| ⌘ `[` / ⌘ `]` | — | previous / next (`:163-168`, menu `AppCommands.swift:56`, `:62`) | — | Lensjet only (macOS Go-menu idiom) |
| ⇧⌘ `A` | — | toggle auto-advance (`AppCommands.swift:49`) | — | Lensjet only |
| Ctrl/⌘ `Q` | — (Android) | quit (system) | quit (`ui/application.py:89`) | platform |

Other screens of Frameduel, scoped to that screen:

- **Learn** (`ui/inspect/InspectScreen.kt:168-208`): `L`, `I`, `Esc` back to the selector;
  `←` / `→`; `0`–`5` set the rating (0 clears); `X` rejects.
- **Fullscreen viewer** (`ui/selector/FullscreenViewer.kt:455-480`): `Esc`, `Delete`/`Backspace`,
  `M`, `C`, `I` (pin info panel), `←` / `→`, Ctrl/Meta `Z`.

Behaviour that is not a key but belongs to the map:

| Rule | Frameduel | Lensjet | Blitzlens |
|---|---|---|---|
| A held `Delete`, `M` or `C` acts once, not once per auto-repeat | ✅ `ONE_SHOT_KEYS` (`SelectorScreen.kt:795-798`; fullscreen `FullscreenViewer.kt:464`) | ✅ `isARepeat` consumed without acting (`KeyboardShortcutRouter.swift:205-209`, `:235-240`) | ❌ code reading: `dispatch` has no repeat input; GTK repeats `key-pressed` |
| Letters ignore Caps Lock and Num Lock | ✅ Compose `Key` | ✅ `charactersIgnoringModifiers?.lowercased()` (`:143`) | ✅ `normalize_keyval`, `clean_modifiers` (`:102-110`) |
| Keys yield to a focused text field | ✅ dialogs own their keys (`ui/selector/GoToPhotoDialog.kt:161-176`) | ✅ `isTextInputActive` (`:132-134`) | ✅ `is_text_editing` (`:123-124`) |

### What the sheets say against the code

- Lensjet's help sheet lists `← ↑ / K`, `→ ↓ / J`, and `H` / `L` as going away (`PhotoSelectorApp/Views/ShortcutsHelpSheet.swift:52-56`); `S` and `F` appear only inside the ⌥⌘`P` and `T` rows (`:72-73`). Its ⌘`L` row says Library Statistics shows focal length, aperture, ISO and shutter speed histograms (`:86`); the sheet shows none (`PhotoSelectorApp/Views/Tools/LibraryStatisticsView.swift`).
- Frameduel's guide (`domain/interaction/SelectorGestures.kt:220-230`) does not list `L`, `I` (Learn) or Ctrl `Z`.
- Blitzlens' sheet and README rightly leave out `F` and `S`, but its requirements and
  [`FEATURE_PARITY.md`](FEATURE_PARITY.md) §2.2 describe them as working (filmstrip, sync lock):
  a dispatch-only unit test is all that backs them.
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
| `J` / `K` | next / previous photo | **add** | keep | keep |
| `H` | — (retired) | — | **retire** after one release | **retire** after one release |
| `L` | open Learn (Frameduel only; reserved elsewhere) | keep | **retire** "next" after one release | **retire** "next" after one release |
| `M` / `C` | move / copy to Selection | keep | keep | keep |
| `Delete` / `Backspace` | delete (Trash on desktop) | keep | keep | keep |
| held `Delete`, `M`, `C` | act once per press (principle 5) | keep (`ONE_SHOT_KEYS`) | **add** a repeat guard | **add** a repeat guard |
| Ctrl/⌘ `Z` | undo | keep | keep | keep |
| `1` `2` `3` | view number (see recorded reason) | keep: enlarge the left / middle / right frame | keep: 1-Up / 2-Up / 3-Up | keep: 1-Up / 2-Up / 3-Up |
| `0`–`5`, `X` | rating, reject (where an app rates) | keep, Learn only | reserved | reserved |
| `Space` | toggle 100 % zoom | reserved | keep | keep |
| `Esc` | back out one layer | keep | keep | keep |
| `Tab` | Lensjet: cycle the comparison slot; elsewhere focus traversal | focus traversal | keep | focus traversal (never captured) |
| `F` | **full screen** | keep (fullscreen viewer) | **change in the release after next**: this release adds `T` and `F` stays the filmstrip (sheet: "Filmstrip T (F until next release)"); then `F` toggles window full screen (as ⌃⌘F) | **change now** (a no-op today): toggles window full screen, `F11` too; `Esc` leaves full screen after it has left zoom |
| `T` | **toggle the filmstrip** | **add** (`toggleFilmstrip` exists, no key) | **add**; `F` keeps the filmstrip for one release | reserved (no filmstrip) |
| `I` | **toggle the info overlay** | **change**: toggles the readout block (`toggleDetails` exists, no key); in fullscreen it keeps pinning the info panel; Learn keeps `L` | keep | keep |
| `S` | **choose the sort order** (opens the choice) | keep | reserved until Lensjet has a sort UI (open backlog entry), then `S` | **change**: `S` opens the View order choice; `B` stays one release |
| `P` | reserved for Pick (photo-tool convention) | reserved | reserved | reserved |
| ⌥⌘ `P` | link pan across slots (Lensjet only) | — | **add** as View → Link Pan; `S` keeps linked pan one release | — (no sync lock); drop the dead `S` binding |
| `G` | **go to photo** | keep | `G` when go-to lands, not ⌘`L` | `G` when go-to lands, not Ctrl+`L` |
| `?` | shortcut sheet / guide | keep (`/` too) | keep (`/`, ⌘`/` too) | keep (Ctrl+`?` too) |
| Ctrl/⌘ `O` | open folder | **add** (folder picker) | keep | keep |
| Ctrl/⌘ `D` | Duplicate Finder | **add** (Duplicates screen) | keep | keep |
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
6. Principle 5 is a row of its own: held `Delete`/`M`/`C` on Lensjet and Blitzlens repeat today
   (code reading) — the highest-risk item; arrows keep repeating.
7. Blitzlens does not capture `Tab`: capturing it at the window traps keyboard-only users.

### Done when (per app, in its own change)

- The app's key handler matches its column; the app's key-map test
  (`tests/unit/com/photoselectortoolbox/ui/selector/Selector*KeyTest.kt` and
  `ui/inspect/InspectScreenTest.kt`; `tests/unit/KeyboardShortcutRouterTests.swift`;
  `tests/unit/test_keyboard_router.py`) covers every changed key and its one-release alias.
- The shortcut sheet, README and requirements say the same as the handler.
- This file's part 1 is updated to the new state.
