# Pursue — Content Moderation Policy & Strategy

**Version:** 1.1  
**Status:** Draft  
**Scope:** All user-generated content across the Pursue platform

---

## 1. Overview

Pursue's social model depends on trust. Members share real progress — sometimes vulnerable, imperfect, personal — with people they know. Moderation exists to protect that environment, not to police it. The goal is a platform where people feel safe being honest about their progress without worrying that the space will be corrupted by harassment, inappropriate imagery, or spam.

This spec covers policy (what's allowed and what isn't) and strategy (how violations are detected, reported, reviewed, and actioned).

---

## 2. Content Surface Inventory

The following user-generated content surfaces require moderation consideration:

| Surface | Type | Character Limit | Visibility |
|---|---|---|---|
| Username | Text | ~30 chars | All users who share a group |
| Group name | Text | ~60 chars | Group members + public directory |
| Goal name | Text | ~80 chars | Group members |
| Progress log title (`log_title`) | Text | 120 chars | Group members |
| Progress log note | Text | 500 chars | Group members |
| Photo log | Image | — | Group members for 7 days, then deleted |

Each surface has different risk profiles and appropriate controls.

---

## 3. Content Policy

### 3.1 Prohibited Content (Zero Tolerance)

The following is prohibited across all content surfaces regardless of group type (public or private):

- **Hate speech:** Content that attacks or dehumanises individuals or groups based on race, ethnicity, religion, gender, sexual orientation, disability, or national origin.
- **Harassment and threats:** Direct or implied threats, repeated targeted hostility toward another member, or content designed to intimidate.
- **Illegal content:** Content that is unlawful in the user's jurisdiction or Pursue's operating jurisdictions (NZ, AU, and subsequently US, UK, CA).
- **Child safety violations:** Any content that sexualises, exploits, or endangers minors. This triggers immediate account suspension and mandatory reporting to relevant authorities.
- **Non-consensual intimate imagery:** Photos or descriptions that share or reference intimate content without the subject's consent.
- **Spam and inauthenticity:** Automated posting, coordinated inauthentic behaviour, or content designed to manipulate group metrics.

### 3.2 Restricted Content

The following requires context to assess and may be actioned depending on severity:

- **Profanity:** Light profanity in journal entries is tolerated in private groups where members clearly know each other. Profanity directed at another member, or used in usernames and group names, is not.
- **Graphic content:** Images depicting injury, illness, or medical procedures posted as workout/progress photos are borderline. Pursue errs toward allowing genuinely goal-relevant content while blocking gratuitous imagery.
- **Explicit fitness imagery:** Workout progress photos are a legitimate use case. The line is between "progress documentation" and content that is sexualised or primarily intended to be provocative.

### 3.3 Permitted Content

- Personal goal progress, including imperfect or struggling check-ins
- Authentic journal entries about daily experiences relevant to goals
- Workout and physical progress photos (non-sexualised)
- References to personal struggles, mental health, or setbacks in the context of accountability
- Light humour, banter, and informal language between group members

### 3.4 Public vs. Private Groups

Automated filtering applies uniformly across public and private groups. The distinction matters for discovery (public groups can appear in the directory and shareable content) but not for safety thresholds — harassment and illegal content are equally prohibited regardless of group privacy settings.

The practical difference is that public groups face higher reputational risk from borderline content and receive closer automated scrutiny on content surfaced externally (e.g. group names and descriptions in the public directory).

---

## 4. Moderation Strategy

A layered "defence in depth" approach is used, balancing automation (low overhead) with human review (accuracy) and community reporting (scale).

### Layer 1 — Automated Pre-Filtering (Gatekeeper)

Bad content is rejected before it reaches the database. This is synchronous: the API returns an error to the client and the content is never stored.

#### 4.1.1 Usernames and Group Names

Two separate checks run synchronously at creation time, before any DB write. Reject with HTTP 400 and the message: *"This name isn't available. Please choose something different."*

**Check 1 — Reserved/impersonation name list**

A hardcoded array maintained in the codebase (`src/moderation/reserved-names.ts`). Checked as an exact match against the lowercased, trimmed input.

```typescript
// src/moderation/reserved-names.ts
export const RESERVED_NAMES: string[] = [
  // Pursue brand
  'pursue', 'getpursue', 'pursueapp', 'pursue_app', 'pursueofficial',

  // Platform roles / system
  'admin', 'administrator', 'moderator', 'mod', 'staff', 'team',
  'support', 'help', 'helpdesk', 'contact',
  'system', 'bot', 'robot', 'automated',

  // Technical / database hazards
  'null', 'undefined', 'none', 'true', 'false', 'root', 'anonymous',
  'guest', 'test', 'user', 'users', 'account', 'accounts',
  'default', 'example', 'sample', 'demo',

  // Trust & safety exploitation
  'official', 'verified', 'trusted', 'legitimate', 'real',
  'security', 'safety', 'privacy',

  // Common impersonation targets
  'google', 'apple', 'facebook', 'meta', 'instagram', 'twitter',
  'tiktok', 'snapchat', 'anthropic', 'openai',
];

export function isReservedName(name: string): boolean {
  return RESERVED_NAMES.includes(name.toLowerCase().trim());
}
```

Group names are also checked against this list but with looser matching — a group called "The Admin Circle" is fine; one called "Pursue Official Team" is not. For group names, check whether the normalised string *contains* a reserved brand term (`pursue`, `anthropic`) rather than exact-matching the full list.

**Check 2 — Profanity filter**

Use the `obscenity` npm package, which handles Unicode lookalikes, leet-speak variants, and embedded profanity (e.g. a slur buried inside a longer word) — cases that simple keyword lists miss.

```bash
npm install obscenity
```

```typescript
// src/moderation/profanity.ts
import {
  RegExpMatcher,
  englishDataset,
  englishRecommendedTransformers,
} from 'obscenity';

const matcher = new RegExpMatcher({
  ...englishDataset.build(),
  ...englishRecommendedTransformers,
});

export function containsProfanity(text: string): boolean {
  return matcher.hasMatch(text);
}
```

**Combined validation function:**

```typescript
// src/moderation/validate-name.ts
import { isReservedName } from './reserved-names';
import { containsProfanity } from './profanity';

export type NameValidationResult =
  | { valid: true }
  | { valid: false; reason: 'reserved' | 'profanity' };

export function validateDisplayName(name: string): NameValidationResult {
  const normalised = name.toLowerCase().trim();

  if (isReservedName(normalised)) {
    return { valid: false, reason: 'reserved' };
  }
  if (containsProfanity(normalised)) {
    return { valid: false, reason: 'profanity' };
  }
  return { valid: true };
}
```

Both checks apply to **usernames** (exact match on reserved list) and **group names** (brand-term containment check + profanity). Goal names run the profanity check only — reserved name matching is unnecessary for goal names.

**Note on language coverage:** `obscenity`'s built-in dataset is English only, which is appropriate for the NZ/AU launch. If Pursue expands to non-English markets, supplement with additional language datasets or consider migrating to `@2toad/profanity` which has multi-language support.

**Maintaining the reserved list:** Add entries as needed based on real-world reports. Keep the list intentionally short — over-blocking creates user frustration. When in doubt, leave a term off the reserved list and let the profanity filter handle it.

#### 4.1.2 Photo Logs

**Google Cloud Vision SafeSearch API** is called inside the photo upload Cloud Run function before the image is committed to Cloud Storage and the URL written to Supabase.

Rejection thresholds:

| Category | Threshold |
|---|---|
| Adult | `LIKELY` or `VERY_LIKELY` |
| Violence | `LIKELY` or `VERY_LIKELY` |
| Racy | `VERY_LIKELY` only (fitness photos can trigger `LIKELY`) |

If Cloud Vision rejects the image, the upload endpoint returns HTTP 422 with the message: *"This photo couldn't be uploaded. If you think this is a mistake, you can report it."* The error message surfaces a link to the dispute form (see §6).

**Do not log rejected image content.** Record only the rejection event (timestamp, user ID, SafeSearch scores) for audit purposes.

#### 4.1.3 Journal Log Titles and Notes

**Google Perspective API** is used for text moderation of `log_title` and `note` fields on progress entries. Perspective provides context-aware toxicity scoring rather than keyword matching, avoiding false positives on innocent text.

Score thresholds (call `POST https://commentanalyzer.googleapis.com/v1alpha1/comments:analyze`):

| Attribute | Reject Threshold |
|---|---|
| `TOXICITY` | ≥ 0.85 |
| `SEVERE_TOXICITY` | ≥ 0.70 |
| `IDENTITY_ATTACK` | ≥ 0.80 |
| `THREAT` | ≥ 0.80 |

Thresholds are intentionally conservative (high) to minimise false positives. Pursue's journal entries are about personal progress and the content is typically mundane; the Perspective API should rarely trigger on legitimate content.

Rejection response: HTTP 422 with message: *"We couldn't post this entry. Please review your text and try again."*

### Layer 2 — Community Reporting

A **Report** option is available on all progress log entries (title, note, and photo) and on group names visible to members. Reporting is discreet — it does not notify the reported user or other members.

#### Report Thresholds for Auto-Hide

When a post receives reports, it is automatically hidden from the group feed pending human review:

- Groups with ≤ 10 members: Hide after **2 reports**
- Groups with 11–50 members: Hide after **3 reports**
- Groups with 51+ members: Hide after **5 reports** or **10% of members**, whichever is lower

Auto-hidden content remains in the database and is visible to the reporting user(s) and admins but not to other members. This prevents viral harm while avoiding permanent removal before human review.

Report categories presented to the user:
- Inappropriate or offensive content
- Harassment or bullying
- Spam or fake activity
- Something else

### Layer 3 — Human Review (Admin Dashboard)

All auto-hidden content and flagged content is routed to a moderation queue visible in Supabase Studio via a dedicated view.

#### Database Schema Additions

```sql
-- On progress_entries
ALTER TABLE progress_entries
  ADD COLUMN moderation_status TEXT DEFAULT 'ok'
    CHECK (moderation_status IN ('ok', 'flagged', 'hidden', 'removed', 'disputed')),
  ADD COLUMN moderation_note TEXT,
  ADD COLUMN moderation_updated_at TIMESTAMPTZ;

-- Moderation reports table
CREATE TABLE content_reports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_user_id UUID NOT NULL REFERENCES users(id),
  content_type TEXT NOT NULL CHECK (content_type IN ('progress_entry', 'group', 'username')),
  content_id UUID NOT NULL,
  reason TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now(),
  reviewed BOOLEAN DEFAULT false,
  reviewed_at TIMESTAMPTZ,
  outcome TEXT
);

-- Supabase view for moderation queue
CREATE VIEW moderation_queue AS
SELECT
  pe.id,
  pe.moderation_status,
  pe.log_title,
  pe.note,
  pe.photo_url,
  pe.created_at,
  u.username,
  g.name AS group_name,
  COUNT(cr.id) AS report_count,
  MAX(cr.created_at) AS latest_report_at
FROM progress_entries pe
JOIN users u ON pe.user_id = u.id
JOIN group_members gm ON pe.group_member_id = gm.id
JOIN groups g ON gm.group_id = g.id
LEFT JOIN content_reports cr ON cr.content_id = pe.id AND cr.content_type = 'progress_entry'
WHERE pe.moderation_status IN ('flagged', 'hidden')
GROUP BY pe.id, u.username, g.name
ORDER BY latest_report_at DESC;
```

#### Review Outcomes

| Outcome | Action |
|---|---|
| **Clear** | Set `moderation_status = 'ok'`, content restored to feed |
| **Remove** | Set `moderation_status = 'removed'`, content permanently hidden from all users |
| **Warn user** | Send in-app notification to the posting user explaining the removal |
| **Suspend account** | Disable posting privileges for a defined period (start with 7 days) |
| **Terminate account** | For severe or repeat violations; requires manual action in Supabase |

---

## 5. Enforcement Principles

### 5.1 Proportionality

First-time borderline violations (e.g. a mildly inappropriate joke in a journal entry) receive a warning and content removal. Escalating responses are reserved for repeat offenders, severe violations, or coordinated bad behaviour.

### 5.2 No Metric Manipulation

Moderation outcomes do not affect a user's progress data, streaks, or group metrics. Accountability data belongs to the user's genuine effort history. Suspensions restrict future posting but do not retroactively alter past records.

### 5.3 Consistency Across Group Types

Automated filters and human review standards are applied consistently regardless of whether a group is public or private. The nature of the harm (harassment, illegal content) does not change based on the group's visibility setting.

### 5.4 Account-Level Escalation

Repeat violations escalate at the account level, not the content level:

1. First violation: Content removed, warning issued
2. Second violation within 90 days: Content removed, 7-day posting suspension
3. Third violation within 180 days: Content removed, 30-day posting suspension, review for termination
4. Severe violations (hate speech, CSAM, threats): Immediate account suspension pending review

---

## 6. User Appeals

Users whose content is removed by automated systems (Cloud Vision or Perspective API) or by admin review may dispute the decision.

**Dispute flow:**
1. User sees removal notification in-app (Notification Inbox) with a "This was a mistake" button
2. Tapping opens a simple form: brief description of why the content should be allowed (max 280 chars)
3. Dispute is logged to a `content_disputes` table and added to the moderation queue
4. At launch, disputes are reviewed manually within 5 business days
5. User receives an in-app notification with the outcome

**No appeals for severe violations** (CSAM, explicit threats). These are final.

```sql
CREATE TABLE content_disputes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id),
  content_type TEXT NOT NULL,
  content_id UUID NOT NULL,
  user_explanation TEXT,
  created_at TIMESTAMPTZ DEFAULT now(),
  resolved BOOLEAN DEFAULT false,
  resolved_at TIMESTAMPTZ,
  outcome TEXT
);
```

---

## 7. User Communication

### 7.1 Community Standards (First Public Group Join)

When a user joins their first public group, a one-time bottom sheet is shown:

> **Pursue is a place to grow together**
>
> Keep it real, keep it kind. We automatically filter content to keep groups safe, and members can report anything that doesn't belong. Severe violations result in account suspension.
>
> [Got it]

This leverages the existing covenant concept for private groups but applies as a universal baseline for public groups.

### 7.2 Removal Notifications

When content is removed, the posting user receives a notification in their Notification Inbox:

> **A post was removed**
> One of your entries in [Group Name] was removed for violating Pursue's Community Standards. [Dispute this decision]

The notification does not describe the specific content or the specific rule violated in detail — this avoids coaching bad actors on how to evade filters.

### 7.3 Warning Notifications

For first-time borderline violations where the user receives a warning without removal:

> **A heads-up about a recent post**
> Content you posted in [Group Name] was flagged as potentially inappropriate. It hasn't been removed, but please keep Pursue's Community Standards in mind.

---

## 8. Implementation Phases

### Phase 1 — Pre-Launch (Required)

- [ ] Reserved name list (`src/moderation/reserved-names.ts`) checked in `POST /api/users` and `POST /api/groups`
- [ ] `obscenity` profanity filter checked for usernames, group names, and goal names
- [ ] Cloud Vision SafeSearch check in photo upload handler (reject before storage)
- [ ] `moderation_status` column on `progress_entries`
- [ ] `content_reports` table and report button on progress entries
- [ ] Supabase Studio moderation queue view
- [ ] Removal notification via Notification Inbox

### Phase 2 — Post-Launch (First Month)

- [ ] Perspective API integration for `log_title` and `note` on progress entries
- [ ] Proportional report threshold logic (group size-aware auto-hide)
- [ ] `content_disputes` table and dispute flow in-app
- [ ] Community Standards bottom sheet on first public group join
- [ ] Warning notifications for borderline content

### Phase 3 — As Needed

- [ ] Expand blocklist based on real-world reports
- [ ] Tune Perspective API thresholds based on false positive/negative data
- [ ] Dedicated admin tooling beyond Supabase Studio if moderation volume warrants it
- [ ] Automated account-level escalation tracking

---

## 9. Open Questions

1. **Should goal names be Perspective-scanned?** Currently excluded as low-risk. Revisit if goal names surface as a vector for abuse post-launch.

2. **Profanity in private groups:** The current policy tolerates light profanity in private group journals. Should admins be able to configure a stricter standard for their group?

3. **Cross-group ban propagation:** If a user is suspended, should they be prevented from creating or joining new groups during the suspension period? Recommended: yes, but needs implementation consideration.

4. **Reporting abuse:** What prevents coordinated false reporting (e.g. multiple members pile-on reporting a legitimate post)? The proportional threshold helps, but post-launch data will reveal if this is a real vector.

---

**End of Specification**
