# Tech Debt & Quality Cadence

This document defines the repeating process for keeping GridMaster's codebase healthy.
It is the operational companion to the policies in `ENGINEERING_PRINCIPLES.md §12`.

---

## Cadence Overview

| Frequency | Activity |
|-----------|----------|
| Every PR | Coverage gate, lint, Gemini review |
| Weekly (or per sprint boundary) | Issue triage, coverage trend review |
| Every 5 merged implementation PRs | Doc staleness review |
| Quarterly | Full tech debt burn-down sprint |

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

### Planned tooling (backlog)
- Add a coverage badge to `README.md` sourced from the main-branch workflow artifact.
- Upload to Codecov for historical trending (when the project has multiple active contributors).

---

## 3. Doc Staleness Review (Every 5 Modules)

**Who**: Claude.
**Trigger**: When the 5th implementation PR of a stage merges.

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

## 5. Tech Debt Burn-Down Sprint (Quarterly)

Every quarter (or at stage boundaries), reserve one sprint exclusively for tech debt:

1. List all open `tech-debt` and `performance` issues sorted by priority.
2. Estimate each in lines-changed.
3. Pull enough to fill ~1 000 lines of non-critical work into the sprint.
4. Open a tracking umbrella issue: `chore: Q<N> tech debt sprint`.
5. Close it when all selected issues are resolved.

**This sprint is not optional.** If implementation pressure pushes it out, it becomes the first item of the next sprint.

---

## 6. GitHub Projects Board Setup

The board lives at `https://github.com/users/siliconimaginations/projects/` (or org-level if the repo moves to an org).

### Columns

| Column | Definition |
|--------|-----------|
| **Backlog** | All triaged issues not yet scheduled |
| **This Sprint** | P1 issues + selected P2s for the current sprint |
| **In Progress** | Issues with an open PR or active branch |
| **In Review** | PR open, CI running or awaiting Gemini/Rick review |
| **Done** | Merged and closed |

### Automation rules (set in Projects → Manage → Workflows)

| Trigger | Action |
|---------|--------|
| Issue opened | Add to **Backlog** |
| PR opened | Move linked issue to **In Review** |
| PR merged | Move linked issue to **Done** and close it |
| Issue closed without PR | Move to **Done** |

### Initial setup (one-time, requires `project` scope on PAT)

The `nagasawa94` PAT currently has `repo` + `workflow` scopes but not `project`.
Until the scope is added, please set up the board manually:

1. Go to `https://github.com/users/siliconimaginations/projects/new`.
2. Select "Board" layout.
3. Name it **GridMaster Backlog**.
4. Add the columns above.
5. Enable the automation rules above.
6. Bulk-add all open issues from `siliconimaginations/gridmaster`.

Once the `project` scope is added to the PAT, Claude can manage the board programmatically.
