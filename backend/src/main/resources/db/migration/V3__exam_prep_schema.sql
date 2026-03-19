-- =============================================================================
-- V3__exam_prep_schema.sql
-- Exam Prep AI — session tracking, performance metrics, branch/subject metadata
-- =============================================================================

-- Add branch / subject metadata columns to course_index
ALTER TABLE course_index ADD COLUMN IF NOT EXISTS branch  VARCHAR(50);
ALTER TABLE course_index ADD COLUMN IF NOT EXISTS subject VARCHAR(100);

-- Track study sessions (one row per chat session)
CREATE TABLE IF NOT EXISTS chat_session (
    session_id    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    course_id     VARCHAR(255) NOT NULL,
    mode          VARCHAR(20)  NOT NULL DEFAULT 'LEARN',
    difficulty    VARCHAR(20)  NOT NULL DEFAULT 'INTERMEDIATE',
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    message_count INT          NOT NULL DEFAULT 0,
    hint_count    INT          NOT NULL DEFAULT 0
);

-- Track individual exam interactions within a session
CREATE TABLE IF NOT EXISTS exam_performance (
    id            BIGSERIAL    PRIMARY KEY,
    session_id    UUID         REFERENCES chat_session(session_id),
    question      TEXT,
    response_type VARCHAR(20),   -- HINT | EXPLAINED | REFUSED
    concept_tag   VARCHAR(100),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_chat_session_course ON chat_session(course_id);
CREATE INDEX IF NOT EXISTS idx_exam_perf_session   ON exam_performance(session_id);

