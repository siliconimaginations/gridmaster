# E2E Coverage Workflow

**Stage**: 4  
**Status**: Active  
**Implements**: GitHub issues #184, #207  
**Depends on**: [15-e2e-ci.md](15-e2e-ci.md)

---

## Purpose

Define the two-metric E2E coverage system that produces two README badges with distinct and non-redundant semantics. The system must make gaps and regressions immediately visible, and must remain meaningful as new features and tests are added.

---

## The two metrics

### Metric 1 — Feature coverage `e2e-coverage.svg`

> **"How many implemented game features have at least one meaningful E2E test?"**

```
covered_features / total_implemented_features
```

| Term | Definition |
|------|-----------|
| `total_implemented_features` | Features in `catalogue.json` with `feature_implemented: true` |
| `covered_features` | Subset where at least one scenario listed under the feature has `test_implemented: true` |

**Semantics**: A low number means features exist in the game that have no automated acceptance test. The fix is to write new tests. A drop in this number (e.g. 9/12 → 8/12) means a feature lost all its tests — likely deleted by mistake.

**Colour thresholds**: green ≥ 90 %, yellow-green ≥ 75 %, yellow ≥ 50 %, red < 50 %.

---

### Metric 2 — Test pass rate `e2e-pass.svg`

> **"Of all tests we have written, how many pass right now?"**

```
passing_tests / total_implemented_tests
```

| Term | Definition |
|------|-----------|
| `total_implemented_tests` | Scenarios in `catalogue.json` with `test_implemented: true` |
| `passing_tests` | Subset whose title prefix matches a passing spec in the Playwright JSON results |

**Semantics**: Any value < 100 % is a regression — a feature that used to work is now broken. The fix is to fix the product code (or, rarely, update a test that diverged from the spec). A new test that is flaky or wrong should be fixed before merging, not after.

**Colour thresholds**: green = 100 %, yellow ≥ 80 %, red < 80 %.

---

## Why two metrics, not one?

A single `N/M` badge conflates two different failure modes:

| Scenario | Coverage | Pass rate |
|----------|----------|-----------|
| All tests pass but half the features have no test | high | 100 % |
| All features have tests but 3 tests are broken | 100 % | low |
| Ideal state | 100 % | 100 % |

Merging them into one number hides whichever problem is smaller.

---

## Catalogue schema — `frontend/e2e/catalogue.json`

```jsonc
{
  "features": [
    {
      "id": "F-CLOCK",
      "name": "Game clock",
      "feature_implemented": true,   // is the game feature shipped?
      "scenarios": ["GC-01", "GC-02"]
    }
  ],
  "scenarios": [
    {
      "id": "GC-01",
      "description": "Tick counter increments (clock advancing at ≥1×)",
      "test_implemented": true        // is the Playwright spec written?
    }
  ]
}
```

### Rules

1. **Add a feature entry before shipping the feature.** Set `feature_implemented: false` initially; flip to `true` when the feature merges. This keeps the coverage denominator honest.
2. **Add a scenario entry before writing the test.** Set `test_implemented: false` initially; flip to `true` when the spec is written and passing.
3. **Scenario IDs must be unique and stable.** The badge script matches spec titles by prefix (`"GC-01 tick counter…"` → `GC-01`). Renaming a scenario ID breaks the match silently — update the spec file title at the same time.
4. **Do not remove entries.** Instead mark `test_implemented: false` if a test is deleted, or `feature_implemented: false` if a feature is removed. This keeps history readable and prevents artificial score inflation.

---

## Badge generation — `scripts/gen_e2e_badges.py`

Single script, two outputs:

```
python3 scripts/gen_e2e_badges.py [results_json]
```

Inputs:
- `frontend/e2e/catalogue.json` — source of truth for features and scenarios
- `frontend/playwright-report/results.json` — Playwright JSON reporter output

Outputs:
- `.github/badges/e2e-coverage.svg`
- `.github/badges/e2e-pass.svg`

The script exits 0 whether or not tests pass — badge generation must never block a merge.

---

## CI workflow — `e2e.yml`

```
push to main
    │
    ▼
┌───────────────────────────┐
│  e2e (Playwright E2E)     │  runs on ubuntu-22.04
│  • Build backend + frontend│
│  • Run all Playwright specs│
│  • Upload playwright-report│  (includes results.json)
│  • Upload test-results     │  (on failure only)
└─────────────┬─────────────┘
              │ needs: e2e
              │ if: push to main   (skipped on PR)
              ▼
┌───────────────────────────┐
│  e2e-badge                │  isolated job, contents: write
│  • Download playwright-    │
│    report artifact         │
│  • python3 gen_e2e_badges  │
│  • Push both SVGs to       │  → ci/badges branch (not main)
│    ci/badges branch        │
└───────────────────────────┘
```

**Why the badge job is isolated**: badge commit needs `contents: write` permission. Keeping it separate from the test job means the test job can run with minimal `contents: read`, matching the security posture of `ci.yml`'s `frontend-badge` / `backend-badge` pattern.

**Why `ci/badges` branch**: the `github-actions` bot cannot push to a branch-protected `main` (GH006). Badge SVGs are committed to the unprotected `ci/badges` branch instead. README badge URLs reference raw content from that branch.

---

## README badges

```md
![E2E Coverage](https://raw.githubusercontent.com/siliconimaginations/gridmaster/ci/badges/.github/badges/e2e-coverage.svg)
![E2E Pass Rate](https://raw.githubusercontent.com/siliconimaginations/gridmaster/ci/badges/.github/badges/e2e-pass.svg)
```

Badges are committed SVGs stored on the `ci/badges` branch so they render without external badge services and without pushing to the branch-protected `main`. CI rewrites them on every push to main.

---

## Lifecycle — adding a new feature

1. **Product**: new feature merges. Add a `features` entry in `catalogue.json` with `feature_implemented: true` and an empty `scenarios: []`. Coverage badge drops (denominator grows, numerator unchanged).
2. **Test**: write Playwright spec. Add scenario IDs to `catalogue.json` with `test_implemented: true`; add those IDs to the feature's `scenarios` array. Coverage badge recovers on first green CI run.
3. **Done**: both badges green.

## Lifecycle — fixing a regression

1. `e2e-pass.svg` turns yellow or red on push to main.
2. Check Playwright report artifact for which specs failed.
3. Fix product code (or test if spec was wrong). Push fix.
4. Badge returns to green automatically.

---

## Scenario ID conventions

| Prefix | Domain |
|--------|--------|
| `SL-`  | Session lifecycle |
| `GC-`  | Game clock |
| `CM-`  | Command / network mutation |
| `HUD-` | HUD display |
| `DP-`  | Dispatch panel |
| `PL-`  | Planning panel |
| `PH-`  | Physics REST API |
| `TL-`  | Timeline strip |
| `IP-`  | Inspector panel |
| `AL-`  | Alert toast |
| `EV-`  | Event card panel |

New feature domains get a new two-letter prefix. Add it to this table when the first scenario is created.
