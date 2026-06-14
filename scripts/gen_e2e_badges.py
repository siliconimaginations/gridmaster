#!/usr/bin/env python3
"""
Generate two E2E coverage SVG badges from catalogue + Playwright JSON results.

Badge 1 — e2e-coverage.svg   "feature coverage"
  Numerator:   features (in catalogue) that have ≥1 scenario with test_implemented=true
  Denominator: features (in catalogue) with feature_implemented=true
  Meaning: are we testing all the implemented game features?

Badge 2 — e2e-pass.svg   "test pass rate"
  Numerator:   implemented scenarios (test_implemented=true) that PASS in CI results
  Denominator: all scenarios with test_implemented=true
  Meaning: of the tests we have, how many pass right now?

See docs/engineering/16-e2e-coverage-workflow.md for full design.

Usage:
  python3 scripts/gen_e2e_badges.py [path/to/results.json]
"""
import json, os, sys

RESULTS   = sys.argv[1] if len(sys.argv) > 1 else "frontend/playwright-report/results.json"
CATALOGUE = "frontend/e2e/catalogue.json"
OUT_COV   = ".github/badges/e2e-coverage.svg"
OUT_PASS  = ".github/badges/e2e-pass.svg"


# ── Helpers ───────────────────────────────────────────────────────────────────

def collect_specs(suites: list) -> list[dict]:
    specs = []
    for s in suites:
        specs.extend(s.get("specs", []))
        specs.extend(collect_specs(s.get("suites", [])))
    return specs


def spec_passed(spec: dict) -> bool:
    for test in spec.get("tests", []):
        if not any(r.get("status") == "passed" for r in test.get("results", [])):
            return False
    return True


def make_svg(label: str, value: str, colour: str, lw: int, vw: int) -> str:
    tw = lw + vw
    lx = lw * 10 // 2
    vx = (lw + vw // 2) * 10
    return (
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


def write_svg(path: str, svg: str) -> None:
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(svg)


# ── Load catalogue ────────────────────────────────────────────────────────────

if not os.path.exists(CATALOGUE):
    print(f"ERROR: catalogue not found at {CATALOGUE}", file=sys.stderr)
    sys.exit(1)

with open(CATALOGUE) as f:
    cat = json.load(f)

features  = cat.get("features", [])
scenarios = cat.get("scenarios", [])

# Build lookup: scenario id → test_implemented
scenario_info: dict[str, bool] = {s["id"]: s.get("test_implemented", False) for s in scenarios}

# ── Load Playwright results ───────────────────────────────────────────────────

passing_ids: set[str] = set()

if os.path.exists(RESULTS):
    with open(RESULTS) as f:
        data = json.load(f)
    for spec in collect_specs(data.get("suites", [])):
        title       = spec.get("title", "")
        scenario_id = title.split()[0] if title else ""
        if scenario_id in scenario_info and spec_passed(spec):
            passing_ids.add(scenario_id)
else:
    print(f"WARNING: no results file at {RESULTS} — pass badge will show 0.", file=sys.stderr)

# ── Metric 1: feature coverage ────────────────────────────────────────────────

impl_features     = [f for f in features if f.get("feature_implemented", False)]
covered_features  = [
    f for f in impl_features
    if any(scenario_info.get(sid, False) for sid in f.get("scenarios", []))
]

cov_total   = len(impl_features)
cov_covered = len(covered_features)
cov_value   = f"{cov_covered}/{cov_total}"
cov_pct     = cov_covered / cov_total if cov_total else 0
cov_colour  = "#4c1" if cov_pct >= 0.9 else "#97ca00" if cov_pct >= 0.75 else "#dfb317" if cov_pct >= 0.5 else "#e05d44"

# ── Metric 2: test pass rate ──────────────────────────────────────────────────

impl_scenarios  = [s for s in scenarios if s.get("test_implemented", False)]
pass_total      = len(impl_scenarios)
pass_passed     = sum(1 for s in impl_scenarios if s["id"] in passing_ids)
pass_value      = f"{pass_passed}/{pass_total}"
pass_pct        = pass_passed / pass_total if pass_total else 0
pass_colour     = "#4c1" if pass_pct == 1.0 else "#dfb317" if pass_pct >= 0.8 else "#e05d44"

# ── Write badges ──────────────────────────────────────────────────────────────

cov_svg  = make_svg("e2e coverage",  cov_value,  cov_colour,  lw=100, vw=40)
pass_svg = make_svg("e2e pass rate", pass_value, pass_colour, lw=108, vw=40)

write_svg(OUT_COV,  cov_svg)
write_svg(OUT_PASS, pass_svg)

# ── Summary ───────────────────────────────────────────────────────────────────

uncovered = [f["id"] for f in impl_features if f not in covered_features]
failing   = [s["id"] for s in impl_scenarios if s["id"] not in passing_ids]

print(f"e2e-coverage : {cov_value}  ({cov_pct*100:.0f}%)  → {OUT_COV}")
print(f"e2e-pass     : {pass_value}  ({pass_pct*100:.0f}%)  → {OUT_PASS}")
if uncovered:
    print(f"  Uncovered features : {uncovered}")
if failing:
    print(f"  Failing scenarios  : {failing}")
