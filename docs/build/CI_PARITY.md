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
| flake8 `src/ tests/` | `desktop.yml:lint` | ✅ everywhere | **Gates the entire desktop pipeline.** A lint error fails the run before one test executes. |
| pytest `-m "not visual"` | `desktop.yml:test` | ✅ everywhere | Same marker filter locally and in CI. On Linux the runner renders it on its own Xvfb, as CI does — see [GUI gates run on Xvfb](#gui-gates-run-on-xvfb-not-your-screen). |
| Coverage `--cov-fail-under=60` | `desktop.yml:test` (**Linux only**) | ✅ advisory everywhere | See [Coverage is Linux-authoritative](#coverage-is-linux-authoritative). |
| Visual regression | `desktop.yml:visual` | ⚠️ Linux + Xvfb; baselines are runner-rendered | See [Visual regression](#visual-regression-linux-only). |
| `testDebugUnitTest` | `android.yml:unit-tests` | ✅ needs JDK 17 + Android SDK | |
| `assembleDebugAndroidTest` | `android.yml:unit-tests` | ✅ needs JDK 17 + Android SDK | Compiles `tests/instrumented/`. Nothing else does. |
| `check_native_alignment.py` | `android.yml:unit-tests`, `android.yml:build` | ✅ needs JDK 17 + Android SDK (unit tests run everywhere via `scripts/tests`) | Inspects every `lib/{arm64-v8a,x86_64}/*.so` in `merged_native_libs/debug` and built APK/AAB archives and fails if any ELF `PT_LOAD` segment has `p_align < 16384` (Google Play 16 KB page-size mandate). For `.apk` files it also fails if a stored (uncompressed) `.so` does not start at a 16 KB-aligned zip offset (`zipalign -P 16`); bundles are exempt. The mirror builds and checks the debug APKs like the `build` job. |
| `lintDebug` | `android.yml:lint` | ✅ advisory | Non-blocking in both places, on purpose. |
| `connectedDebugAndroidTest` | `android.yml:instrumented-tests` | ❌ **needs emulator/device** | See [Instrumented tests](#instrumented-tests-genuinely-need-a-device). |
| `swift test (macos-desktop)` | `macos.yml:swift-test` | ⚠️ Darwin only | Native Swift 6 suite covering PhotoSelectorKit & App (release SLA benchmark included). Runs on `macos-latest` in CI. |
| `check_no_test_hooks.sh` (release binary) | `macos.yml:swift-test`, `macos.yml:build` | ⚠️ Darwin only | `swift build -c release`, then fails if the binary contains the UI test hooks (`UIScenarioRunner`, `UITestDriver`, `UISelfTestRunner`), which are compiled in only for debug and `build_app.sh --test-hooks` builds. |
| macOS UI smoke test (running app) | — (local-only) | ⚠️ Darwin + logged-in GUI session | `products/macos-desktop/scripts/ui_smoke_test.sh`: drives the running app (raw binary **and** `.app` bundle) — activation policy, real key events, rendered Focus 3-Up geometry, clicks while zoomed, time-to-first-photo. Window-server key probe needs Accessibility permission, else `⊘ SKIPPED`. See [macOS UI smoke test](#macos-ui-smoke-test-local-only). |
| `linux-desktop lint` | `linux.yml:lint` | ✅ everywhere | Flake8 static analysis on `products/linux-desktop/` |
| `linux-desktop test` (`--cov-fail-under=70`) | `linux.yml:test` | ⚠️ full only with GTK 4 + GExiv2 typelibs (partial elsewhere) | Headless unit tests covering metadata, scoring, file transactions, window/dialog controllers, and format conformance with `--cov=photo_selector_linux --cov-fail-under=70`. CI installs the Debian decoding backends (`gir1.2-gexiv2-0.10`, `libraw-bin`, `heif-gdk-pixbuf` + `libheif-plugin-libde265`; deliberately no `webp-pixbuf-loader`, so the Pillow display fallback stays tested) and, with the corpus, fails if any is missing; off Linux the backend-dependent format checks skip with the package to install. Without the real GTK 4 / GExiv2 typelibs (macOS, or a Linux box without them) `conftest.py` stubs `gi`, so the GTK display decode path never runs; the mirror then labels the gate `linux.yml:test (partial — …)` instead of claiming CI parity. Runs under Xvfb on Linux, mirroring `xvfb-run -a` in CI. The CI job has `timeout-minutes: 15` and both CI and the mirror pass `-o faulthandler_timeout=120`, so a test that blocks forever on a real GTK main loop fails with every thread's stack instead of hanging (a preferences-dialog signal feedback loop did exactly that on 2026-10-17 while the Mac gate, which stubs GTK, was green). |
| `debian packaging & apt repo` | `linux.yml:package` | ✅ everywhere (pure-Python builder) | `package_debian.sh`: builds the `.deb`, generates an unsigned APT repository in `products/linux-desktop/dist/apt` and verifies it. |
| APT publish | `linux.yml:publish-apt` | ❌ credentials-bound | On `v*` tags: builds, signs (`APT_GPG_PRIVATE_KEY`) and pushes the repository to `alexpp90/apt` (GitHub Pages). |
| Homebrew tap update | `desktop.yml:homebrew`, `macos.yml` release steps | ❌ credentials-bound | Writes formula/cask hashes and versions directly to `alexpp90/homebrew-tap` (`HOMEBREW_TAP_TOKEN`); the main repository holds no Formula/Casks. |
| PyInstaller build, release, Play/Firebase publish | `desktop.yml:build`+, `android.yml:build`+, `macos.yml:build`, `linux.yml:github-release`, `linux.yml:publish-apt` | ❌ credentials-bound | Only runs on pushes to `main` / the product's own release tag (`phototok-v*`, `android-desktop-v*`, `macos-desktop-v*`, `linux-desktop-v*`, `desktop-v*` — see [RELEASING.md](RELEASING.md)). Not reproducible locally by design — except the macOS universal bundle, which `products/macos-desktop/scripts/build_app.sh release --universal` builds locally (arm64 + x86_64, verified with `lipo -archs`). Main pushes also move the `nightly` tag to the released commit before updating the Nightly Build release. |
| `check_readme_parity.py --check` | `repo-hygiene.yml:hygiene` | ✅ everywhere | Enforces root `README.md` ecosystem completeness, canonical product ordering (PhotoTok -> Android Desktop -> macOS Desktop -> Linux Desktop -> Desktop), asset integrity, and removal of deprecated terms. |
| `release.py verify-tag` | first step of every tag release job (`android.yml:build`, `desktop.yml:release`, `macos.yml:build`+`release`, `linux.yml:publish-apt`+`github-release`) | ✅ `python3 scripts/release.py verify-tag <tag>` | Fails the release before anything is built or published unless the tag's version equals every in-tree version source and the product's changelog has that section. Locally, `release.py tag` checks the same and additionally refuses a tree without a passing `run_tests.sh` record for the product's gates. |
| `release.py check` | `repo-hygiene.yml:hygiene` | ✅ everywhere | Every product's version sources agree, it has a `CHANGELOG.md`, and its workflow triggers on its own tag prefix. `release.py`'s unit tests run in `scripts/tests` below. |
| `check_private_paths.py` | `repo-hygiene.yml:hygiene` | ✅ everywhere | Fails if maintainer-private material (`private/`, `ai/`, `.claude/`, `.agents/`, `.gemini/`, requirement specs, prompts, the prompt ledger) is tracked in this public repository. Also runs as the local pre-commit hook installed by the overlay. |
| Format corpus (`fetch_format_corpus.py`) | `.github/actions/format-corpus`, used by `desktop.yml:test`, `android.yml:unit-tests`, `macos.yml:swift-test`, `linux.yml:test` | ⚠️ once downloaded (~280 MB, network) | Restores the real-camera RAW corpus pinned in `testdata/formats/manifest.json` (cache key = manifest hash) and sets `PST_REQUIRE_FORMAT_CORPUS=1`, so every product's format conformance tests **fail** rather than skip without it. Locally `run_tests.sh` exports the same variables when the corpus is present and reports `⊘ SKIPPED` otherwise. See [`docs/shared/SUPPORTED_FORMATS.md`](../shared/SUPPORTED_FORMATS.md). |
| `fetch_format_corpus.py --validate` + `scripts/tests` | `repo-hygiene.yml:hygiene` | ✅ everywhere | The format contract is well-formed: every required format has a sample with ground-truth EXIF, extensions are unique, committed bitmap fixtures match their pinned SHA-256; unit tests for the fetcher (mirror fallback, hash verification), for `verification_record.py` and for `release.py`. |
| PR verification record (`verification_record.py check`) | `pr-verification.yml:record` | ⚠️ PR description only — advisory | Compares the verification blocks in the PR description (written by `scripts/verification_record.py pr` from the record every `run_tests.sh` run saves) with the PR head tree and the products the PR touches; annotates missing, stale or partial verification and never fails. Not a `run_tests.sh` gate: it judges the PR description, which exists only on GitHub. Local equivalent: `python3 scripts/verification_record.py check --body-file <description.md> --base origin/main`. Its unit tests run in `scripts/tests` above. See [CONTRIBUTING.md § Verification record](../../CONTRIBUTING.md#verification-record). |
| `lab tooling tests` (`scripts/lab/tests`) | — (local-only) | ✅ everywhere (needs Pillow + PyYAML) | Local-only unit tests (`python3 -m unittest discover -s scripts/lab/tests -q`) covering the device lab's schemas, fixture generator, layout lint, file-system/timing/log oracles, runners, and report builder without touching devices or a GUI. |
| Device lab (`scripts/lab/lab.py`) | — (not a CI gate) | ⚠️ local, on demand, opt-in | Local, on demand, not a CI gate; opt-in; see [`scripts/lab/README.md`](../../scripts/lab/README.md). |
| Private overlay gates (`link_overlay.py --check`, `sync_framework.py --check`, `validate_framework.py`, `gen_gemini_settings.py --check`, `verify_requirements_traceability.py --check --strict`, framework tests) | private repo `framework.yml` | ⚠️ maintainer checkout only | Run by `run_tests.sh` when `private/` is checked out; `⊘ SKIPPED` (not a risk) in a public clone. The private repository's CI checks out this repository and runs the same gates. |

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

Off Linux there is no Xvfb: `pyx` degrades to a plain call, and the macOS conftest keeps
windows off-screen by its own means (see
[Headless & background GUI execution](#headless--background-gui-execution-on-macos-all-products)).

### Visual regression (Linux only)

Baselines in `products/desktop/tests/visual/baselines/` are rendered under Xvfb **on the CI
runner image**. macOS and Windows font rasterisation differs enough to produce false diffs,
so the tests are marked `linux_only` and the local runner reports them SKIPPED off Linux.

Xvfb alone does not make them reproducible on every Linux box. A Debian 13 workstation
renders the same widgets through a different font build than `ubuntu-latest`, which lands
around 0.9 % of pixels — just over the 0.5 % `DEFAULT_THRESHOLD` in
`tests/visual/conftest.py` — on text-heavy baselines. Diffs that size, concentrated on glyph
edges, are the font stack and not a regression; `tests/visual/diffs/<name>_diff.png` shows
which. Treat `desktop.yml:visual` as authoritative and re-render baselines only from a CI
run.

### macOS native Swift testing and Darwin runner isolation

The macOS Desktop application (`products/macos-desktop/`) is built on Swift 6, SwiftUI,
Apple Vision (`VNCalculateImageAestheticsScoresRequest`), Accelerate vImage, and AppKit.
These proprietary frameworks require Apple Darwin operating system kernels:

1. **Darwin Host Dependency**: Running `swift test` against `products/macos-desktop/`
   requires a macOS host environment. In GitHub Actions, `.github/workflows/macos.yml`
   runs on `macos-latest` runners (Apple Silicon arm64). On Linux or Windows developer
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

It runs against the raw binary (the `swift run` path) and the assembled `PhotoSelector.app`.
On the raw binary it also sends a real arrow key through the window server with
`osascript … System Events … key code 124`. That needs Accessibility permission for the
calling terminal; without it the probe prints `⊘ SKIPPED` and the gate still passes on the
in-app checks. Without a logged-in GUI session (SSH, headless CI) the gate is `⊘ SKIPPED`.
It is local-only: GitHub's macOS runners have no reliable foreground session. `--quick`
skips it.

### Automatic path filtering (selective product gates)

GitHub Actions workflows (`desktop.yml`, `android.yml`, `macos.yml`) configure `paths:`
filters so that pull requests touching only Android do not execute Desktop or macOS CI,
and vice versa. `run_tests.sh` mirrors this locally: unless `--all` or an explicit product
flag (`--python`, `--android`, `--macos`, `--linux`) is given, it inspects git status and
branch diffs against `main` (changes to `.github/`, `run_tests.sh` or the format contract under
`testdata/formats/` count for every product). If only one product has modifications, gates for untouched
products are reported as skipped due to path filtering rather than executing redundant suites.

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

To run them locally:

```bash
emulator -avd <avd_name> -no-audio -no-boot-anim &
adb wait-for-device
./scripts/run_tests.sh --android-device
```

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
   observable requirement identifier exists in product specifications, is mapped to automated
   tests, and contains zero orphan test references.
5. **Pre-Delivery Intent Gate**:
   Lifecycle Stop hooks (`ai/hooks/check_retrospective.py` and `ai/hooks/verify_intent_delivery.py`)
   verify that tests were executed during the session and that all prompt directives from
   `ai/memory/intent_ledger.jsonl` were fulfilled before work can be marked complete.

---

## Compose instrumented-test conventions

Of the nine consecutive `fix(test)` / `fix(android)` commits on
`feat/score-chips-and-first-run-hints`, **one** was a compile error and
**eight** were the same small family of semantics-tree mistakes. Following
these five rules removes almost all of that class:

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
- **An "Unexpected input(s) …" annotation** is a *warning*, not an error: the
  input was silently ignored and whatever it was supposed to configure never
  took effect. Treat it as a broken fix, not cosmetic noise.

---

## Keeping this file honest

When you add or change a gate in `.github/workflows/`, in the **same commit**:

1. Add the equivalent to `scripts/run_tests.sh` (or add an explicit `skip`
   with the reason it cannot run locally).
2. Update the gate matrix above.

A gate that exists only in CI is a gate contributors and agents cannot satisfy
before pushing — which is exactly how the push-and-pray loop starts.
