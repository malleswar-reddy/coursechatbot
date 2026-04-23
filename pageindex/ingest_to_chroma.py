#!/usr/bin/env python3
"""
ingest_to_chroma.py — PDF/Markdown → chunks → nomic-embed-text → ChromaDB

Usage:
  python ingest_to_chroma.py --input course.pdf --course-id java-tutorial \\
      --chroma-url http://100.114.88.111:8001 \\
      --ollama-url http://100.114.88.111:11434

  python ingest_to_chroma.py --input notes.md --course-id cse-pqb-1

Options:
  --input       Path to PDF or Markdown file
  --course-id   Collection name in ChromaDB (also used by backend)
  --chroma-url  ChromaDB URL (default: http://localhost:8001)
  --ollama-url  Ollama URL   (default: http://localhost:11434)
  --chunk-size  Characters per chunk (default: 600)
  --overlap     Overlap between chunks (default: 100)
  --embed-model Embedding model name  (default: nomic-embed-text)
  --reset       Drop existing collection before ingest (default: True for clean ingest)
"""

import argparse
import hashlib
import json
import requests
import sys
import time
from pathlib import Path

# ── Defaults ─────────────────────────────────────────────────────────────────
EMBED_MODEL  = "nomic-embed-text"
CHUNK_SIZE   = 600
CHUNK_OVERLAP = 100
BATCH_SIZE   = 50   # chunks per ChromaDB /add request

# ── ChromaDB v2 API base path ─────────────────────────────────────────────────
CHROMA_V2 = "/api/v2/tenants/default_tenant/databases/default_database"


# ══════════════════════════════════════════════════════════════════════════════
# 1. Text Extraction
# ══════════════════════════════════════════════════════════════════════════════

def extract_from_pdf(pdf_path: str) -> list[dict]:
    try:
        import pdfplumber
    except ImportError:
        print("ERROR: pdfplumber not installed. Run: pip install pdfplumber", file=sys.stderr)
        sys.exit(1)

    pages = []
    with pdfplumber.open(pdf_path) as pdf:
        for i, page in enumerate(pdf.pages, start=1):
            text = (page.extract_text() or "").strip()
            if len(text) > 50:
                pages.append({"page": i, "text": text})
    return pages


def extract_from_markdown(md_path: str) -> list[dict]:
    text = Path(md_path).read_text(encoding="utf-8")
    # Split on H1/H2 headers → treat each section as a "page"
    import re
    sections = re.split(r"(?m)^#{1,2} ", text)
    pages = []
    for i, sec in enumerate(sections, start=1):
        sec = sec.strip()
        if len(sec) > 50:
            pages.append({"page": i, "text": sec})
    return pages


def extract_text(input_path: str) -> list[dict]:
    p = Path(input_path)
    if not p.exists():
        print(f"ERROR: File not found: {input_path}", file=sys.stderr)
        sys.exit(1)
    ext = p.suffix.lower()
    if ext == ".pdf":
        return extract_from_pdf(input_path)
    elif ext in (".md", ".txt"):
        return extract_from_markdown(input_path)
    else:
        print(f"ERROR: Unsupported format '{ext}'. Use .pdf, .md, or .txt", file=sys.stderr)
        sys.exit(1)


# ══════════════════════════════════════════════════════════════════════════════
# 2. Chunking
# ══════════════════════════════════════════════════════════════════════════════

def chunk_pages(pages: list[dict], chunk_size: int, overlap: int) -> list[dict]:
    chunks = []
    for p in pages:
        text = p["text"]
        start = 0
        while start < len(text):
            end = min(start + chunk_size, len(text))
            chunk_text = text[start:end].strip()
            if len(chunk_text) > 40:
                # Deterministic ID from content
                chunk_id = hashlib.md5(f"{p['page']}:{chunk_text[:80]}".encode()).hexdigest()[:16]
                chunks.append({
                    "id":   chunk_id,
                    "text": chunk_text,
                    "page": p["page"],
                })
            start += chunk_size - overlap
    return chunks


# ══════════════════════════════════════════════════════════════════════════════
# 3. Embedding via Ollama
# ══════════════════════════════════════════════════════════════════════════════

def embed_text(text: str, ollama_url: str, model: str) -> list[float]:
    url = f"{ollama_url.rstrip('/')}/api/embeddings"
    resp = requests.post(url, json={"model": model, "prompt": text}, timeout=60)
    resp.raise_for_status()
    return resp.json()["embedding"]


def warmup_embed_model(ollama_url: str, model: str) -> None:
    print(f"  Warming up embedding model '{model}' ...")
    try:
        embed_text("warmup", ollama_url, model)
        print("  ✅ Model ready.")
    except Exception as e:
        print(f"  ⚠️  Warmup failed: {e}. Will retry on first real embed.", file=sys.stderr)


# ══════════════════════════════════════════════════════════════════════════════
# 4. ChromaDB Collection Management
# ══════════════════════════════════════════════════════════════════════════════

def delete_collection(course_id: str, chroma_url: str) -> None:
    base = chroma_url.rstrip("/")
    r = requests.delete(f"{base}{CHROMA_V2}/collections/{course_id}", timeout=10)
    if r.status_code in (200, 404):
        print(f"  Deleted existing collection '{course_id}' (or it didn't exist).")
    else:
        print(f"  ⚠️  Delete returned {r.status_code}: {r.text}", file=sys.stderr)


def create_collection(course_id: str, chroma_url: str) -> str:
    base = chroma_url.rstrip("/")
    r = requests.post(
        f"{base}{CHROMA_V2}/collections",
        json={"name": course_id, "metadata": {"hnsw:space": "cosine"}},
        timeout=10
    )
    r.raise_for_status()
    # v2 returns the collection object; id may be under "id" or "name"
    data = r.json()
    col_id = data.get("id") or data.get("name") or course_id
    print(f"  Created collection '{course_id}' → ID: {col_id}")
    return course_id  # v2: use name directly for subsequent calls


def add_batch_to_chroma(col_name: str, batch_chunks: list[dict],
                         batch_embeddings: list[list[float]], chroma_url: str) -> None:
    base = chroma_url.rstrip("/")
    payload = {
        "ids":        [c["id"]   for c in batch_chunks],
        "embeddings": batch_embeddings,
        "documents":  [c["text"] for c in batch_chunks],
        "metadatas":  [{"page": c["page"]} for c in batch_chunks],
    }
    r = requests.post(f"{base}{CHROMA_V2}/collections/{col_name}/add", json=payload, timeout=120)
    r.raise_for_status()


def verify_collection(course_id: str, chroma_url: str) -> int:
    base = chroma_url.rstrip("/")
    r = requests.get(f"{base}{CHROMA_V2}/collections/{course_id}/count", timeout=10)
    r.raise_for_status()
    return r.json()


# ══════════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════════

def main() -> None:
    parser = argparse.ArgumentParser(description="Ingest PDF/Markdown into ChromaDB via Ollama embeddings")
    parser.add_argument("--input",      required=True,  help="Path to PDF or Markdown file")
    parser.add_argument("--course-id",  required=True,  help="ChromaDB collection name (used by backend)")
    parser.add_argument("--chroma-url", default="http://localhost:8001")
    parser.add_argument("--ollama-url", default="http://localhost:11434")
    parser.add_argument("--chunk-size", type=int, default=CHUNK_SIZE)
    parser.add_argument("--overlap",    type=int, default=CHUNK_OVERLAP)
    parser.add_argument("--embed-model", default=EMBED_MODEL)
    args = parser.parse_args()

    t0 = time.time()
    print("\n" + "=" * 60)
    print(f"  ChromaDB Ingest")
    print(f"  Input      : {args.input}")
    print(f"  Course ID  : {args.course_id}")
    print(f"  Chroma     : {args.chroma_url}")
    print(f"  Ollama     : {args.ollama_url}")
    print(f"  Embed model: {args.embed_model}")
    print(f"  Chunk size : {args.chunk_size} chars / {args.overlap} overlap")
    print("=" * 60)

    # Step 1: Extract text
    print(f"\n[1/4] Extracting text ...")
    pages = extract_text(args.input)
    print(f"  → {len(pages)} sections/pages with content")

    # Step 2: Chunk
    print(f"\n[2/4] Chunking ...")
    chunks = chunk_pages(pages, args.chunk_size, args.overlap)
    print(f"  → {len(chunks)} chunks")

    # Step 3: Embed
    print(f"\n[3/4] Embedding {len(chunks)} chunks with '{args.embed_model}' ...")
    warmup_embed_model(args.ollama_url, args.embed_model)
    embeddings = []
    failed = 0
    for idx, chunk in enumerate(chunks):
        try:
            emb = embed_text(chunk["text"], args.ollama_url, args.embed_model)
            embeddings.append(emb)
        except Exception as e:
            print(f"  ⚠️  Chunk {idx} embed failed: {e}", file=sys.stderr)
            # Use zero vector as fallback so ChromaDB batch stays aligned
            embeddings.append([0.0] * 768)
            failed += 1
        if (idx + 1) % 50 == 0:
            elapsed = time.time() - t0
            print(f"  {idx + 1}/{len(chunks)} embedded — {elapsed:.0f}s elapsed")
    print(f"  → {len(embeddings)} embeddings done ({failed} fallbacks)")

    # Step 4: Store in ChromaDB
    print(f"\n[4/4] Storing in ChromaDB ...")
    delete_collection(args.course_id, args.chroma_url)
    col_id = create_collection(args.course_id, args.chroma_url)

    for i in range(0, len(chunks), BATCH_SIZE):
        batch_c = chunks[i:i + BATCH_SIZE]
        batch_e = embeddings[i:i + BATCH_SIZE]
        add_batch_to_chroma(col_id, batch_c, batch_e, args.chroma_url)
        print(f"  Stored batch {i // BATCH_SIZE + 1}/{(len(chunks) + BATCH_SIZE - 1) // BATCH_SIZE} "
              f"({len(batch_c)} chunks)")

    count = verify_collection(args.course_id, args.chroma_url)
    elapsed = time.time() - t0

    print(f"\n{'=' * 60}")
    print(f"  ✅  INGEST COMPLETE")
    print(f"  Collection  : {args.course_id}")
    print(f"  Total chunks: {count}")
    print(f"  Time taken  : {elapsed:.1f}s")
    print(f"  ChromaDB    : {args.chroma_url}")
    print(f"{'=' * 60}\n")


if __name__ == "__main__":
    main()

