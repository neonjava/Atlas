#!/usr/bin/env python3
# make_stars.py — draws site/static/stars.svg, the repeating star layer of the
# site background.
#
# Why: the background is drawn for this project, not taken from a game. A
# fixed seed keeps the file identical between runs, so it does not churn in git.
# Done: specks in teal/violet tones plus a few soft glows.
# Not done: nothing; run it again only to change the look.
import pathlib
import random

SEED = 20261122
TILE = 360
SPECKS = 70
GLOWS = 5
GLOW_RADIUS = 6
GLOW_OPACITY = 0.10
COLORS = ["#3fd6c6", "#7af5e3", "#a77bf3", "#c9b3ff", "#5fbf9f", "#e6f7ff"]
RADII = [0.6, 0.8, 1.0, 1.3, 1.8]


def main() -> None:
    r = random.Random(SEED)
    parts = []
    for _ in range(SPECKS):
        x, y = r.uniform(0, TILE), r.uniform(0, TILE)
        parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{r.choice(RADII)}" '
                     f'fill="{r.choice(COLORS)}" opacity="{r.uniform(0.45, 1.0):.2f}"/>')
    for _ in range(GLOWS):
        x, y = r.uniform(0, TILE), r.uniform(0, TILE)
        parts.append(f'<circle cx="{x:.1f}" cy="{y:.1f}" r="{GLOW_RADIUS}" '
                     f'fill="{r.choice(COLORS[:4])}" opacity="{GLOW_OPACITY:.2f}"/>')
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{TILE}" height="{TILE}" viewBox="0 0 {TILE} {TILE}">'
           '<!-- Atlas site star layer, drawn for this project (seeded random). -->'
           + "".join(parts) + "</svg>\n")
    out = pathlib.Path(__file__).resolve().parent / "static" / "stars.svg"
    out.write_text(svg, encoding="utf-8")


if __name__ == "__main__":
    main()
