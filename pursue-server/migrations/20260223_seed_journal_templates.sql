BEGIN;

-- Insert Daily Discovery template (idempotent via ON CONFLICT DO NOTHING)
WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'daily-discovery',
    'Daily Discovery',
    'Learn something new every day — a fact, a skill, a concept, or a craft. Share it with the group so you can learn from each other too.',
    '🧠', 'learning', 'easy', TRUE, FALSE, NULL, 'group', 10
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Daily Discovery', 'Share what you learned today', 'daily', 'journal', 'What did you learn today?', NULL, NULL, 0
FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- Insert Weekend Workshop template
WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'weekend-workshop',
    'Weekend Workshop',
    'Make something, fix something, or build something every weekend. Even small wins count — a patch, a coat of paint, a tightened hinge.',
    '🔧', 'making', 'easy', FALSE, FALSE, NULL, 'group', 11
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Weekend Project', 'Share what you made, fixed, or built this weekend', 'weekly', 'journal', 'What did you make or fix?', NULL, NULL, 0
FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

COMMIT;
