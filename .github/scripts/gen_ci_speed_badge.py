#!/usr/bin/env python3
"""Generate a Shields-style SVG badge for CI speed (avg duration of recent workflow runs)."""
import urllib.request
import json
import os
import sys
from datetime import datetime, timezone, timedelta


def shields_svg(label: str, value: str, color: str) -> str:
    """Generate a minimal Shields-style flat badge SVG."""
    # Approximate character width for monospace-ish font
    lw = len(label) * 6 + 10
    vw = len(value) * 6 + 10
    total = lw + vw

    return f'''<svg xmlns="http://www.w3.org/2000/svg" width="{total}" height="20">
  <linearGradient id="s" x2="0" y2="100%">
    <stop offset="0" stop-color="#bbb" stop-opacity=".1"/>
    <stop offset="1" stop-opacity=".1"/>
  </linearGradient>
  <rect rx="3" width="{total}" height="20" fill="#555"/>
  <rect rx="3" x="{lw}" width="{vw}" height="20" fill="{color}"/>
  <rect x="{lw}" width="4" height="20" fill="{color}"/>
  <rect rx="3" width="{total}" height="20" fill="url(#s)"/>
  <g fill="#fff" text-anchor="middle" font-family="DejaVu Sans,Verdana,Geneva,sans-serif" font-size="11">
    <text x="{lw // 2}" y="15" fill="#010101" fill-opacity=".3">{label}</text>
    <text x="{lw // 2}" y="14">{label}</text>
    <text x="{lw + vw // 2}" y="15" fill="#010101" fill-opacity=".3">{value}</text>
    <text x="{lw + vw // 2}" y="14">{value}</text>
  </g>
</svg>'''


def main():
    token = os.environ.get("GH_TOKEN", "")
    repo = os.environ.get("GITHUB_REPOSITORY", "siliconimaginations/gridmaster")

    url = f"https://api.github.com/repos/{repo}/actions/workflows/ci.yml/runs?status=success&per_page=20"
    req = urllib.request.Request(
        url,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(req) as resp:
            runs = json.loads(resp.read())["workflow_runs"]
    except Exception as e:
        print(f"Failed to fetch runs: {e}", file=sys.stderr)
        runs = []

    cutoff = datetime.now(timezone.utc) - timedelta(days=1)
    durations = []
    for run in runs:
        created = datetime.fromisoformat(run["created_at"].replace("Z", "+00:00"))
        updated = datetime.fromisoformat(run["updated_at"].replace("Z", "+00:00"))
        if created >= cutoff:
            durations.append((updated - created).total_seconds())

    # Fall back to most recent 5 runs if none in past day
    if not durations:
        for run in runs[:5]:
            created = datetime.fromisoformat(run["created_at"].replace("Z", "+00:00"))
            updated = datetime.fromisoformat(run["updated_at"].replace("Z", "+00:00"))
            durations.append((updated - created).total_seconds())

    if durations:
        avg_secs = sum(durations) / len(durations)
    else:
        avg_secs = 0

    avg_min = avg_secs / 60
    if avg_min < 1:
        label_val = f"{int(avg_secs)}s avg"
    else:
        label_val = f"{avg_min:.1f}min avg"

    color = "#4c1" if avg_min < 3 else ("#dfb317" if avg_min < 5 else "#e05d44")

    out_dir = os.environ.get("BADGES_DIR", ".github/badges")
    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "ci-speed.svg")
    with open(out_path, "w") as f:
        f.write(shields_svg("CI speed", label_val, color))
    print(f"Wrote {out_path} ({label_val}, {color})")


if __name__ == "__main__":
    main()
