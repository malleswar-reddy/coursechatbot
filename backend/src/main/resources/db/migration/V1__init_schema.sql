-- V1__init_schema.sql
-- Initial schema for the PageIndex Course Chatbot

-- Stores the hierarchical index JSON produced by build_index.py
CREATE TABLE IF NOT EXISTS course_index (
    course_id   VARCHAR(255) PRIMARY KEY,
    index_json  TEXT         NOT NULL
);

-- Stores the text of each page in a course's PDF
CREATE TABLE IF NOT EXISTS course_content (
    id          BIGSERIAL    PRIMARY KEY,
    course_id   VARCHAR(255) NOT NULL,
    page_number INTEGER      NOT NULL,
    content     TEXT,
    CONSTRAINT uq_course_page UNIQUE (course_id, page_number)
);

CREATE INDEX IF NOT EXISTS idx_course_content_course_page
    ON course_content (course_id, page_number);
