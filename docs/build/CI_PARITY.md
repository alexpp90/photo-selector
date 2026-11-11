# CI Parity — what runs where, and why

**Read this before pushing a branch.** It exists because of a recurring failure
mode: a change is green locally, red in GitHub Actions, and the branch then
accumulates a chain of blind `fix(ci)` / `fix(test)` commits, each one a guess
validated only by a 15-minute CI round trip.

The rule that prevents it:

> **Every gate CI enforces must be reproducible locally, or explicitly
> documented as un-reproducible with the reason why.**
> `scripts/run_tests.sh` is the mirror. It reports each gate as
> PASS / FAIL / **SKIPPED**, and a SKIPPED gate is a known risk you are
> accepting, not an absence of risk.

```bash
./scripts/run_tests.sh            # auto-detects changed products (mirrors CI paths: filters)
./scripts/run_tests.sh --all      # run everything unconditionally
./scripts/run_tests.sh --python   # Python gates only
./scripts/run_tests.sh --android  # Android gates only
./scripts/run_tests.sh --macos    # macOS Desktop native Swift gates
./scripts/run_tests.sh --linux    # Linux Desktop native gates
./scripts/run_tests.sh --strict   # non-zero exit if any unrunnable gate had to be skipped
```

---

## Gate matrix

| Gate | CI job | Local | Notes |
|---|---|---|---|
| flake8 `src/ tests/` | `desktop.yml:lint` | ✅ in the provisioned [Python environment](#python-gates-run-in-the-projects-own-environment) | **Gates the entire desktop pipeline.** A lint error fails the run before one test executes. |
| pytest `-m "not visual"` | `desktop.yml:test` | ✅ everywhere | Same marker filter locally and in CI. CI and the mirror both pass `-o faulthandler_timeout=120` (also to the visual job), so a test that blocks in Tk or native code fails with every thread's stack instead of hanging. On Linux the runner renders it on its own Xvfb, as CI does — see [GUI gates run on Xvfb](#gui-gates-run-on-xvfb-not-your-screen). |
| Coverage `--cov-fail-under=60` | `desktop.yml:test` (**Linux only**) | ✅ advisory everywhere | See [Coverage is Linux-authoritative](#coverage-is-linux-authoritative). |
| Visual regression | `desktop.yml:visual` | ⚠️ Linux + Xvfb on CI's `ubuntu-24.04` image; SKIPPED elsewhere (baselines are runner-rendered) | See [Visual regression](#visual-regression-linux-only). |
| `testDebugUnitTest --continue` | `android.yml:unit-tests` | ✅ needs JDK 17 + Android SDK | `--continue` in CI and the mirror: a failing module no longer stops Gradle before the other module's tests run, so one run reports every module's failures. Pass/fail is unchanged. |
| `assembleDebugAndroidTest` | `android.yml:unit-tests` | ✅ needs JDK 17 + Android SDK | Compiles `tests/instrumented/`. Nothing else does. |
| `check_native_alignment.py` | `android.yml:unit-tests`, `android.yml:build` | ✅ needs JDK 17 + Android SDK (unit tests run everywhere via `scripts/tests`) | Inspects every `lib/{arm64-v8a,x86_64}/*.so` in `merged_native_libs/debug` and built APK/AAB archives and fails if any ELF `PT_LOAD` segment has `p_align < 16384` (Google Play 16 KB page-size mandate). For `.apk` files it also fails if a stored (uncompressed) `.so` does not start at a 16 KB-aligned zip offset (`zipalign -P 16`); bundles are exempt. The mirror builds and checks the debug APKs like the `build` job. |
| `lintDebug` | `android.yml:lint` | ✅ advisory; opt-in locally | All four modules (`:core`, `:saf`, `:android-desktop`, `:phototok`). Non-blocking in both places, on purpose (CI `continue-on-error`, `abortOnError = false`), so it can never fail a run and costs over a minute: the mirror runs it on `--all` and with `PST_ANDROID_LINT=1`, otherwise reports it SKIPPED (not unrunnable, so `--strict` is unaffected). |
| `assembleLab` (R8-minified) | `android.yml:minified-build` | ✅ needs JDK 17 + Android SDK | Builds `:android-desktop` and `:phototok` as `lab` (`initWith(release)`: R8 minify + resource shrinking, debug signing, no secrets), so a missing `-keep` rule fails before the merge instead of in `android.yml:build` on `main`. Skipped under `--quick`. |
| `connectedDebugAndroidTest` (**partial — compact window**) | `android.yml:instrumented-tests` | ❌ **needs emulator/device** | **A green CI job is partial evidence:** CI's emulator has a compact window, and 28 expanded-layout checks in `:android-desktop` (`if (isCompactLayout()) return`, e.g. `SelectorScreenTest`) pass vacuously there; `SelectorForcedSizeTest` forces expanded sizes for part of it. Only a device with an expanded window (a tablet) runs them: use `--android-device` on one before a release. The mirror labels the gate `(partial — compact window)`. Local: on demand only (`--android-device`, before a release), on every configured test device at once (`scripts/android_device_matrix.py run --configured`: `pst.testDevice.serial` + `.extraSerials`, or the only attached device), each under its device lock, one gate per device; no device, or a device busy or locked after the wait, is a non-blocking `⊘ SKIPPED`, also under `--strict`. See [Instrumented tests](#instrumented-tests-genuinely-need-a-device). |
| `swift test (macos-desktop)` | `macos.yml:swift-test` | ⚠️ Darwin only | Native Swift 6 suite covering PhotoSelectorKit & App (release SLA benchmark included). Runs on the pinned `macos-26` image in CI. |
| `check_no_test_hooks.sh` (release binary) | `macos.yml:swift-test` | ⚠️ Darwin only | `swift build -c release`, then fails if the binary contains the UI test hooks (`UIScenarioRunner`, `UITestDriver`, `UISelfTestRunner`), which are compiled in only for debug and `build_app.sh --test-hooks` builds. |
| Lensjet universal app bundle | `macos.yml:build` | ⚠️ Darwin only (skipped under `--quick`) | `build_app.sh release --universal` (arm64 + x86_64 merged with `lipo`, ad-hoc signed) followed by `check_no_test_hooks.sh` on the `.app`. The release packaging that publishes it runs only on `main` and `macos-desktop-v*` tags; the mirror runs the same two commands, so a break in `build_app.sh` shows before the merge. Not yet exercised on a Mac by the agent that added it. |
| macOS UI smoke test (running app) | — (local-only) | ⚠️ Darwin + logged-in GUI session | `products/macos-desktop/scripts/ui_smoke_test.sh`: drives the running app (raw binary **and** `.app` bundle) — activation policy, real key events, rendered Focus 3-Up geometry, clicks while zoomed, time-to-first-photo. Window-server key probe needs Accessibility permission, else `⊘ SKIPPED`. See [macOS UI smoke test](#macos-ui-smoke-test-local-only). |
| `linux-desktop lint` | `linux.yml:lint` | ✅ in its own [Python environment](#python-gates-run-in-the-projects-own-environment) | Flake8 static analysis on `products/linux-desktop/` |
| `linux-desktop test` (`--cov-fail-under=70`) | `linux.yml:test` | ⚠️ full only with GTK 4 + GExiv2 typelibs (partial elsewhere) | CI runs it on Python 3.12 and 3.13 (Debian 13 ships 3.13); the mirror uses the interpreter of the Blitzlens venv. Headless unit tests covering metadata, scoring, file transactions, window/dialog controllers, and format conformance with `--cov=photo_selector_linux --cov-fail-under=70`. CI installs the Debian decoding backends (`gir1.2-gexiv2-0.10`, `libraw-bin`, `heif-gdk-pixbuf` + `libheif-plugin-libde265`; deliberately no `webp-pixbuf-loader`, so the Pillow display fallback stays tested) and, with the corpus, fails if any is missing; off Linux the backend-dependent format checks skip with the package to install. Without the real GTK 4 / GExiv2 typelibs (macOS, or a Linux box without them) `conftest.py` stubs `gi`, so the GTK display decode path never runs; the mirror then labels the gate `linux.yml:test (partial — …)` instead of claiming CI parity. Runs under Xvfb on Linux, mirroring `xvfb-run -a` in CI. The CI job has `timeout-minutes: 15` and both CI and the mirror pass `-o faulthandler_timeout=120`, so a test that blocks forever on a real GTK main loop fails with every thread's stack instead of hanging (a preferences-dialog signal feedback loop did exactly that on 2026-10-17 while the Mac gate, which stubs GTK, was green). CI installs `appstream` and `desktop-file-utils` and sets `PST_REQUIRE_PACKAGING_TOOLS=1`, so the AppStream and `.desktop` validation tests fail instead of skipping; `run_tests.sh` sets it when both tools are installed. Each matrix cell restores `~/.cache/pip` and Poetry's download cache (`actions/cache`, keyed on the Python version and `pyproject.toml`); a cache is not a gate and has no local mirror. |
| `debian packaging & apt repo` | `linux.yml:package` | ✅ everywhere (pure-Python builder) | `package_debian.sh`: builds the `.deb`, generates an unsigned APT repository in `products/linux-desktop/dist/apt` and verifies it. |
| APT publish | `linux.yml:publish-apt` | ❌ credentials-bound | On `linux-desktop-v*` tags only (never on a manual dispatch; `scripts/tests/test_workflow_bounds.py` enforces that for every publishing job), after `release.py verify-tag`: downloads the `package` job's verified `.deb` (the bytes the GitHub release ships), indexes and signs (`APT_GPG_PRIVATE_KEY`) and pushes the repository to `alexpp90/apt` (GitHub Pages). |
| Homebrew tap update | `desktop.yml:update-homebrew-taps`, `macos.yml:update-homebrew-taps` | ❌ credentials-bound | Writes formula/cask hashes and versions directly to `alexpp90/homebrew-tap` (`HOMEBREW_TAP_TOKEN`); the main repository holds no Formula/Casks. |
| PyInstaller build | `desktop.yml:build` | ✅ opt-in: `PST_BUILD_GATES=1 ./scripts/run_tests.sh --python` | Needs no credential, only the network (it downloads ExifTool 13.59 from SourceForge) and a few minutes; it also regenerates `THIRDPARTY_NOTICES.txt`. CI runs it on pushes to `main`, `desktop-v*` tags and manual dispatch, never on a pull request, so a change to `build.py`, a dependency or a native import is first built on `main` (where `release` `needs: build`). The mirror reports it `⊘ SKIPPED` unless opted in; run it before merging such a change. |
| Release, Play/Firebase publish | `android.yml:build`+, `macos.yml:build`+`release`, `desktop.yml:release`, `linux.yml:github-release`, `linux.yml:publish-apt` | ❌ credentials-bound | Only runs on pushes to `main` / the product's own release tag (`phototok-v*`, `android-desktop-v*`, `macos-desktop-v*`, `linux-desktop-v*`, `desktop-v*` — see [RELEASING.md](RELEASING.md)). Not reproducible locally by design: it needs signing keys and publishing tokens. The packaging steps in front of it are mirrored where they can be (the Lensjet bundle above, the debug APKs, the PyInstaller build opt-in). The nightly tag move is the last step and best-effort: a refused tag move does not fail a run whose assets are published. Main pushes also move each product group's own rolling tag (`nightly-android`, `nightly-macos-desktop`, legacy `nightly`) to the released commit before updating its pre-release; no two workflows share a tag (`test_workflow_bounds.py`). Third-party actions (everything but `actions/*`, `gradle/actions` and local actions) are pinned to a commit SHA with the tag as a comment; Dependabot's `github-actions` updates bump them. The publishing jobs' `timeout-minutes` each cite a measured duration in a comment (3x the longest observed, at least 10 minutes; `test_workflow_bounds.py` requires the comment); the two Linux tag-only jobs (`publish-apt`, `github-release`) are marked *Not measured* until the first `linux-desktop-v*` tag run. |
| `check_readme_parity.py --check` | `repo-hygiene.yml:hygiene` | ✅ everywhere | Enforces root `README.md` ecosystem completeness, canonical product ordering: Jackpix, Frameduel, Lensjet, Blitzlens, legacy Desktop, asset integrity, and removal of deprecated terms. |
| `release.py verify-tag` | first step of every tag release job (`android.yml:build`, `desktop.yml:release`, `macos.yml:build`+`release`, `linux.yml:publish-apt`+`github-release`) | ✅ `python3 scripts/release.py verify-tag <tag>` | Fails the release before anything is built or published unless the tag's version equals every in-tree version source and the product's changelog has that section. Locally, `release.py tag` checks the same and additionally refuses a tree without a passing `run_tests.sh` record for the product's gates. |
| `release.py check` | `repo-hygiene.yml:hygiene` | ✅ everywhere | Every product's version sources agree, it has a `CHANGELOG.md`, and its workflow triggers on its own tag prefix. `release.py`'s unit tests run in `scripts/tests` below. |
| `check_private_paths.py` | `repo-hygiene.yml:hygiene` | ✅ everywhere | Fails if maintainer-private material (`private/`, `ai/`, `.claude/`, `.agents/`, `.gemini/`, requirement specs, prompts, the prompt ledger) is tracked in this public repository. Also runs as the local pre-commit hook installed by the overlay. |
| Format corpus (`fetch_format_corpus.py`) | `.github/actions/format-corpus`, used by `desktop.yml:test`, `android.yml:unit-tests`, `macos.yml:swift-test`, `linux.yml:test` | ⚠️ once downloaded (~280 MB, network) | Restores the real-camera RAW corpus pinned in `testdata/formats/manifest.json` (cache key = manifest hash) and sets `PST_REQUIRE_FORMAT_CORPUS=1`, so every product's format conformance tests **fail** rather than skip without it. Locally `run_tests.sh` exports the same variables when the corpus is present and reports `⊘ SKIPPED` otherwise. See [`docs/shared/SUPPORTED_FORMATS.md`](../shared/SUPPORTED_FORMATS.md). |
| `check_doc_links.py --check` | `repo-hygiene.yml:hygiene` | ✅ everywhere | Every relative link, image and `#anchor` in the tracked `*.md` and `*.html` resolves (GitHub slug rules, case-sensitive paths). External URLs are not fetched; links into the private overlay are skipped in a public clone. `test_check_doc_links.py` also fails when the docs name a file the APT repository does not serve. |
| `check_ui_text_budget.py` | `repo-hygiene.yml:hygiene` | ✅ everywhere | Visible UI text within the budgets of `docs/shared/UI_TEXT.md` (`scripts/ui_text_budget.json`): string resources per element prefix, EN and `values-de` limits, no sentence punctuation on buttons, no hard-coded Compose text, no visible label next to an icon. Fails for products in `fail` mode; the mirror shows report-only findings as `!` (exit code 3). Its tests run in `scripts/tests`. |
| `fetch_format_corpus.py --validate` + `scripts/tests` | `repo-hygiene.yml:hygiene` | ✅ everywhere | The format contract is well-formed: every required format has a sample with ground-truth EXIF, extensions are unique, committed bitmap fixtures match their pinned SHA-256; unit tests for the fetcher (mirror fallback, hash verification), for `verification_record.py`, `release.py`, `android_test_device.py` and `android_device_matrix.py`. |
| PR verification record (`verification_record.py check`) | `pr-verification.yml:record` | ⚠️ PR description only — advisory | Compares the verification blocks in the PR description (written by `scripts/verification_record.py pr` from the record every `run_tests.sh` run saves) with the PR head tree and the products the PR touches; annotates missing, stale or partial verification and never fails. Not a `run_tests.sh` gate: it judges the PR description, which exists only on GitHub. Local equivalent: `python3 scripts/verification_record.py check --body-file <description.md> --base origin/main`. Its unit tests run in `scripts/tests` above. See [CONTRIBUTING.md § Verification record](../../CONTRIBUTING.md#verification-record). |
| CLA signature check | `cla.yml:cla` | ❌ GitHub only | Asks a first-time contributor to sign the CLA on the pull request and records it on the `cla-signatures` branch. Not a code gate and not a `run_tests.sh` gate: it depends on the PR's author and comments, which exist only on GitHub. |
| `lab tooling tests` (`scripts/lab/tests`) | — (local-only) | ✅ everywhere (needs Pillow + PyYAML) | Runs (~90 s) on `--all`, `--lab`, a pristine tree, or a change under `scripts/lab/`, `products/*/tests/e2e/`, `scripts/android_device_matrix.py` (it imports the lab's device lock), `scripts/run_tests.sh` or the workflows; otherwise reported SKIPPED (not unrunnable). Local-only unit tests (`python3 -m unittest discover -s scripts/lab/tests -q`) covering the device lab's schemas, fixture generator, layout lint, file-system/timing/log oracles, runners, and report builder without touching devices or a GUI. |
| Device lab (`scripts/lab/lab.py`) | — (not a CI gate) | ⚠️ local, on demand, opt-in | Local, on demand, not a CI gate; opt-in; see [`scripts/lab/README.md`](../../scripts/lab/README.md). |
| Private overlay gates (`link_overlay.py --check`, `sync_framework.py --check`, `validate_framework.py`, `gen_gemini_settings.py --check`, `verify_requirements_traceability.py --check --strict`, the brand name guard `check_brand_names.py` (run only when the overlay has it), `findings_history.py --check` (the generated summary counts of the findings history), framework tests) | private repo `framework.yml` | ⚠️ maintainer checkout only | Run by `run_tests.sh` when `private/` is checked out; `⊘ SKIPPED` (not a risk) in a public clone. The private repository's CI checks out this repository and runs the same gates. |

---

## Python gates run in the project's own environment

`products/desktop/` and `products/linux-desktop/` each have a Poetry project, and CI installs
each with `poetry install` before its gates. The mirror does the same per product: it finds the
venv **read-only** (`poetry env info -p`; in a worktree, the main checkout's venv when the
worktree's own is empty) and probes it for the tools and dependencies its gates import
(`pytest`, `pytest-cov`, `flake8`, and the product's libraries). A venv that fails the probe is
**one failed gate** that names what is missing and the command that provisions it. The
mirror never falls back to a tool found on `PATH` and never calls `poetry run`: `poetry run`
creates the venv it cannot find (one empty venv per worktree) and then runs whichever
same-named tool is first on `PATH`, which once made a user-global pytest without `pytest-cov`
the gate ("unrecognized arguments: --cov") and silently skipped both flake8 gates. Skipping
is for what cannot run here (Swift on Linux, no device); a missing environment is one command
away, so it fails.

```bash
poetry --directory products/desktop install --no-root                 # --python, and the framework tests
poetry config virtualenvs.options.system-site-packages true            # Blitzlens: gi comes from the system packages
poetry --directory products/linux-desktop install --no-root            # --linux
poetry --directory products/linux-desktop install --no-root --only dev  # --linux without libcairo2-dev & co: numpy, Pillow, gi from the distro
PST_VENV_BIN=/path/to/env/bin ./scripts/run_tests.sh --python          # a machine without Poetry; probed the same way
```

`scripts/tests/test_run_tests_sh.py` proves it against a fake `PATH` that holds a pytest without
`pytest-cov`.

## Free disk space (mirror only)

Gradle and SwiftPM fill several GB, and under ~5 GB a gate's `✘` is often *No space left on
device* rather than code. Before the Android, Android-device and macOS Desktop sections the
mirror checks the free space of the checkout's file system; under `PST_MIN_SPACE_GB` (default
5) it records one failed gate, `free disk space for the <section> gates`, and skips that
section. Any other failed gate re-reads the free space and, under the same floor, says the
failure may be disk. CI runners have no such check; their disk is fresh per job. Gate output
is never piped, so every tool keeps its terminal. `scripts/tests/test_run_tests_sh.py`
(`RunTestsDiskSpaceTest`) covers both paths.

---

## Why each un-reproducible gate stays un-reproducible

### Coverage is Linux-authoritative

`products/desktop/tests/conftest.py` skips tests by platform (`linux_only`, `mac_only`,
`windows_only`) and skips `gui_required` tests when no display is available.
Each OS therefore executes a **different subset** and reaches a **different**
coverage number.

Enforcing one threshold on all three runners made coverage a per-OS lottery: a
change could pass on a contributor's Mac and fail `--cov-fail-under` only on
the Windows runner — with no local reproduction available. The threshold is now
enforced on Linux only (largest subset = authoritative). macOS and Windows still
run the full suite and still fail on any real test failure.

`run_tests.sh` runs the coverage gate on every platform anyway, because a
genuine coverage regression is worth catching early. If it fails locally on
macOS but the Linux CI number is fine, trust Linux.

### Per-gate timing

Every gate that runs a command records its own wall seconds as the sixth field of its results
line, and the closing summary prints them (`(24 s)`). A failed gate records its exit code (plus
`disk` when free space is under the floor). The seconds are saved in the verification record and
shown in the PR block; `python3 scripts/verification_record.py timings [--last N] [--machine SLUG]`
ranks the gates of the saved records by median seconds. Records saved before this carry no
per-gate duration and are not counted per gate.

### GUI gates run on Xvfb, not your screen

Every gate that renders a widget — the Tk suites in `products/desktop/` and the GTK suite in
`products/linux-desktop/` — goes through `run_tests.sh`'s `pyx` helper, which on Linux wraps
the command in `xvfb-run -a -s "-screen 0 1280x1024x24"`. That is CI's own display, verbatim:
`desktop.yml` starts `Xvfb :99 -screen 0 1280x1024x24` for its `test` and `visual` jobs, and
`linux.yml` wraps pytest in `xvfb-run -a`.

The ambient `DISPLAY` is not an acceptable substitute. Widget geometry is measured against
the live font stack and DPI, so a desktop session rendering Cantarell 11 at 96 dpi fails
`tests/unit/test_ui_control_occlusion.py` while CI passes — a red gate that proves nothing
about the code. Running on a private X server also honours the standing rule that a local
test run never opens a window on the developer's screen
(`ai/rules/engineering-invariants.md`).

On a Wayland session GTK4 prefers `WAYLAND_DISPLAY` to the Xvfb `DISPLAY` and would open the
test windows on the real desktop, so under Xvfb `pyx` runs the command with `WAYLAND_DISPLAY`
unset and `GDK_BACKEND=x11`. `PST_SHOW_GUI=1` leaves the environment alone (windows on your screen).

A bare `pytest` of `products/linux-desktop` (no `pyx`) gets the same protection from
`tests/conftest.py` (`_display_env.keep_gui_off_real_desktop`): with `WAYLAND_DISPLAY` set the
session's `DISPLAY` (its Xwayland, also the real desktop) is dropped as well, so the tests run
against the stubs; run them under `xvfb-run` with `WAYLAND_DISPLAY` unset to exercise real GTK.
The lab's `tests/e2e/session.sh` unsets `WAYLAND_DISPLAY` and forces `GDK_BACKEND=x11` on its own Xvfb.
`conftest.py` also points `XDG_CONFIG_HOME` / `XDG_DATA_HOME` at a throwaway directory, so a test
that builds a window without a settings path never touches the user's real settings.

Off Linux there is no Xvfb: `pyx` degrades to a plain call, and the macOS conftest keeps
windows off-screen by its own means (see
[Headless & background GUI execution](#headless--background-gui-execution-on-macos-all-products)).

### Visual regression (Linux only)

Baselines in `products/desktop/tests/visual/baselines/` are rendered under Xvfb **on the CI
runner image**. macOS and Windows font rasterisation differs enough to produce false diffs,
so the tests are marked `linux_only` and the local runner reports them SKIPPED off Linux.

Xvfb alone does not make them reproducible on every Linux box. A Debian 13 workstation
renders the same widgets through a different font build than the pinned `ubuntu-24.04` image:
measured 2026-10-24, eight of nine tests differ by 0.9–3.9 % of pixels, all on glyphs and
emoji, against the 0.5 % `DEFAULT_THRESHOLD` in `tests/visual/conftest.py`. That is the font
stack, not a regression, so the local runner reports the gate `⊘ SKIPPED` on any host other
than the image the visual job runs on (`BASELINE_HOST` in `scripts/visual_baseline_host.py`;
its test checks the job's `runs-on`), naming both hosts.
`PST_VISUAL_ANY_HOST=1 ./scripts/run_tests.sh` runs it anyway, and
`tests/visual/diffs/<name>_diff.png` then shows where it differs. Treat `desktop.yml:visual`
as authoritative and re-render baselines only from a CI run. When the job's `runs-on` image
changes, update `BASELINE_HOST` in the same commit.

### macOS native Swift testing and Darwin runner isolation

The macOS Desktop application (`products/macos-desktop/`) is built on Swift 6, SwiftUI,
Apple Vision (`VNCalculateImageAestheticsScoresRequest`), Accelerate vImage, and AppKit.
These proprietary frameworks require Apple Darwin operating system kernels:

1. **Darwin Host Dependency**: Running `swift test` against `products/macos-desktop/`
   requires a macOS host environment. In GitHub Actions, `.github/workflows/macos.yml`
   runs on the pinned `macos-26` runner image (Apple Silicon arm64). On Linux or Windows developer
   machines, `scripts/run_tests.sh` reports `swift test (macos-desktop)` as `⊘ SKIPPED`.
2. **Xcode 16 / Swift 6 Parity**: The CI runner selects the active Xcode 16 toolchain,
   matching local developer environments.
3. **Performance SLA Verification**: The test suite includes latency SLA gates
   (e.g., `FocusMetricService` 1080p frame latency < 2.0ms core compute in release mode),
   which run under `swift test -c release --no-parallel`.

### macOS UI smoke test (local-only)

`swift test` cannot see failures that live outside `PhotoSelectorKit`. The keyboard router's
unit tests passed for months while arrow keys did nothing in the real app: an unbundled
SwiftPM binary starts with activation policy `.prohibited`, so the window server never makes
it the active app and delivers no key events to it. `run_tests.sh --macos` therefore also
runs `products/macos-desktop/scripts/ui_smoke_test.sh`, which builds the app (with the UI test
hooks: `build_app.sh release --test-hooks`, into `.build/hooks/`), generates 120
camera-sized landscape JPEGs (plus decoys in an excluded `Selection/` folder) and launches
the app with `--ui-self-test`. The in-app runner (`UISelfTestRunner`) asserts:

- activation policy `.regular`, app active, workspace window key, key monitor attached;
- the first photo is drawn in under 2 s, before the scan completes, and every photo is listed;
- posted `NSEvent` key-downs (→ ← ↓ 3 Space Esc) change the workspace state;
- the rendered Focus 3-Up frames: CURRENT above PREVIOUS and NEXT, PREVIOUS left of NEXT;
- posted mouse clicks on the toolbar and on the Fit pill work while zoomed.

It runs against the raw binary (the `swift run` path) and the assembled `Lensjet.app`.
On the raw binary it also sends a real arrow key through the window server with
`osascript … System Events … key code 124`. That needs Accessibility permission for the
calling terminal; without it the probe prints `⊘ SKIPPED` and the gate still passes on the
in-app checks. Without a logged-in GUI session (SSH, headless CI) the gate is `⊘ SKIPPED`.
It is local-only: GitHub's macOS runners have no reliable foreground session. `--quick`
skips it.

### Automatic path filtering (selective product gates)

The four product workflows (`desktop.yml`, `android.yml`, `macos.yml`, `linux.yml`) configure `paths:`
filters so that pull requests touching only Android do not execute Desktop or macOS CI,
and vice versa. `run_tests.sh` mirrors this locally: unless `--all` or an explicit product
flag (`--python`, `--android`, `--macos`, `--linux`) is given, it inspects git status and
branch diffs against `main` (changes to `.github/`, `run_tests.sh` or the format contract under
`testdata/formats/` count for every product; `scripts/check_native_alignment.py` counts for
Android, the legacy `products/desktop/scripts/update_formula.py` for Desktop (Lensjet's cask
job no longer runs it), and `docs/products/linux-desktop/INSTALL.md` for Blitzlens, whose
packaging test reads the APT key fingerprint from it — the same as the workflows' `paths:`).
If only one product has modifications, gates for untouched
products are reported as skipped due to path filtering rather than executing redundant suites.
There is deliberately no `scripts/**` filter: the legacy Desktop reads from `scripts/` only the
format-corpus fetcher, so a change to `scripts/lab/`, `release.py` or the Android tooling does not
rebuild it, publish its nightly or rewrite its Homebrew hashes.
`scripts/tests/test_ci_mirror.py` (part of `scripts/tests`, run by `repo-hygiene.yml` and the
mirror) parses both sides and fails when a workflow path does not select its product in
`run_tests.sh`, when a product arm of `run_tests.sh` is missing from its workflow's filter, or
when a workflow's push and pull-request filters differ.

`android.yml:detect-changes` decides which Android products a change touches: a change under
one product's directory builds that product, and a change under a shared module
(`products/android/core/**`, `products/android/saf/**`) or the shared Gradle files builds both.
`testDebugUnitTest --continue` runs every module's unit tests, `:core` and `:saf` included, and keeps going past a failing module (without it Gradle stopped at the first one and the other module reported nothing). Locally
every path under `products/android/` selects the Android gates, so the mirror needs no
per-module list.

### Headless & background GUI execution on macOS (All Products)

Local automated test runs MUST NEVER force GUI windows into the foreground or steal window focus from the developer. By default, all test suites execute in a completely backgrounded state:
- **Desktop (Python/Tkinter):** `products/desktop/tests/conftest.py` swizzles AppKit activation (`activateIgnoringOtherApps:`) and window ordering (`makeKeyAndOrderFront:`) methods via ctypes on Darwin during pytest runs, keeping widget testing completely in-memory.
- **macOS Desktop (Native Swift/SwiftUI):** `PhotoSelectorApp` under `--ui-self-test` (and `--screenshot`) detects background test mode (`PST_SHOW_GUI` unset) and positions the workspace window off-screen at `(-20000, -20000)` with `alphaValue = 0.0`, suppresses `NSApp.activate(ignoringOtherApps: true)`, and avoids calling `osascript ... set frontmost`. Mouse and keyboard events dispatch directly without stealing OS window focus or interrupting the developer's work.
- **Linux Desktop (Python/GTK4):** GTK 4 has no headless backend. `products/linux-desktop/tests/conftest.py` sets `ADW_DISABLE_PORTAL=1` and, when PyGObject or a display server is missing, replaces GTK with stubs; CI (`linux.yml:test`) runs the real GTK under `xvfb-run` (`GDK_BACKEND=x11`), which never touches a developer's screen.

To disable background execution and view GUI windows interactively across any desktop suite, set `PST_SHOW_GUI=1` (or pass `--external-key` to `ui_smoke_test.sh`).

### Instrumented tests genuinely need a device

`products/android/android-desktop/tests/instrumented/` and `products/android/phototok/tests/instrumented/` assert
against the **live Compose semantics tree** and use Hilt test injection, a real
`Activity` lifecycle, and Room on a real SQLite instance. These are the right
tests for what they cover — they are not portable to the JVM without losing the
behaviour they exist to verify, so **they stay where they are**.

What changed is that their *failure modes are now split*:

1. **Compilation errors** — unresolved references, wrong matcher names, wrong
    data-class fields. These no longer require a device. `run_tests.sh` runs
    `assembleDebugAndroidTest`, which compiles the exact same Kotlin + KSP/Hilt
    sources in ~2 minutes. **This catches them before you push.**
2. **Semantics-tree assertion errors** — merged vs. unmerged tree, a matcher
    hitting multiple nodes, ancestor scoping. These still require a real
    runtime. Mitigate them by following the conventions below rather than by
    guessing through CI.

**Lab-only device tests.** A Jackpix test that needs state only a prepared lab device has (a
persisted SAF folder grant) carries `@LabDeviceOnly` (`products/android/phototok/tests/instrumented/com/phototok/LabDeviceOnly.kt`).
`:phototok`'s `defaultConfig` excludes that annotation from every Gradle connected run, CI's
`instrumented-tests` job and `--android-device` included, because the instrumentation runner
reports an `assumeTrue` skip there as a failure. Run such a test on the lab device with
`adb shell am instrument -w -e class <Test> com.phototok.test/androidx.test.runner.AndroidJUnitRunner`
after the app holds the grant (`tests/e2e/_pick_work_folder.yaml`).

To run them locally:

```bash
emulator -avd <avd_name> -no-audio -no-boot-anim &
adb wait-for-device
./scripts/run_tests.sh --android-device
```

**All devices at once: `scripts/android_device_matrix.py`.** Gradle's `connectedDebugAndroidTest`
installs on — and afterwards uninstalls from — *every* attached device, prints little while it
runs, and one device after another takes long enough for the next phone to auto-lock. The device
suite therefore runs through
[`scripts/android_device_matrix.py`](../../scripts/android_device_matrix.py), which builds the
debug and androidTest APKs once, then drives each device in its own thread — device lock, keep
awake, install, `am instrument -r` for both products, one retry per failure (to tell flaky from
broken), restore, uninstall — so the whole fleet takes as long as its slowest device:

```bash
python3 scripts/android_device_matrix.py devices                # what a run covers; locked? busy?
python3 scripts/android_device_matrix.py run                    # every attached device
python3 scripts/android_device_matrix.py run --only-open-issues # just what failed last time
python3 scripts/android_device_matrix.py issues                 # the open-issue ledger
```

Unlock every device, start the run, and walk away: each device is kept awake from the start
(through the APK build), a device that is still locked is waited for (`--unlock-wait`, 120 s)
and then skipped, not failed. While it runs it prints each device's phase, every failure as it
happens, and a progress board; `progress.log` in the run directory has every test event
(`tail -f` it). A test with no output for `--test-timeout` (600 s) counts as hung; a crash or
hang fails the test it hit and the suite resumes after it.
A killed test never runs its `finally` blocks, and `WindowGeometryRule` leaves the display at the
reference tablet size, density and rotation: whenever the runner kills or loses an instrumentation
(hang, crash, USB drop) and again at the end of the run, it puts the display back itself and reads
`wm size`, `wm density`, the rotation settings and the `cmd window fixed-to-user-rotation` mode (`default`, `enabled`, …; dumpsys boolean before API 31) back. A test device and an emulator return to the
`prepare` state; any other device to the values recorded in `restore-pending.json` before the run.
A change or a failed restore is a note on the device's row in the report.
A test that hung and passed on its retry is reported as *hung* (own column, heading and ledger outcome `hung`), never as flaky.

**Report and issue hand-over.** Each run writes `.git/pst-device-runs/<run-id>/` (the common git
directory, shared by every worktree): `report.md` — wall time, build time, per device setup,
install and per-module time, passed/failed/flaky/skipped, failures grouped by test across
devices with their first stack line and logs, the slowest tests — plus `results.json`,
`instrument-<module>.txt` and logcat per device. `issues.json` beside the runs is the open-issue
ledger: every run first validates the previous run's open issues and reports each as ✅ fixed,
❌ still failing or ⚠ not verified (no affected device ran it), then adds what is new. A later
session reads it with `issues`, validates a fix quickly with `run --only-open-issues`, and
confirms with a full run. `report` prints the latest report again.

**Which devices.** `run` covers every attached device, one per physical device (`ro.serialno`:
a phone on USB and on wireless debugging counts once, USB preferred), including personal phones
and tablets. An app the device already has under the same package id (`com.photoselectortoolbox`,
`com.phototok`) is never removed: a compatible install is updated in place and left installed; a
differently signed one (a release build) is left alone and that product is not tested on that
device, unless `--replace-installed` replaces it, wiping its data. Packages the run installed
are uninstalled afterwards. `--no-personal` leaves out the devices marked `personal: true` in the
maintainer's lab inventory. `--serial` limits the run; `--configured` limits it
to the named test devices, which is what `./scripts/run_tests.sh --android-device` uses, with
one gate per device. A test device is designated once, machine-locally (local.properties is
ignored by git):

```bash
echo "pst.testDevice.serial=<serial from adb devices>" >> products/android/local.properties
echo "pst.testDevice.extraSerials=<serial>,<serial>" >> products/android/local.properties
```

With several devices attached and none named, `--configured` refuses rather than run on
personal devices. A named test device (and an emulator) gets `android_test_device.py prepare`'s
persistent setup; any other device gets only animations off, stay-awake and a maximal
screen-off timeout for the run, saved to `.git/pst-device-runs/restore-pending.json` first and
put back afterwards (with its display size, density and rotation) — by the next run if this one was killed.

**A secure lock screen cannot be prepared away.** `locksettings set-disabled` succeeds silently
on a phone with a PIN, pattern or password, and the lock screen then covers every activity, so
each Compose test fails with "No compose hierarchies found". `prepare` therefore checks
`isKeyguardShowing` last and reports a lock screen that is still up; `run_tests.sh` then skips
that device (`⊘ SKIPPED … lock screen showing`) instead of running the suite against the lock
screen. Remove the screen lock on a test phone, or unlock it before the run. The matrix
runner checks the lock screen before installing and before each product's suite and waits for an
unlock; a failure that says "No compose hierarchies found" is flagged in the report as a likely
locked or dark screen.

The device suite never runs as part of the default run or `--all`: it is triggered on demand
with `./scripts/run_tests.sh --android-device`, typically before an Android release. Without a
test device it reports `⊘ SKIPPED` and does not block — not even under `--strict` — and CI's
`instrumented-tests` job still covers every PR. Before every run the script prepares a designated
device (or an emulator) —
animations off (CI's `disable-animations: true`), screen kept on while powered, keyguard off,
portrait rotation, default size/density/font scale, light theme, no verifier prompt on adb
installs — using `adb root` where the build allows it. Run it on its own with
`python3 scripts/android_test_device.py prepare`; `status` shows which device is selected.

**One session at a time.** Several agent sessions share the workstation, and the device lab's
usability walkthroughs tap on the same phone; one walkthrough tapping during an instrumented
run caused a spurious failure (2026-10-18). The matrix runner therefore holds each device's
lock while it prepares, installs and tests it, and any other command can take it with
`python3 scripts/android_test_device.py with-lock [--timeout S] -- <command…>`. The lock is
an `flock` on `/tmp/pstlab/device-<serial>.lock` (`PST_LAB_TMP` moves it; a wireless transport is
keyed by the phone's `ro.serialno`, so USB and Wi-Fi debugging of one phone share it), the file the
lab takes too; its holder writes who it is into it (`device-matrix <worktree> pid=… since=…`,
or `lab usability-capture <product> …`). A held lock is waited for —
`--lock-timeout`, or `PST_DEVICE_LOCK_TIMEOUT` seconds, default 300 — and still held after that,
the device is `⊘ SKIPPED` naming the holder, not failed. `android_test_device.py lock-holder` (and
`status`) shows who holds the device now. The kernel drops the lock when its holder dies, so a
crashed run never leaves the device locked.

**Test photos on the device.** `python3 scripts/android_test_device.py load-images [--sets smoke,formats]`
generates the device lab's fixture sets ([`scripts/lab/fixtures/spec.yaml`](../../scripts/lab/fixtures/spec.yaml))
plus the format corpus and pushes each to `/sdcard/PSTLab/<set>/`, replacing what was there.
It needs Pillow and PyYAML (e.g. `/usr/bin/python3` on Debian); RAW samples need
`python3 scripts/fetch_format_corpus.py` first. The instrumented tests do not read these —
they are for manual testing on the device and for lab journeys.

If you have no emulator, that is a legitimate SKIP — but then treat the first
CI run as a real test run, read the uploaded
`instrumented-test-report-api30` artifact, and fix from the report rather than
pushing another guess.

---

## Adversarial Pre-Delivery Verification Gates

To ensure hands-off autonomous execution with zero required code review, changes must
satisfy five adversarial verification gates prior to delivery:

1. **Focus Theft & Window Isolation**: GUI test suites must execute completely headless
   or backgrounded without stealing user window focus, flashing test windows, or altering
   system input focus. Enforced via ctypes method swizzling on macOS for Tkinter and
   `MainActor`-isolated async testing in Swift.
2. **Automated Visual Contrast & Layout Non-Occlusion (WCAG 2.1 AA)**:
   All visual UI elements must satisfy minimum contrast ratios (>= 4.5:1 for normal text,
   >= 3.0:1 for graphical controls and large text). Dark-on-dark styling is strictly banned.
   All dialogs, buttons, and input controls must undergo bounding box intersection audits
   to verify zero occluded or clipped controls across all supported screen dimensions.
3. **Concurrency & Race Condition Hardening**:
   Asynchronous workflows (e.g. score calculation, thumbnail generation, duplicate scanning)
   must be validated against adversarial burst inputs, out-of-order undo actions, and
   cooperative task cancellation without memory leaks or state corruption.
4. **Bidirectional Requirements Traceability**:
   `private/scripts/verify_requirements_traceability.py --check --strict` (maintainer overlay) validates that every
   requirement identifier in product specifications is well-formed and unique, contains zero
   orphan or cross-product test references, and reports forward test coverage.
5. **Pre-Delivery Intent Gate**:
   Lifecycle Stop hooks (`ai/hooks/check_retrospective.py` and `ai/hooks/verify_intent_delivery.py`)
   verify that tests were executed during the session and that all prompt directives from
   `ai/memory/intent_ledger.jsonl` were fulfilled before work can be marked complete.

---

## Compose instrumented-test conventions

Of the nine consecutive `fix(test)` / `fix(android)` commits on
`feat/score-chips-and-first-run-hints`, **one** was a compile error and
**eight** were the same small family of semantics-tree mistakes. Following
these rules removes almost all of that class:

1. **Use `onAllNodes(...).onFirst()`, not `onNode(...)`, for anything that can
   legitimately match more than once.** `onNode` throws when a matcher hits
   several nodes, and Compose duplicates nodes far more often than it looks —
   a text label appears in both the merged parent and the unmerged child.
2. **Pass `useUnmergedTree = true` when asserting on content inside a
   container that merges semantics** — `ModalBottomSheet`, list rows, buttons
   with an icon plus label. Without it the child node you are matching does not
   exist in the tree you are querying.
3. **Scope assertions to their container** with
   `hasAnyAncestor(hasTestTag("..."))` rather than matching bare text globally.
   A bare `hasText("Sharpness")` will find the chip *and* the legend row.
4. **Import Compose test matchers explicitly, never
   `import androidx.compose.ui.test.*`.** The wildcard is what let `hasTag`
   (which does not exist; the real matcher is `hasTestTag`) survive review and
   reach CI.
5. **Call `performScrollTo()` before `assertIsDisplayed()` / `performClick()` on
   anything inside a `verticalScroll` / `LazyColumn` container.** The CI
   emulator's screen is much smaller than a dev device or the reference tablet,
   so content that is visible locally sits below the fold there and
   `assertIsDisplayed` fails with "is not displayed" although the app is correct
   (`PhoneModeScreenDialogTest`, PR #737, API 30 job). `assertExists()` passes
   off-screen and hides the problem; `assertIsDisplayed()` does not.
6. **Wait on the effect, not on idle, after a gesture.** `waitForIdle()` does not
   prove a gesture's effect has happened. A tap on a node whose
   `detectTapGestures` also handles `onDoubleTap` reaches `onTap` only after the
   double-tap timeout, which runs in real time, so assert with
   `composeTestRule.waitUntil(timeoutMillis = 5_000) { … }` and click the node by
   its own `testTag`, not `onRoot()`. A test asserting that something did *not*
   happen must first prove the gesture was handled, or a late or lost event passes
   it (`PhoneModeViewerInteractionTest`).

7. **A Frameduel test of the expanded layout carries `@ExpandedWindow` and never returns
   early.** CI's emulator is phone-class, so 28 screen tests that began with
   `if (isCompactLayout()) return` passed there without asserting anything. Annotate the test
   (or the class); `WindowGeometryRule` (listed first in the rule order, so it wraps the
   activity) sets the display to the reference geometry, 1480 x 924 dp, through `wm size`,
   `wm density` and `user_rotation`, and puts it back in a `finally`. The test then fails,
   rather than returns, when the window is not expanded (`requireExpandedLayout()`). A phone
   with a display cutout loses that inset from the window (86 dp on a Pixel 4a), so size
   assertions are made against `FrameGeometry` for the width the screen actually offers, and
   absolute floors are guarded by `FrameGeometry.ReferenceWindowWidth` and `ReferenceWindowHeight`.
   The window manager clamps a forced size to twice the panel's own (`MAX_SCALE`), and CI's
   default AVD is 320x640 px: asking it for 1848x2960 px leaves the display at 320x640 dp. The
   rule therefore scales size and density by the same factor, so the window in dp is the
   reference one in as few pixels as the panel allows.
   A phone's launcher requests `SCREEN_ORIENTATION_NOSENSOR` and holds the display in portrait
   whatever `user_rotation` says, and no test activity is up yet when the rule runs, so the rule
   also fixes the display to the user rotation and puts the previous mode back exactly. The
   subcommand is `cmd window fixed-to-user-rotation <mode>` from API 31 and
   `set-fix-to-user-rotation` on API 30 only (`FixedRotation`); the wrong spelling answers
   `Unknown command` on stdout with no exception, so the rule used the old one on every device and
   never fixed the rotation (found 2026-11-05). `shell()` now fails on that answer.
   The usable width is exact: a cutout scales with the forced size (a Pixel 4a's punch hole is
   172 px at 2960 px, 183 at 3142), so the rule grows the long edge until `size - cutout`
   reaches 1480 dp to the pixel, not one pixel less (2959 px is 1479.5 dp, and every 1480 dp
   host refused to start, 2026-11-05). That arithmetic is `tests/common/.../ReferenceWindowMath.kt`,
   compiled into the unit tests too and proved on the JVM (`ReferenceWindowMathTest`). The
   rule returns only after the display has stayed landscape and unchanged for a second, a host
   that still does not see the window calls `reassertLandscape()` while it waits, and a failing
   test carries the display's size changes as a suppressed exception. The rule also bounds what
   used to be able to hang: the test body (150 s), each shell command (20 s) and each injected
   key (`BoundedKeys`, 10 s) fail with the blocked thread's stack instead of holding the process
   until the matrix kills it, which skipped the `finally` and left a Moto G77 at tablet size. The
   device's own state is written to a global setting (`frameduel_test_display_original`) before
   anything is forced and deleted after the restore, so the run after a kill restores the
   device's own geometry, not the leftover.

These rules are also part of the Android UI agent definitions in the maintainer's agent
framework.

---

## Reading a failed Actions run

- **Emulator job fails in under ~5 minutes** → it is a *build* failure, not a
  test failure. The emulator cannot even boot that fast. The annotation reads
  `The process '/usr/bin/sh' failed with exit code 1` and hides the real Kotlin
  error. Run `./scripts/run_tests.sh --android` locally to see it in plain text.
- **Emulator job runs ~15 minutes and fails** → a *test* failure, but
  `gh run view --log-failed` shows only the Gradle summary, not which test or
  assertion. Download the `instrumented-test-report-api<N>` artifact
  (`gh run download <run-id> -n instrumented-test-report-api30`) and read the
  failing test's HTML/XML report — it names the test and the assertion message.
- **Desktop pipeline fails with no test output** → the `lint` job failed;
  everything else was skipped by `needs:`. Run flake8.
- **macOS pipeline fails** → check `swift-test` step logs; compilation errors,
  missing Xcode toolchain, or test assertions will be reported with exact file
  and line references.
- **`publish-play` fails on a missing/empty artifact** → the upstream build
  step did not produce it. All release uploads now use
  `if-no-files-found: error`, so this should fail at the producing step first.
- **A job that sits "in progress" far past its usual time** is hung, not slow. Every job
  declares `timeout-minutes` (about three times its slowest green run, at least 10; the tag-only publish jobs, which have run once, keep a generous bound until measured), so it
  fails instead of running into GitHub's 360-minute default. The pytest jobs
  (`desktop.yml:test`, `desktop.yml:visual`, `linux.yml:test`) pass
  `-o faulthandler_timeout=120`, so a single blocked test dumps every thread's stack into the
  log. `scripts/tests/test_workflow_bounds.py` (run by `repo-hygiene.yml` and the mirror)
  fails when a job or a CI pytest command lacks either. Every `apt-get` step retries (`-o Acquire::Retries=3`) and is bounded to 5 minutes, since a stalled mirror fetch once consumed 15 of a job's 20 minutes (same test). A timeout is a CI-only bound with no local mirror: `run_tests.sh` runs in the
  foreground, where you see a hang yourself.
- **An "Unexpected input(s) …" annotation** is a *warning*, not an error: the
  input was silently ignored and whatever it was supposed to configure never
  took effect. Treat it as a broken fix, not cosmetic noise.

---

## Runner images and dependencies

Every job runs on a pinned image (`ubuntu-24.04`, `macos-26`, `windows-2025`), never `*-latest`:
GitHub moves `-latest` to a new release without a commit in this repository, which would change
the apt package set, the emulator job's KVM step and the glibc the legacy Linux binary is linked
against on `main`, unannounced. Moving to a newer image is one deliberate change
(`scripts/tests/test_workflow_bounds.py` fails on a floating image). The visual-regression
baselines are rendered on the pinned `ubuntu-24.04`.

Dependabot (monthly, grouped) covers Gradle, the GitHub Actions in `.github/workflows/` and
`.github/actions/*`, and the Poetry projects `products/desktop` and `products/linux-desktop`
(minor and patch updates only).

---

## Keeping this file honest

When you add or change a gate in `.github/workflows/`, in the **same commit**:

1. Add the equivalent to `scripts/run_tests.sh` (or add an explicit `skip`
   with the reason it cannot run locally).
2. Update the gate matrix above.

A gate that exists only in CI is a gate contributors and agents cannot satisfy
before pushing — which is exactly how the push-and-pray loop starts.
