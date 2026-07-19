# Display & touchscreen rotation — `fix-rotation.sh`

Rotates the Raspberry Pi display **and** the touchscreen input for the
Midnight Brewer kiosk (JavaFX, 1024x600, fullscreen).

> **The one thing to know:** on a Raspberry Pi, rotating the *display* does
> **not** rotate the *touch input*. The panel keeps reporting coordinates in
> its original orientation, so the picture looks right but your taps land
> somewhere else — usually mirrored, or with X and Y swapped. Both halves have
> to be fixed, and this script does both.

---

## Quick start

```bash
cd scripts
chmod +x fix-rotation.sh

./fix-rotation.sh --status          # what am I actually running?
./fix-rotation.sh right --dry-run   # preview every change, write nothing
sudo ./fix-rotation.sh right        # apply
sudo reboot
```

Then tap each corner of the kiosk UI to confirm touch lines up.

### Rotation values

Given in xrandr's vocabulary, describing how the **image** is rotated:

| Value      | Meaning                    |
| ---------- | -------------------------- |
| `normal`   | no rotation                |
| `right`    | 90° clockwise              |
| `inverted` | 180°                       |
| `left`     | 90° counter-clockwise      |

Panel mounting orientation cannot be detected from software. **If the image
comes out rotated the wrong way, re-run with the opposite value** (`left` ↔
`right`). That is expected, not a bug.

### Options

| Option | Purpose |
| --- | --- |
| `--status` | Show detected environment + current rotation. Read-only, no root needed. |
| `--dry-run` | Print every change as a diff, write nothing. No root needed. |
| `--revert` | Restore everything from the pristine `.bak` backups. |
| `--mode WxH@Hz` | Mode for the KMS `video=` parameter (default `1024x600@60`). |
| `--output NAME` | Force the connector (`HDMI-1`, `HDMI-A-1`, `DSI-1`, …). |
| `--stack NAME` | Force the backend: `labwc`, `wayfire`, `x11`, `kms`, `legacy`. |
| `--touch-device NAME` | Force the touch device name, skipping auto-detection. |
| `--touch-matrix` | Wayland only — also write the udev calibration matrix. Use when the display rotated but touch did not. |
| `--no-touch` | Rotate the display only, leave touch alone. |

---

## What each backend does

The script detects your display stack (running compositor first, then session
environment, then installed configs, then bare DRM) and edits only the files
that stack actually reads.

### `labwc` — Raspberry Pi OS Bookworm default since late 2024

Writes a marked block into `~/.config/labwc/autostart`:

```sh
# >>> fix-rotation managed block >>>
wlr-randr --output HDMI-A-1 --transform 90
# <<< fix-rotation managed block <<<
```

Needs `wlr-randr` (`sudo apt install wlr-randr`); the script warns if it is
missing. Anything you wrote in that file outside the markers is preserved.

### `wayfire` — earlier Bookworm / Pi 4 desktop

Edits `~/.config/wayfire.ini`:

```ini
[output:HDMI-A-1]
transform = 90

[input-device:FT5406 memory based driver]
output = HDMI-A-1
```

Transform values are `normal | 90 | 180 | 270`. The `[input-device:…]` section
is the *correct* way to make touch follow the rotation — it tells wayfire which
output the panel belongs to, and wayfire then transforms the coordinates itself.

### `x11` — Bullseye and earlier, LXDE

Generates `~/.config/fix-rotation-x11.sh` containing both halves of the fix,
and autostarts it via `~/.config/autostart/fix-rotation.desktop`:

```sh
xrandr --output HDMI-1 --rotate right
xinput set-prop "FT5406 memory based driver" "Coordinate Transformation Matrix" 0 1 0 -1 0 1 0 0 1
```

If you already run a session-wide autostart, the equivalent system-wide
location is `/etc/xdg/lxsession/LXDE-pi/autostart` (add `@` before the command).

The script also applies both live if it detects a running X session.

### `kms` — no desktop, JavaFX straight on the framebuffer (Monocle)

Appends a kernel parameter to `/boot/firmware/cmdline.txt` (Bookworm) or
`/boot/cmdline.txt` (older) — whichever exists:

```
video=HDMI-A-1:1024x600@60,rotate=90
```

This rotates the framebuffer console and fbdev, which is what a Monocle/
framebuffer JavaFX app draws to. There is no compositor here to rotate touch,
so the udev calibration matrix is **always** written in this mode.

> If a Wayland or X11 compositor later starts, it overrides this with its own
> transform. `video=…,rotate=` and a compositor transform are not additive —
> the compositor wins.

### `legacy` — `display_rotate=` / `lcd_rotate=` in `config.txt`

Only reachable with `--stack legacy`, because it is almost always the wrong
answer on a modern Pi:

> **`display_rotate` is IGNORED under the modern KMS driver**
> (`dtoverlay=vc4-kms-v3d`, the default on Bookworm). It only does anything on
> the legacy firmware/fbturbo stack.

The script detects which driver `config.txt` configures (`kms`, `fkms`, or
`legacy`) and tells you plainly whether the setting will have any effect.
`--status` reports the same thing. DSI panels use `lcd_rotate`; HDMI uses
`display_rotate` — the script picks based on the detected connector.

---

## Touch input, in detail

### X11

Touch is rotated with a 3×3 **Coordinate Transformation Matrix**, nine values:

| Rotation | Matrix |
| --- | --- |
| `normal` | `1 0 0 0 1 0 0 0 1` |
| `right` (90° CW) | `0 1 0 -1 0 1 0 0 1` |
| `inverted` (180°) | `-1 0 1 0 -1 1 0 0 1` |
| `left` (90° CCW) | `0 -1 1 1 0 0 0 0 1` |

### Wayland (labwc / wayfire)

A Wayland compositor rotates touch coordinates **automatically — but only if
the touch device is mapped to the rotated output.** The built-in DSI panel
usually is. Generic USB and HDMI touch panels usually are **not**, because the
compositor cannot tell which output a USB device physically belongs to.

So on Wayland the script rotates the display first and leaves touch alone,
unless you pass `--touch-matrix`. That is deliberate: if the compositor is
*already* rotating touch and you also apply a calibration matrix, the rotation
is applied **twice** and touch ends up worse than before. Rotate the display,
look at it, and only then come back with `--touch-matrix` if needed.

`--touch-matrix` writes `/etc/udev/rules.d/99-touchscreen-rotate.rules`:

```
SUBSYSTEM=="input", KERNEL=="event*", ATTRS{name}=="FT5406 memory based driver", ENV{LIBINPUT_CALIBRATION_MATRIX}="0 1 0 -1 0 1"
SUBSYSTEM=="input", KERNEL=="event*", ENV{ID_INPUT_TOUCHSCREEN}=="1", ENV{LIBINPUT_CALIBRATION_MATRIX}="0 1 0 -1 0 1"
```

> **`LIBINPUT_CALIBRATION_MATRIX` takes SIX values, not nine** — the affine part
> (first two rows) of the 3×3 matrix. Passing nine is a common mistake and
> libinput silently ignores the entire rule, which looks exactly like "my udev
> rule doesn't work."

---

## How to undo

```bash
sudo ./fix-rotation.sh --revert
sudo reboot
```

This restores every edited file from its **pristine** `.bak` and deletes the
files the script created outright (the udev rule, the X11 helper, the autostart
entry).

### Backup scheme

| File | Meaning |
| --- | --- |
| `<file>.bak` | The pristine original, written once on first edit and **never overwritten**. This is what `--revert` restores. |
| `<file>.bak-1`, `.bak-2`, … | Rolling history of later edits, capped at 20. |

Backups are deliberately **not** timestamped, so repeated runs don't litter
`/boot` with dozens of files.

To undo by hand instead:

```bash
sudo cp /boot/firmware/cmdline.txt.bak /boot/firmware/cmdline.txt
rm ~/.config/labwc/autostart.bak       # etc.
sudo rm /etc/udev/rules.d/99-touchscreen-rotate.rules
```

---

## Troubleshooting

### "The display rotated, but touch is wrong"

The single most common outcome. Work through it in order:

1. **Which way is it wrong?**
   - *Taps land as if the screen were still un-rotated* → touch was not rotated
     at all. On Wayland, re-run with `--touch-matrix`:
     ```bash
     sudo ./fix-rotation.sh right --touch-matrix && sudo reboot
     ```
   - *Taps are mirrored along one axis, or X and Y are swapped the wrong way* →
     the rotation direction is inverted. Try the opposite value (`left` ↔
     `right`).
   - *Touch got worse after adding `--touch-matrix`* → it is now **double
     rotated**: the compositor was already handling it. Remove the rule:
     ```bash
     sudo rm /etc/udev/rules.d/99-touchscreen-rotate.rules
     sudo reboot
     ```

2. **Check the matrix actually applied** (X11):
   ```bash
   xinput list-props "Your Device Name" | grep -i "Coordinate Transformation"
   ```
   All-identity (`1.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 1.0`) means it never applied —
   usually a wrong device name.

3. **Check the udev rule applied** (Wayland):
   ```bash
   udevadm info /dev/input/event0 | grep -i CALIBRATION
   ```
   Nothing? The device name in the rule doesn't match, or you have nine values
   where libinput wants six.

4. A reboot is genuinely required for the udev route. `udevadm control
   --reload-rules` alone does not re-apply the matrix to an already-open device.

### "How do I find my touch device name?"

Any of these, depending on what's installed:

```bash
xinput list                    # X11 — the name is between the ⎜ characters
libinput list-devices          # Wayland (sudo apt install libinput-tools)
cat /proc/bus/input/devices    # always available; look at the N: Name= lines
```

Common names on a Pi: `FT5406 memory based driver` (official 7" DSI panel),
`raspberrypi-ts`, `Goodix Capacitive TouchScreen`, `ILITEK ILITEK-TP`,
`EP0110M09` (many generic 1024x600 HDMI panels).

Then pass it explicitly — quote it, names contain spaces:

```bash
sudo ./fix-rotation.sh right --touch-device "Goodix Capacitive TouchScreen"
```

### "Nothing happened after reboot"

- Run `./fix-rotation.sh --status` and check the **Display stack** line matches
  what you're really running. If detection was wrong, force it with `--stack`.
- Check the **Output** line. If the connector is wrong the config is written for
  a display that doesn't exist. List real connectors with `ls /sys/class/drm/`
  and pass `--output HDMI-A-1`.
- labwc: is `wlr-randr` installed? Without it the autostart line silently fails.
- wayfire: confirm the section header exactly matches the connector name.

### "`display_rotate` in config.txt does nothing"

Expected on any current Raspberry Pi OS — see the `legacy` section above.
`display_rotate` and `lcd_rotate` are firmware-era settings that the modern
KMS driver ignores completely. Use the compositor backend, or `--stack kms`.

### "The script refuses to run and prints diagnostics"

It could not confidently identify the display stack, so it stopped rather than
guess. Guessing wrong here means writing to boot files for the wrong stack. The
diagnostics block shows what it looked at; force the backend explicitly:

```bash
sudo ./fix-rotation.sh right --stack labwc --output HDMI-A-1
```

### "The Pi won't boot after editing cmdline.txt"

`cmdline.txt` must be a **single line** — a stray newline breaks boot. The
script guards against this (it rebuilds the line token-by-token, refuses to
write anything with an embedded newline, and even repairs an already-broken
multi-line file), but if you have hand-edited it:

1. Put the SD card in another machine.
2. Open the small FAT `bootfs` partition.
3. Replace `cmdline.txt` with `cmdline.txt.bak`, or delete the
   `video=…,rotate=…` token, making sure everything stays on one line.

### JavaFX-specific notes

- The app is a fixed 1024x600 fullscreen scene. If you rotate to `left`/`right`
  the framebuffer becomes 600x1024 and the scene will letterbox — rotate the
  *panel* to portrait only if the UI is designed for it.
- Running headless on the framebuffer (Monocle), pass `--stack kms`; there is
  no compositor to rotate touch for you, so the udev matrix is mandatory.
- Verify `--mode` matches your panel's real mode; a wrong mode in `video=` can
  leave you with a blank screen. `--status` shows the current cmdline.

---

## Design notes

- **Idempotent.** Running twice never duplicates a line. `cmdline.txt` is
  rebuilt token-by-token (existing `video=` for the same connector is removed
  before the new one is added), `config.txt` and `wayfire.ini` keys are
  match-and-replaced, and shell configs use delimited managed blocks.
- **`cmdline.txt` stays one line.** Written with a single trailing newline and
  rejected outright if it would ever contain more than one.
- **Root only where needed.** `--status` and `--dry-run` work as a normal user;
  anything that writes requires root and refuses otherwise.
- **Fails loudly rather than guessing.** Undetectable environment → diagnostics
  and exit code 2, with nothing modified.
- **Runs under `sudo` correctly.** Resolves the real desktop user via
  `$SUDO_USER` so `~/.config/…` means *your* home, not `/root`, and chowns
  anything it creates back to you.
