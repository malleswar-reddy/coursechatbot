"""
pdf_to_md.py — Convert a PDF file to Markdown using pandoc + pdfplumber.

Strategy
--------
1. Use pdfplumber to extract plain text from every page (handles
   text-based PDFs well, no image rendering needed).
2. Write the text to a temporary .txt file.
3. Call pandoc via pypandoc to convert that .txt → .md, which gives
   us proper heading detection, paragraph wrapping, etc.
4. Save the result next to the source PDF (or to --output path).

Usage
-----
    python3 pdf_to_md.py --pdf testinout/9780134034089.pdf
    python3 pdf_to_md.py --pdf testinout/9780134034089.pdf --output out.md
"""

from __future__ import annotations

import argparse
import sys
import tempfile
from pathlib import Path


# ---------------------------------------------------------------------------
# Core function
# ---------------------------------------------------------------------------

def pdf_to_md(pdf_path: str | Path, output_path: str | Path | None = None) -> Path:
    """
    Convert *pdf_path* to a Markdown file and return the output Path.

    Parameters
    ----------
    pdf_path   : path to the source PDF
    output_path: where to write the .md file (default: same dir / same stem)

    Returns
    -------
    Path to the created Markdown file.
    """
    try:
        import pdfplumber
    except ImportError:
        sys.exit("❌  pdfplumber not installed — run: pip3 install pdfplumber")

    try:
        import pypandoc
    except ImportError:
        sys.exit("❌  pypandoc not installed — run: pip3 install pypandoc")

    pdf_path = Path(pdf_path)
    if not pdf_path.exists():
        sys.exit(f"❌  PDF not found: {pdf_path}")

    # Default output: same folder, same stem, .md extension
    if output_path is None:
        output_path = pdf_path.with_suffix(".md")
    output_path = Path(output_path)

    # ── Step 1: extract text from every PDF page ──────────────────────────
    print(f"📄  Opening PDF: {pdf_path}")
    pages_text: list[str] = []
    with pdfplumber.open(pdf_path) as pdf:
        total = len(pdf.pages)
        print(f"    Total pages: {total}")
        for i, page in enumerate(pdf.pages, start=1):
            text = page.extract_text() or ""
            pages_text.append(text.strip())
            if i % 20 == 0 or i == total:
                print(f"    Extracted {i}/{total} pages …", flush=True)

    # Join pages with a clear page-break marker that pandoc will preserve
    full_text = "\n\n---\n\n".join(
        f"<!-- page {i+1} -->\n{t}" for i, t in enumerate(pages_text) if t
    )

    # ── Step 2: write to a temp .txt file ────────────────────────────────
    with tempfile.NamedTemporaryFile(
        mode="w", suffix=".txt", delete=False, encoding="utf-8"
    ) as tmp:
        tmp.write(full_text)
        tmp_path = tmp.name

    # ── Step 3: convert txt → md via pandoc ──────────────────────────────
    print(f"🔄  Running pandoc: txt → markdown …")
    try:
        md_content = pypandoc.convert_file(
            tmp_path,
            to="markdown",
            format="markdown",          # input is already plain-text/markdown-like
            extra_args=["--wrap=none"], # no hard line-wraps
        )
    finally:
        Path(tmp_path).unlink(missing_ok=True)  # clean up temp file

    # ── Step 4: save Markdown ─────────────────────────────────────────────
    output_path.write_text(md_content, encoding="utf-8")
    print(f"✅  Markdown saved → {output_path}  ({len(md_content):,} chars)")
    return output_path


# ---------------------------------------------------------------------------
# CLI entry-point
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Convert a PDF to Markdown using pdfplumber + pandoc"
    )
    parser.add_argument("--pdf",    required=True, help="Path to input PDF")
    parser.add_argument("--output", default=None,  help="Path for output .md file")
    args = parser.parse_args()

    out = pdf_to_md(args.pdf, args.output)
    print(f"\nDone! Open with:\n  open {out}")


if __name__ == "__main__":
    main()

