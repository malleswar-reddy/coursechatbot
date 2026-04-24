#!/bin/bash
# run_ingest_all.sh — Ingest all 11 remaining courses into ChromaDB
# Usage: bash run_ingest_all.sh

set -e

PYTHON="/Users/malleswar/IdeaProjects/coursechatbot/.venv/bin/python3"
SCRIPT="/Users/malleswar/IdeaProjects/coursechatbot/pageindex/ingest_to_chroma.py"
DOC="/Users/malleswar/IdeaProjects/coursechatbot/.github/Doc"
CHROMA="http://100.114.88.111:8001"
OLLAMA="http://100.114.88.111:11434"

ingest() {
  local filename="$1" course_id="$2" branch="$3" subject="$4" title="$5"
  echo ""
  echo "════════════════════════════════════════════════════════════"
  echo "  Ingesting: $course_id"
  echo "  File     : $filename"
  echo "════════════════════════════════════════════════════════════"
  "$PYTHON" "$SCRIPT" \
    --input "$DOC/$filename" \
    --course-id "$course_id" \
    --branch "$branch" \
    --subject "$subject" \
    --title "$title" \
    --chroma-url "$CHROMA" \
    --ollama-url "$OLLAMA"
  echo "  ✅ Done: $course_id"
}

echo "Starting batch ingest of 11 courses..."
echo "Chroma : $CHROMA"
echo "Ollama : $OLLAMA"

ingest "CSE PQB 1.pdf"                       cse-pqb-1        CSE      "Previous Question Bank"       "Computer Science PQB - Part 1"
ingest "CSE PQB 2.pdf"                       cse-pqb-2        CSE      "Previous Question Bank"       "Computer Science PQB - Part 2"
ingest "ECE PQB 1.pdf"                       ece-pqb-1        ECE      "Previous Question Bank"       "Electronics and Communication PQB - Part 1"
ingest "ECE PQB 2.pdf"                       ece-pqb-2        ECE      "Previous Question Bank"       "Electronics and Communication PQB - Part 2"
ingest "EEE PQB.pdf"                         eee-pqb-1        EEE      "Previous Question Bank"       "Electrical Engineering PQB"
ingest "DA- AIML 2025 Question.pdf"          da-aiml-2025-q   DA-AIML  "2025 Exam Questions"          "DA-AIML 2025 Question Paper"
ingest "DA -AIML 2025 Key & Solutions.pdf"   da-aiml-2025-ans DA-AIML  "2025 Answer Key"              "DA-AIML 2025 Answer Key and Solutions"
ingest "CSE 2025 Set 1 Question.pdf"         cse-2025-set1-q  CSE      "2025 Exam Questions Set 1"    "CSE 2025 Set-1 Question Paper"
ingest "CSE 2025 Set 1 Key & Solutions.pdf"  cse-2025-set1-ans CSE     "2025 Answer Key Set 1"        "CSE 2025 Set-1 Answer Key"
ingest "CSE 2025 Set 2 Question.pdf"         cse-2025-set2-q  CSE      "2025 Exam Questions Set 2"    "CSE 2025 Set-2 Question Paper"
ingest "CSE 2025 Set 2 key & Solutions.pdf"  cse-2025-set2-ans CSE     "2025 Answer Key Set 2"        "CSE 2025 Set-2 Answer Key"

echo ""
echo "════════════════════════════════════════════════════════════"
echo "  🎉 All 11 courses ingested successfully!"
echo "════════════════════════════════════════════════════════════"

