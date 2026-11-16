#!/usr/bin/env bash
# products/linux-desktop/scripts/distro_check.sh — Blitzlens against a distribution's own GTK stack
#
# Runs INSIDE a Debian or Ubuntu container (CI: `container: debian:trixie` / `ubuntu:24.04`;
# locally: scripts/distro_check.sh --container debian:trixie, which needs podman or docker):
#   1. installs the system GTK 4 / Libadwaita / GExiv2 stack the .deb depends on, plus the
#      distribution's own Python test packages (no pip, no Poetry),
#   2. runs the unit tests with the distribution's Python on a virtual display,
#   3. builds the .deb, installs it with apt (so its Depends must resolve on this release) and
#      starts the installed package far enough to import Libadwaita and the application.
# Debian 13 (Trixie) is the target; Ubuntu 24.04 (noble, GNOME 46) is the second suite (D8).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRODUCT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ "${1:-}" == "--container" ]]; then
    image="${2:?usage: distro_check.sh --container IMAGE}"
    runtime="$(command -v podman || command -v docker || true)"
    [[ -n "$runtime" ]] || { echo "✘ needs podman or docker" >&2; exit 2; }
    repo="$(cd "$PRODUCT_DIR/../.." && pwd)"
    # Overlay mount (podman): the container writes caches and dist/ without touching the checkout.
    mount="$repo:/src:O"
    [[ "$runtime" == *docker ]] && mount="$repo:/src:ro"
    exec "$runtime" run --rm -v "$mount" -w /src "$image" \
        bash /src/products/linux-desktop/scripts/distro_check.sh
fi

export DEBIAN_FRONTEND=noninteractive
export PYTHONDONTWRITEBYTECODE=1
. /etc/os-release
echo "━━━ Blitzlens on ${PRETTY_NAME} ━━━"

apt-get -o Acquire::Retries=3 update -qq
apt-get -o Acquire::Retries=3 install -y -qq --no-install-recommends \
    ca-certificates dpkg-dev binutils xvfb xauth dbus-daemon dbus-user-session \
    python3 python3-gi python3-gi-cairo python3-numpy python3-pil python3-pytest \
    gir1.2-gtk-4.0 gir1.2-adw-1 gir1.2-gexiv2-0.10 gir1.2-gdkpixbuf-2.0 libraw-bin \
    appstream desktop-file-utils
# The HEIC decoder pair is a Recommends of the package; a release without it still runs.
apt-get -o Acquire::Retries=3 install -y -qq --no-install-recommends \
    heif-gdk-pixbuf libheif-plugin-libde265 || echo "note: no HEIC decoder packages on ${PRETTY_NAME}"

echo "python3 $(python3 -c 'import sys; print(sys.version.split()[0])')," \
     "libadwaita $(dpkg-query -W -f='${Version}' libadwaita-1-0)," \
     "GTK $(dpkg-query -W -f='${Version}' libgtk-4-1)"

echo "━━━ Unit tests (system Python, virtual display) ━━━"
cd "$PRODUCT_DIR"
# A private session bus (as the device lab uses): GApplication registers on it, and without a bus a
# container waits out the D-Bus timeout. -v names each test as it runs, so a hang is attributable;
# -rf lists the failures at the end.
GDK_BACKEND=x11 PST_REQUIRE_PACKAGING_TOOLS=1 \
    dbus-run-session -- xvfb-run -a python3 -m pytest tests/unit -v -rf --tb=short -p no:cacheprovider \
    -o faulthandler_timeout=120

echo "━━━ Build, install and start the .deb ━━━"
out="$(mktemp -d)"
bash "$SCRIPT_DIR/build_deb.sh" --output-dir "$out"
apt-get install -y -qq "$out"/photo-selector-linux_*_all.deb
cd /
python3 -c "
import gi
gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
from gi.repository import Adw
import photo_selector_linux.ui.application as app
print('installed package imports; libadwaita', Adw.get_major_version(), Adw.get_minor_version())
"
photo-selector-linux --version
echo "✔ Blitzlens passes on ${PRETTY_NAME}"
