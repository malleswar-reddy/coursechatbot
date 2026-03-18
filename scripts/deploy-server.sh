#!/bin/bash
# =============================================================================
# deploy-server.sh — Build + deploy all services
# Called by Jenkins (LOCAL mode) or via SSH (REMOTE mode)
# =============================================================================
set -euo pipefail

# ── Config (overridable via env vars from Jenkins) ────────────────────────────
SERVER_DIR="${SERVER_DIR:-/home/dell/chatbot}"
COMPOSE_FILE="${COMPOSE_FILE:-docker-compose.server.yml}"
BUILD_BACKEND="${BUILD_BACKEND:-true}"
BUILD_FRONTEND="${BUILD_FRONTEND:-true}"
RESET_DB="${RESET_DB:-false}"
OLLAMA_MODEL="${OLLAMA_MODEL:-qwen2.5:0.5b}"
NEXT_PUBLIC_API_URL="${NEXT_PUBLIC_API_URL:-http://100.114.88.111:8080}"

cd "$SERVER_DIR"
echo ""
echo "============================================================"
echo "  CourseChatbot — Server Deploy"
echo "  Dir         : $SERVER_DIR"
echo "  Compose     : $COMPOSE_FILE"
echo "  Build BE    : $BUILD_BACKEND"
echo "  Build FE    : $BUILD_FRONTEND"
echo "  Reset DB    : $RESET_DB"
echo "  Ollama model: $OLLAMA_MODEL"
echo "  API URL     : $NEXT_PUBLIC_API_URL"
echo "============================================================"
echo ""

# ── Helper: check if a container is already running ───────────────────────────
container_running() {
    docker inspect --format='{{.State.Status}}' "$1" 2>/dev/null | grep -q "running"
}

container_healthy() {
    docker inspect --format='{{.State.Health.Status}}' "$1" 2>/dev/null | grep -q "healthy"
}

# ── Step 1: PostgreSQL ────────────────────────────────────────────────────────
echo "[1/5] 🗄️  Database..."

if [ "$RESET_DB" = "true" ]; then
    echo "  ⚠️  RESET_DB=true → removing postgres volume (data will be wiped)"
    docker stop coursechatbot-postgres 2>/dev/null || true
    docker rm   coursechatbot-postgres 2>/dev/null || true
    docker volume rm chatbot_postgres_data 2>/dev/null || true
fi

if container_running "coursechatbot-postgres"; then
    echo "  ℹ️  PostgreSQL already running — skipping start (container started externally)"
else
    echo "  Starting PostgreSQL..."
    docker compose -f "$COMPOSE_FILE" up -d postgres
fi

echo "  ⏳ Waiting for PostgreSQL to be healthy..."
RETRIES=30
COUNT=0
until container_healthy "coursechatbot-postgres"; do
    COUNT=$((COUNT + 1))
    if [ "$COUNT" -ge "$RETRIES" ]; then
        echo "  ❌ PostgreSQL did not become healthy in time."
        docker logs coursechatbot-postgres --tail 50
        exit 1
    fi
    echo "  Waiting... ($COUNT/$RETRIES)"
    sleep 5
done
echo "  ✅ PostgreSQL is healthy."

# ── Step 2: Ollama ────────────────────────────────────────────────────────────
echo ""
echo "[2/5] 🤖  Ollama..."

if container_running "coursechatbot-ollama"; then
    echo "  ℹ️  Ollama already running — skipping start"
else
    echo "  Starting Ollama..."
    docker compose -f "$COMPOSE_FILE" up -d ollama
fi
echo "  ✅ Ollama is up."

# ── Step 3: Build Backend ─────────────────────────────────────────────────────
echo ""
echo "[3/5] 🏗️   Build Backend..."
if [ "$BUILD_BACKEND" = "true" ]; then
    DOCKER_BUILDKIT=1 docker compose -f "$COMPOSE_FILE" build backend
    echo "  ✅ Backend image built."
else
    echo "  ⏩ Skipped (BUILD_BACKEND=false)."
fi

# ── Step 4: Build Frontend ────────────────────────────────────────────────────
echo ""
echo "[4/5] 🎨  Build Frontend..."
if [ "$BUILD_FRONTEND" = "true" ]; then
    DOCKER_BUILDKIT=1 \
    NEXT_PUBLIC_API_URL="$NEXT_PUBLIC_API_URL" \
    docker compose -f "$COMPOSE_FILE" build frontend
    echo "  ✅ Frontend image built."
else
    echo "  ⏩ Skipped (BUILD_FRONTEND=false)."
fi

# ── Step 5: Deploy backend + frontend only ────────────────────────────────────
# postgres + ollama + open-webui are already running under a DIFFERENT compose
# project (/home/dell/chatbot). --no-deps skips depends_on so those containers
# are never touched (avoids "container name already in use" conflict).
echo ""
echo "[5/5] 🚀  Deploy backend + frontend..."

# ── Free ports 8080 and 3000 before starting new containers ──────────────────
echo "  Freeing ports..."

# Stop + remove any container binding port 8080
OLD_8080=$(docker ps -q --filter "publish=8080")
if [ -n "$OLD_8080" ]; then
    echo "  ⚠️  Port 8080 in use by container $OLD_8080 — stopping it"
    docker stop "$OLD_8080" 2>/dev/null || true
    docker rm   "$OLD_8080" 2>/dev/null || true
fi

# Stop + remove any container binding port 3000
OLD_3000=$(docker ps -q --filter "publish=3000")
if [ -n "$OLD_3000" ]; then
    echo "  ⚠️  Port 3000 in use by container $OLD_3000 — stopping it"
    docker stop "$OLD_3000" 2>/dev/null || true
    docker rm   "$OLD_3000" 2>/dev/null || true
fi

# Remove old named containers by name (handles stopped-but-not-removed state)
docker stop coursechatbot-backend  2>/dev/null || true
docker rm   coursechatbot-backend  2>/dev/null || true
docker stop coursechatbot-frontend 2>/dev/null || true
docker rm   coursechatbot-frontend 2>/dev/null || true

echo "  ✅ Ports 8080 and 3000 are free."

# ── Start backend + frontend with new images ──────────────────────────────────
OLLAMA_MODEL="$OLLAMA_MODEL" \
NEXT_PUBLIC_API_URL="$NEXT_PUBLIC_API_URL" \
docker compose -f "$COMPOSE_FILE" up -d --no-deps backend frontend

echo ""
echo "============================================================"
echo "  Container status:"
docker ps --filter "name=coursechatbot" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
echo ""

# ── Health check ──────────────────────────────────────────────────────────────
echo "  Waiting 20s for services to stabilize..."
sleep 20

echo ""
echo "  🔍 Backend:"
curl -sf http://localhost:8080/api/courses/courseIds \
    && echo "  ✅ /api/courses/courseIds → OK" \
    || echo "  ⚠️  /api/courses/courseIds → not responding yet"

echo ""
echo "  🔍 Frontend:"
curl -sf http://localhost:3000/ -o /dev/null -w "  HTTP %{http_code}\n" \
    && echo "  ✅ Frontend → OK" \
    || echo "  ⚠️  Frontend → not responding yet"

echo ""
echo "============================================================"
echo "  ✅  DEPLOY COMPLETE"
echo "  Backend   : http://100.114.88.111:8080"
echo "  Frontend  : http://100.114.88.111:3000"
echo "  Open WebUI: http://100.114.88.111:3001"
echo "  Ollama    : http://100.114.88.111:11434"
echo "  DB host   : 100.114.88.111:5432"
echo "============================================================"

