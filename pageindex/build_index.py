"""
build_index.py — Build a hierarchical PageIndex from a PDF course document.

Usage:
    python build_index.py --pdf course.pdf [--output course_index.json] [--ollama-url http://localhost:11434] [--model qwen2.5:7b]

This script:
1. Extracts text from every page of the PDF.
2. Uses the LLM to generate a hierarchical table-of-contents JSON index.
3. Saves the index to a JSON file for later retrieval.
"""

import argparse
import json
import re
import sys
from pathlib import Path

import pdfplumber
import requests


def extract_pages(pdf_path: str) -> dict[int, str]:
    """Return a mapping of page_number (1-based) → page text."""
    pages: dict[int, str] = {}
    with pdfplumber.open(pdf_path) as pdf:
        for i, page in enumerate(pdf.pages, start=1):
            text = page.extract_text() or ""
            pages[i] = text.strip()
    return pages


def build_toc_prompt(pages: dict[int, str]) -> str:
    """Build the prompt that asks the LLM to produce the hierarchical index."""
    # Use only the first 30 pages (or all if fewer) to keep the prompt tractable.
    sample_pages = dict(list(pages.items())[:30])
    pages_text = "\n\n".join(
        f"[Page {num}]\n{text[:800]}" for num, text in sample_pages.items()
    )

    total = len(pages)
    return f"""You are a document analyst. The following text is extracted from a PDF document with {total} pages.
Analyze the content and create a hierarchical table-of-contents index in JSON format.

Rules:
- Return ONLY valid JSON, nothing else.
- The JSON must have a "title" field (string) for the document name.
- The JSON must have a "chapters" array where each chapter has:
    - "title": chapter or section title (string)
    - "summary": one-sentence description (string)
    - "start_page": first page number (integer, 1-based)
    - "end_page": last page number (integer, 1-based)
    - "children": optional array of sub-chapters with the same structure

Sample pages (first {len(sample_pages)} of {total}):

{pages_text}

Return the JSON index now:"""


def call_ollama(prompt: str, ollama_url: str, model: str) -> str:
    """Call the Ollama generate API and return the response text."""
    url = f"{ollama_url.rstrip('/')}/api/generate"
    payload = {
        "model": model,
        "prompt": prompt,
        "stream": False,
        "options": {"temperature": 0.1, "num_predict": 4096},
    }
    resp = requests.post(url, json=payload, timeout=300)
    resp.raise_for_status()
    return resp.json().get("response", "")


def extract_json(text: str) -> dict:
    """Extract the first JSON object from a string (LLM may include extra text)."""
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise ValueError("No JSON object found in LLM response.")
    return json.loads(match.group())


def main() -> None:
    parser = argparse.ArgumentParser(description="Build a PageIndex from a PDF")
    parser.add_argument("--pdf", required=True, help="Path to the PDF file")
    parser.add_argument("--output", default="", help="Output JSON file path (default: <pdf_name>_index.json)")
    parser.add_argument("--ollama-url", default="http://localhost:11434", help="Ollama base URL")
    parser.add_argument("--model", default="qwen2.5:7b", help="Ollama model name")
    args = parser.parse_args()

    pdf_path = Path(args.pdf)
    if not pdf_path.exists():
        print(f"ERROR: PDF not found: {pdf_path}", file=sys.stderr)
        sys.exit(1)

    output_path = Path(args.output) if args.output else pdf_path.with_suffix("_index.json")

    print(f"Extracting pages from {pdf_path} …")
    pages = extract_pages(str(pdf_path))
    print(f"  Extracted {len(pages)} pages.")

    print("Building hierarchical index via LLM …")
    prompt = build_toc_prompt(pages)
    raw_response = call_ollama(prompt, args.ollama_url, args.model)

    print("Parsing LLM response …")
    try:
        index = extract_json(raw_response)
    except (ValueError, json.JSONDecodeError) as exc:
        print(f"ERROR: Failed to parse LLM response as JSON: {exc}", file=sys.stderr)
        print("Raw response:", raw_response[:500], file=sys.stderr)
        sys.exit(1)

    # Attach the total page count and the raw page texts to the index.
    index["total_pages"] = len(pages)
    index["pages"] = {str(k): v for k, v in pages.items()}

    output_path.write_text(json.dumps(index, indent=2, ensure_ascii=False))
    print(f"Index saved to {output_path}")
    print(json.dumps({k: v for k, v in index.items() if k != "pages"}, indent=2))


if __name__ == "__main__":
    main()
