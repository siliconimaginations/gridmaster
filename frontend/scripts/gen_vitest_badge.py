#!/usr/bin/env python3
"""
Generate a flat SVG coverage badge from Vitest coverage-summary.json.
Writes ../.github/badges/vitest.svg.

Run from the frontend/ directory:
    python3 scripts/gen_vitest_badge.py
"""
import json, os, sys

# Paths are relative to frontend/ (the working directory when CI runs this script)
SUMMARY = "coverage/coverage-summary.json"
OUTPUT  = "../.github/badges/vitest.svg"

if not os.path.exists(SUMMARY):
    print(f"No coverage summary at {SUMMARY} — skipping badge generation.")
    sys.exit(0)

with open(SUMMARY) as f:
    data = json.load(f)

pct     = data.get("total", {}).get("lines", {}).get("pct", 0)
pct_str = f"{pct:.1f}%"
colour  = "#4c1" if pct >= 80 else "#97ca00" if pct >= 60 else "#dfb317" if pct >= 40 else "#e05d44"

label   = "frontend coverage"
lw, vw  = 124, 52          # label width, value width (px)
tw      = lw + vw           # total width
lx      = lw * 10 // 2     # label text centre x (tenths of px for scale(.1))
vx      = (lw + vw // 2) * 10

svg = (
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{tw}" height="20"'
    f' role="img" aria-label="{label}: {pct_str}">\n'
    f'  <title>{label}: {pct_str}</title>\n'
    f'  <linearGradient id="s" x2="0" y2="100%">'
    f'<stop offset="0" stop-color="#bbb" stop-opacity=".1"/>'
    f'<stop offset="1" stop-opacity=".1"/></linearGradient>\n'
    f'  <clipPath id="r"><rect width="{tw}" height="20" rx="3" fill="#fff"/></clipPath>\n'
    f'  <g clip-path="url(#r)">\n'
    f'    <rect width="{lw}" height="20" fill="#555"/>\n'
    f'    <rect x="{lw}" width="{vw}" height="20" fill="{colour}"/>\n'
    f'    <rect width="{tw}" height="20" fill="url(#s)"/>\n'
    f'  </g>\n'
    f'  <g fill="#fff" text-anchor="middle"'
    f'   font-family="DejaVu Sans,Verdana,Geneva,sans-serif" font-size="110">\n'
    f'    <text x="{lx}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)"'
    f'     textLength="{(lw-10)*10}" lengthAdjust="spacing">{label}</text>\n'
    f'    <text x="{lx}" y="140" transform="scale(.1)"'
    f'     textLength="{(lw-10)*10}" lengthAdjust="spacing">{label}</text>\n'
    f'    <text x="{vx}" y="150" fill="#010101" fill-opacity=".3" transform="scale(.1)"'
    f'     textLength="{(vw-10)*10}" lengthAdjust="spacing">{pct_str}</text>\n'
    f'    <text x="{vx}" y="140" transform="scale(.1)"'
    f'     textLength="{(vw-10)*10}" lengthAdjust="spacing">{pct_str}</text>\n'
    f'  </g>\n'
    f'</svg>\n'
)

os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
with open(OUTPUT, "w") as f:
    f.write(svg)
print(f"Badge written to {OUTPUT}: {pct_str} (colour {colour})")
