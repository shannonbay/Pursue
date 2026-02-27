ALTER TABLE groups
  ADD COLUMN IF NOT EXISTS language VARCHAR(10) DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_groups_language
  ON groups(language)
  WHERE visibility = 'public' AND deleted_at IS NULL;
