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
OLLAMA_MODEL="${OLLAMA_MODEL:-gemma3:4b}"
NEXT_PUBLIC_API_URL="${NEXT_PUBLIC_API_URL:-https://hirevitae-chatbotapi.chakritech.org}"

cd "$SERVER_DIR"
echo ""
echo "============================================================"
echo "  CourseChatbot — Server Deploy"
echo "  Dir         : $SERVER_DIR"
echo "  Compose     : $COMPOSE_FILE"
echo "  Build BE    : $BUILD_BACKEND"
echo "  Build FE    : $BUILD_FRONTEND"
echo "  Ollama model: $OLLAMA_MODEL"
echo "  Embed model : ${OLLAMA_EMBED_MODEL:-nomic-embed-text}"
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

# ── Step 1: ChromaDB ─────────────────────────────────────────────────────────
echo "[1/4] 🗂️  ChromaDB..."

if container_running "coursechatbot-chromadb" && container_healthy "coursechatbot-chromadb"; then
    echo "  ℹ️  ChromaDB already running and healthy — skipping."
else
    echo "  Starting/restarting ChromaDB..."
    docker compose -f "$COMPOSE_FILE" up -d chromadb
    # Wait up to 30s for ChromaDB to become healthy
    for i in $(seq 1 6); do
        sleep 5
        if curl -sf http://localhost:8001/api/v2/heartbeat > /dev/null 2>&1; then
            echo "  ✅ ChromaDB is up."
            break
        fi
        echo "  ⏳ Waiting for ChromaDB... ($((i*5))s)"
    done
fi

# ── Step 2: Ollama ────────────────────────────────────────────────────────────
echo ""
echo "[2/4] 🤖  Ollama..."

if container_running "coursechatbot-ollama"; then
    echo "  ℹ️  Ollama already running — skipping start."
else
    echo "  Starting Ollama..."
    docker compose -f "$COMPOSE_FILE" up -d ollama
    sleep 5
fi

# Check model — only pull if not already installed (avoids re-downloading GBs every deploy)
MODEL_NAME="${OLLAMA_MODEL%%:*}"
echo "  Checking model '$OLLAMA_MODEL' ..."
if docker exec coursechatbot-ollama ollama list 2>/dev/null | grep -q "^${MODEL_NAME}"; then
    echo "  ✅ Model '$OLLAMA_MODEL' already installed — skipping pull."
else
    echo "  📥 Pulling model '$OLLAMA_MODEL' (first time only) ..."
    docker exec coursechatbot-ollama ollama pull "$OLLAMA_MODEL"
    echo "  ✅ Model '$OLLAMA_MODEL' ready."
fi

# Check embedding model — same logic
EMBED_MODEL="${OLLAMA_EMBED_MODEL:-nomic-embed-text}"
EMBED_NAME="${EMBED_MODEL%%:*}"
echo "  Checking embedding model '$EMBED_MODEL' ..."
if docker exec coursechatbot-ollama ollama list 2>/dev/null | grep -q "^${EMBED_NAME}"; then
    echo "  ✅ Embedding model '$EMBED_MODEL' already installed — skipping pull."
else
    echo "  📥 Pulling embedding model '$EMBED_MODEL' (first time only) ..."
    docker exec coursechatbot-ollama ollama pull "$EMBED_MODEL"
    echo "  ✅ Embedding model '$EMBED_MODEL' ready."
fi
echo "  ✅ Ollama is up."

# ── Step 3: Build Backend ─────────────────────────────────────────────────────
echo ""
echo "[3/4] 🏗️   Build Backend..."
if [ "$BUILD_BACKEND" = "true" ]; then
    DOCKER_BUILDKIT=1 docker compose -f "$COMPOSE_FILE" build backend
    echo "  ✅ Backend image built."
else
    echo "  ⏩ Skipped (BUILD_BACKEND=false)."
fi

# ── Step 4: Build Frontend ────────────────────────────────────────────────────
echo ""
echo "[4/4] 🎨  Build Frontend..."
if [ "$BUILD_FRONTEND" = "true" ]; then
    DOCKER_BUILDKIT=1 \
    NEXT_PUBLIC_API_URL="$NEXT_PUBLIC_API_URL" \
    docker compose -f "$COMPOSE_FILE" build frontend
    echo "  ✅ Frontend image built."
else
    echo "  ⏩ Skipped (BUILD_FRONTEND=false)."
fi

# ── Clean up orphaned Postgres (no longer used — ChromaDB replaced it) ───────
if container_running "coursechatbot-postgres"; then
    echo "  🧹 Stopping orphaned coursechatbot-postgres (no longer needed)..."
    docker stop coursechatbot-postgres 2>/dev/null || true
    docker rm   coursechatbot-postgres 2>/dev/null || true
    echo "  ✅ Postgres removed."
fi

# ── Deploy backend + frontend ─────────────────────────────────────────────────
echo ""
echo "🚀  Deploying backend + frontend..."

# Free ports 8000 and 3000 before starting new containers
OLD_8000=$(docker ps -q --filter "publish=8000")
if [ -n "$OLD_8000" ]; then
    echo "  ⚠️  Port 8000 in use — stopping old container"
    docker stop "$OLD_8000" 2>/dev/null || true
    docker rm   "$OLD_8000" 2>/dev/null || true
fi

OLD_3000=$(docker ps -q --filter "publish=3000")
if [ -n "$OLD_3000" ]; then
    echo "  ⚠️  Port 3000 in use — stopping old container"
    docker stop "$OLD_3000" 2>/dev/null || true
    docker rm   "$OLD_3000" 2>/dev/null || true
fi

docker stop coursechatbot-backend  2>/dev/null || true
docker rm   coursechatbot-backend  2>/dev/null || true
docker stop coursechatbot-frontend 2>/dev/null || true
docker rm   coursechatbot-frontend 2>/dev/null || true

OLLAMA_MODEL="$OLLAMA_MODEL" \
OLLAMA_EMBED_MODEL="$EMBED_MODEL" \
NEXT_PUBLIC_API_URL="$NEXT_PUBLIC_API_URL" \
docker compose -f "$COMPOSE_FILE" up -d --no-deps backend frontend

echo ""
echo "============================================================"
echo "  Container status:"
docker ps --filter "name=coursechatbot" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"
echo ""

echo "  Waiting 20s for services to stabilize..."
sleep 20

echo ""
echo "  🔍 Backend health:"
curl -sf http://localhost:8000/actuator/health \
    && echo "  ✅ Backend → OK" \
    || echo "  ⚠️  Backend not responding yet"

echo ""
echo "  🔍 ChromaDB health:"
curl -sf http://localhost:8001/api/v2/heartbeat \
    && echo "  ✅ ChromaDB → OK" \
    || echo "  ⚠️  ChromaDB not responding yet"

echo ""
echo "============================================================"
echo "  ✅  DEPLOY COMPLETE"
echo "  Frontend  (tunnel) : https://hirevitae-chatbot.chakritech.org"
echo "  Backend   (tunnel) : https://hirevitae-chatbotapi.chakritech.org"
echo "  Backend   (direct) : http://100.114.88.111:8000"
echo "  Frontend  (direct) : http://100.114.88.111:3000"
echo "  ChromaDB  (direct) : http://100.114.88.111:8001"
echo "  Ollama    (direct) : http://100.114.88.111:11434  model: $OLLAMA_MODEL"
echo "============================================================"

