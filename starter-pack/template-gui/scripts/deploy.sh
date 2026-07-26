#!/usr/bin/env bash
#
# Build locally, ship the compiled classes to the Pi.
#
# Java bytecode is architecture-independent, so a laptop build runs unchanged
# on the Pi's ARM chip. This is ~5 seconds instead of ~40 spent compiling on a
# Zero 2 W, and it keeps your slow machine out of your fast feedback loop.
#
#   ./scripts/deploy.sh                 # build + copy classes
#   ./scripts/deploy.sh --full          # also re-copy dependency jars
#
# Configure the target by editing PI below, or by setting it in your shell:
#   export PI=martin@brewer.local

set -euo pipefail

PI="${PI:-martin@raspberrypi.local}"
REMOTE_DIR="${REMOTE_DIR:-midnight-brewer}"

cd "$(dirname "$0")/.."

echo "==> building locally"
mvn -q -DskipTests compile

echo "==> ensuring remote directory exists"
ssh "$PI" "mkdir -p ~/$REMOTE_DIR/target/classes"

echo "==> copying classes to $PI:~/$REMOTE_DIR"
rsync -a --delete target/classes/ "$PI:$REMOTE_DIR/target/classes/"

if [[ "${1:-}" == "--full" ]]; then
  echo "==> resolving dependencies locally"
  mvn -q dependency:copy-dependencies -DincludeScope=runtime
  echo "==> copying dependency jars (only needed when pom.xml changes)"
  ssh "$PI" "mkdir -p ~/$REMOTE_DIR/target/dependency"
  rsync -a target/dependency/ "$PI:$REMOTE_DIR/target/dependency/"
fi

echo "==> note: FXML, CSS and images live in target/classes too,\n            so they ship with the same rsync."

echo "==> done"
echo
echo "   run something on the Pi with:"
echo "     ./scripts/run.sh com.midnightbrewer.ToolchainCheck"
