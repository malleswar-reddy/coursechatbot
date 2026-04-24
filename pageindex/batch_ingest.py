#!/usr/bin/env python3
"""
batch_ingest.py — Ingest all course PDFs from .github/Doc/ into ChromaDB.

Usage:
    # Ingest all PDFs to remote server (default)
    python3 batch_ingest.py

    # Custom server URLs
    python3 batch_ingest.py \\
        --chroma-url http://100.114.88.111:8001 \\
        --ollama-url http://100.114.88.111:11434

    # Dry run — print what would be ingested
    python3 batch_ingest.py --dry-run

    # Ingest only specific course IDs (comma-separated)
    python3 batch_ingest.py --only civil-pqb-1,cse-pqb-1

    # Skip already-ingested collections
    python3 batch_ingest.py --skip-existing

Options:
    --chroma-url      ChromaDB URL (default: http://100.114.88.111:8001)
    --ollama-url      Ollama URL   (default: http://100.114.88.111:11434)
    --only            Comma-separated list of course-ids to ingest (default: all)
    --skip-existing   Skip courses already in ChromaDB
    --dry-run         Show commands without running them

NOTE: PostgreSQL is no longer used. Data is stored in ChromaDB only.
      Use --skip-pg flag is ignored (kept for backwards compatibility).
"""

from __future__ import annotations

import argparse
import subprocess
import sys
import time
from pathlib import Path

import requests

# ─── Script location ───────────────────────────────────────────────────────────
HERE    = Path(__file__).parent
DOC_DIR = HERE.parent / ".github" / "Doc"

# ChromaDB v2 base path
CHROMA_V2 = "/api/v2/tenants/default_tenant/databases/default_database"

# ─── All PDFs to ingest ────────────────────────────────────────────────────────
# Each entry: (filename, course_id, branch, subject, title)
COURSES = [
    # ── CIVIL ──────────────────────────────────────────────────────────────────
    ("CIVIL PQB.pdf",                      "civil-pqb-1",        "CIVIL",    "Previous Question Bank",   "Civil Engineering PQB"),

    # ── CSE ────────────────────────────────────────────────────────────────────
    ("CSE PQB 1.pdf",                      "cse-pqb-1",          "CSE",      "Previous Question Bank",   "Computer Science PQB - Part 1"),
    ("CSE PQB 2.pdf",                      "cse-pqb-2",          "CSE",      "Previous Question Bank",   "Computer Science PQB - Part 2"),
    ("CSE 2025 Set 1 Question.pdf",        "cse-2025-set1-q",    "CSE",      "2025 Exam Questions Set 1","CSE 2025 Set-1 Question Paper"),
    ("CSE 2025 Set 1 Key & Solutions.pdf", "cse-2025-set1-ans",  "CSE",      "2025 Answer Key Set 1",    "CSE 2025 Set-1 Answer Key"),
    ("CSE 2025 Set 2 Question.pdf",        "cse-2025-set2-q",    "CSE",      "2025 Exam Questions Set 2","CSE 2025 Set-2 Question Paper"),
    ("CSE 2025 Set 2 key & Solutions.pdf", "cse-2025-set2-ans",  "CSE",      "2025 Answer Key Set 2",    "CSE 2025 Set-2 Answer Key"),

    # ── ECE ────────────────────────────────────────────────────────────────────
    ("ECE PQB 1.pdf",                      "ece-pqb-1",          "ECE",      "Previous Question Bank",   "Electronics & Communication PQB - Part 1"),
    ("ECE PQB 2.pdf",                      "ece-pqb-2",          "ECE",      "Previous Question Bank",   "Electronics & Communication PQB - Part 2"),

    # ── EEE ────────────────────────────────────────────────────────────────────
    ("EEE PQB.pdf",                        "eee-pqb-1",          "EEE",      "Previous Question Bank",   "Electrical Engineering PQB"),

    # ── DA-AIML ────────────────────────────────────────────────────────────────
    ("DA- AIML 2025 Question.pdf",         "da-aiml-2025-q",     "DA-AIML",  "2025 Exam Questions",      "DA-AIML 2025 Question Paper"),
    ("DA -AIML 2025 Key & Solutions.pdf",  "da-aiml-2025-ans",   "DA-AIML",  "2025 Answer Key",          "DA-AIML 2025 Answer Key & Solutions"),
]


def get_existing_collections(chroma_url: str) -> set[str]:
    """Return set of collection names already in ChromaDB."""
    try:
        r = requests.get(f"{chroma_url.rstrip('/')}{CHROMA_V2}/collections", timeout=10)
        r.raise_for_status()
        return {c["name"] for c in r.json()}
    except Exception as e:
        print(f"  ⚠️  Could not fetch existing collections: {e}", file=sys.stderr)
        return set()


def check_servers(chroma_url: str, ollama_url: str) -> bool:
    """Quick connectivity check before starting batch."""
    ok = True
    try:
        r = requests.get(f"{chroma_url.rstrip('/')}/api/v2/heartbeat", timeout=5)
        r.raise_for_status()
        print(f"  ✅ ChromaDB reachable: {chroma_url}")
    except Exception as e:
        print(f"  ❌ ChromaDB NOT reachable at {chroma_url}: {e}", file=sys.stderr)
        ok = False
    try:
        r = requests.get(f"{ollama_url.rstrip('/')}/api/tags", timeout=5)
        r.raise_for_status()
        print(f"  ✅ Ollama   reachable: {ollama_url}")
    except Exception as e:
        print(f"  ❌ Ollama NOT reachable at {ollama_url}: {e}", file=sys.stderr)
        ok = False
    return ok


def run_step(cmd: list[str], label: str, dry_run: bool) -> bool:
    """Run a subprocess command. Returns True on success."""
    print(f"\n  Command: {' '.join(str(c) for c in cmd)}")
    if dry_run:
        print("  ⏭  DRY-RUN: skipped")
        return True
    t0 = time.time()
    result = subprocess.run(cmd, capture_output=False, text=True)
    elapsed = time.time() - t0
    if result.returncode != 0:
        print(f"  ❌ FAILED (exit {result.returncode}) in {elapsed:.1f}s")
        return False
    print(f"  ✅ Done in {elapsed:.1f}s")
    return True


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Batch-ingest all course PDFs into ChromaDB",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("--chroma-url",    default="http://100.114.88.111:8001")
    parser.add_argument("--ollama-url",    default="http://100.114.88.111:11434")
    parser.add_argument("--only",          default=None,
                        help="Comma-separated list of course-ids to ingest (e.g. civil-pqb-1,cse-pqb-1)")
    parser.add_argument("--skip-existing", action="store_true",
                        help="Skip courses already in ChromaDB")
    parser.add_argument("--dry-run",       action="store_true",
                        help="Show commands without running")
    # Kept for backwards compatibility — no longer does anything
    parser.add_argument("--skip-pg",       action="store_true",
                        help="(ignored — PostgreSQL no longer used)")
    parser.add_argument("--skip-chroma",   action="store_true",
                        help="Skip ChromaDB ingest (for testing)")
    parser.add_argument("--db-url",        default=None,
                        help="(ignored — PostgreSQL no longer used)")
    args = parser.parse_args()

    only_set  = {x.strip() for x in args.only.split(",")} if args.only else None
    to_ingest = [
        (f, cid, br, sub, title)
        for f, cid, br, sub, title in COURSES
        if (only_set is None or cid in only_set)
    ]

    if not to_ingest:
        print("❌ No matching courses found. Check --only values.")
        sys.exit(1)

    print(f"\n{'═'*65}")
    print(f"  BATCH INGEST — ChromaDB only (PostgreSQL removed)")
    print(f"  ChromaDB : {args.chroma_url}")
    print(f"  Ollama   : {args.ollama_url}")
    print(f"  Courses  : {len(to_ingest)}")
    print(f"  Dry Run  : {args.dry_run}")
    print(f"{'═'*65}")

    # Pre-flight connectivity check
    if not args.dry_run:
        print("\n[Pre-flight] Checking server connectivity …")
        if not check_servers(args.chroma_url, args.ollama_url):
            print("\n❌  Cannot reach servers. Check Tailscale VPN and server status.", file=sys.stderr)
            sys.exit(1)

    # Check existing collections if --skip-existing
    existing: set[str] = set()
    if args.skip_existing and not args.dry_run:
        existing = get_existing_collections(args.chroma_url)
        if existing:
            print(f"\n  Already in ChromaDB: {sorted(existing)}")

    failed    : list[str] = []
    succeeded : list[str] = []

    for idx, (filename, course_id, branch, subject, title) in enumerate(to_ingest, start=1):
        pdf_path = DOC_DIR / filename
        if not pdf_path.exists():
            print(f"\n  ⚠️  [{idx}/{len(to_ingest)}] Skipping '{filename}' — file not found at {pdf_path}")
            failed.append(course_id)
            continue

        if args.skip_existing and course_id in existing:
            print(f"\n  ⏭  [{idx}/{len(to_ingest)}] Skipping '{course_id}' — already in ChromaDB")
            succeeded.append(course_id)
            continue

        print(f"\n{'─'*65}")
        print(f"  [{idx}/{len(to_ingest)}] Course  : {course_id}")
        print(f"  Branch  : {branch}  |  Subject: {subject}")
        print(f"  PDF     : {pdf_path.name}")
        print(f"{'─'*65}")

        course_ok = True

        # ── ChromaDB vector ingest (primary store) ────────────────────────────
        if not args.skip_chroma:
            chroma_cmd = [
                sys.executable, str(HERE / "ingest_to_chroma.py"),
                "--input",      str(pdf_path),
                "--course-id",  course_id,
                "--chroma-url", args.chroma_url,
                "--ollama-url", args.ollama_url,
                "--branch",     branch,
                "--subject",    subject,
                "--title",      title,
            ]
            ok = run_step(chroma_cmd,
                          f"ChromaDB ingest → {course_id}",
                          args.dry_run)
            if not ok:
                course_ok = False

        if course_ok:
            succeeded.append(course_id)
        else:
            failed.append(course_id)

    # ── Summary ───────────────────────────────────────────────────────────────
    print(f"\n{'═'*65}")
    print(f"  BATCH INGEST COMPLETE")
    print(f"  ✅ Success : {len(succeeded)}")
    for cid in succeeded:
        print(f"      ✅  {cid}")
    if failed:
        print(f"  ❌ Failed  : {len(failed)}")
        for cid in failed:
            print(f"      ❌  {cid}")
    print(f"{'═'*65}")

    if not args.dry_run and succeeded:
        print("\n  Verify ChromaDB collections:")
        print(f"    python3 ../.github/test_chroma.py")
        print("\n  Test the chatbot API:")
        first_ok = succeeded[0]
        print(f"    curl -s -X POST http://localhost:8080/api/chat \\")
        print(f"      -H 'Content-Type: application/json' \\")
        print(f"      -d '{{\"courseId\":\"{first_ok}\",\"question\":\"Explain a key concept\",\"mode\":\"LEARN\",\"difficultyLevel\":\"INTERMEDIATE\"}}'")

    print()
    if failed:
        sys.exit(1)


if __name__ == "__main__":
    main()

