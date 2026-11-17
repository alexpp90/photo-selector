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
# One pytest process per test file, each bounded: a test that blocks on a real GTK main loop fails
# its file (and dumps the stacks) instead of holding the whole job until the job timeout, and one run
# reports every failing file. A private session bus, as the device lab uses.
failed=()
run_file() {
    GDK_BACKEND=x11 PST_REQUIRE_PACKAGING_TOOLS=1 \
        timeout 300 dbus-run-session -- xvfb-run -a python3 -m pytest "$1" -rf --tb=short \
        -p no:cacheprovider -o faulthandler_timeout=120 -o addopts=""
}
for test_file in tests/unit/test_*.py; do
    # The scale tests compare timings at two folder sizes: that is a statement about our code, not the
    # distribution's stack, and a shared container's noise can push a ratio over its limit. The `test`
    # job runs them (with best-of-attempts); here they are left out.
    case "$test_file" in
        *_scale.py) echo "note: $test_file left to the test job"; continue ;;
    esac
    run_file "$test_file" && continue
    rc=$?
    [[ $rc -eq 5 ]] && continue  # nothing collected
    failed+=("$test_file (exit $rc)")
done
if ((${#failed[@]})); then
    printf '✘ failed: %s\n' "${failed[@]}" >&2
    exit 1
fi

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
