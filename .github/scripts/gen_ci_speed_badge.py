#!/usr/bin/env python3
"""
Generate a Shields-style SVG badge showing average CI wall-clock time.

Queries the GitHub Actions API for recent successful ci.yml runs:
  - Primary window: past 24 h
  - Fallback 1: past 7 days (up to 10 runs) when <1 run found in past 24 h
  - Fallback 2: most recent 5 runs regardless of age (cold-start / sparse repos)

Colour thresholds:
  green  < 3 min
  yellow 3–5 min
  red    ≥ 5 min

Exits 1 only on unrecoverable API error (network / auth); badge write failures
are propagated normally so the caller can decide whether to continue-on-error.
"""
import json
import os
import sys
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone


def _api_get(url: str, token: str) -> dict:
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            return json.loads(resp.read())
    except urllib.error.HTTPError as exc:
        print(f"HTTP {exc.code} fetching {url}: {exc.reason}", file=sys.stderr)
        raise
    except urllib.error.URLError as exc:
        print(f"Network error fetching {url}: {exc.reason}", file=sys.stderr)
        raise


def _fetch_runs(repo: str, token: str, per_page: int = 20) -> list[dict]:
    url = (
        f"https://api.github.com/repos/{repo}/actions/workflows/ci.yml/runs"
        f"?status=success&per_page={per_page}"
    )
    return _api_get(url, token)["workflow_runs"]


def _duration_seconds(run: dict) -> float:
    created = datetime.fromisoformat(run["created_at"].replace("Z", "+00:00"))
    updated = datetime.fromisoformat(run["updated_at"].replace("Z", "+00:00"))
    return (updated - created).total_seconds()


def _select_durations(runs: list[dict]) -> tuple[list[float], str]:
    """
    Return (durations, window_description) using the widest appropriate window.
    Primary: past 24 h — Fallback 1: past 7 days (≤10 runs) — Fallback 2: last 5
    """
    now = datetime.now(timezone.utc)

    for hours, label, max_runs in [(24, "past 24 h", None), (24 * 7, "past 7 days", 10)]:
        cutoff = now - timedelta(hours=hours)
        subset = [r for r in runs if datetime.fromisoformat(r["created_at"].replace("Z", "+00:00")) >= cutoff]
        if max_runs:
            subset = subset[:max_runs]
        if subset:
            return [_duration_seconds(r) for r in subset], f"{label} ({len(subset)} run(s))"

    # Cold-start / very sparse repo: use whatever we have
    subset = runs[:5]
    if subset:
        return [_duration_seconds(r) for r in subset], f"last {len(subset)} run(s) (sparse history)"
    return [], "no data"


def shields_svg(label: str, value: str, color: str) -> str:
    """Minimal Shields.io-compatible flat badge."""
    lw = len(label) * 6 + 10
    vw = len(value) * 6 + 10
    total = lw + vw
    return (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{total}" height="20">'
        f'<linearGradient id="s" x2="0" y2="100%">'
        f'<stop offset="0" stop-color="#bbb" stop-opacity=".1"/>'
        f'<stop offset="1" stop-opacity=".1"/>'
        f"</linearGradient>"
        f'<rect rx="3" width="{total}" height="20" fill="#555"/>'
        f'<rect rx="3" x="{lw}" width="{vw}" height="20" fill="{color}"/>'
        f'<rect x="{lw}" width="4" height="20" fill="{color}"/>'
        f'<rect rx="3" width="{total}" height="20" fill="url(#s)"/>'
        f'<g fill="#fff" text-anchor="middle" font-family="DejaVu Sans,Verdana,Geneva,sans-serif" font-size="11">'
        f'<text x="{lw // 2}" y="15" fill="#010101" fill-opacity=".3">{label}</text>'
        f'<text x="{lw // 2}" y="14">{label}</text>'
        f'<text x="{lw + vw // 2}" y="15" fill="#010101" fill-opacity=".3">{value}</text>'
        f'<text x="{lw + vw // 2}" y="14">{value}</text>'
        f"</g></svg>"
    )


def main() -> None:
    token = os.environ.get("GH_TOKEN", "")
    repo = os.environ.get("GITHUB_REPOSITORY", "siliconimaginations/gridmaster")
    badges_dir = os.environ.get("BADGES_DIR", ".github/badges")

    runs = _fetch_runs(repo, token)
    durations, window = _select_durations(runs)
    print(f"Using {window}")

    if durations:
        avg_secs = sum(durations) / len(durations)
        avg_min = avg_secs / 60
        value = f"{int(avg_secs)}s avg" if avg_min < 1 else f"{avg_min:.1f}min avg"
        color = "#4c1" if avg_min < 3 else ("#dfb317" if avg_min < 5 else "#e05d44")
    else:
        value, color = "no data", "#9f9f9f"
        avg_min = None

    print(f"Badge: CI speed | {value} | color={color}")

    os.makedirs(badges_dir, exist_ok=True)
    out = os.path.join(badges_dir, "ci-speed.svg")
    with open(out, "w") as fh:
        fh.write(shields_svg("CI speed", value, color))
    print(f"Wrote {out}")


if __name__ == "__main__":
    main()
