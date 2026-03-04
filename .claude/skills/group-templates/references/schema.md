# Template Schema Reference

> **Keep this file up to date** whenever `group_templates`, `group_template_goals`,
> `group_template_translations`, or `group_template_goal_translations` tables are altered.

## `group_templates`

```sql
CREATE TABLE group_templates (
  id           UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  slug         VARCHAR(100) UNIQUE NOT NULL,   -- kebab-case, human-readable key
  title        VARCHAR(200) NOT NULL,           -- English display name
  description  TEXT NOT NULL,                  -- English description
  icon_emoji   VARCHAR(10) NOT NULL,            -- single emoji
  icon_url     TEXT,                            -- res://drawable/ic_icon_<name>  OR NULL
  duration_days INTEGER,                       -- NULL for ongoing; integer for time-boxed challenges
  category     VARCHAR(50) NOT NULL,
  difficulty   VARCHAR(20) NOT NULL DEFAULT 'moderate',
  is_featured  BOOLEAN NOT NULL DEFAULT FALSE,
  is_challenge BOOLEAN NOT NULL DEFAULT TRUE,  -- TRUE = time-boxed challenge, FALSE = ongoing group
  default_mode VARCHAR(20) NOT NULL DEFAULT 'challenge'
               CHECK (default_mode IN ('group', 'challenge', 'either')),
  sort_order   INTEGER NOT NULL DEFAULT 0,
  created_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at   TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

### Valid enum values

| Column        | Valid values                                                                                 |
|---------------|----------------------------------------------------------------------------------------------|
| `category`    | `fitness`, `mindfulness`, `lifestyle`, `learning`, `creativity`, `productivity`, `finance`, `social`, `making` |
| `difficulty`  | `easy`, `moderate`, `hard`                                                                   |
| `default_mode`| `group`, `challenge`, `either`                                                               |

### `is_challenge` conventions

| `is_challenge` | `duration_days` | `default_mode` | Used in               |
|----------------|-----------------|----------------|-----------------------|
| `TRUE`         | e.g. `7`, `30`  | `'challenge'`  | "Start a Challenge"   |
| `FALSE`        | `NULL`          | `'group'`      | "Browse Group Ideas"  |

---

## `group_template_goals`

```sql
CREATE TABLE group_template_goals (
  id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  template_id      UUID NOT NULL REFERENCES group_templates(id) ON DELETE CASCADE,
  title            VARCHAR(200) NOT NULL,
  description      TEXT,
  cadence          VARCHAR(20) NOT NULL,       -- 'daily' | 'weekly'
  metric_type      VARCHAR(20) NOT NULL,       -- 'binary' | 'numeric' | 'duration' | 'journal'
  target_value     DECIMAL(10,2),              -- NULL for binary/journal
  unit             VARCHAR(50),                -- NULL for binary/journal; e.g. 'km', 'minutes', 'steps', 'pages', 'words', 'hours', 'glasses'
  log_title_prompt VARCHAR(80),               -- journal prompt shown to user (journal goals only, max 80 chars)
  sort_order       INTEGER NOT NULL DEFAULT 0,
  active_days      INTEGER DEFAULT NULL,       -- bitmask: 1=Mon,2=Tue,4=Wed,8=Thu,16=Fri,32=Sat,64=Sun; NULL = every day
  UNIQUE(template_id, sort_order),
  CONSTRAINT chk_template_goal_active_days CHECK (active_days IS NULL OR (active_days >= 1 AND active_days <= 127)),
  CONSTRAINT chk_template_goal_active_days_cadence CHECK (active_days IS NULL OR cadence = 'daily')
);
```

### `metric_type` reference

| `metric_type` | `target_value` | `unit`   | `log_title_prompt` |
|---------------|----------------|----------|--------------------|
| `binary`      | `NULL`         | `NULL`   | `NULL`             |
| `numeric`     | e.g. `10000`   | required | `NULL`             |
| `duration`    | e.g. `30`      | required | `NULL`             |
| `journal`     | `NULL`         | `NULL`   | Optional (max 80)  |

### `active_days` bitmask

| Day       | Bit value |
|-----------|-----------|
| Monday    | 1         |
| Tuesday   | 2         |
| Wednesday | 4         |
| Thursday  | 8         |
| Friday    | 16        |
| Saturday  | 32        |
| Sunday    | 64        |

Common combinations: Mon–Fri = `31`, Mon–Sat = `63`, Mon/Wed/Fri = `21`, every day = `NULL` (not `127`).

> **Constraint**: `active_days` is only valid when `cadence = 'daily'`. Omit or use `NULL` for weekly goals.

---

## `group_template_translations`

```sql
CREATE TABLE group_template_translations (
  template_id UUID NOT NULL REFERENCES group_templates(id) ON DELETE CASCADE,
  language    VARCHAR(10) NOT NULL,   -- e.g. 'pt-BR', 'es', 'fr', 'de', 'zh'
  title       VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  PRIMARY KEY (template_id, language)
);
```

- English is **never** inserted here. The API falls back to the canonical English row.
- Language codes must match exactly: `de`, `es`, `fr`, `pt-BR`, `zh`.

---

## `group_template_goal_translations`

```sql
CREATE TABLE group_template_goal_translations (
  goal_id          UUID NOT NULL REFERENCES group_template_goals(id) ON DELETE CASCADE,
  language         VARCHAR(10) NOT NULL,
  title            VARCHAR(200) NOT NULL,
  description      TEXT,
  log_title_prompt VARCHAR(80),
  PRIMARY KEY (goal_id, language)
);
```

- Translate `title`, `description`, and `log_title_prompt` if the English value is non-NULL.
- If `description` or `log_title_prompt` is `NULL` in English, insert `NULL` in the translation too.

---

## `goals` table (FK reference)

Goals created from templates carry a `template_goal_id` FK:

```sql
template_goal_id UUID REFERENCES group_template_goals(id) ON DELETE SET NULL
```

This enables the API to look up translations at read time for user goals that originated from a template.

---

## App language support

Defined in `pursue-app/app/src/main/res/xml/locales_config.xml`:

| Code    | Language            | Resource dir    |
|---------|---------------------|-----------------|
| `en`    | English (canonical) | `values/`       |
| `de`    | German              | `values-de/`    |
| `es`    | Spanish             | `values-es/`    |
| `fr`    | French              | `values-fr/`    |
| `pt-BR` | Portuguese (Brazil) | `values-pt-rBR/`|
| `zh`    | Chinese             | `values-zh/`    |

When adding a new app language, add a new translation block for every template and every template goal.
