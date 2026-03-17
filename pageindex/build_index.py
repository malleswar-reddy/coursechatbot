"""
build_index.py — Build a hierarchical PageIndex from a course document.

Supported input formats
-----------------------
  .pdf          — extracted page-by-page via pdfplumber
  .md / .markdown — split by ## headings into logical pages
  .txt / .text    — split into 500-word chunks

Improvements
------------
- Multi-format support (PDF, Markdown, plain text)
- Streaming API (no timeout waiting for full response)
- Skips blank / front-matter pages intelligently
- Only 10 best content pages × 300 chars sent to LLM (small prompt = fast)
- Timeout raised to 600s
- Fallback chunk-based index if LLM fails

Usage
-----
    python3 build_index.py --input course.pdf \
        [--output course_index.json] \
        [--ollama-url http://100.114.88.111:11434] \
        [--model qwen2.5:0.5b] \
        [--skip-pages 30]

    # Markdown / plain text also work:
    python3 build_index.py --input notes.md
    python3 build_index.py --input lecture.txt
"""

import argparse
import json
import re
import sys
from pathlib import Path

import requests

from extract_text import extract_text

# Words that indicate a page is front-matter / TOC, not real content
_FRONT_MATTER_KEYWORDS = {"contents", "preface", "index", "copyright", "edition",
                          "intentionally left blank", "about the author", "table of"}


def is_front_matter(text: str) -> bool:
    """Return True if the page looks like a TOC / blank / front-matter page."""
    t = text.lower().strip()
    if len(t) < 80:
        return True  # too short → blank or near-blank
    if t.count("\n") > 15 and len(t) / (t.count("\n") + 1) < 20:
        return True  # many short lines → table of contents
    for kw in _FRONT_MATTER_KEYWORDS:
        if kw in t[:200]:
            return True
    return False



def build_toc_prompt(pages: dict[int, str], skip_pages: int = 0) -> str:
    """Build a compact prompt using 10 best real-content pages."""
    total = len(pages)

    # Filter: skip explicitly skipped pages + front matter + blank pages
    content_pages = {}
    for num, text in pages.items():
        if int(num) <= skip_pages:
            continue
        if len(text.strip()) < 100:
            continue
        if is_front_matter(text):
            continue
        content_pages[num] = text

    # Fall back to any non-blank page if filter was too aggressive
    if len(content_pages) < 3:
        content_pages = {n: t for n, t in pages.items() if len(t.strip()) > 80}

    sample_pages = dict(list(content_pages.items())[:10])

    pages_text = "\n\n".join(
        f"[Page {num}]\n{text[:300]}"
        for num, text in sample_pages.items()
    )

    first_content_page = min(int(n) for n in content_pages) if content_pages else 1

    return f"""You are a document analyst. Analyze the sample pages below from a {total}-page PDF and return a JSON table-of-contents.

Rules:
- Return ONLY valid JSON, nothing else. No explanation.
- The document has {total} pages total.
- Real content starts around page {first_content_page} — chapters should start from there.
- Spread chapter page ranges across all {total} pages.
- JSON format:
{{
  "title": "document title",
  "chapters": [
    {{
      "title": "Chapter title",
      "summary": "one sentence description",
      "start_page": {first_content_page},
      "end_page": {total},
      "children": [
        {{"title": "Sub section", "summary": "one sentence", "start_page": {first_content_page}, "end_page": {first_content_page + 10}}}
      ]
    }}
  ]
}}

Sample content pages ({len(sample_pages)} pages shown, starting from page {first_content_page}):
{pages_text}

JSON:"""


def call_ollama_streaming(prompt: str, ollama_url: str, model: str) -> str:
    """Call Ollama with streaming — prints dots, avoids read timeout."""
    url = f"{ollama_url.rstrip('/')}/api/generate"
    payload = {
        "model": model,
        "prompt": prompt,
        "stream": True,
        "options": {"temperature": 0.1, "num_predict": 2048},
    }

    print("  Calling LLM (streaming) ", end="", flush=True)
    full_response = []
    dot_counter = 0

    with requests.post(url, json=payload, stream=True, timeout=600) as resp:
        resp.raise_for_status()
        for line in resp.iter_lines():
            if line:
                chunk = json.loads(line)
                token = chunk.get("response", "")
                full_response.append(token)
                dot_counter += 1
                if dot_counter % 40 == 0:
                    print(".", end="", flush=True)
                if chunk.get("done"):
                    break

    print(" done!", flush=True)
    return "".join(full_response)


def extract_json(text: str) -> dict:
    """Extract the first JSON object from the LLM response."""
    code_block = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
    if code_block:
        return json.loads(code_block.group(1))
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise ValueError("No JSON object found in LLM response.")
    return json.loads(match.group())


def build_fallback_index(pages: dict[int, str], skip_pages: int = 0) -> dict:
    """Create a chunk-based index if LLM fails (every 10 pages = 1 chapter)."""
    print("  Building fallback chunk index ...", flush=True)
    total = len(pages)
    chunk_size = 10
    chapters = []

    # Title from first non-blank page
    doc_title = "Unknown Course"
    for p in sorted(pages.keys()):
        lines = [l.strip() for l in pages[p].splitlines() if l.strip()]
        if lines:
            doc_title = lines[0][:80]
            break

    start = max(1, skip_pages + 1)
    for chunk_start in range(start, total + 1, chunk_size):
        end = min(chunk_start + chunk_size - 1, total)
        first_text = pages.get(chunk_start, "")
        first_line = next(
            (ln.strip() for ln in first_text.splitlines() if ln.strip()),
            f"Pages {chunk_start}-{end}"
        )
        chapters.append({
            "title": first_line[:80],
            "summary": f"Content covering pages {chunk_start} to {end}",
            "start_page": chunk_start,
            "end_page": end,
            "children": []
        })

    return {"title": doc_title, "chapters": chapters}


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build a PageIndex from a PDF, Markdown, or text file"
    )
    parser.add_argument(
        "--input", "--pdf", dest="input", required=True,
        help="Path to the source file (.pdf / .md / .txt)"
    )
    parser.add_argument("--output", default="", help="Output JSON file path")
    parser.add_argument("--ollama-url", default="http://localhost:11434")
    parser.add_argument("--model", default="qwen2.5:0.5b")
    parser.add_argument("--skip-pages", type=int, default=0,
                        help="Skip first N pages/sections (front matter, TOC). Auto-detected if 0.")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        print(f"ERROR: File not found: {input_path}", file=sys.stderr)
        sys.exit(1)

    output_path = (
        Path(args.output) if args.output
        else input_path.with_name(input_path.stem + "_index.json")
    )

    # ── Step 1: Extract all pages ──────────────────────────────────────────────
    print(f"\n[1/3] Extracting text from: {input_path.name}")
    pages = extract_text(str(input_path))
    print(f"  ✓ {len(pages)} pages/sections extracted.")

    # Auto-detect skip_pages if not specified (PDF front-matter detection)
    skip_pages = args.skip_pages
    if skip_pages == 0 and input_path.suffix.lower() == ".pdf":
        for num in sorted(pages.keys()):
            if len(pages[num].strip()) > 100 and not is_front_matter(pages[num]):
                skip_pages = num - 1
                break
        print(f"  Auto-detected: skipping first {skip_pages} front-matter pages.")

    # ── Step 2: Build TOC via LLM ──────────────────────────────────────────────
    print(f"\n[2/3] Building TOC via LLM ({args.model}) ...")
    prompt = build_toc_prompt(pages, skip_pages)
    print(f"  Prompt size : {len(prompt):,} characters")
    print(f"  Skip pages  : {skip_pages}")

    try:
        raw_response = call_ollama_streaming(prompt, args.ollama_url, args.model)
        index = extract_json(raw_response)
        print("  ✓ LLM index built successfully.")
    except Exception as exc:
        print(f"\n  WARNING: LLM failed ({exc})", file=sys.stderr)
        print("  → Using fallback chunk-based index.", file=sys.stderr)
        index = build_fallback_index(pages, skip_pages)

    # ── Step 3: Attach pages and save ─────────────────────────────────────────
    print(f"\n[3/3] Saving index to: {output_path}")
    index["total_pages"] = len(pages)
    index["source_file"] = str(input_path)
    index["pages"] = {str(k): v for k, v in pages.items()}
    output_path.write_text(json.dumps(index, indent=2, ensure_ascii=False))

    print(f"\n{'='*55}")
    print(f"✅  Done!")
    print(f"   Title       : {index.get('title', 'N/A')}")
    print(f"   Total pages : {index.get('total_pages', 0)}")
    print(f"   Chapters    : {len(index.get('chapters', []))}")
    print(f"   Output      : {output_path}")
    print(f"{'='*55}")
    print("\nFull TOC:")
    for ch in index.get("chapters", []):
        print(f"  [{ch['start_page']}-{ch['end_page']}] {ch['title']}")
        for sub in ch.get("children", []):
            print(f"    [{sub['start_page']}-{sub['end_page']}] {sub['title']}")


if __name__ == "__main__":
    main()
