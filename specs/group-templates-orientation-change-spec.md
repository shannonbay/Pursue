# Pursue — Group Templates Infrastructure Change Spec

**Type:** Change Specification (delta against existing implementation)  
**Version:** 2.0  
**Date:** February 2026  
**Status:** Implementation Ready  
**Affects:** challenges-spec.md, post-onboarding-orientation-spec.md  
**Depends On:** Existing challenges implementation, existing orientation implementation  
**Related:** ongoing-group-templates-spec.md

---

## 1. Purpose & Context

This spec covers two things:

1. **Rename challenge infrastructure to group templates.** The `challenge_templates` and `challenge_template_goals` tables were named too narrowly. Renaming them to `group_templates` and `group_template_goals` makes them accurate — they are a shared template library used by both time-boxed challenges and (via the new ongoing group templates feature) open-ended groups. This is a backend and data-layer change only.

2. **Minimal orientation flow updates.** The orientation flow's Step 2 ("Start a Challenge") is largely preserved as-is. "Start a Challenge" is the right framing for that step — it's persuasive, specific, and lower-friction than generic copy. The only changes are mechanical: updating endpoint references to match the renamed infrastructure.

**What this spec deliberately does NOT do:**

- Rename Android screens or composables beyond what's mechanically required. `ChallengeTemplatesScreen` stays as `ChallengeTemplatesScreen` because it accurately describes what the screen shows. Code naming should reflect the user's mental model, not the database schema.
- Change any user-facing copy in the orientation flow. "Try a challenge!" stays as-is.
- Add the ongoing group templates feature. That is a separate spec — see `ongoing-group-templates-spec.md`.

---

## 2. Database Changes

### 2.1 Table & Column Renames

These are rename-only operations. No data changes, no column structure changes.

```sql
-- Migration: 20260223_rename_challenge_tables.sql

BEGIN;

-- Rename tables
ALTER TABLE challenge_templates      RENAME TO group_templates;
ALTER TABLE challenge_template_goals RENAME TO group_template_goals;
ALTER TABLE challenge_suggestion_log RENAME TO group_suggestion_log;

-- Rename indexes
ALTER INDEX idx_challenge_templates_category RENAME TO idx_group_templates_category;
ALTER INDEX idx_challenge_templates_featured RENAME TO idx_group_templates_featured;
ALTER INDEX idx_template_goals_template      RENAME TO idx_group_template_goals;
ALTER INDEX idx_groups_challenge_template    RENAME TO idx_groups_template;

-- Rename FK constraint on groups table
ALTER TABLE groups
  RENAME CONSTRAINT fk_groups_challenge_template TO fk_groups_template;

-- Rename column on groups table
ALTER TABLE groups
  RENAME COLUMN challenge_template_id TO template_id;

COMMIT;
```

### 2.2 New Column: `is_challenge` on `group_templates`

Add a discriminator column so the template library can serve both challenge templates and ongoing group templates (introduced by the companion spec).

```sql
ALTER TABLE group_templates
  ADD COLUMN is_challenge BOOLEAN NOT NULL DEFAULT TRUE;

-- All existing templates are challenge templates — make it explicit
UPDATE group_templates SET is_challenge = TRUE;
```

This column is the authoritative signal for which setup flow a template triggers:

- `is_challenge = TRUE` → date picker setup screen, creates a time-boxed group
- `is_challenge = FALSE` → simple name screen, creates an ongoing open-ended group

### 2.3 Impact on `groups.template_id`

The `challenge_template_id` column on `groups` becomes `template_id`. The remaining challenge columns (`is_challenge`, `challenge_start_date`, `challenge_end_date`, `challenge_status`) are **not renamed** — they remain descriptive and accurate.

---

## 3. Backend Changes

### 3.1 Renamed Endpoint

| Old | New |
|-----|-----|
| `GET /api/challenge-templates` | `GET /api/group-templates` |

Add a 301 redirect from the old path for one release cycle, then remove it.

### 3.2 `is_challenge` Filter Parameter

The renamed endpoint gains an `is_challenge` query parameter so the challenge browser and the new ongoing group template browser can each fetch only their relevant templates:

```
GET /api/group-templates?is_challenge=true    ← challenge template browser (existing behaviour)
GET /api/group-templates?is_challenge=false   ← ongoing group template browser (new)
GET /api/group-templates                      ← all templates (no filter)
```

The existing client call must be updated to pass `?is_challenge=true` explicitly, so it is unaffected when ongoing group templates are added to the database.

### 3.3 Response Field Rename

Any response that previously included `challenge_template_id` now emits `template_id`:

```json
// Before
{ "id": "group-uuid", "is_challenge": true, "challenge_template_id": "template-uuid" }

// After
{ "id": "group-uuid", "is_challenge": true, "template_id": "template-uuid" }
```

Affected responses: `POST /api/groups` (create), `GET /api/groups/:id`, `GET /api/groups` list.

### 3.4 Kysely Schema Update

```typescript
// Before
challenge_template_id: string | null;

// After
template_id: string | null;
```

Update all queries that reference `groups.challenge_template_id` by name.

---

## 4. Android Changes

### 4.1 Data Layer Only — Screen Names Unchanged

Only the data/repository layer needs updating. `ChallengeTemplatesScreen`, `ChallengeTemplateViewModel`, and `TemplateCard` are **not renamed** — they accurately describe what they show from the user's perspective and should not be renamed just because the backing table changed.

| What changes | Old | New |
|---|---|---|
| Endpoint constant | `"/api/challenge-templates"` | `"/api/group-templates?is_challenge=true"` |
| `Group` model field | `challengeTemplateId: String?` | `templateId: String?` |
| Internal data class | `TemplateSeed` | `GroupTemplateSeed` |

### 4.2 Endpoint Update

```kotlin
// In ChallengeTemplateRepository or equivalent
// Before
private const val TEMPLATES_ENDPOINT = "/api/challenge-templates"

// After
private const val TEMPLATES_ENDPOINT = "/api/group-templates?is_challenge=true"
```

### 4.3 Model Field Update

```kotlin
// Before
data class Group(
    ...
    val challengeTemplateId: String?,
    ...
)

// After
data class Group(
    ...
    val templateId: String?,
    ...
)
```

Update all call sites that reference `challengeTemplateId`.

---

## 5. Orientation Flow Changes

The orientation flow is largely unchanged. Only mechanical reference updates are required.

### 5.1 No Copy Changes

The headline ("Try a challenge!"), body copy, and all CTAs on Step 2 are **unchanged**. "Start a Challenge" remains the right framing for that step — it's persuasive and specific. Do not dilute this.

### 5.2 Endpoint Reference Update

The Step 2 data fetch updates its endpoint string via the repository change in §4.2. No other changes to `OrientationChallengeScreen` are required — composable names, copy, navigation, and behaviour are all unchanged.

### 5.3 Backend Checklist Update

`post-onboarding-orientation-spec.md §12.2` references the old endpoint. Update the checklist comment only:

```
- GET /api/group-templates?is_challenge=true   (Step 2 — was /api/challenge-templates)
```

All other endpoints in that checklist are unchanged.

### 5.4 Section 9.4 — No Update Required

`post-onboarding-orientation-spec.md §9.4` references `ChallengeTemplatesScreen`, `TemplateCard`, and `ChallengeTemplateViewModel`. Since these names are unchanged (§4.1), §9.4 requires no update.

---

## 6. Speed Dial Addition (Scoped to Companion Spec)

The ongoing group templates feature introduces a fourth Speed Dial option. This is specced fully in `ongoing-group-templates-spec.md` but noted here for sequencing:

```
Speed Dial (after companion spec ships):
  1. Create Group       → blank group creation (unchanged)
  2. Start a Challenge  → ChallengeTemplatesScreen, is_challenge=true (unchanged)
  3. Browse Group Ideas → GroupIdeasScreen, is_challenge=false (new)
  4. Join with Code     → unchanged
```

Do not add the new Speed Dial option until the ongoing group template library has been seeded with content.

---

## 7. Summary Checklist

### Backend
- [ ] Run migration `20260223_rename_challenge_tables.sql`
- [ ] Add `is_challenge BOOLEAN NOT NULL DEFAULT TRUE` to `group_templates`
- [ ] `UPDATE group_templates SET is_challenge = TRUE` for all existing rows
- [ ] Rename route: `GET /api/challenge-templates` → `GET /api/group-templates`
- [ ] Add `?is_challenge` filter support to `GET /api/group-templates`
- [ ] Add 301 redirect from old path for one release cycle
- [ ] Update Kysely schema: `challenge_template_id` → `template_id` on `Groups` type
- [ ] Update all queries referencing `groups.challenge_template_id` → `groups.template_id`
- [ ] Update API response serialisers to emit `template_id`

### Android
- [ ] Update `TEMPLATES_ENDPOINT` constant to `/api/group-templates?is_challenge=true`
- [ ] Rename `Group.challengeTemplateId` → `Group.templateId` and update all call sites
- [ ] Rename internal `TemplateSeed` data class → `GroupTemplateSeed`

### Docs
- [ ] Update `post-onboarding-orientation-spec.md §12.2` endpoint reference per §5.3

---

## 8. Testing Notes

No new test cases needed. Update existing tests that reference:

- `GET /api/challenge-templates` → `GET /api/group-templates?is_challenge=true`
- `challenge_template_id` in response body assertions → `template_id`
- `challengeTemplateId` in Android model assertions → `templateId`

The 301 redirect ensures the old endpoint continues to work during the transition window.
