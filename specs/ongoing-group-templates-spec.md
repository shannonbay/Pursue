# Pursue — Ongoing Group Templates Spec

**Feature:** Browse Group Ideas / Ongoing Group Templates  
**Version:** 1.1  
**Date:** February 2026  
**Status:** Draft  
**Platform:** Android (Material Design 3)  
**Depends On:** group-templates-orientation-change-spec.md (infrastructure rename must ship first), existing groups, group_templates table, Speed Dial FAB

---

## 1. Overview

### 1.1 What This Feature Does

Adds a curated library of templates for **ongoing, open-ended groups** — the kind that don't have an end date. Think gardening clubs, bird-watching groups, DIY crews, book clubs, language practice circles. These are long-term accountability groups built around shared interests and regular habits, not time-boxed goals.

A user taps "Browse Group Ideas" in the Speed Dial, picks a template (e.g. "Gardening"), and lands in a lightweight setup screen: give it a name, optionally tweak it, and the group is created with sensible pre-configured goals already in place. No blank-page paralysis, no "what goals do I add?", no friction. The template does the heavy lifting.

### 1.2 Why This Matters for Retention

Challenges drive acquisition — they're shareable, urgent, and viral. But they end. A user who completes a 30-Day No Sugar challenge and has no ongoing group to return to has no reason to keep opening the app. Ongoing group templates solve the post-challenge retention cliff by giving users a low-friction path to a permanent accountability home.

The gardening group, bird-watching circle, or DIY crew doesn't complete — it grows. Members accumulate history, streaks, and heat. That's the kind of engagement that produces long-term retention, word-of-mouth, and premium conversion ("I've been in this group for 8 months and I want to unlock the full history").

### 1.3 Relationship to Challenges

Challenges and ongoing group templates are different products aimed at different moments:

| | Challenges | Ongoing Group Templates |
|---|---|---|
| Duration | Fixed (7–365 days) | Indefinite |
| Setup screen | Date picker + name | Name only |
| Goal immutability | Locked once active | Admin can add/archive goals anytime |
| Primary driver | Urgency, virality | Retention, community |
| Speed Dial entry | "Start a Challenge" | "Browse Group Ideas" |
| Template flag | `is_challenge = TRUE` | `is_challenge = FALSE` |

Both use the same `group_templates` and `group_template_goals` tables and the same `GroupTemplateCard` composable. The only differences are the filter applied when fetching and the setup screen reached after selecting a template.

---

## 2. Prerequisites

This feature depends on the infrastructure rename in `group-templates-orientation-change-spec.md` shipping first — specifically:

- `group_templates` table exists (renamed from `challenge_templates`)
- `group_templates.is_challenge BOOLEAN` column exists
- `GET /api/group-templates?is_challenge=false` works

Do not ship this feature until those migrations have run.

---

## 3. Data Model

### 3.1 No New Tables

Ongoing group templates use the existing `group_templates` and `group_template_goals` tables. The only distinction is `is_challenge = FALSE`.

### 3.2 Bundled Icon Assets

Templates reference bundled drawable assets via `icon_url` using the `res://drawable/` scheme. These assets are shipped with the app and do not require network fetching. When `icon_url` is set, it takes precedence over `icon_emoji` for display.

**Available bundled icons for templates:**

| Asset filename | Used by |
|---|---|
| `ic_icon_alarmclock.png` | 7-Day Launch Sprint |
| `ic_icon_book.png` | Book Club |
| `ic_icon_books.png` | Daily Scripture |
| `ic_icon_brain.png` | The Polymath Project |
| `ic_icon_budgeting.png` | Budget Accountability |
| `ic_icon_cash.png` | Savings Group |
| `ic_icon_coldshower.png` | Cold Shower Challenge (challenge) |
| `ic_icon_frypan.png` | Home Cooking |
| `ic_icon_handshake.png` | Out There Doing It |
| `ic_icon_inbox.png` | *(reserved — Inbox Zero challenge already exists; no ongoing equivalent planned)* |
| `ic_icon_journal.png` | Writers' Room |
| `ic_icon_laptop.png` | Daily Coding |
| `ic_icon_lightning.png` | The Weekend Warrior |
| `ic_icon_phone.png` | Staying Connected |
| `ic_icon_planking.png` | Gym Buddies |
| `ic_icon_prayer.png` | Prayer Group |
| `ic_icon_prayerhands.png` | Daily Meditation |
| `ic_icon_running.png` | Running Club |
| `ic_icon_salad.png` | Healthy Eating |
| `ic_icon_sleep.png` | Sleep Accountability |
| `ic_icon_socialmediaban.png` | Screen Time |
| `ic_icon_speaking.png` | Language Practice |
| `ic_icon_steps.png` | Daily Steps |
| `ic_icon_strength.png` | Strength Training |
| `ic_icon_sunrise.png` | Morning Routines |
| `ic_icon_walking.png` | Nature Walks |
| `ic_icon_water.png` | Daily Hydration |
| `ic_icon_bird_watching.png` | Bird Watching |
| `ic_icon_cooking.png` | Home Cooking *(preferred over ic_icon_frypan — use whichever renders better)* |
| `ic_icon_creative_spark.png` | Creative Practice |
| `ic_icon_dailydiscovery.png` | The Weekly Stretch |
| `ic_icon_gardening.png` | Gardening Club |
| `ic_icon_nature.png` | The 365-Day Marathon |
| `ic_icon_skill_building.png` | Skill Building |
| `ic_icon_workshop.png` | DIY & Home Projects |

### 3.3 Seed Data

All templates below are seeded with `is_challenge = FALSE` unless marked **[CHALLENGE]**, which indicates `is_challenge = TRUE` and requires `duration_days` to be set. Challenge entries in this seed list are new challenge templates being added alongside the ongoing group template library.

```typescript
const ONGOING_GROUP_TEMPLATES: GroupTemplateSeed[] = [

  // ─── FITNESS ────────────────────────────────────────────────────────────────

  {
    slug: 'running-club',
    title: 'Running Club',
    description: 'Log your runs, share your routes, and keep each other moving. No end date — just consistent miles.',
    icon_emoji: '🏃',
    icon_url: 'res://drawable/ic_icon_running',
    duration_days: null,
    category: 'fitness',
    difficulty: null,
    is_featured: true,
    is_challenge: false,
    sort_order: 1,
    goals: [
      { title: 'Run today', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Weekly distance', cadence: 'weekly', metric_type: 'numeric', target_value: 20, unit: 'km', sort_order: 1 }
    ]
  },
  {
    slug: 'gym-buddies',
    title: 'Gym Buddies',
    description: 'Show up, log it, hold each other accountable. The group that trains together, stays together.',
    icon_emoji: '💪',
    icon_url: 'res://drawable/ic_icon_planking',
    duration_days: null,
    category: 'fitness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 2,
    goals: [
      { title: 'Hit the gym', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
  {
    slug: 'strength-training',
    title: 'Strength Training',
    description: 'Get strong together. Log your sessions, track your lifts, and keep each other consistent.',
    icon_emoji: '🏋️',
    icon_url: 'res://drawable/ic_icon_strength',
    duration_days: null,
    category: 'fitness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 3,
    goals: [
      { title: 'Strength session done', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
  {
    slug: 'family-fitness',
    title: 'Family Fitness',
    description: 'Get the whole family moving. Track steps, workouts, or whatever active thing you\'re doing together.',
    icon_emoji: '👨‍👩‍👧',
    icon_url: null,  // ic_icon_steps reserved for Daily Steps companion template
    duration_days: null,
    category: 'fitness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 4,
    goals: [
      { title: 'Active for 30 minutes', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
  {
    slug: 'nature-walks',
    title: 'Nature Walks',
    description: 'Step outside, breathe it in, log it. A gentle accountability group for people who want to make time in nature a regular habit.',
    icon_emoji: '🌿',
    icon_url: 'res://drawable/ic_icon_walking',
    duration_days: null,
    category: 'fitness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 5,
    goals: [
      { title: 'Walk outside today', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Time outdoors', cadence: 'weekly', metric_type: 'duration', target_value: 90, unit: 'minutes', sort_order: 1 }
    ]
  },

  // ─── HEALTH & WELLNESS ──────────────────────────────────────────────────────

  {
    slug: 'sleep-accountability',
    title: 'Sleep Accountability',
    description: 'Sleep is the foundation of everything. Log your hours, hold each other to consistent bedtimes, and feel the difference.',
    icon_emoji: '😴',
    icon_url: 'res://drawable/ic_icon_sleep',
    duration_days: null,
    category: 'mindfulness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 1,
    goals: [
      { title: 'In bed by target time', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Hours slept', cadence: 'daily', metric_type: 'numeric', target_value: 7, unit: 'hours', sort_order: 1 }
    ]
  },
  {
    slug: 'healthy-eating',
    title: 'Healthy Eating',
    description: 'Not a diet — a long-term habit. Log when you ate well, share what\'s working, and keep each other on track without the all-or-nothing pressure.',
    icon_emoji: '🥗',
    icon_url: 'res://drawable/ic_icon_salad',
    duration_days: null,
    category: 'mindfulness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 2,
    goals: [
      { title: 'Ate well today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
  {
    slug: 'screen-time',
    title: 'Screen Time',
    description: 'Take back your attention. Log your phone-free time, share strategies, and hold each other to less mindless scrolling.',
    icon_emoji: '📵',
    icon_url: 'res://drawable/ic_icon_socialmediaban',
    duration_days: null,
    category: 'mindfulness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 3,
    goals: [
      { title: 'Stayed within screen time limit', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },

  // ─── MORNING & ROUTINES ─────────────────────────────────────────────────────

  {
    slug: 'morning-routines',
    title: 'Morning Routines',
    description: 'Win the morning together. Whatever your routine — journaling, exercise, cold shower, reading — log it and stay consistent.',
    icon_emoji: '🌅',
    icon_url: 'res://drawable/ic_icon_sunrise',
    duration_days: null,
    category: 'lifestyle',
    difficulty: null,
    is_featured: true,
    is_challenge: false,
    sort_order: 1,
    goals: [
      { title: 'Morning routine done', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },

  // ─── LEARNING ───────────────────────────────────────────────────────────────

  {
    slug: 'book-club',
    title: 'Book Club',
    description: 'Read together, share thoughts, hold each other to the pace. Works for any genre or reading speed.',
    icon_emoji: '📖',
    icon_url: 'res://drawable/ic_icon_book',
    duration_days: null,
    category: 'learning',
    difficulty: null,
    is_featured: true,
    is_challenge: false,
    sort_order: 1,
    goals: [
      { title: 'Read today', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Pages read', cadence: 'weekly', metric_type: 'numeric', target_value: 50, unit: 'pages', sort_order: 1 }
    ]
  },
  {
    slug: 'language-practice',
    title: 'Language Practice',
    description: 'Daily practice makes fluent. Keep each other on track whether you\'re learning Spanish, Japanese, or anything else.',
    icon_emoji: '🗣️',
    icon_url: 'res://drawable/ic_icon_speaking',
    duration_days: null,
    category: 'learning',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 2,
    goals: [
      { title: 'Practice today', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Study session', cadence: 'daily', metric_type: 'duration', target_value: 15, unit: 'minutes', sort_order: 1 }
    ]
  },
  {
    slug: 'polymath-project',
    title: 'The Polymath Project',
    description: 'A group for the genuinely curious. Each day, learn one new thing — anything — and log a brief summary of what it was and why it matters. The goal is breadth, not depth.',
    icon_emoji: '🧠',
    icon_url: 'res://drawable/ic_icon_brain',
    duration_days: null,
    category: 'learning',
    difficulty: null,
    is_featured: true,
    is_challenge: false,
    sort_order: 3,
    goals: [
      { title: 'Learned something new today', cadence: 'daily', metric_type: 'journal', log_title_prompt: 'What did you learn today?', sort_order: 0 }
    ]
  },
  {
    slug: 'skill-building',
    title: 'Skill Building',
    description: 'Pick a skill, practise consistently, log your sessions. Whether it\'s guitar, woodworking, chess, or watercolour — deliberate practice needs accountability.',
    icon_emoji: '🎯',
    icon_url: 'res://drawable/ic_icon_skill_building',
    duration_days: null,
    category: 'learning',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 4,
    goals: [
      { title: 'Practised today', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Practice session', cadence: 'daily', metric_type: 'duration', target_value: 30, unit: 'minutes', sort_order: 1 }
    ]
  },

  // ─── CREATIVITY ─────────────────────────────────────────────────────────────

  {
    slug: 'writers-room',
    title: 'Writers\' Room',
    description: 'Write consistently with people who understand the struggle. Log your word count or just that you showed up.',
    icon_emoji: '✍️',
    icon_url: 'res://drawable/ic_icon_journal',
    duration_days: null,
    category: 'creativity',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 1,
    goals: [
      { title: 'Write today', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Word count', cadence: 'daily', metric_type: 'numeric', target_value: 500, unit: 'words', sort_order: 1 }
    ]
  },
  {
    slug: 'creative-practice',
    title: 'Creative Practice',
    description: 'Make something — anything — consistently. Painting, photography, music, pottery, design. The medium doesn\'t matter; showing up does.',
    icon_emoji: '🎨',
    icon_url: 'res://drawable/ic_icon_creative_spark',
    duration_days: null,
    category: 'creativity',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 2,
    goals: [
      { title: 'Created something today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },

  // ─── LIFESTYLE & HOBBIES ────────────────────────────────────────────────────

  {
    slug: 'gardening-club',
    title: 'Gardening Club',
    description: 'Track your garden together — watering, planting, weeding, and harvesting. Perfect for green thumbs who want to stay accountable to their plot.',
    icon_emoji: '🌱',
    icon_url: 'res://drawable/ic_icon_gardening',
    duration_days: null,
    category: 'lifestyle',
    difficulty: null,
    is_featured: true,
    is_challenge: false,
    sort_order: 2,
    goals: [
      { title: 'Water the garden', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Weekend garden session', cadence: 'weekly', metric_type: 'binary', sort_order: 1 }
    ]
  },
  {
    slug: 'bird-watching',
    title: 'Bird Watching',
    description: 'Log your sightings, track your life list, and share the joy of birds with people who get it.',
    icon_emoji: '🐦',
    icon_url: 'res://drawable/ic_icon_bird_watching',
    duration_days: null,
    category: 'lifestyle',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 3,
    goals: [
      { title: 'Spot a new species', cadence: 'weekly', metric_type: 'journal', log_title_prompt: 'What did you spot today?', sort_order: 0 },
      { title: 'Time outside birdwatching', cadence: 'weekly', metric_type: 'duration', target_value: 60, unit: 'minutes', sort_order: 1 }
    ]
  },
  {
    slug: 'home-cooking',
    title: 'Home Cooking',
    description: 'Cook more, eat better, spend less. Log when you cooked at home and share what you made. No recipes required — just the habit.',
    icon_emoji: '🍳',
    icon_url: 'res://drawable/ic_icon_cooking',
    duration_days: null,
    category: 'lifestyle',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 4,
    goals: [
      { title: 'Cooked at home', cadence: 'daily', metric_type: 'journal', log_title_prompt: 'What did you cook?', sort_order: 0 }
    ]
  },

  // ─── MAINTENANCE & PRODUCTIVITY ─────────────────────────────────────────────

  {
    slug: 'weekend-warrior',
    title: 'The Weekend Warrior',
    description: 'One home maintenance task per week. Fixing a leak, painting a room, servicing the car — whatever adulting looks like this weekend. Log it and keep the house from falling apart together.',
    icon_emoji: '🔧',
    icon_url: 'res://drawable/ic_icon_lightning',
    duration_days: null,
    category: 'lifestyle',
    difficulty: null,
    is_featured: true,
    is_challenge: false,
    sort_order: 5,
    goals: [
      { title: 'Completed a home task this week', cadence: 'weekly', metric_type: 'journal', log_title_prompt: 'What did you get done this weekend?', sort_order: 0 }
    ]
  },
  {
    slug: 'diy-home',
    title: 'DIY & Home Projects',
    description: 'Keep the momentum going on home projects. Log your progress, share what you\'re working on, and get the accountability to actually finish.',
    icon_emoji: '🔨',
    icon_url: 'res://drawable/ic_icon_workshop',
    duration_days: null,
    category: 'lifestyle',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 6,
    goals: [
      { title: 'Worked on a project', cadence: 'weekly', metric_type: 'journal', log_title_prompt: 'What did you work on?', sort_order: 0 }
    ]
  },
  {
    slug: 'out-there-doing-it',
    title: 'Out There Doing It',
    description: 'No specific project, no specific goal. Just a group of people who show up and prove they did something productive each day. Log it. Anything counts.',
    icon_emoji: '✅',
    icon_url: 'res://drawable/ic_icon_handshake',
    duration_days: null,
    category: 'productivity',
    difficulty: null,
    is_featured: true,
    is_challenge: false,
    sort_order: 1,
    goals: [
      { title: 'Got something done today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },

  // ─── INTELLECTUAL & PERSONAL GROWTH ─────────────────────────────────────────

  {
    slug: 'weekly-stretch',
    title: 'The Weekly Stretch',
    description: 'Personal courage as a practice. Once a week, do one thing that makes you uncomfortable — a hard conversation, a new social situation, a creative risk — and log it. Growth lives outside your comfort zone.',
    icon_emoji: '🌱',
    icon_url: 'res://drawable/ic_icon_dailydiscovery',
    duration_days: null,
    category: 'lifestyle',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 7,
    goals: [
      { title: 'Did something uncomfortable this week', cadence: 'weekly', metric_type: 'journal', log_title_prompt: 'What did you do that pushed your comfort zone?', sort_order: 0 }
    ]
  },

  // ─── SPIRITUAL & COMMUNITY ───────────────────────────────────────────────────

  {
    slug: 'prayer-group',
    title: 'Prayer Group',
    description: 'Pray together consistently. Log your daily quiet time and keep each other anchored.',
    icon_emoji: '🙏',
    icon_url: 'res://drawable/ic_icon_prayer',
    duration_days: null,
    category: 'mindfulness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 4,
    goals: [
      { title: 'Morning prayer / quiet time', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },

  // ─── FINANCE ────────────────────────────────────────────────────────────────

  {
    slug: 'savings-group',
    title: 'Savings Group',
    description: 'Save consistently and hold each other to it. Log your contributions and watch the habit stick.',
    icon_emoji: '💰',
    icon_url: 'res://drawable/ic_icon_cash',
    duration_days: null,
    category: 'finance',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 1,
    goals: [
      { title: 'Saved today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
  {
    slug: 'budget-accountability',
    title: 'Budget Accountability',
    description: 'Track every dollar together. Log when you\'ve reviewed your budget or tracked your spending for the day. Awareness is the first step.',
    icon_emoji: '📊',
    icon_url: 'res://drawable/ic_icon_budgeting',
    duration_days: null,
    category: 'finance',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 2,
    goals: [
      { title: 'Tracked spending today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },

  // ─── ONGOING COMPANIONS TO EXISTING CHALLENGE TEMPLATES ────────────────────
  // These habits don't end when the challenge does. Each is derived from an
  // existing challenge template but reframed as a permanent accountability group
  // for people who've proven the habit and want to sustain it indefinitely.

  {
    slug: 'daily-meditation',
    title: 'Daily Meditation',
    description: 'The 21-day challenge gets you started. This is where you stay. A quiet group for people who have made meditation a permanent part of their day.',
    icon_emoji: '🧘',
    icon_url: 'res://drawable/ic_icon_prayerhands',
    duration_days: null,
    category: 'mindfulness',
    difficulty: null,
    is_featured: true,
    is_challenge: false,
    sort_order: 5,
    goals: [
      { title: 'Meditated today', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Meditation session', cadence: 'daily', metric_type: 'duration', target_value: 10, unit: 'minutes', sort_order: 1 }
    ]
  },
  {
    slug: 'gratitude-journal',
    title: 'Gratitude Journal',
    description: 'Three things, every day. A simple practice that compounds over time. Ongoing accountability for people who\'ve moved past the challenge and into the habit.',
    icon_emoji: '🙏',
    icon_url: null,  // ic_icon_journal taken by Writers' Room — use emoji
    duration_days: null,
    category: 'mindfulness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 6,
    goals: [
      { title: 'Wrote gratitude today', cadence: 'daily', metric_type: 'journal', log_title_prompt: 'What are you grateful for today?', sort_order: 0 }
    ]
  },
  {
    slug: 'daily-steps',
    title: 'Daily Steps',
    description: '10,000 steps is the challenge. This is the lifestyle. A permanent group for people who log their steps every day and want others doing the same.',
    icon_emoji: '👟',
    icon_url: 'res://drawable/ic_icon_steps',
    duration_days: null,
    category: 'fitness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 6,
    goals: [
      { title: 'Step goal reached', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Daily steps', cadence: 'daily', metric_type: 'numeric', target_value: 10000, unit: 'steps', sort_order: 1 }
    ]
  },
  {
    slug: 'alcohol-free',
    title: 'Alcohol-Free',
    description: 'Whether you\'re sober curious, in recovery, or just done with drinking — this is a long-term accountability group, not a 30-day reset. Quiet, supportive, no pressure.',
    icon_emoji: '🚫',
    icon_url: null,  // no suitable bundled icon — emoji renders clearly
    duration_days: null,
    category: 'mindfulness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 7,
    goals: [
      { title: 'Alcohol-free today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
  {
    slug: 'daily-hydration',
    title: 'Daily Hydration',
    description: 'Eight glasses sounds simple. Doing it every day without accountability is harder than it sounds. A low-key group to keep each other drinking enough water.',
    icon_emoji: '💧',
    icon_url: 'res://drawable/ic_icon_water',
    duration_days: null,
    category: 'mindfulness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 9,
    goals: [
      { title: 'Drank enough water today', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
      { title: 'Glasses of water', cadence: 'daily', metric_type: 'numeric', target_value: 8, unit: 'glasses', sort_order: 1 }
    ]
  },
  {
    slug: 'daily-coding',
    title: 'Daily Coding',
    description: '30 Days of Code gets you into the habit. This is where you keep it going. A group for developers, learners, and builders who want to write code every day — indefinitely.',
    icon_emoji: '💻',
    icon_url: 'res://drawable/ic_icon_laptop',
    duration_days: null,
    category: 'learning',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 5,
    goals: [
      { title: 'Wrote code today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
  {
    slug: 'daily-scripture',
    title: 'Daily Scripture',
    description: 'Bible, Quran, Torah, or any sacred text — a permanent home for people who want to read scripture consistently and keep each other accountable to the practice.',
    icon_emoji: '📖',
    icon_url: 'res://drawable/ic_icon_books',
    duration_days: null,
    category: 'mindfulness',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 8,
    goals: [
      { title: 'Read scripture today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
  {
    slug: 'staying-connected',
    title: 'Staying Connected',
    description: 'Relationships need maintenance. A group for people who want to make regular contact with friends and family a non-negotiable habit — not just a 30-day challenge.',
    icon_emoji: '📞',
    icon_url: 'res://drawable/ic_icon_phone',
    duration_days: null,
    category: 'social',
    difficulty: null,
    is_featured: false,
    is_challenge: false,
    sort_order: 1,
    goals: [
      { title: 'Reached out to someone today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },

  // ─── NEW CHALLENGE TEMPLATES (is_challenge = TRUE) ──────────────────────────
  // These are time-boxed and appear in the Start a Challenge browser, not Browse
  // Group Ideas. Included here to keep all template seed data in one file.

  {
    slug: '7-day-launch-sprint',
    title: '7-Day Launch Sprint',
    description: 'Seven days of consistent daily logging to kick off a new habit. High intensity, short commitment. Perfect for anyone just getting started and wanting to prove they can show up every day.',
    icon_emoji: '⚡',
    icon_url: 'res://drawable/ic_icon_alarmclock',
    duration_days: 7,
    category: 'productivity',
    difficulty: 'hard',
    is_featured: true,
    is_challenge: true,
    sort_order: 1,
    goals: [
      { title: 'Showed up today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
  {
    slug: '365-day-marathon',
    title: 'The 365-Day Marathon',
    description: 'A full year of one non-negotiable habit. Not a sprint — a lifestyle change. For people who are done with 30-day resets and want to build something permanent.',
    icon_emoji: '🗓️',
    icon_url: 'res://drawable/ic_icon_nature',
    duration_days: 365,
    category: 'lifestyle',
    difficulty: 'hard',
    is_featured: false,
    is_challenge: true,
    sort_order: 100, // listed last in the challenge browser
    goals: [
      { title: 'Did the thing today', cadence: 'daily', metric_type: 'binary', sort_order: 0 }
    ]
  },
];
```

### 3.4 Schema Note: `duration_days` and `difficulty`

For ongoing group templates (`is_challenge = FALSE`), `duration_days` is `NULL` (no end date) and `difficulty` is `NULL` (not applicable). The existing columns are already nullable, so no schema changes are needed beyond the `is_challenge` column added in the infrastructure change spec.

The two challenge templates in the seed list above (`is_challenge = TRUE`) follow the same schema as all existing challenge templates.

---

## 4. API

### 4.1 Fetch Ongoing Group Templates

No new endpoint — uses the existing `GET /api/group-templates` with the `is_challenge` filter:

```
GET /api/group-templates?is_challenge=false
```

Response shape is identical to the challenge template response. `duration_days` and `difficulty` will be `null` for ongoing group templates — clients must handle this gracefully (don't show a duration badge, don't show a difficulty badge).

### 4.2 Create Group from Template

No new endpoint. Uses `POST /api/groups` — the same endpoint used for blank group creation and for challenge creation. The request body for an ongoing group template:

```json
{
  "template_id": "template-uuid",
  "name": "Shannon's Gardening Club"
}
```

No `start_date` or `end_date` — the server infers this is an ongoing group because `group_templates.is_challenge = FALSE` for the referenced template, and does not set `is_challenge`, `challenge_start_date`, `challenge_end_date`, or `challenge_status` on the resulting group.

**Server logic additions** to `POST /api/groups`:

1. If `template_id` is provided, fetch the template
2. If `template.is_challenge = TRUE`: existing challenge creation path (requires `start_date`, sets challenge columns)
3. If `template.is_challenge = FALSE`: ongoing group creation path — create a normal group, copy goals from template, set `template_id` on the group, no challenge columns set
4. Return the new group

---

## 5. Android UI

### 5.1 Speed Dial Update

Add a fourth option to the existing Speed Dial FAB:

```
Speed Dial Options:
  1. Create Group       → existing blank group creation
  2. Start a Challenge  → ChallengeTemplatesScreen (unchanged)
  3. Browse Group Ideas → GroupIdeasScreen (new)
  4. Join with Code     → existing
```

**Label:** "Browse Group Ideas" — warm and exploratory rather than functional. Alternatives considered: "Explore Group Types", "Group Templates" (too technical). "Browse Group Ideas" wins because it matches the user's mindset ("I want ideas for what kind of group to start").

**Icon:** A lightbulb or sparkle icon (`Icons.Outlined.Lightbulb` or similar) to signal discovery/inspiration rather than creation.

**Ordering rationale:** "Start a Challenge" stays at position 2 (it's the most frequently used path for new users). "Browse Group Ideas" is at position 3 — new and less familiar, but still prominent. "Join with Code" moves to position 4.

### 5.2 GroupIdeasScreen

A new full-screen composable, structured identically to `ChallengeTemplatesScreen` but filtered to `is_challenge = false` templates and with appropriate copy.

**Header:**
```
← Browse Group Ideas
```

**Intro section** (below header, above template rows):
```
Find your people

Pick a group type and we'll set up goals for you.
You can customise everything once your group is created.
```

**Template rows:** Horizontal `LazyRow` per category, identical layout to challenge template browser. Each card shows icon, title, category, and a "Start" button.

**Differences from ChallengeTemplatesScreen:**
- No duration badge on cards (ongoing groups have no duration)
- No difficulty badge on cards (not applicable)
- No "Create Custom Challenge (Premium)" section — custom group creation is already available via "Create Group" in the Speed Dial
- Card CTA label: "Start" (same as challenge templates — consistent)
- Featured section label: "Popular" (rather than challenge browser's "✨ Featured")

**Template card for ongoing groups:**
```
┌──────────────────┐
│  [icon]          │
│  Gardening Club  │
│  Lifestyle       │
│  [Start]         │
└──────────────────┘
```

Compare to challenge template card:
```
┌──────────────────┐
│  [icon]          │
│  30-Day No Sugar │
│  30 days · Hard  │
│  [Start]         │
└──────────────────┘
```

### 5.3 GroupIdeasViewModel

```kotlin
class GroupIdeasViewModel(
    private val templateRepository: GroupTemplateRepository
) : ViewModel() {

    private val _templates = MutableStateFlow<List<GroupTemplate>>(emptyList())
    val templates: StateFlow<List<GroupTemplate>> = _templates

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    fun loadTemplates() {
        viewModelScope.launch {
            _isLoading.value = true
            _templates.value = templateRepository.fetchTemplates(isChallenge = false)
            _isLoading.value = false
        }
    }
}
```

The `fetchTemplates(isChallenge: Boolean)` method on the existing repository handles both challenge and ongoing template fetches — add the `isChallenge` parameter to the existing repository method rather than creating a separate one.

### 5.4 GroupSetupScreen (Ongoing)

After tapping "Start" on an ongoing group template, navigate to a simplified group setup screen. This is distinct from `ChallengeSetupScreen` (which has a date picker):

```
┌─────────────────────────────────────┐
│ ← Set Up Your Group                 │
├─────────────────────────────────────┤
│                                     │
│  [icon] Gardening Club              │
│  Track your garden together —       │
│  watering, planting, and sharing    │
│  the joy of growing things.         │
│                                     │
│  ┌───────────────────────────────┐  │
│  │ Group Name                   │  │
│  │ Shannon's Gardening Club     │  │
│  └───────────────────────────────┘  │
│                                     │
│  Goals included:                    │
│  ✅ Water the garden (daily)        │
│  ✅ Weekend garden session (weekly) │
│                                     │
│  ┌───────────────────────────────┐  │
│  │       Create Group            │  │
│  └───────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

**Fields:**
- **Group Name:** Pre-filled from template title. Editable. Max 100 chars.
- **Goals:** Read-only preview of goals from the template. Admin can add/edit goals after creation.

No date picker — ongoing groups have no start or end date.

**On "Create Group":**
1. Call `POST /api/groups` with `{ template_id, name }`
2. On success → navigate to Group Detail → mark setup complete
3. On error → show inline error

**Reuse:** This screen reuses the same `GroupTemplateCard` composable for the template preview header and the same goal list preview pattern as `ChallengeSetupScreen`. Extract the shared goal preview list into a `TemplateGoalPreviewList` composable if it hasn't been already.

### 5.5 Icon Rendering

Template icons are rendered from `icon_url` when present, falling back to `icon_emoji` if `icon_url` is null or fails to load.

For `res://drawable/` URLs, resolve the resource name and load via `painterResource()`:

```kotlin
@Composable
fun TemplateIcon(
    iconUrl: String?,
    iconEmoji: String,
    modifier: Modifier = Modifier
) {
    val drawableRes = iconUrl
        ?.takeIf { it.startsWith("res://drawable/") }
        ?.removePrefix("res://drawable/")
        ?.let { name ->
            LocalContext.current.resources.getIdentifier(name, "drawable", LocalContext.current.packageName)
                .takeIf { it != 0 }
        }

    if (drawableRes != null) {
        Image(
            painter = painterResource(id = drawableRes),
            contentDescription = null,
            modifier = modifier
        )
    } else {
        Text(
            text = iconEmoji,
            style = MaterialTheme.typography.headlineMedium,
            modifier = modifier
        )
    }
}
```

---

## 6. Freemium

Ongoing group templates are free. The free tier already allows creating 1 group — creating a group from a template counts against the same limit. No special gating needed.

| Feature | Free | Premium |
|---|---|---|
| Browse ongoing group templates | ✅ | ✅ |
| Create group from template | ✅ (counts as their 1 free group) | ✅ |

**Premium upsell opportunity:** If a free user tries to create a second group from template and has hit their group limit, show the standard premium upgrade prompt. This is handled by the existing resource limit check — no special logic needed for templates.

---

## 7. Analytics Events

| Event | When | Properties |
|---|---|---|
| `group_ideas_opened` | User opens GroupIdeasScreen | — |
| `group_idea_viewed` | User taps a template card (detail) | `template_slug`, `category` |
| `group_idea_selected` | User taps "Start" on a template | `template_slug`, `category` |
| `group_from_template_created` | Group successfully created from template | `template_slug`, `category` |

---

## 8. Implementation Checklist

### Backend
- [ ] Seed `group_templates` with all ongoing templates from §3.3 (`is_challenge = FALSE`)
- [ ] Seed the two new challenge templates from §3.3 (`is_challenge = TRUE`)
- [ ] Verify `GET /api/group-templates?is_challenge=false` returns only ongoing templates
- [ ] Verify `GET /api/group-templates?is_challenge=true` continues to return only challenge templates
- [ ] Update `POST /api/groups` to handle `template.is_challenge = FALSE` path (§4.2)
- [ ] Add `isChallenge` parameter to `fetchTemplates()` in `GroupTemplateRepository`

### Android
- [ ] Add "Browse Group Ideas" option to Speed Dial FAB (§5.1)
- [ ] Create `GroupIdeasScreen` composable (§5.2)
- [ ] Create `GroupIdeasViewModel` (§5.3)
- [ ] Create `GroupSetupScreen` (ongoing variant, no date picker) (§5.4)
- [ ] Implement `TemplateIcon` composable with `res://drawable/` resolution (§5.5)
- [ ] Extract `TemplateGoalPreviewList` composable if not already shared
- [ ] Add `GroupIdeasScreen` and `GroupSetupScreen` to nav graph
- [ ] Wire "Start" button on template cards → `GroupSetupScreen`
- [ ] Wire "Create Group" → `POST /api/groups` → Group Detail
- [ ] Handle `duration_days = null` and `difficulty = null` gracefully on `GroupTemplateCard`
- [ ] Ensure all bundled icon assets (§3.2) are present in `res/drawable/`
- [ ] Fire analytics events (§7)

### Testing
- [ ] `GET /api/group-templates?is_challenge=false` returns only ongoing templates
- [ ] `GET /api/group-templates?is_challenge=true` still returns only challenge templates
- [ ] `POST /api/groups` with ongoing template `template_id` creates a normal (non-challenge) group
- [ ] Created group has `is_challenge = FALSE`, no challenge date columns set
- [ ] Created group has goals copied from template
- [ ] `POST /api/groups` with challenge template `template_id` continues to require `start_date`
- [ ] Free user hitting group limit during template setup sees premium prompt
- [ ] `GroupTemplateCard` renders correctly with null `duration_days` and `difficulty`
- [ ] `TemplateIcon` falls back to emoji when drawable not found
- [ ] Speed Dial shows all four options

---

## 9. Future Enhancements

- **Suggested templates post-challenge:** When a user's challenge completes, if they don't have an ongoing group in a related category, suggest a relevant ongoing group template. ("You finished the 30-Day No Sugar challenge — want to keep going with an ongoing wellness group?")
- **Community-submitted templates:** Premium users submit templates for moderation; popular ones get added to the library.
- **Template previews on Discover:** Public groups that were created from a template could show the template name/icon as a badge, helping discovery browsers understand the group's purpose at a glance.
- **Template ratings:** After a month in a template-created group, prompt members to rate the template's goal quality (thumbs up/down). Use to surface and retire templates.
