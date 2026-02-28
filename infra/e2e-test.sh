#!/usr/bin/env bash
# =============================================================================
# e2e-test.sh — Full product + order lifecycle end-to-end test
#
# Exercises every API operation in order, asserting HTTP status codes and
# response payloads at each step. Useful for smoke-testing after deploys
# and for generating recognisable trace sequences in Grafana.
#
# Flow:
#  1.  POST   /api/products             → 201  create product
#  2.  GET    /api/products/:id          → 200  verify creation
#  3.  PATCH  /api/products/:id          → 200  partial update
#  4.  POST   /api/products/:id/status    → 200  lifecycle transition (ACTIVE)
#  5.  POST   /api/orders               → 201  create order
#  6.  GET    /api/orders/:id            → 200  verify order with lines
#  7.  POST   /api/orders/:id/status     → 200  transition (CONFIRMED)
#  8.  POST   /api/orders/:id/status     → 200  transition (SHIPPED)
#  9.  POST   /api/orders/:id/status     → 200  transition (DELIVERED)
# 10.  GET    /api/orders/:id            → 200  verify DELIVERED
# =============================================================================
set -euo pipefail

BASE_URL="http://localhost:8080/api"
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()      { echo -e "${GREEN}[${1}]${NC} ${2}"; }
err()     { echo -e "${RED}[${1}]${NC} ${2}"; }
section() { echo -e "\n${BOLD}${YELLOW}── $* ──${NC}"; }

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
  python3.11 -c "import sys,json; print(json.load(open('/tmp/http_resp')).get('$1',''))" 2>/dev/null || echo ""
}

TOTAL_OK=0; TOTAL_ERR=0

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
echo -e "${BOLD}${GREEN}  boot4-reference End-to-End Lifecycle Test${NC}"
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════${NC}"
echo -e "  Target: ${CYAN}$BASE_URL${NC}"
echo ""

# Step 1 — CREATE PRODUCT
section "Step 1: Create Product"
PRODUCT_BODY='{"name":"E2E Widget","description":"Test product","price":25.00,"stock":50}'
code=$(http POST "$BASE_URL/products" "$PRODUCT_BODY")
req "POST /api/products" "201" "$code"
PRODUCT_ID=$(json_field "id")
echo "  Product ID: $PRODUCT_ID"

# Step 2 — GET PRODUCT
section "Step 2: Verify Product"
code=$(http GET "$BASE_URL/products/$PRODUCT_ID")
req "GET /api/products/$PRODUCT_ID" "200" "$code"

# Step 3 — PATCH PRODUCT
section "Step 3: Partial Update Product"
code=$(http PATCH "$BASE_URL/products/$PRODUCT_ID" '{"description":"Updated via E2E"}' "application/merge-patch+json")
req "PATCH /api/products/$PRODUCT_ID" "200" "$code"

# Step 4 — ACTIVATE PRODUCT
section "Step 4: Activate Product"
code=$(http POST "$BASE_URL/products/$PRODUCT_ID/status" '{"status":"ACTIVE"}')
req "POST /api/products/$PRODUCT_ID/status" "200" "$code"

# Step 5 — CREATE ORDER
section "Step 5: Create Order"
E2E_IDEM_KEY="e2e-$(date +%s)"
ORDER_BODY=$(printf '{"items":[{"productId":%s,"quantity":2}]}' "$PRODUCT_ID")
code=$(curl -s -o /tmp/http_resp -w "%{http_code}" -X POST "$BASE_URL/orders" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: $E2E_IDEM_KEY" \
  -d "$ORDER_BODY" 2>/dev/null)
req "POST /api/orders" "201" "$code"
ORDER_ID=$(json_field "id")
echo "  Order ID: $ORDER_ID"

# Step 6 — GET ORDER
section "Step 6: Verify Order"
code=$(http GET "$BASE_URL/orders/$ORDER_ID")
req "GET /api/orders/$ORDER_ID" "200" "$code"

# Step 7 — CONFIRM ORDER
section "Step 7: Confirm Order"
code=$(http POST "$BASE_URL/orders/$ORDER_ID/status" '{"status":"CONFIRMED"}')
req "POST confirm" "200" "$code"

# Step 8 — SHIP ORDER
section "Step 8: Ship Order"
code=$(http POST "$BASE_URL/orders/$ORDER_ID/status" '{"status":"SHIPPED"}')
req "POST ship" "200" "$code"

# Step 9 — DELIVER ORDER
section "Step 9: Deliver Order"
code=$(http POST "$BASE_URL/orders/$ORDER_ID/status" '{"status":"DELIVERED"}')
req "POST deliver" "200" "$code"

# Step 10 — VERIFY DELIVERED
section "Step 10: Verify Delivered"
code=$(http GET "$BASE_URL/orders/$ORDER_ID")
req "GET final status" "200" "$code"
STATUS=$(json_field "status")
if [ "$STATUS" = "DELIVERED" ]; then
  ok "DELIVERED" "Order status is DELIVERED"
  TOTAL_OK=$((TOTAL_OK+1))
else
  err "$STATUS" "Expected DELIVERED"
  TOTAL_ERR=$((TOTAL_ERR+1))
fi

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════${NC}"
echo -e "${BOLD}  E2E Test Complete${NC}"
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════${NC}"
echo -e "  ${GREEN}Passed:${NC} $TOTAL_OK"
echo -e "  ${RED}Failed:${NC} $TOTAL_ERR"
echo ""
if [ $TOTAL_ERR -gt 0 ]; then
  echo -e "  ${RED}SOME TESTS FAILED${NC}"
  exit 1
else
  echo -e "  ${GREEN}ALL TESTS PASSED${NC}"
fi
echo -e "${BOLD}${GREEN}════════════════════════════════════════════════════${NC}"
