#!/usr/bin/env bash
#
# Run this ONCE on a fresh Raspberry Pi, over SSH:
#
#   scp scripts/pi-bootstrap.sh martin@raspberrypi.local:~
#   ssh martin@raspberrypi.local 'bash pi-bootstrap.sh'
#
# It installs Java and Maven, enables SPI, adds you to the hardware groups,
# and reports anything it could not do. Safe to run more than once.

set -euo pipefail

echo "=================================================="
echo " Midnight Brewer -- Pi bootstrap"
echo "=================================================="
echo

# ---------------------------------------------------------------- packages
echo "==> updating package lists (this is the slow part)"
sudo apt-get update -qq

echo "==> installing Java 21, Maven, git, rsync"
sudo apt-get install -y -qq openjdk-21-jdk maven git rsync

# ---------------------------------------------------------------- SPI
echo "==> enabling SPI"
if command -v raspi-config >/dev/null 2>&1; then
  # 0 = enable. Counter-intuitive, but that is raspi-config's convention.
  sudo raspi-config nonint do_spi 0
else
  echo "    raspi-config not found -- enabling via /boot/firmware/config.txt"
  BOOTCFG=/boot/firmware/config.txt
  [[ -f $BOOTCFG ]] || BOOTCFG=/boot/config.txt
  grep -q '^dtparam=spi=on' "$BOOTCFG" || echo 'dtparam=spi=on' | sudo tee -a "$BOOTCFG" >/dev/null
fi

# ---------------------------------------------------------------- groups
echo "==> adding $USER to the spi and gpio groups"
sudo usermod -aG spi,gpio "$USER"

# ---------------------------------------------------------------- swap
# A Zero 2 W has 512 MB. Maven and a JVM will hit that, and when Linux runs out
# of memory it kills processes -- usually sshd, which locks you out.
TOTAL_MB=$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo)
if (( TOTAL_MB < 1024 )); then
  echo "==> only ${TOTAL_MB} MB RAM detected; raising swap to 1 GB"
  sudo dphys-swapfile swapoff || true
  sudo sed -i 's/^CONF_SWAPSIZE=.*/CONF_SWAPSIZE=1024/' /etc/dphys-swapfile
  sudo dphys-swapfile setup
  sudo dphys-swapfile swapon
fi

# ---------------------------------------------------------------- report
echo
echo "=================================================="
echo " Results"
echo "=================================================="
printf ' java   : %s\n' "$(java -version 2>&1 | head -1)"
printf ' maven  : %s\n' "$(mvn -v 2>/dev/null | head -1)"
printf ' git    : %s\n' "$(git --version)"
printf ' model  : %s\n' "$(tr -d '\0' < /proc/device-tree/model 2>/dev/null || echo unknown)"
printf ' memory : %s MB\n' "$TOTAL_MB"

echo
if [[ -e /dev/spidev0.0 ]]; then
  echo " SPI    : /dev/spidev0.0 present"
else
  echo " SPI    : NOT present yet -- a REBOOT is required"
fi

if id -nG "$USER" | tr ' ' '\n' | grep -qx spi; then
  echo " groups : spi group active in this session"
else
  echo " groups : spi group added but NOT active -- log out and back in"
fi

echo
echo "Next: reboot, then reconnect."
echo "  sudo reboot"
echo
echo "After the reboot, verify with:"
echo "  ls -l /dev/spidev0.0     # must exist"
echo "  groups                   # must list spi and gpio"
