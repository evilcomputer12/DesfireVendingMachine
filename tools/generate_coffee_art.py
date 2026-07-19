#!/usr/bin/env python3
"""
Generates the drink artwork for The Midnight Brewer kiosk.

Every drink is drawn from scratch as a vector-ish illustration so the whole
menu reads as one set: same camera angle, same light direction, same margins.
Rendered at 4x and downsampled, which is what gives the edges their smoothness.

    python3 tools/generate_coffee_art.py

Writes 512x512 RGBA PNGs into src/main/resources/images/.
"""

import os
from PIL import Image, ImageDraw, ImageChops, ImageFilter

# Work in 512-space, render at 4x, downsample at the end.
SIZE = 512
SS = 4
W = SIZE * SS

OUT_DIR = os.path.join("src", "main", "resources", "images")

# ── palette ──────────────────────────────────────────────────────────────
CERAMIC = (245, 241, 234)
CERAMIC_SHADE = (214, 206, 194)
CERAMIC_DARK = (188, 178, 164)
GLASS = (226, 238, 243)
SAUCER = (238, 233, 224)
SHADOW = (0, 0, 0, 38)


def s(v):
    """512-space -> render-space."""
    return int(round(v * SS))


def vgrad(w, h, top, bottom):
    """Vertical gradient strip, used for every liquid so nothing looks flat."""
    grad = Image.new("RGB", (1, max(h, 1)))
    px = grad.load()
    for y in range(max(h, 1)):
        t = y / max(h - 1, 1)
        px[0, y] = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
    return grad.resize((max(w, 1), max(h, 1)), Image.NEAREST).convert("RGBA")


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c[:3])


# ── vessel geometry ──────────────────────────────────────────────────────
# Each builder returns (draw_body_fn, interior_mask, rim_box, liquid_top_y, bottom_y)


def _ellipse_h(width):
    """Perspective squash of the rim: how tall the rim ellipse looks."""
    return width * 0.26


def cup_shape(top_w, bot_w, top_y, bot_y, cx=SIZE / 2):
    """Tapered ceramic cup outline as a polygon, in 512-space."""
    return [
        (cx - top_w / 2, top_y),
        (cx + top_w / 2, top_y),
        (cx + bot_w / 2, bot_y),
        (cx - bot_w / 2, bot_y),
    ]


def draw_cup(canvas, top_w, bot_w, top_y, bot_y, wall=9, handle=True,
             body=CERAMIC, cx=SIZE / 2, glassy=False):
    """
    Draws a cup and returns an L-mode mask of its interior cavity so liquid
    can be clipped into it. Everything is in 512-space; scaling happens here.
    """
    d = ImageDraw.Draw(canvas)
    eh_top = _ellipse_h(top_w)
    eh_bot = _ellipse_h(bot_w) * 0.7

    # --- handle (behind the body so it tucks in cleanly) ---
    if handle:
        hx = cx + top_w / 2
        hy = top_y + (bot_y - top_y) * 0.42
        hr = top_w * 0.30
        d.ellipse(
            [s(hx - hr * 0.55), s(hy - hr), s(hx + hr * 1.15), s(hy + hr)],
            outline=shade(body, 0.93), width=s(wall * 1.5),
        )

    # --- body ---
    poly = cup_shape(top_w, bot_w, top_y, bot_y, cx)
    d.polygon([(s(x), s(y)) for x, y in poly], fill=body)
    # rounded base
    d.ellipse(
        [s(cx - bot_w / 2), s(bot_y - eh_bot), s(cx + bot_w / 2), s(bot_y + eh_bot)],
        fill=body,
    )
    # right-hand shading: light comes from the upper left, consistently
    shade_poly = [
        (cx + top_w * 0.16, top_y),
        (cx + top_w / 2, top_y),
        (cx + bot_w / 2, bot_y),
        (cx + bot_w * 0.20, bot_y),
    ]
    d.polygon([(s(x), s(y)) for x, y in shade_poly], fill=shade(body, 0.93))

    # --- rim ---
    rim_box = [s(cx - top_w / 2), s(top_y - eh_top / 2),
               s(cx + top_w / 2), s(top_y + eh_top / 2)]
    d.ellipse(rim_box, fill=shade(body, 0.88))

    # --- interior cavity mask ---
    mask = Image.new("L", canvas.size, 0)
    md = ImageDraw.Draw(mask)
    in_top_w = top_w - wall * 2
    in_bot_w = bot_w - wall * 2
    in_poly = cup_shape(in_top_w, in_bot_w, top_y, bot_y - wall, cx)
    md.polygon([(s(x), s(y)) for x, y in in_poly], fill=255)
    md.ellipse(
        [s(cx - in_top_w / 2), s(top_y - _ellipse_h(in_top_w) / 2),
         s(cx + in_top_w / 2), s(top_y + _ellipse_h(in_top_w) / 2)],
        fill=255,
    )
    md.ellipse(
        [s(cx - in_bot_w / 2), s(bot_y - wall - eh_bot * 0.7),
         s(cx + in_bot_w / 2), s(bot_y - wall + eh_bot * 0.7)],
        fill=255,
    )

    if glassy:
        # a soft vertical highlight makes glass read as glass
        gl = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
        gd = ImageDraw.Draw(gl)
        gd.rounded_rectangle(
            [s(cx - top_w * 0.34), s(top_y + 18), s(cx - top_w * 0.22), s(bot_y - 26)],
            radius=s(8), fill=(255, 255, 255, 90),
        )
        gl = gl.filter(ImageFilter.GaussianBlur(s(3)))
        canvas.alpha_composite(gl)

    return mask, top_y, bot_y - wall


def fill_liquid(canvas, mask, layers, top_y, bot_y, width_hint):
    """
    layers: list of (start_frac, end_frac, color_top, color_bottom) measured
    from the liquid surface downward. Clipped to the vessel interior.
    """
    liquid = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    total_h = bot_y - top_y
    for a, b, c_top, c_bot in layers:
        y0 = top_y + total_h * a
        y1 = top_y + total_h * b
        h = s(y1) - s(y0)
        if h <= 0:
            continue
        strip = vgrad(canvas.size[0], h, c_top, c_bot)
        liquid.paste(strip, (0, s(y0)))

    liquid.putalpha(ImageChops.multiply(liquid.getchannel("A"), mask))
    canvas.alpha_composite(liquid)

    # liquid surface ellipse — sells the viewing angle
    surf_w = width_hint
    eh = _ellipse_h(surf_w)
    top_c = layers[0][2]
    surf = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(surf)
    sd.ellipse(
        [s(SIZE / 2 - surf_w / 2), s(top_y - eh / 2),
         s(SIZE / 2 + surf_w / 2), s(top_y + eh / 2)],
        fill=shade(top_c, 1.10),
    )
    surf.putalpha(ImageChops.multiply(surf.getchannel("A"), mask))
    canvas.alpha_composite(surf)


def draw_saucer(canvas, y, width):
    d = ImageDraw.Draw(canvas)
    eh = width * 0.20
    d.ellipse([s(SIZE / 2 - width / 2), s(y - eh / 2),
               s(SIZE / 2 + width / 2), s(y + eh / 2)], fill=SAUCER)
    d.ellipse([s(SIZE / 2 - width / 2), s(y - eh / 2 + 7),
               s(SIZE / 2 + width / 2), s(y + eh / 2 + 7)], fill=CERAMIC_DARK)
    d.ellipse([s(SIZE / 2 - width / 2), s(y - eh / 2),
               s(SIZE / 2 + width / 2), s(y + eh / 2)], fill=SAUCER)


def draw_ground_shadow(canvas, y, width):
    sh = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    sd = ImageDraw.Draw(sh)
    sd.ellipse([s(SIZE / 2 - width / 2), s(y - width * 0.075),
                s(SIZE / 2 + width / 2), s(y + width * 0.075)], fill=SHADOW)
    sh = sh.filter(ImageFilter.GaussianBlur(s(9)))
    canvas.alpha_composite(sh)


# ── toppings ─────────────────────────────────────────────────────────────

def topping_foam_dome(canvas, cx, top_y, width, color=(250, 242, 229)):
    d = ImageDraw.Draw(canvas)
    eh = _ellipse_h(width)
    d.ellipse([s(cx - width / 2), s(top_y - width * 0.20),
               s(cx + width / 2), s(top_y + eh / 2)], fill=color)
    d.ellipse([s(cx - width * 0.34), s(top_y - width * 0.17),
               s(cx + width * 0.06), s(top_y - width * 0.02)],
              fill=shade(color, 1.03))


def topping_cocoa(canvas, cx, top_y, width):
    d = ImageDraw.Draw(canvas)
    dots = [(-0.22, -0.02), (-0.05, -0.10), (0.14, -0.04), (0.02, 0.03),
            (-0.15, 0.05), (0.24, -0.11), (-0.30, -0.09), (0.08, -0.15)]
    for i, (dx, dy) in enumerate(dots):
        r = width * (0.020 if i % 2 else 0.014)
        x, y = cx + width * dx, top_y + width * dy
        d.ellipse([s(x - r), s(y - r), s(x + r), s(y + r)], fill=(120, 72, 42))


def topping_whip(canvas, cx, top_y, width):
    """A mound of cream seated on the rim, widest where it meets the glass."""
    d = ImageDraw.Draw(canvas)
    cream = (255, 250, 242)
    for i, (dx, dy, r) in enumerate([
        (-0.30, 0.02, 0.26), (0.30, 0.02, 0.26), (0.00, 0.04, 0.32),
        (-0.16, -0.16, 0.24), (0.17, -0.17, 0.23), (0.00, -0.22, 0.25),
        (-0.07, -0.38, 0.17), (0.08, -0.40, 0.15), (0.00, -0.52, 0.11),
    ]):
        x, y = cx + width * dx, top_y + width * dy
        rr = width * r
        d.ellipse([s(x - rr), s(y - rr), s(x + rr), s(y + rr)],
                  fill=cream if i % 2 == 0 else shade(cream, 0.95))
    # A single drizzle arc across the shoulders of the mound. A second, higher
    # arc collapses into a blob at the peak once downsampled.
    d.arc([s(cx - width * 0.34), s(top_y - width * 0.58),
           s(cx + width * 0.34), s(top_y - width * 0.04)],
          198, 342, fill=(92, 51, 28), width=s(6))


def topping_latte_art(canvas, cx, top_y, width, mask):
    """A rosetta-ish heart, clipped to the crema surface."""
    art = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ad = ImageDraw.Draw(art)
    milk = (250, 244, 234)
    r = width * 0.13
    ad.ellipse([s(cx - r * 1.5), s(top_y - r * 0.85),
                s(cx - r * 0.1), s(top_y + r * 0.35)], fill=milk)
    ad.ellipse([s(cx + r * 0.1), s(top_y - r * 0.85),
                s(cx + r * 1.5), s(top_y + r * 0.35)], fill=milk)
    ad.polygon([(s(cx - r * 1.42), s(top_y + r * 0.02)),
                (s(cx + r * 1.42), s(top_y + r * 0.02)),
                (s(cx), s(top_y + r * 1.15))], fill=milk)
    art.putalpha(ImageChops.multiply(art.getchannel("A"), mask))
    canvas.alpha_composite(art)


def topping_ice(canvas, cx, top_y, width, mask):
    """Chunky rotated cubes — small squares just read as noise at kiosk size."""
    ice = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    cubes = [(-0.20, 0.13, 0.40, 14), (0.19, 0.09, 0.42, -20),
             (-0.02, 0.40, 0.38, 28), (0.16, 0.62, 0.34, -8)]
    for dx, dy, sz, rot in cubes:
        h = s(width * sz)
        cube = Image.new("RGBA", (h * 2, h * 2), (0, 0, 0, 0))
        cd = ImageDraw.Draw(cube)
        # Ice sits over near-black liquid, so it needs to be close to opaque
        # or it just muddies down to grey blobs.
        cd.rounded_rectangle([h // 2, h // 2, h + h // 2, h + h // 2],
                             radius=s(9), fill=(224, 240, 249, 232),
                             outline=(255, 255, 255, 255), width=s(4))
        cd.line([h // 2 + s(10), h + h // 2 - s(10),
                 h // 2 + s(10), h // 2 + s(10)],
                fill=(255, 255, 255, 200), width=s(4))
        cube = cube.rotate(rot, resample=Image.BICUBIC)
        x = s(cx + width * dx) - h
        y = s(top_y + width * dy) - h
        ice.alpha_composite(cube, (x, y))
    ice.putalpha(ImageChops.multiply(ice.getchannel("A"), mask))
    canvas.alpha_composite(ice)


def topping_straw(canvas, cx, top_y, width):
    """Drawn last so it reads as passing in front of the liquid, not under it."""
    d = ImageDraw.Draw(canvas)
    tip = (cx - width * 0.34, top_y - width * 0.78)
    dip = (cx + width * 0.20, top_y + width * 0.55)
    d.line([(s(tip[0]), s(tip[1])), (s(dip[0]), s(dip[1]))],
           fill=(150, 100, 216), width=s(16))
    d.line([(s(tip[0]), s(tip[1])), (s(dip[0]), s(dip[1]))],
           fill=(187, 134, 252), width=s(11))
    d.line([(s(tip[0] - 1), s(tip[1])), (s(dip[0] - 1), s(dip[1]))],
           fill=(214, 178, 253), width=s(4))
    r = s(8)
    d.ellipse([s(tip[0]) - r, s(tip[1]) - r // 2, s(tip[0]) + r, s(tip[1]) + r // 2],
              fill=(205, 165, 253))


def topping_tea_tag(canvas, cx, top_y, width, bot_y):
    """String draped over the near rim with the paper tag hanging down outside."""
    d = ImageDraw.Draw(canvas)
    rim_x = cx - width * 0.44
    d.line([(s(cx - width * 0.10), s(top_y + 4)), (s(rim_x), s(top_y - 4))],
           fill=(226, 217, 201), width=s(4))
    tx, ty = rim_x - width * 0.06, top_y + width * 0.34
    d.line([(s(rim_x), s(top_y - 4)), (s(tx + width * 0.07), s(ty - width * 0.12))],
           fill=(226, 217, 201), width=s(4))
    d.rounded_rectangle([s(tx - width * 0.10), s(ty - width * 0.12),
                         s(tx + width * 0.14), s(ty + width * 0.06)],
                        radius=s(6), fill=(240, 233, 219),
                        outline=(214, 203, 184), width=s(3))
    d.line([(s(tx - width * 0.04), s(ty - width * 0.04)),
            (s(tx + width * 0.08), s(ty - width * 0.04))],
           fill=(190, 176, 154), width=s(3))


# ── the menu ─────────────────────────────────────────────────────────────
# Each entry describes a vessel and what is in it. Keeping this declarative
# is what keeps the nine drinks visually consistent with each other.

DRINKS = {
    "espresso": dict(
        vessel="demitasse", saucer=True,
        layers=[(0.0, 0.22, (176, 112, 62), (150, 92, 48)),
                (0.22, 1.0, (74, 43, 28), (46, 26, 16))],
    ),
    "americano": dict(
        vessel="cup", saucer=True,
        layers=[(0.0, 0.10, (150, 96, 55), (120, 74, 42)),
                (0.10, 1.0, (63, 37, 24), (38, 22, 14))],
    ),
    "latte": dict(
        vessel="glass",
        layers=[(0.0, 0.20, (252, 246, 236), (243, 232, 215)),
                (0.20, 0.58, (128, 82, 51), (104, 65, 40)),
                (0.58, 1.0, (238, 222, 200), (222, 202, 176))],
    ),
    "cappuccino": dict(
        vessel="cup", saucer=True, foam=True, cocoa=True,
        layers=[(0.0, 0.30, (250, 242, 229), (238, 227, 209)),
                (0.30, 1.0, (104, 63, 38), (66, 39, 24))],
    ),
    "flatwhite": dict(
        vessel="cup_wide", saucer=True, art=True,
        layers=[(0.0, 0.16, (196, 148, 104), (172, 124, 84)),
                (0.16, 1.0, (110, 68, 41), (72, 44, 27))],
    ),
    # Milk-chocolate tones, deliberately warmer than cold brew so the two
    # dark drinks stay tellable apart in the grid.
    "mocha": dict(
        vessel="glass", whip=True,
        layers=[(0.0, 0.30, (146, 94, 60), (124, 78, 49)),
                (0.30, 0.66, (104, 62, 38), (86, 50, 31)),
                (0.66, 1.0, (74, 42, 26), (58, 32, 20))],
    ),
    # Espresso-forward: a macchiato is milk *marked* with coffee, so the
    # coffee band has to dominate or it just reads as a glass of milk.
    "macchiato": dict(
        vessel="glass_short",
        layers=[(0.0, 0.18, (248, 240, 228), (236, 225, 208)),
                (0.18, 0.62, (112, 70, 44), (88, 54, 34)),
                (0.62, 1.0, (240, 228, 210), (224, 209, 187))],
    ),
    "matcha": dict(
        vessel="glass",
        layers=[(0.0, 0.16, (226, 238, 202), (206, 224, 174)),
                (0.16, 0.74, (138, 182, 76), (104, 148, 54)),
                (0.74, 1.0, (244, 238, 224), (228, 218, 199))],
    ),
    "blacktea": dict(
        vessel="cup", saucer=True, tea_tag=True, glassy=True, body=GLASS,
        layers=[(0.0, 0.18, (206, 130, 58), (186, 112, 44)),
                (0.18, 1.0, (162, 92, 34), (122, 64, 22))],
    ),
    "coldbrew": dict(
        vessel="glass", ice=True, straw=True,
        layers=[(0.0, 0.14, (96, 60, 38), (74, 45, 28)),
                (0.14, 1.0, (52, 31, 20), (32, 18, 11))],
    ),
}

# vessel presets: (top_w, bot_w, top_y, bot_y, wall, handle)
VESSELS = {
    "demitasse":   (150, 108, 214, 320, 9, True),
    "cup":         (196, 140, 190, 328, 10, True),
    "cup_wide":    (224, 152, 196, 318, 10, True),
    "glass":       (168, 138, 132, 400, 8, False),
    "glass_short": (160, 132, 190, 372, 8, False),
}


def render(name, spec):
    canvas = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    top_w, bot_w, top_y, bot_y, wall, handle = VESSELS[spec["vessel"]]
    is_glass = spec["vessel"].startswith("glass")
    body = spec.get("body", GLASS if is_glass else CERAMIC)
    glassy = spec.get("glassy", is_glass)

    draw_ground_shadow(canvas, bot_y + 22, bot_w * 1.9)
    if spec.get("saucer"):
        draw_saucer(canvas, bot_y + 14, top_w * 1.55)

    mask, liq_top, liq_bot = draw_cup(
        canvas, top_w, bot_w, top_y, bot_y, wall=wall,
        handle=handle and not is_glass, body=body, glassy=glassy,
    )

    inner_w = top_w - wall * 2
    fill_liquid(canvas, mask, spec["layers"], liq_top, liq_bot, inner_w)

    cx = SIZE / 2
    if spec.get("art"):
        topping_latte_art(canvas, cx, liq_top, inner_w, mask)
    if spec.get("ice"):
        topping_ice(canvas, cx, liq_top, inner_w, mask)
    if spec.get("foam"):
        topping_foam_dome(canvas, cx, liq_top, inner_w)
    if spec.get("cocoa"):
        topping_cocoa(canvas, cx, liq_top - inner_w * 0.06, inner_w)
    if spec.get("whip"):
        topping_whip(canvas, cx, liq_top, inner_w)
    if spec.get("straw"):
        topping_straw(canvas, cx, liq_top, inner_w)
    if spec.get("tea_tag"):
        topping_tea_tag(canvas, cx, liq_top, inner_w, bot_y)

    out = canvas.resize((SIZE, SIZE), Image.LANCZOS)
    path = os.path.join(OUT_DIR, f"{name}.png")
    out.save(path)
    return path


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    for name, spec in DRINKS.items():
        print("wrote", render(name, spec))


if __name__ == "__main__":
    main()
