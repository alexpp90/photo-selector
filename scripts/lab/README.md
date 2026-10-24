# E2E Device Lab (`scripts/lab`)

Local device lab for automated multi-step UI journeys, deterministic layout & filesystem oracles, and AI visual review across Jackpix, Frameduel, Lensjet and Blitzlens. It runs on demand on the Linux hub; it is **not** a CI gate.

## Run it

```bash
# What is reachable right now (add --with-personal to probe personal devices too)
python3 scripts/lab/lab.py devices

# Everything, on the dedicated devices (builds the .lab APKs, installs, runs, writes the report)
python3 scripts/lab/lab.py run --all

# Everything, including personal devices (your phone, tablet, Mac)
python3 scripts/lab/lab.py run --all --with-personal

# One product / journey / config / device
python3 scripts/lab/lab.py run --product android-desktop --journey J-AD-02 --config tab-landscape --device pixel-tablet
python3 scripts/lab/lab.py run --product phototok --no-build        # reuse this tree's last .lab build
```

`--no-build` skips Gradle, not the check: the last build writes `lab-build.json` next to each
APK (a fingerprint of `products/android` as it is on disk, without the modules' `tests/` that
hold these flows, and the APK's hash). The run refuses when the tree changed since that build, and installs that build on a
device whose installed `base.apk` hashes differently.

**One run at a time per hub.** A run holds `<lab tmp root>/android-hub.lock` while it drives
Android devices; a second `lab.py run` waits for it (up to 3 h, then its Android journeys are
*not run*, naming the holder). Separate devices are not separate runs: every Maestro and adb call
goes through the one adb server. Each device is also driven under
`<lab tmp root>/device-<serial>.lock` (wireless transports keyed by the phone's `ro.serialno`, so
USB and Wi-Fi debugging of one phone share it), the lock instrumented test runs and usability walks take;
a device still held after 15 min is *not run*.

The run directory (`private/lab-runs/<date>-<time>-<sha>/`, or `/tmp/lab-runs/` without the
private overlay) holds `report.html`, `report.md`, `run.json`, `findings.det.json` and one
folder per `<product>/<config>@<device>/<journey>/` with every checkpoint's screenshot, UI tree,
file-system snapshot, timing and the Maestro logs.

## Devices

Devices are listed in the maintainer-private `private/lab/devices.yaml`, keyed by hardware
serial (wireless-debugging ports change on every reconnect). Each Android product fans out over
every selected, reachable device; results are labelled `<config>@<device>`. Devices marked
`personal: true` run only with `--with-personal` or when named with `--device`.

Pair a device once (Settings → Developer options → Wireless debugging → *Pair device with
pairing code*, keep the dialog open):

```bash
python3 scripts/lab/lab.py pair 123456                   # address found over mDNS
python3 scripts/lab/lab.py pair 123456 --device s25-ultra # several dialogs open at once
```

A device that pairs but never advertises its connect port over mDNS (seen: Pixel 4a) gets a
`host: <ip>` entry; the lab then finds the port with `nmap`. Reserve that IP in the router.

What a run changes on a device, and restores afterwards: display size and density, font scale,
dark mode, rotation, stay-awake and Do Not Disturb (on while a journey runs, so no heads-up
notification lands in a screenshot). The lab never switches Samsung DeX. Lab data lives only in
`/sdcard/PSTLab/` (fixtures in `work/`, the UI dump `pst_dump.xml`, removed after each pull);
the `.lab` build (`com.phototok.lab`, `com.photoselectortoolbox.lab`) is separate from any store
install.

- **One working folder.** Every journey's fixture is pushed into `/sdcard/PSTLab/work`, so one
  persisted folder grant serves all journeys. Before each journey that is not a fresh install,
  `products/android/<product>/tests/e2e/_ensure_folder.yaml` grants that folder if the app has
  none and brings the app to a photo.
- **Device preconditions.** A journey that needs device state the lab must not create lists it
  as `requires_dirs` (J-TOK-06 needs `/sdcard/DCIM/Camera`). A device without one of those
  directories reports the journey *not run*, never broken; the check only reads.
- **Locale.** Picker flows use `android:id/button1`, not button text, so devices in any
  language work.
- **Rotation.** A docked Pixel Tablet ignores rotation requests; its portrait configs are
  reported *not run*. Android resets the rotation whenever a portrait-only launcher comes to the
  front (the app's force-stop before each journey, a `background` step), so the runner sets it
  again after every launch and checks it at every checkpoint: an app that still shows the wrong
  orientation ends the journey as *error* ("config … not applied"), never as a pass.
- **Checkpoints.** Before each one the notification shade is collapsed and the UI tree is dumped
  first; if another app's window is in front (the shade, with other apps' notifications), the
  journey ends as *error* and no screenshot is taken.
- **Bindings.** A journey can name the photo the app actually showed instead of assuming a key
  count: `bind: {moved: {kept: column_current}}` reads the first file name in that node (or its
  children) at checkpoint `moved`, and `fs_expect` patterns use it as `{kept}` (J-AD-02,
  J-TOK-03, J-TOK-04, J-TOK-05). Jackpix resumes the shared folder where the last journey left
  it, so its journeys bind every photo they name.
- **"Device … not connected".** Maestro opens every transport of the adb server to list devices,
  and one that is not `device` (a Chromebook gone to sleep, a USB phone re-enumerating) makes it
  report every device as not connected. Before retrying, the runner disconnects such network
  transports and waits up to 2 min for USB ones; the Maestro log says what it did.
- **DeX.** The lab never switches Samsung DeX: on One UI 8 the DeX tile is a toggle and DeX
  is only visible in how apps open (freeform desktop windows), so a wrong reading would flip
  it the wrong way. `dex-on-device` (devices with `supports: [dex]`) runs when the app opens in
  a desktop window; every other tablet config runs when it opens fullscreen; the other kind is
  reported *not run* with the instruction. For both modes: run with DeX off, switch DeX on by
  hand, run again with `--config dex-on-device`.
- **Chromebook (ChromeOS ARC).** Enable Settings → Developers → Linux development environment →
  Develop Android apps → ADB debugging, approve the prompt once; the lab finds it as
  `_adb._tcp` on port 5555. Only the `chromebook-*` configs run there (devices with
  `supports: [arc]`).
- **Window sizes.** Freeform windows (ChromeOS, DeX) are resized with `am task resize`:
  `window: <W>x<H>` (dp) or `max` in a config applies right after launch
  (`chromebook-large/medium/small`), and a `{resize: <W>x<H>}` journey step does it mid-session
  (J-AD-06 drags the window across the layout breakpoints).

## macOS (remote, `pst-lab` account)

The hub triggers the Mac over SSH; a LaunchAgent in the `pst-lab` user's GUI session builds the
app and runs the macOS journeys, and the hub syncs the results back. `pst-lab` only has to be
logged in (fast user switching, in the background); the maintainer keeps working in their own
account. The Mac's run id is the hub run's name plus the trigger time, so a re-used `--out` name
never reads an earlier run's results; `agent_run.sh` re-runs itself from the checked-out commit,
so a change to it takes effect on the first run. One-time setup on the Mac, as `pst-lab`: `scripts/lab/macos/setup_pst_lab.sh` (its
header lists the prerequisites: Remote Login allowed for `pst-lab`, the hub's public key in its
`~/.ssh/authorized_keys`, the Command Line Tools, at least 4 GB free).

## Weekly timer (Linux hub)

```bash
python3 scripts/lab/lab.py install-timer            # Sunday 03:00, dedicated devices only
python3 scripts/lab/lab.py install-timer --dry-run  # print the units instead
```

The units are written for the checkout you run this from, with the current `PATH` (adb,
Maestro), and pull `main` before each run.

## Linux Desktop journeys

`products/linux-desktop/tests/e2e/session.sh` runs each journey in a private Xvfb (on a free
display, picked with `-displayfd`) and D-Bus session; the runner writes the step script.
Every checkpoint is bracketed by two hub steps (`lab/linux/hub_step.py`): `await` polls the
fixture folder and the lab Trash until the checkpoint's `fs_expect` holds on a folder that has
stayed unchanged for 1 s (budget 10 s, `fs_timeout_ms` per journey), and `fs` saves that
checkpoint's own snapshot. A file operation that lands after more than 2 s is reported as
`fs-settle-slow`. The window fills the screen of the config; text scaling reaches GTK through
`gtk-font-name` in the session's `settings.ini` (GTK on X11 ignores `gtk-xft-dpi` without an
XSETTINGS manager). There is no `dark` config: the app is always dark.

## Layout rules

`rules/<product>.yaml`: `overlay_ids` (guidance and overlays exempt from the overlap rules),
`frame_ids`, `allowed_overlaps` (by-design `[a, b]` id globs, matched through ancestors),
`guidance_must_not_cover` (controls an exempt overlay must leave free), `frame_size`. Overlaps
use what is visible — a node clipped to its parents — because Android reports Compose's
touch-expanded bounds; a control counts as named when it or a child carries a label. A control
cut at the top or bottom edge of a scroll container (`scrollable` in the dump) is not checked for
height or name: uiautomator reports only its visible part and drops its off-screen children. A
`layout_expect.required` entry may be a list: any one of those ids.

## Host prerequisites

`python3 scripts/lab/lab.py preflight` lists them: `adb`, Maestro (`~/.maestro/bin`), `Xvfb`,
`xdotool` and `dbus-run-session` for Linux journeys, `nmap` for devices without mDNS, and
Debian's `/usr/bin/python3` with PyGObject for the Linux Desktop app (override with
`PST_LAB_PYTHON`). PyYAML and Pillow are optional; built-in fallbacks are used without them.

## Other commands

```bash
python3 scripts/lab/lab.py fixtures --out /tmp/pstlab
python3 scripts/lab/lab.py layout-lint path/to/checkpoint.tree.json --rules scripts/lab/rules/android-desktop.yaml
python3 scripts/lab/lab.py review-bundle private/lab-runs/<run-id>
python3 scripts/lab/lab.py review-ingest private/lab-runs/<run-id>
python3 scripts/lab/lab.py report private/lab-runs/<run-id>
```

## Usability panel (release gate, on demand)

The journeys above check that screens are *correct*. The usability panel asks whether they
are *usable*: four personas per product judge the product's key use cases one screen at a
time and say what they understand, what they would do next, what they would read and what
bothers them. A moderator then compares that with the action the design expects and the
measured text load, and writes the trade-offs for the maintainer to decide. It is opinion by
design, so it runs before a release, not in CI. Products: Jackpix (`phototok`), Frameduel
(`android-desktop`), Lensjet (`macos`) and Blitzlens (`linux`).

```bash
# 1. Walk the scenarios; writes a new private/lab-runs/<sha>-usability-<product>-<variant>/
#    Android (USB device; Jackpix is walked in portrait, Frameduel in landscape):
/usr/bin/python3 scripts/lab/lab.py usability-capture --product phototok --serial <adb-serial>
#    Repeat per setup the personas use; language, font scale and rotation are restored after.
#    Only Jackpix is translated; Frameduel and Blitzlens can vary the text size:
/usr/bin/python3 scripts/lab/lab.py usability-capture --product phototok --serial <adb-serial> --locale de-DE --font-scale 2.0
#    Linux (private Xvfb session on this machine) and macOS (on the Mac, test-hook build):
/usr/bin/python3 scripts/lab/lab.py usability-capture --product linux [--font-scale 1.5]
python3 scripts/lab/lab.py usability-capture --product macos
# Measured text load only (words per screen and per control, no personas); exit 1 when over budget:
python3 scripts/lab/lab.py usability-textload private/lab-runs/<run-id> --variant private/lab-runs/<de-run-id>
# 2. Build the per-screen persona packets and the moderator's input
python3 scripts/lab/lab.py usability-panel private/lab-runs/<run-id> --variant private/lab-runs/<other-run-id>
# 3. Run personas (one call per screen) + moderator, validate, render the report
private/lab/usability/run_panel.sh private/lab-runs/<run-id>
# or step by step: usability-assemble <run> (per-screen replies -> panel/answers/<persona>.json),
# usability-check <run>, usability-report <run>
```

- **Scenarios:** `scripts/lab/usability/<product>.yaml` — plain user actions (`tap`, `swipe`,
  `key`, `back`, `background`, …) and a `screen` step for each screen to review, with the
  user's `intent` and the `expected` action, and the `use_case` (`UC-n` of the use-case
  catalogue) each scenario covers. Every step must be one the product's driver can perform:
  the desktop drivers translate a scenario into the app's own lab script (Lensjet
  `--ui-scenario`: keys from `UITestDriver.Key`, clicks and waits on registered frame ids;
  Blitzlens `session.sh --script`: keys and waits only) and refuse anything else instead of
  skipping it, so a persona is never told about an action that did not happen.
  `test_usability.py` checks every scenario against its driver and Lensjet's sources.
- **Photos:** a generated trip roll of photo-like landscapes with real flaws (blurred,
  underexposed, overexposed, tilted, camera shake, a burst, a pocket shot) and varied EXIF;
  two shots are RAW + JPEG pairs (a real Linear DNG that LibRaw decodes) with an XMP sidecar,
  one has an `-Edit.jpg`. It is regenerated whenever its recipe changes; Android pushes it to
  `/sdcard/PSTLab/usability-trip`, the desktops walk a fresh copy per run, removed afterwards.
  Rendering needs the real Pillow (`/usr/bin/python3` on Debian).
- **What a persona sees:** one packet per screen (`panel/packets/<persona>/NN.md`): the task,
  what the user just did and the screenshot (plus the previous one), with neutral file names —
  never a later screen, the code, the requirements, extracted text, the screen's label or the
  expected action. Personas and prompts live in `private/lab/usability/`.
- **Text load (criterion `text-load`):** per screen the visible words, and per control (button,
  bar item, settings row) its visible words and whether it shows text next to an icon, against
  the budgets in `docs/shared/UI_TEXT.md` (`lab/usability/textload.py`). Spoken labels
  (`content-desc`) are not counted. A five-word screen with a label under every icon is
  flagged; the per-screen word count alone never saw it.
- **Variants:** each persona file names its device setup (`<!-- setup: de-DE@2.0 -->`) and
  reviews the capture made with that language and text size. The moderator sees all of them.
- **Binding requirements:** the moderator, not the personas, gets the maintainer's own
  requirements for the product as a checklist (`panel/requirements.json`) and rules on each one.
- **Output:** `usability-report.html` (self-contained) and `usability-report.md` in the run.
  Discoverability misses are the personas' own discoverability issues (or the moderator's
  list) and explorer dead ends; without a moderator the report invents no options or costs.
- The Android capture takes the device lock, refuses a device on which instrumented tests run
  or another app is in front, and stops when another app takes over mid-walk.
- **Exploration (`usability-explore`, Android only):** a persona agent decides one step at a
  time from the live screen, within a step budget, under the same device lock and fixture; it
  reports cycles, dead ends, hesitations and charter violations. A failed agent call ends the
  run as `error`.

## Safety Guarantees

1. **Path Guard (`lab/paths.py`):** Every host filesystem write or deletion is restricted by `ensure_under()` to `/tmp/pstlab`, `~/PSTLab`, or `private/lab-runs`. Every Android `adb` deletion/push is restricted by `ensure_android_lab_path()` to `/sdcard/PSTLab/`.
2. **`.lab` Build Type:** Lab runs target the `.lab` application ID (`com.phototok.lab`, `com.photoselectortoolbox.lab`), keeping personal photographs, settings and store installs untouched.
3. **No Desktop Disturbance:** Linux journeys run inside a private `Xvfb` + `dbus-run-session`. macOS journeys run in the separate `pst-lab` account.
4. **Personal devices are opt-in** and have every changed setting restored.

## Headless Wayland Evaluation Notes (C-43)

Evaluated options for running the GTK4/Libadwaita journeys under a headless Wayland compositor (`mutter`) in addition to the default deterministic Xvfb + `xdotool` session (`C-41`):

1. **`dogtail >= 2.1.0` (`HermeticSession`):** Boots a bare `mutter` with a virtual monitor and private D-Bus + AT-SPI accessibility bus without requiring a logged-in desktop session. Recommended when testing Wayland-specific fractional scaling (`scale-monitor-framebuffer`), installed in a dedicated venv since Debian 13 packages `dogtail 1.0.x`.
2. **`waydriver` (0.3.x):** Provides headless `mutter` + AT-SPI + PipeWire screenshots and an MCP server, suitable for exploratory sessions (`C-70`), though less mature than Xvfb + `xdotool` for deterministic CI/lab gating.
3. **Plain `mutter --headless --wayland --no-x11 --virtual-monitor 1920x1080`:** Launches cleanly under `dbus-run-session` with `GDK_BACKEND=wayland`, and `lab-checkpoint` (`Gtk.WidgetPaintable` + renderer texture) captures screenshots directly in-process without needing PipeWire; only key injection requires `org.gnome.Mutter.RemoteDesktop` over D-Bus.
