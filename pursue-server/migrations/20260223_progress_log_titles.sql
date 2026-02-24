BEGIN;

-- 1. log_title on progress_entries
ALTER TABLE progress_entries ADD COLUMN IF NOT EXISTS log_title VARCHAR(120);
CREATE INDEX IF NOT EXISTS idx_progress_journal_entries
  ON progress_entries(goal_id, period_start DESC)
  WHERE log_title IS NOT NULL;

-- 2. log_title_prompt on goals
ALTER TABLE goals ADD COLUMN IF NOT EXISTS log_title_prompt VARCHAR(80);

-- 3. log_title_prompt on group_template_goals
ALTER TABLE group_template_goals ADD COLUMN IF NOT EXISTS log_title_prompt VARCHAR(80);

-- 4. Update metric_type CHECK on goals to include 'journal'
ALTER TABLE goals DROP CONSTRAINT IF EXISTS goals_metric_type_check;
ALTER TABLE goals ADD CONSTRAINT goals_metric_type_check
  CHECK (metric_type IN ('binary', 'numeric', 'duration', 'journal'));

-- 5. Make duration_days nullable on group_templates (for ongoing templates)
ALTER TABLE group_templates ALTER COLUMN duration_days DROP NOT NULL;

-- 6. Add default_mode column to group_templates
ALTER TABLE group_templates ADD COLUMN IF NOT EXISTS default_mode VARCHAR(20)
  NOT NULL DEFAULT 'challenge'
  CHECK (default_mode IN ('group', 'challenge', 'either'));

COMMIT;
