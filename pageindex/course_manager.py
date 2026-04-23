"""
course_manager.py — Unified CLI to manage multiple courses.

Supported input formats: .pdf  .md  .txt

Sub-commands
------------
  add     Build index from a file, save it, and ingest into PostgreSQL
  query   Ask a question against a specific indexed course
  list    Show all ingested courses in the database
  remove  Delete a course from DB and its local index JSON

Quick start
-----------
  # Index + ingest a PDF textbook
  python3 course_manager.py add \\
      --input  textbooks/java.pdf \\
      --course-id  java-basics \\
      --ollama-url http://100.114.88.111:11434

  # Index a Markdown file
  python3 course_manager.py add \\
      --input  notes/python_intro.md \\
      --course-id  python-intro

  # Ask a question
  python3 course_manager.py query \\
      --course-id java-basics \\
      --question "Explain the switch statement" \\
      --ollama-url http://100.114.88.111:11434

  # List all courses
  python3 course_manager.py list

  # Remove a course
  python3 course_manager.py remove --course-id java-basics
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
from pathlib import Path

import requests

# Local modules
from extract_text import extract_text

# ── Folder where course index JSONs are stored ─────────────────────────────────
COURSES_DIR = Path(__file__).parent / "courses"
COURSES_DIR.mkdir(exist_ok=True)

REMOTE_HOST    = "100.114.88.111"
DEFAULT_DB_URL = f"postgresql://chatbot:chatbot_secret@{REMOTE_HOST}:5432/coursechatbot"
DEFAULT_OLLAMA = f"http://{REMOTE_HOST}:11434"
DEFAULT_MODEL  = "qwen2.5:0.5b"

# Front-matter keywords (for PDF auto-skip)
_FRONT_MATTER = {"contents", "preface", "index", "copyright", "edition",
                 "intentionally left blank", "about the author", "table of"}


# ══════════════════════════════════════════════════════════════════════════════
#  Shared helpers
# ══════════════════════════════════════════════════════════════════════════════

def is_front_matter(text: str) -> bool:
    t = text.lower().strip()
    if len(t) < 80:
        return True
    if t.count("\n") > 15 and len(t) / (t.count("\n") + 1) < 20:
        return True
    return any(kw in t[:200] for kw in _FRONT_MATTER)


def index_path_for(course_id: str) -> Path:
    return COURSES_DIR / f"{course_id}_index.json"


def load_index(course_id: str) -> dict:
    path = index_path_for(course_id)
    if not path.exists():
        sys.exit(f"❌  Index not found for course '{course_id}'.\n"
                 f"    Expected: {path}\n"
                 f"    Run:  python3 course_manager.py add --course-id {course_id} --input <file>")
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def call_ollama_stream(prompt: str, ollama_url: str, model: str,
                       num_predict: int = 2048, silent: bool = False) -> str:
    url = f"{ollama_url.rstrip('/')}/api/generate"
    payload = {
        "model": model, "prompt": prompt, "stream": True,
        "keep_alive": "10m",
        "options": {"temperature": 0.1, "num_predict": num_predict, "num_ctx": 2048},
    }
    if not silent:
        print("  Streaming LLM ", end="", flush=True)
    tokens: list[str] = []
    dot = 0
    with requests.post(url, json=payload, stream=True, timeout=600) as r:
        r.raise_for_status()
        for line in r.iter_lines():
            if line:
                chunk = json.loads(line)
                tok = chunk.get("response", "")
                tokens.append(tok)
                if not silent:
                    print(tok, end="", flush=True)
                dot += 1
                if chunk.get("done"):
                    break
    if not silent:
        print("", flush=True)
    return "".join(tokens)


def extract_json_from(text: str) -> dict:
    m = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
    if m:
        return json.loads(m.group(1))
    m = re.search(r"\{.*\}", text, re.DOTALL)
    if not m:
        raise ValueError("No JSON found in LLM response.")
    return json.loads(m.group())


# ══════════════════════════════════════════════════════════════════════════════
#  ADD — index a file and ingest into DB
# ══════════════════════════════════════════════════════════════════════════════

def _is_toc_valid(index: dict, total_pages: int) -> bool:
    """
    Return True only if the LLM-built TOC looks believable:
      - At least 1 chapter
      - At least 50% of chapters have page ranges that stay within [1, total_pages]
    """
    chapters = index.get("chapters", [])
    if not chapters:
        return False
    valid = sum(
        1 for ch in chapters
        if 1 <= ch.get("start_page", 0) <= total_pages
        and ch.get("start_page", 0) <= ch.get("end_page", 0) <= total_pages
    )
    return valid >= max(1, len(chapters) // 2)


def _sanitize_toc(index: dict, total_pages: int) -> dict:
    """
    Flatten and validate an LLM-generated TOC:
    - Max 2 levels deep (chapters → children only)
    - Clamp page numbers to [1, total_pages]
    - Remove children that duplicate parent ranges
    """
    def clamp(v: int) -> int:
        return max(1, min(v, total_pages))

    clean_chapters = []
    for ch in index.get("chapters", []):
        ch_start = clamp(int(ch.get("start_page", 1)))
        ch_end   = clamp(int(ch.get("end_page",   ch_start + 1)))
        if ch_start > ch_end:
            ch_end = ch_start

        children = []
        for sub in ch.get("children", [])[:8]:   # max 8 subsections
            s_start = clamp(int(sub.get("start_page", ch_start)))
            s_end   = clamp(int(sub.get("end_page",   s_start + 1)))
            if s_start > s_end:
                s_end = s_start
            # skip trivial 1-page duplicates that repeat the parent
            if s_start == ch_start and s_end == ch_end:
                continue
            children.append({
                "title":      str(sub.get("title",   "Section"))[:80],
                "summary":    str(sub.get("summary", ""))[:200],
                "start_page": s_start,
                "end_page":   s_end,
                "children":   [],   # no deeper nesting
            })

        clean_chapters.append({
            "title":      str(ch.get("title",   "Chapter"))[:80],
            "summary":    str(ch.get("summary", ""))[:200],
            "start_page": ch_start,
            "end_page":   ch_end,
            "children":   children,
        })

    index["chapters"] = clean_chapters
    return index


def _build_toc_prompt(pages: dict[int, str], skip: int, source_ext: str) -> str:
    total = len(pages)
    content = {n: t for n, t in pages.items()
               if int(n) > skip and len(t.strip()) > 80 and not is_front_matter(t)}
    if len(content) < 3:
        content = {n: t for n, t in pages.items() if len(t.strip()) > 50}
    sample = dict(list(content.items())[:10])
    first = min(int(n) for n in content) if content else 1

    pages_text = "\n\n".join(
        f"[Page {num}]\n{text[:300]}" for num, text in sample.items()
    )
    unit = "pages" if source_ext == ".pdf" else "sections"
    return f"""You are a document analyst. Return a JSON table-of-contents for this {total}-{unit} document.

STRICT RULES:
- Return ONLY valid JSON. No explanation, no markdown, no extra text.
- Maximum 2 levels deep: chapters with optional children. NO deeper nesting.
- Each chapter must have unique start_page and end_page that cover the full document.
- Do NOT repeat the same page range in children that is already in the parent.
- children array must be EMPTY [] if there are no meaningful subsections.

JSON format (EXACTLY this structure, no deeper nesting allowed):
{{
  "title": "document title",
  "chapters": [
    {{
      "title": "Chapter title",
      "summary": "one sentence description",
      "start_page": {first},
      "end_page": {total},
      "children": []
    }}
  ]
}}

Sample content ({len(sample)} {unit} shown):
{pages_text}

JSON (2 levels max, no deep nesting):"""


def _fallback_index(pages: dict[int, str], skip: int) -> dict:
    """
    Smart fallback TOC when LLM fails.
    - Uses chunk_size=3 for PDFs ≤30 pages (PQBs / short docs)
    - Uses chunk_size=10 for larger documents
    - Names each chapter from the first meaningful line of OCR text
    """
    total      = len(pages)
    chunk_size = 3 if total <= 30 else 10

    # Pick document title from first non-empty page
    doc_title = "Unknown Course"
    for p in sorted(pages.keys()):
        lines = [l.strip() for l in pages[p].splitlines() if l.strip()]
        if lines:
            doc_title = lines[0][:80]
            break

    start = max(1, skip + 1)
    chapters = []
    for cs in range(start, total + 1, chunk_size):
        end = min(cs + chunk_size - 1, total)

        # Extract a meaningful title from the first non-empty page in this chunk
        chunk_title = f"Pages {cs}–{end}"
        for pg in range(cs, end + 1):
            txt = pages.get(pg, "").strip()
            if txt:
                first_line = next((l.strip() for l in txt.splitlines() if len(l.strip()) > 5),
                                  chunk_title)
                chunk_title = first_line[:80]
                break

        # Build summary from first 200 chars across the chunk
        chunk_text = " ".join(
            pages.get(pg, "")[:200] for pg in range(cs, end + 1) if pages.get(pg, "").strip()
        )
        summary = chunk_text[:120].replace("\n", " ").strip() or f"Content covering pages {cs}–{end}"

        chapters.append({
            "title":      chunk_title,
            "summary":    summary,
            "start_page": cs,
            "end_page":   end,
            "children":   [],
        })

    return {"title": doc_title, "chapters": chapters}


def cmd_add(args: argparse.Namespace) -> None:
    input_path = Path(args.input)
    if not input_path.exists():
        sys.exit(f"❌  File not found: {input_path}")

    course_id = args.course_id
    out_json   = index_path_for(course_id)
    ext        = input_path.suffix.lower()

    print(f"\n{'═'*60}")
    print(f"  ADD COURSE: {course_id}")
    print(f"  Source    : {input_path.name}  [{ext or 'txt'}]")
    print(f"{'═'*60}\n")

    # 1. Extract text
    print("[1/3] Extracting text …")
    pages = extract_text(str(input_path))
    print(f"  ✓ {len(pages)} pages/sections\n")

    # 2. Auto-skip front-matter for PDFs
    skip = args.skip_pages
    if skip == 0 and ext == ".pdf":
        for num in sorted(pages.keys()):
            if len(pages[num].strip()) > 100 and not is_front_matter(pages[num]):
                skip = num - 1
                break
        print(f"  Auto-skip: {skip} front-matter pages\n")

    # 3. Build TOC via LLM
    print(f"[2/3] Building TOC via {args.model} …")
    prompt = _build_toc_prompt(pages, skip, ext)
    try:
        raw = call_ollama_stream(prompt, args.ollama_url, args.model, num_predict=2048)
        index = extract_json_from(raw)
        index = _sanitize_toc(index, len(pages))   # flatten deep/recursive nesting
        if _is_toc_valid(index, len(pages)):
            print("  ✓ LLM TOC built\n")
        else:
            print("  ⚠  LLM TOC has invalid page ranges → using fallback chunk index\n")
            index = _fallback_index(pages, skip)
    except Exception as e:
        print(f"  ⚠  LLM failed ({e}) → using fallback chunk index\n")
        index = _fallback_index(pages, skip)

    # 4. Save index JSON (includes pages)
    index["total_pages"]  = len(pages)
    index["course_id"]    = course_id
    index["source_file"]  = str(input_path)
    index["source_format"] = ext or "txt"
    index["pages"]        = {str(k): v for k, v in pages.items()}
    out_json.write_text(json.dumps(index, indent=2, ensure_ascii=False))
    print(f"  Index saved → {out_json}")

    # 5. Ingest into PostgreSQL
    print(f"\n[3/3] Ingesting into PostgreSQL …")
    try:
        import psycopg2
    except ImportError:
        sys.exit("❌  psycopg2 not installed — run: pip3 install psycopg2-binary")

    conn = psycopg2.connect(args.db_url)
    cur  = conn.cursor()

    index_no_pages = {k: v for k, v in index.items() if k != "pages"}
    branch  = getattr(args, "branch",  None) or None
    subject = getattr(args, "subject", None) or None
    title   = index.get("title")
    cur.execute(
        """
        INSERT INTO course_index (course_id, index_json, branch, subject, title)
        VALUES (%s, %s, %s, %s, %s)
        ON CONFLICT (course_id) DO UPDATE
            SET index_json = EXCLUDED.index_json,
                branch     = COALESCE(EXCLUDED.branch, course_index.branch),
                subject    = COALESCE(EXCLUDED.subject, course_index.subject),
                title      = COALESCE(EXCLUDED.title, course_index.title)
        """,
        (course_id, json.dumps(index_no_pages), branch, subject, title),
    )

    inserted = 0
    for page_str, content in pages.items():
        cur.execute(
            """
            INSERT INTO course_content (course_id, page_number, content)
            VALUES (%s, %s, %s)
            ON CONFLICT (course_id, page_number) DO UPDATE SET content = EXCLUDED.content
            """,
            (course_id, int(page_str), content),
        )
        inserted += 1

    conn.commit()
    cur.close()
    conn.close()

    print(f"  ✓ {inserted} pages inserted/updated")
    print(f"\n{'═'*60}")
    print(f"✅  Course '{course_id}' is ready!")
    print(f"   Title   : {index.get('title', 'N/A')}")
    print(f"   Pages   : {len(pages)}")
    print(f"   Chapters: {len(index.get('chapters', []))}")
    if branch:
        print(f"   Branch  : {branch}")
    if subject:
        print(f"   Subject : {subject}")
    print(f"\n   Query:  python3 course_manager.py query --course-id {course_id} --question \"...\"")
    print(f"{'═'*60}")


# ══════════════════════════════════════════════════════════════════════════════
#  QUERY — ask a question about a course
# ══════════════════════════════════════════════════════════════════════════════

def _select_section(index: dict, question: str) -> tuple[int, int, str]:
    """Keyword-based section selection — instant, no LLM needed."""
    chapters = index.get("chapters", [])
    if not chapters:
        total = index.get("total_pages", 1)
        return 1, total, "entire document"

    q_lower = question.lower()
    best_score = -1
    best_start, best_end, best_title = 1, index.get("total_pages", 10), "document"

    for ch in chapters:
        score = (sum(1 for w in ch["title"].lower().split() if w in q_lower) * 2
                 + sum(1 for w in ch.get("summary", "").lower().split() if w in q_lower))
        for sub in ch.get("children", []):
            sub_score = (sum(1 for w in sub["title"].lower().split() if w in q_lower) * 2
                         + sum(1 for w in sub.get("summary", "").lower().split() if w in q_lower))
            if sub_score > best_score:
                best_score  = sub_score
                best_start  = sub["start_page"]
                best_end    = sub["end_page"]
                best_title  = sub["title"]
        if score > best_score:
            best_score  = score
            best_start  = ch["start_page"]
            best_end    = ch["end_page"]
            best_title  = ch["title"]

    return best_start, best_end, best_title


def _get_context(index: dict, start: int, end: int,
                 max_pages: int = 5, max_chars: int = 3000) -> str:
    pages = index.get("pages", {})
    parts: list[str] = []
    total_chars = 0
    for p in range(start, end + 1):
        if len(parts) >= max_pages or total_chars >= max_chars:
            break
        text = pages.get(str(p), "").strip()
        if text:
            snippet = text[:max_chars - total_chars]
            parts.append(f"[Page {p}]\n{snippet}")
            total_chars += len(snippet)
    return "\n\n".join(parts)


def _build_query_prompt(context: str, question: str, title: str,
                        start: int, end: int, source_fmt: str,
                        mode: str, difficulty: str) -> tuple[str, str]:
    """Return (system_prompt, user_prompt) for the given mode + difficulty."""
    unit = "page" if source_fmt == ".pdf" else "section"

    # ── Depth guide based on difficulty ───────────────────────────────────────
    depth = {
        "BEGINNER":     "Use simple language and everyday analogies. Keep it short and encouraging.",
        "INTERMEDIATE": "Use standard terminology and include one worked example.",
        "ADVANCED":     "Use precise technical language, include edge cases and complexity analysis.",
    }.get(difficulty.upper(), "Use standard terminology and include one worked example.")

    if mode.upper() == "EXAM":
        system_prompt = f"""You are a strict exam coach for "{title}".
RULES (EXAM MODE):
- NEVER give the full answer or solution.
- Give ONLY a concise hint — a guiding question or a first step.
- End with "Try it yourself first!" if appropriate.
Keep your hint to 2-3 sentences maximum."""

        user_prompt = f"""Context ({unit}s {start}-{end}):
{context}

Question: {question}

Hint (2-3 sentences, no full answer):"""

    else:  # LEARN mode
        system_prompt = f"""You are an expert teacher for "{title}".
For every answer use this EXACT structure:

### Concept
Explain the core idea clearly.

### Formula / Rule
State the formula or rule precisely (skip if not applicable).

### Example
Work through one step-by-step example.

### Application
Connect to a real exam problem or use-case.

Depth guideline: {depth}
Use ONLY the provided context. Start with "Great question!" when the question is interesting."""

        user_prompt = f"""Context ({unit}s {start}-{end}):
{context}

Question: {question}

Structured Answer:"""

    return system_prompt, user_prompt


def cmd_query(args: argparse.Namespace) -> None:
    index      = load_index(args.course_id)
    title      = index.get("title", args.course_id)
    total      = index.get("total_pages", 0)
    question   = args.question
    mode       = getattr(args, "mode",       "LEARN")
    difficulty = getattr(args, "difficulty", "INTERMEDIATE")

    print(f"\n{'═'*60}")
    print(f"  COURSE    : {title}  ({total} pages)")
    print(f"  MODE      : {mode.upper()}  |  DIFFICULTY: {difficulty.upper()}")
    print(f"  QUESTION  : {question}")
    print(f"{'═'*60}")

    t0 = time.time()

    # 1. Find best section (keyword scoring)
    start, end, section_title = _select_section(index, question)
    print(f"\n  📖 Section: [{start}-{end}] {section_title}")

    # 2. Build context (larger window matching backend)
    context = _get_context(index, start, end, max_pages=5, max_chars=3000)
    if not context.strip():
        context = _get_context(index, max(1, start - 5), min(total, end + 5))

    # 3. Build mode-aware prompt
    source_fmt = index.get("source_format", ".pdf")
    system_prompt, user_prompt = _build_query_prompt(
        context, question, title, start, end, source_fmt, mode, difficulty)

    # 4. Stream answer — use chat endpoint if available, else /api/generate
    print(f"\n{'─'*60}")
    full_prompt = f"{system_prompt}\n\n{user_prompt}"
    answer = call_ollama_stream(
        full_prompt, args.ollama_url, args.model, num_predict=800)
    elapsed = time.time() - t0

    print(f"\n{'─'*60}")
    print(f"  📄 Source: {source_fmt[1:].upper() if source_fmt.startswith('.') else source_fmt}"
          f"  pages {start}-{end}  ·  '{section_title}'")
    print(f"  ⏱  Time  : {elapsed:.1f}s")
    print(f"{'═'*60}\n")


# ══════════════════════════════════════════════════════════════════════════════
#  LIST — show all courses in DB
# ══════════════════════════════════════════════════════════════════════════════

def cmd_list(args: argparse.Namespace) -> None:
    try:
        import psycopg2
    except ImportError:
        sys.exit("❌  psycopg2 not installed — run: pip3 install psycopg2-binary")

    try:
        conn = psycopg2.connect(args.db_url)
    except Exception as e:
        sys.exit(f"❌  DB connection failed: {e}")

    cur = conn.cursor()
    cur.execute("SELECT course_id, index_json FROM course_index ORDER BY course_id")
    rows = cur.fetchall()
    cur.close()
    conn.close()

    # Also check local JSON files
    local_ids = {p.stem.replace("_index", "") for p in COURSES_DIR.glob("*_index.json")}

    print(f"\n{'═'*60}")
    print(f"  COURSES  (DB: {len(rows)} found)")
    print(f"{'═'*60}")

    if not rows:
        print("  (no courses ingested yet)")
    for course_id, meta_json in rows:
        meta  = meta_json if isinstance(meta_json, dict) else json.loads(meta_json)
        title = meta.get("title", "—")
        pages = meta.get("total_pages", "?")
        src   = meta.get("source_file", "—")
        local = "✓ local" if course_id in local_ids else "✗ no local JSON"
        print(f"\n  [{course_id}]")
        print(f"    Title  : {title}")
        print(f"    Pages  : {pages}")
        print(f"    Source : {src}")
        print(f"    Index  : {local}")

    print(f"\n{'═'*60}")
    print(f"  Query a course:")
    print(f"    python3 course_manager.py query --course-id <id> --question \"...\"")
    print(f"{'═'*60}\n")


# ══════════════════════════════════════════════════════════════════════════════
#  REMOVE — delete a course
# ══════════════════════════════════════════════════════════════════════════════

def cmd_remove(args: argparse.Namespace) -> None:
    course_id = args.course_id
    try:
        import psycopg2
    except ImportError:
        sys.exit("❌  psycopg2 not installed — run: pip3 install psycopg2-binary")

    conn = psycopg2.connect(args.db_url)
    cur  = conn.cursor()
    cur.execute("DELETE FROM course_content WHERE course_id = %s", (course_id,))
    n_content = cur.rowcount
    cur.execute("DELETE FROM course_index   WHERE course_id = %s", (course_id,))
    n_index = cur.rowcount
    conn.commit()
    cur.close()
    conn.close()

    local_json = index_path_for(course_id)
    if local_json.exists():
        local_json.unlink()
        print(f"  🗑  Deleted local index: {local_json}")

    print(f"\n  ✅  Course '{course_id}' removed.")
    print(f"      DB rows deleted: {n_content} content + {n_index} index")


# ══════════════════════════════════════════════════════════════════════════════
#  CLI entry-point
# ══════════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(
        prog="course_manager.py",
        description="Manage multi-course PageIndex — add / query / list / remove",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples
--------
  # Ingest a PDF (DB + Ollama default to 100.114.88.111)
  python3 course_manager.py add \\
      --input "../.github/Doc/CSE PQB 1.pdf" \\
      --course-id cse-pqb-1 --branch CSE --subject "Previous Question Bank"

  # LEARN mode — structured answer (Concept / Formula / Example / Application)
  python3 course_manager.py query \\
      --course-id cse-pqb-1 --question "Explain OS scheduling" \\
      --mode LEARN --difficulty INTERMEDIATE

  # EXAM mode — hint only, no full answer
  python3 course_manager.py query \\
      --course-id cse-pqb-1 --question "What is round-robin scheduling?" \\
      --mode EXAM

  # List all ingested courses
  python3 course_manager.py list

  # Remove a course
  python3 course_manager.py remove --course-id cse-pqb-1
""",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # ── add ──────────────────────────────────────────────────────────────────
    p_add = sub.add_parser("add", help="Index a file and ingest into DB")
    p_add.add_argument("--input",      required=True, help=".pdf / .md / .txt file path")
    p_add.add_argument("--course-id",  required=True, help="Unique course identifier  (e.g. java-basics)")
    p_add.add_argument("--branch",     default=None,
                       help="Engineering branch  e.g. CSE | ECE | EEE | CIVIL | DA-AIML")
    p_add.add_argument("--subject",    default=None,
                       help="Subject name  e.g. 'Data Structures'")
    p_add.add_argument("--skip-pages", type=int, default=0,
                       help="Skip first N pages (PDF front matter). 0 = auto-detect.")
    p_add.add_argument("--ollama-url", default=DEFAULT_OLLAMA)
    p_add.add_argument("--model",      default=DEFAULT_MODEL)
    p_add.add_argument("--db-url",     default=DEFAULT_DB_URL)

    # ── query ─────────────────────────────────────────────────────────────────
    p_qry = sub.add_parser("query", help="Ask a question about a course")
    p_qry.add_argument("--course-id",   required=True)
    p_qry.add_argument("--question",    required=True)
    p_qry.add_argument("--mode",        default="LEARN",
                       choices=["LEARN", "EXAM"],
                       help="LEARN = full structured answer (default) | EXAM = hint only")
    p_qry.add_argument("--difficulty",  default="INTERMEDIATE",
                       choices=["BEGINNER", "INTERMEDIATE", "ADVANCED"],
                       help="Depth of the answer (default: INTERMEDIATE)")
    p_qry.add_argument("--ollama-url",  default=DEFAULT_OLLAMA)
    p_qry.add_argument("--model",       default=DEFAULT_MODEL)

    # ── list ──────────────────────────────────────────────────────────────────
    p_lst = sub.add_parser("list", help="List all ingested courses")
    p_lst.add_argument("--db-url", default=DEFAULT_DB_URL)

    # ── remove ────────────────────────────────────────────────────────────────
    p_rm = sub.add_parser("remove", help="Delete a course from DB and disk")
    p_rm.add_argument("--course-id", required=True)
    p_rm.add_argument("--db-url",    default=DEFAULT_DB_URL)

    args = parser.parse_args()

    dispatch = {
        "add":    cmd_add,
        "query":  cmd_query,
        "list":   cmd_list,
        "remove": cmd_remove,
    }
    dispatch[args.command](args)


if __name__ == "__main__":
    main()

