#!/usr/bin/env python3
"""
Generate a flat SVG badge showing E2E scenario coverage.

Metric: N/M  where
  M = total scenarios listed in frontend/e2e/catalogue.json
  N = catalogue scenarios whose spec title appears as a *passing* test
      in the Playwright JSON results file

This gives an honest coverage fraction even when some catalogue entries
have no test yet (they simply don't appear in results → not counted).
Running all existing tests green but missing 3 catalogue entries shows
17/20, not 17/17.

Writes .github/badges/e2e.svg.

Usage:
  python3 scripts/gen_e2e_badge.py [path/to/results.json]

The default path matches the outputFile set in playwright.config.ts.
"""
import json, os, sys

RESULTS   = sys.argv[1] if len(sys.argv) > 1 else "frontend/playwright-report/results.json"
CATALOGUE = "frontend/e2e/catalogue.json"
OUTPUT    = ".github/badges/e2e.svg"


def collect_specs(suites: list) -> list[dict]:
    """Recursively collect all leaf specs from the nested Playwright suite tree."""
    specs = []
    for suite in suites:
        specs.extend(suite.get("specs", []))
        specs.extend(collect_specs(suite.get("suites", [])))
    return specs


def spec_passed(spec: dict) -> bool:
    """A spec passes if every test within it has at least one 'passed' result."""
    for test in spec.get("tests", []):
        if not any(r.get("status") == "passed" for r in test.get("results", [])):
            return False
    return True


# ── Load catalogue ────────────────────────────────────────────────────────────

if not os.path.exists(CATALOGUE):
    print(f"No catalogue at {CATALOGUE} — skipping badge generation.")
    sys.exit(0)

with open(CATALOGUE) as f:
    cat = json.load(f)

all_scenarios = cat.get("scenarios", [])
total = len(all_scenarios)
catalogue_ids = {s["id"] for s in all_scenarios}

if total == 0:
    print("Empty catalogue — skipping badge generation.")
    sys.exit(0)

# ── Load Playwright results ───────────────────────────────────────────────────

passing_ids: set[str] = set()

if os.path.exists(RESULTS):
    with open(RESULTS) as f:
        data = json.load(f)
    for spec in collect_specs(data.get("suites", [])):
        title = spec.get("title", "")
        # Spec titles use the scenario ID as a prefix, e.g. "SL-01 app loads …"
        scenario_id = title.split()[0] if title else ""
        if scenario_id in catalogue_ids and spec_passed(spec):
            passing_ids.add(scenario_id)
else:
    print(f"No results file at {RESULTS} — coverage will show 0/{total}.")

covered = len(passing_ids)
value   = f"{covered}/{total}"
pct     = covered / total

colour = "#4c1" if pct == 1.0 else "#97ca00" if pct >= 0.8 else "#dfb317" if pct >= 0.5 else "#e05d44"
label  = "e2e coverage"

# ── Build SVG (shields.io flat style) ────────────────────────────────────────

lw = 98    # label panel width (px)
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

print(f"Badge written to {OUTPUT}: {value} ({pct*100:.0f}%) colour={colour}")
print(f"  Passing: {sorted(passing_ids)}")
print(f"  Missing: {sorted(catalogue_ids - passing_ids)}")
