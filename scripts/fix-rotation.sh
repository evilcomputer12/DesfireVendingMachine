#!/usr/bin/env bash
#
# fix-rotation.sh -- Raspberry Pi display + touchscreen rotation for
#                    "The Midnight Brewer" JavaFX kiosk (1024x600 fullscreen).
#
# THE #1 GOTCHA: rotating the DISPLAY does not rotate the TOUCH INPUT.
# The touch panel keeps reporting coordinates in its original orientation, so
# taps land in the wrong place (often mirrored or on the wrong axis). This
# script always considers both halves of the problem.
#
# Usage:  sudo ./fix-rotation.sh [normal|left|right|inverted] [options]
# See:    ./README-rotation.md
#
set -euo pipefail

VERSION="1.0.0"
SCRIPT_NAME="$(basename "$0")"
MANAGED_TAG="fix-rotation"
BLOCK_BEGIN="# >>> ${MANAGED_TAG} managed block >>>"
BLOCK_END="# <<< ${MANAGED_TAG} managed block <<<"

# ---------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------

if [[ -t 1 ]] && [[ -z "${NO_COLOR:-}" ]]; then
    C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_DIM=$'\033[2m'
    C_RED=$'\033[31m';  C_GRN=$'\033[32m'; C_YEL=$'\033[33m'
    C_BLU=$'\033[34m';  C_CYN=$'\033[36m'
else
    C_RESET=''; C_BOLD=''; C_DIM=''
    C_RED='';   C_GRN='';  C_YEL=''
    C_BLU='';   C_CYN=''
fi

info()  { printf '%s\n' "${C_BLU}==>${C_RESET} $*"; }
ok()    { printf '%s\n' "${C_GRN} ok ${C_RESET} $*"; }
warn()  { printf '%s\n' "${C_YEL}warn${C_RESET} $*" >&2; }
err()   { printf '%s\n' "${C_RED}FAIL${C_RESET} $*" >&2; }
step()  { printf '\n%s\n' "${C_BOLD}${C_CYN}## $*${C_RESET}"; }
dim()   { printf '%s\n' "${C_DIM}     $*${C_RESET}"; }
die()   { err "$*"; exit 1; }

# ---------------------------------------------------------------------------
# Defaults / globals
# ---------------------------------------------------------------------------

ROTATION=""                 # normal | left | right | inverted
MODE="1024x600@60"          # used for the KMS video= cmdline parameter
DRY_RUN=0
DO_STATUS=0
DO_REVERT=0
WANT_TOUCH_MATRIX=0         # force the udev LIBINPUT_CALIBRATION_MATRIX on Wayland
SKIP_TOUCH=0
FORCE_STACK=""              # labwc | wayfire | x11 | kms | legacy
FORCE_OUTPUT=""
FORCE_TOUCH_DEV=""

STACK=""                    # detected display stack
OUTPUT=""                   # detected connector name
TOUCH_DEV=""                # detected touch device name
CHANGED=0                   # did we modify anything?
NEEDS_REBOOT=0

UDEV_RULE="/etc/udev/rules.d/99-touchscreen-rotate.rules"

REAL_USER=""
REAL_HOME=""

# ---------------------------------------------------------------------------
# Rotation vocabulary translation
#
# Everything is expressed in xrandr's vocabulary, which describes how the
# IMAGE is rotated:
#   normal    no rotation
#   right     image rotated 90 degrees clockwise
#   inverted  image rotated 180 degrees
#   left      image rotated 90 degrees counter-clockwise
#
# If the result comes out backwards on your panel, swap left <-> right. Panel
# mounting orientation is not discoverable from software, so this is expected
# and normal -- see the README.
# ---------------------------------------------------------------------------

# xrandr --rotate <value>
rot_xrandr() { printf '%s' "$1"; }

# wlroots / wayfire / labwc transform value
rot_wlr() {
    case "$1" in
        normal)   printf '%s' 'normal' ;;
        right)    printf '%s' '90'  ;;
        inverted) printf '%s' '180' ;;
        left)     printf '%s' '270' ;;
    esac
}

# Kernel DRM "video=<conn>:<mode>,rotate=<deg>" value
rot_kms() {
    case "$1" in
        normal)   printf '%s' '0'   ;;
        right)    printf '%s' '90'  ;;
        inverted) printf '%s' '180' ;;
        left)     printf '%s' '270' ;;
    esac
}

# Legacy firmware display_rotate / lcd_rotate value
rot_legacy() {
    case "$1" in
        normal)   printf '%s' '0' ;;
        right)    printf '%s' '1' ;;
        inverted) printf '%s' '2' ;;
        left)     printf '%s' '3' ;;
    esac
}

# xinput "Coordinate Transformation Matrix" -- full 3x3, nine values.
# NOTE: always printf '%s' -- a bare printf would parse a leading "-1" as a flag.
rot_matrix9() {
    case "$1" in
        normal)   printf '%s' '1 0 0 0 1 0 0 0 1'    ;;
        right)    printf '%s' '0 1 0 -1 0 1 0 0 1'   ;;
        inverted) printf '%s' '-1 0 1 0 -1 1 0 0 1'  ;;
        left)     printf '%s' '0 -1 1 1 0 0 0 0 1'   ;;
    esac
}

# libinput LIBINPUT_CALIBRATION_MATRIX -- SIX values only (the affine part,
# i.e. the first two rows of the 3x3 above). Passing nine is a common mistake
# and libinput silently ignores the whole rule.
rot_matrix6() {
    case "$1" in
        normal)   printf '%s' '1 0 0 0 1 0'   ;;
        right)    printf '%s' '0 1 0 -1 0 1'  ;;
        inverted) printf '%s' '-1 0 1 0 -1 1' ;;
        left)     printf '%s' '0 -1 1 1 0 0'  ;;
    esac
}

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------

usage() {
    cat <<EOF
${C_BOLD}${SCRIPT_NAME}${C_RESET} v${VERSION} -- Raspberry Pi display + touch rotation

${C_BOLD}USAGE${C_RESET}
  sudo ./${SCRIPT_NAME} [normal|left|right|inverted] [options]

${C_BOLD}ROTATIONS${C_RESET} (xrandr vocabulary -- how the IMAGE is rotated)
  normal      no rotation
  right       90 degrees clockwise
  inverted    180 degrees
  left        90 degrees counter-clockwise

${C_BOLD}OPTIONS${C_RESET}
  --status              Show detected environment + current rotation, change nothing.
  --dry-run             Print every change as a diff, write nothing.
  --revert              Restore all files from their pristine .bak backups.
  --mode <WxH@Hz>       Mode for the KMS video= parameter (default: ${MODE}).
  --output <NAME>       Force connector name (e.g. HDMI-1, HDMI-A-1, DSI-1).
  --stack <NAME>        Force backend: labwc|wayfire|x11|kms|legacy.
  --touch-device <NAME> Force touch device name (skip auto-detection).
  --touch-matrix        Wayland only: also write the udev calibration matrix.
                        Use this when the display rotated but touch did not.
  --no-touch            Rotate the display only; leave touch input alone.
  -h, --help            This help.

${C_BOLD}EXAMPLES${C_RESET}
  ./${SCRIPT_NAME} --status                 # what am I running?
  ./${SCRIPT_NAME} right --dry-run          # preview, safe as a normal user
  sudo ./${SCRIPT_NAME} right               # apply, then reboot
  sudo ./${SCRIPT_NAME} right --touch-matrix  # display ok but touch still wrong
  sudo ./${SCRIPT_NAME} --revert            # undo everything
EOF
}

parse_args() {
    while [[ $# -gt 0 ]]; do
        case "$1" in
            normal|left|right|inverted)
                ROTATION="$1"; shift ;;
            --status)        DO_STATUS=1; shift ;;
            --dry-run)       DRY_RUN=1; shift ;;
            --revert)        DO_REVERT=1; shift ;;
            --touch-matrix)  WANT_TOUCH_MATRIX=1; shift ;;
            --no-touch)      SKIP_TOUCH=1; shift ;;
            --mode)          MODE="${2:?--mode needs a value}"; shift 2 ;;
            --output)        FORCE_OUTPUT="${2:?--output needs a value}"; shift 2 ;;
            --stack)         FORCE_STACK="${2:?--stack needs a value}"; shift 2 ;;
            --touch-device)  FORCE_TOUCH_DEV="${2:?--touch-device needs a value}"; shift 2 ;;
            -h|--help)       usage; exit 0 ;;
            *)               usage >&2; die "Unknown argument: $1" ;;
        esac
    done
}

# ---------------------------------------------------------------------------
# Environment helpers
# ---------------------------------------------------------------------------

have() { command -v "$1" >/dev/null 2>&1; }

is_root() { [[ "$(id -u)" -eq 0 ]]; }

# Under sudo, $HOME is often still /root. We want the *desktop user's* config.
resolve_real_user() {
    REAL_USER="${SUDO_USER:-${USER:-$(id -un)}}"
    if have getent; then
        REAL_HOME="$(getent passwd "$REAL_USER" 2>/dev/null | cut -d: -f6 || true)"
    fi
    if [[ -z "${REAL_HOME:-}" ]]; then
        if [[ "$REAL_USER" == "root" ]]; then REAL_HOME="/root"; else REAL_HOME="/home/$REAL_USER"; fi
    fi
    [[ -d "$REAL_HOME" ]] || REAL_HOME="${HOME:-$REAL_HOME}"
}

# Anything we create inside the user's home must stay owned by the user,
# otherwise their session cannot rewrite it later.
fix_owner() {
    local path="$1"
    [[ -e "$path" ]] || return 0
    case "$path" in
        "$REAL_HOME"/*) ;;
        *) return 0 ;;
    esac
    is_root || return 0
    chown -R "$REAL_USER" "$path" 2>/dev/null || true
}

require_root_for_writes() {
    if [[ $DRY_RUN -eq 1 || $DO_STATUS -eq 1 ]]; then return 0; fi
    is_root || die "Writing changes needs root. Re-run with sudo, or use --dry-run / --status."
}

# ---------------------------------------------------------------------------
# Backup / commit
#
# Backup scheme (deliberately NOT timestamped, so repeat runs stay tidy):
#   <file>.bak      pristine original, written once, never overwritten
#   <file>.bak-N    rolling history of subsequent edits (N = 1, 2, 3 ...)
# --revert always restores <file>.bak, i.e. the state before this script ever
# touched the machine.
# ---------------------------------------------------------------------------

backup_file() {
    local f="$1"
    [[ -f "$f" ]] || return 0

    if [[ ! -f "$f.bak" ]]; then
        cp -p -- "$f" "$f.bak"
        dim "backup: $f.bak (pristine original)"
        return 0
    fi

    # Pristine backup already exists -> add a numbered history entry, capped.
    local n=1
    while [[ -f "$f.bak-$n" && $n -lt 20 ]]; do n=$((n + 1)); done
    cp -p -- "$f" "$f.bak-$n"
    dim "backup: $f.bak-$n"
}

# commit_file <target> <tmpfile> -- the single funnel for every write.
# Honours --dry-run, skips no-op writes (idempotency), backs up, preserves mode.
commit_file() {
    local target="$1" tmp="$2"

    if [[ -f "$target" ]] && cmp -s "$tmp" "$target"; then
        ok "$target already correct (no change)"
        rm -f -- "$tmp"
        return 0
    fi

    if [[ $DRY_RUN -eq 1 ]]; then
        info "would write ${C_BOLD}$target${C_RESET}:"
        if [[ -f "$target" ]]; then
            diff -u "$target" "$tmp" | sed 's/^/     /' || true
        else
            printf '%s\n' "${C_DIM}     (new file)${C_RESET}"
            sed 's/^/     + /' "$tmp"
        fi
        rm -f -- "$tmp"
        CHANGED=1
        return 0
    fi

    local dir; dir="$(dirname -- "$target")"
    mkdir -p -- "$dir"

    if [[ -f "$target" ]]; then
        backup_file "$target"
        # Preserve the existing permissions rather than the tmpfile's 0600.
        cat -- "$tmp" > "$target"
        rm -f -- "$tmp"
    else
        mv -- "$tmp" "$target"
        chmod 0644 "$target"
    fi

    fix_owner "$dir"
    ok "wrote $target"
    CHANGED=1
}

mktmp() { mktemp "${TMPDIR:-/tmp}/fixrot.XXXXXX"; }

# ---------------------------------------------------------------------------
# Managed-block editing (for shell-style config files)
# ---------------------------------------------------------------------------

# block_replace <file> <payload-file> -- emit file content with our managed
# block replaced (or appended if absent). Result on stdout.
block_replace() {
    local f="$1" payload="$2"
    {
        if [[ -f "$f" ]]; then
            awk -v b="$BLOCK_BEGIN" -v e="$BLOCK_END" '
                $0 == b { skip = 1; next }
                $0 == e { skip = 0; next }
                !skip   { print }
            ' "$f"
        fi
        printf '%s\n' "$BLOCK_BEGIN"
        cat -- "$payload"
        printf '%s\n' "$BLOCK_END"
    }
}

# block_strip <file> -- emit file content with our managed block removed.
block_strip() {
    local f="$1"
    [[ -f "$f" ]] || return 0
    awk -v b="$BLOCK_BEGIN" -v e="$BLOCK_END" '
        $0 == b { skip = 1; next }
        $0 == e { skip = 0; next }
        !skip   { print }
    ' "$f"
}

# ---------------------------------------------------------------------------
# INI editing (wayfire.ini) -- match-and-replace, never blind append
# ---------------------------------------------------------------------------

# ini_set <file> <section> <key> <value> -- result on stdout.
ini_set() {
    local f="$1" section="$2" key="$3" value="$4"
    local src=""
    if [[ -f "$f" ]]; then src="$f"; else src="/dev/null"; fi

    awk -v target="$section" -v key="$key" -v val="$value" '
        BEGIN { insec = 0; done = 0; seen = 0; keyre = "^[ \t]*" key "[ \t]*=" }

        /^[ \t]*\[/ {
            if (insec && !done) { print key " = " val; done = 1 }
            sec = $0
            sub(/^[ \t]*\[/, "", sec)
            sub(/\][ \t]*$/, "", sec)
            insec = (sec == target)
            if (insec) seen = 1
            print
            next
        }

        {
            if (insec && $0 ~ keyre) {
                if (!done) { print key " = " val; done = 1 }
                next            # drop duplicate/stale keys
            }
            print
        }

        END {
            if (insec && !done) { print key " = " val; done = 1 }
            if (!seen) {
                print ""
                print "[" target "]"
                print key " = " val
            }
        }
    ' "$src"
}

# ---------------------------------------------------------------------------
# Detection: display stack
# ---------------------------------------------------------------------------

detect_stack() {
    if [[ -n "$FORCE_STACK" ]]; then
        STACK="$FORCE_STACK"
        info "Stack forced to: ${C_BOLD}$STACK${C_RESET}"
        return 0
    fi

    # 1. Running compositor/server is the most reliable signal.
    if pgrep -x labwc   >/dev/null 2>&1; then STACK="labwc";   return 0; fi
    if pgrep -x wayfire >/dev/null 2>&1; then STACK="wayfire"; return 0; fi
    if pgrep -x Xorg    >/dev/null 2>&1 || pgrep -x X >/dev/null 2>&1; then STACK="x11"; return 0; fi

    # 2. Session environment (may be visible if invoked from the desktop).
    if [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
        # XDG_CURRENT_DESKTOP looks like "labwc:wlroots" -- normalise and match.
        local desk
        desk="$(printf '%s' "${XDG_CURRENT_DESKTOP:-}" | tr '[:upper:]' '[:lower:]')"
        case "$desk" in
            *labwc*)   STACK="labwc";   return 0 ;;
            *wayfire*) STACK="wayfire"; return 0 ;;
        esac
        if have labwc;   then STACK="labwc";   return 0; fi
        if have wayfire; then STACK="wayfire"; return 0; fi
    fi
    if [[ -n "${DISPLAY:-}" ]] && have xrandr; then STACK="x11"; return 0; fi

    # 3. Installed-config heuristics (running headless / over SSH with no session).
    if [[ -f "$REAL_HOME/.config/wayfire.ini" ]] && have wayfire; then STACK="wayfire"; return 0; fi
    if [[ -d "$REAL_HOME/.config/labwc" ]]     && have labwc;   then STACK="labwc";   return 0; fi
    if have labwc   && ! have wayfire; then STACK="labwc";   return 0; fi
    if have wayfire && ! have labwc;   then STACK="wayfire"; return 0; fi

    # 4. No desktop at all -> bare KMS console (JavaFX/Monocle on the framebuffer).
    if compgen -G "/dev/dri/card*" >/dev/null 2>&1; then STACK="kms"; return 0; fi

    STACK=""
    return 1
}

# ---------------------------------------------------------------------------
# Detection: connected output / connector name
#
# Naming differs per stack and this is a real trap:
#   X11 (xrandr)              HDMI-1,   DSI-1
#   wlroots / kernel DRM      HDMI-A-1, DSI-1
# The KMS cmdline needs the *kernel* name.
# ---------------------------------------------------------------------------

detect_output_x11() {
    have xrandr || return 1
    xrandr --query 2>/dev/null \
        | awk '/ connected/ { print $1; exit }'
}

detect_output_wlr() {
    have wlr-randr || return 1
    # wlr-randr prints "HDMI-A-1 \"vendor model\"" at column 0 per output.
    wlr-randr 2>/dev/null | awk '/^[A-Za-z]/ { print $1; exit }'
}

detect_output_kms() {
    # /sys/class/drm/card1-HDMI-A-1/status -> "connected"
    local p name
    for p in /sys/class/drm/card*-*/status; do
        [[ -r "$p" ]] || continue
        [[ "$(cat "$p")" == "connected" ]] || continue
        name="$(basename "$(dirname "$p")")"
        # strip the leading "cardN-"
        printf '%s' "${name#*-}"
        return 0
    done
    return 1
}

detect_output() {
    if [[ -n "$FORCE_OUTPUT" ]]; then OUTPUT="$FORCE_OUTPUT"; return 0; fi

    case "$STACK" in
        x11)     OUTPUT="$(detect_output_x11 || true)" ;;
        labwc|wayfire)
                 OUTPUT="$(detect_output_wlr || true)"
                 [[ -n "$OUTPUT" ]] || OUTPUT="$(detect_output_kms || true)" ;;
        kms|legacy)
                 OUTPUT="$(detect_output_kms || true)" ;;
    esac

    # Last resort: the kernel always knows, even with no session running.
    [[ -n "$OUTPUT" ]] || OUTPUT="$(detect_output_kms || true)"
    [[ -n "$OUTPUT" ]]
}

# ---------------------------------------------------------------------------
# Detection: touch device
# ---------------------------------------------------------------------------

detect_touch_x11() {
    have xinput || return 1
    # Prefer an explicit touchscreen; fall back to anything named *touch*.
    xinput list --name-only 2>/dev/null \
        | grep -i -m1 -E 'touch|ft5406|goodix|edt-ft5x06|silead|raspberrypi-ts' || return 1
}

detect_touch_libinput() {
    have libinput || return 1
    libinput list-devices 2>/dev/null | awk '
        /^Device:/       { name = substr($0, index($0, $2)) }
        /^Capabilities:/ { if ($0 ~ /touch/ && name != "") { print name; exit } }
    ' || return 1
}

detect_touch_procfs() {
    # Works with no session and no extra packages: parse the kernel input list.
    # A touchscreen has ABS bits and an event handler but no "mouse" handler.
    [[ -r /proc/bus/input/devices ]] || return 1
    awk '
        /^N: Name=/ {
            name = $0
            sub(/^N: Name="/, "", name)
            sub(/"$/, "", name)
        }
        /^H: Handlers=/ {
            if (name ~ /[Tt]ouch|FT5406|Goodix|Silead|EP0110M09|raspberrypi-ts/) {
                print name
                exit
            }
        }
    ' /proc/bus/input/devices || return 1
}

detect_touch() {
    if [[ -n "$FORCE_TOUCH_DEV" ]]; then TOUCH_DEV="$FORCE_TOUCH_DEV"; return 0; fi

    case "$STACK" in
        x11) TOUCH_DEV="$(detect_touch_x11 || true)" ;;
        *)   TOUCH_DEV="$(detect_touch_libinput || true)" ;;
    esac
    [[ -n "$TOUCH_DEV" ]] || TOUCH_DEV="$(detect_touch_procfs || true)"
    [[ -n "$TOUCH_DEV" ]]
}

touch_help() {
    warn "Could not auto-detect a touchscreen device."
    dim "Find its name yourself with one of:"
    dim "    xinput list                  # X11"
    dim "    libinput list-devices        # Wayland (needs: sudo apt install libinput-tools)"
    dim "    cat /proc/bus/input/devices  # always available"
    dim "Then re-run with:  --touch-device \"Exact Device Name\""
}

# ---------------------------------------------------------------------------
# Detection: graphics driver (KMS vs legacy) -- decides whether the legacy
# display_rotate knob does anything at all.
# ---------------------------------------------------------------------------

config_txt_path() {
    if   [[ -f /boot/firmware/config.txt ]]; then printf '/boot/firmware/config.txt'
    elif [[ -f /boot/config.txt ]];          then printf '/boot/config.txt'
    else return 1
    fi
}

cmdline_txt_path() {
    if   [[ -f /boot/firmware/cmdline.txt ]]; then printf '/boot/firmware/cmdline.txt'
    elif [[ -f /boot/cmdline.txt ]];          then printf '/boot/cmdline.txt'
    else return 1
    fi
}

# Echoes: kms | fkms | legacy | unknown
detect_gfx_driver() {
    local cfg
    cfg="$(config_txt_path || true)"
    if [[ -z "$cfg" ]]; then printf 'unknown'; return 0; fi

    if grep -qE '^[[:space:]]*dtoverlay=vc4-kms-v3d' "$cfg"; then printf 'kms'
    elif grep -qE '^[[:space:]]*dtoverlay=vc4-fkms-v3d' "$cfg"; then printf 'fkms'
    else printf 'legacy'
    fi
}

# ---------------------------------------------------------------------------
# Current rotation readback (best effort, for --status)
# ---------------------------------------------------------------------------

current_rotation() {
    case "$STACK" in
        x11)
            have xrandr || { printf 'unknown'; return 0; }
            xrandr --query 2>/dev/null | awk '
                / connected/ {
                    if ($0 ~ / left /)          { print "left";     exit }
                    else if ($0 ~ / right /)    { print "right";    exit }
                    else if ($0 ~ / inverted /) { print "inverted"; exit }
                    else                        { print "normal";   exit }
                }' || printf 'unknown'
            ;;
        labwc|wayfire)
            if have wlr-randr; then
                wlr-randr 2>/dev/null | awk '/[Tt]ransform/ { print $2; exit }' || printf 'unknown'
            else
                printf 'unknown'
            fi
            ;;
        *)
            local cl; cl="$(cmdline_txt_path || true)"
            if [[ -n "$cl" ]] && grep -qE 'video=[^ ]*rotate=' "$cl"; then
                sed -n 's/.*video=[^ ]*rotate=\([0-9]*\).*/\1/p' "$cl" | head -1
            else
                printf 'unknown'
            fi
            ;;
    esac
}

# ---------------------------------------------------------------------------
# Backend: labwc  (Raspberry Pi OS Bookworm default since late 2024)
# ---------------------------------------------------------------------------

apply_labwc() {
    local transform; transform="$(rot_wlr "$ROTATION")"
    local autostart="$REAL_HOME/.config/labwc/autostart"

    step "labwc: display rotation"

    if ! have wlr-randr; then
        warn "wlr-randr is not installed -- labwc rotation will not apply at login."
        dim "Install it first:  sudo apt install wlr-randr"
    fi

    local payload; payload="$(mktmp)"
    {
        printf '%s\n' "# Display rotation for the Midnight Brewer kiosk."
        printf '%s\n' "# Managed by ${SCRIPT_NAME} -- edits inside this block are overwritten."
        printf 'wlr-randr --output %s --transform %s\n' "$OUTPUT" "$transform"
    } > "$payload"

    local out; out="$(mktmp)"
    block_replace "$autostart" "$payload" > "$out"
    rm -f -- "$payload"
    commit_file "$autostart" "$out"

    if [[ $DRY_RUN -eq 0 && -f "$autostart" ]]; then
        chmod 0755 "$autostart" 2>/dev/null || true
        fix_owner "$autostart"
    fi

    dim "labwc runs ~/.config/labwc/autostart at session start."
    NEEDS_REBOOT=1
}

# ---------------------------------------------------------------------------
# Backend: wayfire  (Bookworm early / Pi 4 desktop default)
# ---------------------------------------------------------------------------

apply_wayfire() {
    local transform; transform="$(rot_wlr "$ROTATION")"
    local ini="$REAL_HOME/.config/wayfire.ini"

    step "wayfire: display rotation"

    if [[ ! -f "$ini" ]]; then
        warn "$ini does not exist yet -- creating it with just the output section."
        dim "If wayfire later writes its own default config it may overwrite this."
    fi

    # Build every edit against one buffer, then commit once. Doing two
    # sequential commit_file calls on the same path would make --dry-run show a
    # misleading second diff computed against the unwritten original.
    local out; out="$(mktmp)"
    ini_set "$ini" "output:$OUTPUT" "transform" "$transform" > "$out"

    # Map the touch device onto the rotated output so wayfire transforms its
    # coordinates for us. This is the *correct* fix; the udev matrix is the hack.
    if [[ $SKIP_TOUCH -eq 0 && -n "$TOUCH_DEV" ]]; then
        local out2; out2="$(mktmp)"
        ini_set "$out" "input-device:$TOUCH_DEV" "output" "$OUTPUT" > "$out2"
        mv -- "$out2" "$out"
        ok "will map touch device \"$TOUCH_DEV\" to $OUTPUT"
    fi

    commit_file "$ini" "$out"
    fix_owner "$ini"

    dim "wayfire transform values: normal | 90 | 180 | 270 (clockwise image rotation)."
    NEEDS_REBOOT=1
}

# ---------------------------------------------------------------------------
# Backend: X11 / LXDE  (Bullseye and earlier)
#
# Both the xrandr rotation and the xinput matrix go into one helper script so
# they always apply together and in the right order.
# ---------------------------------------------------------------------------

apply_x11() {
    local rot;    rot="$(rot_xrandr "$ROTATION")"
    local matrix; matrix="$(rot_matrix9 "$ROTATION")"
    local helper="$REAL_HOME/.config/${MANAGED_TAG}-x11.sh"
    local desktop="$REAL_HOME/.config/autostart/${MANAGED_TAG}.desktop"

    step "X11/LXDE: display rotation + touch matrix"

    local out; out="$(mktmp)"
    {
        printf '%s\n' '#!/bin/sh'
        printf '%s\n' "# Managed by ${SCRIPT_NAME}. Regenerated on every run -- do not hand-edit."
        printf '%s\n' 'set -e'
        printf 'xrandr --output %s --rotate %s\n' "$OUTPUT" "$rot"
        if [[ $SKIP_TOUCH -eq 0 ]]; then
            if [[ -n "$TOUCH_DEV" ]]; then
                printf '%s\n' '# Rotate touch input to match. Without this, taps land in the wrong place.'
                printf 'xinput set-prop "%s" "Coordinate Transformation Matrix" %s || true\n' \
                    "$TOUCH_DEV" "$matrix"
            else
                printf '%s\n' '# No touch device was detected when this file was generated.'
                printf '%s\n' '# Re-run fix-rotation.sh with --touch-device "Your Device Name".'
            fi
        fi
    } > "$out"
    commit_file "$helper" "$out"
    if [[ $DRY_RUN -eq 0 ]]; then
        chmod 0755 "$helper" 2>/dev/null || true
        fix_owner "$helper"
    fi

    local out2; out2="$(mktmp)"
    {
        printf '%s\n' '[Desktop Entry]'
        printf '%s\n' 'Type=Application'
        printf '%s\n' 'Name=Kiosk display rotation'
        printf '%s\n' 'Comment=Managed by fix-rotation.sh'
        printf 'Exec=%s\n' "$helper"
        printf '%s\n' 'X-GNOME-Autostart-enabled=true'
        printf '%s\n' 'NoDisplay=true'
    } > "$out2"
    commit_file "$desktop" "$out2"
    fix_owner "$desktop"

    # Apply live too, if we happen to be inside the X session already.
    if [[ $DRY_RUN -eq 0 && -n "${DISPLAY:-}" ]] && have xrandr; then
        if xrandr --output "$OUTPUT" --rotate "$rot" 2>/dev/null; then
            ok "applied live: xrandr --output $OUTPUT --rotate $rot"
        fi
        if [[ $SKIP_TOUCH -eq 0 && -n "$TOUCH_DEV" ]] && have xinput; then
            # shellcheck disable=SC2086
            if xinput set-prop "$TOUCH_DEV" "Coordinate Transformation Matrix" $matrix 2>/dev/null; then
                ok "applied live: touch matrix on \"$TOUCH_DEV\""
            fi
        fi
    fi

    dim "Persisted via ~/.config/autostart/${MANAGED_TAG}.desktop"
    dim "Alternative system-wide location: /etc/xdg/lxsession/LXDE-pi/autostart"
}

# ---------------------------------------------------------------------------
# Backend: bare KMS console (JavaFX on the framebuffer / Monocle, no desktop)
#
# cmdline.txt MUST remain exactly ONE line. Appending a newline breaks boot.
# ---------------------------------------------------------------------------

apply_kms() {
    local deg; deg="$(rot_kms "$ROTATION")"
    local cl;  cl="$(cmdline_txt_path || true)"

    step "KMS console: kernel framebuffer rotation"

    [[ -n "$cl" ]] || die "Found neither /boot/firmware/cmdline.txt nor /boot/cmdline.txt. Is this a Raspberry Pi?"
    info "Using boot cmdline: ${C_BOLD}$cl${C_RESET}"

    # Read the whole file as a single logical line. Using tr (not tr -d) means
    # a previously-corrupted multi-line file gets repaired rather than having
    # its tokens glued together.
    local line
    line="$(tr '\n' ' ' < "$cl")"

    # Rebuild the token list, dropping any existing video= for THIS connector.
    # Match-and-replace, never blind append -- this is what keeps it idempotent.
    local toks=() tok kept=()
    read -r -a toks <<< "$line"
    for tok in "${toks[@]:-}"; do
        [[ -n "$tok" ]] || continue
        case "$tok" in
            video="$OUTPUT":*) continue ;;
            *) kept+=("$tok") ;;
        esac
    done

    local newline
    if [[ $ROTATION == "normal" ]]; then
        # "normal" means: just remove our parameter entirely.
        newline="${kept[*]:-}"
    else
        kept+=("video=${OUTPUT}:${MODE},rotate=${deg}")
        newline="${kept[*]}"
    fi

    local out; out="$(mktmp)"
    # printf with a single trailing \n == one line + terminator. Correct.
    printf '%s\n' "$newline" > "$out"

    # Paranoia: never ship a cmdline.txt with an embedded newline.
    if [[ "$(wc -l < "$out" | tr -d ' ')" != "1" ]]; then
        rm -f -- "$out"
        die "Internal error: refusing to write a multi-line cmdline.txt."
    fi

    commit_file "$cl" "$out"
    NEEDS_REBOOT=1

    dim "Kernel rotation affects the framebuffer console and fbdev (Monocle)."
    dim "A Wayland/X11 compositor, if one starts, overrides it with its own transform."
}

# ---------------------------------------------------------------------------
# Backend: legacy firmware rotation (display_rotate / lcd_rotate)
# ---------------------------------------------------------------------------

apply_legacy() {
    local val; val="$(rot_legacy "$ROTATION")"
    local cfg; cfg="$(config_txt_path || true)"
    local drv; drv="$(detect_gfx_driver)"

    step "Legacy firmware rotation (display_rotate)"

    [[ -n "$cfg" ]] || die "Found neither /boot/firmware/config.txt nor /boot/config.txt."

    if [[ "$drv" == "kms" ]]; then
        warn "This system uses the modern KMS driver (dtoverlay=vc4-kms-v3d)."
        warn "display_rotate / lcd_rotate are IGNORED under KMS. This will do nothing."
        dim "Use the 'kms' backend instead:  sudo ./${SCRIPT_NAME} $ROTATION --stack kms"
        dim "Proceeding anyway because you asked for --stack legacy."
    elif [[ "$drv" == "fkms" ]]; then
        warn "This system uses the fake-KMS driver (vc4-fkms-v3d)."
        warn "display_rotate support under fkms is unreliable; prefer --stack kms."
    else
        ok "Legacy (fbturbo/firmware) graphics stack detected -- display_rotate applies."
    fi

    # DSI panels use lcd_rotate; HDMI uses display_rotate.
    local key="display_rotate"
    case "$OUTPUT" in
        DSI-*|dsi*) key="lcd_rotate"
                    dim "DSI panel detected -- using lcd_rotate instead of display_rotate." ;;
    esac

    # Match-and-replace any existing key (commented or not), else append once.
    local out; out="$(mktmp)"
    awk -v key="$key" -v val="$val" '
        BEGIN { done = 0; re = "^[ \t]*#?[ \t]*" key "[ \t]*=" }
        $0 ~ re {
            if (!done) { print key "=" val; done = 1 }
            next
        }
        { print }
        END { if (!done) { print key "=" val } }
    ' "$cfg" > "$out"

    commit_file "$cfg" "$out"
    NEEDS_REBOOT=1
}

# ---------------------------------------------------------------------------
# Touch input on Wayland
#
# In theory a Wayland compositor rotates touch coordinates automatically -- but
# only when the touch device is MAPPED to the rotated output. Built-in DSI
# panels usually are. Generic USB/HDMI touch panels usually are NOT, because
# the compositor cannot tell which output the USB device belongs to.
#
# Hence: --touch-matrix is opt-in. Applying the calibration matrix when the
# compositor is ALREADY rotating touch double-rotates it and makes things
# worse. Rotate the display first, look at it, then come back if needed.
# ---------------------------------------------------------------------------

apply_touch_udev() {
    local matrix; matrix="$(rot_matrix6 "$ROTATION")"

    step "Touch input: libinput calibration matrix (udev)"

    local out; out="$(mktmp)"
    {
        printf '%s\n' "# Touchscreen rotation for the Midnight Brewer kiosk."
        printf '%s\n' "# Managed by ${SCRIPT_NAME} -- rotation: ${ROTATION}"
        printf '%s\n' "#"
        printf '%s\n' "# LIBINPUT_CALIBRATION_MATRIX takes SIX values (the affine part of the"
        printf '%s\n' "# 3x3 matrix), not nine. Nine values are silently ignored."
        printf '%s\n' "#"
        printf '%s\n' "# Delete this file (and reboot) if touch ends up double-rotated."
        printf '%s\n' ''
        if [[ -n "$TOUCH_DEV" ]]; then
            printf 'SUBSYSTEM=="input", KERNEL=="event*", ATTRS{name}=="%s", ENV{LIBINPUT_CALIBRATION_MATRIX}="%s"\n' \
                "$TOUCH_DEV" "$matrix"
            printf '%s\n' '# Fallback for the same panel enumerating under a different parent:'
        fi
        printf 'SUBSYSTEM=="input", KERNEL=="event*", ENV{ID_INPUT_TOUCHSCREEN}=="1", ENV{LIBINPUT_CALIBRATION_MATRIX}="%s"\n' \
            "$matrix"
    } > "$out"

    commit_file "$UDEV_RULE" "$out"
    NEEDS_REBOOT=1

    if [[ $DRY_RUN -eq 0 ]] && have udevadm; then
        udevadm control --reload-rules 2>/dev/null || true
        udevadm trigger --subsystem-match=input 2>/dev/null || true
        dim "udev rules reloaded (a reboot is still the reliable way to apply this)."
    fi
}

# ---------------------------------------------------------------------------
# --status
# ---------------------------------------------------------------------------

show_status() {
    step "Detected environment"

    local model="unknown"
    if [[ -r /proc/device-tree/model ]]; then
        model="$(tr -d '\0' < /proc/device-tree/model)"
    fi
    printf '  %-22s %s\n' "Device:"        "$model"
    printf '  %-22s %s\n' "Desktop user:"  "$REAL_USER ($REAL_HOME)"
    printf '  %-22s %s\n' "Display stack:" "${STACK:-${C_RED}UNDETECTED${C_RESET}}"
    printf '  %-22s %s\n' "Output:"        "${OUTPUT:-${C_RED}UNDETECTED${C_RESET}}"
    printf '  %-22s %s\n' "Touch device:"  "${TOUCH_DEV:-${C_YEL}not found${C_RESET}}"
    printf '  %-22s %s\n' "Graphics driver:" "$(detect_gfx_driver)"
    printf '  %-22s %s\n' "config.txt:"    "$(config_txt_path || printf 'not found')"
    printf '  %-22s %s\n' "cmdline.txt:"   "$(cmdline_txt_path || printf 'not found')"
    printf '  %-22s %s\n' "Current rotation:" "$(current_rotation)"

    step "Managed files"
    local f
    for f in "$REAL_HOME/.config/labwc/autostart" \
             "$REAL_HOME/.config/wayfire.ini" \
             "$REAL_HOME/.config/autostart/${MANAGED_TAG}.desktop" \
             "$REAL_HOME/.config/${MANAGED_TAG}-x11.sh" \
             "$UDEV_RULE" \
             "$(cmdline_txt_path || printf '/nonexistent')" \
             "$(config_txt_path || printf '/nonexistent')"; do
        if [[ "$f" == "/nonexistent" ]]; then continue; fi
        if [[ -f "$f" ]]; then
            if [[ -f "$f.bak" ]]; then
                printf '  %s %s %s\n' "${C_GRN}present${C_RESET}" "$f" "${C_DIM}(has .bak)${C_RESET}"
            else
                printf '  %s %s\n' "${C_GRN}present${C_RESET}" "$f"
            fi
        else
            printf '  %s %s\n' "${C_DIM}absent ${C_RESET}" "${C_DIM}$f${C_RESET}"
        fi
    done

    if [[ "$(detect_gfx_driver)" == "kms" ]]; then
        step "Note"
        dim "Modern KMS driver in use -> display_rotate= / lcd_rotate= in config.txt"
        dim "are IGNORED. Rotation must come from the compositor or the kernel"
        dim "video= cmdline parameter."
    fi
}

# ---------------------------------------------------------------------------
# --revert
# ---------------------------------------------------------------------------

restore_one() {
    local f="$1"
    if [[ -f "$f.bak" ]]; then
        if [[ $DRY_RUN -eq 1 ]]; then
            info "would restore $f from $f.bak"
        else
            cp -p -- "$f.bak" "$f"
            ok "restored $f"
            fix_owner "$f"
        fi
        CHANGED=1
        return 0
    fi
    return 1
}

remove_one() {
    local f="$1"
    [[ -f "$f" ]] || return 1
    if [[ $DRY_RUN -eq 1 ]]; then
        info "would delete $f"
    else
        rm -f -- "$f"
        ok "deleted $f"
    fi
    CHANGED=1
}

do_revert() {
    step "Reverting all changes made by ${SCRIPT_NAME}"

    # Files we edit in place -> restore the pristine backup.
    local f
    for f in "$(cmdline_txt_path || printf '/nonexistent')" \
             "$(config_txt_path  || printf '/nonexistent')" \
             "$REAL_HOME/.config/wayfire.ini"; do
        if [[ "$f" == "/nonexistent" ]]; then continue; fi
        restore_one "$f" || true
    done

    # labwc autostart: restore if we have a backup, else strip our block and
    # delete the file if nothing of the user's remains.
    local la="$REAL_HOME/.config/labwc/autostart"
    if [[ -f "$la" ]]; then
        if ! restore_one "$la"; then
            local out; out="$(mktmp)"
            block_strip "$la" > "$out"
            if [[ -s "$out" ]] && grep -qv '^[[:space:]]*$' "$out"; then
                commit_file "$la" "$out"
            else
                rm -f -- "$out"
                remove_one "$la" || true
            fi
        fi
    fi

    # Files we created outright -> just delete them.
    for f in "$UDEV_RULE" \
             "$REAL_HOME/.config/autostart/${MANAGED_TAG}.desktop" \
             "$REAL_HOME/.config/${MANAGED_TAG}-x11.sh"; do
        remove_one "$f" || true
    done

    if [[ $CHANGED -eq 0 ]]; then
        ok "Nothing to revert -- no backups or managed files found."
        return 0
    fi

    if [[ $DRY_RUN -eq 0 ]] && have udevadm; then
        udevadm control --reload-rules 2>/dev/null || true
    fi

    NEEDS_REBOOT=1
    dim "Numbered .bak-N history files were left in place; delete them by hand if you want."
}

# ---------------------------------------------------------------------------
# Diagnostics on detection failure -- never guess at boot files.
# ---------------------------------------------------------------------------

fail_diagnostics() {
    err "Could not confidently determine the display stack."
    step "Diagnostics"
    printf '  %-22s %s\n' "id:"                 "$(id -un) (uid $(id -u))"
    printf '  %-22s %s\n' "DISPLAY:"            "${DISPLAY:-<unset>}"
    printf '  %-22s %s\n' "WAYLAND_DISPLAY:"    "${WAYLAND_DISPLAY:-<unset>}"
    printf '  %-22s %s\n' "XDG_SESSION_TYPE:"   "${XDG_SESSION_TYPE:-<unset>}"
    printf '  %-22s %s\n' "XDG_CURRENT_DESKTOP:" "${XDG_CURRENT_DESKTOP:-<unset>}"
    printf '  %-22s %s\n' "labwc installed:"    "$(have labwc   && echo yes || echo no)"
    printf '  %-22s %s\n' "wayfire installed:"  "$(have wayfire && echo yes || echo no)"
    printf '  %-22s %s\n' "xrandr installed:"   "$(have xrandr  && echo yes || echo no)"
    printf '  %-22s %s\n' "wlr-randr installed:" "$(have wlr-randr && echo yes || echo no)"
    local cards="none"
    if compgen -G "/dev/dri/card*" >/dev/null 2>&1; then
        cards="$(printf '%s ' /dev/dri/card*)"
    fi
    printf '  %-22s %s\n' "DRM cards:"          "$cards"
    printf '  %-22s %s\n' "DRM connectors:"     "$(detect_output_kms || printf 'none found')"
    echo
    dim "Nothing was modified. Re-run forcing a backend, for example:"
    dim "    sudo ./${SCRIPT_NAME} ${ROTATION:-right} --stack labwc"
    dim "    sudo ./${SCRIPT_NAME} ${ROTATION:-right} --stack kms --output HDMI-A-1"
    exit 2
}

# ---------------------------------------------------------------------------
# Interactive prompt when no rotation was given
# ---------------------------------------------------------------------------

prompt_rotation() {
    if [[ ! -t 0 ]]; then
        ROTATION="normal"
        warn "No rotation given and stdin is not a terminal -- defaulting to 'normal'."
        return 0
    fi
    printf '\n%s\n' "${C_BOLD}Which rotation?${C_RESET}"
    printf '  %s\n' "1) normal    (no rotation)"
    printf '  %s\n' "2) right     (90 clockwise)"
    printf '  %s\n' "3) inverted  (180)"
    printf '  %s\n' "4) left      (90 counter-clockwise)"
    local reply=""
    read -r -p "Choice [1-4, default 1]: " reply || true
    case "$reply" in
        2) ROTATION="right"    ;;
        3) ROTATION="inverted" ;;
        4) ROTATION="left"     ;;
        *) ROTATION="normal"   ;;
    esac
    info "Selected: ${C_BOLD}$ROTATION${C_RESET}"
}

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

main() {
    parse_args "$@"
    resolve_real_user

    printf '%s\n' "${C_BOLD}Midnight Brewer -- display & touch rotation${C_RESET} ${C_DIM}v${VERSION}${C_RESET}"

    if [[ $DRY_RUN -eq 1 ]]; then
        warn "DRY RUN -- nothing will be written."
    fi

    # Detection is needed by every mode, but must never be fatal for --status.
    detect_stack || true
    if [[ -n "$STACK" ]];      then detect_output || true; fi
    if [[ $SKIP_TOUCH -eq 0 ]]; then detect_touch  || true; fi

    if [[ $DO_STATUS -eq 1 ]]; then
        show_status
        exit 0
    fi

    if [[ $DO_REVERT -eq 1 ]]; then
        require_root_for_writes
        do_revert
        finish
        exit 0
    fi

    [[ -n "$ROTATION" ]] || prompt_rotation

    # Refuse to guess -- a wrong guess here corrupts boot files.
    [[ -n "$STACK" ]]  || fail_diagnostics
    if [[ -z "$OUTPUT" ]]; then
        err "Detected stack '$STACK' but could not determine the connected output."
        dim "List your connectors with:  ls /sys/class/drm/"
        dim "Then re-run with:  --output HDMI-A-1   (or DSI-1, HDMI-1, ...)"
        exit 2
    fi

    require_root_for_writes

    step "Plan"
    printf '  %-22s %s\n' "Rotation:"      "${C_BOLD}${ROTATION}${C_RESET}"
    printf '  %-22s %s\n' "Display stack:" "$STACK"
    printf '  %-22s %s\n' "Output:"        "$OUTPUT"
    printf '  %-22s %s\n' "Touch device:"  "${TOUCH_DEV:-${C_YEL}none detected${C_RESET}}"
    printf '  %-22s %s\n' "Graphics driver:" "$(detect_gfx_driver)"

    if [[ $SKIP_TOUCH -eq 0 && -z "$TOUCH_DEV" ]]; then
        echo
        touch_help
    fi

    case "$STACK" in
        labwc)
            apply_labwc
            if [[ $SKIP_TOUCH -eq 0 ]]; then
                if [[ $WANT_TOUCH_MATRIX -eq 1 ]]; then
                    apply_touch_udev
                else
                    step "Touch input"
                    dim "labwc maps touch to the output automatically for panels it can"
                    dim "associate (typically the official DSI display)."
                    dim "USB/HDMI touch panels often are NOT associated."
                    warn "If the display rotates but touch does not, re-run with --touch-matrix"
                    dim "    sudo ./${SCRIPT_NAME} ${ROTATION} --touch-matrix"
                fi
            fi
            ;;
        wayfire)
            apply_wayfire   # includes the touch -> output mapping
            if [[ $SKIP_TOUCH -eq 0 ]]; then
                step "Touch input"
                if [[ $WANT_TOUCH_MATRIX -eq 1 ]]; then
                    apply_touch_udev
                else
                    warn "If the display rotates but touch does not, re-run with --touch-matrix"
                    dim "    sudo ./${SCRIPT_NAME} ${ROTATION} --touch-matrix"
                fi
            fi
            ;;
        x11)
            apply_x11   # handles display + touch together
            ;;
        kms)
            apply_kms
            if [[ $SKIP_TOUCH -eq 0 ]]; then
                # On a bare console there is no compositor to rotate touch,
                # so the calibration matrix is always required.
                apply_touch_udev
            fi
            ;;
        legacy)
            apply_legacy
            if [[ $SKIP_TOUCH -eq 0 ]]; then apply_touch_udev; fi
            ;;
        *)
            fail_diagnostics
            ;;
    esac

    finish
}

finish() {
    step "Result"
    if [[ $CHANGED -eq 0 ]]; then
        ok "Everything was already in the requested state. Nothing changed."
        return 0
    fi

    if [[ $DRY_RUN -eq 1 ]]; then
        warn "DRY RUN -- no files were written. Re-run without --dry-run to apply."
        return 0
    fi

    ok "Changes applied."
    if [[ $NEEDS_REBOOT -eq 1 ]]; then
        echo
        printf '%s\n' "${C_BOLD}${C_YEL}  *** A REBOOT IS REQUIRED ***${C_RESET}"
        printf '%s\n' "${C_BOLD}  sudo reboot${C_RESET}"
        echo
        dim "After rebooting, tap each corner of the kiosk UI to verify touch alignment."
        dim "If the image is rotated the wrong way, re-run with the opposite value"
        dim "(left <-> right). If the image is right but taps are mirrored, see the"
        dim "troubleshooting section in README-rotation.md."
    fi
}

main "$@"
