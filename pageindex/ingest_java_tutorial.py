"""
ingest_java_tutorial.py — ingest the pre-built java-tutorial-6e index into PostgreSQL.
Run from: pageindex/
"""
import json, sys
from pathlib import Path

DB  = "postgresql://chatbot:chatbot_secret@100.114.88.111:5432/coursechatbot"
IDX = Path(__file__).parent / "courses" / "java-tutorial-6e_index.json"

try:
    import psycopg2
except ImportError:
    sys.exit("❌  pip3 install psycopg2-binary")

idx       = json.loads(IDX.read_text())
course_id = "java-tutorial-6e"
pages     = idx.pop("pages", {})

conn = psycopg2.connect(DB)
cur  = conn.cursor()

cur.execute("""
    INSERT INTO course_index (course_id, index_json, branch, subject, title)
    VALUES (%s, %s, %s, %s, %s)
    ON CONFLICT (course_id) DO UPDATE
        SET index_json = EXCLUDED.index_json,
            branch     = EXCLUDED.branch,
            subject    = EXCLUDED.subject,
            title      = EXCLUDED.title
""", (course_id, json.dumps(idx), "CSE", "Java Programming", idx.get("title")))

inserted = 0
for pg_str, content in pages.items():
    cur.execute("""
        INSERT INTO course_content (course_id, page_number, content)
        VALUES (%s, %s, %s)
        ON CONFLICT (course_id, page_number) DO UPDATE SET content = EXCLUDED.content
    """, (course_id, int(pg_str), content))
    inserted += 1

conn.commit()
cur.close()
conn.close()

print(f"✅  Ingested '{course_id}'")
print(f"   Pages   : {inserted}")
print(f"   Title   : {idx.get('title')}")
print(f"   Chapters: {len(idx.get('chapters', []))}")
print(f"   Branch  : CSE  |  Subject : Java Programming")

