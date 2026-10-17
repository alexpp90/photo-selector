#!/usr/bin/env bash
# macOS Desktop UI smoke test — exercises the *running* app, not just the Kit.
#
# The keyboard router's unit tests passed for months while arrow keys did nothing in the
# real app (the process was never allowed to become active). This script therefore:
#   1. builds the app and generates camera-sized landscape fixtures (+ excluded decoys);
#   2. runs the in-app self-test (`--ui-self-test`) against BOTH the raw SwiftPM binary
#      (the `swift run` path that used to fail) and the assembled PhotoSelector.app;
#   3. on the raw binary, also sends a real arrow key through the window server via
#      System Events (osascript). That step needs Accessibility permission for the calling
#      terminal; without it the step is reported SKIPPED, never silently passed.
#
# Usage: products/macos-desktop/scripts/ui_smoke_test.sh [--count N] [--no-external-key]
# Exit codes: 0 all checks passed, 1 a check failed, 3 no GUI session available.
set -euo pipefail

PRODUCT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COUNT=120
EXTERNAL_KEY=0
if [[ -n "${PST_SHOW_GUI:-}" && "${PST_SHOW_GUI}" != "0" ]]; then
    EXTERNAL_KEY=1
fi
while [[ $# -gt 0 ]]; do
    case "$1" in
        --count) COUNT="$2"; shift 2 ;;
        --external-key) EXTERNAL_KEY=1; shift ;;
        --no-external-key) EXTERNAL_KEY=0; shift ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
done

# A GUI test needs a logged-in Aqua session (not SSH-only, not a headless CI runner).
if ! launchctl print "gui/$(id -u)" >/dev/null 2>&1; then
    echo "⊘ SKIPPED: no GUI (Aqua) session for $(id -un)" >&2
    exit 3
fi

WORK="$(mktemp -d -t pst-macos-ui)"
cleanup() {
    rm -rf "$WORK"
    if [[ "$EXTERNAL_KEY" -eq 1 && -n "${PREV_APP:-}" && "${PREV_APP}" != "PhotoSelectorApp" && "${PREV_APP}" != "PhotoSelector" ]]; then
        osascript -e 'with timeout of 1 second' -e 'tell application "'"$PREV_APP"'" to activate' -e 'end timeout' >/dev/null 2>&1 || true
    fi
}
trap cleanup EXIT
PREV_APP=""
if [[ "$EXTERNAL_KEY" -eq 1 ]]; then
    PREV_APP="$(osascript -e 'with timeout of 1 second' -e 'tell application "System Events" to get name of first process whose frontmost is true' -e 'end timeout' 2>/dev/null || true)"
fi
FIXTURES="$WORK/fixtures"

echo "• building app bundle (release, with UI test hooks)…"
APP="$("$PRODUCT_DIR/scripts/build_app.sh" release --test-hooks | tail -n 1)"
RAW_BIN="$(PST_TEST_HOOKS=1 swift build -c release --package-path "$PRODUCT_DIR" --scratch-path "$PRODUCT_DIR/.build/hooks" --show-bin-path)/PhotoSelectorApp"

echo "• generating $COUNT fixtures…"
EXPECTED="$(swift "$PRODUCT_DIR/scripts/make_fixtures.swift" "$FIXTURES" "$COUNT" | tail -n 1)"

FAILED=0
EXTERNAL_RESULT="not-run"

run_self_test() {
    local label="$1" binary="$2" external_wait="$3"
    local report="$WORK/report-$label.json"
    echo "• self-test: $label"
    "$binary" --ui-self-test "$FIXTURES" --report "$report" --expect-count "$EXPECTED" \
        --external-key-wait "$external_wait" 2>"$WORK/stderr-$label.log" &
    local pid=$!

    if [[ "$external_wait" != "0" ]]; then
        # Wait for the app to announce it is ready for an external key, then send → (124).
        for _ in $(seq 1 600); do
            [[ -f "$report.ready" ]] && break
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.1
        done
        if [[ -f "$report.ready" ]]; then
            if [[ -n "${PST_SHOW_GUI:-}" && "${PST_SHOW_GUI}" != "0" ]]; then
                osascript -e 'tell application "System Events" to set frontmost of first process whose unix id is '"$pid"' to true' >/dev/null 2>&1 || true
                sleep 0.2
            fi
            if osascript -e 'with timeout of 5 seconds' -e 'tell application "System Events" to key code 124' -e 'end timeout' >/dev/null 2>"$WORK/osascript.err"; then
                EXTERNAL_RESULT="sent"
            else
                EXTERNAL_RESULT="skipped"
            fi
        fi
    fi

    local status=0
    wait "$pid" || status=$?
    grep '\[ui-self-test\]' "$WORK/stderr-$label.log" | sed 's/^/    /' || true
    if [[ ! -f "$report" ]]; then
        echo "  ✗ $label: no report written (exit $status)"
        sed 's/^/    /' "$WORK/stderr-$label.log" | tail -n 20
        FAILED=1
        return
    fi
    python3 - "$report" "$label" <<'PY'
import json, sys
report = json.load(open(sys.argv[1]))
t = report.get("timings", {})
print(f"    timings: first listed {t.get('first_photo_listed_s', float('nan')):.3f}s · "
      f"first drawn {t.get('first_photo_drawn_s', float('nan')):.3f}s · "
      f"scan complete {t.get('scan_complete_s', float('nan')):.3f}s")
print(f"    external key: {report.get('externalKey')}")
PY
    if [[ "$status" -ne 0 ]]; then
        echo "  ✗ $label: self-test failed (exit $status)"
        FAILED=1
    else
        echo "  ✓ $label: all checks passed"
    fi
    if [[ "$external_wait" != "0" ]]; then
        local observed
        observed="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["externalKey"])' "$report")"
        case "$EXTERNAL_RESULT:$observed" in
            sent:delivered) echo "  ✓ window-server arrow key delivered to the app" ;;
            sent:*) echo "  ✗ window-server arrow key was sent but the app did not react"; FAILED=1 ;;
            skipped:*) echo "  ⊘ SKIPPED window-server key probe: grant Accessibility to this terminal ($(head -c 160 "$WORK/osascript.err" 2>/dev/null))" ;;
            *) echo "  ⊘ SKIPPED window-server key probe: app never became ready" ;;
        esac
    fi
}

EXTERNAL_WAIT=0
[[ "$EXTERNAL_KEY" -eq 1 ]] && EXTERNAL_WAIT=6
run_self_test "raw-binary" "$RAW_BIN" "$EXTERNAL_WAIT"
run_self_test "app-bundle" "$APP/Contents/MacOS/PhotoSelectorApp" 0

if [[ "$FAILED" -ne 0 ]]; then
    echo "✗ macOS UI smoke test FAILED"
    exit 1
fi
echo "✓ macOS UI smoke test passed"
