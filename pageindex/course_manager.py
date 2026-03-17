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

DEFAULT_DB_URL = "postgresql://chatbot:chatbot_secret@localhost:5432/coursechatbot"
DEFAULT_OLLAMA  = "http://localhost:11434"
DEFAULT_MODEL   = "qwen2.5:0.5b"

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
    return f"""You are a document analyst. Analyze the sample below from a {total}-{unit} document and return a JSON table-of-contents.

Rules:
- Return ONLY valid JSON. No explanation.
- Spread chapter ranges across all {total} {unit}.
- JSON format:
{{
  "title": "document title",
  "chapters": [
    {{
      "title": "Chapter title",
      "summary": "one sentence",
      "start_page": {first},
      "end_page": {total},
      "children": [
        {{"title": "Sub section", "summary": "one sentence", "start_page": {first}, "end_page": {first+5}}}
      ]
    }}
  ]
}}

Sample ({len(sample)} {unit} shown):
{pages_text}

JSON:"""


def _fallback_index(pages: dict[int, str], skip: int) -> dict:
    total = len(pages)
    doc_title = "Unknown Course"
    for p in sorted(pages.keys()):
        lines = [l.strip() for l in pages[p].splitlines() if l.strip()]
        if lines:
            doc_title = lines[0][:80]
            break
    chunk_size = 10
    start = max(1, skip + 1)
    chapters = []
    for cs in range(start, total + 1, chunk_size):
        end = min(cs + chunk_size - 1, total)
        first_text = pages.get(cs, "")
        first_line = next((l.strip() for l in first_text.splitlines() if l.strip()),
                          f"Pages {cs}-{end}")
        chapters.append({
            "title": first_line[:80],
            "summary": f"Content covering pages {cs} to {end}",
            "start_page": cs, "end_page": end, "children": []
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
        print("  ✓ LLM TOC built\n")
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
    cur.execute(
        """
        INSERT INTO course_index (course_id, index_json)
        VALUES (%s, %s)
        ON CONFLICT (course_id) DO UPDATE SET index_json = EXCLUDED.index_json
        """,
        (course_id, json.dumps(index_no_pages)),
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


def _get_context(index: dict, start: int, end: int, max_pages: int = 3,
                 max_chars: int = 1200) -> str:
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


def cmd_query(args: argparse.Namespace) -> None:
    index     = load_index(args.course_id)
    title     = index.get("title", args.course_id)
    total     = index.get("total_pages", 0)
    question  = args.question

    print(f"\n{'═'*60}")
    print(f"  COURSE  : {title}  ({total} pages)")
    print(f"  QUESTION: {question}")
    print(f"{'═'*60}")

    t0 = time.time()

    # 1. Find best section (keyword scoring)
    start, end, section_title = _select_section(index, question)
    print(f"\n  📖 Section: [{start}-{end}] {section_title}")

    # 2. Build context
    context = _get_context(index, start, end)
    if not context.strip():                          # widen if empty
        context = _get_context(index, max(1, start - 5), min(total, end + 5))

    # 3. Build prompt & stream answer
    source_fmt = index.get("source_format", ".pdf")
    unit = "page" if source_fmt == ".pdf" else "section"

    prompt = f"""You are a helpful course assistant for "{title}".
Answer the question ONLY using the provided context. Be concise (4-6 sentences).

Context ({unit}s {start}-{end}):
{context}

Question: {question}

Answer:"""

    print(f"\n{'─'*60}")
    answer = call_ollama_stream(prompt, args.ollama_url, args.model, num_predict=350)
    elapsed = time.time() - t0

    print(f"{'─'*60}")
    print(f"  Source: {unit}s {start}-{end}  |  '{title}'")
    print(f"  Time  : {elapsed:.1f}s")
    print(f"{'═'*60}")


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
  python3 course_manager.py add \\
      --input textbooks/java.pdf --course-id java-basics \\
      --ollama-url http://100.114.88.111:11434

  python3 course_manager.py add \\
      --input notes/python.md --course-id python-intro

  python3 course_manager.py query \\
      --course-id java-basics --question "Explain switch statement"

  python3 course_manager.py list

  python3 course_manager.py remove --course-id java-basics
""",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # ── add ──────────────────────────────────────────────────────────────────
    p_add = sub.add_parser("add", help="Index a file and ingest into DB")
    p_add.add_argument("--input",      required=True, help=".pdf / .md / .txt file path")
    p_add.add_argument("--course-id",  required=True, help="Unique course identifier  (e.g. java-basics)")
    p_add.add_argument("--skip-pages", type=int, default=0,
                       help="Skip first N pages (PDF front matter). 0 = auto-detect.")
    p_add.add_argument("--ollama-url", default=DEFAULT_OLLAMA)
    p_add.add_argument("--model",      default=DEFAULT_MODEL)
    p_add.add_argument("--db-url",     default=DEFAULT_DB_URL)

    # ── query ─────────────────────────────────────────────────────────────────
    p_qry = sub.add_parser("query", help="Ask a question about a course")
    p_qry.add_argument("--course-id",  required=True)
    p_qry.add_argument("--question",   required=True)
    p_qry.add_argument("--ollama-url", default=DEFAULT_OLLAMA)
    p_qry.add_argument("--model",      default=DEFAULT_MODEL)

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

