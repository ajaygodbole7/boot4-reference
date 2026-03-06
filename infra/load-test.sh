#!/usr/bin/env bash
# =============================================================================
# load-test.sh — Generate sustained load across all boot4-reference endpoints
#
# Hits every route repeatedly so Grafana panels populate with visible data:
#   POST   /api/products             (create)
#   POST   /api/products/:id/status  (activate)
#   POST   /api/orders               (create order)
#   POST   /api/orders/:id/status    (transition)
#   GET    /api/products          (list)
#   GET    /api/orders            (list)
#   GET    /api/orders/9999999    (intentional 404 — drives error-rate panel)
# =============================================================================
set -euo pipefail

BASE_URL="http://localhost:8080/api"
ROUNDS="${1:-5}"
PAUSE=0.5

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()      { echo -e "${GREEN}[${1}]${NC} ${2}"; }
err()     { echo -e "${RED}[${1}]${NC} ${2}"; }
section() { echo -e "\n${BOLD}${YELLOW}── $* ──${NC}"; }

detect_python() {
  for cmd in python3 python3.11 python3.12 python; do
    if command -v "$cmd" &>/dev/null; then
      PYTHON="$cmd"
      return
    fi
  done
  echo -e "${RED}[ERROR]${NC} Python not found. Install python3 and retry." >&2
  exit 1
}
detect_python

check_app() {
  if ! curl -sf "http://localhost:8081/actuator/health" -o /dev/null 2>/dev/null; then
    echo -e "${RED}[ERROR]${NC} App is not running at http://localhost:8080" >&2
    echo "  Start it with: ./infra/start-app.sh" >&2
    exit 1
  fi
}

http() {
  local method="$1" url="$2" body="${3:-}" ctype="${4:-application/json}"
  local args=(-s -o /tmp/http_resp -w "%{http_code}" -X "$method" "$url")
  [ -n "$body" ] && args+=(-H "Content-Type: $ctype" -d "$body")
  curl "${args[@]}" 2>/dev/null
}

json_field() {
  $PYTHON -c "import sys,json; print(json.load(open('/tmp/http_resp')).get('$1',''))" 2>/dev/null || echo ""
}

TOTAL_OK=0; TOTAL_ERR=0
CREATED_PRODUCTS=()
CREATED_ORDERS=()

req() {
  local label="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    ok "$actual" "$label"
    TOTAL_OK=$((TOTAL_OK+1))
  else
    err "$actual" "$label (expected $expected)"
    TOTAL_ERR=$((TOTAL_ERR+1))
  fi
}

# ─────────────────────────────────────────────────────────────────────────────
check_app
echo ""
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}${GREEN}  boot4-reference Load Test  (${ROUNDS} rounds)${NC}"
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════${NC}"
echo -e "  Target: ${CYAN}$BASE_URL${NC}"
echo -e "  Pause between requests: ${PAUSE}s"
echo ""

NAMES=("Widget" "Gadget" "Gizmo" "Doohickey" "Thingamajig")
SEQ=0

for round in $(seq 1 "$ROUNDS"); do
  section "Round $round / $ROUNDS"
  SEQ=$((SEQ+1))
  NAME="${NAMES[$((RANDOM % ${#NAMES[@]}))]}-$SEQ"
  PRICE=$((RANDOM % 100 + 5))
  STOCK=$((RANDOM % 200 + 10))

  # Create product
  BODY=$(printf '{"name":"%s","description":"Load test","price":%d,"stock":%d}' "$NAME" "$PRICE" "$STOCK")
  code=$(http POST "$BASE_URL/products" "$BODY")
  req "POST product ($NAME)" "201" "$code"
  PRODUCT_ID=$(json_field "id")
  CREATED_PRODUCTS+=("$PRODUCT_ID")
  sleep "$PAUSE"

  # Activate product
  code=$(http POST "$BASE_URL/products/$PRODUCT_ID/status" '{"status":"ACTIVE"}')
  req "POST activate" "200" "$code"
  sleep "$PAUSE"

  # Create order
  IDEM_KEY="load-$SEQ-$(date +%s)"
  ORDER_BODY=$(printf '{"items":[{"productId":%s,"quantity":1}]}' "$PRODUCT_ID")
  code=$(curl -s -o /tmp/http_resp -w "%{http_code}" -X POST "$BASE_URL/orders" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: $IDEM_KEY" \
    -d "$ORDER_BODY" 2>/dev/null)
  req "POST order" "201" "$code"
  ORDER_ID=$(json_field "id")
  CREATED_ORDERS+=("$ORDER_ID")
  sleep "$PAUSE"

  # Confirm order
  code=$(http POST "$BASE_URL/orders/$ORDER_ID/status" '{"status":"CONFIRMED"}')
  req "POST confirm" "200" "$code"
  sleep "$PAUSE"

  # List products
  code=$(http GET "$BASE_URL/products")
  req "GET products" "200" "$code"
  sleep "$PAUSE"

  # List orders
  code=$(http GET "$BASE_URL/orders")
  req "GET orders" "200" "$code"
  sleep "$PAUSE"

  # Intentional 404
  code=$(http GET "$BASE_URL/orders/9999999999999")
  req "GET 404" "404" "$code"
  sleep "$PAUSE"
done

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}  Load Test Complete${NC}"
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════${NC}"
echo -e "  ${GREEN}Passed:${NC} $TOTAL_OK"
echo -e "  ${RED}Failed:${NC} $TOTAL_ERR"
echo ""
echo -e "  View results in Grafana:"
echo -e "    App Overview:    ${CYAN}http://localhost:3000/d/app-overview${NC}"
echo -e "    HTTP Endpoints:  ${CYAN}http://localhost:3000/d/http-endpoints${NC}"
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════${NC}"
