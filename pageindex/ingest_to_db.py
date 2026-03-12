"""
ingest_to_db.py — Load page texts from a PageIndex JSON file into PostgreSQL.

Usage:
    python ingest_to_db.py --index course_index.json --course-id course1 \
        [--db-url postgresql://chatbot:chatbot_secret@localhost:5432/coursechatbot]
"""

import argparse
import json
import sys
from pathlib import Path

import psycopg2


def main() -> None:
    parser = argparse.ArgumentParser(description="Ingest PageIndex pages into PostgreSQL")
    parser.add_argument("--index", required=True, help="Path to the index JSON file")
    parser.add_argument("--course-id", required=True, help="Unique course identifier")
    parser.add_argument(
        "--db-url",
        default="postgresql://chatbot:chatbot_secret@localhost:5432/coursechatbot",
        help="PostgreSQL connection URL",
    )
    args = parser.parse_args()

    index_path = Path(args.index)
    if not index_path.exists():
        print(f"ERROR: Index not found: {index_path}", file=sys.stderr)
        sys.exit(1)

    with open(index_path, encoding="utf-8") as f:
        index = json.load(f)

    pages: dict = index.get("pages", {})
    if not pages:
        print("ERROR: No pages found in index.", file=sys.stderr)
        sys.exit(1)

    print(f"Connecting to database …")
    conn = psycopg2.connect(args.db_url)
    cur = conn.cursor()

    # Save the course index JSON (without inline pages to save space).
    index_without_pages = {k: v for k, v in index.items() if k != "pages"}
    cur.execute(
        """
        INSERT INTO course_index (course_id, index_json)
        VALUES (%s, %s)
        ON CONFLICT (course_id) DO UPDATE SET index_json = EXCLUDED.index_json
        """,
        (args.course_id, json.dumps(index_without_pages)),
    )

    print(f"Inserting {len(pages)} pages for course '{args.course_id}' …")
    inserted = 0
    for page_num_str, content in pages.items():
        cur.execute(
            """
            INSERT INTO course_content (course_id, page_number, content)
            VALUES (%s, %s, %s)
            ON CONFLICT (course_id, page_number) DO UPDATE SET content = EXCLUDED.content
            """,
            (args.course_id, int(page_num_str), content),
        )
        inserted += 1

    conn.commit()
    cur.close()
    conn.close()

    print(f"Done. Inserted/updated {inserted} pages.")


if __name__ == "__main__":
    main()
