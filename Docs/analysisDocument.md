# ExamPrep AI — Analysis & Requirements Document

> Generated: March 2026 | Base: CourseChatbot PageIndex RAG

---

## 1. Project Overview

**ExamPrep AI** is an intelligent exam-preparation assistant built on top of the existing CourseChatbot PageIndex RAG system.  
It targets B.Tech students across **CSE / ECE / EEE / CIVIL / DA-AIML** branches preparing for end-semester exams.

### Core Value Proposition
| Before | After |
|---|---|
| Generic Q&A chatbot | Mode-aware study partner (LEARN + EXAM) |
| No session context | Per-session performance tracking |
| Fixed prompt depth | Difficulty-scaled responses (Beginner → Advanced) |
| No integrity protection | Academic integrity guard |
| No study pacing | Pomodoro 25-min focus reminder |
| Flat course list | Branch-grouped course selector |

---

## 2. Functional Requirements

### 2.1 Dual-Mode Interaction

| Mode | Behaviour |
|---|---|
| **LEARN** | Full structured explanation: Concept → Formula → Example → Application. Depth adapts to difficulty level. |
| **EXAM** | Hints only — never gives the full answer. Asks leading questions, prompts the student to try first. |

### 2.2 Difficulty Levels

| Level | Prompt Style |
|---|---|
| BEGINNER | Plain language, everyday analogies, short & encouraging |
| INTERMEDIATE | Standard terminology + one worked example |
| ADVANCED | Technical depth, edge cases, complexity analysis |

### 2.3 Session Lifecycle
1. **Create** — `POST /api/chat/session` creates a row in `chat_session`.
2. **Record** — Every message interaction is written to `exam_performance` (response type: HINT / EXPLAINED / REFUSED).
3. **Pomodoro** — After 25 min, backend returns `pomodoroReminder=true`; frontend shows a dismissable banner with a built-in timer.
4. **End Exam** — Student clicks "End Exam 📊"; frontend calls `GET /api/chat/session/{id}/summary` and shows the `PerformanceSummary` modal.

### 2.4 Academic Integrity Guard
Questions containing phrases like `"current exam"`, `"live quiz"`, `"exam right now"` etc. are intercepted before any LLM call.  
Response type recorded as **REFUSED**.

### 2.5 Follow-Up Suggestion Chips
Every assistant response includes 3 clickable follow-up suggestions generated heuristically from the question pattern.

### 2.6 Grouped Course Selector
Courses are grouped by **branch** (CSE / ECE / EEE / CIVIL / DA-AIML) in an `<optgroup>` dropdown, ordered by branch priority.

---

## 3. Non-Functional Requirements

| Concern | Implementation |
|---|---|
| Reactivity | WebFlux + R2DBC end-to-end; all DB calls non-blocking |
| LLM offload | `Schedulers.boundedElastic()` for blocking Ollama HTTP call |
| Context window | MAX_CONTEXT_CHARS = 3000 (up from 800); MAX_PAGES = 5 (up from 2) |
| Session resilience | Session failures are non-fatal (`onErrorResume`); chat answer is still returned |
| Type safety | Full TypeScript strict mode in frontend; Lombok-generated Java models |

---

## 4. Database Schema Changes

### V3 Migration (`V3__exam_prep_schema.sql`)
```sql
-- Metadata on course_index
ALTER TABLE course_index ADD COLUMN IF NOT EXISTS branch  VARCHAR(50);
ALTER TABLE course_index ADD COLUMN IF NOT EXISTS subject VARCHAR(100);

-- Session tracking
CREATE TABLE IF NOT EXISTS chat_session (
    session_id    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id     VARCHAR(255) NOT NULL,
    mode          VARCHAR(20)  NOT NULL DEFAULT 'LEARN',
    difficulty    VARCHAR(20)  NOT NULL DEFAULT 'INTERMEDIATE',
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    message_count INT          NOT NULL DEFAULT 0,
    hint_count    INT          NOT NULL DEFAULT 0
);

-- Interaction log
CREATE TABLE IF NOT EXISTS exam_performance (
    id            BIGSERIAL    PRIMARY KEY,
    session_id    UUID         REFERENCES chat_session(session_id),
    question      TEXT,
    response_type VARCHAR(20),   -- HINT | EXPLAINED | REFUSED
    concept_tag   VARCHAR(100),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

---

## 5. API Changes

### New / Modified Endpoints

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/chat` | Now accepts `mode`, `difficultyLevel`, `sessionId` in request body |
| `POST` | `/api/chat/session` | Create a new study session |
| `GET` | `/api/chat/session/{id}/summary` | Performance summary for a session |
| `GET` | `/api/courses` | Full course list with `branch`/`subject`/`title` (for grouped UI) |

### ChatRequest (updated)
```json
{
  "courseId": "cse-pqb-1",
  "question": "Explain binary search",
  "mode": "LEARN",
  "difficultyLevel": "INTERMEDIATE",
  "sessionId": "uuid-or-null"
}
```

### ChatResponse (updated)
```json
{
  "answer": "...",
  "startPage": 42, "endPage": 48, "sectionReason": "Chapter 4",
  "courseId": "cse-pqb-1",
  "sessionId": "uuid",
  "mode": "LEARN",
  "difficultyLevel": "INTERMEDIATE",
  "isHintOnly": false,
  "pomodoroReminder": false,
  "followUpSuggestions": ["Give an example", "How is it used in exams?", "What are common mistakes?"]
}
```

---

## 6. Backend New Files

| File | Purpose |
|---|---|
| `model/ChatSession.java` | Session entity → `chat_session` table |
| `model/ExamPerformance.java` | Interaction entity → `exam_performance` table |
| `dto/PerformanceSummaryResponse.java` | Summary DTO for session end |
| `repository/ChatSessionRepository.java` | Reactive CRUD for sessions |
| `repository/ExamPerformanceRepository.java` | Reactive CRUD for interactions + hint count query |
| `service/PromptBuilderService.java` | Builds LEARN/EXAM system prompts + integrity guard + follow-up chips |
| `service/SessionService.java` | Create session, record interaction, Pomodoro check, summary |

---

## 7. Frontend New Components

| Component | Purpose |
|---|---|
| `ModeToggle.tsx` | LEARN ↔ EXAM toggle with tooltip |
| `DifficultySelector.tsx` | 3-button Beginner / Intermediate / Advanced |
| `CourseGroupSelector.tsx` | Grouped `<optgroup>` dropdown by branch |
| `MessageBubble.tsx` | Message with page metadata, hint badge, follow-up chips |
| `PerformanceSummary.tsx` | End-session modal: questions, hints, study time, confidence %, concept gaps |
| `PomodoroTimer.tsx` | Standalone 25m focus / 5m break countdown with progress bar |

---

## 8. Ingestion CLI Changes (`course_manager.py`)

### New flags for `add` sub-command
```bash
python3 course_manager.py add \
  --input       Doc/CSE\ PQB\ 1.pdf \
  --course-id   cse-pqb-1 \
  --branch      CSE \
  --subject     "Previous Question Bank" \
  --ollama-url  http://localhost:11434
```

`--branch` and `--subject` are stored in the `branch` and `subject` columns of `course_index` via `ON CONFLICT DO UPDATE`.

---

## 9. Available Course Materials

Located in `.github/Doc/`:

| File | Suggested `--branch` | Suggested `--course-id` |
|---|---|---|
| `CSE PQB 1.pdf` | CSE | `cse-pqb-1` |
| `CSE PQB 2.pdf` | CSE | `cse-pqb-2` |
| `CSE 2025 Set 1 Question.pdf` | CSE | `cse-2025-set1-q` |
| `CSE 2025 Set 1 Key & Solutions.pdf` | CSE | `cse-2025-set1-sol` |
| `CSE 2025 Set 2 Question.pdf` | CSE | `cse-2025-set2-q` |
| `CSE 2025 Set 2 key & Solutions.pdf` | CSE | `cse-2025-set2-sol` |
| `ECE PQB 1.pdf` | ECE | `ece-pqb-1` |
| `ECE PQB 2.pdf` | ECE | `ece-pqb-2` |
| `EEE PQB.pdf` | EEE | `eee-pqb-1` |
| `CIVIL PQB.pdf` | CIVIL | `civil-pqb-1` |
| `DA- AIML 2025 Question.pdf` | DA-AIML | `da-aiml-2025-q` |
| `DA -AIML 2025 Key & Solutions.pdf` | DA-AIML | `da-aiml-2025-sol` |

---

## 10. Ingest All Courses — One-liner Script

```bash
cd /Users/malleswar/IdeaProjects/coursechatbot/pageindex

python3 course_manager.py add --input "../.github/Doc/CSE PQB 1.pdf"                    --course-id cse-pqb-1           --branch CSE      --subject "Previous Question Bank"
python3 course_manager.py add --input "../.github/Doc/CSE PQB 2.pdf"                    --course-id cse-pqb-2           --branch CSE      --subject "Previous Question Bank"
python3 course_manager.py add --input "../.github/Doc/CSE 2025 Set 1 Question.pdf"       --course-id cse-2025-set1-q     --branch CSE      --subject "2025 Exam Set 1"
python3 course_manager.py add --input "../.github/Doc/CSE 2025 Set 1 Key & Solutions.pdf"--course-id cse-2025-set1-sol   --branch CSE      --subject "2025 Exam Set 1 Solutions"
python3 course_manager.py add --input "../.github/Doc/CSE 2025 Set 2 Question.pdf"       --course-id cse-2025-set2-q     --branch CSE      --subject "2025 Exam Set 2"
python3 course_manager.py add --input "../.github/Doc/CSE 2025 Set 2 key & Solutions.pdf"--course-id cse-2025-set2-sol   --branch CSE      --subject "2025 Exam Set 2 Solutions"
python3 course_manager.py add --input "../.github/Doc/ECE PQB 1.pdf"                    --course-id ece-pqb-1           --branch ECE      --subject "Previous Question Bank"
python3 course_manager.py add --input "../.github/Doc/ECE PQB 2.pdf"                    --course-id ece-pqb-2           --branch ECE      --subject "Previous Question Bank"
python3 course_manager.py add --input "../.github/Doc/EEE PQB.pdf"                      --course-id eee-pqb-1           --branch EEE      --subject "Previous Question Bank"
python3 course_manager.py add --input "../.github/Doc/CIVIL PQB.pdf"                    --course-id civil-pqb-1         --branch CIVIL    --subject "Previous Question Bank"
python3 course_manager.py add --input "../.github/Doc/DA- AIML 2025 Question.pdf"        --course-id da-aiml-2025-q      --branch DA-AIML  --subject "2025 Exam"
python3 course_manager.py add --input "../.github/Doc/DA -AIML 2025 Key & Solutions.pdf" --course-id da-aiml-2025-sol    --branch DA-AIML  --subject "2025 Exam Solutions"
```

---

## 11. What Was Already Built (Pre-Analysis)

| Component | Status |
|---|---|
| PageIndex RAG core (keyword selection → LLM) | ✅ Complete |
| Spring WebFlux + R2DBC reactive backend | ✅ Complete |
| Flyway DB migrations (V1 + V2) | ✅ Complete |
| Course ingestion service + CLI | ✅ Complete |
| Basic Next.js chat UI | ✅ Complete |
| Docker Compose stack | ✅ Complete |

## 12. What Was Added (This Analysis)

| Item | Status |
|---|---|
| V3 migration — session/performance tables, branch/subject columns | ✅ Done |
| CourseIndex model — `title`, `branch`, `subject` fields | ✅ Done |
| ChatSession + ExamPerformance models | ✅ Done |
| ChatRequest — `mode`, `difficultyLevel`, `sessionId` | ✅ Done |
| ChatResponse — 6 new fields | ✅ Done |
| PerformanceSummaryResponse DTO | ✅ Done |
| ChatSessionRepository + ExamPerformanceRepository | ✅ Done |
| PromptBuilderService (LEARN/EXAM prompts, integrity guard, chips) | ✅ Done |
| SessionService (create, record, Pomodoro, summary) | ✅ Done |
| PageIndexService refactor (MAX_CONTEXT 800→3000, MAX_PAGES 2→5) | ✅ Done |
| ChatController — POST /session + GET /session/{id}/summary | ✅ Done |
| CourseController — GET /api/courses | ✅ Done |
| CourseIngestionService — getAllCourses() | ✅ Done |
| ModeToggle, DifficultySelector, CourseGroupSelector components | ✅ Done |
| MessageBubble, PerformanceSummary, PomodoroTimer components | ✅ Done |
| ChatWindow full rewrite | ✅ Done |
| page.tsx rebranded to ExamPrep AI | ✅ Done |
| course_manager.py — `--branch` and `--subject` flags | ✅ Done |

