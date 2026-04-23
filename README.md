# 📚 Course Chatbot — PageIndex RAG for LMS

A **vectorless RAG** chatbot for a Learning Management System (LMS).  
Instead of vector embeddings, it uses **PageIndex** — a hierarchical tree index over course PDFs — and lets the LLM reason over the document structure to find the right pages.

```
Students
   │
Next.js LMS (port 3000)
   │
Spring Boot API (port 8080)
   │
PageIndex Engine (Python)
   │
PostgreSQL (page text)
   │
Ollama (Qwen model, port 11434)
```

---

## 🧠 Why PageIndex instead of Vector RAG?

| Feature | Vector RAG | PageIndex |
|---|---|---|
| Embedding model required | ✅ Yes | ❌ No |
| Vector database (Milvus/FAISS) | ✅ Yes | ❌ No |
| RAM (typical) | 8–12 GB | ~2–4 GB |
| Explainability | Low | High (shows pages) |
| Works with large PDFs | Moderate | ✅ Excellent |

PageIndex mirrors how humans search in books: using the **table of contents** rather than full-text similarity.

---

## 🚀 Quick Start

### Prerequisites
- Docker & Docker Compose
- Python 3.10+ (for the PageIndex scripts)
- Java 21 (for local backend development)

### Step 1 — Clone and start infrastructure

```bash
git clone https://github.com/malleswar-reddy/coursechatbot
cd coursechatbot
docker compose up -d
```

This starts:
- **Ollama** on `localhost:11434`
- **Open WebUI** on `localhost:3001`
- **PostgreSQL** on `localhost:5432`
- **Spring Boot backend** on `localhost:8080`
- **Next.js frontend** on `localhost:3000`

### Step 2 — Pull the LLM model

```bash
docker exec -it coursechatbot-ollama ollama pull qwen2.5:7b
```

> For CPU-only machines you can use `qwen2.5:4b` — update `OLLAMA_MODEL` in `docker-compose.yml`.

### Step 3 — Index a course PDF

Install Python dependencies:

```bash
cd pageindex
pip install -r requirements.txt
```

Build the index from a PDF:

```bash
python build_index.py --pdf /path/to/course.pdf --output course_index.json
```

This produces `course_index.json`:
```json
{
  "title": "Introduction to Ayurveda",
  "total_pages": 120,
  "chapters": [
    { "title": "Chapter 1: Panchakarma", "summary": "...", "start_page": 1, "end_page": 20 }
  ],
  "pages": { "1": "...", "2": "..." }
}
```

### Step 4 — Ingest into the database

**Option A — via the REST API (recommended):**

```bash
curl -X POST http://localhost:8080/api/courses \
  -H "Content-Type: application/json" \
  -d "{\"courseId\": \"course1\", \"indexJson\": $(cat course_index.json | jq -Rs .)}"
```

**Option B — via the Python script (direct DB):**

```bash
python ingest_to_db.py --index course_index.json --course-id course1
```

### Step 5 — Ask questions

**Via the web UI:**  
Open `http://localhost:3000`, enter the course ID, and type your question.

**Via the REST API:**

```bash
curl -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"courseId": "course1", "question": "What is Panchakarma?"}'
```

Response:
```json
{
  "answer": "Panchakarma is a traditional Ayurvedic treatment...",
  "startPage": 5,
  "endPage": 20,
  "sectionReason": "Chapter 1 covers Panchakarma in detail",
  "courseId": "course1"
}
```

---

## 📖 How It Works (PageIndex RAG Flow)

```
Student question
      │
      ▼
Load course_index.json from PostgreSQL
      │
      ▼
LLM selects the relevant chapter / page range
(Prompt: "Given this table of contents, which pages answer: <question>?")
      │
      ▼
Fetch page texts from PostgreSQL
(SELECT content FROM course_content WHERE page_number BETWEEN ? AND ?)
      │
      ▼
LLM generates answer from page context
(Prompt: "Using only this context, answer: <question>")
      │
      ▼
Return answer + source page range to student
```

---

## 🏗 Project Structure

```
coursechatbot/
├── docker-compose.yml          # Full infrastructure
├── pageindex/                  # Python PageIndex scripts
│   ├── build_index.py          # PDF → hierarchical index JSON
│   ├── query_index.py          # CLI Q&A tool (standalone, no DB)
│   ├── ingest_to_db.py         # Load index + pages into PostgreSQL
│   └── requirements.txt
├── backend/                    # Spring Boot 3 + LangChain4j
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/com/coursechatbot/
│       ├── CourseChatbotApplication.java
│       ├── config/             # Ollama, VirtualThread, error handler
│       ├── controller/         # ChatController, CourseController
│       ├── dto/                # ChatRequest, ChatResponse, IngestRequest
│       ├── model/              # CourseContent, CourseIndex (JPA entities)
│       ├── repository/         # Spring Data JPA repositories
│       └── service/            # PageIndexService, CourseIngestionService
└── frontend/                   # Next.js 14 chat UI
    ├── Dockerfile
    ├── package.json
    └── src/
        ├── app/                # Next.js App Router
        └── components/         # ChatWindow component
```

---

## ⚙️ Configuration

### Backend environment variables

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/coursechatbot` | PostgreSQL URL |
| `SPRING_DATASOURCE_USERNAME` | `chatbot` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | `chatbot_secret` | DB password |
| `OLLAMA_BASE_URL` | `http://localhost:11434` | Ollama server URL |
| `OLLAMA_MODEL` | `qwen2.5:7b` | LLM model name |

### Frontend environment variables

| Variable | Default | Description |
|---|---|---|
| `NEXT_PUBLIC_API_URL` | `http://localhost:8080` | Backend API URL |

---

## 🧪 Running Tests

```bash
cd backend
mvn test
```

Tests use H2 in-memory database and mock the Ollama LLM.

---

## ⚡ Performance Notes

- **Virtual threads** (Java 21) handle high concurrency with minimal resources.
- **No vector database** — PageIndex uses only PostgreSQL and LLM reasoning.
- **Small LLM** — Qwen 2.5 7B runs on CPU; supports 1000–5000 concurrent students on typical hardware.

---

## 🔧 Local Development (without Docker)

### Backend

```bash
# Start PostgreSQL (e.g., with Docker)
docker run -d -p 5432:5432 -e POSTGRES_DB=coursechatbot -e POSTGRES_USER=chatbot -e POSTGRES_PASSWORD=chatbot_secret postgres:16

# Start Ollama
docker run -d -p 11434:11434 ollama/ollama
docker exec <ollama-container> ollama pull qwen2.5:7b

cd backend
mvn spring-boot:run
```

### Frontend

```bash
cd frontend
npm install
npm run dev
```


ssh dell@100.114.88.111
cd /home/dell/chatbot
# Run the updated deploy script (it will update Ollama + pull gemma4)
bash /path/to/deploy-server.sh

# Or manually:
docker pull ollama/ollama:latest
docker stop coursechatbot-ollama && docker rm coursechatbot-ollama
docker compose up -d ollama
docker exec coursechatbot-ollama ollama pull gemma4:latest   # ~9.6GB download


docker exec -it coursechatbot-ollama ollama run gemma4



# From inside the Dell container
docker exec -it coursechatbot-ollama ollama run gemma4 "describe this image" --image /path/to/image.jpg

docker volume ls size --format "{{.Name}}: {{.Size}}" | grep coursechatbot_ollama_data


docker ps -a --format "table {{.Names}}\t{{.Status}}\t{{.Size}}" | grep coursechatbot-ollama