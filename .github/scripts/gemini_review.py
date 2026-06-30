#!/usr/bin/env python3
"""
Gemini AI code reviewer for pull requests.

Called by .github/workflows/gemini-review.yml on pull_request events.
Fetches the diff for reviewable files only, sends to Gemini,
and posts the result as a PR comment (replacing any previous Gemini review).

Environment variables (injected by the workflow):
  GITHUB_TOKEN       — for reading diff and posting comments
  GEMINI_API_KEY     — Google AI Studio key (free tier supported)
  GITHUB_REPOSITORY  — owner/repo
  PR_NUMBER          — pull request number
  BASE_SHA           — base commit SHA of the PR
  HEAD_SHA           — head commit SHA of the PR
"""

import os
import re
import subprocess
import sys
import time

import google.generativeai as genai
from google.api_core.exceptions import ResourceExhausted
from github import Github

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# Models tried in round-robin order. All models in a round are attempted before
# any backoff is applied. Lower-RPD-headroom models go first; Gemma candidates last.
#
# Order rationale: lite/budget models first (higher free-tier RPD headroom),
# flagship models in the middle, Gemma models at the end (unverified API IDs —
# verify against https://ai.google.dev/gemini-api/docs/models before relying on them).
# All confirmed stable API IDs are from ai.google.dev/gemini-api/docs/models (2026-06).
MODELS = [
    "gemini-2.5-flash-lite",  # 2.5 lite — fastest, most budget-friendly; highest free-tier RPD in 2.5 family
    "gemini-3.1-flash-lite",  # 3.1 lite — newest stable lite model; high free-tier RPD
    "gemini-3.5-flash",       # 3.5 flagship — best quality, stable; lower free-tier RPD
    "gemini-2.5-flash",       # 2.5 flash — proven baseline; 20 RPD on free tier
    # Gemma 4 — free tier, Unlimited TPM; API IDs verified 2026-06-30 via list_models()
    "gemma-4-26b-a4b-it",     # Gemma 4 26B activation-4-bit (instruction-tuned) — free tier, Unlimited TPM
    "gemma-4-31b-it",         # Gemma 4 31B (instruction-tuned) — free tier, Unlimited TPM
]

# Hard cap on diff characters sent to Gemini.
# Kept at 60k to stay comfortably within free-tier per-request token limits.
MAX_DIFF_CHARS = 60_000

# Round-based retry parameters.
# Strategy: try every model in MODELS once (one "round") before applying backoff.
# This avoids waiting 15–120 s on a single exhausted model when a healthy one is next.
# Round backoff sequence (seconds): 30 → 60 → 120 (3 gaps for 4 rounds).
MAX_ROUNDS = 4
INITIAL_ROUND_BACKOFF_SECONDS = 30

# Allowlist of extensions to send to Gemini for review.
# Only these types are reviewed; binary blobs, lock files (package-lock.json),
# and generated wrappers (gradlew.bat) are excluded via SKIP_FILES.
# Add extensions here when new reviewable file types are introduced.
#
# Surveyed from `git ls-files` — covers all hand-authored source in this repo:
REVIEW_EXTENSIONS = {
    # Backend
    ".kt",          # Kotlin source
    ".kts",         # Kotlin Gradle build scripts (build.gradle.kts, settings.gradle.kts)
    ".properties",  # Gradle / Spring Boot properties files

    # Frontend
    ".ts",    # TypeScript
    ".tsx",   # TypeScript JSX
    ".css",   # CSS / CSS Modules
    ".html",  # HTML template
    ".json",  # package.json, tsconfig.json — deps and compiler config

    # CI / config
    ".yml",   # GitHub Actions workflows, docker-compose
    ".yaml",  # YAML variant

    # Tooling / scripts
    ".py",    # Python CI scripts
    ".sh",    # Shell scripts (scripts/lint.sh)

    # Documentation
    ".md",    # Markdown docs
}

# Exact filenames to skip even when their extension is in REVIEW_EXTENSIONS.
# gradlew / gradlew.bat are generated Gradle wrappers; package-lock.json is a lockfile.
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
    return ext in REVIEW_EXTENSIONS and basename not in SKIP_FILES


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


def _call_model_once(model_name: str, diff: str, truncated: bool) -> str:
    """Single attempt at one model. Raises ResourceExhausted or any Exception on failure."""
    model = genai.GenerativeModel(model_name)

    notice = (
        f"\n\n> ⚠️ Diff was truncated to {MAX_DIFF_CHARS:,} chars. "
        "Some files may not have been reviewed.\n"
        if truncated
        else ""
    )

    prompt = PROMPT_TEMPLATE.format(diff=diff) + notice
    response = model.generate_content(prompt)
    try:
        return response.text
    except ValueError as exc:
        # Candidates list is empty — usually a safety-filter block. Treat as
        # a transient failure so call_gemini() can fall through to the next model.
        raise RuntimeError(
            f"Response blocked or empty (prompt_feedback={response.prompt_feedback}): {exc}"
        ) from exc


def call_gemini(diff: str, truncated: bool) -> tuple[str, str]:
    """
    Round-based rotation over MODELS.

    Each round tries every model exactly once. If all models fail in a round,
    back off before the next round (backoff doubles each round: 30s, 60s, 120s).

    Returns (review_text, used_model_name) on the first success.
    Raises RuntimeError when all MAX_ROUNDS rounds are exhausted.
    """
    round_backoff = INITIAL_ROUND_BACKOFF_SECONDS

    for round_num in range(1, MAX_ROUNDS + 1):
        for model_name in MODELS:
            print(f"[Round {round_num}] Trying {model_name}…", file=sys.stderr)
            try:
                review = _call_model_once(model_name, diff, truncated)
                return review, model_name
            except ResourceExhausted:
                print(
                    f"[{model_name}] Quota exhausted — trying next model.",
                    file=sys.stderr,
                )
            except Exception as exc:
                print(
                    f"[{model_name}] API error ({exc.__class__.__name__}: {exc}) — trying next model.",
                    file=sys.stderr,
                )

        # All models failed this round
        if round_num < MAX_ROUNDS:
            print(
                f"All models failed in round {round_num}/{MAX_ROUNDS} — "
                f"backing off {round_backoff}s before round {round_num + 1}…",
                file=sys.stderr,
            )
            time.sleep(round_backoff)
            round_backoff *= 2

    raise RuntimeError(
        f"All {len(MODELS)} models exhausted after {MAX_ROUNDS} rounds."
    )


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
    if "GEMINI_API_KEY" not in os.environ:
        raise RuntimeError("Missing required environment variable: GEMINI_API_KEY")

    # Configure once — shared across all _call_model_once() calls.
    genai.configure(api_key=os.environ["GEMINI_API_KEY"])

    base_sha = os.environ["BASE_SHA"]
    head_sha = os.environ["HEAD_SHA"]

    print(f"Diffing {base_sha[:8]}...{head_sha[:8]}")
    diff, truncated = get_filtered_diff(base_sha, head_sha)

    if not diff.strip():
        print("No reviewable changes in this diff (all changed files are excluded).")
        return

    print(
        f"Sending {len(diff):,} chars to Gemini "
        f"({len(MODELS)} models, up to {MAX_ROUNDS} rounds)…"
    )

    try:
        review_text, used_model = call_gemini(diff, truncated)
    except RuntimeError:
        gh = Github(os.environ["GITHUB_TOKEN"])
        repo = gh.get_repo(os.environ["GITHUB_REPOSITORY"])
        pr = repo.get_pull(int(os.environ["PR_NUMBER"]))
        pr.create_issue_comment(
            f"{REVIEW_HEADER}\n\n"
            "> ⏸️ Gemini review skipped — free-tier quota exhausted on all models "
            f"({', '.join(MODELS)}) across {MAX_ROUNDS} rounds. "
            "No action required; this does not block merge."
        )
        print("All models/rounds exhausted — soft notice posted, job exits 0.")
        return

    # Append the model attribution footer to the review body.
    review_with_attribution = review_text.rstrip() + f"\n\n---\n*Review by `{used_model}`*"
    post_or_update_comment(review_with_attribution)


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(f"Error: {exc}", file=sys.stderr)
        sys.exit(1)
