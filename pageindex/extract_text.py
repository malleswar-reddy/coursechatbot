"""
extract_text.py — Multi-format text extractor for PageIndex.

Supported formats
-----------------
  .pdf          → pdfplumber  (text layer)
                  → PyMuPDF + pytesseract OCR  (auto-fallback for scanned PDFs)
  .md / .markdown → split by headings (one "page" = one ## section)
  .txt / .text    → split into 500-word chunks (one "page" = one chunk)
  anything else   → treated as plain text

Returns: dict[int, str]   1-based page/chunk number → plain text
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

WORDS_PER_CHUNK = 500   # for .txt chunk size


# ── PDF helpers ───────────────────────────────────────────────────────────────

def _has_text_layer(pdf_path: Path, sample_pages: int = 5) -> bool:
    """Return True if at least one of the first N pages has extractable text."""
    try:
        import pdfplumber
        with pdfplumber.open(pdf_path) as pdf:
            for page in pdf.pages[:sample_pages]:
                if (page.extract_text() or "").strip():
                    return True
        return False
    except Exception:
        return False


def _extract_pdf_text_layer(path: Path) -> dict[int, str]:
    """Extract text page-by-page using pdfplumber (for PDFs with a text layer)."""
    try:
        import pdfplumber
    except ImportError:
        sys.exit("❌  pdfplumber not installed — run: pip3 install pdfplumber")

    pages: dict[int, str] = {}
    with pdfplumber.open(path) as pdf:
        total = len(pdf.pages)
        print(f"    PDF: {total} pages found", flush=True)
        for i, page in enumerate(pdf.pages, start=1):
            text = page.extract_text() or ""
            pages[i] = text.strip()
            if i % 20 == 0 or i == total:
                print(f"    Extracted {i}/{total} pages …", flush=True)
    return pages


def _extract_pdf_ocr(path: Path) -> dict[int, str]:
    """
    OCR fallback for scanned / image-only PDFs.
    Uses PyMuPDF to render each page as a high-res image,
    then pytesseract to extract text.
    """
    try:
        import fitz          # PyMuPDF
    except ImportError:
        sys.exit("❌  pymupdf not installed — run: pip3 install pymupdf")
    try:
        import pytesseract
        from PIL import Image
        import io
    except ImportError:
        sys.exit("❌  pytesseract / pillow not installed — run: pip3 install pytesseract pillow")

    pages: dict[int, str] = {}
    doc = fitz.open(str(path))
    total = len(doc)
    print(f"    PDF (scanned): {total} pages — running OCR …", flush=True)

    for i, page in enumerate(doc, start=1):
        # Render at 300 DPI for best OCR accuracy
        mat  = fitz.Matrix(300 / 72, 300 / 72)
        pix  = page.get_pixmap(matrix=mat, colorspace=fitz.csRGB)
        img  = Image.open(io.BytesIO(pix.tobytes("png")))
        text = pytesseract.image_to_string(img, lang="eng",
                                           config="--psm 6 --oem 3")
        pages[i] = text.strip()
        if i % 5 == 0 or i == total:
            print(f"    OCR {i}/{total} pages …", flush=True)

    doc.close()
    return pages


def extract_pdf(path: Path) -> dict[int, str]:
    """
    Smart PDF extractor:
      1. Try pdfplumber (text layer).
      2. If pages are blank → auto-fallback to OCR.
    """
    print(f"📄  Extracting: {path.name}  [PDF]", flush=True)

    if _has_text_layer(path):
        print("    ✓ Text layer detected — using pdfplumber", flush=True)
        return _extract_pdf_text_layer(path)
    else:
        print("    ⚠  No text layer — switching to OCR (PyMuPDF + Tesseract)", flush=True)
        return _extract_pdf_ocr(path)


# ── Markdown ──────────────────────────────────────────────────────────────────

def strip_markdown(text: str) -> str:
    """Remove common Markdown syntax, leaving clean plain text."""
    # Fenced code blocks
    text = re.sub(r"```[\s\S]*?```", "", text)
    # Inline code
    text = re.sub(r"`([^`]+)`", r"\1", text)
    # Images
    text = re.sub(r"!\[.*?\]\(.*?\)", "", text)
    # Links
    text = re.sub(r"\[([^\]]+)\]\([^\)]+\)", r"\1", text)
    # Bold / italic
    text = re.sub(r"\*{1,3}([^*\n]+)\*{1,3}", r"\1", text)
    text = re.sub(r"_{1,3}([^_\n]+)_{1,3}", r"\1", text)
    # Headings → keep text, drop #
    text = re.sub(r"^#{1,6}\s+", "", text, flags=re.MULTILINE)
    # Horizontal rules
    text = re.sub(r"^[-*_]{3,}\s*$", "", text, flags=re.MULTILINE)
    # HTML tags
    text = re.sub(r"<[^>]+>", "", text)
    # Collapse excess blank lines
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def extract_md(path: Path) -> dict[int, str]:
    """
    Split a Markdown file into logical 'pages' by heading level.
    Each top-level (# / ##) section becomes one page.
    """
    raw = path.read_text(encoding="utf-8")

    # Split on heading lines; keep the heading as the first line of each section
    parts = re.split(r"(?m)(^#{1,3}\s+.+$)", raw)

    pages: dict[int, str] = {}
    page_num = 0
    current_lines: list[str] = []

    def flush() -> None:
        nonlocal page_num
        combined = "\n".join(current_lines)
        text = strip_markdown(combined).strip()
        if len(text) > 50:          # skip near-empty sections
            page_num += 1
            pages[page_num] = text

    for part in parts:
        if re.match(r"^#{1,3}\s+", part):
            if current_lines:
                flush()
                current_lines = []
            current_lines.append(part)
        else:
            current_lines.append(part)

    if current_lines:
        flush()

    print(f"    Markdown: {page_num} sections → logical pages", flush=True)
    return pages


# ── Plain text ────────────────────────────────────────────────────────────────

def extract_txt(path: Path) -> dict[int, str]:
    """Split a plain-text file into fixed-size word chunks."""
    raw = path.read_text(encoding="utf-8", errors="replace")
    words = raw.split()
    total_words = len(words)

    pages: dict[int, str] = {}
    page_num = 0
    for i in range(0, total_words, WORDS_PER_CHUNK):
        chunk = " ".join(words[i: i + WORDS_PER_CHUNK])
        if chunk.strip():
            page_num += 1
            pages[page_num] = chunk

    print(f"    Text: {total_words:,} words → {page_num} chunks (pages)", flush=True)
    return pages


# ── Public entry-point ────────────────────────────────────────────────────────

def extract_text(file_path: str | Path) -> dict[int, str]:
    """
    Detect the file format from its extension and extract text into pages.

    Parameters
    ----------
    file_path : path to the source file (PDF / MD / TXT)

    Returns
    -------
    dict[int, str]
        1-based page/section/chunk number → plain text content
    """
    path = Path(file_path)
    if not path.exists():
        sys.exit(f"❌  File not found: {path}")

    ext = path.suffix.lower()
    fmt_label = {
        ".pdf": "PDF", ".md": "Markdown", ".markdown": "Markdown",
        ".txt": "Text", ".text": "Text",
    }.get(ext, f"Text (unknown ext '{ext}')")

    print(f"📄  Extracting: {path.name}  [{fmt_label}]", flush=True)

    if ext == ".pdf":
        return extract_pdf(path)
    elif ext in (".md", ".markdown"):
        return extract_md(path)
    else:
        return extract_txt(path)

