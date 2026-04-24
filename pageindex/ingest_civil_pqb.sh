#!/bin/bash
# ingest_civil_pqb.sh — Quick script to ingest CIVIL PQB into ChromaDB + PostgreSQL
# Run this on the Dell server (where the PDFs are accessible) or locally via Tailscale.
#
# Usage:
#   chmod +x ingest_civil_pqb.sh
#   ./ingest_civil_pqb.sh
#
# Prerequisites:
#   pip install pdfplumber psycopg2-binary requests

set -e

REMOTE_HOST="100.114.88.111"
CHROMA_URL="http://${REMOTE_HOST}:8001"
OLLAMA_URL="http://${REMOTE_HOST}:11434"
DB_URL="postgresql://chatbot:chatbot_secret@${REMOTE_HOST}:5432/coursechatbot"
DOC_DIR="$(dirname "$0")/../.github/Doc"
SCRIPT_DIR="$(dirname "$0")"

echo "========================================"
echo "  CIVIL PQB Ingest Script"
echo "  ChromaDB : $CHROMA_URL"
echo "  Ollama   : $OLLAMA_URL"
echo "========================================"

# Verify ChromaDB is reachable
echo ""
echo "[PRE-CHECK] Pinging ChromaDB..."
curl -sf "$CHROMA_URL/api/v2/tenants/default_tenant/databases/default_database/collections" > /dev/null \
  && echo "  ✅ ChromaDB reachable" \
  || { echo "  ❌ ChromaDB not reachable at $CHROMA_URL"; exit 1; }

# Verify Ollama is reachable
echo "[PRE-CHECK] Pinging Ollama..."
curl -sf "$OLLAMA_URL/api/tags" > /dev/null \
  && echo "  ✅ Ollama reachable" \
  || { echo "  ❌ Ollama not reachable at $OLLAMA_URL"; exit 1; }

echo ""

# ── Step 1: course_manager.py add (PostgreSQL + local JSON) ──────────────────
echo "========================================"
echo "  STEP 1/2 — PostgreSQL + JSON Index"
echo "========================================"
python3 "$SCRIPT_DIR/course_manager.py" add \
    --input      "$DOC_DIR/CIVIL PQB.pdf" \
    --course-id  civil-pqb-1 \
    --branch     CIVIL \
    --subject    "Previous Question Bank" \
    --ollama-url "$OLLAMA_URL" \
    --db-url     "$DB_URL"

echo ""

# ── Step 2: ingest_to_chroma.py (ChromaDB vector store) ──────────────────────
echo "========================================"
echo "  STEP 2/2 — ChromaDB Vector Ingest"
echo "========================================"
python3 "$SCRIPT_DIR/ingest_to_chroma.py" \
    --input      "$DOC_DIR/CIVIL PQB.pdf" \
    --course-id  civil-pqb-1 \
    --chroma-url "$CHROMA_URL" \
    --ollama-url "$OLLAMA_URL"

echo ""
echo "========================================"
echo "  ✅ CIVIL PQB Ingest Complete!"
echo "  Collection: civil-pqb-1"
echo ""
echo "  Verify with:"
echo "    curl $CHROMA_URL/api/v2/tenants/default_tenant/databases/default_database/collections"
echo "========================================"

