#!/bin/bash
# =============================================================================
# deploy-server.sh — Run ON the server to build + deploy all services
# Called by Jenkins via SSH after rsync
# =============================================================================
set -euo pipefail

# ── Config (can be overridden via env vars from Jenkins) ─────────────────────
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

# ── Step 1: Postgres ──────────────────────────────────────────────────────────
echo "[1/5] 🗄️  Database..."

if [ "$RESET_DB" = "true" ]; then
  echo "  ⚠️  RESET_DB=true → removing postgres volume (data will be wiped)"
  docker compose -f "$COMPOSE_FILE" stop postgres  2>/dev/null || true
  docker compose -f "$COMPOSE_FILE" rm  -f postgres 2>/dev/null || true
  docker volume rm chatbot_postgres_data 2>/dev/null || true
  echo "  Volume removed."
fi

docker compose -f "$COMPOSE_FILE" up -d postgres

echo "  ⏳ Waiting for postgres to become healthy..."
RETRIES=30
COUNT=0
until docker inspect --format='{{.State.Health.Status}}' coursechatbot-postgres 2>/dev/null | grep -q healthy; do
  COUNT=$((COUNT + 1))
  if [ "$COUNT" -ge "$RETRIES" ]; then
    echo "  ❌ Postgres did not become healthy in time."
    docker logs coursechatbot-postgres --tail 50
    exit 1
  fi
  echo "  Waiting... ($COUNT/$RETRIES)"
  sleep 5
done
echo "  ✅ Postgres is healthy."

# ── Step 2: Ollama ────────────────────────────────────────────────────────────
echo ""
echo "[2/5] 🤖  Ollama..."
docker compose -f "$COMPOSE_FILE" up -d ollama
echo "  ✅ Ollama started."

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

# ── Step 5: Deploy all services ───────────────────────────────────────────────
echo ""
echo "[5/5] 🚀  Deploy all services..."
OLLAMA_MODEL="$OLLAMA_MODEL" \
NEXT_PUBLIC_API_URL="$NEXT_PUBLIC_API_URL" \
docker compose -f "$COMPOSE_FILE" up -d --remove-orphans

echo ""
echo "============================================================"
echo "  Container status:"
docker compose -f "$COMPOSE_FILE" ps
echo ""

# ── Health check URLs ─────────────────────────────────────────────────────────
echo "  Waiting 20s for services to stabilize..."
sleep 20

echo ""
echo "  🔍 Backend health:"
curl -sf http://localhost:8080/api/courses/courseIds \
  && echo "  ✅ /api/courses/courseIds → OK" \
  || echo "  ⚠️  /api/courses/courseIds → not responding yet (check logs)"

echo ""
echo "  🔍 Frontend health:"
curl -sf http://localhost:3000/ -o /dev/null -w "  HTTP %{http_code}\n" \
  && echo "  ✅ Frontend → OK" \
  || echo "  ⚠️  Frontend → not responding yet (check logs)"

echo ""
echo "============================================================"
echo "  ✅  DEPLOY COMPLETE"
echo "  Backend   : http://100.114.88.111:8080"
echo "  Frontend  : http://100.114.88.111:3000"
echo "  Open WebUI: http://100.114.88.111:3001"
echo "  Ollama    : http://100.114.88.111:11434"
echo "  DB host   : 100.114.88.111:5432"
echo "============================================================"

