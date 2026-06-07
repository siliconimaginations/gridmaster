#!/usr/bin/env python3
"""
Gemini AI code reviewer for pull requests.

Called by .github/workflows/gemini-review.yml on pull_request events.
Fetches the diff for reviewable files only, sends to Gemini,
and posts the result as a PR comment (replacing any previous Gemini review).

Environment variables (injected by the workflow):
  GITHUB_TOKEN       — for reading diff and posting comments
  GEMINI_API_KEY     — Google AI Studio key
  GITHUB_REPOSITORY  — owner/repo
  PR_NUMBER          — pull request number
  BASE_SHA           — base commit SHA of the PR
  HEAD_SHA           — head commit SHA of the PR
"""

import os
import re
import subprocess
import sys

import google.generativeai as genai
from github import Github

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

GEMINI_MODEL = "gemini-2.5-pro"

# Hard cap on diff characters sent to Gemini (~25 k tokens, well within context).
MAX_DIFF_CHARS = 90_000

# File extensions to skip — config, lockfiles, binary assets, generated files.
# Note: .md is intentionally NOT skipped — documentation PRs should be reviewed
# for accuracy, completeness, and consistency with the codebase.
SKIP_EXTENSIONS = {
    ".txt", ".xml", ".xiidm",
    ".yml", ".yaml",
    ".json",
    ".png", ".jpg", ".jpeg", ".svg", ".ico",
    ".gitignore", ".gitattributes",
}

# Exact filenames to skip regardless of extension.
SKIP_FILES = {"gradlew", "gradlew.bat", "package-lock.json"}

REVIEW_HEADER = "## Gemini Code Review 🤖"

# Regex that robustly parses the "b/<filename>" part of a git diff header,
# handling filenames that contain spaces or special characters.
_DIFF_HEADER_RE = re.compile(r"^diff --git a/.+ b/(.+)$")

PROMPT_TEMPLATE = """\
You are an expert code reviewer for a Kotlin/Spring Boot + TypeScript/React project called GridMaster —
an educational power grid simulation game. The backend uses PowSyBl (AC power flow, N-1 contingency),
Spring Boot 3, Kotlin coroutines, and SQLite. The frontend uses Vite + React + TypeScript + Babylon.js.

Review the following git diff and provide concise, actionable feedback.

Focus on:
- Bugs or logic errors (including PowSyBl API misuse)
- Security issues
- Performance problems (especially inside the game tick path)
- Code quality, naming, and maintainability
- Kotlin idioms (null safety, coroutines, sealed classes)
- TypeScript strictness and React patterns

For documentation files (.md):
- Technical accuracy — does the doc match the actual code/API?
- Completeness — are important scenarios, edge cases, or decisions missing?
- Consistency — does it align with ENGINEERING_PRINCIPLES.md and other design docs?
- Clarity — are examples concrete? Are interfaces/types shown correctly?

Format your response exactly as follows:

## Gemini Code Review 🤖

### Summary
[1–2 sentences]

### Issues
[Each issue: `file.kt:line — 🔴 Critical / 🟠 Major / 🟡 Minor — description and fix`]
[Write "None found." if there are no issues]

### Suggestions
[Optional improvements that are not bugs. Write "None." if nothing to add]

### Looks good ✅
[What is done well]

Be concise. No padding or filler.

---
{diff}
"""


# ---------------------------------------------------------------------------
# Diff helpers
# ---------------------------------------------------------------------------


def _should_review(filename: str) -> bool:
    ext = os.path.splitext(filename)[1].lower()
    basename = os.path.basename(filename)
    return ext not in SKIP_EXTENSIONS and basename not in SKIP_FILES


def get_filtered_diff(base_sha: str, head_sha: str) -> tuple[str, bool]:
    """
    Two-step approach:
    1. Get only the names of changed files — cheap, avoids loading a huge diff.
    2. Fetch the full diff for reviewable files only.
    Returns (diff, truncated).
    """
    # Step 1: names only
    names_result = subprocess.run(
        ["git", "diff", "--name-only", f"{base_sha}...{head_sha}"],
        capture_output=True,
        text=True,
        check=True,
    )
    all_files = [f.strip() for f in names_result.stdout.strip().splitlines() if f.strip()]
    reviewable = [f for f in all_files if _should_review(f)]

    if not reviewable:
        return "", False

    # Step 2: full diff for reviewable files only
    diff_result = subprocess.run(
        ["git", "diff", f"{base_sha}...{head_sha}", "--"] + reviewable,
        capture_output=True,
        text=True,
        check=True,
    )
    diff = diff_result.stdout

    if len(diff) > MAX_DIFF_CHARS:
        return diff[:MAX_DIFF_CHARS], True
    return diff, False


# ---------------------------------------------------------------------------
# Gemini
# ---------------------------------------------------------------------------


def call_gemini(diff: str, truncated: bool) -> str:
    genai.configure(api_key=os.environ["GEMINI_API_KEY"])
    model = genai.GenerativeModel(GEMINI_MODEL)

    notice = (
        f"\n\n> ⚠️ Diff was truncated to {MAX_DIFF_CHARS:,} chars. "
        "Some files may not have been reviewed.\n"
        if truncated
        else ""
    )

    prompt = PROMPT_TEMPLATE.format(diff=diff) + notice
    response = model.generate_content(prompt)
    return response.text


# ---------------------------------------------------------------------------
# GitHub comment
# ---------------------------------------------------------------------------


def post_or_update_comment(review: str) -> None:
    gh = Github(os.environ["GITHUB_TOKEN"])
    repo = gh.get_repo(os.environ["GITHUB_REPOSITORY"])
    pr = repo.get_pull(int(os.environ["PR_NUMBER"]))

    # Replace any previous Gemini review comment to keep the PR tidy.
    for comment in pr.get_issue_comments():
        if comment.body.startswith(REVIEW_HEADER):
            comment.delete()

    pr.create_issue_comment(review)
    print(f"Review posted on PR #{os.environ['PR_NUMBER']}.")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------


def main() -> None:
    base_sha = os.environ["BASE_SHA"]
    head_sha = os.environ["HEAD_SHA"]

    print(f"Diffing {base_sha[:8]}...{head_sha[:8]}")
    diff, truncated = get_filtered_diff(base_sha, head_sha)

    if not diff.strip():
        print("No reviewable changes in this diff (all changed files are excluded).")
        return

    print(f"Sending {len(diff):,} chars to {GEMINI_MODEL}…")
    review = call_gemini(diff, truncated)
    post_or_update_comment(review)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
