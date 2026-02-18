# Pursue — Challenges Feature Specification

**Feature:** Start a Challenge Mode  
**Version:** 1.0  
**Status:** Draft  
**Platform:** Android (Material Design 3)  
**Depends On:** Existing groups, goals, progress_entries, group_memberships, user_notifications, invite_codes tables; shareable milestone cards system; notification inbox

---

## 1. Overview

### 1.1 What Is a Challenge?

A Challenge is a time-boxed group goal with a clear start date, end date, and predefined goals. Unlike regular groups (which are ongoing and open-ended), challenges create urgency and lower the commitment barrier: "Just try it for 30 days."

Challenges are built on top of the existing group infrastructure — a challenge **is** a group with additional metadata (start/end dates, template source, challenge-specific rules). This means all existing features — nudges, reactions, group heat, smart reminders, activity feed, progress logging — work inside challenges with zero additional effort.

### 1.2 Why Challenges Matter

**For growth:**
- "Join my 30-day no sugar challenge" is a more compelling invite than "join my accountability group"
- Time-boxed commitment lowers the psychological barrier to entry
- Challenge cards are inherently shareable — every share is a targeted ad to the sharer's social circle
- Challenge invite links can include the challenge name and duration in Open Graph previews, making them richer when shared on social media

**For engagement:**
- Defined end dates create urgency ("Day 22 of 30 — don't stop now!")
- Countdowns and progress bars give a sense of momentum that open-ended groups lack
- Completing a challenge feels like an achievement, triggering milestone cards and creating another share opportunity
- Users who complete one challenge are primed to start another

**For monetization:**
- Challenges create a natural premium upgrade trigger: users who enjoy one challenge want to create their own
- Challenge templates surface Pursue's value proposition to new users before they've even committed

### 1.3 Design Goals

- **Zero new UI paradigms:** Challenges use the existing group detail screen with a challenge header overlay — not a separate experience
- **Template-first:** Curated templates make challenge creation effortless and establish Pursue's brand identity
- **Viral by default:** Every challenge has a shareable card and a dedicated invite link with rich Open Graph previews
- **Freemium-friendly:** Challenges are the single best viral acquisition channel — restricting creation to premium would be leaving growth on the table

---

## 2. Freemium Model for Challenges

### 2.1 Decision: Free Users Can Create One Challenge

Challenges are Pursue's highest-virality feature. Every challenge created generates invite links, shareable cards, and social proof. Restricting creation to premium users would dramatically reduce the viral loop.

**Free tier:**
- Create **1 active challenge** (in addition to their 1 regular group)
- Join **1 active challenge** created by someone else (in addition to their 1 regular group)
- All challenge features: progress logging, nudges, reactions, group heat, activity feed, shareable cards

**Premium tier ($30/year):**
- Create **unlimited active challenges** (within the existing 10-group creation limit)
- Join **unlimited active challenges** (within the existing 10-group join limit)
- Create custom challenges (not from templates) — see §2.2

### 2.2 Why Separate Challenges from Regular Groups?

Challenges and regular groups serve different purposes and have different retention profiles. Counting them against the same single-group limit for free users would force an unnatural choice: "Do I want ongoing accountability with my running buddies, or do I want to try a 30-day meditation challenge?" That's a false dilemma that reduces engagement.

**Separate limits mean:**
- Free users get: 1 regular group + 1 challenge created + 1 challenge joined = up to 3 active group-like entities
- This is generous enough to demonstrate value without giving away the full product
- The natural upgrade trigger is wanting to run a second challenge or join multiple challenges simultaneously

### 2.3 Custom Challenges

Free users can only create challenges **from the template library** (§4). Premium users can create **custom challenges** with arbitrary goals, durations, and descriptions. This is a meaningful differentiator that keeps the free experience curated and on-brand while giving premium users creative freedom.

---

## 3. Data Model

### 3.1 Challenge Metadata on Groups

Rather than creating a separate `challenges` table, challenges are groups with additional metadata. This preserves all existing group functionality and avoids data duplication.

**Schema changes to `groups` table:**

```sql
ALTER TABLE groups ADD COLUMN is_challenge BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE groups ADD COLUMN challenge_start_date DATE;        -- User's local date
ALTER TABLE groups ADD COLUMN challenge_end_date DATE;          -- User's local date
ALTER TABLE groups ADD COLUMN challenge_template_id UUID;       -- NULL for custom challenges
ALTER TABLE groups ADD COLUMN challenge_status VARCHAR(20)      -- 'upcoming', 'active', 'completed', 'cancelled'
  DEFAULT NULL;

-- Index for querying active challenges
CREATE INDEX idx_groups_challenge_status 
  ON groups(is_challenge, challenge_status) 
  WHERE is_challenge = TRUE;

-- Index for template usage analytics
CREATE INDEX idx_groups_challenge_template 
  ON groups(challenge_template_id) 
  WHERE challenge_template_id IS NOT NULL;
```

**Column definitions:**

| Column | Type | Description |
|--------|------|-------------|
| `is_challenge` | boolean | Distinguishes challenges from regular groups |
| `challenge_start_date` | DATE | First day of the challenge (local date, like `period_start`) |
| `challenge_end_date` | DATE | Last day of the challenge (inclusive) |
| `challenge_template_id` | UUID | FK to `challenge_templates`; NULL for custom challenges |
| `challenge_status` | varchar(20) | Lifecycle state: `upcoming`, `active`, `completed`, `cancelled` |

**Status transitions:**
```
upcoming → active       (automated: when current date >= challenge_start_date)
upcoming → cancelled    (manual: creator cancels before start)
active → completed      (automated: when current date > challenge_end_date)
active → cancelled      (manual: creator cancels mid-challenge)
completed → (terminal)
cancelled → (terminal)
```

**Why not a separate table?** A challenge needs groups, goals, memberships, activity feeds, invite codes, progress entries, nudges, reactions, and group heat. All of these already reference `groups.id`. Adding columns to `groups` means every existing feature works automatically.

### 3.2 Challenge Templates Table

```sql
CREATE TABLE challenge_templates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug VARCHAR(100) UNIQUE NOT NULL,          -- URL-friendly identifier: '30-day-no-sugar'
  title VARCHAR(200) NOT NULL,                -- '30-Day No Sugar Challenge'
  description TEXT NOT NULL,                  -- Marketing copy for the template
  icon_emoji VARCHAR(10) NOT NULL,            -- Default group icon
  duration_days INTEGER NOT NULL,             -- 30, 21, 260, 365, etc.
  category VARCHAR(50) NOT NULL,              -- 'fitness', 'reading', 'spiritual', 'wellness', etc.
  difficulty VARCHAR(20) NOT NULL DEFAULT 'moderate', -- 'easy', 'moderate', 'hard'
  is_featured BOOLEAN NOT NULL DEFAULT FALSE, -- Highlighted in template browser
  sort_order INTEGER NOT NULL DEFAULT 0,      -- Display ordering within category
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_challenge_templates_category ON challenge_templates(category, sort_order);
CREATE INDEX idx_challenge_templates_featured ON challenge_templates(is_featured, sort_order) WHERE is_featured = TRUE;
```

### 3.3 Challenge Template Goals Table

Each template includes predefined goals that are auto-created when the challenge starts:

```sql
CREATE TABLE challenge_template_goals (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  template_id UUID NOT NULL REFERENCES challenge_templates(id) ON DELETE CASCADE,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  cadence VARCHAR(20) NOT NULL,               -- 'daily', 'weekly', etc.
  metric_type VARCHAR(20) NOT NULL,           -- 'binary', 'numeric', 'duration'
  target_value DECIMAL(10,2),
  unit VARCHAR(50),
  sort_order INTEGER NOT NULL DEFAULT 0,      -- Goal display ordering
  
  UNIQUE(template_id, sort_order)
);

CREATE INDEX idx_template_goals_template ON challenge_template_goals(template_id, sort_order);
```

### 3.4 Resource Limits for Challenges

Update the existing resource limit checks to handle challenges separately:

```typescript
const CHALLENGE_LIMITS = {
  FREE: {
    MAX_CHALLENGES_CREATED: 1,    // Active challenges created
    MAX_CHALLENGES_JOINED: 1,     // Active challenges joined (not created by user)
  },
  PREMIUM: {
    MAX_CHALLENGES_CREATED: 10,   // Same as regular group limit
    MAX_CHALLENGES_JOINED: 10,    // Same as regular group limit
  }
};
```

**"Active" means:** `challenge_status IN ('upcoming', 'active')`. Completed and cancelled challenges do not count toward limits. This means free users can run challenge after challenge sequentially — they just can't have two running simultaneously.

### 3.5 Migration Script

```sql
-- Migration: 20260218_add_challenges.sql
-- Description: Add Challenges feature

BEGIN;

-- Add challenge columns to groups
ALTER TABLE groups ADD COLUMN is_challenge BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE groups ADD COLUMN challenge_start_date DATE;
ALTER TABLE groups ADD COLUMN challenge_end_date DATE;
ALTER TABLE groups ADD COLUMN challenge_template_id UUID;
ALTER TABLE groups ADD COLUMN challenge_status VARCHAR(20) DEFAULT NULL;

-- Add check constraint for challenge fields
ALTER TABLE groups ADD CONSTRAINT chk_challenge_fields CHECK (
  (is_challenge = FALSE AND challenge_start_date IS NULL AND challenge_end_date IS NULL AND challenge_status IS NULL)
  OR
  (is_challenge = TRUE AND challenge_start_date IS NOT NULL AND challenge_end_date IS NOT NULL AND challenge_status IS NOT NULL)
);

-- Add check constraint for valid date range
ALTER TABLE groups ADD CONSTRAINT chk_challenge_dates CHECK (
  challenge_end_date IS NULL OR challenge_end_date >= challenge_start_date
);

-- Add check constraint for valid status values
ALTER TABLE groups ADD CONSTRAINT chk_challenge_status CHECK (
  challenge_status IS NULL OR challenge_status IN ('upcoming', 'active', 'completed', 'cancelled')
);

-- Indexes
CREATE INDEX idx_groups_challenge_status 
  ON groups(is_challenge, challenge_status) 
  WHERE is_challenge = TRUE;

CREATE INDEX idx_groups_challenge_template 
  ON groups(challenge_template_id) 
  WHERE challenge_template_id IS NOT NULL;

-- Challenge templates
CREATE TABLE challenge_templates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slug VARCHAR(100) UNIQUE NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  icon_emoji VARCHAR(10) NOT NULL,
  duration_days INTEGER NOT NULL,
  category VARCHAR(50) NOT NULL,
  difficulty VARCHAR(20) NOT NULL DEFAULT 'moderate',
  is_featured BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_challenge_templates_category ON challenge_templates(category, sort_order);
CREATE INDEX idx_challenge_templates_featured ON challenge_templates(is_featured, sort_order) WHERE is_featured = TRUE;

-- Challenge template goals
CREATE TABLE challenge_template_goals (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  template_id UUID NOT NULL REFERENCES challenge_templates(id) ON DELETE CASCADE,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  cadence VARCHAR(20) NOT NULL,
  metric_type VARCHAR(20) NOT NULL,
  target_value DECIMAL(10,2),
  unit VARCHAR(50),
  sort_order INTEGER NOT NULL DEFAULT 0,
  UNIQUE(template_id, sort_order)
);

CREATE INDEX idx_template_goals_template ON challenge_template_goals(template_id, sort_order);

-- Add FK from groups to challenge_templates (after template table exists)
ALTER TABLE groups ADD CONSTRAINT fk_groups_challenge_template 
  FOREIGN KEY (challenge_template_id) REFERENCES challenge_templates(id) ON DELETE SET NULL;

-- Seed challenge templates (see §4 for full list)
-- Seeded via application code or separate seed script

COMMIT;
```

---

## 4. Challenge Template Library

### 4.1 Curated Templates

Templates are seeded into the database and can be updated via app releases or server-side config. Each template defines a title, description, duration, and one or more goals.

#### Fitness

| Template | Duration | Goal(s) | Difficulty |
|----------|----------|---------|------------|
| **10K Steps Daily** | 30 days | 10,000 steps/day (numeric, unit: steps) | Moderate |
| **Couch to 5K** | 60 days | Run today (binary, daily) | Moderate |
| **100 Pushups a Day** | 30 days | 100 pushups/day (numeric, unit: pushups) | Hard |
| **30-Day Yoga** | 30 days | Complete yoga session (binary, daily) | Easy |
| **10K Steps for a Week** | 7 days | 10,000 steps/day (numeric, unit: steps) | Easy |
| **Plank Challenge** | 30 days | Hold plank (duration, target increases weekly: 30s→60s→90s→120s) | Moderate |
| **Morning Workout** | 21 days | Exercise before 9am (binary, daily) | Moderate |

#### Reading

| Template | Duration | Goal(s) | Difficulty |
|----------|----------|---------|------------|
| **Read the Bible in a Year** | 365 days | 3 chapters/day (numeric, unit: chapters) | Moderate |
| **Read the New Testament in 260 Days** | 260 days | 1 chapter/day (numeric, unit: chapters) | Easy |
| **Book a Month** | 30 days | Read 30 min/day (duration, target: 30 min) | Easy |
| **50 Books in a Year** | 365 days | Read today (binary, daily) + 1 book/week (numeric, weekly, unit: books) | Hard |
| **Read the Quran in 30 Days** | 30 days | 20 pages/day (numeric, unit: pages) | Moderate |

#### Wellness & Mindfulness

| Template | Duration | Goal(s) | Difficulty |
|----------|----------|---------|------------|
| **21-Day Meditation** | 21 days | Meditate 10 min (duration, target: 10 min) | Easy |
| **30-Day Gratitude Journal** | 30 days | Write 3 things I'm grateful for (binary, daily) | Easy |
| **No Social Media for 30 Days** | 30 days | No social media today (binary, daily) | Hard |
| **8 Glasses of Water** | 30 days | 8 glasses/day (numeric, unit: glasses) | Easy |
| **7 Hours of Sleep** | 30 days | 7+ hours sleep (numeric, unit: hours) | Moderate |
| **Digital Detox Weekend** | 2 days | Stay off screens (binary, daily) | Moderate |
| **Cold Shower Challenge** | 30 days | Take cold shower (binary, daily) | Hard |

#### Diet & Nutrition

| Template | Duration | Goal(s) | Difficulty |
|----------|----------|---------|------------|
| **30-Day No Sugar** | 30 days | No added sugar today (binary, daily) | Hard |
| **Whole30** | 30 days | Ate whole foods only (binary, daily) | Hard |
| **Meatless Monday for a Month** | 30 days | No meat on Monday (binary, weekly) | Easy |
| **Cook at Home** | 30 days | Cooked at home (binary, daily) | Moderate |
| **No Alcohol for 30 Days** | 30 days | No alcohol today (binary, daily) | Moderate |

#### Productivity & Learning

| Template | Duration | Goal(s) | Difficulty |
|----------|----------|---------|------------|
| **30 Days of Coding** | 30 days | Write code today (binary, daily) | Moderate |
| **Language Learning Sprint** | 30 days | Study 15 min/day (duration, target: 15 min) | Easy |
| **Wake Up at 5am** | 21 days | Woke up by 5am (binary, daily) | Hard |
| **Inbox Zero for a Month** | 30 days | Cleared inbox today (binary, daily) | Moderate |
| **Daily Journaling** | 30 days | Wrote in journal (binary, daily) | Easy |

#### Finance

| Template | Duration | Goal(s) | Difficulty |
|----------|----------|---------|------------|
| **No Spend Challenge** | 30 days | No unnecessary purchases today (binary, daily) | Hard |
| **Save $5 a Day** | 30 days | Saved $5 today (binary, daily) | Easy |
| **Track Every Dollar** | 30 days | Logged all expenses (binary, daily) | Moderate |

#### Social & Relationships

| Template | Duration | Goal(s) | Difficulty |
|----------|----------|---------|------------|
| **Reach Out Daily** | 30 days | Contacted a friend or family member (binary, daily) | Easy |
| **30 Days of Kindness** | 30 days | Did one kind act (binary, daily) | Easy |
| **Phone Call a Day** | 21 days | Called someone (binary, daily) | Moderate |

### 4.2 Template Seed Data Format

Templates are seeded via a server-side script. Example for "30-Day No Sugar":

```typescript
const CHALLENGE_TEMPLATES: TemplateSeed[] = [
  {
    slug: '30-day-no-sugar',
    title: '30-Day No Sugar Challenge',
    description: 'Cut out added sugar for 30 days. Track your progress daily and hold each other accountable. Most participants report better energy, clearer skin, and reduced cravings by day 14.',
    icon_emoji: '🍬',
    duration_days: 30,
    category: 'diet',
    difficulty: 'hard',
    is_featured: true,
    sort_order: 1,
    goals: [
      {
        title: 'No added sugar today',
        description: 'Avoid all foods and drinks with added sugar',
        cadence: 'daily',
        metric_type: 'binary',
        target_value: null,
        unit: null,
        sort_order: 0
      }
    ]
  },
  {
    slug: 'read-bible-one-year',
    title: 'Read the Bible in a Year',
    description: 'Read through the entire Bible in 365 days by reading 3 chapters each day. A classic challenge that\'s better with friends keeping you accountable.',
    icon_emoji: '📖',
    duration_days: 365,
    category: 'reading',
    difficulty: 'moderate',
    is_featured: true,
    sort_order: 1,
    goals: [
      {
        title: 'Read 3 chapters',
        description: 'Read 3 chapters of the Bible today',
        cadence: 'daily',
        metric_type: 'numeric',
        target_value: 3,
        unit: 'chapters',
        sort_order: 0
      }
    ]
  },
  // ... remaining templates
];
```

### 4.3 Adding New Templates

New templates can be added via database migration without an app update. The client fetches the template list from the API, so new templates appear automatically. Template additions should be reviewed for quality and appropriateness before seeding.

**Future:** Allow premium users to submit custom challenges to a "community templates" library (moderated). Out of scope for v1.

---

## 5. API Endpoints

### 5.1 GET /api/challenge-templates

List all available challenge templates for the template browser.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Query Parameters:**
- `category` (optional): Filter by category ('fitness', 'reading', 'wellness', etc.)
- `featured` (optional): `true` to show only featured templates

**Response (200 OK):**
```json
{
  "templates": [
    {
      "id": "template-uuid",
      "slug": "30-day-no-sugar",
      "title": "30-Day No Sugar Challenge",
      "description": "Cut out added sugar for 30 days...",
      "icon_emoji": "🍬",
      "duration_days": 30,
      "category": "diet",
      "difficulty": "hard",
      "is_featured": true,
      "goals": [
        {
          "title": "No added sugar today",
          "description": "Avoid all foods and drinks with added sugar",
          "cadence": "daily",
          "metric_type": "binary",
          "target_value": null,
          "unit": null
        }
      ]
    }
  ],
  "categories": ["fitness", "reading", "wellness", "diet", "productivity", "finance", "social"]
}
```

**Notes:**
- No authentication required — template browsing is public (useful for pre-signup marketing page)
- Actually, keep auth required for now (simpler), but consider making it public later for SEO/marketing

### 5.2 POST /api/challenges

Create a challenge from a template or as a custom challenge.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request (from template):**
```json
{
  "template_id": "template-uuid",
  "start_date": "2026-03-01",
  "group_name": "No Sugar March 🍬"
}
```

**Request (custom — premium only):**
```json
{
  "start_date": "2026-03-01",
  "end_date": "2026-03-31",
  "group_name": "Our Custom Challenge",
  "group_description": "A custom challenge we designed",
  "icon_emoji": "⚡",
  "goals": [
    {
      "title": "Complete daily task",
      "cadence": "daily",
      "metric_type": "binary"
    }
  ]
}
```

**Validation:**
- `start_date`: Required. Must be today or in the future. Max 30 days in the future.
- `template_id`: Required for free users. Optional for premium users.
- `end_date`: Computed from template `duration_days` if using template. Required if custom.
- `group_name`: Optional. Defaults to template title if not provided. Max 100 chars.
- `goals`: Required if custom (no template_id). Max 10 goals per challenge.

**Server Logic:**
1. Verify authentication
2. Check challenge creation limits (free: 1 active created, premium: 10)
3. If `template_id` provided: fetch template and compute `end_date = start_date + duration_days - 1`
4. If no `template_id`: verify user is premium; validate custom goals
5. Create group with `is_challenge = TRUE`, `challenge_status = 'upcoming'`
6. Create goals from template or custom input
7. Generate invite code for the challenge
8. Return challenge data

**Response (201 Created):**
```json
{
  "challenge": {
    "id": "group-uuid",
    "name": "No Sugar March 🍬",
    "is_challenge": true,
    "challenge_start_date": "2026-03-01",
    "challenge_end_date": "2026-03-30",
    "challenge_status": "upcoming",
    "challenge_template_id": "template-uuid",
    "member_count": 1,
    "goals": [ /* goal objects */ ],
    "invite_code": "PURSUE-ABC123-XYZ789",
    "invite_url": "https://getpursue.app/challenge/PURSUE-ABC123-XYZ789"
  }
}
```

**Errors:**
- 400: Invalid dates, missing required fields
- 403: Custom challenge requires premium
- 429: Challenge creation limit reached

### 5.3 POST /api/challenges/join

Join a challenge via invite code. Uses the existing `/api/groups/join` endpoint — no new endpoint needed. The existing join logic checks group limits; the resource limit checker needs to be updated to distinguish challenge limits from regular group limits.

### 5.4 GET /api/challenges

List the current user's challenges (active, upcoming, completed).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Query Parameters:**
- `status` (optional): Filter by status ('upcoming', 'active', 'completed', 'cancelled')

**Response (200 OK):**
```json
{
  "challenges": [
    {
      "id": "group-uuid",
      "name": "No Sugar March 🍬",
      "icon_emoji": "🍬",
      "challenge_start_date": "2026-03-01",
      "challenge_end_date": "2026-03-30",
      "challenge_status": "active",
      "days_remaining": 12,
      "days_elapsed": 18,
      "total_days": 30,
      "member_count": 5,
      "my_completion_rate": 0.89,
      "heat_tier": 4,
      "template_title": "30-Day No Sugar Challenge"
    }
  ]
}
```

### 5.5 PATCH /api/challenges/:id/cancel

Cancel a challenge (creator only). Sets `challenge_status = 'cancelled'`.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Server Logic:**
1. Verify user is challenge creator
2. Verify status is `upcoming` or `active`
3. Set `challenge_status = 'cancelled'`
4. Send FCM to all members: "The [Challenge Name] challenge has been cancelled by the organizer."
5. Insert activity entry: `challenge_cancelled`

**Response (200 OK):**
```json
{
  "id": "group-uuid",
  "challenge_status": "cancelled"
}
```

---

## 6. Challenge Lifecycle

### 6.1 Status Transitions (Scheduled Job)

A Cloud Scheduler job runs daily (e.g., 00:15 UTC) to transition challenge statuses. This job should run in the same scheduled jobs infrastructure as smart reminders and group heat recalculation.

```typescript
async function updateChallengeStatuses() {
  const today = new Date().toISOString().split('T')[0]; // UTC date

  // Upcoming → Active
  await db
    .update('groups')
    .set({ challenge_status: 'active', updated_at: new Date() })
    .where('is_challenge', '=', true)
    .where('challenge_status', '=', 'upcoming')
    .where('challenge_start_date', '<=', today)
    .execute();

  // Active → Completed
  const completedChallenges = await db
    .update('groups')
    .set({ challenge_status: 'completed', updated_at: new Date() })
    .where('is_challenge', '=', true)
    .where('challenge_status', '=', 'active')
    .where('challenge_end_date', '<', today)
    .returning(['id', 'name'])
    .execute();

  // Send completion notifications for each completed challenge
  for (const challenge of completedChallenges) {
    await sendChallengeCompletionNotifications(challenge.id, challenge.name);
  }
}
```

**Timezone consideration:** The scheduled job runs on UTC. A user in NZ (UTC+12) might see their challenge marked as completed a few hours before midnight local time, or a few hours after. This is acceptable for a daily-granularity feature — the same approach used for group heat recalculation. If this becomes an issue, we can shift the job to run at multiple times (e.g., every 6 hours) or use the creator's timezone as the reference.

### 6.2 Challenge Completion Flow

When a challenge transitions to `completed`:

1. **Activity entry:** Insert `challenge_completed` activity in the group feed
2. **Milestone notification:** For each member, create a `milestone_achieved` notification with `metadata: { milestone_type: 'challenge_completed', challenge_name: '...', completion_rate: 0.93 }`
3. **Shareable challenge card:** Generate `shareable_card_data` on the milestone notification (see §8)
4. **FCM push:** Send to all members: "🎉 Challenge Complete! You finished the 30-Day No Sugar Challenge with a 93% completion rate. Tap to share!"

### 6.3 Joining a Challenge After It Starts

Users can join active challenges (not just upcoming ones). When joining mid-challenge:
- Progress is only expected from the join date forward
- Completion rate is calculated based on days since join, not total challenge days
- The member is not retroactively penalized for days before they joined
- Group heat calculations already handle mid-window joins (per group-heat-spec.md §2.2)

### 6.4 Challenge Goals Are Immutable

Unlike regular groups where admins can add/archive goals at any time, challenge goals are locked once the challenge starts (status = `active`). This ensures fairness and consistency — everyone in the challenge is working toward the same goals. The creator can still add goals while the challenge is `upcoming`.

**Implementation:** The existing `POST /api/groups/:group_id/goals` endpoint checks `groups.is_challenge AND groups.challenge_status = 'active'` and returns 403 if true.

---

## 7. UI Design

### 7.1 Challenge Discovery: Template Browser

Accessed via a prominent "Start a Challenge" button on the group list screen (alongside the existing "Create Group" FAB or Speed Dial).

```
┌─────────────────────────────────────────────┐
│  ← Start a Challenge                        │
├─────────────────────────────────────────────┤
│                                             │
│  ✨ Featured                                │
│  ┌─────────────────┐ ┌─────────────────┐   │
│  │ 🍬              │ │ 🧘              │   │
│  │ 30-Day No Sugar │ │ 21-Day          │   │
│  │                 │ │ Meditation      │   │
│  │ 30 days · Hard  │ │ 21 days · Easy  │   │
│  │ [Start]         │ │ [Start]         │   │
│  └─────────────────┘ └─────────────────┘   │
│                                             │
│  💪 Fitness                                 │
│  ┌─────────────────┐ ┌─────────────────┐   │
│  │ 👟              │ │ 💪              │   │
│  │ 10K Steps Daily │ │ 100 Pushups     │   │
│  │                 │ │ a Day           │   │
│  │ 30 days · Med   │ │ 30 days · Hard  │   │
│  │ [Start]         │ │ [Start]         │   │
│  └─────────────────┘ └─────────────────┘   │
│                                             │
│  📚 Reading                                 │
│  ...                                        │
│                                             │
│  ─────────────────────────────────────────  │
│  🔓 Premium: Create custom challenge        │
│  Design your own challenge with any goals.  │
│  [Create Custom]                            │
│  ─────────────────────────────────────────  │
│                                             │
└─────────────────────────────────────────────┘
```

**Layout details:**
- Horizontal scrolling rows per category (Material 3 `LazyRow`)
- Each template card shows: icon, title, duration, difficulty badge
- Featured row at top with larger cards
- "Create Custom" section at bottom (greyed with lock icon for free users, tap shows upgrade prompt)
- Category filter chips at top (optional, may add visual clutter — test without first)

### 7.2 Challenge Setup Screen

After tapping "Start" on a template:

```
┌─────────────────────────────────────────────┐
│  ← Set Up Challenge                         │
├─────────────────────────────────────────────┤
│                                             │
│  🍬 30-Day No Sugar Challenge               │
│  Cut out added sugar for 30 days. Track     │
│  your progress daily and hold each other    │
│  accountable.                               │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ Challenge Name                      │    │
│  │ No Sugar March 🍬                   │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │ Start Date                          │    │
│  │ March 1, 2026                   📅  │    │
│  └─────────────────────────────────────┘    │
│                                             │
│  End Date: March 30, 2026 (30 days)         │
│                                             │
│  Goals included:                            │
│  ✅ No added sugar today (daily, binary)    │
│                                             │
│                                             │
│  ┌─────────────────────────────────────┐    │
│  │       🚀 Start Challenge            │    │
│  └─────────────────────────────────────┘    │
│                                             │
└─────────────────────────────────────────────┘
```

**Fields:**
- **Challenge Name:** Pre-filled from template title. Editable. Max 100 chars.
- **Start Date:** Date picker. Defaults to tomorrow. Min: today. Max: 30 days from now.
- **End Date:** Computed and read-only (start + duration - 1). Displayed for clarity.
- **Goals:** Read-only list from template. Shown for transparency.

### 7.3 Challenge Header in Group Detail

When viewing a challenge in GroupDetailFragment, a challenge-specific header appears above the regular goal list:

```
┌─────────────────────────────────────────────┐
│  ← No Sugar March 🍬                    ⋮  │
├─────────────────────────────────────────────┤
│  ┌─────────────────────────────────────────┐│
│  │  🍬 30-Day No Sugar Challenge          ││
│  │  Day 18 of 30                          ││
│  │  ████████████████████░░░░░░░  60%      ││
│  │  12 days remaining                     ││
│  │                                        ││
│  │  [Share Challenge]  [Invite Friends]   ││
│  └─────────────────────────────────────────┘│
│                                             │
│  Goals                                      │
│  ... (regular goal list with member         │
│       progress, same as any group)          │
│                                             │
└─────────────────────────────────────────────┘
```

**Challenge header details:**
- Template title (or "Custom Challenge" for custom)
- Progress bar: `days_elapsed / total_days`
- Days remaining countdown
- "Share Challenge" button → generates shareable challenge card (§8)
- "Invite Friends" button → shows invite code/link with share sheet

**Completed challenge header:**
```
┌─────────────────────────────────────────────┐
│  🎉 Challenge Complete!                     │
│  You completed 28 of 30 days (93%)          │
│  [Share Your Achievement]                   │
└─────────────────────────────────────────────┘
```

### 7.4 Challenge Cards on Group List

On the main group list screen, challenges appear alongside regular groups but are visually distinct:

```
┌─────────────────────────────────────────────┐
│  🏃 Morning Runners          🔥🔥🔥       │ ← Regular group
│  5 members                                  │
├─────────────────────────────────────────────┤
│  🍬 No Sugar March           Day 18/30     │ ← Challenge
│  5 members · 12 days left    ██████░░░     │
├─────────────────────────────────────────────┤
│  📖 Bible in a Year          Day 45/365    │ ← Challenge
│  3 members · 320 days left   █░░░░░░░     │
└─────────────────────────────────────────────┘
```

**Visual differences for challenge cards:**
- Progress bar instead of (or alongside) group heat flame
- "Day X/Y" label with days remaining
- Subtle accent color or badge ("Challenge") to differentiate from regular groups
- Completed challenges show a ✅ badge and greyed progress bar

### 7.5 Challenges in the Today Screen

Challenge goals appear in the Today screen alongside regular group goals, using the existing combined goal list. No special treatment needed — they're just goals. The group name badge on each goal row already provides context.

### 7.6 Speed Dial Integration

Update the existing Speed Dial FAB to include "Start a Challenge":

```
Speed Dial Options:
  1. Create Group (existing)
  2. Start a Challenge (new) → opens template browser
  3. Join with Code (existing)
```

---

## 8. Shareable Challenge Cards

### 8.1 Challenge Invite Card

A beautiful card for sharing when inviting friends to a challenge. Generated client-side, similar to milestone cards.

**Card layout (1080×1920 for Instagram Stories):**

```
┌─────────────────────────────────────────┐
│                                         │
│            🍬                           │
│                                         │
│     30-Day No Sugar                     │
│        Challenge                        │
│                                         │
│     March 1 – March 30, 2026           │
│                                         │
│     "Are you in?"                       │
│                                         │
│     ┌───────────────────────────┐       │
│     │  Join at getpursue.app    │       │
│     └───────────────────────────┘       │
│                                         │
│              pursue                     │
│     ─────────────────────────           │
│     Group accountability for goals      │
│                                         │
└─────────────────────────────────────────┘
```

**Card data (stored on group, not notification):**

```json
{
  "card_type": "challenge_invite",
  "title": "30-Day No Sugar Challenge",
  "subtitle": "March 1 – March 30, 2026",
  "icon_emoji": "🍬",
  "cta_text": "Are you in?",
  "background_gradient": ["#E53935", "#C62828"],
  "invite_url": "https://getpursue.app/challenge/PURSUE-ABC123-XYZ789"
}
```

### 8.2 Challenge Completion Card

Generated when the challenge completes. Uses the existing shareable milestone card infrastructure.

**Milestone card data:**
```json
{
  "milestone_type": "challenge_completed",
  "title": "Challenge Complete!",
  "subtitle": "30-Day No Sugar Challenge",
  "stat_value": "28",
  "stat_label": "of 30 days completed",
  "quote": "Discipline is choosing between what you want now and what you want most",
  "goal_icon_emoji": "🍬",
  "background_color": "#E53935",
  "generated_at": "2026-03-31T00:15:00Z"
}
```

### 8.3 UTM Tracking for Challenge Shares

Challenge invite links use UTM parameters for attribution:

```
https://getpursue.app/challenge/{invite_code}?utm_source=share&utm_medium=challenge_card&utm_campaign={template_slug}&utm_content={user_id}
```

**Landing page behavior for challenge links:**
1. Track UTM parameters
2. Show challenge-specific landing: "Your friend started a 30-Day No Sugar Challenge! Join them on Pursue."
3. CTA: "Download on Play Store"
4. If app installed: deep link to challenge join flow

### 8.4 Open Graph Tags for Challenge Links

The `/challenge/{invite_code}` route on `getpursue.app` should include rich Open Graph tags:

```html
<meta property="og:title" content="Join the 30-Day No Sugar Challenge on Pursue">
<meta property="og:description" content="Your friend started a challenge! Join them and stay accountable together.">
<meta property="og:image" content="https://getpursue.app/og/challenge/30-day-no-sugar.png">
<meta property="og:url" content="https://getpursue.app/challenge/PURSUE-ABC123-XYZ789">
```

Pre-generate OG images for each template (static assets on Cloudflare Pages). Custom challenges use a generic Pursue OG image.

---

## 9. Notifications

### 9.1 Challenge Suggestion Notification

A proactive notification sent to active users who haven't created or joined a challenge, encouraging them to try one.

**Trigger conditions (all must be true):**
- User has been active for ≥7 days (has logged progress in 5 of the last 7 days)
- User has never created or joined a challenge
- User has not dismissed a challenge suggestion in the last 30 days
- User has at least 1 active group (demonstrates engagement)

**Timing:** Sent as part of the smart reminders scheduled job, during the user's typical active window.

**Push notification:**
```json
{
  "notification": {
    "title": "Ready for a challenge? 🏆",
    "body": "Start a 30-day challenge with your friends. Pick from dozens of templates!"
  },
  "data": {
    "type": "challenge_suggestion",
    "notification_id": "notif-uuid"
  }
}
```

**Tap behavior:** Opens the template browser (§7.1).

**Database tracking:**

```sql
-- Track suggestion dismissals to avoid nagging
CREATE TABLE challenge_suggestion_log (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sent_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  dismissed_at TIMESTAMP WITH TIME ZONE,
  converted BOOLEAN DEFAULT FALSE,     -- User created/joined a challenge after this
  UNIQUE(user_id)                       -- Only one active suggestion per user
);
```

**Rate limit:** Maximum 1 suggestion per 30 days per user. If the user creates or joins a challenge, stop sending suggestions permanently.

### 9.2 Challenge Countdown Notifications

Sent to challenge members at key milestones during the challenge:

| Trigger | Title | Body |
|---------|-------|------|
| 1 day before start | "🏁 Challenge starts tomorrow!" | "No Sugar March kicks off tomorrow. Make sure your group is ready!" |
| Day 1 | "🚀 Day 1 — let's go!" | "Your 30-Day No Sugar Challenge starts today. Log your first entry!" |
| Halfway point | "🔥 Halfway there!" | "Day 15 of 30 — you're halfway through No Sugar March! Keep pushing." |
| 3 days remaining | "⏰ 3 days left!" | "Just 3 more days in your No Sugar challenge. Don't stop now!" |
| Final day | "🏆 Last day!" | "It's the final day of No Sugar March. Finish strong!" |
| Completed | "🎉 Challenge Complete!" | "You finished the 30-Day No Sugar Challenge! Tap to share your achievement." |

**Implementation:** These are generated by the challenge status update scheduled job (§6.1). The job checks for challenges hitting these milestones and queues FCM pushes.

### 9.3 Inbox Integration

Challenge-specific notifications appear in the notification inbox:

| Type | Avatar | Accent | Icon overlay |
|------|--------|--------|-------------|
| Challenge suggestion | 🏆 system icon | None | None |
| Challenge starts tomorrow | Group icon | Blue left border | 🏁 |
| Challenge completed | Group icon | Gold left border | 🎉 |

---

## 10. Challenge Invite Link Flow

### 10.1 Invite Link Format

Challenge invite links use the same invite code system as regular groups, but with a challenge-specific URL path for richer Open Graph previews:

```
https://getpursue.app/challenge/{invite_code}
```

This URL resolves to the same underlying invite handler but with challenge-specific OG tags and marketing copy.

### 10.2 Join Flow for New Users

1. New user taps challenge link on social media
2. `getpursue.app/challenge/{invite_code}` shows challenge details + "Download on Play Store" CTA
3. User installs app, creates account
4. App detects pending invite code (via Android App Links / deferred deep link)
5. User auto-joins the challenge
6. Challenge appears on their group list with the challenge header

### 10.3 Join Flow for Existing Users

1. Existing user taps challenge link
2. Android App Link opens the Pursue app directly
3. App calls `POST /api/groups/join` with the invite code
4. If challenge is `upcoming` or `active`: user joins
5. If challenge is `completed` or `cancelled`: show message "This challenge has ended" with CTA to browse templates

---

## 11. Analytics Events

| Event | When | Properties |
|-------|------|-----------|
| `template_browser_opened` | User opens template browser | — |
| `template_viewed` | User taps a template card | `template_slug`, `category` |
| `challenge_created` | Challenge created successfully | `template_slug`, `duration_days`, `is_custom` |
| `challenge_joined` | User joins a challenge | `challenge_id`, `template_slug`, `days_remaining` |
| `challenge_invite_shared` | Challenge invite card shared | `challenge_id`, `share_method` (instagram/generic) |
| `challenge_card_viewed` | User opens challenge invite card | `challenge_id` |
| `challenge_completed_card_shared` | Completion card shared | `challenge_id`, `completion_rate` |
| `challenge_cancelled` | Creator cancels challenge | `challenge_id`, `days_elapsed` |
| `challenge_suggestion_sent` | Suggestion notification sent | `user_id` |
| `challenge_suggestion_tapped` | User taps suggestion notification | `user_id` |
| `challenge_suggestion_converted` | User creates/joins challenge after suggestion | `user_id`, `days_since_suggestion` |

---

## 12. Success Metrics

### 12.1 Launch Metrics (First 30 Days)

| Metric | Target | Measurement |
|--------|--------|-------------|
| **Challenge creation rate** | >20% of active users create a challenge | `challenge_created` / MAU |
| **Template usage** | >80% of challenges use a template | challenges with `template_id` / total challenges |
| **Invite share rate** | >40% of challenge creators share invite | `challenge_invite_shared` / `challenge_created` |
| **Join conversion** | >50% of invite recipients join | `challenge_joined` / invite link clicks |
| **Completion rate** | >60% of challenge members complete >80% of days | members with ≥80% completion / total members |
| **New user acquisition** | >5% of new signups attributed to challenge invites | UTM-attributed signups / total signups |

### 12.2 Long-Term Goals (6 Months)

- Challenge invites become **#1 acquisition channel** (surpassing organic search)
- >30% of active users participate in at least one challenge per month
- Challenge completion triggers >25% share rate on completion cards
- Free-to-premium conversion rate for challenge creators >15% (wanting to create second simultaneous challenge or custom challenge)

---

## 13. Implementation Checklist

### 13.1 Backend

**Database:**
- [ ] Add challenge columns to `groups` table
- [ ] Add check constraints for challenge fields
- [ ] Create `challenge_templates` table
- [ ] Create `challenge_template_goals` table
- [ ] Create `challenge_suggestion_log` table
- [ ] Write migration script
- [ ] Seed initial template data (§4)

**API Endpoints:**
- [ ] `GET /api/challenge-templates` — list templates with goals
- [ ] `POST /api/challenges` — create challenge from template or custom
- [ ] `GET /api/challenges` — list user's challenges with progress stats
- [ ] `PATCH /api/challenges/:id/cancel` — cancel challenge
- [ ] Update `POST /api/groups/join` — handle challenge-specific limits
- [ ] Update `POST /api/groups/:id/goals` — block goal creation on active challenges
- [ ] Update resource limit checks to separate challenge vs regular group limits

**Scheduled Jobs:**
- [ ] Challenge status transition job (upcoming→active, active→completed)
- [ ] Challenge completion notification job
- [ ] Challenge countdown notification job (1 day before, day 1, halfway, 3 days left, final day)
- [ ] Challenge suggestion notification job (for engaged users without challenges)

**Notifications:**
- [ ] `challenge_completed` milestone notification with shareable card data
- [ ] Challenge countdown FCM pushes
- [ ] Challenge suggestion FCM push
- [ ] Challenge cancelled FCM push

### 13.2 Android

**Template Browser:**
- [ ] `ChallengeTemplatesScreen` composable with category rows
- [ ] `TemplateCard` composable (icon, title, duration, difficulty badge)
- [ ] `ChallengeTemplateViewModel` — fetch templates, handle create
- [ ] Category horizontal scroll rows (`LazyRow`)
- [ ] Featured section with larger cards

**Challenge Setup:**
- [ ] `ChallengeSetupScreen` composable
- [ ] Date picker for start date
- [ ] Auto-computed end date display
- [ ] Editable challenge name field
- [ ] Read-only goal list preview

**Group Detail Modifications:**
- [ ] Challenge header composable (progress bar, days remaining, share/invite buttons)
- [ ] Completed challenge header composable
- [ ] Block "Add Goal" button for active challenges

**Group List Modifications:**
- [ ] Challenge-specific card layout (progress bar, Day X/Y, days remaining)
- [ ] Visual differentiation from regular group cards
- [ ] Completed challenge badge (✅)

**Speed Dial:**
- [ ] Add "Start a Challenge" option to Speed Dial FAB

**Shareable Cards:**
- [ ] Challenge invite card generation (1080×1920)
- [ ] Challenge completion card generation (uses existing milestone card infrastructure)
- [ ] Share to Instagram Stories / generic share sheet

**Navigation:**
- [ ] Template browser → Setup screen → Group detail
- [ ] Challenge suggestion notification tap → template browser
- [ ] Challenge countdown notification tap → group detail
- [ ] Challenge completion notification tap → shareable card screen

### 13.3 Marketing Site (getpursue.app)

- [ ] `/challenge/{invite_code}` route with challenge-specific OG tags
- [ ] Pre-generated OG images per template
- [ ] Challenge-specific landing page copy
- [ ] Generic fallback OG image for custom challenges

### 13.4 Testing

**Unit Tests:**
- [ ] Challenge status transition logic
- [ ] Resource limit checks (free vs premium, challenge vs regular)
- [ ] Template validation
- [ ] Date computation (start + duration = end)
- [ ] Mid-challenge join completion rate calculation

**Integration Tests:**
- [ ] Create challenge from template (free user)
- [ ] Create custom challenge (premium only, free user gets 403)
- [ ] Challenge limit enforcement (free: 1 created, 1 joined)
- [ ] Challenge lifecycle: upcoming → active → completed
- [ ] Challenge cancellation
- [ ] Goal creation blocked on active challenges
- [ ] Join after challenge starts (mid-challenge)
- [ ] Join after challenge ends (rejected)
- [ ] Completion notifications and shareable card generation

**Manual Tests:**
- [ ] Template browser scroll performance
- [ ] Challenge card rendering on various screen sizes
- [ ] Share to Instagram Stories end-to-end
- [ ] Challenge invite deep link flow (new user + existing user)
- [ ] OG tag preview on WhatsApp, iMessage, Facebook Messenger

---

## 14. Open Questions

1. **Should completed challenges auto-archive?** After a challenge completes, should it remain visible in the group list indefinitely, move to a "Past Challenges" section, or auto-archive after 30 days?
   - **Recommendation:** Show completed challenges in a collapsible "Completed" section at the bottom of the group list. They remain accessible for viewing progress history but don't clutter the active view.

2. **Can a challenge be extended?** If a group is on a roll, can the creator extend the end date?
   - **Recommendation:** Not for v1. Keep challenges fixed-duration to preserve the urgency mechanic. A "Run it again" button that creates a new challenge from the same template is simpler and preserves clean data.

3. **Challenge leaderboard:** Should challenges show a ranked list of members by completion rate?
   - **Recommendation:** Not for v1. Competition can be demotivating for users who fall behind. The existing member progress list already shows everyone's status without explicit ranking. Revisit based on user feedback.

4. **Can users leave a challenge mid-way?** 
   - **Recommendation:** Yes, using the existing "Leave Group" flow. Their progress data is preserved but they stop appearing in the active member list.

5. **Re-running a template:** If a user completes a "30-Day No Sugar" challenge, can they immediately start another one?
   - **Recommendation:** Yes. Each challenge instance is a separate group. Completed challenges don't count toward the active challenge limit, so users can chain challenges back-to-back.

6. **Notifications for upcoming challenges:** If a challenge is created with a start date 2 weeks away, should members receive periodic reminders before it starts?
   - **Recommendation:** Just the "1 day before start" notification (§9.2). More frequent reminders before the start would feel spammy for something the user voluntarily joined.

7. **Template versioning:** If a template's goals or description are updated, do existing challenges using that template change?
   - **Recommendation:** No. Challenge goals are copied from the template at creation time (snapshot). Template updates only affect future challenges. The `challenge_template_id` FK is for analytics/attribution, not live data binding.

---

## 15. Future Enhancements (v2)

- **Community templates:** Premium users submit custom challenges for community use (moderated)
- **Challenge discovery:** Browse public challenges by others (opt-in visibility)
- **Team challenges:** Two groups compete against each other on the same challenge
- **Progressive challenges:** Goals that escalate over time (e.g., plank duration increases weekly)
- **Challenge streaks:** "You've completed 3 challenges in a row!"
- **Recurring challenges:** Auto-restart a challenge on completion (e.g., monthly "No Spend" challenge)
- **Challenge analytics:** Premium feature showing per-challenge completion trends, drop-off points, and member engagement curves

---

**End of Specification**
