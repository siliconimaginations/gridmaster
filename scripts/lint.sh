#!/usr/bin/env bash
# Run all linters locally before pushing — mirrors what CI checks.
# Usage: ./scripts/lint.sh

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KTLINT_VERSION="1.2.1"
KTLINT_BIN="$REPO_ROOT/.tools/ktlint"

# ── Download ktlint if not cached ────────────────────────────────────────────
if [[ ! -x "$KTLINT_BIN" ]]; then
  echo "-> Downloading ktlint $KTLINT_VERSION..."
  mkdir -p "$REPO_ROOT/.tools"
  curl -sL \
    "https://github.com/pinterest/ktlint/releases/download/${KTLINT_VERSION}/ktlint" \
    -o "$KTLINT_BIN"
  chmod +x "$KTLINT_BIN"
fi

# ── Kotlin lint ──────────────────────────────────────────────────────────────
echo "-> ktlint (backend)"
cd "$REPO_ROOT"
"$KTLINT_BIN" "backend/src/**/*.kt"
echo "   OK: Kotlin lint clean"

# ── TypeScript lint ──────────────────────────────────────────────────────────
echo "-> ESLint (frontend)"
cd "$REPO_ROOT/frontend"
npm install --silent 2>/dev/null
  npx eslint src --ext ts,tsx --max-warnings 0
echo "   OK: TypeScript lint clean"

echo ""
echo "All linters passed. Safe to push."
