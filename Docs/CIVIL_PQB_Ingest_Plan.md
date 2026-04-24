# CIVIL PQB — Ingestion Plan & System Analysis
> Date: April 24, 2026  
> Status: **Pre-implementation documentation**

---

## 1. Problem Statement

### Error Observed
```
⚠️ Error: courseId: courseId must not be blank
```

### Root Cause Chain
```
ChromaDB has 0 collections
  → GET /api/courses returns []
    → Frontend CourseGroupSelector shows "Loading…"
      → courseId state stays "" (empty string)
        → POST /api/chat → Spring @NotBlank validation fails
          → "courseId must not be blank"
```

**The real fix is not code — it is ingesting data.**

---

## 2. Available Source PDFs

Location: `/coursechatbot/.github/Doc/`

| File | Type | Branch | Suggested course-id |
|---|---|---|---|
| `CIVIL PQB.pdf` | Previous Question Bank | CIVIL | `civil-pqb-1` |
| `CSE PQB 1.pdf` | Previous Question Bank | CSE | `cse-pqb-1` |
| `CSE PQB 2.pdf` | Previous Question Bank | CSE | `cse-pqb-2` |
| `ECE PQB 1.pdf` | Previous Question Bank | ECE | `ece-pqb-1` |
| `ECE PQB 2.pdf` | Previous Question Bank | ECE | `ece-pqb-2` |
| `EEE PQB.pdf` | Previous Question Bank | EEE | `eee-pqb-1` |
| `DA- AIML 2025 Question.pdf` | Exam Paper | DA-AIML | `da-aiml-2025-q` |
| `DA -AIML 2025 Key & Solutions.pdf` | Answer Key | DA-AIML | `da-aiml-2025-ans` |
| `CSE 2025 Set 1 Question.pdf` | Exam Paper | CSE | `cse-2025-set1-q` |
| `CSE 2025 Set 1 Key & Solutions.pdf` | Answer Key | CSE | `cse-2025-set1-ans` |
| `CSE 2025 Set 2 Question.pdf` | Exam Paper | CSE | `cse-2025-set2-q` |
| `CSE 2025 Set 2 key & Solutions.pdf` | Answer Key | CSE | `cse-2025-set2-ans` |

---

## 3. CIVIL PQB.pdf — Document Structure Analysis

Based on the pattern established in `CSE PQB 1.pdf` (which is already indexed as `cse-pqb-1_index.json`):

### Format
- **Type**: Previous Question Bank (PQB) — GATE / university MCQ style
- **Structure**: Organised by topic chapters
- **Each page**: Contains 4–10 numbered MCQ questions with 4-option answers (a/b/c/d)
- **Trailing pages**: Answers section + detailed explanations for each question
- **Year tagging**: Each question tagged with exam year and marks (e.g., `[2022: 2 Marks]`)

### Typical CIVIL Engineering Topics Covered
- Strength of Materials (SOM)
- Fluid Mechanics
- Soil Mechanics & Foundation Engineering
- Structural Analysis
- RCC & Steel Design
- Transportation Engineering
- Environmental Engineering
- Surveying & Geomatics
- Engineering Mathematics

### How the LLM Uses This
1. Student asks: *"Explain Rankine's theory of earth pressure"*
2. ChromaDB vector search retrieves relevant MCQ chunks (Soil Mechanics chapter)
3. LLM reads the question + explanation text as context
4. In LEARN mode → full structured explanation with concept/formula/example
5. In EXAM mode → hint only, no direct answer

---

## 4. How the RAG Pipeline Works (End-to-End)

```
PDF File (.github/Doc/CIVIL PQB.pdf)
    │
    ▼  [ingest_to_chroma.py / course_manager.py add]
    │
    ├─► pdfplumber extracts text page-by-page
    │
    ├─► Text split into 600-char chunks with 100-char overlap
    │
    ├─► Each chunk → Ollama nomic-embed-text → 768-dim vector
    │
    ├─► Vectors + text stored in ChromaDB collection "civil-pqb-1"
    │       Collection metadata: { "hnsw:space": "cosine" }
    │       Each chunk metadata: { "page": <page_number> }
    │
    └─► Page text + TOC stored in PostgreSQL:
            course_index  (course_id, index_json, branch, subject, title)
            course_content (course_id, page_number, content)

User asks question in browser
    │
    ▼  [POST /api/chat]
    │
    ├─► VectorRagService.embedQuestion()
    │       → Ollama /api/embeddings → 768-dim vector
    │
    ├─► VectorRagService.queryChroma()
    │       → ChromaDB /collections/civil-pqb-1/query
    │       → Returns top-3 matching text chunks
    │
    ├─► PromptBuilderService.buildSystemPrompt(mode, title, difficulty)
    │
    └─► ChatModel.chat(systemPrompt, userPrompt)
            → Ollama LLM (gemma3:4b or qwen2.5:0.5b)
            → Returns structured answer
```

---

## 5. DB Schema (No Restructuring Needed)

The current schema (Flyway V1→V3) is already complete:

```sql
-- V1: Core tables
course_index    (course_id PK, index_json)
course_content  (id, course_id, page_number, content)  -- unique (course_id, page_number)

-- V2: Metadata columns
course_index    ← title, description, created_at, updated_at

-- V3: Branch / subject + session tracking
course_index    ← branch VARCHAR(50), subject VARCHAR(100)
chat_session    (session_id UUID, course_id, mode, difficulty, ...)
exam_performance (session_id FK, question, response_type, concept_tag, ...)
```

**✅ No migration needed.** `course_manager.py add` already writes to all these columns.

---

## 6. Data Already Indexed (Local JSON)

```
pageindex/courses/
├── cse-pqb-1_index.json   ← CSE PQB 1 (11 pages, Searching & Hashing chapter only)
└── java-tutorial-6e_index.json
```

> **Note**: `cse-pqb-1_index.json` exists locally but may NOT be in ChromaDB/PostgreSQL yet.
> Run the migration to push it to both stores.

---

## 7. Pre-requisites

### Remote Server (100.114.88.111)
| Service | Port | Status Check |
|---|---|---|
| ChromaDB | 8001 | `curl http://100.114.88.111:8001/api/v2/tenants/default_tenant/databases/default_database/collections` |
| PostgreSQL | 5432 | `psql postgresql://chatbot:chatbot_secret@100.114.88.111:5432/coursechatbot` |
| Ollama | 11434 | `curl http://100.114.88.111:11434/api/tags` |

### Python Dependencies (pageindex/.venv)
```bash
cd pageindex
python3 -m venv .venv
source .venv/bin/activate
pip install pdfplumber psycopg2-binary requests
```

---

## 8. Ingest Commands — Step by Step

### Option A: course_manager.py add (recommended — does PostgreSQL + local JSON)

```bash
cd /Users/malleswar/IdeaProjects/coursechatbot/pageindex
source .venv/bin/activate

# CIVIL PQB (PRIMARY GOAL)
python3 course_manager.py add \
  --input "../.github/Doc/CIVIL PQB.pdf" \
  --course-id civil-pqb-1 \
  --branch CIVIL \
  --subject "Previous Question Bank" \
  --ollama-url http://100.114.88.111:11434 \
  --db-url "postgresql://chatbot:chatbot_secret@100.114.88.111:5432/coursechatbot"

# Then push to ChromaDB (vector search)
python3 ingest_to_chroma.py \
  --input "../.github/Doc/CIVIL PQB.pdf" \
  --course-id civil-pqb-1 \
  --chroma-url http://100.114.88.111:8001 \
  --ollama-url http://100.114.88.111:11434
```

### Option B: Batch ingest all PDFs
```bash
# Use the batch script created in pageindex/
python3 batch_ingest.py --chroma-url http://100.114.88.111:8001 \
                        --ollama-url http://100.114.88.111:11434 \
                        --db-url "postgresql://chatbot:chatbot_secret@100.114.88.111:5432/coursechatbot"
```

---

## 9. Verify After Ingest

```bash
# Check ChromaDB has collections
python3 /Users/malleswar/IdeaProjects/coursechatbot/.github/test_chroma.py

# Check PostgreSQL has course data
psql postgresql://chatbot:chatbot_secret@100.114.88.111:5432/coursechatbot \
  -c "SELECT course_id, branch, subject, title FROM course_index ORDER BY course_id;"

# Test backend API (after ingest)
curl http://localhost:8080/api/courses | python3 -m json.tool

# Test a chat
curl -s -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"courseId":"civil-pqb-1","question":"Explain Rankine theory of earth pressure","mode":"LEARN","difficultyLevel":"INTERMEDIATE"}' \
  | python3 -m json.tool
```

---

## 10. Backend Error in Error.log (Secondary Issue)

The error log shows:
```
No qualifying bean of type 'WebClient$Builder' available
```

**Status**: FIXED in current code. `OllamaConfig.java` already has:
```java
@Bean
public WebClient.Builder webClientBuilder() {
    return WebClient.builder();
}
```
The error log was from an **old Docker image** (pre-fix build). Rebuild the container to apply.

```bash
# Rebuild backend
cd /Users/malleswar/IdeaProjects/coursechatbot
docker compose build backend
docker compose up -d backend
```

---

## 11. Batch Ingest Plan — All 12 PDFs

After confirming CIVIL PQB works, ingest all files:

| Priority | course-id | branch | Subject |
|---|---|---|---|
| 🔴 HIGH | `civil-pqb-1` | CIVIL | Previous Question Bank |
| 🔴 HIGH | `cse-pqb-1` | CSE | Previous Question Bank |
| 🔴 HIGH | `cse-pqb-2` | CSE | Previous Question Bank |
| 🟡 MED | `ece-pqb-1` | ECE | Previous Question Bank |
| 🟡 MED | `ece-pqb-2` | ECE | Previous Question Bank |
| 🟡 MED | `eee-pqb-1` | EEE | Previous Question Bank |
| 🟢 LOW | `da-aiml-2025-q` | DA-AIML | 2025 Exam Questions |
| 🟢 LOW | `da-aiml-2025-ans` | DA-AIML | 2025 Answer Key |
| 🟢 LOW | `cse-2025-set1-q` | CSE | 2025 Set-1 Questions |
| 🟢 LOW | `cse-2025-set1-ans` | CSE | 2025 Set-1 Answer Key |
| 🟢 LOW | `cse-2025-set2-q` | CSE | 2025 Set-2 Questions |
| 🟢 LOW | `cse-2025-set2-ans` | CSE | 2025 Set-2 Answer Key |

---

## 12. Expected Frontend Behaviour After Ingest

1. Browser opens ExamPrep AI
2. `GET /api/courses` → ChromaDB collections list → courses dropdown populated
3. Grouped by branch: `CSE`, `ECE`, `EEE`, `CIVIL`, `DA-AIML`
4. User selects "CIVIL — Previous Question Bank"
5. Sends question → `POST /api/chat` with `courseId=civil-pqb-1`
6. Backend embeds question → ChromaDB vector search → top-3 chunks → LLM answer

---

## 13. File Paths Reference

| Purpose | Path |
|---|---|
| Source PDFs | `.github/Doc/*.pdf` |
| Ingest script (ChromaDB) | `pageindex/ingest_to_chroma.py` |
| Ingest + PostgreSQL | `pageindex/course_manager.py` |
| Batch ingest script | `pageindex/batch_ingest.py` (new) |
| Local index JSONs | `pageindex/courses/*.json` |
| DB migrations | `backend/src/main/resources/db/migration/V*.sql` |
| Backend config | `backend/src/main/resources/application.properties` |
| WebClient bean fix | `backend/src/main/java/.../config/OllamaConfig.java` |

---

*Document created: April 24, 2026*  
*Author: GitHub Copilot (automated analysis)*

