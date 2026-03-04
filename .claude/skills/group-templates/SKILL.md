---
name: group-templates
description: This skill should be used when the user asks to "create a group template", "add a challenge template", "add a new template", "seed a group template", "add template translations", "create an ongoing group template", or needs to write SQL migrations for new group or challenge templates (including all required translations) in the Pursue backend. Also invoke this skill to self-update when the group_templates, group_template_goals, group_template_translations, or group_template_goal_translations schema is modified.
---

# Group & Challenge Templates

Creates SQL migration files for new group or challenge templates, including English canonical rows and translations for all 5 non-English app languages.

## Overview

Templates are stored in three layers:

1. **`group_templates`** — The canonical English master row (title, description, settings).
2. **`group_template_goals`** — One or more goals attached to the template.
3. **`group_template_translations` / `group_template_goal_translations`** — Localized title/description for each non-English language.

The API uses COALESCE: translation → English fallback. English is never put in the translation tables.

**Supported app languages** (from `locales_config.xml`):

| Code    | Language            | Translation table value |
|---------|---------------------|-------------------------|
| `en`    | English (canonical) | _(never inserted)_      |
| `de`    | German              | `'de'`                  |
| `es`    | Spanish             | `'es'`                  |
| `fr`    | French              | `'fr'`                  |
| `pt-BR` | Portuguese (Brazil) | `'pt-BR'`               |
| `zh`    | Chinese             | `'zh'`                  |

## How to Use

### Step 1 — Design the template

Gather the following from the user or infer sensible defaults:

**Template fields** (see `references/schema.md` for all valid values):
- `slug` — kebab-case, unique, e.g. `morning-yoga`
- `title` — English display name
- `description` — English description (1–2 sentences, engaging copy)
- `icon_emoji` — single emoji
- `icon_url` — `res://drawable/ic_icon_<name>` (use an existing drawable name if known, or `NULL`)
- `category` — one of: `fitness`, `mindfulness`, `lifestyle`, `learning`, `creativity`, `productivity`, `finance`, `social`, `making`
- `difficulty` — `easy`, `moderate`, or `hard`
- `is_featured` — `TRUE` / `FALSE`
- `is_challenge` — `TRUE` for time-boxed challenges, `FALSE` for ongoing groups
- `duration_days` — integer for challenges (e.g. `30`), `NULL` for ongoing
- `default_mode` — `'challenge'` for challenges, `'group'` for ongoing, `'either'` if both
- `sort_order` — integer position within category (higher = later in list)

**Goal fields** (one or more):
- `title` — short English label, e.g. `Run today`
- `description` — optional longer English description
- `cadence` — `'daily'` or `'weekly'`
- `metric_type` — `'binary'`, `'numeric'`, `'duration'`, or `'journal'`
- `target_value` / `unit` — for numeric/duration goals (e.g. `30`, `'minutes'`); `NULL` for binary/journal
- `log_title_prompt` — optional journal prompt shown to users (max 80 chars), for `journal` metric goals
- `active_days` — integer bitmask `1=Mon … 64=Sun`; `NULL` = every day; only valid with `cadence='daily'`
- `sort_order` — 0-indexed position

### Step 2 — Write the migration files

Create **two migration files** in `pursue-server/migrations/`:

#### File 1: English canonical template + goals

Filename: `YYYYMMDD_seed_<slug>_template.sql` (use today's date)

Use the CTE pattern. See `references/sql-format.md` for the full template.

#### File 2: All translations

Filename: `YYYYMMDD_add_<slug>_template_translations.sql`

Add translations for **all 5 non-English languages** (`de`, `es`, `fr`, `pt-BR`, `zh`). Each language block:
- JOINs by English title (the canonical key)
- Uses `ON CONFLICT (template_id, language) DO NOTHING`

See `references/sql-format.md` for the exact pattern.

### Step 3 — Verify

- Every `slug` must be unique. Search existing migrations for the slug before writing.
- Every goal `sort_order` must be unique within the template.
- `active_days` constraint: only allowed when `cadence = 'daily'`.
- `duration_days` must be non-NULL when `is_challenge = TRUE`.
- Translation files must cover all 5 non-English languages.
- All files wrapped in `BEGIN; … COMMIT;`.
- All inserts idempotent via `ON CONFLICT … DO NOTHING`.

### When invoked to self-update (schema change)

When the schema of any template table is modified, update `references/schema.md` to reflect:
- Any added, renamed, or removed columns
- Any new/changed constraints or enum values
- Any new translation fields

## Resources

- **`references/schema.md`** — Full table schema, column types, and valid enum values.
- **`references/sql-format.md`** — Copy-paste SQL migration templates with annotated examples.
