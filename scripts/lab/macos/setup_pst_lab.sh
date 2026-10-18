#!/usr/bin/env bash
# One-time setup of the macOS lab account (C-32). Run on the Mac, logged in as `pst-lab`:
#
#   curl -fsSL https://raw.githubusercontent.com/alexpp90/photo-selector/main/scripts/lab/macos/setup_pst_lab.sh | bash
#   (or copy this file over and run it)
#
# It clones the public repository to ~/pst/photo-selector, installs the LaunchAgent that runs a
# lab pass whenever the Linux hub writes ~/pst/trigger/run.json, and checks the toolchain.
# Prerequisites (done once by an administrator): the `pst-lab` user exists, System Settings ->
# General -> Sharing -> Remote Login is on for it, and the hub's public key is in
# ~/.ssh/authorized_keys of `pst-lab`. Keep `pst-lab` logged in (fast user switching) so its
# GUI session exists; `lab.py devices --with-personal` reports "pst-lab not logged in" otherwise.
set -euo pipefail

REPO_URL="${PST_LAB_REPO_URL:-https://github.com/alexpp90/photo-selector.git}"
REPO_DIR="$HOME/pst/photo-selector"
AGENT_LABEL="com.pstlab.runner"
AGENT_PLIST="$HOME/Library/LaunchAgents/$AGENT_LABEL.plist"

if [[ "$(id -un)" != "pst-lab" ]]; then
  echo "warning: running as $(id -un), not pst-lab; the LaunchAgent paths assume /Users/pst-lab" >&2
fi

if ! xcrun --find swift >/dev/null 2>&1; then
  echo "error: Swift toolchain missing. Run: xcode-select --install   (then re-run this script)" >&2
  exit 1
fi

free_gb=$(df -g "$HOME" | awk 'NR==2 {print $4}')
if [[ "${free_gb:-0}" -lt 4 ]]; then
  echo "error: only ${free_gb} GB free on this disk; the clone and a release build need about 3 GB." >&2
  echo "       Free some space (or set PST_LAB_ALLOW_LOW_DISK=1 to try anyway)." >&2
  [[ -n "${PST_LAB_ALLOW_LOW_DISK:-}" ]] || exit 1
fi

# The lab writes real JPEG fixtures with Pillow and reads its YAML with PyYAML; the
# Command Line Tools' python3 has neither.
/usr/bin/python3 -m pip install --user --quiet --disable-pip-version-check pillow pyyaml

mkdir -p "$HOME/pst/trigger" "$HOME/pst/out" "$HOME/Library/LaunchAgents"
if [[ -d "$REPO_DIR/.git" ]]; then
  git -C "$REPO_DIR" fetch --all --prune
else
  git clone "$REPO_URL" "$REPO_DIR"
fi

sed "s|/Users/pst-lab|$HOME|g" "$REPO_DIR/scripts/lab/macos/com.pstlab.runner.plist" >"$AGENT_PLIST"
launchctl bootout "gui/$(id -u)/$AGENT_LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$AGENT_PLIST"

echo "ok: $AGENT_LABEL installed; repo at $REPO_DIR"
echo "Building once to warm the SwiftPM cache (a few minutes)..."
(cd "$REPO_DIR/products/macos-desktop" && ./scripts/build_app.sh release --test-hooks)
echo "ok: the hub can now run: python3 scripts/lab/lab.py run --product macos --with-personal"
