#!/usr/bin/env bash
# Executed inside pst-lab's Aqua session by com.pstlab.runner.plist when ~/pst/trigger/run.json changes (C-32).
set -euo pipefail

TRIGGER_JSON="$HOME/pst/trigger/run.json"
if [[ ! -f "$TRIGGER_JSON" ]]; then
  exit 0
fi

REPO_DIR="${PST_LAB_REPO_DIR:-$HOME/pst/photo-selector}"
RUN_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["run_id"])' "$TRIGGER_JSON")"
COMMIT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["commit"])' "$TRIGGER_JSON")"

OUT_DIR="$HOME/pst/out/$RUN_ID"
if [[ -f "$OUT_DIR/done" ]]; then
  exit 0
fi
mkdir -p "$OUT_DIR"

cd "$REPO_DIR"
# The LaunchAgent runs this file from the clone, so the checkout below replaces it while bash is
# still reading it, and bash goes on with the old text (LAB-20, 2026-10-19: the first run after a
# change to this script built without --test-hooks; 6/6 journeys "no UI test hooks" in 2 s). After
# the checkout, start again from the checked-out script; the guard stops a second checkout.
if [[ "${PST_LAB_CHECKED_OUT:-}" != "$COMMIT" ]]; then
  git fetch --all --prune
  git checkout "$COMMIT"
  PST_LAB_CHECKED_OUT="$COMMIT" exec bash "$REPO_DIR/scripts/lab/macos/agent_run.sh" "$@"
fi

# The journeys drive the app through its UI test hooks, which only a --test-hooks build contains.
(cd products/macos-desktop && ./scripts/build_app.sh release --test-hooks)
mkdir -p "$HOME/Applications"
rm -rf "$HOME/Applications/PhotoSelector.app"
cp -R products/macos-desktop/.build/hooks/app/PhotoSelector.app "$HOME/Applications/PhotoSelector.app"

# Each journey generates its own small fixture set under the lab's scratch root (/tmp/pstlab,
# or /tmp/pstlab-<uid> when another account owns that); no full fixture pass (the large set
# alone is several hundred MB on a laptop disk).
python3 scripts/lab/lab.py run --product macos --out "$OUT_DIR"
if [[ -n "${PST_LAB_TMP:-}" ]]; then scratch=("$PST_LAB_TMP"); else scratch=(/tmp/pstlab "/tmp/pstlab-$(id -u)"); fi
for d in "${scratch[@]}"; do
  if [[ -O "$d" ]]; then rm -rf "$d"; fi
done
touch "$OUT_DIR/done"
