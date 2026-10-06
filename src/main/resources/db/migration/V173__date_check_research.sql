-- V173: web research state per date check. Existing rows never ran research, so they take 'off'.
-- research_queued_at backs the per-org and global daily research caps.

ALTER TABLE date_check ADD COLUMN research_status VARCHAR(16) NOT NULL DEFAULT 'off';
ALTER TABLE date_check ADD COLUMN research_queued_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE date_check ADD CONSTRAINT ck_date_check_research_status
    CHECK (research_status IN ('off','running','done','failed'));
ALTER TABLE date_check ADD CONSTRAINT ck_date_check_research_off
    CHECK (research OR research_status = 'off');
CREATE INDEX ix_date_check_research_queued ON date_check (research_queued_at);
