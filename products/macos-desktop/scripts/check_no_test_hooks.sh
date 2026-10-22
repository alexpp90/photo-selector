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
    # Swift mangles type names into the symbol table, so the names survive in the binary. The
    # literals are strings that exist only inside `#if PST_TEST_HOOKS` code (checked by diffing
    # `strings` of a hooks build against a release build, 2026-10-21). `--ui-scenario` and friends
    # are NOT listed: a release build keeps them on purpose, to refuse them with exit 64.
    hook_pattern='UIScenarioRunner|UITestDriver|UISelfTestRunner|UISelfTestHooks'
    hook_pattern+='|scenario-artifacts|layout\.boundary_slot_retains_frame|slot_next at last photo='
    if strings -a "$binary" | grep -E -q "$hook_pattern"; then
        echo "✘ $binary: contains UI test hooks (built with PST_TEST_HOOKS?)" >&2
        status=1
    else
        echo "✔ $binary: no UI test hooks"
    fi
done
exit "$status"
