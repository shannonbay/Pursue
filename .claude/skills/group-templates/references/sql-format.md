# SQL Migration Format

Copy-paste templates for creating group/challenge templates and their translations.

## Migration Naming

```
pursue-server/migrations/YYYYMMDD_seed_<slug>_template.sql       ← English canonical
pursue-server/migrations/YYYYMMDD_add_<slug>_template_translations.sql  ← All translations
```

Use today's date. If adding to an existing seed/translation batch on the same date, increment the suffix (e.g. `_2`).

---

## File 1: English Canonical Template + Goals

```sql
SET client_encoding = 'UTF8';

BEGIN;

-- <Template Title> (<is_challenge ? 'challenge' : 'ongoing group'> template)
WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    '<slug>',                  -- kebab-case unique key
    '<English title>',
    '<English description>',
    '<emoji>',
    '<res://drawable/ic_icon_name OR NULL>',
    '<category>',              -- fitness | mindfulness | lifestyle | learning | creativity | productivity | finance | social | making
    '<difficulty>',            -- easy | moderate | hard
    <TRUE|FALSE>,              -- is_featured
    <TRUE|FALSE>,              -- is_challenge: TRUE=time-boxed, FALSE=ongoing
    <duration_days|NULL>,      -- e.g. 30 for challenges, NULL for ongoing
    '<default_mode>',          -- 'challenge' | 'group' | 'either'
    <sort_order>               -- integer position within category
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, '<goal title>', <'description' | NULL>, '<cadence>', '<metric_type>', <'prompt' | NULL>, <target | NULL>, <'unit' | NULL>, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

COMMIT;
```

### Single-goal example (binary, daily)

```sql
SET client_encoding = 'UTF8';

BEGIN;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'morning-yoga',
    'Morning Yoga',
    'Roll out your mat every morning and move with intention. Log your sessions and keep each other accountable.',
    '🧘', 'res://drawable/ic_icon_yoga', 'fitness', 'easy', FALSE, FALSE, NULL, 'group', 7
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Yoga session done', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

COMMIT;
```

### Multi-goal example (binary + numeric + journal)

```sql
SET client_encoding = 'UTF8';

BEGIN;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'swim-club',
    'Swim Club',
    'Hit the pool, log your laps, and stay consistent. No end date — just steady progress.',
    '🏊', 'res://drawable/ic_icon_swimming', 'fitness', 'moderate', FALSE, FALSE, NULL, 'group', 8
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Swam today',       NULL, 'daily',  'binary',  NULL,                    NULL, NULL,   0 FROM t
UNION ALL
SELECT id, 'Laps this week',   NULL, 'weekly', 'numeric', NULL,                    20,   'laps', 1 FROM t
UNION ALL
SELECT id, 'Session notes',    NULL, 'daily',  'journal', 'How did the swim go?',  NULL, NULL,   2 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

COMMIT;
```

### Challenge template example (time-boxed, 30 days)

```sql
SET client_encoding = 'UTF8';

BEGIN;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    '30-day-meditation',
    '30-Day Meditation Challenge',
    'Meditate every day for 30 days. Build the habit, feel the shift.',
    '🧘', 'res://drawable/ic_icon_prayerhands', 'mindfulness', 'moderate', TRUE, TRUE, 30, 'challenge', 5
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Meditated today',    NULL, 'daily', 'binary',   NULL,                      NULL, NULL,      0 FROM t
UNION ALL
SELECT id, 'Meditation session', NULL, 'daily', 'duration', NULL,                      10,   'minutes', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

COMMIT;
```

### Goal with `active_days` (weekdays only, Mon–Fri = 31)

```sql
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order, active_days)
SELECT id, 'Workout before 9am', NULL, 'daily', 'binary', NULL, NULL, NULL, 0, 31 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;
-- 31 = Mon(1)+Tue(2)+Wed(4)+Thu(8)+Fri(16)
```

---

## File 2: All Translations

Provide translations for all **5 non-English languages**: `de`, `es`, `fr`, `pt-BR`, `zh`.

The lookup key is the English title — do not change canonical English titles after seeding without also updating all translation lookups.

```sql
SET client_encoding = 'UTF8';

BEGIN;

-- ─── GERMAN (de) ──────────────────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'de', v.de_title, v.de_description
FROM (VALUES
  ('<English title>', '<German title>', '<German description>')
) AS v(en_title, de_title, de_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'de', v.de_title, v.de_description, v.de_log_prompt
FROM (VALUES
  ('<English template title>', '<English goal title>', '<German goal title>', <'German description' | NULL>, <'German prompt' | NULL>)
) AS v(en_template, en_goal, de_title, de_description, de_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

-- ─── SPANISH (es) ─────────────────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'es', v.es_title, v.es_description
FROM (VALUES
  ('<English title>', '<Spanish title>', '<Spanish description>')
) AS v(en_title, es_title, es_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'es', v.es_title, v.es_description, v.es_log_prompt
FROM (VALUES
  ('<English template title>', '<English goal title>', '<Spanish goal title>', <NULL|'description'>, <NULL|'prompt'>)
) AS v(en_template, en_goal, es_title, es_description, es_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

-- ─── FRENCH (fr) ──────────────────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'fr', v.fr_title, v.fr_description
FROM (VALUES
  ('<English title>', '<French title>', '<French description>')
) AS v(en_title, fr_title, fr_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'fr', v.fr_title, v.fr_description, v.fr_log_prompt
FROM (VALUES
  ('<English template title>', '<English goal title>', '<French goal title>', <NULL|'description'>, <NULL|'prompt'>)
) AS v(en_template, en_goal, fr_title, fr_description, fr_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

-- ─── PORTUGUESE BRAZIL (pt-BR) ────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'pt-BR', v.pt_title, v.pt_description
FROM (VALUES
  ('<English title>', '<Portuguese title>', '<Portuguese description>')
) AS v(en_title, pt_title, pt_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'pt-BR', v.pt_title, v.pt_description, v.pt_log_prompt
FROM (VALUES
  ('<English template title>', '<English goal title>', '<Portuguese goal title>', <NULL|'description'>, <NULL|'prompt'>)
) AS v(en_template, en_goal, pt_title, pt_description, pt_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

-- ─── CHINESE (zh) ─────────────────────────────────────────────────────────────

INSERT INTO group_template_translations (template_id, language, title, description)
SELECT t.id, 'zh', v.zh_title, v.zh_description
FROM (VALUES
  ('<English title>', '<Chinese title>', '<Chinese description>')
) AS v(en_title, zh_title, zh_description)
JOIN group_templates t ON t.title = v.en_title
ON CONFLICT (template_id, language) DO NOTHING;

INSERT INTO group_template_goal_translations (goal_id, language, title, description, log_title_prompt)
SELECT tg.id, 'zh', v.zh_title, v.zh_description, v.zh_log_prompt
FROM (VALUES
  ('<English template title>', '<English goal title>', '<Chinese goal title>', <NULL|'description'>, <NULL|'prompt'>)
) AS v(en_template, en_goal, zh_title, zh_description, zh_log_prompt)
JOIN group_templates t ON t.title = v.en_template
JOIN group_template_goals tg ON tg.template_id = t.id AND tg.title = v.en_goal
ON CONFLICT (goal_id, language) DO NOTHING;

COMMIT;
```

---

## Tips

- When a template has multiple goals, add one VALUES row per goal in each language block.
- `NULL` values in the VALUES list must be bare `NULL` (not `'NULL'`).
- Single quotes inside strings must be escaped as `''` (e.g. `'It''s working'`).
- Set `client_encoding = 'UTF8'` at the top of translation files with non-ASCII characters.
- If adding translations for templates that already exist (e.g. backfilling a new language), use the same `JOIN group_templates t ON t.title = v.en_title` lookup pattern — title is the stable key.
- If seeding many templates at once, you can batch all language inserts for all templates inside a single VALUES block per language (see `20260228_add_chinese_template_translations.sql` for the full-catalog pattern).
