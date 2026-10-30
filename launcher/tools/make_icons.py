#!/usr/bin/env python3
"""Generates the Adwaita-style app icons (res/drawable/pm_*.xml).

Each icon is a list of shapes on GNOME's 128px icon grid: a flat front face with a darker
"slab" edge underneath, like the app icons on postmarketOS (Phosh). The same shapes are also
written as SVG (with --svg DIR) to preview them in a browser.

Run from the launcher/ folder: python3 tools/make_icons.py
"""
import math
import os
import sys

OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "drawable")


def f(n):
    return ("%.2f" % n).rstrip("0").rstrip(".")


def rr(x, y, w, h, r):
    return (f"M{f(x + r)},{f(y)} H{f(x + w - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w)},{f(y + r)} "
            f"V{f(y + h - r)} A{f(r)},{f(r)} 0 0 1 {f(x + w - r)},{f(y + h)} H{f(x + r)} "
            f"A{f(r)},{f(r)} 0 0 1 {f(x)},{f(y + h - r)} V{f(y + r)} A{f(r)},{f(r)} 0 0 1 {f(x + r)},{f(y)} Z")


def rect(x, y, w, h):
    return poly((x, y), (x + w, y), (x + w, y + h), (x, y + h))


def ellipse(cx, cy, rx, ry):
    return (f"M{f(cx - rx)},{f(cy)} A{f(rx)},{f(ry)} 0 1 0 {f(cx + rx)},{f(cy)} "
            f"A{f(rx)},{f(ry)} 0 1 0 {f(cx - rx)},{f(cy)} Z")


def circle(cx, cy, r):
    return ellipse(cx, cy, r, r)


def poly(*pts):
    return "M" + " L".join(f"{f(x)},{f(y)}" for x, y in pts) + " Z"


def fill(d, color):
    return {"d": d, "fill": color}


def stroke(d, color, width):
    return {"d": d, "stroke": color, "width": width}


def slab(face, side, x=8, y=8, w=112, h=100, r=16, depth=8):
    return [fill(rr(x, y + depth, w, h, r), side), fill(rr(x, y, w, h, r), face)]


def disc(face, side, cx=64, cy=60, r=52, depth=6):
    return [fill(circle(cx, cy + depth, r), side), fill(circle(cx, cy, r), face)]


def gear(cx, cy, outer, inner, teeth=8):
    pts = []
    step = 360 / teeth
    for k in range(teeth):
        a = k * step
        for da, r in ((-step * 0.3, inner), (-step * 0.17, outer), (step * 0.17, outer), (step * 0.3, inner)):
            t = math.radians(a + da)
            pts.append((cx + r * math.cos(t), cy + r * math.sin(t)))
    return poly(*pts)


# Material "call" glyph (Apache 2.0), on a 24px grid.
HANDSET = ("M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 "
           "1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 "
           "-17,-17 0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 "
           "0.03,0.74 -0.25,1.02l-2.2,2.2z")

WHITE, LIGHT, LIGHT2, LIGHT3 = "#ffffff", "#f6f5f4", "#deddda", "#c0bfbc"
GREY, GREY2, DARK, DARK2, DARK3 = "#9a9996", "#77767b", "#5e5c64", "#3d3846", "#241f31"
BLUE1, BLUE2, BLUE3, BLUE4, BLUE5 = "#99c1f1", "#62a0ea", "#3584e4", "#1c71d8", "#1a5fb4"
GREEN1, GREEN2, GREEN3, GREEN4 = "#8ff0a4", "#57e389", "#33d17a", "#26a269"
RED, ORANGE, ORANGE_DARK, YELLOW = "#e01b24", "#ff7800", "#c64600", "#f6d32d"

ICONS = {
    "dialer": slab(GREEN3, GREEN4) + [
        {"d": HANDSET, "fill": WHITE, "transform": (3, 26, 21)},
    ],
    "messages": slab(BLUE2, BLUE4) + [
        fill(rr(24, 26, 80, 54, 22), WHITE),
        fill(poly((40, 72), (34, 96), (62, 78)), WHITE),
        fill(circle(46, 53, 6), BLUE3), fill(circle(64, 53, 6), BLUE3), fill(circle(82, 53, 6), BLUE3),
    ],
    "browser": disc(BLUE3, BLUE5) + [
        stroke(circle(64, 60, 36), WHITE, 5),
        stroke(ellipse(64, 60, 15, 36), WHITE, 5),
        stroke("M28,60 H100", WHITE, 5),
        stroke("M35,42 H93", WHITE, 4),
        stroke("M35,78 H93", WHITE, 4),
    ],
    "camera": slab(DARK, DARK2) + [
        fill(rr(86, 18, 18, 10, 3), YELLOW),
        fill(circle(64, 62, 36), LIGHT2),
        fill(circle(64, 62, 29), DARK3),
        fill(circle(64, 62, 17), BLUE4),
        fill(circle(57, 55, 6), BLUE1),
    ],
    "settings": [
        fill(gear(64, 66, 52, 42), GREY2),
        fill(gear(64, 60, 52, 42), LIGHT3),
        fill(circle(64, 60, 30), LIGHT2),
        fill(circle(64, 60, 15), DARK2),
    ],
    "contacts": slab(LIGHT, LIGHT3) + [
        fill(rr(8, 8, 22, 100, 10), "#986a44"),
        fill(rect(18, 8, 12, 100), "#986a44"),
        fill(circle(70, 44, 17), DARK),
        fill("M38,94 C38,72 52,66 70,66 C88,66 102,72 102,94 Z", DARK),
    ],
    "clock": disc(LIGHT, GREY) + [
        stroke(circle(64, 60, 47), DARK2, 6),
    ] + [
        fill(circle(64 + 36 * math.cos(math.radians(a)), 60 + 36 * math.sin(math.radians(a)), 3), DARK2)
        for a in range(0, 360, 30)
    ] + [
        stroke("M64,60 L64,34", DARK3, 7),
        stroke("M64,60 L86,72", DARK3, 5),
        stroke("M64,60 L48,84", RED, 2.5),
        fill(circle(64, 60, 5), RED),
    ],
    "calculator": slab(DARK2, DARK3) + [
        fill(rr(20, 18, 88, 22, 6), LIGHT2),
    ] + [
        fill(rr(20 + c * 23, 48 + r * 19, 19, 14, 4), ORANGE if c == 3 else GREY2)
        for r in range(3) for c in range(4)
    ],
    "calendar": slab(LIGHT, LIGHT3) + [
        fill("M8,24 A16,16 0 0 1 24,8 H104 A16,16 0 0 1 120,24 V36 H8 Z", RED),
        fill(rr(34, 2, 8, 18, 4), DARK2), fill(rr(86, 2, 8, 18, 4), DARK2),
    ] + [
        fill(rr(20 + c * 23, 46 + r * 19, 19, 13, 3), BLUE3 if (c, r) == (2, 1) else LIGHT3)
        for r in range(3) for c in range(4)
    ],
    "photos": slab(LIGHT, LIGHT3) + [
        fill(rr(18, 18, 92, 80, 8), BLUE1),
        fill(circle(86, 40, 10), YELLOW),
        fill("M18,82 L46,54 L68,76 L84,62 L110,86 V90 A8,8 0 0 1 102,98 H26 A8,8 0 0 1 18,90 Z", GREEN3),
    ],
    "files": [
        fill(rr(8, 8, 50, 24, 8), BLUE5),
        fill(rr(8, 16, 112, 96, 12), BLUE5),
        fill(rr(18, 26, 92, 40, 4), LIGHT),
        fill(rr(8, 40, 112, 72, 12), BLUE3),
        fill(rr(8, 36, 112, 70, 12), BLUE2),
    ],
    "maps": [
        fill(poly((10, 30), (46, 18), (46, 112), (10, 124)), "#2ec27e"),
        fill(poly((46, 18), (82, 30), (82, 124), (46, 112)), GREEN3),
        fill(poly((82, 30), (118, 18), (118, 112), (82, 124)), "#2ec27e"),
        fill(poly((10, 22), (46, 10), (46, 104), (10, 116)), GREEN2),
        fill(poly((46, 10), (82, 22), (82, 116), (46, 104)), GREEN1),
        fill(poly((82, 22), (118, 10), (118, 104), (82, 116)), GREEN2),
        stroke("M10,76 C40,64 62,96 118,56", LIGHT, 6),
        fill("M64,20 A20,20 0 0 1 84,40 C84,56 64,78 64,78 C64,78 44,56 44,40 A20,20 0 0 1 64,20 Z", RED),
        fill(circle(64, 40, 8), WHITE),
    ],
    "email": slab(BLUE2, BLUE4) + [
        fill(rr(20, 28, 88, 60, 6), WHITE),
        stroke("M24,33 L64,62 L104,33", BLUE1, 5),
    ],
    "videos": slab(DARK2, DARK3) + [
        fill(rr(x, 15, 8, 8, 2), GREY2) for x in range(18, 112, 16)
    ] + [
        fill(rr(x, 93, 8, 8, 2), GREY2) for x in range(18, 112, 16)
    ] + [
        fill(poly((52, 38), (86, 58), (52, 78)), WHITE),
        stroke(poly((52, 38), (86, 58), (52, 78)), WHITE, 8),
    ],
    "music": disc(ORANGE, ORANGE_DARK) + [
        fill(circle(48, 82, 11), WHITE), fill(circle(84, 74, 11), WHITE),
        fill(rect(53, 36, 6, 46), WHITE), fill(rect(89, 28, 6, 46), WHITE),
        fill(poly((53, 36), (95, 26), (95, 40), (53, 50)), WHITE),
    ],
    "store": [
        stroke("M44,46 V36 A20,20 0 0 1 84,36 V46", DARK2, 7),
        fill(rr(16, 46, 96, 70, 12), LIGHT3),
        fill(rr(16, 38, 96, 70, 12), LIGHT),
        fill(circle(44, 50, 4), DARK2), fill(circle(84, 50, 4), DARK2),
        fill(circle(64, 78, 16), BLUE3),
        fill(poly((58, 70), (74, 78), (58, 86)), WHITE),
    ],
    "notes": [
        fill("M28,14 H86 L106,34 V108 A8,8 0 0 1 98,116 H36 A8,8 0 0 1 28,108 Z", LIGHT3),
        fill("M28,8 H86 L106,28 V102 A8,8 0 0 1 98,110 H36 A8,8 0 0 1 28,102 Z", LIGHT),
        fill(poly((86, 8), (86, 28), (106, 28)), LIGHT2),
    ] + [
        fill(rr(40, y, 54 if y < 88 else 32, 5, 2.5), GREY) for y in range(40, 96, 12)
    ],
    "terminal": slab(DARK2, DARK3) + [
        fill(rr(16, 16, 96, 84, 8), DARK3),
        stroke("M34,42 L50,56 L34,70", WHITE, 6),
        fill(rr(56, 68, 26, 6, 2), WHITE),
    ],
}


def android(shapes):
    out = ['<?xml version="1.0" encoding="utf-8"?>',
           "<!-- Generated by tools/make_icons.py; edit that instead. -->",
           '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
           '    android:width="128dp" android:height="128dp"',
           '    android:viewportWidth="128" android:viewportHeight="128">']
    for s in shapes:
        attrs = [f'android:pathData="{s["d"]}"']
        if "fill" in s:
            attrs.append(f'android:fillColor="{s["fill"]}"')
        else:
            attrs += [f'android:strokeColor="{s["stroke"]}"', f'android:strokeWidth="{f(s["width"])}"',
                      'android:strokeLineCap="round"', 'android:strokeLineJoin="round"']
        path = "<path " + " ".join(attrs) + " />"
        if "transform" in s:
            scale, tx, ty = s["transform"]
            out.append(f'    <group android:scaleX="{scale}" android:scaleY="{scale}" '
                       f'android:translateX="{tx}" android:translateY="{ty}">')
            out.append("        " + path)
            out.append("    </group>")
        else:
            out.append("    " + path)
    out.append("</vector>")
    return "\n".join(out) + "\n"


def svg(shapes):
    out = ['<svg xmlns="http://www.w3.org/2000/svg" width="128" height="128" viewBox="0 0 128 128">']
    for s in shapes:
        if "fill" in s:
            el = f'<path d="{s["d"]}" fill="{s["fill"]}"/>'
        else:
            el = (f'<path d="{s["d"]}" fill="none" stroke="{s["stroke"]}" stroke-width="{s["width"]}" '
                  f'stroke-linecap="round" stroke-linejoin="round"/>')
        if "transform" in s:
            scale, tx, ty = s["transform"]
            el = f'<g transform="translate({tx},{ty}) scale({scale})">{el}</g>'
        out.append(el)
    out.append("</svg>")
    return "\n".join(out)


if __name__ == "__main__":
    for name, shapes in ICONS.items():
        with open(os.path.join(OUT, f"pm_{name}.xml"), "w") as fh:
            fh.write(android(shapes))
    if len(sys.argv) > 2 and sys.argv[1] == "--svg":
        os.makedirs(sys.argv[2], exist_ok=True)
        for name, shapes in ICONS.items():
            with open(os.path.join(sys.argv[2], f"{name}.svg"), "w") as fh:
                fh.write(svg(shapes))
