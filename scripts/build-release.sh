#!/usr/bin/env bash
# Build a release archive for ShiftLefter
#
# Usage:
#   ./scripts/build-release.sh [version]
#
# Creates: target/shiftlefter-VERSION.zip containing:
#   shiftlefter-VERSION/
#   ├── sl                    # launcher script
#   ├── shiftlefter.jar       # standalone uberjar
#   └── README.txt            # quick start instructions

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
VERSION="${1:-0.1.1}"

RELEASE_NAME="shiftlefter-${VERSION}"
RELEASE_DIR="$PROJECT_DIR/target/$RELEASE_NAME"
RELEASE_ZIP="$PROJECT_DIR/target/${RELEASE_NAME}.zip"

echo "Building ShiftLefter release v${VERSION}..."
echo

# Step 1: Build the uberjar
echo "Step 1: Building uberjar..."
cd "$PROJECT_DIR"
clj -T:build uberjar
echo

# Step 2: Create release directory structure
echo "Step 2: Creating release directory..."
rm -rf "$RELEASE_DIR"
mkdir -p "$RELEASE_DIR"

# Step 3: Copy files
echo "Step 3: Copying files..."
cp "$PROJECT_DIR/target/shiftlefter.jar" "$RELEASE_DIR/shiftlefter.jar"
cp "$SCRIPT_DIR/sl-release" "$RELEASE_DIR/sl"
chmod +x "$RELEASE_DIR/sl"
cp "$SCRIPT_DIR/README-release.txt" "$RELEASE_DIR/README.txt"

# Step 4: Create zip archive
echo "Step 4: Creating zip archive..."
cd "$PROJECT_DIR/target"
rm -f "${RELEASE_NAME}.zip"
zip -r "${RELEASE_NAME}.zip" "$RELEASE_NAME"

echo
echo "Release built successfully!"
echo "  Archive: $RELEASE_ZIP"
echo "  Size: $(du -h "$RELEASE_ZIP" | cut -f1)"
echo
echo "To test:"
echo "  cd /tmp && unzip $RELEASE_ZIP && cd $RELEASE_NAME && ./sl --help"
