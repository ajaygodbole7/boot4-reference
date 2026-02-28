#!/usr/bin/env bash
# =============================================================================
# stop.sh — Stop (and optionally destroy) the boot4-reference stack
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
COMPOSE_FILE="$SCRIPT_DIR/docker-compose.yml"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
die()     { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }

DESTROY_VOLUMES=false

usage() {
  echo "Usage: $0 [--destroy]"
  echo ""
  echo "  --destroy   Stop containers AND remove all volumes (wipes all data)"
  echo "              Without this flag, volumes are preserved for the next start."
  exit 0
}

for arg in "$@"; do
  case $arg in
    --destroy) DESTROY_VOLUMES=true ;;
    --help|-h) usage ;;
    *) die "Unknown argument: $arg" ;;
  esac
done

# ── Container runtime detection ───────────────────────────────────────────────
if docker info &>/dev/null 2>&1; then
  COMPOSE_CMD="docker compose"
elif PODMAN_SOCK=$(podman machine inspect --format '{{.ConnectionInfo.PodmanSocket.Path}}' 2>/dev/null) \
     && [ -S "$PODMAN_SOCK" ]; then
  export DOCKER_HOST="unix://$PODMAN_SOCK"
  COMPOSE_CMD="podman compose"
else
  warn "No container runtime detected — attempting podman compose anyway"
  COMPOSE_CMD="podman compose"
fi

# ── Stop the Spring Boot app if running ──────────────────────────────────────
APP_PIDS=$(pgrep -f "boot4-reference.*SNAPSHOT.jar" 2>/dev/null || true)
if [ -n "$APP_PIDS" ]; then
  info "Stopping Spring Boot app (PIDs: $APP_PIDS)..."
  echo "$APP_PIDS" | xargs kill 2>/dev/null || true
  success "App stopped"
fi
# Also check PID file
if [ -f "$PROJECT_DIR/.app.pid" ]; then
  kill "$(cat "$PROJECT_DIR/.app.pid")" 2>/dev/null || true
  rm -f "$PROJECT_DIR/.app.pid"
fi

# ── Compose down ─────────────────────────────────────────────────────────────
if [ "$DESTROY_VOLUMES" = true ]; then
  warn "Destroying all volumes — all stored data will be lost!"
  $COMPOSE_CMD -f "$COMPOSE_FILE" down -v
  success "Stack stopped and volumes destroyed"
else
  info "Stopping stack (volumes preserved)..."
  $COMPOSE_CMD -f "$COMPOSE_FILE" down
  success "Stack stopped — data volumes retained for next start"
fi

echo ""
echo -e "${GREEN}Done.${NC} Re-run ${CYAN}./infra/start.sh${NC} to bring the stack back up."
