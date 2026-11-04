#!/usr/bin/env bash
# products/linux-desktop/scripts/package_debian.sh — End-to-end Debian package & APT pipeline
#
# Builds the .deb into an APT repository directory, refreshes the repository templates
# (setup.sh, photo-selector.sources, README, index.html), regenerates the metadata and
# verifies the result.
#
#   scripts/package_debian.sh                         # validation build: dist/apt, unsigned
#   scripts/package_debian.sh --apt-dir ../apt-repo   # into a checkout of alexpp90/apt
#   GPG_KEY_ID=<id> scripts/package_debian.sh --apt-dir … --sign   # signed (CI publish job)
#   scripts/package_debian.sh --apt-dir … --deb path/to/photo-selector-linux_X_all.deb
#                                                     # index and verify a .deb built earlier
#                                                     # (the publish job reuses the release's bytes)
#
# The published repository is https://github.com/alexpp90/apt (GitHub Pages:
# https://alexpp90.github.io/apt/). Extra options are passed to build_deb.sh.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRODUCT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TEMPLATE_DIR="$PRODUCT_DIR/packaging/apt"

APT_DIR="$PRODUCT_DIR/dist/apt"
SIGN=false
# The pure-Python builder is the reference: deterministic, identical on macOS and Linux,
# and it writes md5sums. --native opts into dpkg-deb (same file list and md5sums; the pure builder
# stays the default because it does not depend on the build machine's umask).
BUILD_ARGS=(--pure-python)
PREBUILT_DEB=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --apt-dir) APT_DIR="$2"; shift 2 ;;
        --sign) SIGN=true; shift 1 ;;
        --native) BUILD_ARGS=(); shift 1 ;;
        --deb) PREBUILT_DEB="$2"; shift 2 ;;
        *) BUILD_ARGS+=("$1"); shift 1 ;;
    esac
done
mkdir -p "$APT_DIR"
APT_DIR="$(cd "$APT_DIR" && pwd)"
POOL_DIR="$APT_DIR/pool/main/p/photo-selector-linux"

if [[ -n "$PREBUILT_DEB" ]]; then
    echo "━━━ Step 1: Using the prebuilt Debian Package ($PREBUILT_DEB) ━━━"
    [[ -f "$PREBUILT_DEB" ]] || { echo "✘ --deb: no such file: $PREBUILT_DEB" >&2; exit 1; }
    mkdir -p "$POOL_DIR"
    install -m 0644 "$PREBUILT_DEB" "$POOL_DIR/$(basename "$PREBUILT_DEB")"
else
    echo "━━━ Step 1: Building Debian Package (.deb) ━━━"
    "$SCRIPT_DIR/build_deb.sh" --output-dir "$POOL_DIR" ${BUILD_ARGS[@]+"${BUILD_ARGS[@]}"}
fi

echo "━━━ Step 2: Refreshing repository templates ━━━"
install -m 0755 "$TEMPLATE_DIR/setup.sh" "$APT_DIR/setup.sh"
install -m 0644 "$TEMPLATE_DIR/photo-selector.sources" "$APT_DIR/photo-selector.sources"
install -m 0644 "$TEMPLATE_DIR/README.md" "$APT_DIR/README.md"
install -m 0644 "$TEMPLATE_DIR/index.html" "$APT_DIR/index.html"
install -m 0644 "$TEMPLATE_DIR/.nojekyll" "$APT_DIR/.nojekyll"
install -m 0644 "$TEMPLATE_DIR/gitignore.template" "$APT_DIR/.gitignore"

echo "━━━ Step 3: Generating APT Repository Metadata ━━━"
if $SIGN; then
    if [[ -z "${GPG_KEY_ID:-}" ]]; then
        echo "✘ --sign needs GPG_KEY_ID (the imported signing key)" >&2
        exit 1
    fi
    python3 "$SCRIPT_DIR/generate_apt_repo.py" --repo-root "$APT_DIR" --gpg-key-id "$GPG_KEY_ID"
else
    python3 "$SCRIPT_DIR/generate_apt_repo.py" --repo-root "$APT_DIR" --unsigned
fi

echo "━━━ Step 4: Verifying Package & Repository Integrity ━━━"
"$SCRIPT_DIR/verify_package.sh" --apt-dir "$APT_DIR"

echo "✔ Debian package and APT repository ready in $APT_DIR"
