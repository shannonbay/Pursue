-- Migration: Content Moderation
-- Date: 2026-02-25

-- 1. Moderation columns on progress_entries
ALTER TABLE progress_entries
  ADD COLUMN moderation_status TEXT NOT NULL DEFAULT 'ok'
    CHECK (moderation_status IN ('ok', 'flagged', 'hidden', 'removed', 'disputed')),
  ADD COLUMN moderation_note TEXT,
  ADD COLUMN moderation_updated_at TIMESTAMPTZ;

CREATE INDEX idx_progress_entries_moderation
  ON progress_entries(moderation_status) WHERE moderation_status <> 'ok';

-- 2. content_reports table
CREATE TABLE content_reports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_user_id UUID NOT NULL REFERENCES users(id),
  content_type TEXT NOT NULL CHECK (content_type IN ('progress_entry', 'group', 'username')),
  content_id UUID NOT NULL,
  reason TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now(),
  reviewed BOOLEAN DEFAULT false,
  reviewed_at TIMESTAMPTZ,
  outcome TEXT
);
CREATE INDEX idx_content_reports_content ON content_reports(content_type, content_id);
CREATE INDEX idx_content_reports_reporter ON content_reports(reporter_user_id);
CREATE UNIQUE INDEX idx_content_reports_unique_reporter
  ON content_reports(reporter_user_id, content_type, content_id);

-- 3. content_disputes table
CREATE TABLE content_disputes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id),
  content_type TEXT NOT NULL,
  content_id UUID NOT NULL,
  user_explanation TEXT,
  created_at TIMESTAMPTZ DEFAULT now(),
  resolved BOOLEAN DEFAULT false,
  resolved_at TIMESTAMPTZ,
  outcome TEXT
);
CREATE INDEX idx_content_disputes_user ON content_disputes(user_id);
CREATE INDEX idx_content_disputes_content ON content_disputes(content_type, content_id);

-- 4. moderation_queue view
CREATE VIEW moderation_queue AS
SELECT
  pe.id,
  pe.moderation_status,
  pe.log_title,
  pe.note,
  pe.created_at,
  u.display_name AS username,
  g.name AS group_name,
  COUNT(cr.id) AS report_count,
  MAX(cr.created_at) AS latest_report_at
FROM progress_entries pe
JOIN users u ON pe.user_id = u.id
JOIN goals go ON pe.goal_id = go.id
JOIN groups g ON go.group_id = g.id
LEFT JOIN content_reports cr ON cr.content_id = pe.id AND cr.content_type = 'progress_entry'
WHERE pe.moderation_status IN ('flagged', 'hidden')
GROUP BY pe.id, u.display_name, g.name
ORDER BY latest_report_at DESC NULLS LAST;
