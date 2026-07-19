#!/usr/bin/env bash
#
# Launch the Midnight Brewer kiosk on a Raspberry Pi.
#
# Why this exists instead of `mvn javafx:run`:
#
#   `mvn javafx:run` starts TWO JVMs -- Maven's own launcher, which stays
#   resident for the whole session, plus the forked application JVM. On a Pi
#   Zero 2 W with 416 MB of RAM that is enough to exhaust memory, drive the
#   box into swap, and take sshd down with it. This script builds the module
#   path once and execs a single JVM.
#
# Usage:
#   ./scripts/run-kiosk.sh              # fullscreen kiosk
#   ./scripts/run-kiosk.sh --windowed   # windowed, for development
#
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$PROJECT_DIR/target/classes"
JAVAFX_VERSION="${JAVAFX_VERSION:-17.0.2}"
M2="${M2_REPO:-$HOME/.m2/repository}"

# Heap is deliberately small. The default on a 416 MB box is a quarter of
# RAM, and JavaFX plus the JVM's own overhead will happily exceed what is
# actually free once labwc and the rest of the desktop are accounted for.
HEAP="${KIOSK_HEAP:-192m}"

die() { printf '\033[31merror:\033[0m %s\n' "$1" >&2; exit 1; }
info() { printf '\033[36m%s\033[0m\n' "$1"; }

[ -d "$CLASSES" ] || die "target/classes not found -- run: mvn -o -DskipTests compile"

# --- runtime classpath -----------------------------------------------------
# The kiosk itself, plus the reference RC522/DESFire stack and its Pi4J and
# slf4j jars, so real card payment works. JavaFX is deliberately NOT here -- it
# must live on the module path only, or the launcher reports it as missing.
CP="$CLASSES"
REF_CLASSES="$PROJECT_DIR/reference/target/classes"
[ -d "$REF_CLASSES" ] && CP="$CP:$REF_CLASSES"
# Pi4J and slf4j from the local repository. Globbing avoids pinning versions
# here; if the reader stack is absent the kiosk simply falls back to the
# simulator at runtime.
if [ -d "$M2/com/pi4j" ]; then
    while IFS= read -r jar; do CP="$CP:$jar"; done \
        < <(find "$M2/com/pi4j" "$M2/org/slf4j" -name '*.jar' 2>/dev/null)
fi

# --- module path -----------------------------------------------------------
# JavaFX ships platform-specific natives under a classifier. On a Pi that is
# linux-aarch64; fall back to the plain artifact so this also works on a
# desktop checkout.
detect_classifier() {
    case "$(uname -m)" in
        aarch64|arm64) echo "linux-aarch64" ;;
        armv7l|armv6l) echo "linux-arm32-monocle" ;;
        x86_64)        echo "linux" ;;
        *)             echo "" ;;
    esac
}
CLASSIFIER="${JAVAFX_CLASSIFIER:-$(detect_classifier)}"

MODULE_PATH=""
for m in base graphics controls fxml; do
    jar="$M2/org/openjfx/javafx-$m/$JAVAFX_VERSION/javafx-$m-$JAVAFX_VERSION-$CLASSIFIER.jar"
    if [ ! -f "$jar" ]; then
        jar="$M2/org/openjfx/javafx-$m/$JAVAFX_VERSION/javafx-$m-$JAVAFX_VERSION.jar"
    fi
    [ -f "$jar" ] || die "missing JavaFX module '$m' at $jar
  Populate the local repository once with:  mvn -DskipTests compile"
    MODULE_PATH="${MODULE_PATH:+$MODULE_PATH:}$jar"
done

# --- session environment ---------------------------------------------------
# When invoked over SSH none of these are inherited, so the app has no idea
# which display to draw on. JavaFX 17 has no native Wayland backend; it
# renders through GTK on X11, which under labwc means Xwayland.
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export WAYLAND_DISPLAY="${WAYLAND_DISPLAY:-wayland-0}"
export DISPLAY="${DISPLAY:-:0}"

# The Pi runs under the C locale, which makes the JVM pick ASCII for stdout
# and turn every non-ASCII character in a log line into '?'.
JVM_OPTS=(
    "-Xmx$HEAP"
    "-Dfile.encoding=UTF-8"
    "-Dstdout.encoding=UTF-8"
    "-Dstderr.encoding=UTF-8"
    "-Dprism.vsync=true"
)

if [ "${1:-}" = "--windowed" ]; then
    JVM_OPTS+=("-Dbrewer.windowed=true")
fi

# Refuse to stack instances -- a second copy is another whole JVM.
if pgrep -f "com.midnightbrewer.ui.MainApp" >/dev/null 2>&1; then
    die "kiosk already running (pkill -f com.midnightbrewer.ui.MainApp to stop it)"
fi

info "Starting kiosk  [heap=$HEAP, javafx=$JAVAFX_VERSION/$CLASSIFIER, display=$DISPLAY]"

# exec, so this script does not linger as an extra process.
exec java \
    --module-path "$MODULE_PATH" \
    --add-modules javafx.base,javafx.graphics,javafx.controls,javafx.fxml \
    "${JVM_OPTS[@]}" \
    -cp "$CP" \
    com.midnightbrewer.ui.MainApp
