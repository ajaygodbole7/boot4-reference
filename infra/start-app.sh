#!/usr/bin/env bash
# =============================================================================
# start-app.sh — Build and start the boot4-reference Spring Boot app
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
JAR_GLOB="$PROJECT_DIR/target/boot4-reference-*-SNAPSHOT.jar"
LOG_FILE="$PROJECT_DIR/app.log"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*"; }
die()     { echo -e "${RED}[ERROR]${NC} $*" >&2; exit 1; }

# ── Java 25 ───────────────────────────────────────────────────────────────────
if command -v java &>/dev/null; then
  JAVA_BIN="java"
  JAVA_VER=$("$JAVA_BIN" -version 2>&1 | head -1 | grep -oE '"[0-9]+' | tr -d '"')
  [ "$JAVA_VER" -ge 25 ] || die "Java 25+ required, found $JAVA_VER"
elif [ -x "$(/usr/libexec/java_home -v 25 2>/dev/null)/bin/java" ]; then
  export JAVA_HOME=$(/usr/libexec/java_home -v 25)
  JAVA_BIN="$JAVA_HOME/bin/java"
else
  die "Java 25 not found. Install via: brew install --cask temurin@25"
fi
info "Java: $("$JAVA_BIN" -version 2>&1 | head -1)"

# ── Kill any existing app instance ───────────────────────────────────────────
EXISTING=$(pgrep -f "boot4-reference.*SNAPSHOT.jar" 2>/dev/null || true)
if [ -n "$EXISTING" ]; then
  warn "Stopping existing app (PID $EXISTING)..."
  echo "$EXISTING" | xargs kill 2>/dev/null || true
  sleep 2
fi

# ── Build if JAR is missing or source is newer ───────────────────────────────
SKIP_BUILD="${SKIP_BUILD:-false}"
JAR_FILE=$(ls $JAR_GLOB 2>/dev/null | head -1 || true)

if [ "$SKIP_BUILD" = "true" ] && [ -n "$JAR_FILE" ]; then
  info "Skipping build (SKIP_BUILD=true), using: $(basename "$JAR_FILE")"
else
  info "Building application (skipping tests)..."
  cd "$PROJECT_DIR"
  ./mvnw clean package -DskipTests -q 2>/dev/null || mvn clean package -DskipTests -q
  JAR_FILE=$(ls $JAR_GLOB | head -1)
  success "Built: $(basename "$JAR_FILE")"
fi

# ── Start app ─────────────────────────────────────────────────────────────────
# Boot 4 uses spring-boot-starter-opentelemetry — no Java agent needed.
# OTel export configured via application.properties (management.opentelemetry.*)
info "Starting app in background → log: $LOG_FILE"
"$JAVA_BIN" \
  -Dspring.profiles.active=dev \
  -jar "$JAR_FILE" \
  > "$LOG_FILE" 2>&1 &

APP_PID=$!
echo "$APP_PID" > "$PROJECT_DIR/.app.pid"
info "App PID $APP_PID — waiting for startup..."

# ── Wait for actuator health (management port 8081) ──────────────────────────
elapsed=0
until curl -sf http://localhost:8081/actuator/health -o /dev/null 2>/dev/null; do
  sleep 2; elapsed=$((elapsed+2))
  printf "."
  if [ $elapsed -ge 60 ]; then
    echo ""
    die "App did not start within 60s. Check $LOG_FILE"
  fi
done
echo ""

success "App is UP (PID $APP_PID)"
echo ""
echo -e "${GREEN}═══════════════════════════════════════════════════════${NC}"
echo -e "${GREEN}  boot4-reference is running${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════${NC}"
echo -e "  Health:    ${CYAN}http://localhost:8081/actuator/health${NC}"
echo -e "  Swagger:   ${CYAN}http://localhost:8080/swagger-ui.html${NC}"
echo -e "  Products:  ${CYAN}http://localhost:8080/api/products${NC}"
echo -e "  Orders:    ${CYAN}http://localhost:8080/api/orders${NC}"
echo -e "  Logs:      ${CYAN}$LOG_FILE${NC}"
echo -e "  PID file:  ${CYAN}$PROJECT_DIR/.app.pid${NC}"
echo ""
echo -e "  Next steps:"
echo -e "    Generate load:  ${YELLOW}./infra/load-test.sh${NC}"
echo -e "    E2E test:       ${YELLOW}./infra/e2e-test.sh${NC}"
echo -e "    Grafana:        ${CYAN}http://localhost:3000${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════${NC}"
