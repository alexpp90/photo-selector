#!/usr/bin/env bash
# Assembles PhotoSelector.app from the SwiftPM executable.
#
# A bundled app gets activation policy `.regular` from Launch Services, shows in the Dock
# and receives keyboard focus normally. (The raw SwiftPM binary is also fixed at runtime by
# ForegroundActivation, but the bundle is the supported way to run the app.)
#
# Usage: products/macos-desktop/scripts/build_app.sh [debug|release] [--universal]
#   --universal  build arm64 and x86_64 slices and merge them with lipo (release packaging;
#                the published cask serves Apple Silicon and Intel Macs from one zip).
#                Without it the bundle holds the host architecture only (fast local runs).
# Prints the path of the assembled bundle on the last line.
#
# Environment:
#   MACOS_VERSION       CFBundleShortVersionString; defaults to the contents of
#                       products/macos-desktop/VERSION (the product's release version).
#   MACOS_BUILD_NUMBER  CFBundleVersion; defaults to 1 (CI passes the workflow run number).
set -euo pipefail

CONFIG="release"
UNIVERSAL=false
TEST_HOOKS=false
for arg in "$@"; do
    case "$arg" in
        debug|release) CONFIG="$arg" ;;
        --universal)   UNIVERSAL=true ;;
        # Compile the UI test hooks (--ui-scenario, --ui-self-test, --screenshot) in, into a separate
        # build tree and app bundle so a distributable build never carries them (finding Q-2).
        --test-hooks)  TEST_HOOKS=true ;;
        *) echo "ERROR: unknown argument: $arg" >&2; exit 1 ;;
    esac
done
PRODUCT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

VERSION_FILE="$PRODUCT_DIR/VERSION"
if [[ -n "${MACOS_VERSION:-}" ]]; then
    APP_VERSION="$MACOS_VERSION"
elif [[ -f "$VERSION_FILE" ]]; then
    APP_VERSION="$(tr -d '[:space:]' < "$VERSION_FILE")"
else
    echo "ERROR: $VERSION_FILE not found and MACOS_VERSION is not set" >&2
    exit 1
fi
BUILD_NUMBER="${MACOS_BUILD_NUMBER:-1}"
if [[ ! "$APP_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "ERROR: version '$APP_VERSION' is not X.Y.Z" >&2
    exit 1
fi
if [[ ! "$BUILD_NUMBER" =~ ^[0-9]+$ ]]; then
    echo "ERROR: MACOS_BUILD_NUMBER '$BUILD_NUMBER' is not a number" >&2
    exit 1
fi

SWIFT_EXTRA=()
if $TEST_HOOKS; then
    export PST_TEST_HOOKS=1
    SWIFT_EXTRA=(--scratch-path "$PRODUCT_DIR/.build/hooks")
    APP="$PRODUCT_DIR/.build/hooks/app/PhotoSelector.app"
else
    APP="$PRODUCT_DIR/.build/app/PhotoSelector.app"
fi
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"

if $UNIVERSAL; then
    # One build per triple. Both can share one output directory, so each slice is copied
    # out before the next build overwrites it.
    SLICES="$PRODUCT_DIR/.build/app-slices"
    rm -rf "$SLICES"
    mkdir -p "$SLICES"
    for arch in arm64 x86_64; do
        triple="$arch-apple-macosx14.0"
        swift build -c "$CONFIG" --package-path "$PRODUCT_DIR" --product PhotoSelectorApp --triple "$triple" ${SWIFT_EXTRA[@]+"${SWIFT_EXTRA[@]}"} >&2
        BIN_DIR="$(swift build -c "$CONFIG" --package-path "$PRODUCT_DIR" --triple "$triple" ${SWIFT_EXTRA[@]+"${SWIFT_EXTRA[@]}"} --show-bin-path)"
        cp "$BIN_DIR/PhotoSelectorApp" "$SLICES/PhotoSelectorApp-$arch"
    done
    lipo -create "$SLICES/PhotoSelectorApp-arm64" "$SLICES/PhotoSelectorApp-x86_64" \
        -output "$APP/Contents/MacOS/PhotoSelectorApp"
    ARCHS="$(lipo -archs "$APP/Contents/MacOS/PhotoSelectorApp")"
    if [[ " $ARCHS " != *" arm64 "* || " $ARCHS " != *" x86_64 "* ]]; then
        echo "ERROR: universal binary is missing a slice (has: $ARCHS)" >&2
        exit 1
    fi
else
    swift build -c "$CONFIG" --package-path "$PRODUCT_DIR" --product PhotoSelectorApp ${SWIFT_EXTRA[@]+"${SWIFT_EXTRA[@]}"} >&2
    BIN_DIR="$(swift build -c "$CONFIG" --package-path "$PRODUCT_DIR" ${SWIFT_EXTRA[@]+"${SWIFT_EXTRA[@]}"} --show-bin-path)"
    cp "$BIN_DIR/PhotoSelectorApp" "$APP/Contents/MacOS/PhotoSelectorApp"
fi
if [[ -f "$PRODUCT_DIR/Resources/AppIcon.icns" ]]; then
    cp "$PRODUCT_DIR/Resources/AppIcon.icns" "$APP/Contents/Resources/AppIcon.icns"
elif [[ -f "$PRODUCT_DIR/../../assets/logo.icns" ]]; then
    cp "$PRODUCT_DIR/../../assets/logo.icns" "$APP/Contents/Resources/AppIcon.icns"
else
    echo "ERROR: AppIcon.icns not found in $PRODUCT_DIR/Resources or assets/" >&2
    exit 1
fi

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>CFBundleDevelopmentRegion</key><string>en</string>
    <key>CFBundleExecutable</key><string>PhotoSelectorApp</string>
    <key>CFBundleIconFile</key><string>AppIcon</string>
    <key>CFBundleIdentifier</key><string>com.photoselectortoolbox.macos</string>
    <key>CFBundleName</key><string>PhotoSelector Studio</string>
    <key>CFBundleDisplayName</key><string>PhotoSelector Studio</string>
    <key>CFBundlePackageType</key><string>APPL</string>
    <key>CFBundleShortVersionString</key><string>${APP_VERSION}</string>
    <key>CFBundleVersion</key><string>${BUILD_NUMBER}</string>
    <key>LSMinimumSystemVersion</key><string>14.0</string>
    <key>LSApplicationCategoryType</key><string>public.app-category.photography</string>
    <key>NSHighResolutionCapable</key><true/>
    <key>NSPrincipalClass</key><string>NSApplication</string>
</dict>
</plist>
PLIST

# Ad-hoc signature so Gatekeeper/TCC treat the bundle as one stable identity.
codesign --force --sign - "$APP" >/dev/null 2>&1 || true
echo "$APP"
