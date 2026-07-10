#!/usr/bin/env bash
# ShiftLefter installer (sl-k0s).
#
# Installs a runnable `sl` + jar into ./sl/ by REUSING the release-zip
# artifact (build.clj release-zip) — it does not repackage anything. At the
# end it PRINTS the agent on-ramp breadcrumb for you to paste into your agent
# file (AGENTS.md / CLAUDE.md / .cursor/rules / startup prompt). It does NOT
# write to any agent file — that is `sl init`'s job (cn7), because agent-file
# conventions vary and clobbering them is not the installer's call.
#
# Usage:
#   install.sh --zip PATH        Install from a local release-zip (dev + drive)
#   install.sh --version X.Y.Z   Install from the public GitHub release (prod)
#   install.sh                   Install the pinned default version (prod)
#
# Options:
#   --dir TARGET     Install directory (default: ./sl)
#   --no-breadcrumb  Skip printing the breadcrumb stanza
#   -h | --help      Show this help

set -euo pipefail

# Pinned default release version. Tracks build.clj's `version` at release time.
DEFAULT_VERSION="0.5.1"
REPO_SLUG="SHIFT-LEFTER/shiftlefter"

ZIP_PATH=""
VERSION=""
TARGET_DIR="sl"
PRINT_BREADCRUMB=true

usage() {
    sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --zip)           ZIP_PATH="${2:?--zip needs a path}"; shift 2 ;;
        --version)       VERSION="${2:?--version needs a value}"; shift 2 ;;
        --dir)           TARGET_DIR="${2:?--dir needs a path}"; shift 2 ;;
        --no-breadcrumb) PRINT_BREADCRUMB=false; shift ;;
        -h|--help)       usage; exit 0 ;;
        *) echo "Unknown option: $1" >&2; echo "Use --help for usage." >&2; exit 1 ;;
    esac
done

err() { echo "Error: $*" >&2; exit 1; }

command -v unzip >/dev/null 2>&1 || err "unzip is required but not found."

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

# --- Obtain the release-zip ---------------------------------------------------
if [[ -n "$ZIP_PATH" ]]; then
    [[ -f "$ZIP_PATH" ]] || err "release-zip not found: $ZIP_PATH"
    LOCAL_ZIP="$ZIP_PATH"
else
    VERSION="${VERSION:-$DEFAULT_VERSION}"
    command -v curl >/dev/null 2>&1 || err "curl is required for URL install but not found."
    url="https://github.com/${REPO_SLUG}/releases/download/v${VERSION}/shiftlefter-v${VERSION}.zip"
    echo "Downloading ShiftLefter v${VERSION} from ${url} ..."
    LOCAL_ZIP="$WORK_DIR/shiftlefter-v${VERSION}.zip"
    if ! curl -fsSL "$url" -o "$LOCAL_ZIP"; then
        err "could not download $url
  The public release may not exist yet. Until it is published (sl-3qk), install
  from a locally built zip instead:
    clj -T:build release-zip :version '\"${VERSION}\"'
    release/install.sh --zip target/shiftlefter-v${VERSION}.zip"
    fi
fi

# --- Unpack and place into TARGET_DIR (idempotent overwrite) ------------------
unzip -q -o "$LOCAL_ZIP" -d "$WORK_DIR"
# The zip holds a single shiftlefter-vX.Y.Z/ directory.
unpacked="$(find "$WORK_DIR" -maxdepth 1 -type d -name 'shiftlefter-v*' | head -1)"
[[ -n "$unpacked" ]] || err "release-zip did not contain a shiftlefter-v* directory."

mkdir -p "$TARGET_DIR"
cp -f "$unpacked"/* "$TARGET_DIR"/
chmod +x "$TARGET_DIR/sl"

abs_target="$(cd "$TARGET_DIR" && pwd)"
echo "Installed ShiftLefter into $abs_target"
echo
echo "Add it to your PATH:"
echo "    export PATH=\"$abs_target:\$PATH\""
echo

# --- Emit the agent on-ramp breadcrumb ---------------------------------------
if [[ "$PRINT_BREADCRUMB" == true ]]; then
    stanza="$TARGET_DIR/agents-breadcrumb.md"
    if [[ -f "$stanza" ]]; then
        echo "=============================================================================="
        echo "Agent on-ramp breadcrumb"
        echo
        echo "Paste this into your agent file (AGENTS.md / CLAUDE.md / .cursor/rules) or"
        echo "startup prompt so a cold coding agent is routed to the ShiftLefter surface."
        echo "(Once \`sl init\` exists it will do this for you.)"
        echo "=============================================================================="
        echo
        cat "$stanza"
    else
        echo "Note: breadcrumb stanza not found in the release artifact." >&2
    fi
fi
