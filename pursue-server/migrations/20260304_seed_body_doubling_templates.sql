SET client_encoding = 'UTF8';

BEGIN;

-- Body Doubling (ongoing group template)
WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'body-doubling',
    'Body Doubling',
    'Work alongside others to get things done. Schedule focus sessions, log your sessions, and show up for each other — whether you''re studying, writing, or deep in a project.',
    '🎯', 'res://drawable/ic_icon_focus', 'productivity', 'moderate', TRUE, FALSE, NULL, 'group', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Focus session done', NULL, 'daily',  'binary',   NULL, NULL,  NULL,      0 FROM t
UNION ALL
SELECT id, 'Focus time',         NULL, 'weekly', 'duration', NULL, 120,   'minutes', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- Deep Work Club (ongoing group template)
WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'deep-work-club',
    'Deep Work Club',
    'Protect your best hours. Log your deep work sessions, track your time, and share what you''re building with a group that values real focus over busy work.',
    '🧠', 'res://drawable/ic_icon_brain', 'productivity', 'moderate', FALSE, FALSE, NULL, 'group', 2
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Deep work session done', NULL, 'daily',  'binary',   NULL, NULL::numeric, NULL::text, 0 FROM t
UNION ALL
SELECT id, 'What did you work on?',  NULL, 'daily',  'journal',  NULL, NULL::numeric, NULL::text, 1 FROM t
UNION ALL
SELECT id, 'Deep work time',         NULL, 'weekly', 'duration', NULL, 180::numeric,  'minutes',  2 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

COMMIT;
