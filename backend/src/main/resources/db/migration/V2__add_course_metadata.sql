-- =============================================================================
-- V2__add_course_metadata.sql
-- Example: add a title and description column to course_index
-- =============================================================================
-- ⚠️  IMPORTANT FLYWAY RULES:
--   1. NEVER edit a migration that has already been applied (V1, etc.)
--      Flyway stores a checksum — editing will cause validate to FAIL.
--   2. Always add a NEW file for new changes (V2, V3, V4 …)
--   3. Version numbers must be unique and increasing.
--   4. Description uses underscores → stored as spaces in flyway_schema_history.
-- =============================================================================

-- Add a human-readable title column to course_index
ALTER TABLE course_index
    ADD COLUMN IF NOT EXISTS title       VARCHAR(500),
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS created_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_at  TIMESTAMP WITH TIME ZONE DEFAULT NOW();

-- Add full-text search index on course_content for faster keyword queries
CREATE INDEX IF NOT EXISTS idx_course_content_text
    ON course_content USING gin(to_tsvector('english', COALESCE(content, '')));

-- Add a column to track which file was the original source
ALTER TABLE course_content
    ADD COLUMN IF NOT EXISTS source_file VARCHAR(500);

