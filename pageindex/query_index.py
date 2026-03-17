"""
query_index.py — Fast RAG query using PageIndex.

Speed optimisations:
- Single LLM call approach: combine page selection + answer in ONE prompt
- Context hard-capped at 800 chars (2 pages max)
- num_predict reduced to 250 tokens
- Model kept warm with keep_alive
- Streaming with live token print to terminal
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


def warmup_model(ollama_url: str, model: str) -> None:
    """Send a tiny request to pre-load the model into RAM before the real query."""
    try:
        url = f"{ollama_url.rstrip('/')}/api/generate"
        requests.post(url, json={"model": model, "prompt": " ", "stream": False,
                                 "keep_alive": "10m", "options": {"num_predict": 1}},
                      timeout=30)
    except Exception:
        pass  # warmup failure is non-fatal


def build_combined_prompt(index: dict, question: str) -> str:
    """Single prompt: find best page range AND answer the question together."""
    chapters = index.get("chapters", [])
    toc_lines = []
    for ch in chapters:
        toc_lines.append(f"- {ch['title']} (p{ch['start_page']}-{ch['end_page']}): {ch['summary']}")
        for sub in ch.get("children", []):
            toc_lines.append(f"  - {sub['title']} (p{sub['start_page']}-{sub['end_page']}): {sub['summary']}")
    toc_text = "\n".join(toc_lines)

    # Pick the 2 most relevant pages based on question keywords
    pages = index.get("pages", {})
    question_words = set(question.lower().split())
    scored = []
    for p, text in pages.items():
        if not text.strip():
            continue
        score = sum(1 for w in question_words if w in text.lower())
        scored.append((score, int(p), text))
    scored.sort(reverse=True)
    top_pages = scored[:2]

    context = "\n\n".join(
        f"[Page {p}]\n{text[:400]}"
        for _, p, text in top_pages
        if text.strip()
    )

    return f"""You are a Java course assistant. Answer the student question using the context below.
Be concise — maximum 5 sentences.

Course TOC:
{toc_text}

Relevant page content:
{context}

Student Question: {question}

Answer:""", [p for _, p, _ in top_pages]


def call_ollama_streaming(prompt: str, ollama_url: str, model: str) -> str:
    """Stream tokens, print them live, return full response."""
    url = f"{ollama_url.rstrip('/')}/api/generate"
    payload = {
        "model": model,
        "prompt": prompt,
        "stream": True,
        "keep_alive": "10m",   # keep model in RAM for 10 min
        "options": {
            "temperature": 0.1,
            "num_predict": 250,  # short answer = fast
            "num_ctx": 1024,     # small context window = fast
        },
    }

    print("\nANSWER: ", end="", flush=True)
    tokens = []
    with requests.post(url, json=payload, stream=True, timeout=600) as resp:
        resp.raise_for_status()
        for line in resp.iter_lines():
            if line:
                chunk = json.loads(line)
                token = chunk.get("response", "")
                tokens.append(token)
                print(token, end="", flush=True)   # live streaming to terminal
                if chunk.get("done"):
                    break
    print("\n", flush=True)
    return "".join(tokens)


def extract_json(text: str) -> dict:
    code_block = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.DOTALL)
    if code_block:
        return json.loads(code_block.group(1))
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise ValueError("No JSON found.")
    return json.loads(match.group())


def get_page_context(index: dict, start_page: int, end_page: int, max_pages: int = 2) -> str:
    pages = index.get("pages", {})
    texts = []
    count = 0
    for p in range(start_page, end_page + 1):
        if count >= max_pages:
            break
        text = pages.get(str(p), "").strip()
        if text:
            texts.append(f"[Page {p}]\n{text[:400]}")
            count += 1
    return "\n\n".join(texts)


def select_pages(index: dict, question: str, ollama_url: str, model: str, total_pages: int):
    """Fast page selection using keyword scoring — no LLM call needed."""
    chapters = index.get("chapters", [])
    if not chapters:
        return 31, 40, "fallback"

    question_lower = question.lower()
    best_score = -1
    best_ch = chapters[0]
    best_sub = None

    for ch in chapters:
        # Score chapter
        score = sum(1 for w in ch['summary'].lower().split() if w in question_lower)
        score += sum(1 for w in ch['title'].lower().split() if w in question_lower) * 2
        # Check children first (more precise)
        for sub in ch.get("children", []):
            sub_score = sum(1 for w in sub['summary'].lower().split() if w in question_lower)
            sub_score += sum(1 for w in sub['title'].lower().split() if w in question_lower) * 2
            if sub_score > best_score:
                best_score = sub_score
                best_ch = ch
                best_sub = sub
        if score > best_score and best_sub is None:
            best_score = score
            best_ch = ch

    target = best_sub if best_sub else best_ch
    return target["start_page"], target["end_page"], target["title"]


def main() -> None:
    parser = argparse.ArgumentParser(description="Fast PageIndex query")
    parser.add_argument("--index", required=True)
    parser.add_argument("--question", required=True)
    parser.add_argument("--ollama-url", default="http://localhost:11434")
    parser.add_argument("--model", default="qwen2.5:0.5b")
    args = parser.parse_args()

    index_path = Path(args.index)
    if not index_path.exists():
        print(f"ERROR: Index not found: {index_path}", file=sys.stderr)
        sys.exit(1)

    import time
    t0 = time.time()

    index = load_index(str(index_path))
    total_pages = index.get("total_pages", 0)
    print(f"Course  : {index.get('title', 'Unknown')} ({total_pages} pages)")
    print(f"Question: {args.question}")

    # ── Step 1: Keyword-based page selection (instant, no LLM) ────────────────
    print("\n[1/2] Finding relevant section (keyword match) ...", flush=True)
    start_page, end_page, reason = select_pages(
        index, args.question, args.ollama_url, args.model, total_pages
    )
    print(f"  -> Pages {start_page}-{end_page}: {reason}")

    # ── Step 2: Get context and stream answer ─────────────────────────────────
    print("\n[2/2] Streaming answer from LLM ...", flush=True)
    context = get_page_context(index, start_page, end_page, max_pages=2)

    if not context.strip():
        start_page = max(1, start_page - 5)
        end_page = min(total_pages, end_page + 5)
        context = get_page_context(index, start_page, end_page, max_pages=2)

    if not context.strip():
        print("ERROR: No page content found.", file=sys.stderr)
        sys.exit(1)

    # Build prompt with page context
    chapters = index.get("chapters", [])
    toc_lines = []
    for ch in chapters:
        toc_lines.append(f"- {ch['title']} (p{ch['start_page']}-{ch['end_page']})")
        for sub in ch.get("children", []):
            toc_lines.append(f"  - {sub['title']} (p{sub['start_page']}-{sub['end_page']})")

    prompt = f"""You are a Java course assistant. Answer concisely in 4-5 sentences using ONLY the context.

Context ({len(context)} chars):
{context[:800]}

Question: {args.question}

Answer:"""

    answer = call_ollama_streaming(prompt, args.ollama_url, args.model)

    elapsed = time.time() - t0
    print("=" * 55)
    print(f"Source : pages {start_page}-{end_page} | '{index.get('title', '')}'")
    print(f"Time   : {elapsed:.1f}s")
    print("=" * 55)


if __name__ == "__main__":
    main()
