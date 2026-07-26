#!/usr/bin/env bash
#
# Launch the kiosk fullscreen on the Pi's touchscreen.
#
#   ./scripts/run-kiosk.sh
#
# Assumes `make deploy-full` has run at least once.

set -euo pipefail

PI="${PI:-martin@raspberrypi.local}"
REMOTE_DIR="${REMOTE_DIR:-midnight-brewer}"

# -Dprism.forceGPU=true is NOT optional on a Pi.
#
# The VideoCore driver reports OpenGL 2.1; JavaFX's Prism pipeline checks for a
# higher version and silently falls back to SOFTWARE rendering. The GPU is
# perfectly capable -- the version check is simply wrong. Without this flag a
# fade that should take 16 ms takes 400, and the kiosk feels broken.
#
# -Dprism.verbose=true prints the chosen pipeline at startup: you want "es2"
# (hardware), not "sw".

ssh -t "$PI" "cd ~/$REMOTE_DIR && \
  DISPLAY=:0 WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=/run/user/\$(id -u) \
  java -Dprism.forceGPU=true \
       -Dprism.verbose=true \
       -cp 'target/classes:target/dependency/*' \
       com.midnightbrewer.ui.MainApp"
