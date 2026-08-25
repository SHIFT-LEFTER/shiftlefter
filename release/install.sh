#!/usr/bin/env bash
# ShiftLefter installer (sl-k0s).
#
# Installs a runnable `sl` + jar into ./sl/ by REUSING the release-zip
# artifact (build.clj release-zip) — it does not repackage anything. On a
# FRESH install (no pre-existing sl/) it then runs `sl init`, which scaffolds
# the project (sl/shiftlefter.edn, glossary, starter feature), writes the
# agent on-ramp stanza into AGENTS.md (marker-guarded create-or-append), and
# makes .shiftlefter/ self-ignoring. The installer also writes a
# TARGET_DIR/.gitignore (if absent) covering only the landed binaries — jar,
# CDS archive, lib/ — so authored project files stay committable while a
# `git add -A` never stages ~96MB of tooling. When sl/ pre-existed the scaffold is
# skipped entirely and the breadcrumb stanza is PRINTED instead for manual
# pasting (init also bails on its own if sl/shiftlefter.edn exists).
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
DEFAULT_VERSION="0.5.5"
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

# Captured BEFORE anything is created: a pre-existing sl/ means a prior or
# hand-rolled project — the scaffold step is skipped and only the jar/wrapper
# refresh (init's own bail on sl/shiftlefter.edn is the second belt).
SL_DIR_PREEXISTED=false
[[ -d "sl" ]] && SL_DIR_PREEXISTED=true

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

# Binaries-only ignore file (write-if-absent — a user's customized ignore file
# is authored content and never clobbered). Everything authored in TARGET_DIR
# (shiftlefter.edn, features/, glossary/, steps/, setup.clj) stays committable;
# results/ already self-ignores at run time.
if [[ ! -f "$TARGET_DIR/.gitignore" ]]; then
    cat > "$TARGET_DIR/.gitignore" <<'EOF'
# Installed ShiftLefter binaries — recreated by release/install.sh.
# Authored project files (shiftlefter.edn, features/, glossary/, steps/,
# setup.clj) stay tracked.
*.jar
*.jsa
lib/
EOF
fi

abs_target="$(cd "$TARGET_DIR" && pwd)"
echo "Installed ShiftLefter into $abs_target"
echo
echo "Add it to your PATH:"
echo "    export PATH=\"$abs_target:\$PATH\""
echo

# --- Scaffold the project (sl init) ------------------------------------------
# Fresh install only: init scaffolds sl/shiftlefter.edn + glossary + starter
# feature, writes the AGENTS.md stanza itself, and self-ignores .shiftlefter/.
if [[ "$SL_DIR_PREEXISTED" == false ]]; then
    echo "Scaffolding project files (sl init) ..."
    "$TARGET_DIR/sl" init
else
    echo "Pre-existing sl/ detected — skipping the project scaffold (sl init)."
    # --- Emit the agent on-ramp breadcrumb (skip-path fallback) ---------------
    if [[ "$PRINT_BREADCRUMB" == true ]]; then
        stanza="$TARGET_DIR/agents-breadcrumb.md"
        if [[ -f "$stanza" ]]; then
            echo "=============================================================================="
            echo "Agent on-ramp breadcrumb"
            echo
            echo "Paste this into your agent file (AGENTS.md / CLAUDE.md / .cursor/rules) or"
            echo "startup prompt so a cold coding agent is routed to the ShiftLefter surface."
            echo "=============================================================================="
            echo
            cat "$stanza"
        else
            echo "Note: breadcrumb stanza not found in the release artifact." >&2
        fi
    fi
fi
