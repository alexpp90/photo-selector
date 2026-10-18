#!/usr/bin/env bash
# Fails when a macOS binary contains the UI test hooks (finding Q-2).
#
# `--ui-scenario`, `--ui-self-test` and `--screenshot` drive the running app and can trash
# files, so they are compiled in only for debug builds and for `build_app.sh --test-hooks`
# (the lab and the smoke test). A distributable build must carry none of their types.
#
# Usage: check_no_test_hooks.sh <binary-or-.app> [...]
set -euo pipefail

if [[ $# -eq 0 ]]; then
    echo "usage: $0 <binary-or-.app> [...]" >&2
    exit 2
fi

status=0
for target in "$@"; do
    binary="$target"
    if [[ -d "$target" ]]; then
        binary="$target/Contents/MacOS/PhotoSelectorApp"
    fi
    if [[ ! -f "$binary" ]]; then
        echo "✘ $binary: not found" >&2
        status=1
        continue
    fi
    # Swift mangles type names into the symbol table, so the names survive in the binary.
    if strings -a "$binary" | grep -E -q 'UIScenarioRunner|UITestDriver|UISelfTestRunner|UISelfTestHooks'; then
        echo "✘ $binary: contains UI test hooks (built with PST_TEST_HOOKS?)" >&2
        status=1
    else
        echo "✔ $binary: no UI test hooks"
    fi
done
exit "$status"
