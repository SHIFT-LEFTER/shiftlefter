#!/usr/bin/env bash
# ShiftLefter installer (sl-k0s).
#
# Installs a runnable `sl` + jar into ./sl/ by REUSING the release-zip
# artifact (build.clj release-zip) — it does not repackage anything. On a
# FRESH install (no pre-existing sl/) it then runs `sl init`, which scaffolds
# the project (sl/shiftlefter.edn, glossary, starter feature) and makes
# .shiftlefter/ self-ignoring. The installer also writes a
# TARGET_DIR/.gitignore (if absent) covering only the landed binaries — jar,
# CDS archive, lib/ — so authored project files stay committable while a
# `git add -A` never stages ~96MB of tooling. When sl/ pre-existed the
# scaffold is skipped entirely (init also bails if sl/shiftlefter.edn exists).
#
# AGENTS.md is YOURS (sl-7dq5 F1): the agent on-ramp stanza is appended only
# with your explicit consent — --write-agents / --no-write-agents pre-answer
# it; with neither flag an interactive install asks once, and a
# non-interactive install defaults to NO write and prints the stanza for
# manual pasting instead (an unattended install never hangs on a question).
#
# Usage:
#   install.sh --zip PATH        Install from a local release-zip (dev + drive)
#   install.sh --version X.Y.Z   Install from the public GitHub release (prod)
#   install.sh                   Install the pinned default version (prod)
#
# Options:
#   --dir TARGET       Install directory (default: ./sl)
#   --write-agents     Append the agent on-ramp stanza to AGENTS.md (marker-guarded)
#   --no-write-agents  Never touch AGENTS.md; skip the printed stanza too
#   -h | --help        Show this help

set -euo pipefail

# Pinned default release version. Tracks build.clj's `version` at release time.
DEFAULT_VERSION="0.5.6"
REPO_SLUG="SHIFT-LEFTER/shiftlefter"

ZIP_PATH=""
VERSION=""
TARGET_DIR="sl"
# Tri-state AGENTS.md consent (sl-7dq5 F1): "" = unanswered, true, false.
WRITE_AGENTS=""

usage() {
    sed -n '2,29p' "$0" | sed 's/^# \{0,1\}//'
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --zip)             ZIP_PATH="${2:?--zip needs a path}"; shift 2 ;;
        --version)         VERSION="${2:?--version needs a value}"; shift 2 ;;
        --dir)             TARGET_DIR="${2:?--dir needs a path}"; shift 2 ;;
        --write-agents)    WRITE_AGENTS=true; shift ;;
        --no-write-agents) WRITE_AGENTS=false; shift ;;
        -h|--help)         usage; exit 0 ;;
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

# --- Resolve AGENTS.md consent (sl-7dq5 F1 tri-state) -------------------------
# Explicit flags pre-answered above; otherwise ask ONLY when a human is
# attached (stderr is a TTY and /dev/tty is readable — under `curl | bash`
# stdin is the script, so the prompt must read the terminal directly).
# Anything non-interactive defaults to NO write: an unattended install never
# hangs on a question. WRITE_AGENTS_DEFAULTED marks "nobody answered" — that
# arm still gets the stanza printed as an offer; an explicit no does not
# (you answered; the installer doesn't nag).
WRITE_AGENTS_DEFAULTED=false
if [[ -z "$WRITE_AGENTS" ]]; then
    if [[ -t 2 && -r /dev/tty ]]; then
        printf 'Append the ShiftLefter agent on-ramp stanza to AGENTS.md (marker-guarded)? [y/N] ' >&2
        read -r reply < /dev/tty || reply=""
        case "$reply" in
            [yY]*) WRITE_AGENTS=true ;;
            "")    WRITE_AGENTS=false; WRITE_AGENTS_DEFAULTED=true ;;
            *)     WRITE_AGENTS=false ;;
        esac
    else
        WRITE_AGENTS=false
        WRITE_AGENTS_DEFAULTED=true
    fi
fi

print_breadcrumb() {
    local stanza="$TARGET_DIR/agents-breadcrumb.md"
    if [[ -f "$stanza" ]]; then
        echo "=============================================================================="
        echo "Agent on-ramp breadcrumb"
        echo
        echo "Paste this into your agent file (AGENTS.md / CLAUDE.md / .cursor/rules) or"
        echo "startup prompt so a cold coding agent is routed to the ShiftLefter surface."
        echo "(Or have it appended for you: $TARGET_DIR/sl init --write-agents)"
        echo "=============================================================================="
        echo
        cat "$stanza"
    else
        echo "Note: breadcrumb stanza not found in the release artifact." >&2
    fi
}

# --- Scaffold the project (sl init) ------------------------------------------
# Fresh install only: init scaffolds sl/shiftlefter.edn + glossary + starter
# feature and self-ignores .shiftlefter/. AGENTS.md rides the consent above —
# `sl init --write-agents` performs the marker-guarded write, and on a
# pre-existing project init bails the scaffold but still honors the explicit
# ask (the sl-7dq5 bail-arm exception), so both paths are one mechanism.
if [[ "$SL_DIR_PREEXISTED" == false ]]; then
    echo "Scaffolding project files (sl init) ..."
    if [[ "$WRITE_AGENTS" == true ]]; then
        "$TARGET_DIR/sl" init --write-agents
    else
        "$TARGET_DIR/sl" init
    fi
else
    echo "Pre-existing sl/ detected — skipping the project scaffold (sl init)."
    if [[ "$WRITE_AGENTS" == true ]]; then
        "$TARGET_DIR/sl" init --write-agents
    fi
fi

# Nobody answered the AGENTS.md question — nothing was written, so print the
# stanza as the offer.
if [[ "$WRITE_AGENTS_DEFAULTED" == true ]]; then
    print_breadcrumb
fi
