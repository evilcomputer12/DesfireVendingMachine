#!/usr/bin/env bash
#
# Run a main class on the Pi.
#
#   ./scripts/run.sh com.midnightbrewer.ToolchainCheck
#   ./scripts/run.sh com.midnightbrewer.diag.SpiProbe
#
# Anything after the class name is passed to the program as arguments.

set -euo pipefail

PI="${PI:-martin@raspberrypi.local}"
REMOTE_DIR="${REMOTE_DIR:-midnight-brewer}"

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <main.class.Name> [args...]" >&2
  echo >&2
  echo "examples:" >&2
  echo "  $0 com.midnightbrewer.ToolchainCheck" >&2
  echo "  $0 com.midnightbrewer.diag.SpiProbe" >&2
  exit 1
fi

MAIN="$1"; shift

# -t forces a TTY so Ctrl-C reaches the program on the Pi instead of just
# killing your local ssh client and leaving it running over there.
ssh -t "$PI" "cd ~/$REMOTE_DIR && java -cp 'target/classes:target/dependency/*' $MAIN $*"
