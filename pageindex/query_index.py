"""
query_index.py — Answer a student question using the PageIndex RAG flow.

Usage:
    python query_index.py --index course_index.json --question "What is Panchakarma?" \
        [--ollama-url http://localhost:11434] [--model qwen2.5:7b]

Flow:
    1. Load course_index.json (built by build_index.py).
    2. Send the hierarchical index + question to the LLM to select relevant page range.
    3. Retrieve selected page texts from the index.
    4. Send page context + question to the LLM for the final answer.
"""

import argparse
import json
import re
import sys
from pathlib import Path

import requests


def load_index(index_path: str) -> dict:
    with open(index_path, encoding="utf-8") as f:
        return json.load(f)


def build_selection_prompt(index: dict, question: str) -> str:
    """Ask the LLM to select the chapter / page range relevant to the question."""
    chapters = index.get("chapters", [])
    toc_lines = []
    for ch in chapters:
        line = f"- {ch['title']} (pages {ch['start_page']}–{ch['end_page']}): {ch['summary']}"
        toc_lines.append(line)
        for sub in ch.get("children", []):
            sub_line = f"  - {sub['title']} (pages {sub['start_page']}–{sub['end_page']}): {sub['summary']}"
            toc_lines.append(sub_line)

    toc_text = "\n".join(toc_lines) if toc_lines else "No table of contents available."

    return f"""You are a document assistant. Based on the course table of contents below, identify the most relevant page range that would answer the student's question.

Course: {index.get('title', 'Unknown')}

Table of Contents:
{toc_text}

Student Question:
{question}

Instructions:
- Return ONLY valid JSON, nothing else.
- JSON format: {{"start_page": <integer>, "end_page": <integer>, "reason": "<brief explanation>"}}
- Choose the narrowest page range that is likely to answer the question.
- If the question spans multiple chapters, extend the range accordingly.

Return JSON:"""


def build_answer_prompt(context_text: str, question: str) -> str:
    return f"""You are a knowledgeable course assistant. Use ONLY the context below to answer the student's question. If the answer is not in the context, say "I could not find an answer in the course material."

Context:
{context_text}

Student Question:
{question}

Answer:"""


def call_ollama(prompt: str, ollama_url: str, model: str) -> str:
    url = f"{ollama_url.rstrip('/')}/api/generate"
    payload = {
        "model": model,
        "prompt": prompt,
        "stream": False,
        "options": {"temperature": 0.2, "num_predict": 2048},
    }
    resp = requests.post(url, json=payload, timeout=300)
    resp.raise_for_status()
    return resp.json().get("response", "")


def extract_json(text: str) -> dict:
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise ValueError("No JSON object found in LLM response.")
    return json.loads(match.group())


def get_page_context(index: dict, start_page: int, end_page: int) -> str:
    """Retrieve and concatenate the text for the selected page range."""
    pages = index.get("pages", {})
    texts = []
    for p in range(start_page, end_page + 1):
        text = pages.get(str(p), "")
        if text:
            texts.append(f"[Page {p}]\n{text}")
    return "\n\n".join(texts)


def main() -> None:
    parser = argparse.ArgumentParser(description="Query a PageIndex for a student question")
    parser.add_argument("--index", required=True, help="Path to the index JSON file")
    parser.add_argument("--question", required=True, help="Student question")
    parser.add_argument("--ollama-url", default="http://localhost:11434")
    parser.add_argument("--model", default="qwen2.5:7b")
    args = parser.parse_args()

    index_path = Path(args.index)
    if not index_path.exists():
        print(f"ERROR: Index not found: {index_path}", file=sys.stderr)
        sys.exit(1)

    index = load_index(str(index_path))
    total_pages = index.get("total_pages", 0)
    print(f"Loaded index: {index.get('title', 'Unknown')} ({total_pages} pages)")

    # Step 1: Select relevant page range.
    print("Selecting relevant section …")
    selection_prompt = build_selection_prompt(index, args.question)
    raw_selection = call_ollama(selection_prompt, args.ollama_url, args.model)

    try:
        selection = extract_json(raw_selection)
        start_page = int(selection["start_page"])
        end_page = int(selection["end_page"])
        reason = selection.get("reason", "")
    except (ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"ERROR: Failed to parse section selection: {exc}", file=sys.stderr)
        print("Falling back to first 10 pages.")
        start_page, end_page, reason = 1, min(10, total_pages), "fallback"

    # Clamp to valid range.
    start_page = max(1, min(start_page, total_pages))
    end_page = max(start_page, min(end_page, total_pages))

    print(f"Selected pages {start_page}–{end_page}: {reason}")

    # Step 2: Retrieve context and generate answer.
    context = get_page_context(index, start_page, end_page)
    if not context.strip():
        print("No page content found for the selected range.", file=sys.stderr)
        sys.exit(1)

    print("Generating answer …")
    answer_prompt = build_answer_prompt(context, args.question)
    answer = call_ollama(answer_prompt, args.ollama_url, args.model)

    print("\n" + "=" * 60)
    print("ANSWER:")
    print("=" * 60)
    print(answer.strip())
    print("=" * 60)
    print(f"\n(Source: pages {start_page}–{end_page})")


if __name__ == "__main__":
    main()
