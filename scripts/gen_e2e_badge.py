#!/usr/bin/env python3
"""
Generate a flat SVG badge from Playwright JSON results.

Metric: N/M e2e scenarios passed
  N = number of specs whose final test result is 'passed' (across all retries)
  M = total number of specs in the suite

Writes .github/badges/e2e.svg.

Usage:
  python3 scripts/gen_e2e_badge.py [path/to/results.json]

The default path matches Playwright's --reporter=json default output location
when outputFile is set to frontend/playwright-report/results.json.
"""
import json, os, sys

RESULTS = sys.argv[1] if len(sys.argv) > 1 else "frontend/playwright-report/results.json"
OUTPUT  = ".github/badges/e2e.svg"


def collect_specs(suites: list) -> list[dict]:
    """Recursively collect all leaf specs from the nested suite tree."""
    specs = []
    for suite in suites:
        specs.extend(suite.get("specs", []))
        specs.extend(collect_specs(suite.get("suites", [])))
    return specs


def spec_passed(spec: dict) -> bool:
    """A spec passes if every test in it has at least one 'passed' result."""
    for test in spec.get("tests", []):
        results = test.get("results", [])
        if not any(r.get("status") == "passed" for r in results):
            return False
    return True


if not os.path.exists(RESULTS):
    print(f"No results file at {RESULTS} — skipping badge generation.")
    sys.exit(0)

with open(RESULTS) as f:
    data = json.load(f)

specs = collect_specs(data.get("suites", []))
total  = len(specs)
passed = sum(1 for s in specs if spec_passed(s))

if total == 0:
    print("No specs found — skipping badge generation.")
    sys.exit(0)

value   = f"{passed}/{total}"
all_ok  = passed == total
colour  = "#4c1" if all_ok else "#e05d44" if passed == 0 else "#dfb317"
label   = "e2e scenarios"

# Badge geometry (shields.io flat style)
lw = 108   # label panel width (px)
vw = 40    # value panel width (px)
tw = lw + vw
lx = lw * 10 // 2
vx = (lw + vw // 2) * 10

svg = (
    f'<svg xmlns="http://www.w3.org/2000/svg" width="{tw}" height="20"'
    f' role="img" aria-label="{label}: {value}">\n'
    f'  <title>{label}: {value}</title>\n'
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
    f'     textLength="{(vw-10)*10}" lengthAdjust="spacing">{value}</text>\n'
    f'    <text x="{vx}" y="140" transform="scale(.1)"'
    f'     textLength="{(vw-10)*10}" lengthAdjust="spacing">{value}</text>\n'
    f'  </g>\n'
    f'</svg>\n'
)

os.makedirs(os.path.dirname(OUTPUT), exist_ok=True)
with open(OUTPUT, "w") as f:
    f.write(svg)

status = "✅" if all_ok else "❌"
print(f"Badge written to {OUTPUT}: {value} {status} (colour {colour})")
