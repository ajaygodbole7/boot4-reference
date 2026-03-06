#!/usr/bin/env bash
# =============================================================================
# demo.sh — Full pipeline: infra → app → e2e → load → observability check
#
# Orchestrates the entire boot4-reference stack and verifies that metrics,
# traces, and logs flow end-to-end. Reuses existing infra scripts.
#
# Usage:
#   ./infra/demo.sh [--cleanup] [--skip-build] [--skip-infra] [--load-rounds N]
# =============================================================================
# No set -e — this is an orchestrator that captures per-stage exit codes
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Colour helpers ────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
pass()    { echo -e "  ${GREEN}PASS${NC}  $*"; }
fail()    { echo -e "  ${RED}FAIL${NC}  $*"; }

# ── Defaults ──────────────────────────────────────────────────────────────────
CLEANUP=false
SKIP_BUILD=false
SKIP_INFRA=false
LOAD_ROUNDS=5
STAGE_RESULTS=()

usage() {
  echo "Usage: $0 [--cleanup] [--skip-build] [--skip-infra] [--load-rounds N]"
  echo ""
  echo "  --cleanup        Run stop.sh after demo completes (even on failure)"
  echo "  --skip-build     Pass SKIP_BUILD=true to start-app.sh"
  echo "  --skip-infra     Skip start.sh (use when stack is already running)"
  echo "  --load-rounds N  Number of load-test rounds (default: 5)"
  exit 0
}

while [[ $# -gt 0 ]]; do
  case $1 in
    --cleanup)      CLEANUP=true; shift ;;
    --skip-build)   SKIP_BUILD=true; shift ;;
    --skip-infra)   SKIP_INFRA=true; shift ;;
    --load-rounds)  LOAD_ROUNDS="${2:-5}"; shift 2 ;;
    --help|-h)      usage ;;
    *)              echo -e "${RED}[ERROR]${NC} Unknown argument: $1" >&2; usage ;;
  esac
done

record() {
  local stage="$1" result="$2"
  STAGE_RESULTS+=("$result|$stage")
}

# ── Cleanup trap — always runs if --cleanup is set ────────────────────────────
cleanup_on_exit() {
  if [ "$CLEANUP" = true ]; then
    echo ""
    info "Cleaning up (--cleanup flag set)..."
    "$SCRIPT_DIR/stop.sh" || true
  fi
}
trap cleanup_on_exit EXIT

# ── Banner ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}${GREEN}  boot4-reference Full Demo Pipeline${NC}"
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════════════${NC}"
echo -e "  Skip infra:   ${CYAN}$SKIP_INFRA${NC}"
echo -e "  Skip build:   ${CYAN}$SKIP_BUILD${NC}"
echo -e "  Load rounds:  ${CYAN}$LOAD_ROUNDS${NC}"
echo -e "  Cleanup:      ${CYAN}$CLEANUP${NC}"
echo ""

# ── Stage 1: Start infrastructure ────────────────────────────────────────────
echo -e "\n${BOLD}${YELLOW}━━ Stage 1: Start Infrastructure ━━${NC}"
if [ "$SKIP_INFRA" = true ]; then
  info "Skipping infrastructure startup (--skip-infra)"
  record "Infrastructure" "SKIP"
elif "$SCRIPT_DIR/start.sh"; then
  record "Infrastructure" "PASS"
else
  record "Infrastructure" "FAIL"
  warn "Infrastructure failed to start — attempting remaining stages anyway"
fi

# ── Stage 2: Build & start app ───────────────────────────────────────────────
echo -e "\n${BOLD}${YELLOW}━━ Stage 2: Build & Start App ━━${NC}"
if SKIP_BUILD="$SKIP_BUILD" "$SCRIPT_DIR/start-app.sh"; then
  record "App startup" "PASS"
else
  record "App startup" "FAIL"
  warn "App failed to start — attempting remaining stages anyway"
fi

# ── Stage 3: E2E tests ───────────────────────────────────────────────────────
echo -e "\n${BOLD}${YELLOW}━━ Stage 3: E2E Tests ━━${NC}"
if "$SCRIPT_DIR/e2e-test.sh"; then
  record "E2E tests" "PASS"
else
  record "E2E tests" "FAIL"
  warn "E2E tests had failures — continuing"
fi

# ── Stage 4: Load test ───────────────────────────────────────────────────────
echo -e "\n${BOLD}${YELLOW}━━ Stage 4: Load Test ($LOAD_ROUNDS rounds) ━━${NC}"
if "$SCRIPT_DIR/load-test.sh" "$LOAD_ROUNDS"; then
  record "Load test" "PASS"
else
  record "Load test" "FAIL"
  warn "Load test had failures — continuing"
fi

# ── Stage 5: Verify observability ────────────────────────────────────────────
echo -e "\n${BOLD}${YELLOW}━━ Stage 5: Verify Observability ━━${NC}"

# OTel pipeline has inherent latency: collector batch processor (~5s) + backend WAL flush (~10s)
info "Waiting 15s for OTel pipeline flush..."
sleep 15

OBS_PASS=0; OBS_FAIL=0
NOW=$(date +%s)
START=$(( NOW - 300 ))

# Prometheus — check for http_server_requests metric (Micrometer convention via Prometheus scrape)
PROM_RESULT=$(curl -sf 'http://localhost:9090/api/v1/query?query=http_server_requests_seconds_count' 2>/dev/null || echo "")
PROM_COUNT=$(echo "$PROM_RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('data',{}).get('result',[])))" 2>/dev/null || echo "0")
if [ "$PROM_COUNT" -gt 0 ] 2>/dev/null; then
  pass "Prometheus — $PROM_COUNT metric series"
  OBS_PASS=$((OBS_PASS+1))
else
  fail "Prometheus — no http_server_requests_seconds_count metrics found"
  OBS_FAIL=$((OBS_FAIL+1))
fi

# Tempo — search for recent traces (requires explicit time range)
TEMPO_RESULT=$(curl -sfG 'http://localhost:3200/api/search' \
  --data-urlencode 'q={}' \
  --data-urlencode "start=$START" \
  --data-urlencode "end=$NOW" \
  --data-urlencode 'limit=5' 2>/dev/null || echo "")
TEMPO_COUNT=$(echo "$TEMPO_RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('traces',[])))" 2>/dev/null || echo "0")
if [ "$TEMPO_COUNT" -gt 0 ] 2>/dev/null; then
  pass "Tempo — $TEMPO_COUNT traces found"
  OBS_PASS=$((OBS_PASS+1))
else
  fail "Tempo — no traces found"
  OBS_FAIL=$((OBS_FAIL+1))
fi

# Loki — query for log streams from the app (OTel sets service_name resource attribute)
LOKI_RESULT=$(curl -sfG 'http://localhost:3100/loki/api/v1/query_range' \
  --data-urlencode 'query={service_name="boot4-reference"}' \
  --data-urlencode "start=$START" \
  --data-urlencode "end=$NOW" \
  --data-urlencode 'limit=5' 2>/dev/null || echo "")
LOKI_COUNT=$(echo "$LOKI_RESULT" | python3 -c "import sys,json; d=json.load(sys.stdin); print(len(d.get('data',{}).get('result',[])))" 2>/dev/null || echo "0")
if [ "$LOKI_COUNT" -gt 0 ] 2>/dev/null; then
  pass "Loki — $LOKI_COUNT log streams"
  OBS_PASS=$((OBS_PASS+1))
else
  fail "Loki — no log streams found (query: {service_name=\"boot4-reference\"})"
  OBS_FAIL=$((OBS_FAIL+1))
fi

if [ "$OBS_FAIL" -eq 0 ]; then
  record "Observability" "PASS"
else
  record "Observability" "FAIL"
fi

# ── Stage 6: Summary ─────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}${GREEN}  Demo Pipeline Summary${NC}"
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════════════${NC}"
echo ""

TOTAL_PASS=0; TOTAL_FAIL=0
for entry in "${STAGE_RESULTS[@]}"; do
  result="${entry%%|*}"
  stage="${entry#*|}"
  case "$result" in
    PASS) pass "$stage"; TOTAL_PASS=$((TOTAL_PASS+1)) ;;
    SKIP) echo -e "  ${YELLOW}SKIP${NC}  $stage" ;;
    FAIL) fail "$stage"; TOTAL_FAIL=$((TOTAL_FAIL+1)) ;;
  esac
done

echo ""
echo -e "  Grafana dashboards:"
echo -e "    App Overview:    ${CYAN}http://localhost:3000/d/app-overview${NC}"
echo -e "    HTTP Endpoints:  ${CYAN}http://localhost:3000/d/http-endpoints${NC}"
echo -e "    Explore Traces:  ${CYAN}http://localhost:3000/explore${NC}"
echo ""
echo -e "  Observability backends:"
echo -e "    Prometheus:  ${CYAN}http://localhost:9090${NC}"
echo -e "    Tempo:       ${CYAN}http://localhost:3200${NC}"
echo -e "    Loki:        ${CYAN}http://localhost:3100${NC}"
echo ""

if [ "$TOTAL_FAIL" -gt 0 ]; then
  echo -e "  ${RED}${TOTAL_FAIL} stage(s) failed${NC}"
else
  echo -e "  ${GREEN}All ${TOTAL_PASS} stages passed${NC}"
fi
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════════════${NC}"

# Exit with failure if any stage failed
exit "$TOTAL_FAIL"
