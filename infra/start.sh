#!/usr/bin/env bash
# =============================================================================
# start.sh — Start the boot4-reference observability stack
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

# ── Colour helpers ────────────────────────────────────────────────────────────
GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
die()     { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }

# ── Container runtime detection ───────────────────────────────────────────────
detect_runtime() {
  if docker info &>/dev/null 2>&1; then
    info "Container runtime: Docker"
    unset DOCKER_HOST TESTCONTAINERS_RYUK_DISABLED
    COMPOSE_CMD="docker compose"
  elif PODMAN_SOCK=$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}' 2>/dev/null) \
       && [ -S "$PODMAN_SOCK" ]; then
    info "Container runtime: Podman (socket: $PODMAN_SOCK)"
    export DOCKER_HOST="unix://$PODMAN_SOCK"
    export TESTCONTAINERS_RYUK_DISABLED=true
    COMPOSE_CMD="podman compose"
  else
    die "Neither Docker nor Podman is running. Start one and retry."
  fi
}

# ── Wait for a URL to return HTTP 200 ────────────────────────────────────────
wait_for_http() {
  local name="$1" url="$2" max_secs="${3:-60}"
  local elapsed=0
  printf "  Waiting for %s " "$name"
  while ! curl -sf "$url" -o /dev/null 2>/dev/null; do
    sleep 2; elapsed=$((elapsed+2))
    printf "."
    [ $elapsed -ge $max_secs ] && echo "" && die "$name did not become ready in ${max_secs}s"
  done
  echo ""; success "$name is ready"
}

# ── Main ──────────────────────────────────────────────────────────────────────
detect_runtime

info "Tearing down any existing stack..."
$COMPOSE_CMD -f "$COMPOSE_FILE" down --remove-orphans 2>/dev/null || true

info "Starting observability stack..."
$COMPOSE_CMD -f "$COMPOSE_FILE" up -d

echo ""
info "Waiting for services to become healthy..."
wait_for_http "Prometheus"              "http://localhost:9090/-/ready"          60
wait_for_http "Grafana"                 "http://localhost:3000/api/health"       60
wait_for_http "OTel Collector (zPages)" "http://localhost:55679/debug/tracez"    30

# Tempo takes longer to initialise the ingester
info "Waiting for Tempo ingester..."
local_elapsed=0
until curl -sf http://localhost:3200/ready -o /dev/null 2>/dev/null; do
  sleep 3; local_elapsed=$((local_elapsed+3))
  [ $local_elapsed -ge 60 ] && warn "Tempo not yet ready after 60s — continuing anyway" && break
done
success "Tempo ready (or timed out gracefully)"

echo ""
echo -e "${GREEN}═══════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  boot4-reference stack is UP${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════${NC}"
echo -e "  Grafana:         ${CYAN}http://localhost:3000${NC}  (no login required)"
echo -e "  Prometheus:      ${CYAN}http://localhost:9090${NC}"
echo -e "  Tempo:           ${CYAN}http://localhost:3200${NC}"
echo -e "  Loki:            ${CYAN}http://localhost:3100${NC}"
echo -e "  OTel Collector:  ${CYAN}http://localhost:55679/debug/tracez${NC}"
echo -e "  PostgreSQL:      ${CYAN}localhost:5432${NC}  (boot4refdb / boot4user / boot4pass)"
echo -e "  Kafka:           ${CYAN}localhost:9092${NC}"
echo ""
echo -e "  Next: start the app with  ${YELLOW}./infra/start-app.sh${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════${NC}"
