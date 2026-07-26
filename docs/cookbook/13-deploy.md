# 13 — Deploying to the Pi

**Goal:** the machine boots into the kiosk, fullscreen, correctly rotated, GPU-accelerated, with no keyboard attached.

---

## 13.1 Rotating the display

A 7″ panel mounted vertically needs the compositor rotated, not the app. Raspberry Pi OS Bookworm+ uses **labwc** (Wayland) by default.

`~/.config/labwc/autostart`:

```bash
# 1024x600 panel mounted rotated -> the app sees a 600x1024 portrait screen.
wlr-randr --output HDMI-A-1 --transform 90 &
```

Check your output name with `wlr-randr` — it may be `HDMI-A-2`.

**Touch must be rotated too**, and this is the step everyone forgets. An unrotated touchscreen on a rotated display gives you inverted axes: you press the top-left tile and the bottom-right one activates. Maddening, and it looks like a UI bug.

For Wayland/labwc, add to `~/.config/labwc/rc.xml` inside `<libinput>`:

```xml
<device category="touch">
  <transformationMatrix>0 -1 1 1 0 0</transformationMatrix>
</device>
```

That matrix is a 90° rotation. For 270° use `0 1 0 -1 0 1`. If touch is mirrored rather than rotated, you have the wrong one.

> **Why the app doesn't rotate itself:** if JavaFX rendered rotated, every animation would run through a transform, the compositor would still composite unrotated, and touch would still be wrong. Rotating at the compositor means everything below it — including input — is consistently transformed, and the app just sees a portrait screen.

---

## 13.2 GPU acceleration — the one flag that matters

JavaFX on a Pi will silently fall back to software rendering, and the difference is night and day: a fade that takes 16 ms on the GPU takes 400 ms on the CPU, and your kiosk feels broken.

The cause is specific: the VideoCore VI driver reports **OpenGL 2.1**, and JavaFX's Prism pipeline checks for a higher version and refuses. The GPU is perfectly capable; the version check is wrong.

```bash
java -Dprism.forceGPU=true \
     -Dprism.verbose=true \
     -jar kiosk.jar
```

`-Dprism.verbose=true` prints the chosen pipeline at startup. You want to see `es2` (hardware). If it says `sw`, you're on the CPU.

Also worth setting in `/boot/firmware/config.txt`:

```ini
dtoverlay=vc4-kms-v3d
gpu_mem=128
```

**Other things that mattered on the Zero 2 W:**

- **Cache the pulse rings.** `ring.setCache(true); ring.setCacheHint(CacheHint.SPEED);` — without it, JavaFX re-rasterises the vector circle every frame while it scales. With it, you're scaling a bitmap, which is nearly free.
- **Hide the cursor** (`scene.setCursor(Cursor.NONE)`). Xwayland parks a pointer mid-screen; it sits on a tile and leaves it stuck in `:hover`, which reads as a pre-selected drink.
- **Scope hover styling** to a `.desktop` CSS class you only enable in windowed dev mode. A touchscreen has no hover state to represent.

---

## 13.3 Autostart with systemd

`/etc/systemd/system/midnight-brewer.service`:

```ini
[Unit]
Description=The Midnight Brewer kiosk
After=graphical.target

[Service]
Type=simple
User=martin
Environment=DISPLAY=:0
Environment=WAYLAND_DISPLAY=wayland-0
Environment=XDG_RUNTIME_DIR=/run/user/1000
WorkingDirectory=/home/martin/midnight-brewer
ExecStart=/usr/bin/java -Dprism.forceGPU=true \
    -cp "target/classes:target/dependency/*" com.midnightbrewer.ui.MainApp
Restart=always
RestartSec=5

[Install]
WantedBy=graphical.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now midnight-brewer
journalctl -u midnight-brewer -f      # watch it
```

**`Restart=always` is the important line.** A kiosk that dies at 3 a.m. and stays dead is worse than one that restarts in five seconds. Combined with the broad `catch (RuntimeException)` from Chapter 11, you have two layers of resilience: bad taps don't kill the thread, and a fatal crash doesn't kill the machine.

**SPI permissions:** the user must be in the `spi` and `gpio` groups, or Pi4J can't open the device:

```bash
sudo usermod -aG spi,gpio martin      # log out and back in
```

---

## 13.4 The deploy loop

Compiling on a Zero 2 W is slow enough to break your concentration. Two options:

**Rsync the source, build on the Pi** (simple, ~40 s):

```bash
rsync -av --exclude target/ ./ martin@raspberrypi.local:~/midnight-brewer/
ssh martin@raspberrypi.local 'cd ~/midnight-brewer && mvn -q -DskipTests compile'
```

**Or build locally, ship the classes** (faster, ~5 s) — Java bytecode is architecture-independent, so a laptop build runs unchanged on ARM:

```bash
mvn -q -DskipTests compile
rsync -av target/classes/ martin@raspberrypi.local:~/midnight-brewer/target/classes/
```

Do the dependency copy **once**:

```bash
ssh martin@raspberrypi.local 'cd ~/midnight-brewer && mvn dependency:copy-dependencies -DincludeScope=runtime'
```

> ⚠️ **Stale classes are a real trap.** If you change a file and the old `.class` survives, you'll debug behaviour that no longer exists in your source. When something makes no sense at all, `mvn clean compile` before anything else.

---

## 13.5 Field hygiene

Three habits that keep a kiosk healthy:

**Power-cycle the RF field per transaction.** `softReset()` + `antennaOn()` at the start of each tap puts any lingering card back to IDLE. Without it, a card left on the antenna after one purchase is in the wrong state for the next.

**Always close the link.** `try-with-resources` on `Pi4jSpiLink`, and `close()` in the JavaFX `stop()` hook. An RF field left energised overnight cooks the module.

**Never log key material.** It's tempting during debugging to print session keys. If that line survives into production, your logs are now a key store. Print the *TI* and the *counter* instead — they tell you everything you need for protocol debugging and nothing an attacker wants.

---

## The finished machine

```
boot → labwc (rotated) → systemd → JavaFX kiosk (GPU)
                                        │
                                   PaymentTerminal
                                        │
                              DesfirePaymentTerminal
                                        │
                     your stack: SPI → RC522 → ISO-DEP → DESFire
                                        │
                                   a real debit
```

---

## Commit

```bash
git add -A
git commit -m "deploy: rotation, GPU flag, systemd unit, deploy scripts"
```

---

*Next: [Appendix A — Test vectors](A-test-vectors.md)*
