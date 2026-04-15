#!/usr/bin/env bash
# Pre-PR check script — run this before opening a Pull Request
# Usage: bash scripts/check-pr.sh

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0
FAIL=0

ok()   { echo "  [PASS] $1"; PASS=$((PASS + 1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL + 1)); }
info() { echo ""; echo "==> $1"; }

# ── 1. Kotlin tests ──────────────────────────────────────────────────────────
info "Running Kotlin tests (./gradlew test)..."
if "$ROOT/gradlew" -p "$ROOT" test -q 2>&1; then
  ok "Kotlin tests passed"
else
  fail "Kotlin tests FAILED — fix before opening PR"
fi

# ── 2. Webview build ─────────────────────────────────────────────────────────
info "Building webview (npm run build)..."
if [ -f "$ROOT/webview/package.json" ]; then
  if (cd "$ROOT/webview" && npm run build --silent 2>&1); then
    ok "Webview build passed"
  else
    fail "Webview build FAILED — fix before opening PR"
  fi
else
  ok "No webview/package.json found — skipping webview build"
fi

# ── 3. Debug log check ───────────────────────────────────────────────────────
info "Checking for leftover debug logs..."
DEBUG_HITS=$(grep -rn \
  --include="*.kt" --include="*.ts" --include="*.tsx" \
  -e 'println(' -e 'System\.out\.print' -e 'console\.log(' \
  "$ROOT/src" "$ROOT/webview/src" 2>/dev/null | \
  grep -v '\.test\.' | grep -v 'spec\.' || true)

if [ -z "$DEBUG_HITS" ]; then
  ok "No leftover debug logs found"
else
  fail "Found possible debug logs — review before opening PR:"
  echo "$DEBUG_HITS" | head -20 | sed 's/^/    /'
fi

# ── 4. PR title reminder ─────────────────────────────────────────────────────
info "PR title reminder"
echo "  Make sure your PR title follows Conventional Commits:"
echo "    feat(<scope>): description"
echo "    fix(<scope>): description"
echo "    docs: description"
echo ""
echo "  Common mistake: using 'fix' for new features — use 'feat' instead."

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "────────────────────────────────────────"
echo "  Results: ${PASS} passed, ${FAIL} failed"
echo "────────────────────────────────────────"

if [ "$FAIL" -gt 0 ]; then
  echo "  Fix the failures above before opening your PR."
  exit 1
else
  echo "  All checks passed. Ready to open PR!"
  exit 0
fi
