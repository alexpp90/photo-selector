#!/usr/bin/env bash
#
# run_tests.sh — CI-parity test runner for Photo Selector Toolbox
#
# This script is the LOCAL MIRROR of .github/workflows/desktop.yml,
# .github/workflows/android.yml, .github/workflows/macos.yml, and
# .github/workflows/linux.yml. Every gate CI enforces is enforced here, in
# the same order, with the same flags. If this script is green and it reports
# no SKIPPED gates, CI is expected to be green.
#
# The inverse is the point: a gate that exists only in CI is a gate an agent
# cannot satisfy before pushing, which turns the PR into a push-and-pray loop.
# When you add a gate to a workflow, add it here in the same commit.
#
# Usage:
#   ./scripts/run_tests.sh                  # auto-detects changed products (mirrors CI paths: filters)
#   ./scripts/run_tests.sh --python         # Python gates only (flake8 + pytest + coverage)
#   ./scripts/run_tests.sh --android        # Android gates that need no device
#   ./scripts/run_tests.sh --android-device # Android instrumented tests on the test device only — run on
#                                           # demand (before a release); never part of any other mode.
#                                           # No device attached is a skip, not a failure (also with --strict)
#   ./scripts/run_tests.sh --macos          # macOS Desktop native Swift gates (Darwin only)
#   ./scripts/run_tests.sh --linux          # Linux Desktop native gates (Python/GTK4 + Debian packaging)
#   ./scripts/run_tests.sh --all            # all products unconditionally (without the device suite)
#   ./scripts/run_tests.sh --strict         # treat unrunnable SKIPPED gates as failures
#   ./scripts/run_tests.sh --quick          # tests only, no lint/coverage (inner-loop use)
#
# Every run ends by saving a verification record (machine, toolchains, gates, tested tree) in
# .git/pst-verification/; `python3 scripts/verification_record.py pr` puts it into the PR
# description. PST_NO_RECORD=1 skips it; PST_EXECUTOR="<agent or person>" names who ran it.
#
# Exit codes: 0 all attempted gates passed; 1 a gate failed; 2 --strict and a gate was skipped.
#
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN_ARGS="$*"
RUN_STARTED="$(date +%s)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Inside a Flatpak sandbox (the VS Code Flatpak, and so Claude Code and the integrated
# terminal it hosts) /usr is the Freedesktop runtime, not the workstation: no GTK 4 /
# Adwaita / GExiv2 typelibs, no tkinter, no xvfb-run, no dpkg-deb, and the Poetry venv —
# built on the host's /usr/bin/python3 with system site-packages — does not resolve. Every
# Python and Linux gate would then skip or fail for reasons that say nothing about the
# code. Re-run on the host instead. A non-interactive host shell never reads ~/.bashrc,
# so the JDK and SDK locations are carried across; only paths under $HOME, which the
# sandbox shares with the host, mean the same thing on both sides.
# A sandbox without host access (flatpak-builder, a CI Flatpak test run) stays where it is.
# PST_NO_HOST_REEXEC=1 keeps the run inside the sandbox.
if [ -f /.flatpak-info ] && [ -z "${PST_NO_HOST_REEXEC:-}" ] && command -v flatpak-spawn &>/dev/null \
    && flatpak-spawn --host true &>/dev/null; then
    host_env=(--env=PST_NO_HOST_REEXEC=1)
    java_home="${JAVA_HOME:-}"
    if [ -z "$java_home" ] && command -v java &>/dev/null; then
        java_home="$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")"
    fi
    for var in java_home ANDROID_HOME ANDROID_SDK_ROOT; do
        value="${!var:-}"
        case "$value" in
            "$HOME"/*) host_env+=("--env=${var^^}=$value") ;;
        esac
    done
    echo -e "${DIM:-}Flatpak sandbox detected — running the gates on the host (PST_NO_HOST_REEXEC=1 to opt out)${NC:-}"
    # shellcheck disable=SC2016  # expanded by the host shell, not here
    exec flatpak-spawn --host --directory="$PWD" "${host_env[@]}" \
        bash -lc '[ -n "${JAVA_HOME:-}" ] && export PATH="$JAVA_HOME/bin:$PATH"; exec bash "$@"' \
        run_tests.sh "$SCRIPT_DIR/run_tests.sh" "$@"
fi

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
DIM='\033[2m'
NC='\033[0m'

# Keep in sync with `--cov-fail-under` in .github/workflows/desktop.yml and linux.yml.
COV_FAIL_UNDER=60
LINUX_COV_FAIL_UNDER=70

have() { command -v "$1" &>/dev/null; }

run_python=false
run_android=false
run_android_device=false
run_macos=false
run_linux=false
explicit_product=false
strict=false
quick=false

for arg in "$@"; do
    case "$arg" in
        --python)         run_python=true; explicit_product=true ;;
        --android|--android-unit) run_android=true; explicit_product=true ;;
        --android-device) run_android_device=true; explicit_product=true ;;
        --macos)          run_macos=true; explicit_product=true ;;
        --linux)          run_linux=true; explicit_product=true ;;
        --strict)         strict=true ;;
        --quick)          quick=true ;;
        --all)
            run_python=true
            run_android=true
            run_macos=true
            run_linux=true
            explicit_product=true
            ;;
        -h|--help)
            sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo -e "${RED}Unknown argument: $arg${NC}"
            exit 1
            ;;
    esac
done

# If no product was explicitly requested, mirror CI paths: filters via git change detection
if ! $explicit_product; then
    CHANGED_FILES=()
    if have git && [ -e "$ROOT_DIR/.git" ]; then
        BASE_COMMIT=""
        if git rev-parse --verify origin/main &>/dev/null; then
            BASE_COMMIT="$(git merge-base HEAD origin/main 2>/dev/null || true)"
        elif git rev-parse --verify main &>/dev/null; then
            BASE_COMMIT="$(git merge-base HEAD main 2>/dev/null || true)"
        fi

        if [ -n "$BASE_COMMIT" ]; then
            while IFS= read -r f; do [ -n "$f" ] && CHANGED_FILES+=("$f"); done < <(git diff --name-only "$BASE_COMMIT..HEAD" 2>/dev/null || true)
        fi
        while IFS= read -r f; do [ -n "$f" ] && CHANGED_FILES+=("$f"); done < <(git diff --name-only HEAD 2>/dev/null || true)
        while IFS= read -r f; do [ -n "$f" ] && CHANGED_FILES+=("$f"); done < <(git ls-files --others --exclude-standard "$ROOT_DIR" 2>/dev/null || true)
    fi

    if [ ${#CHANGED_FILES[@]} -eq 0 ]; then
        # Pristine working tree: test all products by default
        run_python=true
        run_android=true
        if [ "$(uname -s)" = "Darwin" ]; then
            run_macos=true
        fi
        run_linux=true
    else
        has_desktop=false
        has_android=false
        has_macos=false
        has_linux=false
        has_cross=false

        for f in "${CHANGED_FILES[@]}"; do
            case "$f" in
                products/desktop/*|*.spec) has_desktop=true ;;
                products/android/*) has_android=true ;;
                products/macos-desktop/*) has_macos=true ;;
                products/linux-desktop/*) has_linux=true ;;
                .github/workflows/*|.github/actions/*|scripts/run_tests.sh|testdata/formats/*|scripts/fetch_format_corpus.py) has_cross=true ;;
            esac
        done

        if $has_cross; then
            run_python=true
            run_android=true
            if [ "$(uname -s)" = "Darwin" ]; then run_macos=true; fi
            run_linux=true
        else
            if $has_desktop; then run_python=true; fi
            if $has_android; then run_android=true; fi
            if $has_macos; then run_macos=true; fi
            if $has_linux; then run_linux=true; fi
        fi
    fi
fi

EXIT_CODE=0
SKIPPED_ANY=false
SKIPPED_UNRUNNABLE=false
RESULTS_FILE="$(mktemp)"
trap 'rm -f "$RESULTS_FILE"' EXIT

# ── Result helpers ────────────────────────────────────────────────
# Every gate reports exactly one line so the closing summary can state, per
# gate, whether it actually ran. A gate that silently did not run is the
# failure mode this script exists to prevent.

# Line format: STATUS|name|detail|ci_ref|unrunnable — read by the summary below and by
# scripts/verification_record.py. For PASS/FAIL/WARN the detail column repeats the ci_ref.
record() { printf '%s|%s|%s|%s|%s\n' "$1" "$2" "$3" "${4:-$3}" "${5:-}" >> "$RESULTS_FILE"; }

pass() { record PASS "$1" "$2"; echo -e "${GREEN}  ✔ $1${NC}"; }
fail() { record FAIL "$1" "$2"; echo -e "${RED}  ✘ $1${NC}"; EXIT_CODE=1; }
warn() { record WARN "$1" "$2"; echo -e "${YELLOW}  ! $1${NC}"; }
skip() {
    local name="$1"
    local ci_ref="$2"
    local reason="$3"
    local unrunnable="${4:-true}"
    record SKIP "$name" "$reason" "$ci_ref" "$unrunnable"
    SKIPPED_ANY=true
    if [ "$unrunnable" = "true" ]; then
        SKIPPED_UNRUNNABLE=true
    fi
    echo -e "${YELLOW}  ⊘ $name — $reason${NC}"
}

section() { echo -e "\n${BLUE}━━━ $1 ━━━${NC}"; }

# Run a command, reporting pass/fail under a gate name.
gate() {
    local name="$1"; shift
    local ci_ref="$1"; shift
    if "$@"; then
        pass "$name" "$ci_ref"
    else
        fail "$name" "$ci_ref"
    fi
}

# Prefer poetry when a lockfile-managed venv exists, else fall back to bare tools.
PY_RUNNER=()
VENV_BIN=""
if have poetry && [ -f "$ROOT_DIR/products/desktop/poetry.lock" ]; then
    PY_RUNNER=(poetry run)
    VENV_PATH="$(poetry --directory "$ROOT_DIR/products/desktop" env info -p 2>/dev/null || true)"
    if [ ! -x "$VENV_PATH/bin/pytest" ] && have git; then
        COMMON_GIT="$(git -C "$ROOT_DIR" rev-parse --path-format=absolute --git-common-dir 2>/dev/null || true)"
        COMMON_ROOT="${COMMON_GIT%/.git}"
        if [ -n "$COMMON_ROOT" ] && [ -f "$COMMON_ROOT/products/desktop/poetry.lock" ]; then
            VENV_PATH="$(poetry --directory "$COMMON_ROOT/products/desktop" env info -p 2>/dev/null || true)"
        fi
    fi
    if [ -x "$VENV_PATH/bin/pytest" ]; then
        VENV_BIN="$VENV_PATH/bin"
    fi
fi
PY_ARGV=()
py_resolve() {
    if [ -n "$VENV_BIN" ] && [ -x "$VENV_BIN/$1" ]; then
        local tool="$1"; shift
        PY_ARGV=("$VENV_BIN/$tool" "$@")
    elif [ ${#PY_RUNNER[@]} -gt 0 ]; then
        PY_ARGV=("${PY_RUNNER[@]}" "$@")
    else
        PY_ARGV=("$@")
    fi
}
py() {
    py_resolve "$@"
    "${PY_ARGV[@]}"
}

# GUI suites run on a private Xvfb display, never the developer's screen.
#
# CI renders every Tk and GTK test on a throwaway X server — desktop.yml starts
# `Xvfb :99 -screen 0 1280x1024x24` for both its `test` and `visual` jobs, and
# linux.yml wraps pytest in `xvfb-run -a`. Running the same tests against the
# ambient DISPLAY instead is not the same gate: window-manager decorations, the
# desktop's font stack and its DPI all feed into measured widget geometry, so
# `test_ui_control_occlusion.py` fails on a 96-dpi GNOME session with Cantarell
# 11 while passing in CI. It also violates the standing rule that a local test
# run never disturbs the developer's desktop (ai/rules/engineering-invariants.md).
#
# The geometry is CI's, verbatim, for both products: the visual baselines in
# products/desktop/tests/visual/baselines/ were rendered at 1280x1024x24, and
# comparing them against anything else produces false diffs.
XVFB_PREFIX=()
if [ "$(uname -s)" = "Linux" ] && have xvfb-run; then
    XVFB_PREFIX=(xvfb-run -a -s "-screen 0 1280x1024x24")
fi
# Like py(), but on its own X server. Use for any gate that renders widgets.
# The ${arr[@]+...} guard keeps an empty prefix expandable under `set -u` on
# macOS's bash 3.2, where a bare "${empty[@]}" aborts the script.
pyx() {
    py_resolve "$@"
    ${XVFB_PREFIX[@]+"${XVFB_PREFIX[@]}"} "${PY_ARGV[@]}"
}

# ── Format corpus (mirror: .github/actions/format-corpus, used by every product test job) ──
# The real-camera RAW corpus pinned in testdata/formats/manifest.json. CI downloads it and sets
# PST_REQUIRE_FORMAT_CORPUS=1, so each product's format tests run instead of skipping. Locally
# the corpus is used when present (python3 scripts/fetch_format_corpus.py fetches ~280 MB once);
# when absent, the product suites skip their corpus tests and this gate is reported SKIPPED.
section "Format corpus"
if ! have python3; then
    skip "format corpus" "format-corpus action" "python3 not found"
elif python3 "$ROOT_DIR/scripts/fetch_format_corpus.py" --check >/dev/null 2>&1; then
    PST_FORMAT_CORPUS="$(python3 "$ROOT_DIR/scripts/fetch_format_corpus.py" --print-dir)"
    export PST_FORMAT_CORPUS
    export PST_REQUIRE_FORMAT_CORPUS=1
    pass "format corpus present and verified ($PST_FORMAT_CORPUS)" "format-corpus action"
else
    skip "format corpus" "format-corpus action" \
        "corpus not downloaded — product corpus tests will skip (run: python3 scripts/fetch_format_corpus.py)"
fi

# ── Python gates (mirror: desktop.yml `lint`, `test`, `visual`) ────
if $run_python; then
    cd "$ROOT_DIR/products/desktop" || exit 1

    if ! have poetry && ! have pytest; then
        section "Python"
        skip "Python gates" "" "neither poetry nor pytest found (pip install poetry)"
    else
        # ── flake8 ────────────────────────────────────────────────
        # CI runs this as a *separate job that gates every other job*. A flake8
        # error therefore fails the whole pipeline before a single test runs,
        # which is why it must be the first thing checked locally too.
        section "Lint (flake8)"
        if $quick; then
            skip "flake8" "" "--quick"
        elif py flake8 --version &>/dev/null; then
            gate "flake8 src/ tests/" "desktop.yml:lint" \
                py flake8 src/ tests/ --count --show-source --statistics
        else
            skip "flake8" "" "flake8 not installed in the active environment"
        fi

        # ── pytest + coverage ─────────────────────────────────────
        # `-m "not visual"` matches CI's split: visual regression tests run in
        # their own job. Without the marker filter the local run and the CI run
        # execute different test sets.
        section "Python tests"
        if $quick; then
            gate "pytest (quick, no coverage gate)" "desktop.yml:test (partial)" \
                pyx pytest --tb=short -q -o faulthandler_timeout=120 -m "not visual"
        else
            # The coverage gate is authoritative on Linux (CI enforces it there
            # only, because linux_only/mac_only/windows_only markers skip
            # different tests per platform). Running it here still catches
            # genuine coverage regressions early.
            gate "pytest -m 'not visual' --cov-fail-under=$COV_FAIL_UNDER" "desktop.yml:test" \
                pyx pytest --tb=short -q -o faulthandler_timeout=120 \
                    --cov=photo_selector_toolbox \
                    --cov-report=term-missing:skip-covered \
                    --cov-fail-under="$COV_FAIL_UNDER" \
                    -m "not visual"
        fi

        # ── visual regression ─────────────────────────────────────
        # Marked `linux_only` in tests/visual/test_visual_regression.py: the
        # baselines are rendered under Xvfb, so macOS/Windows font metrics
        # produce false diffs. This gate is genuinely un-runnable off Linux —
        # it is reported as SKIPPED rather than silently omitted.
        section "Visual regression"
        if $quick; then
            skip "visual tests" "" "--quick"
        elif [ "$(uname -s)" != "Linux" ]; then
            skip "visual tests" "" "linux_only — baselines are Xvfb-rendered; CI job 'Visual regression tests' covers this"
        elif [ ${#XVFB_PREFIX[@]} -eq 0 ] && [ -z "${DISPLAY:-}" ]; then
            skip "visual tests" "" "no Xvfb and no DISPLAY (apt install xvfb)"
        else
            gate "pytest tests/visual/ -m visual" "desktop.yml:visual" \
                pyx pytest tests/visual/ --tb=short -q -o faulthandler_timeout=120 -m visual
        fi
    fi
elif ! $explicit_product; then
    section "Python gates (skipped)"
    skip "Python gates" "desktop.yml" "no changes under products/desktop/ (use --python or --all to force)" false
fi

# ── Android gates (mirror: android.yml `unit-tests`, `lint`) ──────
if $run_android; then
    cd "$ROOT_DIR/products/android" || exit 1

    if [ ! -f "./gradlew" ]; then
        section "Android"
        skip "Android gates" "" "gradlew not found in products/android/"
    else
        chmod +x ./gradlew 2>/dev/null || true

        section "Android unit tests (JVM)"
        gate "gradlew testDebugUnitTest" "android.yml:unit-tests" \
            ./gradlew testDebugUnitTest --stacktrace

        # ── androidTest compilation ───────────────────────────────
        # THIS IS THE GATE THAT USED TO ONLY EXIST IN CI.
        # `testDebugUnitTest` and `lintDebug` never compile tests/instrumented, so
        # an unresolved reference there (e.g. `hasTag` instead of `hasTestTag`)
        # survives every cheap gate and only fails inside the emulator job —
        # where it surfaces as a bare "process '/usr/bin/sh' failed with exit
        # code 1". Compiling the test APKs here validates the same Kotlin +
        # KSP/Hilt sources in ~2 minutes with no emulator.
        # Ordered AFTER the unit tests so a test-source compile break never
        # masks unit-test results.
        section "Android instrumented-test compilation"
        gate "gradlew assembleDebugAndroidTest (:android-desktop, :phototok)" "android.yml:unit-tests" \
            ./gradlew :android-desktop:assembleDebugAndroidTest :phototok:assembleDebugAndroidTest --stacktrace

        section "Android 16 KB ELF page alignment"
        gate "check_native_alignment.py (merged_native_libs)" "android.yml:unit-tests" \
            python3 "$ROOT_DIR/scripts/check_native_alignment.py" \
                "$ROOT_DIR/products/android/android-desktop/build/intermediates/merged_native_libs/debug" \
                "$ROOT_DIR/products/android/phototok/build/intermediates/merged_native_libs/debug"

        # android.yml:build packages the debug APKs and checks them too: ELF alignment plus the
        # zip-entry alignment of the stored .so files (finding Q-6) — only an APK has the latter.
        gate "gradlew assembleDebug" "android.yml:build" \
            ./gradlew assembleDebug --stacktrace
        gate "check_native_alignment.py (debug APKs: ELF + zip-entry alignment)" "android.yml:build" \
            python3 "$ROOT_DIR/scripts/check_native_alignment.py" \
                "$ROOT_DIR"/products/android/android-desktop/build/outputs/apk/debug/*.apk \
                "$ROOT_DIR"/products/android/phototok/build/outputs/apk/debug/*.apk

        # ── Android lint (advisory) ───────────────────────────────
        # Deliberately non-blocking: the CI job is `continue-on-error: true`
        # and both modules set `abortOnError = false`. Mirrored as advisory so
        # local and CI agree; the report is still printed for review.
        section "Android lint (advisory)"
        if $quick; then
            skip "android lint" "" "--quick"
        elif ./gradlew :android-desktop:lintDebug :phototok:lintDebug --continue --stacktrace; then
            pass "gradlew lintDebug (advisory)" "android.yml:lint"
        else
            warn "gradlew lintDebug (advisory — does not fail CI)" "android.yml:lint"
        fi
    fi
elif ! $explicit_product; then
    section "Android gates (skipped)"
    skip "Android gates" "android.yml" "no changes under products/android/ (use --android or --all to force)" false
fi

# ── Android instrumented tests (mirror: android.yml `instrumented-tests`) ──
# These require a real Android runtime. They are the ONLY gate that cannot be
# satisfied without an emulator or device — see docs/build/CI_PARITY.md for why the
# Compose semantics-tree assertions in them genuinely need one.
if $run_android_device; then
    section "Android instrumented tests (device/emulator)"
    cd "$ROOT_DIR/products/android" || exit 1

    # Gradle installs on every attached device, so the run is pinned to one test device
    # (ANDROID_SERIAL, honoured by the Android Gradle plugin). Selection and preparation
    # live in scripts/android_test_device.py; see docs/build/CI_PARITY.md. The device suite is
    # run on demand, so a missing device is a stated gap, never a blocker (not even --strict).
    if ! TEST_SERIAL=$(python3 "$ROOT_DIR/scripts/android_test_device.py" serial 2>&1); then
        skip "instrumented tests" "" "$TEST_SERIAL — see docs/build/CI_PARITY.md (Instrumented tests)" false
    else
        echo -e "${DIM}  test device: $TEST_SERIAL ($(adb -s "$TEST_SERIAL" shell getprop ro.product.model 2>/dev/null | tr -d '\r'))${NC}"
        # Before the export: prepare must see whether the device was designated or merely the
        # only one attached (it refuses to change settings on an undesignated device).
        if ! python3 "$ROOT_DIR/scripts/android_test_device.py" prepare; then
            warn "test device not prepared (see above)" "local-only"
        fi
        export ANDROID_SERIAL="$TEST_SERIAL"
        chmod +x ./gradlew 2>/dev/null || true
        gate "gradlew connectedDebugAndroidTest" "android.yml:instrumented-tests" \
            ./gradlew :android-desktop:connectedDebugAndroidTest :phototok:connectedDebugAndroidTest --stacktrace
    fi
fi

# ── macOS Desktop native Swift gates (mirror: macos.yml `swift-test`) ──
if $run_macos; then
    section "macOS Desktop native Swift tests"
    if [ "$(uname -s)" != "Darwin" ]; then
        skip "swift test (macos-desktop)" "macos.yml:swift-test" "macOS native product requires Darwin runner" false
    elif ! have swift; then
        skip "swift test (macos-desktop)" "macos.yml:swift-test" "swift toolchain not found"
    else
        SWIFT_TEST_ARGS=(-c release --no-parallel --package-path "$ROOT_DIR/products/macos-desktop")
        CLT_TESTING_MACRO="/Library/Developer/CommandLineTools/usr/lib/swift/host/plugins/testing/libTestingMacros.dylib"
        if [ -f "$CLT_TESTING_MACRO" ]; then
            SWIFT_TEST_ARGS+=("-Xswiftc" "-load-plugin-library" "-Xswiftc" "$CLT_TESTING_MACRO")
        fi
        gate "swift test (products/macos-desktop)" "macos.yml:swift-test" \
            swift test "${SWIFT_TEST_ARGS[@]}"

        # macos.yml builds the release binary and refuses one that carries the UI test hooks.
        gate "swift build -c release (products/macos-desktop)" "macos.yml:swift-test" \
            swift build -c release --package-path "$ROOT_DIR/products/macos-desktop"
        gate "release binary carries no UI test hooks" "macos.yml:swift-test" \
            "$ROOT_DIR/products/macos-desktop/scripts/check_no_test_hooks.sh" \
            "$ROOT_DIR/products/macos-desktop/.build/release/PhotoSelectorApp"

        # Local-only gate: drives the RUNNING app (activation policy, real key events,
        # rendered Focus 3-Up geometry, clicks while zoomed, time-to-first-photo). Unit
        # tests alone passed while arrow keys were dead in the real app.
        # See docs/build/CI_PARITY.md § macOS UI smoke test.
        if $quick; then
            skip "macOS UI smoke test" "local-only" "--quick skips the GUI smoke test" false
        else
            "$ROOT_DIR/products/macos-desktop/scripts/ui_smoke_test.sh"
            smoke_status=$?
            if [ "$smoke_status" -eq 0 ]; then
                pass "macOS UI smoke test (running app)" "local-only"
            elif [ "$smoke_status" -eq 3 ]; then
                skip "macOS UI smoke test" "local-only" "no GUI (Aqua) session — log in to the desktop to run it"
            else
                fail "macOS UI smoke test (running app)" "local-only"
            fi
        fi
    fi
elif ! $explicit_product; then
    section "macOS Desktop gates (skipped)"
    skip "macOS Desktop gates" "macos.yml" "no changes under products/macos-desktop/ (use --macos or --all to force)" false
fi

# ── Linux Desktop gates (mirror: linux.yml `lint`, `test`, `package`) ──
if $run_linux; then
    section "Linux Desktop gates"
    if [ ! -d "$ROOT_DIR/products/linux-desktop" ]; then
        skip "Linux Desktop gates" "linux.yml" "products/linux-desktop not yet created (planned for M3)" false
    else
        cd "$ROOT_DIR/products/linux-desktop" || exit 1

        # 1. Lint / Static analysis
        if [ -d "$ROOT_DIR/products/linux-desktop/src" ]; then
            section "Linux Desktop lint (flake8)"
            if $quick; then
                skip "flake8 (linux-desktop)" "linux.yml:lint" "--quick"
            elif py flake8 --version &>/dev/null; then
                gate "flake8 (products/linux-desktop)" "linux.yml:lint" \
                    py flake8 src/ tests/ --count --show-source --statistics --max-line-length=120 --extend-ignore=E203,W503
            elif have flake8; then
                gate "flake8 (products/linux-desktop)" "linux.yml:lint" \
                    flake8 src/ tests/ --count --show-source --statistics --max-line-length=120 --extend-ignore=E203,W503
            else
                skip "flake8 (linux-desktop)" "linux.yml:lint" "flake8 not installed in the active environment"
            fi
        fi

        # 2. Unit tests (pytest)
        section "Linux Desktop unit tests"
        if [ -d "$ROOT_DIR/products/linux-desktop/tests" ]; then
            # Without the real GTK 4 / GExiv2 typelibs (macOS, or a bare Linux box) conftest.py
            # stubs gi, so the display decode path (ui/photo_loader.py) never runs and the
            # format-conformance display checks fall back to Pillow. Say so instead of
            # reporting the full CI gate as passed.
            linux_ci_ref="linux.yml:test"
            if ! py python -c "import gi; gi.require_version('Gdk', '4.0'); gi.require_version('GExiv2', '0.10'); from gi.repository import Gdk, GExiv2" &>/dev/null; then
                linux_ci_ref="linux.yml:test (partial — no GTK 4/GExiv2 typelibs, display path stubbed)"
            fi
            if ! have pytest && ! have poetry; then
                skip "pytest (linux-desktop)" "linux.yml:test" "neither poetry nor pytest found"
            elif $quick; then
                gate "pytest (products/linux-desktop, quick)" "$linux_ci_ref" \
                    pyx pytest tests/ --tb=short -q
            elif py pytest --version &>/dev/null; then
                gate "pytest --cov-fail-under=$LINUX_COV_FAIL_UNDER (products/linux-desktop)" "$linux_ci_ref" \
                    pyx pytest tests/ --tb=short -q -o faulthandler_timeout=120 \
                        --cov=photo_selector_linux \
                        --cov-report=term-missing:skip-covered \
                        --cov-fail-under="$LINUX_COV_FAIL_UNDER"
            elif have pytest; then
                gate "pytest --cov-fail-under=$LINUX_COV_FAIL_UNDER (products/linux-desktop)" "$linux_ci_ref" \
                    pyx pytest tests/ --tb=short -q -o faulthandler_timeout=120 \
                        --cov=photo_selector_linux \
                        --cov-report=term-missing:skip-covered \
                        --cov-fail-under="$LINUX_COV_FAIL_UNDER"
            else
                skip "pytest (linux-desktop)" "linux.yml:test" "pytest runner not found"
            fi
        else
            skip "pytest (linux-desktop)" "linux.yml:test" "tests/ directory not yet created" false
        fi

        # 3. Debian packaging validation
        section "Linux Desktop packaging & APT validation"
        if [ -f "$ROOT_DIR/products/linux-desktop/scripts/package_debian.sh" ]; then
            gate "debian packaging & apt repo (products/linux-desktop)" "linux.yml:package" \
                bash ./scripts/package_debian.sh
        else
            skip "debian packaging (linux-desktop)" "linux.yml:package" "packaging scripts not yet created (planned for M4)" false
        fi
    fi
elif ! $explicit_product; then
    section "Linux Desktop gates (skipped)"
    skip "Linux Desktop gates" "linux.yml" "no changes under products/linux-desktop/ (use --linux or --all to force)" false
fi

# ── Repository hygiene (mirror: repo-hygiene.yml) ──────────────────
# Public gates: the README stays true to the products, and nothing from the maintainer's
# private overlay (AI framework, requirement specs, prompts, memory) is tracked publicly.
section "Repository hygiene"
cd "$ROOT_DIR" || exit 1
if have python3; then
    gate "readme parity" "repo-hygiene.yml:hygiene" \
        python3 scripts/check_readme_parity.py --check
    gate "no private-overlay material tracked" "repo-hygiene.yml:hygiene" \
        python3 scripts/check_private_paths.py
    gate "release metadata consistent" "repo-hygiene.yml:hygiene" \
        python3 scripts/release.py check
    gate "format contract manifest" "repo-hygiene.yml:hygiene" \
        python3 scripts/fetch_format_corpus.py --validate
    gate "format corpus tooling tests" "repo-hygiene.yml:hygiene" \
        python3 -m unittest discover -s scripts/tests -q
    gate "lab tooling tests" "local-only" \
        python3 -m unittest discover -s scripts/lab/tests -q
else
    skip "readme parity" "" "python3 not found"
    skip "no private-overlay material tracked" "" "python3 not found"
    skip "release metadata consistent" "" "python3 not found"
    skip "format contract manifest" "" "python3 not found"
    skip "format corpus tooling tests" "" "python3 not found"
    skip "lab tooling tests" "" "python3 not found"
fi

# ── Private overlay gates (mirror: photo-selector-private framework.yml) ──
# The agent framework and the requirement specs live in the private overlay (private/,
# linked into this tree). Its gates run here when the overlay is checked out, and in the
# private repository's own CI; a public clone has nothing to check and says so.
if [ -d "$ROOT_DIR/private/.git" ] || [ -f "$ROOT_DIR/private/.git" ]; then
    section "Private overlay (agent framework & requirements)"
    if have python3; then
        gate "overlay links" "framework.yml:framework" \
            python3 private/scripts/link_overlay.py --check
        gate "toolchain sync" "framework.yml:framework" \
            python3 ai/skills/sync-framework/scripts/sync_framework.py --check
        gate "framework validation" "framework.yml:framework" \
            python3 ai/skills/sync-framework/scripts/validate_framework.py
        gate ".gemini/settings.json in sync" "framework.yml:framework" \
            python3 ai/skills/sync-framework/scripts/gen_gemini_settings.py --check
        gate "requirements traceability" "framework.yml:framework" \
            python3 private/scripts/verify_requirements_traceability.py --check --strict
        if python3 -c "import pytest" &>/dev/null; then
            gate "framework & hook tests" "framework.yml:framework" \
                python3 -m pytest -q -p no:cacheprovider private/tests/framework
        elif [ -n "$VENV_BIN" ] && [ -x "$VENV_BIN/pytest" ]; then
            gate "framework & hook tests" "framework.yml:framework" \
                "$VENV_BIN/pytest" -q -p no:cacheprovider private/tests/framework
        else
            skip "framework & hook tests" "framework.yml:framework" "pytest not importable by python3"
        fi
    else
        skip "private overlay gates" "" "python3 not found"
    fi
else
    section "Private overlay (not present)"
    skip "private overlay gates" "" "no private/ checkout — public clone, nothing to check" false
fi

# ── Summary ───────────────────────────────────────────────────────
echo ""
echo -e "${BLUE}━━━ Summary (gate → CI equivalent) ━━━${NC}"
while IFS='|' read -r status name ci_ref _ci _unrunnable; do
    [ -z "${status:-}" ] && continue
    case "$status" in
        PASS) printf "${GREEN}  ✔ %-52s${NC} ${DIM}%s${NC}\n" "$name" "$ci_ref" ;;
        FAIL) printf "${RED}  ✘ %-52s${NC} ${DIM}%s${NC}\n" "$name" "$ci_ref" ;;
        WARN) printf "${YELLOW}  ! %-52s${NC} ${DIM}%s${NC}\n" "$name" "$ci_ref" ;;
        SKIP) printf "${YELLOW}  ⊘ %-52s${NC} ${DIM}%s${NC}\n" "$name" "$ci_ref" ;;
    esac
done < "$RESULTS_FILE"

# ── Verification record (CONTRIBUTING.md § Verification record) ──
# Which machine ran which gates on exactly which tree, for the PR description. Written for
# failed and partial runs too: a PR states how far it was tested, not only that it passed.
write_verification_record() {
    [ "${PST_NO_RECORD:-0}" = "1" ] && return 0
    echo ""
    if ! have python3; then
        echo -e "${DIM}Verification record not written: python3 not found.${NC}"
        return 0
    fi
    cd "$ROOT_DIR" || return 0
    python3 "$ROOT_DIR/scripts/verification_record.py" --root "$ROOT_DIR" write \
        --results "$RESULTS_FILE" --args="$RUN_ARGS" --exit-code "$1" --started "$RUN_STARTED" \
        || echo -e "${YELLOW}Verification record not written (see the error above).${NC}"
}

echo ""
if [ $EXIT_CODE -ne 0 ]; then
    echo -e "${RED}FAILED — one or more gates failed. CI will fail too.${NC}"
    write_verification_record 1
    exit 1
fi

if $SKIPPED_ANY; then
    echo -e "${YELLOW}PASSED WITH SKIPS — some gates were not run.${NC}"
    echo -e "${DIM}Review the ⊘ lines above before assuming full verification.${NC}"
    if $strict && $SKIPPED_UNRUNNABLE; then
        echo -e "${RED}--strict: treating unrunnable skipped gates as failure.${NC}"
        write_verification_record 2
        exit 2
    fi
    write_verification_record 0
    exit 0
fi

echo -e "${GREEN}ALL GATES PASSED — this machine reproduced the full CI gate set.${NC}"
write_verification_record 0
exit 0
