# Tech Debt & Quality Cadence

This document defines the repeating process for keeping GridMaster's codebase healthy.
It is the operational companion to the policies in `ENGINEERING_PRINCIPLES.md §12`.

---

## Cadence Overview

| Frequency | Activity |
|-----------|----------|
| Every PR | Coverage gate, lint, Gemini review, project management (create issues, update board) |
| After every PR merges | Pull next work item from the board |
| Weekly (or per sprint boundary) | Issue triage, coverage trend review |
| Every 5 merged implementation PRs | Doc staleness review |
| Quarterly | Full tech debt burn-down sprint |

---

## 0. Per-PR Project Management

Every PR is an opportunity to keep the backlog accurate. Claude performs these steps as part of the autonomous review loop:

**During a PR:**
1. If Gemini or review comments surface new problems not worth fixing now, open a GitHub issue immediately (don't just leave a mental note).
2. Label each new issue (`tech-debt`, `performance`, `coverage`, or `docs`) and set a priority.
3. Add issues to the **Backlog** column on the GitHub Projects board.
4. Promote any issue that is a safety/data-integrity risk (e.g. race conditions, data loss) to `priority/P1` and move it to **This Sprint**.

**After a PR merges:**
1. Query the GitHub Projects board for the top `priority/P1` item in **This Sprint**; if none, take the top `priority/P2`.
2. Announce the next planned task to Rick in the merge summary message.
3. If the board is empty, run the weekly triage (§1 below) before proceeding.

This keeps the work queue self-refreshing — Rick does not need to manually assign the next task unless he wants to override the board order.

---

## 1. Issue Triage (Weekly)

**Who**: Claude, confirmed by siliconimaginations.
**When**: Start of each sprint / Monday.

Steps:
1. List all open `tech-debt`, `performance`, `coverage`, and `docs` issues.
2. Re-evaluate priorities (`P1`/`P2`/`P3`) against current stage.
3. Pull all `P1` issues and any `P2` issues ≤ 200 lines into **"This Sprint"** on the GitHub Projects board.
4. Close any issues already resolved by merged PRs (verify via `git log --oneline`).
5. Add `// TODO: #<issue>` comments near affected code if none exist.

**Trigger for coverage task**: If overall backend coverage drops below 60% or frontend below 60% on main, immediately create a `coverage`-labelled issue and promote it to `P1`.

---

## 2. Coverage Monitoring

Coverage is reported two ways:

### On Pull Requests
- JaCoCo posts a diff comment (backend) and Vitest posts a diff comment (frontend).
- The PR is blocked if changed-file coverage falls below 70%.

### On Push to Main
- The `coverage-main.yml` workflow runs `./gradlew test jacocoTestReport` and uploads the HTML report as a GitHub Actions artifact (`backend-coverage-report`).
- The job summary prints overall line coverage so it is visible in the Actions tab without downloading the artifact.
- **No build failure on main** — the goal is visibility, not a gate (the PR gate already enforced the threshold).

### Reviewing Coverage Trend
1. Go to **Actions → Coverage (main branch)** in the GitHub repo.
2. Compare the last 4–5 runs for `Overall Project` percentage.
3. If it drops >3 pp between runs, investigate which merged PR caused it and open a `coverage / P1` issue.

### Immediate next actions (prioritised)

Coverage monitoring is a **priority item**. The following are scheduled for the next available sprint slot, in order:

1. **Coverage badge in README** — source from the main-branch CI artifact. Gives instant visibility without opening the Actions tab.
2. **Codecov integration** — upload JaCoCo and Vitest reports to Codecov for historical trending and PR annotations. Free for public/private repos up to a team size.
3. **Coverage threshold escalation** — raise overall minimum from 60% → 70% once the game engine core (Modules 07–10) is merged.

Tracking issue: open a `coverage / P2` issue for items 1–2 if none exists.

---

## 3. Doc Staleness Review (weekly)

**Who**: Claude.
**Trigger**: sprint planning.

Steps:
1. For each file in `docs/engineering/` and `docs/ux/`:
   - Find the last commit that touched it: `git log -1 --format="%ar" -- <file>`
   - Find the last commit that touched the corresponding implementation file.
   - If the doc is older than the implementation by more than 1 month, it is stale.
2. For each stale doc:
   - Open a `docs / P2` GitHub issue titled `docs: update <filename> — stale since <date>`.
   - Add the issue to the sprint board.
3. Review `WORK_PLAN.md` and `ENGINEERING_PRINCIPLES.md` for any references to completed or changed items.

**Minimum doc review per module**: The design doc must be updated in the same PR if implementation materially diverges from the design. This is already enforced by `ENGINEERING_PRINCIPLES.md §1`.

---

## 4. CI Health

See `ENGINEERING_PRINCIPLES.md §7` for the definition of "CI green."

**Target job durations** (wall-clock from push to all-green):

| Job | Target | Current |
|-----|--------|---------|
| `backend-lint` (ktlint) | < 45 s | ~15 s |
| `backend-test` (unit) | < 3 min | ~2.5 min |
| `frontend-lint` (ESLint) | < 30 s | ~20 s |
| `frontend-test` (vitest) | < 60 s | ~30 s |
| `gemini-review` | < 90 s | ~60 s |
| **Total (parallel)** | **< 3.5 min** | ~4 min |

If any job consistently exceeds its target:
1. Open a `ci / P2` issue.
2. Investigate: Gradle/npm caching miss, new heavy dependency, integration test leaking into unit run.

**Caching rules** (already in `ci.yml`):
- `actions/setup-java` with `cache: gradle` caches the Gradle wrapper and dependency jars.
- `actions/setup-node` with `cache: npm` caches `node_modules`.
- Gradle build cache is enabled via `org.gradle.caching=true` in `gradle.properties`.

---

## 5. Tech Debt Burn-Down (check weekly, execute as needed)

If there are too many tech debt issues:

1. List all open `tech-debt` and `performance` issues sorted by priority.
2. Estimate each in lines-changed.
3. Pull enough to fill ~1 000 lines of non-critical work into the sprint.
4. Open a tracking umbrella issue: `chore: Q<N> tech debt sprint`.
5. Close it when all selected issues are resolved.

---

## 6. GitHub Projects Board Setup

The board lives at `https://github.com/users/siliconimaginations/projects/` (or org-level if the repo moves to an org).

### Fields

The board uses two single-select fields on each item, not separate named columns:

| Field | Options |
|-------|---------|
| **Status** | Todo / In Progress / Done |
| **Priority** | P1 — This Sprint / P2 — Next Sprint / P3 — Backlog |

Sprint membership is controlled by the **Priority** field. The board is viewed as a standard table grouped by Priority.

### Board management

Board mutations require the `project` OAuth scope. The `nagasawa94` PAT has `repo` + `workflow` only; all board mutations use Rick's PAT (`siliconimaginations`) via the GitHub Projects v2 GraphQL API.

Key GraphQL IDs (project `PVT_kwHOEN2YFM4BZ3uF`):
- Status field: `PVTSSF_lAHOEN2YFM4BZ3uFzhUzY48` (Todo option: `f75ad846`)
- Priority field: `PVTSSF_lAHOEN2YFM4BZ3uFzhUzb7k` (P1: `68d86744`, P2: `58963618`, P3: `16ff7f25`)
