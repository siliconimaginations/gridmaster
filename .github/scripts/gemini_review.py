#!/usr/bin/env python3
"""
Gemini AI code reviewer for pull requests.

Called by .github/workflows/gemini-review.yml on pull_request events.
Fetches the full PR diff, filters to reviewable files, sends to Gemini,
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
import subprocess
import sys

import google.generativeai as genai
from github import Github

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

GEMINI_MODEL = "gemini-2.5-pro"

# Hard cap on diff characters sent to Gemini (~25 k tokens, well within context).
# If the diff is larger it is truncated with a notice.
MAX_DIFF_CHARS = 90_000

# File extensions to skip — docs, config, lockfiles, generated files.
SKIP_EXTENSIONS = {
    ".md", ".txt", ".xml", ".xiidm",
    ".yml", ".yaml",
    ".json",       # covers package-lock.json; build.gradle.kts is .kts not .json
    ".png", ".jpg", ".jpeg", ".svg", ".ico",
    ".gitignore", ".gitattributes",
}

# Exact filenames to skip regardless of extension.
SKIP_FILES = {"gradlew", "gradlew.bat", "package-lock.json"}

REVIEW_HEADER = "## Gemini Code Review 🤖"

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

Format your response exactly as follows:

## Gemini Code Review 🤖

### Summary
[1–2 sentences]

### Issues
[Each issue on its own line: `file.kt:line — 🔴 Critical / 🟠 Major / 🟡 Minor — description and fix`]
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


def get_filtered_diff(base_sha: str, head_sha: str) -> tuple[str, bool]:
    """
    Return (filtered_diff, truncated).
    Runs git diff between base and head, strips files we don't review.
    """
    result = subprocess.run(
        ["git", "diff", f"{base_sha}...{head_sha}"],
        capture_output=True,
        text=True,
        check=True,
    )
    raw = result.stdout
    filtered = _filter_diff(raw)
    if len(filtered) > MAX_DIFF_CHARS:
        return filtered[:MAX_DIFF_CHARS], True
    return filtered, False


def _filter_diff(diff: str) -> str:
    lines = diff.split("\n")
    out = []
    include = True
    for line in lines:
        if line.startswith("diff --git"):
            # Extract filename from "diff --git a/path b/path"
            parts = line.split(" b/")
            filename = parts[-1] if len(parts) > 1 else ""
            ext = os.path.splitext(filename)[1].lower()
            basename = os.path.basename(filename)
            include = ext not in SKIP_EXTENSIONS and basename not in SKIP_FILES
        if include:
            out.append(line)
    return "\n".join(out)


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
