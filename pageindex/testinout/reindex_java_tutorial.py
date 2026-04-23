"""
reindex_java_tutorial.py
Builds a complete, accurate PageIndex for 9780134034089.pdf
(The Java Tutorial — 6th Edition, 98 pages, text-layer PDF).

TOC reconstructed from the PDF's own Contents pages (pages 7-12).
"""
import json, sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent.parent))

PDF   = Path(__file__).parent / "9780134034089.pdf"
OUT   = Path(__file__).parent.parent / "courses" / "java-tutorial-6e_index.json"
TOTAL = 98

# ── 1. Extract all page texts with pdfplumber ─────────────────────────────
try:
    import pdfplumber
except ImportError:
    sys.exit("pip3 install pdfplumber")

pages = {}
print("Extracting text …")
with pdfplumber.open(str(PDF)) as pdf:
    for i, p in enumerate(pdf.pages, 1):
        pages[i] = (p.extract_text() or "").strip()
non_empty = sum(1 for v in pages.values() if v)
print(f"  ✓ {non_empty}/{TOTAL} pages have text")

# ── 2. TOC — hand-built from the real PDF Contents pages ─────────────────
chapters = [
    {
        "title":   "Getting Started",
        "summary": "Introduction to the Java platform, JDK tools, and the Hello World program",
        "start_page": 5, "end_page": 32,
        "children": [
            {"title":"The Java Technology Phenomenon",
             "summary":"Java platform editions, virtual machine, and API overview",
             "start_page":6,"end_page":14,"children":[]},
            {"title":"The Hello World Application",
             "summary":"Creating, compiling, and running a Hello World program",
             "start_page":15,"end_page":28,"children":[]},
            {"title":"A Closer Look at Hello World",
             "summary":"Line-by-line explanation of classes, main method and println",
             "start_page":29,"end_page":32,"children":[]},
        ]
    },
    {
        "title":   "Object-Oriented Programming Concepts",
        "summary": "Objects, classes, inheritance, interfaces, and packages explained with analogies",
        "start_page": 33, "end_page": 42,
        "children": [
            {"title":"What Is an Object?","summary":"State, behavior and identity of real-world objects","start_page":34,"end_page":35,"children":[]},
            {"title":"What Is a Class?","summary":"Class as a blueprint for creating objects","start_page":36,"end_page":37,"children":[]},
            {"title":"What Is Inheritance?","summary":"Subclasses, superclasses, and the extends keyword","start_page":38,"end_page":38,"children":[]},
            {"title":"What Is an Interface?","summary":"Defining contracts and implementing interfaces","start_page":39,"end_page":39,"children":[]},
            {"title":"What Is a Package?","summary":"Organizing related types into namespaces","start_page":40,"end_page":41,"children":[]},
        ]
    },
    {
        "title":   "Language Basics",
        "summary": "Variables, primitive types, arrays, operators, expressions, and all control flow statements",
        "start_page": 43, "end_page": 86,
        "children": [
            {"title":"Variables",
             "summary":"Declaring variables, naming conventions, instance, class, local and parameter variables",
             "start_page":44,"end_page":57,"children":[]},
            {"title":"Primitive Data Types",
             "summary":"byte, short, int, long, float, double, boolean, char and their default values",
             "start_page":46,"end_page":50,"children":[]},
            {"title":"Arrays",
             "summary":"Creating, initialising, copying and multi-dimensional arrays",
             "start_page":51,"end_page":57,"children":[]},
            {"title":"Operators",
             "summary":"Assignment, arithmetic, unary, equality, relational, conditional, bitwise, bit-shift",
             "start_page":58,"end_page":72,"children":[]},
            {"title":"Expressions, Statements and Blocks",
             "summary":"How expressions form statements and how blocks group statements",
             "start_page":73,"end_page":75,"children":[]},
            {"title":"Control Flow Statements",
             "summary":"if-then, if-then-else, switch, while, do-while, for, enhanced for, break, continue, return",
             "start_page":76,"end_page":86,"children":[]},
        ]
    },
    {
        "title":   "Classes and Objects",
        "summary": "Declaring classes, member variables, methods, constructors, access control, static members",
        "start_page": 87, "end_page": 98,
        "children": [
            {"title":"Declaring Classes and Member Variables",
             "summary":"Class body, field declarations, access modifiers, static fields",
             "start_page":88,"end_page":93,"children":[]},
            {"title":"Defining Methods and Constructors",
             "summary":"Method signatures, return types, parameters, overloading, constructors",
             "start_page":92,"end_page":98,"children":[]},
            {"title":"Access Control and static Members",
             "summary":"public/protected/private, understanding static, initializing fields",
             "start_page":95,"end_page":98,"children":[]},
        ]
    },
]

# Clamp all page numbers to [1, TOTAL]
def fix(ch):
    ch["start_page"] = max(1, min(ch["start_page"], TOTAL))
    ch["end_page"]   = max(1, min(ch["end_page"],   TOTAL))
    ch["children"]   = [fix(s) for s in ch.get("children", [])]
    return ch

chapters = [fix(ch) for ch in chapters]

index = {
    "title":         "The Java Tutorial — Sixth Edition",
    "total_pages":   TOTAL,
    "course_id":     "java-tutorial-6e",
    "source_file":   str(PDF),
    "source_format": ".pdf",
    "chapters":      chapters,
    "pages":         {str(k): v for k, v in pages.items()},
}

OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(index, indent=2, ensure_ascii=False))
print(f"✓ Index saved → {OUT}")

# ── Summary ───────────────────────────────────────────────────────────────
print(f"\n{'═'*55}")
print(f"  Title   : {index['title']}")
print(f"  Pages   : {TOTAL}")
print(f"  Chapters: {len(chapters)}")
for ch in chapters:
    print(f"  [{ch['start_page']:2d}-{ch['end_page']:2d}] {ch['title']}")
    for s in ch['children']:
        print(f"         [{s['start_page']:2d}-{s['end_page']:2d}] {s['title']}")
print(f"{'═'*55}")

