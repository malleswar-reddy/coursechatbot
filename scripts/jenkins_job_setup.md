# 🏗️ Jenkins Job Setup Guide — CourseChatbot

> **Last updated:** March 2026  
> **Server IP:** `100.114.88.111` (Dell OptiPlex)  
> **Jenkins runs on:** Your Mac (local)  
> **Project dir on server:** `/home/dell/chatbot`

---

## 📐 Architecture Overview

```
Your Mac (Jenkins)                                  Server (100.114.88.111)
──────────────────                                  ───────────────────────
  Jenkins UI                                          Docker containers:
  ├── Job 1: coursechatbot-deploy   ──SSH/rsync──►   ├── coursechatbot-postgres  :5432
  ├── Job 2: coursechatbot-restart  ──SSH──────────►  ├── coursechatbot-backend   :8080
  ├── Job 3: coursechatbot-migrate  ──SSH──────────►  ├── coursechatbot-frontend  :3000
  └── Job 4: coursechatbot-ingest   ──SSH/rsync──►   ├── coursechatbot-ollama    :11434
                                                      └── coursechatbot-webui     :3001
```

---

## ✅ One-Time Prerequisites

### 1. Install Jenkins Plugins

Go to: `Manage Jenkins → Plugins → Available Plugins`

| Plugin | Why needed |
|---|---|
| **SSH Agent Plugin** | `sshagent{}` step to SSH into server |
| **File Parameter Plugin** | `base64File` for PDF/file upload in Job 4 |
| **Git Plugin** | `checkout scm` (usually pre-installed) |
| **Pipeline Plugin** | Declarative pipeline support (usually pre-installed) |

---

### 2. Add SSH Credential

This allows Jenkins to SSH into the server without a password.

```
Manage Jenkins
  → Credentials
    → System
      → Global credentials (unrestricted)
        → Add Credentials
```

| Field | Value |
|---|---|
| **Kind** | SSH Username with private key |
| **ID** | `server-ssh-key` ← must be exactly this |
| **Username** | `dell` |
| **Private Key** | paste contents of `~/.ssh/id_rsa` on your Mac |

> **First time?** Copy your Mac public key to the server:
> ```bash
> ssh-copy-id dell@100.114.88.111
> # test it works:
> ssh dell@100.114.88.111 "echo connected"
> ```

---

### 3. Install tools on Jenkins agent (your Mac)

```bash
# rsync (needed by deploy and ingest jobs)
brew install rsync

# ssh (usually pre-installed on Mac)
ssh -V

# curl (usually pre-installed)
curl --version
```

---

### 4. Install tools on the Server (one-time SSH)

```bash
ssh dell@100.114.88.111

# Python 3 + venv (needed by ingest job)
sudo apt install -y python3 python3-venv python3-pip

# pandoc (needed by pypandoc in build_index.py)
sudo apt install -y pandoc

# Docker (should already be installed)
docker --version
docker compose version
```

---

## 🔢 Jenkins Jobs Summary

| # | Job Name | Jenkinsfile | What it does |
|---|---|---|---|
| 1 | `coursechatbot-deploy` | `Jenkinsfile` | **Full CI/CD** — rsync code + rebuild images + deploy all services |
| 2 | `coursechatbot-restart` | `Jenkinsfile.deploy` | **Quick restart** — restart service(s) without rebuilding |
| 3 | `coursechatbot-migrate` | `Jenkinsfile.migrate` | **DB migration** — run Flyway SQL migrations |
| 4 | `coursechatbot-ingest` | `Jenkinsfile.ingest` | **Course ingest** — upload PDF → build index → load DB |

---

## 🔧 Job 1 — `coursechatbot-deploy` (Full CI/CD)

**Jenkinsfile:** `Jenkinsfile`

### Create the Job

```
Jenkins Dashboard → New Item
  Name  : coursechatbot-deploy
  Type  : Pipeline
  → OK
```

In the job configuration:
```
Pipeline section:
  Definition : Pipeline script from SCM
  SCM        : Git
  Repository : <your git repo URL>
  Branch     : */main
  Script Path: Jenkinsfile
```

### What each stage does

```
Stage 1: Checkout         → git clone/pull source code on Jenkins agent
Stage 2: Sync to Server   → rsync entire project to /home/dell/chatbot/ on server
Stage 3: Prepare Server   → chmod +x scripts/deploy-server.sh on server
Stage 4: DB Migrate       → runs flyway migrate on server (schema up to date)
Stage 5: Deploy on Server → SSH runs deploy-server.sh which:
                              [1/5] starts postgres, waits until healthy
                              [2/5] starts ollama
                              [3/5] docker compose build backend
                              [4/5] docker compose build frontend
                              [5/5] docker compose up -d all services
Stage 6: Smoke Test       → curl backend + frontend from Jenkins to verify
```

### Build Parameters

Click **"Build with Parameters"** and fill in:

| Parameter | Default | Description |
|---|---|---|
| `BUILD_BACKEND` | ✅ true | Rebuild Spring Boot Docker image |
| `BUILD_FRONTEND` | ✅ true | Rebuild Next.js Docker image |
| `RESET_DB` | ❌ false | ⚠️ Drop postgres volume (DATA LOSS) |
| `OLLAMA_MODEL` | `qwen2.5:0.5b` | Model for chat answers |
| `SERVER_HOST` | `100.114.88.111` | Target server IP |
| `SERVER_DIR` | `/home/dell/chatbot` | Project folder on server |

### Verify success

After the build:
- ✅ Backend API: `http://100.114.88.111:8080/api/courses/courseIds`
- ✅ Frontend: `http://100.114.88.111:3000`
- ✅ Open WebUI: `http://100.114.88.111:3001`
- ✅ Ollama: `http://100.114.88.111:11434`

---

## 🔄 Job 2 — `coursechatbot-restart` (Quick Restart)

**Jenkinsfile:** `Jenkinsfile.deploy`

> Use this when you want to restart a container **without rebuilding**.  
> Much faster than Job 1 — no Docker build, no rsync.

### Create the Job

```
Jenkins Dashboard → New Item
  Name  : coursechatbot-restart
  Type  : Pipeline
  Script Path: Jenkinsfile.deploy
```

### Build Parameters

| Parameter | Options | Description |
|---|---|---|
| `SERVICE` | `all / backend / frontend / postgres / ollama / open-webui` | Which service to restart |
| `PULL_LATEST_CODE` | false | Run `git pull` on server before restart |
| `SERVER_HOST` | `100.114.88.111` | Server IP |
| `SERVER_DIR` | `/home/dell/chatbot` | Project folder |

### When to use

| Situation | Action |
|---|---|
| Backend crashed / OOM | Restart → `backend` |
| Postgres needs restart | Restart → `postgres` |
| New env var change only | Restart → `all` |
| New code + rebuild needed | Use **Job 1** instead |

---

## 🗄️ Job 3 — `coursechatbot-migrate` (Flyway DB Migration)

**Jenkinsfile:** `Jenkinsfile.migrate`

> Runs Flyway SQL migrations against the server's PostgreSQL.  
> Migration files are in `backend/src/main/resources/db/migration/`.

### Create the Job

```
Jenkins Dashboard → New Item
  Name  : coursechatbot-migrate
  Type  : Pipeline
  Script Path: Jenkinsfile.migrate
```

### What each stage does

```
Stage 1: Checkout              → get latest source
Stage 2: Sync Migration Files  → rsync V*.sql files to server
Stage 3: Verify Postgres       → confirm postgres container is healthy
Stage 4: Migration Status(Before) → flyway info (shows applied/pending)
Stage 5: Run Flyway            → runs your chosen command
Stage 6: Migration Status(After)  → flyway info again (shows result)
Stage 7: Verify Tables         → psql \dt to confirm tables exist
```

### Flyway Commands

| Command | Safe? | Use when |
|---|---|---|
| **info** | ✅ Read-only | Always run first — shows what's applied / pending |
| **migrate** | ✅ Applies changes | Apply new V*.sql files to DB |
| **validate** | ✅ Read-only | Check SQL files haven't been edited after applying |
| **repair** | ⚠️ Modifies history | Fix a FAILED migration record in flyway_schema_history |
| **baseline** | ⚠️ One-time | Mark existing DB as starting point (use on fresh server) |

### SQL Migration Naming Convention

```
backend/src/main/resources/db/migration/
  V1__init_schema.sql           ✅ Applied — creates course_index, course_content
  V2__add_course_metadata.sql   ⏳ Pending — adds title, description columns
  V3__your_next_change.sql      ← create this when you need another schema change
     │   ││
     │   │└── description (underscores = spaces in Flyway history)
     │   └─── DOUBLE underscore (mandatory)
     └──────── version number (must be unique, always increasing)
```

> ⚠️ **Golden rule:** NEVER edit a V*.sql file after it has been applied.  
> Flyway stores a checksum — editing will cause `validate` to FAIL.  
> Always create a new `V{n+1}__description.sql` for new changes.

### Typical workflow for a new DB change

```
1. Create:  backend/src/main/resources/db/migration/V3__my_change.sql
2. Job 3 → info    → confirm V3 shows as "Pending"
3. Job 3 → migrate → V3 applied ✅
4. Job 1 → deploy  → backend starts, Flyway sees V3 already done → skips
```

---

## 📚 Job 4 — `coursechatbot-ingest` (Course PDF Ingest)

**Jenkinsfile:** `Jenkinsfile.ingest`

> Upload a course PDF (or Markdown / plain text) → Jenkins processes it →  
> builds a PageIndex JSON → loads all pages into PostgreSQL.  
> After this, students can ask questions about the course in the chatbot.

### Create the Job

```
Jenkins Dashboard → New Item
  Name  : coursechatbot-ingest
  Type  : Pipeline
  Script Path: Jenkinsfile.ingest
```

### Full Data Flow

```
Your Browser                Jenkins Agent (Mac)           Server (100.114.88.111)
─────────────               ───────────────────           ───────────────────────
  Upload PDF          →     base64File decodes it
  Fill COURSE_ID            saves to workspace/uploads/
  Click Build Now
                            rsync pageindex/*.py    →      /home/dell/chatbot/pageindex/
                            rsync uploads/file.pdf  →      pageindex/uploads/file.pdf

                                                           python3 build_index.py
                                                             ├─ extract_text()  reads PDF page by page
                                                             ├─ build_toc_prompt() picks 10 best pages
                                                             ├─ calls Ollama (localhost:11434)
                                                             │    └─ LLM returns TOC as JSON
                                                             └─ saves {COURSE_ID}_index.json

                                                           python3 ingest_to_db.py
                                                             ├─ reads {COURSE_ID}_index.json
                                                             ├─ upserts course_index row (TOC)
                                                             └─ upserts course_content rows (pages)
                                                                  (one row per page with full text)

                            scp _index.json back    ←      pageindex/index_output/
                            archiveArtifacts (JSON)
                            curl /api/courses/courseIds → verify course appears in API
```

### What each stage does

```
Stage 1: Save Uploaded File  → base64File decoded → saved as uploads/<ORIGINAL_FILENAME>
Stage 2: Sync to Server      → rsync pageindex/*.py + uploaded file to server
Stage 3: Setup Python Env    → creates .venv on server (cached, reused across builds)
                               installs: pdfplumber, requests, psycopg2-binary, pypandoc
Stage 4: Build Index         → runs build_index.py on server
                               reads PDF → calls Ollama → writes _index.json
Stage 5: Ingest to DB        → runs ingest_to_db.py on server
                               reads _index.json → writes to postgres
Stage 6: Archive             → scp _index.json back to Jenkins → archiveArtifacts
Stage 7: Verify              → curl /api/courses/courseIds to confirm course visible
```

### Build Parameters

| Parameter | Example | Description |
|---|---|---|
| `COURSE_FILE` | 📎 Browse → `java-book.pdf` | **File upload** — PDF, .md, or .txt |
| `ORIGINAL_FILENAME` | `java-book.pdf` | ⚠️ Must set this — Jenkins loses the filename from upload |
| `COURSE_ID` | `java-how-to-program` | Unique ID stored in DB — used to query this course |
| `SKIP_PAGES` | `0` | Front-matter pages to skip (0 = auto-detect) |
| `SERVER_HOST` | `100.114.88.111` | Server where DB + Ollama run |
| `SERVER_DIR` | `/home/dell/chatbot` | Project folder on server |
| `OLLAMA_URL` | `http://localhost:11434` | Ollama URL as seen FROM the server |
| `OLLAMA_MODEL` | `qwen2.5:0.5b` | Model for TOC extraction |

> ⚠️ **Important:** `ORIGINAL_FILENAME` must include the correct extension  
> (`.pdf` / `.md` / `.txt`) — this tells `build_index.py` how to parse the file.

### Supported file formats

| Format | Extension | How it's parsed |
|---|---|---|
| PDF | `.pdf` | `pdfplumber` — one "page" = one PDF page |
| Markdown | `.md` | Split by `##` headings — one "page" = one section |
| Plain text | `.txt` | Split into 500-word chunks — one "page" = one chunk |

### After ingest — verify in DB (on server)

```bash
ssh dell@100.114.88.111

# Check course_index table
docker exec coursechatbot-postgres \
  psql -U chatbot -d coursechatbot \
  -c "SELECT course_id FROM course_index;"

# Check page count for a course
docker exec coursechatbot-postgres \
  psql -U chatbot -d coursechatbot \
  -c "SELECT course_id, COUNT(*) as pages FROM course_content GROUP BY course_id;"

# Test via API
curl http://localhost:8080/api/courses/courseIds
```

---

## 🔁 Recommended Job Execution Order

### First-time server setup

```
1. Job 1 (deploy)    → deploys all services (postgres, ollama, backend, frontend)
2. Job 3 (migrate)   → run 'info' then 'migrate' to apply V1 schema
3. Job 4 (ingest)    → upload first course PDF
4. Browse            → http://100.114.88.111:3000 — start chatting!
```

### Adding a new course

```
1. Job 4 (ingest)    → upload new PDF, set COURSE_ID
2. Done! Course appears in frontend dropdown automatically
```

### Deploying new code changes

```
1. Commit + push code to git
2. Job 1 (deploy)    → rsync + rebuild + redeploy
   (Flyway runs automatically inside this job before backend starts)
```

### Adding a new DB column / table

```
1. Create V{n}__description.sql in backend/src/main/resources/db/migration/
2. Job 3 (migrate) → info  → confirm it shows as Pending
3. Job 3 (migrate) → migrate → apply it
4. Update Java entity/repository if needed
5. Job 1 (deploy) → rebuild + redeploy backend
```

### Something broke — quick restart

```
1. Job 2 (restart) → pick SERVICE = backend (or all)
2. Check logs:  ssh dell@100.114.88.111 "docker logs coursechatbot-backend --tail 100"
```

---

## 🔍 Useful Server Commands (SSH in manually)

```bash
ssh dell@100.114.88.111

# See all running containers
docker ps

# See all services status
cd /home/dell/chatbot
docker compose -f docker-compose.server.yml ps

# Backend logs (live)
docker logs -f coursechatbot-backend

# Postgres logs
docker logs coursechatbot-postgres --tail 50

# Test backend API
curl http://localhost:8080/api/courses/courseIds

# Test Ollama
curl http://localhost:11434

# Connect to DB directly
docker exec -it coursechatbot-postgres psql -U chatbot -d coursechatbot

# List DB tables
docker exec coursechatbot-postgres psql -U chatbot -d coursechatbot -c '\dt'

# Check Flyway migration history
docker exec coursechatbot-postgres psql -U chatbot -d coursechatbot \
  -c "SELECT version, description, installed_on, success FROM flyway_schema_history ORDER BY installed_rank;"
```

---

## 🩺 Troubleshooting

| Problem | Cause | Fix |
|---|---|---|
| Backend fails to start — `Connection refused` to DB | Postgres not running or not healthy | Job 2 → restart `postgres`, wait, then restart `backend` |
| Backend fails — `Flyway checksum mismatch` | A V*.sql file was edited after applying | Job 3 → `repair`, then `validate` |
| Backend fails — `No qualifying bean CourseContentRepository` | Old JAR without JPA — rebuild needed | Job 1 → deploy with `BUILD_BACKEND=true` |
| Ingest fails — `Is a directory` (init.sql error) | File mount path doesn't exist on server | Fixed: init.sql mount removed from docker-compose.server.yml |
| Ingest slow (1-2 min response) | Ollama model not warmed up | Normal for first query; subsequent queries are faster |
| Frontend shows wrong API URL | `NEXT_PUBLIC_API_URL` baked at build time | Job 1 → rebuild with correct `SERVER_HOST` |
| `ssh: connection refused` in Jenkins | SSH credential not configured | Re-check credential ID = `server-ssh-key` in Jenkins |
| `rsync: command not found` on Jenkins | rsync not installed on Mac Jenkins agent | `brew install rsync` on Mac |

---

## 📂 Project File Reference

```
coursechatbot/
  Jenkinsfile              ← Job 1: Full CI/CD deploy to server
  Jenkinsfile.deploy       ← Job 2: Quick service restart
  Jenkinsfile.migrate      ← Job 3: Flyway DB migrations
  Jenkinsfile.ingest       ← Job 4: Course PDF upload + ingest
  docker-compose.yml       ← Local development (Mac)
  docker-compose.server.yml← Server deployment (postgres+ollama+backend+frontend)
  scripts/
    deploy-server.sh       ← Runs on server: build + up all services
  backend/
    src/main/resources/
      application.properties        ← Spring Boot config (DB, Ollama, Flyway)
      db/
        init.sql                    ← Docker postgres init (just SELECT 1)
        migration/
          V1__init_schema.sql       ← Creates course_index + course_content
          V2__add_course_metadata.sql← Adds title/description columns
  pageindex/
    build_index.py         ← PDF/MD/TXT → PageIndex JSON via Ollama
    ingest_to_db.py        ← PageIndex JSON → PostgreSQL
    extract_text.py        ← Multi-format text extractor
    query_index.py         ← RAG query (for testing locally)
    requirements.txt       ← pdfplumber, requests, psycopg2-binary, pypandoc
  frontend/
    Dockerfile             ← Next.js build (accepts NEXT_PUBLIC_API_URL build arg)
    src/components/
      ChatWindow.tsx        ← Main chat UI (fetches courseIds from API)
```

---

## 🔐 Credentials & Config Reference

| Item | Value |
|---|---|
| Server IP | `100.114.88.111` |
| Server user | `dell` |
| Server project dir | `/home/dell/chatbot` |
| Jenkins SSH credential ID | `server-ssh-key` |
| DB name | `coursechatbot` |
| DB user | `chatbot` |
| DB password | `chatbot_secret` |
| DB port | `5432` |
| Backend port | `8080` |
| Frontend port | `3000` |
| Open WebUI port | `3001` |
| Ollama port | `11434` |
| Ollama model | `qwen2.5:0.5b` |

