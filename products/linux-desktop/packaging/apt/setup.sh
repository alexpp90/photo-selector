#!/usr/bin/env bash
# Blitzlens — APT Repository Setup Script for Debian 13 (Trixie)
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
    echo "Error: This script must be run as root (e.g. curl -fsSL ... | sudo bash)." >&2
    exit 1
fi

echo "━━━ Setting up Blitzlens APT Repository for Debian 13 (Trixie) ━━━"

# 1. Install prerequisites
apt-get update
apt-get install -y curl ca-certificates gpg

# 2. Configure dedicated keyring directory
install -m 0755 -d /etc/apt/keyrings

# 3. Import repository public key
KEYRING_URL="https://alexpp90.github.io/apt/photo-selector-archive-keyring.gpg"
KEYRING_DST="/etc/apt/keyrings/photo-selector-archive-keyring.gpg"
if curl -fsSL --proto '=https' --tlsv1.2 "$KEYRING_URL" -o "$KEYRING_DST" && [ -s "$KEYRING_DST" ]; then
    chmod 0644 "$KEYRING_DST"
    echo "✔ GPG archive keyring imported into /etc/apt/keyrings/"
else
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
    if [ -s "${SCRIPT_DIR}/photo-selector-archive-keyring.gpg" ]; then
        cp "${SCRIPT_DIR}/photo-selector-archive-keyring.gpg" "$KEYRING_DST"
        chmod 0644 "$KEYRING_DST"
        echo "✔ GPG archive keyring copied from local directory into /etc/apt/keyrings/"
    else
        echo "Error: Repository GPG keyring not reachable at $KEYRING_URL and no local keyring found. Aborting setup." >&2
        rm -f "$KEYRING_DST"
        exit 1
    fi
fi

# 4. Install deb822 sources file
SOURCES_URL="https://alexpp90.github.io/apt/photo-selector.sources"
if curl -fsSL --proto '=https' --tlsv1.2 "$SOURCES_URL" -o /etc/apt/sources.list.d/photo-selector.sources; then
    chmod 0644 /etc/apt/sources.list.d/photo-selector.sources
else
    cat <<'EOF' > /etc/apt/sources.list.d/photo-selector.sources
Types: deb
URIs: https://alexpp90.github.io/apt/
Suites: trixie
Components: main
Architectures: amd64 all
Signed-By: /etc/apt/keyrings/photo-selector-archive-keyring.gpg
EOF
    chmod 0644 /etc/apt/sources.list.d/photo-selector.sources
fi
echo "✔ Repository source installed: /etc/apt/sources.list.d/photo-selector.sources"

# 5. Refresh repository indices
echo "Updating APT package index..."
apt-get update

echo "━━━ Setup Complete ━━━"
echo "Install Blitzlens with:"
echo "  sudo apt install -y photo-selector-linux"
