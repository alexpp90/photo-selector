# Design.md — Frameduel, Android Desktop (Tablet / DeX)

Complete design instruction set for a visual redesign of the **Android Desktop** solution
(`products/android/android-desktop/`, package `com.photoselectortoolbox`). Self-contained: everything a design
tool or a human designer needs, in one file.

Derived from `REQUIREMENTS.md`, `ARCHITECTURE.md`, and the current Compose sources
under `products/android/android-desktop/src/com/photoselectortoolbox/ui/`.

**Scope note:** this file covers the Android Desktop solution only. The Desktop (Python /
Tkinter) app and the Jackpix phone app (`products/android/phototok/`) are independent
solutions with their own UX models and their own themes — do not apply these instructions
to them.

---

## Table of contents

1. [Product context](#1-product-context)
2. [Target devices](#2-target-devices)
3. [Design principles](#3-design-principles)
4. [Visual language](#4-visual-language)
5. [Information architecture](#5-information-architecture)
6. [Data the UI displays](#6-data-the-ui-displays)
7. [Screen: Selector — three-up comparison, one over two (hero)](#7-screen-selector--three-up-comparison-hero)
8. [Screen: Selector — retired layouts](#8-screen-selector--retired-layouts)
9. [Screen: Fullscreen viewer](#9-screen-fullscreen-viewer)
10. [Surface: Scan configuration](#10-surface-scan-configuration)
11. [Surface: Score legend](#11-surface-score-legend)
12. [Screen: Statistics](#12-screen-statistics)
13. [Screen: Duplicate finder](#13-screen-duplicate-finder)
14. [Screen: Settings](#14-screen-settings)
15. [Breakpoint adaptations](#15-breakpoint-adaptations)
16. [Component sheet](#16-component-sheet)
17. [Accessibility](#17-accessibility)
18. [Keyboard and pointer](#18-keyboard-and-pointer)
19. [Motion](#19-motion)
20. [Using this file with a design tool](#20-using-this-file-with-a-design-tool)

---

## 1. Product context

**One sentence.** A photographer dumps a shoot folder onto the device, the app analyses
every image for technical quality — sharpness, noise, highlight/shadow clipping — and the photographer rapidly compares neighbouring
frames side by side to decide which to keep, copy, move, or delete.

**Who uses it.** Enthusiast and professional photographers reviewing 200–2000 frames from a
single shoot, mostly bursts of near-identical images. Seated, holding a large tablet in
landscape, or at a desk in Samsung DeX with keyboard and mouse.

**The core loop.** Look at three consecutive frames → decide which is sharpest / best →
act → advance. This repeats hundreds of times per session. Every millimetre of image area
and every avoided tap compounds across the session. Optimise for the loop, not for the
first impression.

**What this product is not.** Not a photo editor. Not a gallery. Not a consumer social
app. No filters, no stickers, no reactions, no decorative colour. The nearest reference
points are Lightroom's Library module and Photo Mechanic — not Google Photos.

---

## 2. Target devices

| Priority | Device / mode | Logical size | Notes |
|---|---|---|---|
| Primary | Samsung Galaxy Tab S11 Ultra, landscape | ~1480 × 924 dp (2960 × 1848 px @2x), 16:10 | The reference frame. Design here first. |
| Primary | Samsung DeX / Chromebook / Tablet window | default 1024 × 768 dp, min 600 × 480 dp | Resizable (supports 50/50 split). Hardware keyboard + mouse or touch. |
| Secondary | Tablet portrait | 600–840 dp wide | Medium breakpoint. |
| Tertiary | Phone | < 600 dp wide | Compact breakpoint, reduced feature set. |

**The content is 4:3 photographs.** Almost every image is 4:3, landscape or portrait
orientation. The devices are 16:10. This mismatch is the single most important layout fact
in the product: a 4:3 image fitted to a 16:10 frame always leaves empty side columns.
Those columns are where chrome belongs.

---

## 3. Design principles

Non-negotiable. Every layout decision is checked against these.

1. **The photograph is the interface, and frame size wins every argument.** Maximise the
   pixel area given to images, then place chrome only where the binding constraint is *not*
   — §7.2 works out which axis that is, and it is the first calculation of any layout
   decision on this product. Chrome that competes with the photographs for the scarce axis
   is removed, not shrunk.
2. **No overlays on images.** No floating action buttons, no scrims, no gradients, no
   controls on top of a photo. The exceptions are the one-time first-run navigation hint and
   the maximise badge, which sits on the tile outline. Two interactive controls may never
   share screen bounds.
3. **One control, one home.** Each control belongs to exactly one control group, and nothing
   free-floats.
4. **Touch first, keyboard fast.** Every interactive target ≥ 48 dp; every control that
   changes a file carries a permanent visible word and a permanent key cap. Hover reveals
   nothing that matters — a tablet has no hover.
5. **Dark, quiet, neutral.** The UI must not colour-cast the photographs. Greys plus a
   single lavender accent. No gradients beside images, no coloured glows, no translucent blur
   panels over image areas.
6. **Numbers must be readable.** A quality score is never a bare number: it is icon + short
   label + formatted value, with its direction encoded visually and its legend one tap away.
7. **Density without clutter.** This is a professional tool. Prefer compact type and tight
   spacing over generous consumer padding — while keeping touch targets full size.

**Creative latitude.** Layout composition, hierarchy, information density, empty states and
visual polish are open. The dark neutral grey / lavender palette, principles 1–3, and the four-destination
navigation model are fixed. The three-image comparison view is the heart of the product and
deserves the most design effort.

---

## 4. Visual language

### 4.1 Colour

Dark theme only. Material 3 `darkColorScheme`. Frameduel palette: neutral-grey studio surfaces (R = G = B, so the surround adds no colour cast to the photos), a lavender brand accent used sparingly for the primary action, selection and focus, and Curatorial Amber reserved for keepers and ratings. The orange-red of the app icon is icon-only and never appears in the UI.

| Role | Hex | Name | Use |
|---|---|---|---|
| `background` / `surface` | `#0D0D0D` | StudioCanvas | Neutral canvas pedestal; zero chromatic cast |
| `surfaceVariant` | `#151515` | StudioPanel | 88dp navigation sidebar, details pane shell |
| `surfaceContainer` | `#1E1E1E` | StudioCard | Hardware control block card fill & telemetry tiles |
| `outline` | `#313131` | StudioBorder | 1dp structural hairline dividers & borders |
| `outlineVariant` | `#484848` | StudioBorderActive | Highlighted / active border |
| `onSurface` / `onBackground` | `#FAFAFA` | Zinc-50 | Primary text |
| `onSurfaceVariant` | `#A1A1A1` | Zinc-400 | Secondary text, icons at rest |
| `primary` | `#C4B8FF` | BrandAccent | Active 2dp hero frame border, primary action, selection, focus, active nav (`onPrimary` `#2D2570`, BrandOnAccent, 7.2:1) |
| `tertiary` | `#DAD3FF` | BrandAccentGlow | Focus diamond indicator (`◈`), telemetry reticles |
| `curation` | `#F59E0B` | CurationAmber | Keeper selection, star ratings, curatorial triage |
| `secondary` | `#A1A1A1` | Zinc-400 | Muted controls |

Semantic score micro-telemetry — used **only** for score direction and destructive actions, never
decoratively:

| Role | Hex | Name |
|---|---|---|
| Good / pass | `#34D399` | Emerald-400 (`ScoreGood`) |
| Warning | `#FBBF24` | Amber-400 (`ScoreWarn`) |
| Bad / destructive | `#F87171` | Rose-400 (`ScoreBad`) |

### 4.2 Type

Roboto or Inter, Material 3 type scale.

| Element | Style | Size |
|---|---|---|
| Filenames | bodyMedium, middle-ellipsis, one line | 14 sp |
| EXIF lines, score chips | labelSmall–labelMedium | 11–13 sp |
| Column headers ("Previous"/"Current"/"Next") | labelSmall, uppercase tracking | 11 sp |
| Card and section titles | titleSmall / labelLarge | 14–16 sp |

Numeric values use **tabular figures** so columns of scores align vertically across the
three comparison columns. Decimal separator is always a period (`Locale.US`) — never
locale-dependent, or the same score renders differently on different devices.

### 4.3 Shape, elevation, spacing

- Corner radius: 8 dp on cards and chips, 12 dp on sheets and dialogs, **4 dp or 0 dp on
  images**. Rounded photos read as consumer styling — never exceed 4 dp.
- **No drop shadows.** Separate surfaces with a 1 dp `#3F3F46` outline plus the
  `#18181B` → `#27272A` value step. Dark themes read better with borders than shadows.
- Spacing scale: 4 / 6 / 8 / 12 / 16 / 24 dp. In the comparison layouts, outer padding is
  ≤ 8 dp and column gaps are 8 dp — space is not decoration here.
- All screens are edge-to-edge and must respect `WindowInsets` for system bars.

### 4.4 Iconography

Material Symbols, Rounded, weight 400.

| Concept | Icon |
|---|---|
| Selector | `photo_camera` |
| Statistics | `bar_chart` |
| Duplicates | `content_copy` |
| Settings | `settings` |
| Move to Selection | `drive_file_move` |
| Copy to Selection | `content_copy` |
| Delete | `delete` |
| Fullscreen | `fullscreen` |
| Photo details | `info` |
| Filmstrip | `view_carousel` |
| Sharpness | `center_focus_strong` |
| Noise | `grain` |
| Highlight clipping | `brightness_high` |
| Shadow clipping | `brightness_low` |
| Maximise a frame | `open_in_full` |
| Value overlays on / off | `visibility` / `visibility_off` |
| Shortcut sheet | `keyboard` |

**The four metric glyphs are load-bearing, not decorative.** On Previous and Next the glyph
is the *only* thing identifying a metric (§7.4b), so each one is declared once on
`ScoreMetric` next to its label, format, direction and normalisation range — never picked at
a call site — and the named readout beside the current frame (§7.4a) is what teaches it. A
metric added without a glyph must fail to compile, not render blank.

---

## 5. Information architecture

Four top-level destinations, reached from a persistent **NavigationRail** on the left edge
(Expanded and Medium) or a **BottomNavigation** bar (Compact).

| # | Destination | Purpose | Share of session |
|---|---|---|---|
| 1 | **Selector** | The culling workspace. Default destination. | ~95% |
| 2 | **Statistics** | EXIF distribution charts for the loaded folder. | small |
| 3 | **Duplicates** | Byte-identical file finder (SHA-256) with batch delete. | small |
| 4 | **Settings** | Storage, analysis, display, cache, about. | rare |

### Session-level actions

Open Folder (system folder picker) · Scan Images · Cancel Scan ·
Clear Scores · Group Similar Series · Score legend.

### Per-image actions

Move to Selection · Copy to Selection · Delete · Open Fullscreen.

"Selection" is a destination subfolder — default name `Selection`, configurable — created
inside the source folder. Deletion goes to the Android trash and is undoable via a
30-second Snackbar.

### Persistent state the design must reflect

- **Nothing about the arrangement is persisted.** There is one comparison layout, so there is
  no choice to remember and no toggle to offer (§7, REQUIREMENTS §2). Maximise is transient
  view state and is deliberately not persisted either.
- Filmstrip visible/hidden is a user toggle, remembered. It starts **hidden** (§7.8).
- Photo-details panel visible/hidden is a user toggle, remembered.
- The first-run navigation hint shows exactly once, then never again.

---

## 6. Data the UI displays

### 6.1 Image item

Filename · thumbnail · full-resolution image · orientation (4:3 landscape or portrait) ·
position in folder (`127 / 842`) · selection state.

### 6.2 EXIF data

Loaded asynchronously for the current image and its two neighbours; the UI updates
reactively as each arrives.

| Field | Rendered as |
|---|---|
| Shutter speed | `1/250s` (sub-second) or `2s` |
| Aperture | `f/2.8` |
| Focal length | `35mm` |
| 35mm equivalent | `52mm` |
| ISO | `ISO 400` |
| Lens | free text |

Any field may be `Unknown`. A fallback flag exists when full EXIF could not be read — show
it as a subtle "limited metadata" marker, not an error.

Compact one-line form used in comparison columns:
`1/250s · f/2.8 · 35mm · ISO 400`

### 6.3 Quality scores

Produced by a scan. Any subset may be absent — an image may have no scores at all.

| Chip label | Full name | Format | Direction | Description (shown in legend) |
|---|---|---|---|---|
| `Sharp` | Sharpness | `%.1f` | higher is better | Edge contrast in the sharpest part of the frame. Low values usually mean motion blur or a missed focus. |
| `Noise` | Noise | `%.1f` | lower is better | Estimated sensor noise in flat areas of the image. Rises with high ISO. |
| `Highl` | Highlight clipping | `%.1f%%` | lower is better | Share of pixels blown out to pure white — detail that cannot be recovered. |
| `Shad` | Shadow clipping | `%.1f%%` | lower is better | Share of pixels crushed to pure black — detail that cannot be recovered. |

**Rendering rule.** A score chip is **icon + short label + value**. The label may be dropped
only on thumbnail overlays where space genuinely does not exist — and there the accessibility
label must still carry the full name, value and direction.

**Direction indicator.** Each chip carries a 2 dp bar beneath it that fills toward "good",
tinted emerald → amber → red. This lets the user compare three frames at a glance without
reading digits, and satisfies the requirement that direction not be conveyed by colour alone.

---

## 7. Screen: Selector — three-up comparison (hero)

**Frame:** 1480 × 924 dp landscape tablet (Galaxy Tab S11 Ultra). This is the most important
screen in the product, and from this revision it is the **only** comparison layout: three
equal frames arranged **one over two**, at 600 × 450 dp each.

> **The single governing rule of this screen.** Frame size beats every other consideration.
> Any element that competes with the photographs for space must either move into space the
> photographs cannot use, or be removed. §7.2 proves which space that is.

### 7.0 What this revision changes, and why

The previous revision was right about the principles and wrong about three things in
practice. Recorded here so the reasoning is not lost:

| Problem observed | Root cause | Resolution |
|---|---|---|
| Three-up frames render far smaller than the display allows | the frames were arranged **in a row**, and three 4:3 frames abreast are width-bound — they cap at 493 × 370 dp even with zero chrome, abandoning 60 % of the display's height. Width-first `aspectRatio` and five stacked caption elements per column then took more still | the row is abandoned for a one-over-two arrangement, which is height-bound at **600 × 450 dp** — 76 % more area per frame (§7.2) |
| Which frame does a number describe? | scores sat in a caption stack under each column, and then in a single matrix beside one frame — in both cases the eye had to map a column position back to a photograph, hundreds of times a session | values are placed *on* the frame they describe: named and iconed beside the centred current frame, icon-and-value overlaid on each neighbour's own right edge (§7.4) |
| The current frame does not read as the one being decided on | it was top-left in a two-row grid, distinguished only by a border | it is **centred**, with its readouts left and its controls right (§7.2) |
| Controls read as decorative — small, unlabelled icons | `BarIconButton` is 34 dp with an 18 dp glyph and no visible text; rail buttons are 48 dp but icon-only; key hints appear on hover, which a tablet has none of | every control that acts on a photograph carries a permanent text label and a permanent key cap, at ≥ 48 dp (§7.5) |
| Session controls (Scan, bursts, legend) live in a 44 dp top bar | vertical space is the scarce axis, and a horizontal strip of unlabelled 34 dp glyphs is the least legible arrangement available | they move into a single labelled left sidebar merged with the app navigation rail (§7.1) |
| "As large as possible" is unreachable in three-up | three 4:3 frames abreast are geometrically width-bound; no amount of chrome trimming makes them large | an explicit per-frame **maximise** affordance (§7.3) |

### 7.1 Shell — one left sidebar, no top bar

There is **one** vertical control surface, 88 dp wide, on the left edge, and it replaces both
the Material `NavigationRail` and the selector's top app bar. Two zones separated by a 1 dp
Zinc-700 rule:

```
┌────────┐
│ Folder │  ← zone A: session actions (was the top app bar)
│ Scan   │
│ Bursts │
│ Legend │
├────────┤
│ Cull   │  ← zone B: app screens (was the NavigationRail)
│ Stats  │
│ Dupes  │
│ Setup  │
└────────┘
```

- Each item is a 72 dp-tall, 80 dp-wide target: 24 dp glyph over an 11 sp label, centred.
  The column scrolls vertically: its natural height is ~785 dp, so in any shorter window
  (DeX and ChromeOS open at 1024 × 768; the manifest allows 480 dp) Stats, Dupes and Setup
  were simply cut off. A 16 dp fade marks an edge only while there is more beyond it; the
  items keep their 72 dp rather than shrinking to fit. **The label is not optional** — an icon plus nothing is the defect this revision exists to
  fix.
- Active state: `BrandAccentNav` fill plus an lavender accent glyph and label. Toggle items
  (Bursts) use the same treatment for their on state, so "selected screen" and "toggle on"
  read identically — both mean *this is currently true*.
- **Scan** is the one emphasised item: `BrandAccentTonal` fill at rest. While a scan runs it
  becomes a counter (`412 / 842`) over a **Cancel** label in `ScoreBad`, with a 2 dp accent
  determinate line along the sidebar's right edge. A scan still never takes the screen.
- **Queued and busy items share that treatment.** Scan and Bursts conflict — each rebuilds
  what the other reads — so the second request waits rather than clobbering the first. A
  waiting request shows `Queued` over the same `ScoreBad` **Cancel**; a running grouping
  pass shows `Grouping` over it. Neither control is ever greyed out: a disabled control and
  a frozen app look identical from the outside, and this is the mirror case the desktop
  product got wrong (`ai/memory/palette.md`, 2026-07-24).
- Folder name and the burst chip move into the head of the **left readout block** (§7.4a);
  `127 / 842` closes the control block on the opposite side (§7.5). Nothing goes above the image region: a 32 dp strip would still be 21 dp off
  the height of every frame.
- Net budget: the sidebar costs 88 dp of width against the 80 dp rail it replaces, and
  returns the app bar's whole 44 dp of height. Because §7 is height-bound, that is **+29 dp
  of height on every frame at a cost of zero** — the sidebar is not a compromise, it is a
  gain.

### 7.2 The layout maths — why one over two, and why the chrome is then free

Three equal 4:3 frames must be packed into a 1480 × 924 dp window. There are only two
arrangements that keep them equal, and they are not close:

| Arrangement | Binding constraint | Frame size | Area | Height used |
|---|---|---|---|---|
| Three in a row (previous revision) | **width**: `w ≤ 1480/3` | 493 × 370 dp | 182 k dp² | 40 % |
| **One over two** | **height**: `2h ≤ 924` | **616 × 462 dp** | **285 k dp²** | 100 % |

```
one over two:   2h ≤ H  →  h ≤ 462  →  w = h × 4/3 = 616
                2w ≤ W  →  w ≤ 740                          ← slack: 124 dp per frame
three in a row: 3w ≤ W  →  w ≤ 493  →  h = 370
                 h ≤ H  →  h ≤ 924                          ← slack: 554 dp of height, wasted
```

**This is the whole redesign in one line.** In a row, width is scarce, so every dp of
horizontal chrome — sidebar, readout block, control block — is taken directly off all three
photographs. One over two flips the binding constraint to height, which leaves **124 dp of
horizontal slack per frame that the photographs physically cannot use.** The controls move
into that slack and cost the images nothing.

With real chrome subtracted:

```
sidebar 88 · outer padding 8 · row gap 8 · no top app bar
image region = 1376 × 908 dp
row height   = (908 − 8) / 2 = 450        tile = 600 × 450 dp

top row      [ values 388 ][ CURRENT 600 ][ controls 388 ]   → §7.4a, §7.5
bottom row   [ 84 ][ PREVIOUS 600 ][ 8 ][ NEXT 600 ][ 84 ]   → §7.4b
```

With the filmstrip switched on the control flank splits, and only the flank does — the frames
and the bottom row are untouched:

```
top row      [ values 342.5 ][ CURRENT 675 ][ controls 262.5 ][ 8 ][ strip 72 ]   → §7.8
```

(measured at 3:2, where the flank resolves to 342.5 dp; at 4:3 the flank is 388 and the
controls keep 308.)

**The current frame is centred in the image region**, with its readouts and its controls
balanced either side — 388 dp each at 4:3, 342.5 at 3:2; the flanks are whatever the frame
leaves, not a fixed number. Previous and Next are centred as a pair beneath it, so
the three frames share a vertical centre line. Centring is what marks the current frame as
the one under judgement — together with its 2 dp accent border, and *not* by making it
larger, which would destroy the comparison.

600 × 450 against the previous revision's 453 × 340: **+76 % area per frame.** Against the
current shipped focused layout's 524 × 393 (which pays a 44 dp app bar and a 76 dp filmstrip
out of the height budget): **+31 %.**

#### Measured, 2026-08-08, on the reference device

The arithmetic above is what the screen is supposed to do; this is what it does. Window
1480 × 924 dp, sidebar 88, outer padding 8 — nothing else in the vertical stack:

| Photograph | Frame | Binding axis |
|---|---|---|
| 4:3 | 600 × 450 | height — `2 × 450 + 8 = 908`, the whole region |
| 3:2 | 675 × 450 | height — two abreast need 1358 of 1376 dp, 18 dp spare |
| 16:9 | 684 × 385 | **width** — two abreast want 1464 dp; only 1376 exist |

The 3:2 figures are identical with the filmstrip switched off and switched on, which is what
"the strip is free" means and is asserted rather than asserted-about. Note the 18 dp of spare
width in that row, though: it is why the strip is a **72 dp column inside the flank** and not
a full-height column down the outer edge of the region. A strip that spanned both rows would
take its width off the bottom row too, and at 3:2 the bottom row has 18 dp to give — the
frames would fall to 646 × 431, below the 440 dp floor. Height is scarce, but at this aspect
ratio the bottom row's width is nearly as scarce, and only the *flanks* are genuinely surplus.

**Past 3:2 the bottom row runs out of width before the column runs out of height.** A 16:9
frame caps at 385 dp on this display however much height is freed, and that is the display's
ceiling, not a leak. This distinction cost a full investigation: a 16:9 test fixture rendering
at 41 % of the window height was read as "146 dp are being eaten somewhere", and the same
41 % at 1280 × 800 was read as confirmation, when both windows are ~1.6:1 and a width-bound
frame is therefore the same fraction of either. **Measure the region before concluding
anything about the budget** — `image_region` and `selector_sidebar` carry test tags for
exactly this.

The top status bar is 36 dp on the reference device: it is preserved ("left alone") and the app
pads for `safeDrawing` horizontally and for the top status bar (`safeDrawing.only(Horizontal + Top)`),
ensuring the status bar never overlays app content. On the bottom, the navigation bar / taskbar
(32 dp: launcher, back/home buttons) is hidden via `WindowInsetsControllerCompat.hide(navigationBars())`
with swipe-to-reveal behavior, freeing the bottom space for the app. The reference frame height at
3:2 is 432 dp with the status bar excluded and bottom navigation bar hidden (MinimumReferenceFrameHeight = 430 dp).

Two consequences that must not be forgotten during implementation:

- **Height is the only scarce axis, and the flanks are the only surplus.** Anything that
  consumes height — a top app bar, a horizontal filmstrip, a caption row under a frame,
  vertical padding — comes straight off every frame at a rate of 2 dp of frame height per
  3 dp taken. Nothing may be added to the vertical stack. Width is free, but only *in the
  flanks*: at 4:3 the bottom row has 160 dp of slack, at 3:2 it has 18 dp, and chrome that
  spans both rows is priced against the smaller of the two. Chrome that lives in a flank is
  priced against the 82.5 dp each flank has above the control block's 260 dp minimum.
- **The sidebar is free, and can therefore afford to be legible.** 88 dp of labelled sidebar
  costs zero dp of frame size here, where it would have cost 29 dp per frame in a row. This
  is why §7.1 can spend width on words.

**Implementation rule (non-negotiable).** Compute the tile size once, in a
`BoxWithConstraints` over the image region, and hand all three tiles the same explicit
`DpSize`. Do not use `aspectRatio` with an implicit constraint order — see the 2026-07-27
lesson in `ai/memory/palette.md`; width-first resolution is exactly how three frames end up
different heights. A UI test asserts all three `getUnclippedBoundsInRoot()` are equal to
within the border delta (2 dp active, 1 dp resting), and a second test asserts the tile
height is at least 440 dp on the reference frame — a size regression must fail the build,
not wait to be noticed.

**Portrait frames.** The tile footprint stays 4:3 and a portrait frame is pillarboxed inside
it. A portrait frame mid-burst must never reflow the grid — this remains the one deliberate
exception to "the tile is the image".

**Reading order.** Current is centred on the top row; Previous and Next sit beneath it, left
to right. Neighbours are never dimmed, scrimmed or shrunk.

### 7.3 Maximise — how a single frame gets larger still

600 × 450 dp is the maximum for *three* equal frames. When one frame needs the whole display,
two routes land on the same 1376 × 908 dp region — a **4:3 frame at 1211 × 908 dp, 4.1 × the
area of its three-up tile**:

1. **Maximise badge.** Each tile carries a 48 dp `⛶` badge in its bottom-right corner,
   inside the tile bounds but outside the image's safe centre. Tap it, or press `1` / `2` /
   `3`, and that frame fills the region. The badge is the only permanently drawn on-image
   affordance permitted, and it is drawn on the tile's outline, not over the photograph.
2. **Fullscreen** (`F`, or tap the current frame) — unchanged, and now reachable for a
   *neighbour* too, via that neighbour's badge.

In the maximised state the maximised frame keeps the §7.4a readout block beside it, and the
two hidden frames' values collapse into a single 148 dp column at the right
edge — the same overlay vocabulary as §7.4b, so the numbers for the frames you can no longer
see stay readable and stay recognisable. `Esc` or a second tap on the badge returns to three-up. Transition is a
180 ms crossfade — no scale, no slide. The user is judging sharpness, and movement lies.

### 7.4 Value readouts — every number touching the photograph it describes

The previous revision put all fifteen numbers in one matrix beside the current frame. Reading
across a row compared the metrics well, but it broke the more basic question — *whose number
is this?* — because two of the three columns described photographs a long way from them on
screen. Proximity beats tabulation: a value belongs to a frame, so it is drawn on or against
that frame.

The metric set, the `ScoreMetric` definitions, the 0..1 goodness normalisation, the 2 dp
direction bar and the accent best-of-three dot are unchanged throughout. Only placement and
verbosity differ, and they differ by role.

#### 7.4a Current frame — named, iconed, to its left

The 388 × 450 dp block left of the centred current frame. Full verbosity, because this is
also the **legend** for the two overlays below it:

```
DSC_0127.JPG
1/500 · f2.8 · 35mm · ISO 400
──────────────────────────────
◎  Sharpness                688 ●
   ▇▇▇▇▇▇▇▇▇▇▇▁
◍  Noise                    2.4
   ▇▇▇▇▇▇▁▁▁▁▁▁
☀  Highlights               1.9%
   ▇▇▇▇▁▁▁▁▁▁▁▁
☾  Shadows                  0.2%
   ▇▇▇▇▇▇▇▇▁▁▁▁
```

- **Icon + full metric name + value**, one metric per line, the goodness bar beneath, indented
  to the name so the icon column stays clean. Right-aligned values in tabular mono so the
  four numbers form a column.
- The icon here is the *same glyph* the overlays use — this block is what teaches it. Each
  metric therefore has exactly one icon, defined once on `ScoreMetric` alongside its label,
  format and direction, never chosen at a call site.
- Filename and the one-line EXIF summary head the block; `127 / 842` sits under the controls
  on the opposite side, balancing the composition.
- Unscanned: a single dashed "Not scanned" row, not four blanks.

#### 7.4b Previous and Next — identity, exposure and values, on the frame's own right edge

A 148 dp column carrying the same information as §7.4a in the same reading order, compressed:
identity, then exposure, then a rule, then icon + value + bar per metric.

```
┌────────────────┐
│ DSC_0126.JPG   │  11 sp, middle-ellipsis, Zinc-50
│ 126 · 1/250 f2.8│ 9 sp mono, Zinc-400
│ 35mm · ISO 400 │  9 sp mono, Zinc-400
│ ────────────── │
│ ◎ 412   ▇▇▇▇▁▁ │
│ ◍ 2.9   ▇▇▇▁▁▁ │
│ ☀ 0.4%  ▇▇▇▇▇▁ │
│ ☾ 0.1%  ▇▇▇▇▇▇ │
│ ★ 6.4   ▇▇▇▇▁▁ │
└────────────────┘
```

- **Same order as the current frame's block, always.** Filename, exposure, rule, then the four
  metrics in the `ScoreMetric` declaration order. The eye learns one vertical order and reuses
  it across all three frames; a neighbour that ordered its metrics differently would make the
  overlays unreadable at a glance, so the order comes from the enum and never from a
  composable.
- **Exposure wraps to two lines rather than truncating.** `1/250 · f2.8` then
  `35mm · ISO 400`, tabular mono so shutter speeds and apertures align vertically between
  Previous and Next — the comparison the photographer is actually making. Position number
  leads the first line. Lens name is omitted here and remains in the context menu's Photo
  details; it is the one EXIF field that will not fit and the one that is identical across a
  burst.
  It wraps only *between* tokens — each value (`1/250s`, `f/2.8`, `50mm`) is unbreakable, on
  the current frame's readout as well — and a trailing token that does not fit in two lines is
  dropped, never split (AD-16b: the 720 × 560 Chromebook window printed `f/` ⏎ `2.8`).
- **Fallback EXIF** shows a 10 dp amber `error_outline` after the exposure block instead of
  the words "limited metadata" — the marker is what matters, the explanation is in the
  details panel.
- **Placement.** Outside the frame, against its right edge, whenever ≥ 156 dp of free width
  exists there; otherwise **overlaid inside the frame**, inset 8 dp from the right edge. On
  the reference device the bottom row has 84 dp free per side, so it overlays. One rule, one
  measurement, both branches tested — never a hard-coded choice.
- **Overlay treatment.** `Zinc950` at 72 % over a 6 dp radius, 8 dp inset top, right and
  bottom, vertically centred, 14 dp icons, `Zinc50` values. Deliberately a flat panel and not
  a gradient scrim: a gradient across the right of a photograph reads as part of the
  photograph, which is the failure mode §9 already documents for the fullscreen top bar.
- **It covers roughly 25 % of the frame's width, and that is a real cost.** Two mitigations,
  both required: the overlay hides while a frame is maximised (§7.3), and the eye control in
  the view cluster toggles all overlays off for a clean look at the photographs. The state is
  persisted (`overlay_values_visible`, default on).
- No metric names — §7.4a is the legend, and these frames are being scanned, not read. The
  full name, value and direction still go in the `contentDescription` of every overlay row,
  and the exposure block is read out in full.
- The maximise badge moves to the frame's **bottom-left** on these two tiles so it cannot
  collide with the overlay. A UI test asserts their bounds do not intersect.

#### Why this placement, in one line each

| Frame | Treatment | Reason |
|---|---|---|
| Current | Left block, named, iconed | It is the frame being decided on; it can afford words, and it doubles as the legend |
| Previous / Next | Right-edge overlay: filename, exposure, icon + value | The data must be unambiguously *theirs*; at a glance you compare bar lengths and aligned exposure tokens, not names |

### 7.5 Controls — right of the current frame

The flank right of the centred current frame, mirroring the readouts on the left. Its width
is whatever the solver leaves — 342.5 dp at 3:2 on the reference device, of which 262.5 goes
to the controls when the filmstrip shares the flank (§7.8), never below the 212 dp the
view-toggle row needs. A 2 × 3
grid of large controls rather than a narrow rail, because that width is surplus and a button
of ~158 dp (~118 dp with the strip on) carries its word comfortably:

```
┌──────────┬──────────┐
│ ⧉ Copy C │ ➜ Move M │
├──────────┼──────────┤
│ 🗑 Delete│ ⛶ Full F │
│    Del   │          │
├──────────┼──────────┤
│ ‹ Prev ← │ Next → › │
└──────────┴──────────┘
      ▤  ▭  👁  ⌨
        127 / 842
```

- Each cell is 80 dp tall and as wide as the flank allows: glyph above, word below, key cap
  after the word. Copy, Move and Delete are permanently worded — a control that changes a
  file is never icon-only (§17). The two filing verbs come from `SelectionActionLabels`, so
  they read Copy and Move according to the setting and never *Keep* (§17).
- The configured filing verb is the emphasised primary — `ScoreGood`-tinted tonal — the other
  stays visible and outlined, and Delete is outlined with a `ScoreBad` glyph and word. Delete
  sits diagonally opposite the primary filing button, not adjacent to it.
- Previous / Next occupy the bottom row, furthest from Delete, and are duplicated by tapping
  a neighbour frame, swiping the image region, and the arrow keys.
- Beneath the grid, a 48 dp icon row of view controls — readout panel, filmstrip, overlay
  visibility, shortcut sheet. Icon-only is acceptable *only here*, because none of them
  changes a file; each carries a tooltip and a full `contentDescription`.
- The position counter `127 / 842` closes the block, in tabular mono — and reads
  `127 / 842+` while the folder is still enumerating. Discovery streams, so the total is a
  running total for the first seconds of a large shoot; the `+` is the whole of the loading
  indication this screen gets, because the first batch is already usable and a spinner over
  a usable screen is a lie about what the photographer can do.
- Key caps are **permanent**, not hover-revealed. A tablet has no hover, and the desktop
  lesson already recorded (2024-05-18, `ai/memory/palette.md`) is that unhinted shortcuts go
  unused.
- Nothing here may spill into a horizontal row across the bottom of the screen. That costs
  72 dp of height, which is 48 dp off the height of all three frames.

**Height is the block's binding axis too.** Row 1 is one frame tall: ~436 dp on the reference
device, ~376 dp at 1280 × 800 (Pixel Tablet native), ~336 dp at 1024 × 720 (the DeX/ChromeOS
default window less its bars) and ~228 dp at 600 × 480. The block above needs 332 dp, and a
`Column` pays a shortfall with its last children — the 2026-10-17 device lab found the
counter gone and the view toggles squashed to slivers below ~410 dp. So the block has two
variants, chosen by `ControlBlockFit` from the row height it is handed and the font scale:

```
FULL (≥ 332 dp, ≥ 210 dp wide)   COMPACT (201–265 dp)
┌──────────┬──────────┐     ┌───────────┬───────────┐
│ ⧉ Copy C │ ➜ Move M │     │⧉ Copy  [C]│➜ Move  [M]│  48 dp rows
│  …80 dp…  │          │     │🗑 Delete[Del]│⛶ Full [F]│
│          │          │     │‹ Prev  [←]│Next [→] › │
└──────────┴──────────┘     └───────────┴───────────┘
      ▤  ▭  👁  ⌨             ▤ ▭ 👁 ⌨      127 / 842
        127 / 842
```

Every view toggle is a 48 × 48 dp target at every size (AD-17): four in a row from 192 dp of
inner width, a 2 × 2 grid below it, with the counter beside the toggles where it fits and on
its own line above them where it does not (201 dp tall in one row with the counter beside it,
217 dp with the counter above, 265 dp as a 2 × 2 grid). COMPACT keeps every word and every key cap,
never ellipsized (AD-16b): the button is icon, word and key cap on one line from a 100 dp cell,
the word over the key cap beside the icon from 64 dp, and word over key cap without the icon
below that, and the word steps down from 13 sp to 9 sp before it could be cut. At 720 × 560 the
flank is 150 dp, so the block is a 2 × 2 grid with stacked buttons; at 600 × 480 it scrolls.
Below COMPACT the block scrolls with the counter first, so nothing is ever clipped silently.
The frames never pay for the block: the variant changes, the frame does not.

### 7.6 Interaction — one gesture vocabulary, defined once

Every gesture below is defined in a single pure object (`SelectorGestures`) shared by the
three-up layout, the compact layout, the fullscreen viewer and every hint string, so the
copy and the behaviour cannot drift. This is the 2026-07-31 lesson in
`ai/memory/palette.md` applied to the whole product rather than one label.

| Input | Effect | Notes |
|---|---|---|
| Tap a neighbour tile | Navigate to that frame | the neighbours *are* the navigation |
| Tap the current tile | Fullscreen | |
| Tap a tile's `⛶` badge | Maximise that frame in place | |
| Horizontal swipe on the image region | Previous / next | same direction as fullscreen, same as `←` / `→` |
| Long-press / right-click a tile | Context menu at the pointer | Move `M`, Copy `C`, Delete `Del`, Details, Fullscreen `F` |
| Double-tap a tile | *nothing* | reserved; never bind a destructive or filing action to it |

**No destructive action is ever a bare swipe, in any layout.** Horizontal swipe means
navigate everywhere in this product — the compact layout's swipe-left-to-delete is removed
by this revision, because a gesture that means "next" on one screen and "delete" on another
is a trap, and it is the direct cause of the wrong fullscreen hint text.

### 7.7 First-run navigation hint

One centred pill ("Tap either side to browse" · "Got it"), shown once, `hasSeenNavHint`
persisted. It sits over the Previous/Next pair it teaches about, lifted clear of the
neighbours' maximise-badge band (`FrameGeometry.firstRunPillBottomInset`: badge inset + badge
+ 8 dp) — at the original 16 dp it sat squarely on the Next frame's badge, so the first tap a
new user aimed at maximise dismissed the hint instead. "Got it" is a 56 × 48 dp target. It remains the
only image-adjacent overlay besides the maximise badge.

**What "only" restricts, precisely.** It restricts *placement*, not the existence of
guidance. The rule this screen is built on is that nothing may compete with the photographs
for space, and the pill is the single exception because it has to point at the tiles it is
describing. §7.9 and §7.10 add two teaching surfaces that are not exceptions to it at all:
both are transient, both are dismissed for good on first use, and — the part that matters —
**both are laid out entirely inside the horizontal slack**, so neither ever occupies a
frame's bounds. A UI test asserts the non-intersection rather than leaving it to review. The
distinction is chrome versus teaching: permanent chrome is priced in frame height (§7.2) and
is therefore rationed; a surface the user asked for, that goes away, and that costs the
frames nothing, is not.

### 7.8 Filmstrip — a column, and off by default

**Rotated, not just moved.** The strip is a 72 dp-wide vertical column with a 1 dp left
border, scrolling top-to-bottom down the outer (right) edge of the control flank (§7.5). The
sidebar owns the far left; the strip takes the far right.

```
┌────┬──────────┬──────────────┬──────────┬──┐
│    │ readouts │   CURRENT    │ controls │▓▓│  ← 72 dp strip, 450 dp tall
│side│          ├──────┬───────┴──────────┤▓▓│
│bar │          │ PREV │  NEXT │          │  │
└────┴──────────┴──────┴───────┴──────────┴──┘
```

This is measured rather than argued, twice over.

*Why not across the bottom.* A 76 dp full-width strip took the reference frames from
**675 × 450 to 618 × 412** — 38 dp off every frame, ~16 % of the area of all three at once,
permanently, for an affordance the compare-and-cull loop does not use (that loop is Prev/Next
and the two neighbour frames; the strip is random access to the rest of the shoot).

*Why not full height down the region's edge.* Tempting, because it would carry twice the
thumbnails, and wrong: at 3:2 the bottom row has only 18 dp of spare width, so a column that
spanned both rows would flip the bottom row to width-bound and land the frames at
**646 × 431**, below the 440 dp floor. Width is surplus *in the flanks*, not everywhere.

*Why 72 dp.* At 3:2 each flank resolves to 342.5 dp where the control block needs 260 — 82.5
dp of slack. 72 dp of strip plus the 8 dp gap is 80 of that, so the frames measure 675 × 450
with the strip both off and on. Past 74.5 dp the flank pushes the top row's fit below what the
bottom row can carry and all three frames start losing height. A unit test asserts the frames
do not move when the strip is toggled, which is what prices any future widening.

`FrameGeometry.threeUpLayout` returns the strip's width as part of the solved layout, so the
coach-mark overlay (§7.9) reserves the same column rather than re-deriving it. Where a window
is too narrow to hold the strip beside the view toggles the solver returns zero and the strip
is dropped, rather than drawn over the controls; with the readouts hidden the flank still
keeps room for both.

**Content**, re-derived for a column:

- 60 dp-wide thumbnails, 40 dp tall landscape / 80 dp portrait. **Width is the constant**,
  because in a vertical strip width is the cross axis and a ragged cross axis cannot be
  scanned — the mirror of the horizontal strip's constant 56 dp height.
- 3 dp radius, 1 dp Zinc-700 outline, .85 opacity; the current thumbnail is 2 dp lavender accent
  at full opacity.
- One 6 dp sharpness dot tinted by goodness, bottom-right. No text: at this size a number is
  not legible, and the question the strip answers — "is there anything better nearby" — is
  answered faster by a colour.
- With Bursts on, thumbnails in the current burst carry the 2 dp deep accent marker down their
  **leading edge** rather than under them, and each new burst starts after a 10 dp gap. Same
  rule, same colour, same reading: a bracket around a run.
- The mono range caption (`115–156 of 842`, 9 sp, wrapping to two lines) sits **below** the
  list, not over it. At 72 dp an overlaid caption covers a thumbnail outright, and a caption
  reporting what is visible has no business hiding part of it.
- Hidden while a frame is maximised — the maximised state draws no flanks, so this needs no
  second condition.

**`filmstrip_visible` defaults to off.** The screen opens on three photographs and nothing
else; the strip is one tap away in the view cluster and the choice is then remembered. This is
not a way of recovering height — where the strip sits it costs no height, and the earlier
draft of this section rejected default-off for exactly that reason. It is a judgement about
what the screen should say on the first frame of a shoot: the compare-and-cull loop, and
nothing competing with it. The default is pinned by a unit test on
`SettingsRepository.DEFAULT_FILMSTRIP_VISIBLE`, because every instrumented test sets the
preference before asserting and none of them would notice it drifting.

### 7.9 The coach-mark guide (replaces the shortcut sheet)

`?`, the ⌨ glyph in the view cluster (§7.5) and the overflow menu all open one overlay,
which is also shown once at first launch. It replaces a scrolling `ModalBottomSheet` of
`input → effect` rows, and the reason is recorded in `ai/memory/palette.md` (2026-07-31):
*a list of labels is the weakest possible way to explain a control the user can already
see*. Every control on this screen that touches a photograph already carries a permanent
word **and** a permanent mono key cap (§7.5), so that sheet recited what was already legible
while answering none of the questions a new user actually has — what is the sidebar for,
which frame am I deciding on, what are the two beneath it, why is there a block of numbers
beside the picture. Those are spatial questions, so the answer is spatial.

```
┌────────┬──────────────┬──────────────┬────────────┬──┐
│        │ The selector │              │            │  │
│ (real  │ ┃Left edge:  │   CURRENT    │  (control  │st│
│ side-  │ ┃the shoot   │  (reserved,  │   flank:   │ri│
│ bar,   │ ┃Left of the │    empty)    │ reserved,  │p │
│ unla-  │ ┃centre: its │              │   empty)   │  │
│ belled)│ ┃values      ├──────┬───────┤            │  │
│        │ Skip 1/3 Next│ PREV │ NEXT  │            │  │
└────────┴──────────────┴──────┴───────┴────────────┴──┘
```

The filmstrip's callout is the last card, and its column is reserved and left empty — the
strip is 72 dp wide, which is a place to point at, not a place to write. Nothing is reserved along the bottom any more: an overlay that still kept a 76 dp
bottom strip would push every callout up by 76 dp and start labelling the wrong chrome.

- **Dim, do not replace.** Zinc-950 at 72 % over the whole window. The real chrome stays
  visible beneath, because the point is to label *it*.
- **Reserve the real geometry.** The sidebar column, both flanks, all three frame footprints
  and the filmstrip's own column are laid out at the sizes the layout resolved, through the same
  `FrameGeometry.threeUpLayout` solver the layout itself calls. Two copies of that arithmetic
  is an overlay that drifts a dp at a time until it covers the photographs it was drawn to
  point at. The footprints are reserved and left **empty**; every callout lands in the slack.
- **Paged, never scrolled, never on a control.** The callouts live in the **left** flank
  only — over the readout block, which is text, not a control — and the control flank is
  reserved and left empty. The first version wrote its heading, three cards and Got it into
  the control flank, i.e. on top of the controls it was explaining, and still overflowed at
  every tested size (the 2026-10-17 lab: heading over Copy/Move, Got it over the controls,
  cards cut off). Cards are packed in `SelectorTour` order by *measured* height
  (`CoachPaging.pack`); when they do not all fit — already the case at 1480 × 924 — the
  guide pages. A footer at the foot of the same column carries Skip, a `2 / 3` indicator and
  Next, which becomes **Got it** on the last page; each 48 dp tall. Because a card no longer
  stands beside the thing it explains, each opens with a location line generated from
  `TourRegion.location`. A scrolled-out callout is invisible, which is why paging and not a
  scrolling column: if it scrolls it is documenting, not teaching.
- **Nothing is written out.** Rows come from `SelectorGestures` — `SelectorShortcut` for the
  keys, `FrameGesture` for the taps — and unit tests assert the advertised set equals the
  bound set exactly, that no advertised gesture is destructive, and that no callout names a
  glyph. The retired sheet claimed *"tap the ⛱ badge"*; `MaximiseBadge` renders an
  open-in-full arrow pair and always has, which is the 2026-07-31 lesson in one line.
- **Keys split by whether anything can be pointed at.** `C`/`M`, `Del`, `F`, `←`, `→` drive a
  labelled control, so they belong to that control's callout; `1`/`2`/`3`, `Esc` and `?`
  drive nothing visible, so they get a callout of their own.
- Callouts for chrome the user has switched off are omitted — labelling a hidden panel is the
  same defect as inventing a control that does not exist.
- 200 ms fade in, 150 ms out. No slide. `Esc`, a tap on the scrim, Skip or **Got it** closes it;
  the dismissal persists as `selector_tour` in the hint set, so the first-launch showing and
  the on-demand one are the same fact.

### 7.10 First-run action explanations

Three, not seven. This layout labels its controls, so an explanation earns its place only
where the **effect is invisible**:

| Hint | Fires on | Says |
|---|---|---|
| `filing_action` | the first Move or Copy | where the file now is — the configured verb, the configured folder, whether RAW and JPEG were sorted, and the other verb's key. The important one: the photograph left the centre of the screen and nothing said where it went. |
| `delete_undo` | the first Delete | that nothing has touched the disk yet and UNDO has 30 seconds |
| `maximise_frame` | the first maximise | that `Esc` or a second press comes back — nothing on that screen says so |

`scan_scores` is defined and worded but deliberately **not** raised: the bars appear on the
frames and the Legend item appears in the sidebar at the same moment, so a card would explain
what the user is looking at.

- **Every string is derived from the user's current settings** through one pure, Android-free
  object (`SelectorHintText`), mirroring Jackpix's `FirstRunHintText`. The verb, the folder
  name, the sorting clause, the undo window and every key name are read from configuration or
  from the binding declaration. "Keep", "Save" and "Favourite" are prohibited exactly as they
  are on the controls (§7.5), and a unit test asserts they never appear.
- **Placement: the flank, beneath the readout block.** 10 dp radius, `StudioPanel`, 1 dp
  Zinc-700 outline, no shadow. It takes its own space in the flank column rather than
  floating, so it can neither cover a frame nor collide with a control, and it adds **zero**
  height above or below the image region. The bottom centre is unavailable: the snackbar is
  there, confirming the very action the card is explaining.
- Where no flank is wide enough — readouts hidden, or under 240 dp — the card is not drawn
  **and the hint is not spent**, so it appears the next time there is room. The one thing it
  never does is fall back to the image region.
- Fires once, fades out after 9 s or on **Got it**, suppressed while §7.9 is open, and
  brought back by *Reset guidance* (§14).

### Do not

Shrink or dim the neighbour images · put **anything** in the vertical stack above or below
the image region — no top app bar, no bottom action row, no horizontal filmstrip, no caption
row under a frame · overlay arrows, buttons or gradients on the photographs (the maximise
badge sits on the tile outline, not the image) · ship an unlabelled control that moves or
deletes a file · use drop shadows · round image corners beyond 4 dp · bind any file action to
a swipe.

**The test for any future addition to this screen:** does it consume height? If yes, it
takes 2 dp off every frame for every 3 dp it occupies, and it needs a better justification
than "it fits". If it consumes only width, up to the 124 dp of per-frame slack, it is free.

---

## 8. Screen: Selector — retired layouts

**There is now one comparison layout: §7.** The two that existed before are retired, and the
layout toggle, the `Space` shortcut that drove it and the `selector_layout_focused` key go
with them.

| Retired | Why |
|---|---|
| **Three in a row** | Width-bound at 493 × 370 dp; wastes 554 dp of height by construction (§7.2). It cannot be fixed by tuning — only by rearranging, which is what §7 is. |
| **Stacked "focused"** | Correct arrangement, wrong budget: it paid a 44 dp app bar, a 76 dp filmstrip and two 56 dp rails out of the *height*, landing at 524 × 393 dp. §7 keeps its geometry, moves the chrome into the horizontal slack, and reaches 600 × 450 dp. |

§7 is the focused layout with its budget corrected. Everything below in this file that refers
to "the two comparison layouts", "the layout toggle" or "the focused layout" means §7.

### Preserved from the retired focused layout

The reasoning worth keeping, all of it now folded into §7:

- Controls belong in the letterbox columns, not above or below the photographs
  (2026-07-24, `ai/memory/palette.md`). §7.2 generalises this: controls belong wherever the
  binding constraint is *not*.
- Equal frame sizing is non-negotiable, marked only by a 2 dp accent border — never by
  dimming, scrimming, shrinking or blurring a neighbour (§7.2).
- A control shared between layouts must be owned by the layout that renders it
  (2026-07-24). With one layout this class of overlap bug ceases to exist; the UI test
  asserting no two controls' bounds intersect is kept anyway.

### Empty and intermediate states

| State | Design |
|---|---|
| No folder | Centred card: camera icon, **"Select a folder"**, *"Select a folder to start reviewing and culling your photos."*, primary `[Open folder]`. There is no second source: SAF already mounts Drive, OneDrive and the rest as directories. |
| Folder open, unscanned | Frames visible; the readout block shows one dashed **"Not scanned"** row and the neighbour overlays are suppressed entirely; the sidebar's Scan item emphasised. |
| Scanning | Sidebar counter plus the 2 dp determinate line; scores populate progressively, no blocker. |
| Folder empty | Centred card: **"No photos here"** with `[Open folder]`. |

---

## 9. Screen: Fullscreen viewer

**Frame:** 1480 × 924 dp landscape tablet.

Pure black or Zinc-900 canvas, image fitted to the window. Chrome auto-hides after 3 seconds
of inactivity and returns on tap or pointer movement.

**When chrome is visible:**

- **Top bar** — close (`X`), filename, `127 / 842`, and a single-line EXIF summary
  (`1/250s | f/2.8 | 35mm | ISO 400`). Render as a minimal Zinc-900 bar at ~90% opacity —
  a bar, not a gradient scrim.
- **Bottom** — only when *Show Fullscreen Action Buttons* is enabled in Settings:
  `[Move to Selection] [Copy to Selection] [Delete]`, ≥ 48 dp, centred.
- **Score chip row** — bottom-left, compact variant.

**Gestures**, communicated through a dismissible one-time hint card:

| Gesture | Action |
|---|---|
| Pinch | Zoom in / out |
| Double-tap | Toggle fit ↔ 100% |
| Horizontal swipe | Previous / next image |
| Swipe down | Dismiss |
| `Esc` | Exit fullscreen |
| `← / →` | Previous / next image |
| `M` / `C` / `Del` | Move / copy / delete the current frame |

When zoomed beyond fit, show a small navigator thumbnail in the bottom-right corner
indicating which part of the frame is visible.

### 9.1 The hint card must be generated, not written

The shipped hint card claimed *swipe ← → navigate* while a leftward swipe past −200 px
**deleted the photograph**, a rightward swipe dismissed the viewer, and navigation was a
`VerticalPager`. Three of the five lines on that card were false, and the one destructive
action in the viewer was bound to the gesture the card described as harmless.

The card is therefore not allowed to contain literal strings. Its rows are rendered from the
same `SelectorGestures` object (§7.6) that binds the gestures, and a unit test asserts that
every row the card can render corresponds to a binding that exists. A hint card is the one
place in a UI where a plausible-sounding lie survives review indefinitely — see the
2026-07-31 lesson in `ai/memory/palette.md`.

Concretely, the viewer's gesture layer is rebuilt to match this table:

- Navigation is a **`HorizontalPager`**, not a `VerticalPager`.
- **Vertical drag down past the threshold dismisses**; there is no vertical paging.
- **There is no swipe-to-delete.** Delete is the labelled bottom-bar button, the `Del` key,
  or the context menu — all three of which confirm first.
- Double-tap toggles fit ↔ 100 % and **only** that. The dead outer `onDoubleTap` handler
  that filed the frame to the Selection (shadowed by the zoom handler beneath it, so it
  never fired) is removed rather than revived: a filing action must not share a gesture with
  a view action.
- `fullscreenGestureAction` (the move-vs-copy setting) now configures the bottom-bar button
  and the context menu, and the hint card's wording is derived from it — never hard-coded.

---

## 10. Surface: Scan configuration

Rendered as a **right-side sheet, 400 dp wide** on tablet and DeX — not a centred modal, so
the user can still see the photos. On Compact, a bottom sheet. Zinc-800 surface, 12 dp radius.

**Title:** `Scan Configuration`

**Section — Analysis Types.** Material 3 switches, each with a name and a one-line
plain-language explanation:

| Switch | Explanation |
|---|---|
| Sharpness | Detect focus quality via Laplacian variance |
| Noise Level | Estimate sensor noise via median absolute deviation |
| Highlight Clipping | Detect overexposed (blown-out) areas |
| Shadow Clipping | Detect underexposed (crushed black) areas |

**Estimated duration line**, updating live with the selected options:
*"842 images · about 3 min · 612 already cached and will be skipped."*

**Footer:** `[Cancel]` (text) and `[Start Scan]` (filled accent).

---

## 11. Surface: Score legend

Opened from the info icon in the Selector app bar — shown once any image has scores.

**Title:** `What the scan icons mean`

One row per metric: the actual chip as it appears in the UI, the full display name, a
direction badge, and the plain-language description from [§6.3](#63-quality-scores).

Close with a small worked example — three chips side by side with their direction bars,
captioned *"Compare the bars, not the numbers."*

---

## 12. Screen: Statistics

**Frame:** 1480 × 924 dp landscape tablet. EXIF distribution charts for the loaded folder.

**Header summary strip** — four stat tiles:

| Tile | Example value |
|---|---|
| Images | `842` |
| Lenses | `4` |
| Focal Range | `24mm - 200mm` |
| (one more, your choice, derived from the data below) | — |

**Chart cards** — responsive grid, 2–3 per row on Expanded, 1 per row on Compact:

- Focal Length Distribution (histogram)
- Aperture Distribution (histogram)
- ISO Distribution (histogram)
- Shutter Speed Distribution (histogram)
- Lens Usage (horizontal bar / ranked list)

**Chart styling:** lavender accent bars, Zinc-400 axis labels, Zinc-700 grid lines, Zinc-800 card
background, 1 dp Zinc-700 border, 8 dp radius, title in titleSmall. Omit legends where a
single series makes them redundant.

**Empty state:** **"No Folder Selected"** / *"Select a folder containing photos to view EXIF
statistics."* / `[Select Folder]`.

---

## 13. Screen: Duplicate finder

**Frame:** 1480 × 924 dp landscape tablet. Finds byte-identical files by SHA-256 hash and
lets the user batch-delete copies.

**States:**

| State | Design |
|---|---|
| No folder | **"No Folder Selected"** / *"Select a folder to scan for duplicate images."* / `[Select Folder]` |
| Ready | **"Ready to Scan"**, folder name, `[Start Scan]` |
| Scanning | Determinate progress with file counter and `[Cancel]` |
| Results | See below |

**Results** — a vertical list of duplicate **groups**, each a card:

- **Header:** `Group (3 files)` plus a `Select All But First` text action.
- **Body:** a horizontal row of thumbnails, one per file. The first file carries an
  `Original` badge with an accent outline. Files marked for deletion get a red-tinted border,
  a checked checkbox, and a *"Selected for deletion"* caption. Below each thumbnail: filename,
  file size, and the folder path, ellipsised from the **left** so the leaf folder stays visible.

**Persistent bottom action bar**, shown whenever anything is selected:

```
12 selected  ....................................  [Delete Selected (12)]
```

**Confirmation dialog:** **"Delete Selected Files"** / *"Delete 12 file(s)? This action
cannot be undone."* / `[Cancel]` `[Delete]`.

---

## 14. Screen: Settings

**Frame:** 1480 × 924 dp landscape tablet. Two-pane on Expanded — section list on the left
(280 dp), section content on the right. Single scrolling list on Compact.

Labels below are final — use them verbatim.

### Storage

| Item | Control | Supporting text |
|---|---|---|
| Selection Subfolder Name | editable text, value `Selection` | Created inside the source folder by default |
| Custom Selection Location | folder picker | `Default: subfolder in source folder` → `Using custom folder` when set |
| File Sorting | switch | Sort RAW and JPEG files into separate subfolders |

### Analysis

| Item | Control | Supporting text |
|---|---|---|
| Analysis Threads | slider 1–4, value shown numerically | — |
| Group Similar Series | switch | Group photos shot in close succession |
| Grouping Level | segmented control, 3 options | see below |

Grouping Level options:

- **Time & Filename** — Group by capture time and filename similarity
- **Time + Fast Similarity** — Group by time with fast perceptual hash comparison
- **Detailed Similarity** — Full similarity analysis (slower, more accurate)

### Fullscreen Viewer

| Item | Control | Supporting text |
|---|---|---|
| Filing Action | choice: `Move to Selection` / `Copy to Selection` | Which action the fullscreen filing button performs |
| Show Fullscreen Action Buttons | switch | Display delete, copy, and move buttons in fullscreen mode |

Renamed from *Double-Tap Gesture Action*: double-tap no longer files a photograph (§9.1), so
a setting named after that gesture describes something the app does not do. The persisted
key is unchanged.

### Guidance

| Item | Control | Supporting text |
|---|---|---|
| Reset Guidance | action + accent tonal `[Reset]` | Show the first-run explanations and the layout guide again |

Follows the Clear Cache row without its confirmation dialog: nothing is destroyed, so a modal
asking *are you sure* would be ceremony. The supporting text changes to *"The explanations and
the guide will show again"* once tapped, because an action with no visible effect reads as a
control that does nothing. Clears the whole `seen_first_run_hints` set **and** the two older
one-shot flags (`has_seen_nav_hint`, `seen_fullscreen_gesture_hint`), which predate it and are
stored separately — a reset that leaves two hints permanently dismissed is not a reset. After
it, §7.9 opens by itself again and §7.10's cards fire again.

### Cache

| Item | Control | Supporting text |
|---|---|---|
| Cached Scores | read-only | `1,204 analysis results stored` |
| Clear Cache | destructive action + confirm dialog | Remove all cached analysis scores |

### About

Frameduel · `Version 1.4.2` · Source Code — *View on GitHub*.

### Style

Zinc-800 grouped cards on a Zinc-900 canvas, 1 dp Zinc-700 borders. Section headers in
labelLarge lavender accent; item titles bodyLarge Zinc-50; supporting text bodySmall Zinc-400;
controls right-aligned. Destructive items use the red accent for their icon and label only —
never a red-filled button.

---

## 15. Breakpoint adaptations

### 15.1 Medium — 600–840 dp, tablet portrait

Reference frame: 720 × 1100 dp portrait.

Portrait flips which axis is scarce, so the §7.2 solver — not a hand-tuned variant — decides
the arrangement. At 720 × 1100 dp, one over two gives `h ≤ (1100 − chrome)/2 ≈ 520`, but
`2w ≤ 720 → w ≤ 356 → h = 267`; **width is binding again**. So:

- The frames stack **one over two** still, but the readout and control blocks can no longer
  sit beside the current frame. They move **below** the frames, which is affordable here
  precisely because height is now the surplus axis. The rule is unchanged: chrome goes where
  the binding constraint is not. The neighbour overlays (§7.4b) stay on their frames — they
  cost no layout space in either orientation, which is the point of them.
- The §7.1 sidebar collapses to a 56 dp icon rail with tooltips, because width is scarce at
  this breakpoint. The labels return the moment the window is ≥ 840 dp.
- The readout block drops the EXIF line and keeps icon + name + value + bar.
- The control block becomes a 72 dp row: the three verbs centred, Previous/Next at the edges,
  view controls right-aligned. Labels are never dropped; the buttons narrow instead.
- The filmstrip may return to a horizontal scrolling row at the bottom edge, because in
  portrait height is the surplus axis and the flanks are what has run out. Same rule,
  opposite answer. **Not yet implemented:** the solver currently drops the strip on a window
  this narrow rather than drawing it over the view toggles, which is the safe half of the
  behaviour but not the whole of it.

### 15.2 Compact — < 600 dp, phone

Reference frame: 400 × 880 dp portrait.

- **BottomNavigation** replaces the NavigationRail: Selector, Statistics, Duplicates, Settings.
- Single full-width swipeable image viewer (horizontal pager). **The comparison layouts are
  omitted at this width.**
- **Horizontal swipe navigates, and nothing else.** The `SwipeToDismissBox` that deleted the
  photograph on a leftward swipe is removed: the same gesture cannot mean "next frame" in
  fullscreen and "destroy this file" here (§7.6).
- Below the image: single-line filename, then a horizontally scrollable score chip row
  (compact chips — icon + value, labels dropped).
- Detailed EXIF sits behind a tap-to-expand row, not always visible.
- Three always-visible quick actions: `[Keep]` `[Move]` `[Delete]`, each ≥ 56 dp tall with
  its word visible — the same three verbs, in the same order, as §7.5.
- Statistics: single-column scrollable charts.
- Duplicates: compact grid with checkboxes instead of the detailed group rows.

---

## 16. Component sheet

Produce these as a design-system page, dark theme, neutral grey / lavender.

1. **ScoreChip** — four variants (Sharp, Noise, Highl, Shad) × three states (good,
   neutral, poor) × two sizes (full = icon + label + value; compact = icon + value for
   thumbnail overlays). Show the direction-bar treatment in every variant.
2. **ScoreChipRow** — wrapping row of up to four chips at column width ~450 dp.
3. **ImageTile** — 4:3 tile with letterbox and pillarbox behaviour. States: resting,
   current/active (2 dp accent border), hovered (Zinc-600 border), loading (Zinc-800 with a
   subtle shimmer — no spinner).
4. **Sidebar item** — 88 × 72 dp, 24 dp glyph over an 11 sp label; resting, hovered, active
   (`BrandAccentNav` + accent glyph and label), disabled, and the Scan item's emphasised and
   scanning variants.
5. **Control block** — 388 × 450 dp, 2 × 3 grid of ~190 × 80 dp controls with permanent glyph
   + word + key cap: Keep (`ScoreGood` tonal), Move (`BrandAccentTonal`), Delete (outlined,
   `ScoreBad` glyph and word), Fullscreen, Previous, Next; disabled states (`No previous` /
   `No next`); the 48 dp view-toggle row and the position counter beneath.
6. **Readout block** — 388 × 450 dp: filename, EXIF line, four icon + name + value rows with
   goodness bars and the best-of-three dot, the "Not scanned" row, the embedded filmstrip.
7. **Value overlay** — 148 dp neighbour column: filename, two-line exposure, rule, four icon
   + value + bar rows. Both the outside-the-frame and overlaid-on-the-frame placements, the
   fallback-EXIF amber marker, the unscanned variant, and the suppressed state.
8. **Maximise badge** — 48 dp `⛶` on a tile's outline: bottom-right on the current frame,
   bottom-left on Previous and Next so it clears the value overlay. Resting and active.
9. **Filmstrip thumbnail** — resting, current, and in-burst states, 60 dp wide (40 dp tall
   landscape, 80 dp portrait), with the leading-edge burst marker and the sharpness dot.
10. **Snackbar with Undo** — `Moved to «folder»` / `Copied to «folder»` / `1 image deleted`,
    each with a 30 s Undo action and a thin countdown line. «folder» is the configured
    Selection folder name, so mock the longest name the setting allows, not just `Selection`.
11. **Empty-state card** — icon, title, one-line body, primary + secondary button.
12. **Confirmation dialog** — standard and destructive variants.
13. **Metadata panel** — labelled key/value rows for Shutter Speed, Aperture, Focal Length,
    35mm Equiv., ISO, Lens; the `Unknown` treatment for missing fields; a "limited metadata"
    marker for fallback reads.
14. **Key cap** — the permanent mono key-hint style carried by every action control.
15. **Context menu** — long-press menu with Move, Copy, Delete, Details, Fullscreen.
16. **Fullscreen gesture card** — rendered from `SelectorGestures`, never from literals.
17. **Coach mark** — the §7.9 callout: 1 dp Zinc-700 outline on `StudioPanel`, 10 dp radius,
    a 10 dp lavender accent leader rule before the title, title over one line of body, optional
    generated key rows beneath. Full and compact (sidebar / filmstrip) variants.
18. **Explanation card** — the §7.10 first-run card: title, two lines of body, right-aligned
    **Got it**. Show it at flank width with the readout block above it, and the suppressed
    variant.

---

## 17. Accessibility

- **Contrast.** All text ≥ 4.5:1 against its surface. Zinc-400 (`#A1A1A1`) on Zinc-900
  passes; Zinc-500 does not. Do not go darker than `#A1A1A1` for any body text.
- **Direction is not colour-only.** Score direction is conveyed by bar length *and* the
  written direction hint, not by the emerald/amber/red tint alone.
- **Icon-only controls** need a visible tooltip on hover and a full accessibility label
  carrying name, value and direction — e.g. *"Sharpness 512.3, higher is better."*
- **A control that changes a file is never icon-only.** Move, Copy/Keep and Delete carry a
  permanent visible word at every breakpoint, in every layout, including the compact one.
  Icon-only is permitted only for view controls, which cannot lose a photograph.
- **Focus rings.** 2 dp lavender accent with a 2 dp offset, visible on every interactive element,
  for keyboard and DeX navigation.
- **Touch targets.** Minimum 48 dp for every interactive element, everywhere, at every
  breakpoint.
- **No hover-dependent interactions.** Hover may reveal affordances (cursors, key hints,
  tooltips) but must never be the only route to a function.

---

## 18. Keyboard and pointer

Active whenever a hardware keyboard is present — DeX, Chromebook, tablet with keyboard case.
Shortcuts work in both the standard and fullscreen views.

| Key | Action |
|---|---|
| `←` / `→` | Previous / next image |
| `Delete` / `Backspace` | Delete (with confirmation) |
| `M` | Move to Selection |
| `C` | Copy to Selection |
| `F` | Enter fullscreen |
| `Esc` | Exit fullscreen / leave maximised / close sheet or menu |
| `1` / `2` / `3` | Maximise Previous / Current / Next in place (§7.3) |
| `?` | Open the coach-mark guide (§7.9) |

Shortcuts are suppressed while any sheet, dialog or the guide is open, **except `Esc`**. Every
one of these bindings is rendered as a permanent key cap on the control it drives, and the `?`
guide is generated from the same binding table — a shortcut list that can disagree with the
bindings will eventually disagree with the bindings.

**Pointer.** Interactive components show the hand cursor (`PointerIcon.Hand`) on hover —
buttons, clickable images, filmstrip thumbnails. Dragging a folder from an external file
manager into the app window loads it; design a drop-target state (accent dashed outline over
the content area, caption *"Drop a folder to load it"*).

---

## 19. Motion

- Duration 150–200 ms, standard easing.
- **Image transitions crossfade only.** No slide, no scale, no parallax. The user is judging
  sharpness — movement lies about it.
- Panel and rail toggles: fade plus a small size change, never a bounce.
- Progress is always determinate where a total is known.
- Respect the system reduced-motion setting: fall back to instant swaps.

---

## 20. Using this file with a design tool

**Google Stitch.** Paste sections 1–6 once to set context, then paste one screen section
(7–15) per generation. Stitch works best with a single screen plus explicit device framing —
each screen section opens with its frame size.

**Claude Design or any conversational tool.** Paste sections 1–6 as the first message, then
work through screens 7–15 in order, starting with §7 (the hero) and iterating on it before
moving on.

**Human designer.** Read start to finish; §7 and §8 are where the product lives.

### Iteration prompts

Short corrections to steer a generated design back on track.

| Problem in output | Correction |
|---|---|
| Controls floating on the photo | "No *control* may sit on a photograph. Move every button into the control block right of the current frame. The only thing drawn on a frame is its own read-only value overlay." |
| Values that could belong to any frame | "Each frame's data must touch that frame: filename, exposure and named metrics beside the current frame; filename, exposure and icon-and-value metrics overlaid on the right edge of Previous and Next." |
| Neighbours missing their EXIF | "Previous and Next carry filename and a two-line exposure block above their metric rows, in the same order as the current frame's readout." |
| Current frame not centred | "Centre the current frame in the image region, readouts left, controls right, Previous and Next centred as a pair beneath it." |
| Current image enlarged | "All three images must be identical in size. The active image is marked only by a 2 dp accent border." |
| Neighbours dimmed or scrimmed | "Render Previous and Next at full brightness. Dimming defeats the comparison." |
| Three images in a row | "Never arrange the three frames in a row — that is width-bound and wastes 60 % of the display's height. One frame on top, two below, all three 600 × 450 dp." |
| Images too small | "Height is the scarce axis. Delete everything above and below the image region — app bar, action row, filmstrip, captions — and move it into the free width beside the frames. Then measure the region, don't reason about it." |
| An app bar, bottom bar or horizontal filmstrip | "Nothing may occupy the vertical stack. Every 3 dp of height costs 2 dp of height on all three frames. Chrome on this screen is a column, not a bar." |
| A column down the outer edge of the image region | "Only the flanks are surplus width. A column that spans both rows is priced against the bottom row, which has 18 dp to give at 3:2." |
| Consumer-app styling | "This is a professional tool, closer to Lightroom's Library module than Google Photos. Remove gradients, large radii, drop shadows, and decorative colour." |
| Bare numeric scores | "Every score is icon + short label + value, with a direction bar. Never a bare number." |
| Light theme or coloured background | "Dark theme only. Canvas #18181B, panels #27272A. Nothing may colour-cast the photographs." |

---

## Related documents

- `REQUIREMENTS.md` (maintainer-private) §7 — Android application requirements (authoritative)
- [`ARCHITECTURE.md`](ARCHITECTURE.md) — this product's layers, adaptive layout strategy and theme implementation
- `DESIGN_PROMPT.md` (maintainer-private) — the same material packaged as copy-paste prompt blocks
- `REQUIREMENTS.md` (maintainer-private) — what this product must do
- [`../../shared/ANDROID_PLATFORM.md`](../../shared/ANDROID_PLATFORM.md) — the Android baseline shared with Jackpix
- `../../../ai/ROUTING.md` (maintainer-private agent framework) — agent roster and delegation rules

> **Keeping this in sync:** if a design decision here changes app behaviour, update
> `REQUIREMENTS.md` §7 in the same change — it remains the single source of truth.
