# Body-Doubling Group Template — Pursue

**Type:** Addendum to `ongoing-group-templates-spec.md` and `body-teaming-spec.md`  
**Status:** Draft v1.0

---

## 1. Overview

The body-doubling feature (see `body-teaming-spec.md`) introduces focus sessions to Pursue. A dedicated group template makes it easy for users to create a group whose *primary purpose* is body-doubling — sitting together virtually to get work done — rather than treating it as a side feature of a fitness or study group.

The template appears in the **Browse Group Ideas** screen alongside other ongoing group templates. It should be featured (`is_featured = true`) given it's a flagship new capability.

**UX note:** The term "Body Doubling" is used throughout the UI — it's widely understood, especially among the ADHD/productivity audience this feature will resonate with most. "Body-teaming" remains the internal/technical codename.

---

## 2. Template Variants

Two templates cover the most natural use cases. A generic catch-all first, then a more specific ADHD-positioned variant.

### 2.1 Body Doubling (Generic)

The broad-appeal entry point. Suitable for any group that wants to co-work virtually on a regular basis, regardless of what they're working on.

```typescript
{
  slug: 'body-doubling',
  title: 'Body Doubling',
  description: 'Show up, work alongside each other, get things done. Body doubling is the simple practice of working in the presence of others — it makes the hard stuff easier. Schedule focus sessions, jump in when others are live, and log your sessions together.',
  icon_emoji: '🎯',
  icon_url: 'res://drawable/ic_icon_focus',  // new asset — see §4
  duration_days: null,
  category: 'productivity',
  difficulty: null,
  is_featured: true,
  is_challenge: false,
  sort_order: 1,  // first in the productivity category
  goals: [
    { title: 'Focus session done', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
    { title: 'Focus time', cadence: 'weekly', metric_type: 'duration', target_value: 120, unit: 'minutes', sort_order: 1 }
  ]
}
```

**Goal rationale:**
- The binary daily goal ("Focus session done") is the primary check-in — did you show up today?
- The weekly duration goal (120 min / week = ~3 × 40-min sessions) gives numeric accountability without being prescriptive about how long each session runs. Users can adjust the target when creating the group.

### 2.2 Deep Work Club

A more opinionated variant for users who are intentional about protecting focused time — students, writers, developers, researchers. Framing borrowed from Cal Newport's deep work concept, which has broad recognition in this audience.

```typescript
{
  slug: 'deep-work-club',
  title: 'Deep Work Club',
  description: 'Distraction-free time is rare and valuable. This group exists to protect it. Show up for scheduled sessions, mute the world, and do your most important work alongside people who are doing the same.',
  icon_emoji: '🧠',
  icon_url: 'res://drawable/ic_icon_brain',  // already exists (used by Polymath Project)
  duration_days: null,
  category: 'productivity',
  difficulty: null,
  is_featured: false,
  is_challenge: false,
  sort_order: 2,
  goals: [
    { title: 'Deep work session done', cadence: 'daily', metric_type: 'binary', sort_order: 0 },
    { title: 'What did you work on?', cadence: 'daily', metric_type: 'journal', sort_order: 1 },
    { title: 'Deep work time', cadence: 'weekly', metric_type: 'duration', target_value: 180, unit: 'minutes', sort_order: 2 }
  ]
}
```

**Goal rationale:** The three goals tell a complete daily story — you logged that you showed up (binary), said what you worked on (journal), and the time accumulates across the week (duration). The journal goal ("What did you work on?") is the conversation starter that makes the activity feed substantive rather than a row of green checkmarks, and it ties naturally into the chit-chat window at the end of a session. Higher weekly target (180 min = 3 × 60-min blocks) reflects the more committed framing. All targets are adjustable at group creation.

---

## 3. Category Placement

Both templates sit in the `productivity` category. The existing productivity templates in that category are not listed in the current seed data — confirm `sort_order` doesn't conflict when seeding. If the category is new, add it to the category filter list in the Browse Group Ideas UI.

---

## 4. New Icon Asset

`ic_icon_focus` needs to be created for the generic Body Doubling template. Suggested visual: a simple target/bullseye or a stylised timer circle — something that reads immediately as "focused effort" without being too abstract.

The `ic_icon_brain` asset already exists (Polymath Project uses it) and is reused for Deep Work Club — no new asset needed there.

**Asset spec for `ic_icon_focus`:**
- Format: PNG, vector-exportable
- Sizes: `mdpi` / `hdpi` / `xhdpi` / `xxhdpi` / `xxxhdpi` (standard Android drawable set)
- Style: match existing icon set — outlined, single colour, rounded corners
- Suggested concept: concentric circles (target) or a clean hourglass

---

## 5. Template Card Copy (Browse Group Ideas UI)

The template card in the browse screen shows the icon, title, and a short description excerpt. The full description is shown on the template detail sheet.

**Body Doubling card:**
```
🎯  Body Doubling
Show up, work alongside each other, get things done.
```

**Deep Work Club card:**
```
🧠  Deep Work Club
Distraction-free time is rare and valuable. This group protects it.
```

---

## 6. Template Detail Sheet

When a user taps a template card, a bottom sheet expands with the full description, goals preview, and a "Create Group" CTA. No changes to the sheet component are needed — just ensure the body-doubling session feature is surfaced contextually after group creation (see §7).

---

## 7. Post-Creation Onboarding Nudge

After creating a group from either body-doubling template, show a one-time contextual card inside the new group detail:

```
┌─────────────────────────────────────────┐
│ 🎯  How Body Doubling Works in Pursue   │
│                                         │
│  Tap "Start a Session" any time you     │
│  want to work alongside your group.     │
│  Everyone gets a notification and can   │
│  join. No agenda needed — just show up. │
│                                         │
│  You can also schedule sessions in      │
│  advance so your crew can plan ahead.   │
│                                         │
│                              [ Got it ] │
└─────────────────────────────────────────┘
```

This card is dismissed permanently on "Got it" (stored in local preferences, keyed to the group ID). It only appears for groups created from a body-doubling template (`template_id` matches either slug).

---

## 8. Implementation Checklist

- [ ] Add `body-doubling` template seed (§2.1) to template seed file
- [ ] Add `deep-work-club` template seed (§2.2) to template seed file
- [ ] Create `ic_icon_focus` drawable asset in all density buckets (§4)
- [ ] Confirm `productivity` category exists in Browse Group Ideas category filter
- [ ] Verify `sort_order` doesn't conflict with existing productivity templates
- [ ] Implement post-creation onboarding nudge card (§7), triggered by `template_id` check
- [ ] Store nudge dismissal in SharedPreferences keyed to group ID
