#!/usr/bin/env bash
# Private headless X11 + D-Bus laboratory session wrapper for Linux Desktop (C-41).
# Never touches DISPLAY=:0 or the user's real session.
set -euo pipefail

DISPLAY_NUM=""
SIZE="1920x1080"
SCALE=1
COLOR_SCHEME="default"
TEXT_SCALING="1.0"
LAB_DIR="/tmp/pstlab"
ARTIFACTS_DIR="/tmp/pstlab-artifacts"
FIXTURE_DIR=""
USE_INSTALLED=0
SCRIPT_FILE=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --display-num) DISPLAY_NUM="$2"; shift 2 ;;
    --size) SIZE="$2"; shift 2 ;;
    --scale) SCALE="$2"; shift 2 ;;
    --color-scheme) COLOR_SCHEME="$2"; shift 2 ;;
    --text-scaling) TEXT_SCALING="$2"; shift 2 ;;
    --lab-dir) LAB_DIR="$2"; shift 2 ;;
    --artifacts) ARTIFACTS_DIR="$2"; shift 2 ;;
    --fixture) FIXTURE_DIR="$2"; shift 2 ;;
    --installed) USE_INSTALLED=1; shift ;;
    --script) SCRIPT_FILE="$2"; shift 2 ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

case "$LAB_DIR" in
  /tmp/pstlab* | /var/tmp/pstlab*) ;;
  *) echo "Refusing to use non-lab directory: $LAB_DIR" >&2; exit 2 ;;
esac

mkdir -p "$LAB_DIR/xdg-data" "$LAB_DIR/xdg-config" "$ARTIFACTS_DIR"

# Text scaling: GTK4 on X11 takes it from XSETTINGS (gsd-xsettings, absent here), so a
# `gsettings set` in the private bus never reaches the app (2026-10-17: large-text rendered
# exactly like fhd). GTK reads settings.ini from XDG_CONFIG_HOME, but the X11 backend pins
# gtk-xft-dpi to 96 without an XSETTINGS manager; the base font size is what does apply, so the
# factor scales the 10 pt default font (measured with Gtk.Settings under Xvfb, GTK 4.18).
mkdir -p "$LAB_DIR/xdg-config/gtk-4.0"
FONT_PT=$(LC_ALL=C awk -v f="$TEXT_SCALING" 'BEGIN { printf "%.2f", 10 * f }')
printf '[Settings]\ngtk-font-name=Sans %s\n' "$FONT_PT" >"$LAB_DIR/xdg-config/gtk-4.0/settings.ini"

# A free display unless one is named: a fixed :99 collided with other sessions' xvfb-run
# (2026-10-17), and the app then failed with "Can't open display". -displayfd makes Xvfb
# pick the number and report it once it accepts connections.
DISPLAY_FILE="$LAB_DIR/xvfb.display"
rm -f "$DISPLAY_FILE"
if [[ -n "$DISPLAY_NUM" ]]; then
  Xvfb ":${DISPLAY_NUM}" -screen 0 "${SIZE}x24" -nolisten tcp >/dev/null 2>&1 &
else
  Xvfb -displayfd 5 -screen 0 "${SIZE}x24" -nolisten tcp 5>"$DISPLAY_FILE" >/dev/null 2>&1 &
fi
XVFB_PID=$!
cleanup() {
  kill "$XVFB_PID" >/dev/null 2>&1 || true
}
trap cleanup EXIT

if [[ -z "$DISPLAY_NUM" ]]; then
  for _ in $(seq 1 100); do
    [[ -s "$DISPLAY_FILE" ]] && break
    sleep 0.05
  done
  DISPLAY_NUM=$(tr -dc '0-9' <"$DISPLAY_FILE")
  [[ -n "$DISPLAY_NUM" ]] || { echo "Xvfb did not report a display" >&2; exit 1; }
else
  sleep 0.3
fi

export DISPLAY=":${DISPLAY_NUM}"
export GDK_BACKEND=x11
export GDK_SCALE="${SCALE}"
export XDG_DATA_HOME="${LAB_DIR}/xdg-data"
export XDG_CONFIG_HOME="${LAB_DIR}/xdg-config"
export PST_LAB=1
# Xvfb has no GPU: GTK's default Vulkan/GL renderer paints black there; cairo is software and
# deterministic. No portals: in a private bus the desktop portal blocks ~25 s on a missing
# secrets service before the window shows, and the lab never uses portal dialogs.
export GSK_RENDERER="${GSK_RENDERER:-cairo}"
export GDK_DEBUG="${GDK_DEBUG:+$GDK_DEBUG,}no-portals"
export GTK_USE_PORTAL=0
# Libadwaita reads the colour scheme through the Settings portal on its own (GDK_DEBUG does not
# cover it); that activation is what still cost ~25 s per journey on 2026-10-17.
export ADW_DISABLE_PORTAL=1
export PST_LAB_ARTIFACTS="${ARTIFACTS_DIR}"

if [[ "$USE_INSTALLED" -eq 1 ]]; then
  APP_CMD=(photo-selector-linux "$FIXTURE_DIR")
else
  # PST_LAB_PYTHON: an interpreter with the distribution's PyGObject (the lab runner sets it).
  APP_CMD=("${PST_LAB_PYTHON:-python3}" -m photo_selector_linux "$FIXTURE_DIR")
fi

dbus-run-session -- bash -c '
  set -euo pipefail
  gsettings set org.gnome.desktop.interface color-scheme "'"${COLOR_SCHEME}"'" >/dev/null 2>&1 || true
  gsettings set org.gnome.desktop.interface text-scaling-factor "'"${TEXT_SCALING}"'" >/dev/null 2>&1 || true

  "$@" >"'"${ARTIFACTS_DIR}"'/journey.log" 2>&1 &
  APP_PID=$!
  trap "kill \$APP_PID >/dev/null 2>&1 || true" EXIT

  for _ in $(seq 1 300); do
    if xdotool search --onlyvisible --name "Blitzlens" >/dev/null 2>&1; then
      # No window manager under Xvfb: the window keeps its default size unless it is sized
      # here, so every config would test the same 1440x900 window (or one larger than a
      # hidpi screen). Fill the screen, as a maximised window would.
      xdotool search --onlyvisible --name "Blitzlens" \
        windowmove --sync 0 0 windowsize --sync 100% 100% windowactivate --sync >/dev/null 2>&1 || true
      break
    fi
    sleep 0.1
  done
  sleep 1

  if [[ -n "'"${SCRIPT_FILE}"'" && -f "'"${SCRIPT_FILE}"'" ]]; then
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ -z "$line" || "$line" =~ ^# ]] && continue
      cmd="${line%% *}"
      arg="${line#* }"
      case "$cmd" in
        checkpoint)
          if ! gapplication action app.alpatix.Blitzlens lab-checkpoint "'"'"'${arg}'"'"'" >/dev/null 2>&1; then
            gdbus call --session \
              --dest app.alpatix.Blitzlens \
              --object-path /app/alpatix/Blitzlens \
              --method org.gtk.Actions.Activate \
              "lab-checkpoint" "[<'"'"'${arg}'"'"'>]" "{}" >/dev/null 2>&1 || true
          fi
          ;;
        key)
          xdotool key --clearmodifiers "$arg"
          ;;
        sleep)
          sleep "$arg"
          ;;
        await|fs)
          # Hub-side file-system steps (scripts/lab/lab/linux/hub_step.py), set up by the runner.
          if [[ -n "${PST_LAB_HUB_STEP:-}" ]]; then
            "${PST_LAB_HUB_PYTHON:-python3}" "$PST_LAB_HUB_STEP" "$cmd" "$arg" || true
          fi
          ;;
      esac
    done < "'"${SCRIPT_FILE}"'"
  fi

  kill "$APP_PID" >/dev/null 2>&1 || true
  wait "$APP_PID" >/dev/null 2>&1 || true
' -- "${APP_CMD[@]}"
