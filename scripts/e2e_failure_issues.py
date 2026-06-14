#!/usr/bin/env python3
"""
E2E failure issue manager — runs after a failed e2e CI job on main.

For each implemented scenario that failed in results.json:
1. Map it to a feature via catalogue.json.
2. Search for an existing open GitHub issue labelled `e2e-failure` that covers
   the same feature ID.
3. If found  → add a comment with the latest failure context (deduplication).
4. If not found → create a new issue labelled `e2e-failure` + `ci`, then add
   it to the GitHub Projects board (project #2) as P1.

Environment variables (set automatically in GitHub Actions):
  GITHUB_TOKEN        — token with issues:write (GITHUB_TOKEN from Actions)
  PROJECT_TOKEN       — PAT with `project` scope for Projects V2 GraphQL mutations
                        (falls back to GITHUB_TOKEN if not set, but will fail for
                        user-level projects without project scope)
  GITHUB_REPOSITORY   — "owner/repo"
  GITHUB_RUN_NUMBER   — CI run number (e.g. "53")
  GITHUB_RUN_URL      — full URL to the Actions run
  GITHUB_SERVER_URL   — e.g. "https://github.com" (used to build run URL fallback)
  GITHUB_RUN_ID       — used to build run URL if GITHUB_RUN_URL not set
"""

from __future__ import annotations
import json, os, re, sys, urllib.request, urllib.error
from collections import defaultdict

RESULTS   = "frontend/playwright-report/results.json"
CATALOGUE = "frontend/e2e/catalogue.json"

GITHUB_TOKEN  = os.environ.get("GITHUB_TOKEN", "")
PROJECT_TOKEN = os.environ.get("PROJECT_TOKEN") or GITHUB_TOKEN  # needs `project` scope
REPO          = os.environ.get("GITHUB_REPOSITORY", "")          # "owner/repo"
RUN_NUMBER    = os.environ.get("GITHUB_RUN_NUMBER", "?")
RUN_ID        = os.environ.get("GITHUB_RUN_ID", "")
SERVER_URL    = os.environ.get("GITHUB_SERVER_URL", "https://github.com")
RUN_URL       = (
    os.environ.get("GITHUB_RUN_URL")
    or (f"{SERVER_URL}/{REPO}/actions/runs/{RUN_ID}" if RUN_ID else "(unknown)")
)

E2E_FAILURE_LABEL = "e2e-failure"


# ── GitHub helpers ────────────────────────────────────────────────────────────

def gh(method: str, path: str, body: dict | None = None) -> dict | list:
    """REST API call — uses GITHUB_TOKEN (issues:write is enough)."""
    url = f"https://api.github.com{path}"
    data = json.dumps(body).encode() if body else None
    req = urllib.request.Request(
        url, data=data, method=method,
        headers={
            "Authorization": f"token {GITHUB_TOKEN}",
            "Accept": "application/vnd.github.v3+json",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        body_text = e.read().decode()
        print(f"  GitHub API {method} {path} → {e.code}: {body_text[:200]}", file=sys.stderr)
        return {"_error": e.code, "_body": body_text}


def gh_graphql(query: str, variables: dict | None = None) -> dict:
    """GraphQL call — uses PROJECT_TOKEN which must have `project` scope.

    GITHUB_TOKEN cannot access user-level Projects V2; a PAT with `project`
    scope (stored as the PROJECT_TOKEN secret) is required.
    """
    data = json.dumps({"query": query, "variables": variables or {}}).encode()
    req = urllib.request.Request(
        "https://api.github.com/graphql", data=data, method="POST",
        headers={
            "Authorization": f"bearer {PROJECT_TOKEN}",
            "Content-Type": "application/json",
        },
    )
    try:
        with urllib.request.urlopen(req) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return {"errors": [{"message": e.read().decode()}]}


# ── Playwright result helpers ─────────────────────────────────────────────────

def collect_specs(suites: list) -> list[dict]:
    specs: list[dict] = []
    for s in suites:
        specs.extend(s.get("specs", []))
        specs.extend(collect_specs(s.get("suites", [])))
    return specs


def spec_failed(spec: dict) -> bool:
    """True if ANY test attempt in this spec did not pass."""
    for test in spec.get("tests", []):
        if not any(r.get("status") == "passed" for r in test.get("results", [])):
            return True
    return False


# ── Main ──────────────────────────────────────────────────────────────────────

def main() -> None:
    if not GITHUB_TOKEN:
        print("GITHUB_TOKEN not set — skipping issue creation", file=sys.stderr)
        sys.exit(0)

    if not PROJECT_TOKEN:
        print("PROJECT_TOKEN not set — issues will be created but not added to board", file=sys.stderr)

    if not os.path.exists(RESULTS):
        print(f"No results file at {RESULTS} — skipping issue creation")
        sys.exit(0)

    if not os.path.exists(CATALOGUE):
        print(f"No catalogue at {CATALOGUE}", file=sys.stderr)
        sys.exit(1)

    with open(RESULTS) as f:
        results_data = json.load(f)
    with open(CATALOGUE) as f:
        cat = json.load(f)

    # Build lookups
    scenario_info: dict[str, dict] = {s["id"]: s for s in cat["scenarios"]}
    scenario_to_feature: dict[str, dict] = {}
    for feat in cat["features"]:
        if feat.get("feature_implemented", False):
            for sid in feat.get("scenarios", []):
                scenario_to_feature[sid] = feat

    # Find failing implemented scenarios
    failing: list[tuple[str, str]] = []  # (scenario_id, spec_title)
    for spec in collect_specs(results_data.get("suites", [])):
        title = spec.get("title", "")
        sid   = title.split()[0] if title else ""
        info  = scenario_info.get(sid)
        if info and info.get("test_implemented", False) and spec_failed(spec):
            failing.append((sid, title))

    if not failing:
        print("No implemented scenarios failed — no issues to create")
        return

    # Group by feature
    by_feature: dict[str, list[tuple[str, str]]] = defaultdict(list)
    for sid, title in failing:
        feat = scenario_to_feature.get(sid)
        if feat:
            by_feature[feat["id"]].append((sid, title))

    print(f"Failing features: {list(by_feature)}")

    # Ensure the e2e-failure label exists (idempotent)
    gh("POST", f"/repos/{REPO}/labels", {
        "name": E2E_FAILURE_LABEL,
        "color": "e05d44",
        "description": "Automated: e2e regression detected in CI",
    })

    # Fetch all open e2e-failure issues once for deduplication
    open_issues: list[dict] = gh("GET",
        f"/repos/{REPO}/issues?state=open&labels={E2E_FAILURE_LABEL}&per_page=100"
    )  # type: ignore
    if not isinstance(open_issues, list):
        open_issues = []

    for feat_id, failures in by_feature.items():
        feat = next(f for f in cat["features"] if f["id"] == feat_id)
        feat_name = feat["name"]
        scenario_lines = "\n".join(
            f"- `{sid}`: {scenario_info[sid]['description']}" for sid, _ in failures
        )

        failure_comment = (
            f"## E2E regression — CI run #{RUN_NUMBER}\n\n"
            f"**Run:** {RUN_URL}\n\n"
            f"**Failing scenarios:**\n{scenario_lines}\n\n"
            f"*Auto-generated by `.github/workflows/e2e.yml` `e2e-failure-issues` job.*"
        )

        # Deduplication: find existing open issue for this feature
        existing = next(
            (i for i in open_issues if feat_id in i.get("title", "")),
            None,
        )

        if existing:
            result = gh("POST",
                f"/repos/{REPO}/issues/{existing['number']}/comments",
                {"body": failure_comment},
            )
            if "_error" not in result:
                print(f"  ✓ Commented on existing issue #{existing['number']} for {feat_id}")
            else:
                print(f"  ✗ Failed to comment on #{existing['number']}: {result}", file=sys.stderr)
        else:
            issue_body = (
                f"## Automated e2e failure report\n\n"
                f"**Feature:** `{feat_id}` — {feat_name}\n\n"
                f"The following implemented scenarios failed in CI run #{RUN_NUMBER}:\n\n"
                f"{scenario_lines}\n\n"
                f"**Run:** {RUN_URL}\n\n"
                f"## Next steps\n"
                f"1. Open the Playwright report artifact (attached to the run above) for "
                f"screenshots, traces, and error details.\n"
                f"2. Determine if this is a **regression** (production code broken) or a "
                f"**flaky test** (test needs hardening).\n"
                f"3. Fix the failing code or test and verify in CI.\n\n"
                f"---\n"
                f"*Auto-created by `.github/workflows/e2e.yml`. "
                f"Future failures on the same feature append a comment instead of opening "
                f"a duplicate issue.*"
            )
            issue = gh("POST", f"/repos/{REPO}/issues", {
                "title": f"[e2e failure] {feat_id}: {feat_name}",
                "body": issue_body,
                "labels": [E2E_FAILURE_LABEL, "ci"],
            })

            if "_error" in issue or "number" not in issue:
                print(f"  ✗ Failed to create issue for {feat_id}: {issue}", file=sys.stderr)
                continue

            issue_number = issue["number"]
            issue_node_id = issue.get("node_id", "")
            print(f"  ✓ Created issue #{issue_number} for {feat_id}")

            # Add to GitHub Projects board #2 as P1
            _add_to_project(issue_node_id, issue_number, feat_id)


def _add_to_project(issue_node_id: str, issue_number: int, feat_id: str) -> None:
    """Add the issue to the user's Projects V2 board #2 and set Priority = P1.

    Requires PROJECT_TOKEN (a PAT with `project` scope).
    GITHUB_TOKEN cannot access user-level Projects V2.
    """
    owner = REPO.split("/")[0]

    # 1. Get project node ID
    proj_result = gh_graphql(
        """query($login: String!) {
             user(login: $login) {
               projectsV2(first: 20) {
                 nodes { id number title }
               }
             }
           }""",
        {"login": owner},
    )
    projects = (
        proj_result.get("data", {}).get("user", {}) or {}
    ).get("projectsV2", {}).get("nodes", [])
    project = next((p for p in projects if p.get("number") == 2), None)

    if not project:
        print(f"    ⚠ Project board #2 not found for {owner} — skipping board add", file=sys.stderr)
        return

    project_id = project["id"]

    # 2. Add item to project
    add_result = gh_graphql(
        """mutation($projectId: ID!, $contentId: ID!) {
             addProjectV2ItemById(input: {projectId: $projectId, contentId: $contentId}) {
               item { id }
             }
           }""",
        {"projectId": project_id, "contentId": issue_node_id},
    )
    item_id = (
        add_result.get("data", {}).get("addProjectV2ItemById", {}) or {}
    ).get("item", {}).get("id")

    if not item_id:
        errs = add_result.get("errors", add_result.get("data"))
        print(f"    ⚠ Could not add issue #{issue_number} to board: {errs}", file=sys.stderr)
        return

    print(f"    ✓ Added issue #{issue_number} to project board")

    # 3. Find the Priority field and P1 option
    fields_result = gh_graphql(
        """query($projectId: ID!) {
             node(id: $projectId) {
               ... on ProjectV2 {
                 fields(first: 20) {
                   nodes {
                     ... on ProjectV2SingleSelectField {
                       id name
                       options { id name }
                     }
                   }
                 }
               }
             }
           }""",
        {"projectId": project_id},
    )
    fields = (
        fields_result.get("data", {}).get("node", {}) or {}
    ).get("fields", {}).get("nodes", [])

    priority_field = next(
        (f for f in fields if f.get("name", "").lower() == "priority"),
        None,
    )
    if not priority_field:
        print(f"    ⚠ No Priority field found on board — skipping P1 set", file=sys.stderr)
        return

    p1_option = next(
        (o for o in priority_field.get("options", []) if re.match(r"^P1\b", o.get("name", ""), re.IGNORECASE)),
        None,
    )
    if not p1_option:
        print(f"    ⚠ No P1 option in Priority field — skipping", file=sys.stderr)
        return

    # 4. Set Priority = P1
    set_result = gh_graphql(
        """mutation($projectId: ID!, $itemId: ID!, $fieldId: ID!, $optionId: String!) {
             updateProjectV2ItemFieldValue(input: {
               projectId: $projectId
               itemId:    $itemId
               fieldId:   $fieldId
               value:     { singleSelectOptionId: $optionId }
             }) {
               projectV2Item { id }
             }
           }""",
        {
            "projectId": project_id,
            "itemId":    item_id,
            "fieldId":   priority_field["id"],
            "optionId":  p1_option["id"],
        },
    )
    if set_result.get("data", {}).get("updateProjectV2ItemFieldValue"):
        print(f"    ✓ Set Priority = P1 on issue #{issue_number}")
    else:
        print(f"    ⚠ Could not set P1: {set_result.get('errors')}", file=sys.stderr)


if __name__ == "__main__":
    main()

