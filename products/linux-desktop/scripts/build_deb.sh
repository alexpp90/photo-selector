#!/usr/bin/env bash
# products/linux-desktop/scripts/build_deb.sh — Debian package build orchestrator
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRODUCT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# Default settings
DIST_DIR="${PRODUCT_DIR}/dist"
OUTPUT_DIR="${DIST_DIR}"
FORCE_PYTHON=false
VERSION=""
OUTPUT_FILENAME=""

usage() {
    cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Build the Debian package (.deb) for Blitzlens.

Options:
  -o, --output-dir DIR   Destination directory for the .deb file (default: products/linux-desktop/dist)
  -v, --version VER      Override package version (default: from photo_selector_linux.__version__)
  -f, --filename NAME    Override output .deb filename (e.g. photo-selector-linux_0.2.0_all.deb)
  -p, --pure-python      Force cross-platform pure Python packager (skip dpkg-deb)
  -h, --help             Show this help message
EOF
    exit 0
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        -o|--output-dir) OUTPUT_DIR="$2"; shift 2 ;;
        -v|--version) VERSION="$2"; shift 2 ;;
        -f|--filename) OUTPUT_FILENAME="$2"; shift 2 ;;
        -p|--pure-python) FORCE_PYTHON=true; shift 1 ;;
        -h|--help) usage ;;
        *) echo "Unknown option: $1" >&2; exit 1 ;;
    esac
done

mkdir -p "$OUTPUT_DIR"
mkdir -p "$DIST_DIR"

# Resolve package version if not provided
if [[ -z "$VERSION" ]]; then
    VERSION=$(python3 -c "import sys; sys.path.insert(0, '$PRODUCT_DIR/src'); import photo_selector_linux; print(photo_selector_linux.__version__)")
fi

if [[ -z "$OUTPUT_FILENAME" ]]; then
    OUTPUT_FILENAME="photo-selector-linux_${VERSION}_all.deb"
fi

echo "━━━ Building photo-selector-linux v${VERSION} Debian Package ━━━"

# Determine build engine
BUILD_MODE="python"
if ! $FORCE_PYTHON && command -v dpkg-deb &>/dev/null; then
    echo "Using native Debian toolchain: $(command -v dpkg-deb)"
    BUILD_MODE="native"
else
    echo "Using cross-platform pure Python builder (dpkg-deb unavailable or forced)"
fi

python3 "$SCRIPT_DIR/package_deb.py" \
    --product-dir "$PRODUCT_DIR" \
    --output-dir "$OUTPUT_DIR" \
    --version "$VERSION" \
    --output-filename "$OUTPUT_FILENAME" \
    --mode "$BUILD_MODE"

DEB_FILE="${OUTPUT_DIR}/${OUTPUT_FILENAME}"
if [[ -f "$DEB_FILE" ]]; then
    if [[ "$(cd "$OUTPUT_DIR" && pwd)" != "$(cd "$DIST_DIR" && pwd)" ]]; then
        cp -f "$DEB_FILE" "${DIST_DIR}/"
    fi
    echo "✔ Successfully generated: $DEB_FILE"
    echo "✔ Mirrored to: ${DIST_DIR}/${OUTPUT_FILENAME}"
    ls -lh "$DEB_FILE"
else
    echo "✘ Build failed: $DEB_FILE not created" >&2
    exit 1
fi
