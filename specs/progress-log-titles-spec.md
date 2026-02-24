# Pursue — Progress Log Titles & Open-Ended Goal Templates

**Feature:** Custom Progress Log Titles + "Journal" Goal Templates  
**Version:** 1.0  
**Status:** Draft  
**Depends On:** progress_entries table, challenge_templates, group templates concept

---

## 1. Overview

Some goal types don't have a clean binary or numeric outcome — the value is *what* was done, not just *that* it was done. A "Daily Discovery" goal in a Polymath Project group is satisfied by learning *something*, but that something varies each day. The progress log note becomes the point of the entry, not an optional annotation.

This spec covers:
1. **Custom progress log titles** — a short, optional headline field on progress entries for goals that need it
2. **Open-ended goal templates** — a new `metric_type` and a pair of curated templates for variable-achievement groups
3. **Dual-use templates** — allowing templates to instantiate as either ongoing groups or time-boxed challenges

---

## 2. Should We Allow Titles and Descriptions?

### Recommendation: Title yes, long description no (for now)

**The case for a title field:**
The existing `note` field (500 chars) is framed as an annotation on a quantitative achievement. For open-ended goals, the note *is* the achievement — "Fixed the leaky faucet", "Finished a chapter of Sapiens", "Learned what a mortise joint is". A short `log_title` field (100–120 chars) that surfaces prominently in the activity feed is the right shape for this. It becomes the social object that peers react to and feel proud of, rather than a buried footnote.

**The case against a full description/body field:**
- Pursue's social contract is accountability, not blogging. A long-form body field would shift the app's character toward a journaling or social media product.
- Longer arbitrary content dramatically increases moderation complexity (see §3 below).
- The activity feed is designed for quick scanning. Multi-paragraph posts would break the feed's rhythm and create inequity between verbose and brief users.
- If a user genuinely wants to elaborate, they have their notes app. Pursue's job is to record *that* they did it and give the group a shared signal.

**If demand for richer content emerges post-launch**, a collapsible "Read more" body field (1000 chars max) could be introduced as a premium feature. Keep it out of v1.

---

## 3. Pros and Cons of Arbitrary User Content

### Pros
- **Higher engagement:** Varied, interesting log titles make the activity feed more readable and give group members more to react to
- **Stronger identity:** Groups like "Polymath Project" build a shared intellectual history through their logs
- **Natural retention:** Users who've posted 30 interesting discoveries feel invested in the group's archive
- **Viral content:** A witty or surprising log title is more shareable than "Completed: Daily Discovery"

### Cons
- **Moderation burden:** Free-text fields are where inappropriate content enters the app. Even with a curated emoji set, text is harder to police.
- **Cognitive overhead:** Users with simple binary goals (Did I run today? Yes.) don't need or want a title field. It should only appear for goals that need it.
- **Feed quality decay:** If titles are optional and people ignore them, the feed loses the richness that justified the feature.
- **Content liability:** User-generated text, even short titles, creates some legal surface area (defamation, harassment, copyright of quoted text).

### Mitigations
- Only surface the title field for goals with `metric_type = 'journal'` (see §4) — hide it for binary/numeric/duration goals
- Apply the existing 500-char note limit to titles but set a tighter recommended max (120 chars) enforced in the UI via counter
- Existing report/block flows cover user-generated text; no new infrastructure needed
- Content policy already in the user covenant; journal titles fall under existing terms

---

## 4. New Metric Type: `journal`

### 4.1 Rationale

Rather than adding title support to all progress entries, introduce a new goal `metric_type` called `journal`. This scopes the feature cleanly and avoids cluttering the binary/numeric logging UX.

A `journal` goal:
- Has no numeric target — completion is the act of logging with a meaningful title
- The `value` column stores `1` (completed) or `0` (skipped/uncompleted, unlikely to be used)
- The `log_title` field is **required** (not optional) when logging a journal goal
- The `note` field remains optional for additional context

### 4.2 Database Changes

```sql
-- Add log_title to progress_entries
ALTER TABLE progress_entries ADD COLUMN log_title VARCHAR(120);

-- Index for activity feed queries (journal entries will be displayed by title)
CREATE INDEX idx_progress_journal_entries 
  ON progress_entries(goal_id, period_start DESC) 
  WHERE log_title IS NOT NULL;
```

Update `metric_type` constraint:
```sql
-- Add 'journal' to the allowed values
ALTER TABLE goals DROP CONSTRAINT IF EXISTS goals_metric_type_check;
ALTER TABLE goals ADD CONSTRAINT goals_metric_type_check 
  CHECK (metric_type IN ('binary', 'numeric', 'duration', 'journal'));
```

### 4.3 Validation Changes

```typescript
// Extended progress entry validation
export const ProgressEntrySchema = z.object({
  goal_id: z.string().uuid(),
  value: z.number(),
  log_title: z.string().min(1).max(120).optional(),
  note: z.string().max(500).optional(),
  user_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  user_timezone: z.string().min(1).max(50)
}).strict();

// In validateProgressEntry:
if (goal.metric_type === 'journal') {
  if (!data.log_title || data.log_title.trim().length === 0) {
    throw new Error('Journal goals require a log title');
  }
  if (data.value !== 0 && data.value !== 1) {
    throw new Error('Journal goal value must be 0 or 1');
  }
}
```

### 4.4 Goal Creation Schema Update

```typescript
export const CreateGoalSchema = z.object({
  title: z.string().min(1).max(200),
  description: z.string().max(1000).optional(),
  cadence: z.enum(['daily', 'weekly', 'monthly', 'yearly']),
  metric_type: z.enum(['binary', 'numeric', 'duration', 'journal']),
  target_value: z.number().positive().max(999999.99).optional(),
  unit: z.string().max(50).optional(),
  log_title_prompt: z.string().max(80).optional() // e.g. "What did you learn today?"
}).strict().refine(
  (data) => {
    if (data.metric_type === 'numeric' && !data.target_value) return false;
    if (data.metric_type === 'journal' && data.target_value) return false; // No target for journal
    return true;
  }
);
```

The optional `log_title_prompt` is stored on the goal and shown as placeholder text in the logging UI (e.g. "What did you learn today?" / "What did you make or fix?"). This gives groups a way to frame their entries without hard-coding behaviour.

---

## 5. Android UX

### 5.1 Logging a Journal Goal

When a user taps to log a `journal` goal, the logging bottom sheet changes:

**Standard binary/numeric sheet:**
```
[Goal title]
[Value input or checkmark]
[Note (optional)]         ← existing
[Log button]
```

**Journal sheet:**
```
[Goal title]
[log_title_prompt as heading, e.g. "What did you learn today?"]
[Title text field — required, 120 char counter]
[Note text field — optional, "Add more detail..."]
[Log button — disabled until title has content]
```

The title field is a single-line `OutlinedTextField`. The note field is multi-line but compact (3 lines, not a full editor). No markdown, no formatting, no images — plain text only.

### 5.2 Activity Feed Display

Journal entries in the activity feed show the `log_title` prominently as the entry body, replacing the current "Completed [goal title]" pattern:

```
[Avatar] Shannon          2h ago
"Fixed the leaky faucet under the kitchen sink"
Weekend DIY · Reactions · Nudge
```

Compare to binary/numeric:
```
[Avatar] Shannon          2h ago
Completed: Morning Run ✓
Morning Momentum · Reactions · Nudge
```

The visual hierarchy makes journal entries naturally richer and more engaging in the feed.

### 5.3 Goal Detail / History View

In the goal history screen, journal entries list chronologically with their `log_title` as the row title and `note` (if present) as the subtitle. This creates a readable log of what the group has collectively accomplished.

---

## 6. New Goal Templates

### 6.1 "Daily Discovery" Template

**Use case:** Learning groups, curiosity circles, "Polymath Project" style groups.

```typescript
{
  slug: 'daily-discovery',
  title: 'Daily Discovery',
  description: 'Learn something new every day — a fact, a skill, a concept, or a craft. Share it with the group so you can learn from each other too.',
  icon_emoji: '🧠',
  icon_url: null,   // or e.g. 'asset://icons/templates/daily-discovery.png' for bundled assets
  category: 'learning',
  difficulty: 'easy',
  is_featured: true,
  duration_days: null,         // Ongoing by default; set if used as challenge
  goals: [
    {
      title: 'Daily Discovery',
      description: 'Share what you learned today',
      cadence: 'daily',
      metric_type: 'journal',
      log_title_prompt: 'What did you learn today?',
      target_value: null,
      unit: null,
      sort_order: 0
    }
  ]
}
```

### 6.2 "Weekend Workshop" Template

**Use case:** DIY, home improvement, maker groups — people who build and fix things on weekends.

```typescript
{
  slug: 'weekend-workshop',
  title: 'Weekend Workshop',
  description: 'Make something, fix something, or build something every weekend. Even small wins count — a patch, a coat of paint, a tightened hinge.',
  icon_emoji: '🔧',
  icon_url: null,   // or e.g. 'asset://icons/templates/weekend-workshop.png' for bundled assets
  category: 'making',
  difficulty: 'easy',
  is_featured: false,
  duration_days: null,
  goals: [
    {
      title: 'Weekend Project',
      description: 'Share what you made, fixed, or built this weekend',
      cadence: 'weekly',
      metric_type: 'journal',
      log_title_prompt: 'What did you make or fix?',
      target_value: null,
      unit: null,
      sort_order: 0
    }
  ]
}
```

### 6.3 Other General-Purpose Journal Templates Worth Considering

These follow the same pattern and would broaden Pursue's appeal across creative and reflective use cases:

| Slug | Title | Prompt | Cadence | Category |
|------|-------|--------|---------|----------|
| `gratitude-daily` | Daily Gratitude | "What are you grateful for today?" | Daily | Wellness |
| `one-good-thing` | One Good Thing | "What's one good thing that happened today?" | Daily | Wellness |
| `creative-spark` | Creative Spark | "What did you create or make today?" | Daily | Creative |
| `book-notes` | Book Notes | "What did you read and what stood out?" | Weekly | Reading |
| `weekly-win` | Weekly Win | "What's your biggest win this week?" | Weekly | Productivity |
| `act-of-kindness` | Acts of Kindness | "How did you help someone today?" | Daily | Social |
| `skill-builder` | Skill Builder | "What did you practise today?" | Daily | Learning |
| `nature-moment` | Nature Moment | "What did you notice in nature today?" | Daily | Mindfulness |

These all share the same structure as Daily Discovery — the specific content varies, but the mechanic is identical: you did something, you name it, the group sees it.

---

## 7. Dual-Use Templates: Groups and Challenges

### 7.1 The Problem

Templates currently live in `challenge_templates` and carry a `duration_days` field that implies time-boxing. But Daily Discovery and Weekend Workshop are inherently ongoing — they're not "30-day challenges", they're lifestyle groups. Forcing them into the challenge paradigm (even with a long duration) feels wrong and adds artificial urgency to something that should feel permanent.

### 7.2 The Insight

The only real difference between a group and a challenge is time-boxing. The goals, the social mechanics, the feed, the notifications — everything else is identical. Templates should be neutral on this question and let the person creating the group/challenge decide.

### 7.3 Schema Changes

```sql
-- Rename challenge_templates to group_templates (or add template_type)
-- Simplest approach: add a template_mode column

ALTER TABLE challenge_templates RENAME TO group_templates;

ALTER TABLE group_templates ADD COLUMN default_mode VARCHAR(20) 
  NOT NULL DEFAULT 'challenge' 
  CHECK (default_mode IN ('group', 'challenge', 'either'));

-- icon_url already added via migration; references bundled app assets or GCS URLs
-- icon_url takes precedence over icon_emoji when both are present
ALTER TABLE group_templates ADD COLUMN IF NOT EXISTS icon_url TEXT;

ALTER TABLE group_templates ALTER COLUMN duration_days DROP NOT NULL;
-- duration_days NULL means "no default duration — user decides"
```

Update references:
```sql
ALTER TABLE groups RENAME COLUMN challenge_template_id TO template_id;
```

### 7.4 Template Browser UI Changes

The template browser gains a mode toggle at the top level:

```
[ Start a Challenge ]  [ Create a Group ]
```

Or, more elegantly, templates are presented universally and the time-boxing question is asked during setup:

```
Template selected: "Daily Discovery 🧠"

How do you want to run this?
○ Ongoing group  — no end date, runs indefinitely
○ Challenge       — [30] days, ends [date]
```

Templates with `default_mode = 'challenge'` pre-select Challenge; those with `default_mode = 'group'` pre-select Ongoing Group; `'either'` defaults to group.

### 7.5 API Changes

`POST /api/groups` (or `POST /api/challenges`) gets unified:

```json
// Create from template as ongoing group
{
  "template_id": "template-uuid",
  "group_name": "Polymath Project",
  "mode": "group"
  // No start_date, no end_date
}

// Create from template as challenge
{
  "template_id": "template-uuid",
  "group_name": "January Discovery Challenge",
  "mode": "challenge",
  "start_date": "2026-02-01",
  "duration_days": 31   // Override template default, or use template value
}
```

The server uses `mode` to set `is_challenge` on the groups table and to compute/store `challenge_end_date`.

---

## 8. Does This Fit Pursue's Feature Set and Target User Base?

**Yes, with clear scope boundaries.**

The journal goal type fits Pursue's accountability model well because:
- The social mechanic is unchanged — group members still see each other's activity, react, and nudge
- The commitment is still daily/weekly — it's not free-form posting, it's structured logging
- The `log_title` requirement enforces that something actually happened, not just a check-in

Where it doesn't fit (and shouldn't go):
- Long-form posts, threaded comments, or multimedia — Pursue is not a social network
- Unstructured sharing outside the goal-logging flow — there's no "general chat" or "post anything" concept here

The Polymath Project and Weekend DIY archetypes are good fits for your early NZ/AU user base, which tends to skew toward intellectually curious, hands-on people. These templates also naturally attract groups of friends or colleagues with shared interests, which is exactly the social graph Pursue needs to grow through word of mouth.

**The dual-use template idea is a natural evolution** — it removes a false conceptual boundary that was always slightly artificial. "Challenge" and "group" are the same thing with different time horizons. Letting users choose makes templates more flexible without adding complexity to the underlying data model.

---

## 9. Implementation Checklist

### Backend
- [ ] Add `log_title VARCHAR(120)` column to `progress_entries`
- [ ] Add `'journal'` to `goals.metric_type` constraint
- [ ] Add `log_title_prompt VARCHAR(80)` column to `goals`
- [ ] Update `ProgressEntrySchema` Zod validation for journal type
- [ ] Update `CreateGoalSchema` for journal type
- [ ] Rename `challenge_templates` → `group_templates`
- [ ] Add `default_mode` column to `group_templates`
- [ ] Make `duration_days` nullable on `group_templates`
- [ ] Update `POST /api/challenges` or create unified `POST /api/groups-or-challenges` endpoint
- [ ] Update group_templates FK reference on groups table
- [ ] Seed Daily Discovery and Weekend Workshop templates
- [ ] Seed additional journal templates from §6.3 as desired

### Android
- [ ] Add `'journal'` as a supported `MetricType` enum value
- [ ] Update goal creation form to show `log_title_prompt` field for journal type
- [ ] Build journal-specific logging bottom sheet (title required, note optional)
- [ ] Update activity feed item to display `log_title` as primary content for journal entries
- [ ] Update goal history screen to render journal entries with title + note
- [ ] Update template browser to show mode toggle (Group vs Challenge)
- [ ] Update template setup screen to show duration picker only in Challenge mode
- [ ] Add `log_title_prompt` to goal detail display

---

## 10. Open Questions

1. **Should journal entries be searchable within a group?** A "Polymath Project" with 12 months of entries becomes a knowledge archive. Full-text search would be valuable but is out of scope for v1.

2. **Can a group have a mix of journal and binary goals?** Yes — the architecture supports this. A "Wellness Circle" could have `journal` (Daily Reflection) alongside `binary` (Drank 2L water). No special handling needed.

3. **Premium gating?** Journal as a `metric_type` should be free — it's a core feature, not a premium differentiator. Custom `log_title_prompt` text could potentially be a premium customisation, but this feels like over-gating something minor.

4. **Character limit on `log_title`:** 120 chars is proposed. This is enough for "Finished rebuilding the carburetor on the 1972 Honda CB350" (57 chars) but tight enough to prevent mini-essays. Revisit based on UX testing.

---

**End of Specification**
