# Pursue — The Pursue Covenant

**Feature:** Group & Challenge Covenant  
**Version:** 1.0  
**Status:** Draft  
**Platform:** Android (Material Design 3)  
**Depends On:** Group join flow, challenge join flow, `group_memberships` table

---

## 1. Overview

### 1.1 What Is the Covenant?

The Pursue Covenant is a brief, intentional commitment that every user affirms with a **long press** before joining any group or challenge. It is not a legal document — it is a social contract that establishes the culture of Pursue from day one.

Pursue manages more than data. It manages social perceptions, reputations, and the daily vulnerability of showing up (or not) in front of people you care about. The covenant exists to make explicit what Pursue is built to be: **a platform for support and encouragement, not rivalry, criticism, or jealousy.**

### 1.2 Why a Covenant, Not Terms?

- **Terms of Service** are legal, long, and universally ignored. Pursue already has those.
- **A covenant** is personal, brief, and felt. It's a promise between people, not between a user and a corporation.
- The long-press interaction makes it physical — you are *holding* your commitment, not tapping past it.

### 1.3 Design Goals

- **Felt, not read past.** Short enough to read in 10 seconds, meaningful enough to remember.
- **Positive framing.** Describes what you *will* do, not a list of prohibitions.
- **Sets culture early.** New members absorb group norms before they've posted anything.
- **Non-legalistic.** Warm, human language. No legalese, no bullet points of forbidden behavior.

---

## 2. Covenant Text

```
THE PURSUE COVENANT

I'm joining this group to grow, not to judge.

I'll cheer for others on their good days
and have grace for them on their hard ones —
including myself.

Progress here is personal.
I won't compare to compete.
I won't use what I see to criticise or shame.

I'll show up honestly,
encourage generously,
and remember that everyone here
is pursuing something that matters to them.

[ ━━━━━━━━━━━━ Hold to commit ━━━━━━━━━━━━ ]
```

### 2.1 Text Rationale

| Line | Purpose |
|------|---------|
| *"to grow, not to judge"* | Sets the frame immediately — this is about self-improvement, not evaluation of others |
| *"cheer... good days / grace... hard ones"* | Covers both reactions to success (no jealousy) and reactions to struggle (no criticism) |
| *"including myself"* | Normalises self-compassion — prevents the app from becoming a self-punishment tool |
| *"Progress here is personal"* | Acknowledges that goals have different meaning and difficulty for different people |
| *"won't compare to compete"* | Directly addresses the rivalry risk without banning healthy inspiration |
| *"won't use what I see to criticise or shame"* | The most important line — names the specific harm that visibility enables |
| *"show up honestly"* | Encourages genuine logging over performative logging |
| *"encourage generously"* | Frames social features (nudges, reactions) as tools of generosity |
| *"pursuing something that matters to them"* | Closes with respect for the diversity of goals — a 6am wake-up and a sobriety goal deserve equal dignity |

### 2.2 Challenge Variant (Optional)

For challenges specifically, prepend one additional line to acknowledge the time-boxed intensity:

```
I'm taking on this challenge alongside others, not against them.
```

This addresses the heightened competitive instinct that countdowns and completion rates can trigger.

---

## 3. UI Design

### 3.1 Presentation: Modal Bottom Sheet

The covenant appears as a **full-height modal bottom sheet** when a user taps "Join" on any group or challenge. This is consistent with Pursue's existing bottom sheet pattern (reactions detail, nudge goal picker, etc.).

```
┌─────────────────────────────────────────┐
│                                         │
│            THE PURSUE COVENANT          │
│                                         │
│   I'm joining this group to grow,       │
│   not to judge.                         │
│                                         │
│   I'll cheer for others on their good   │
│   days and have grace for them on       │
│   their hard ones — including myself.   │
│                                         │
│   Progress here is personal.            │
│   I won't compare to compete.           │
│   I won't use what I see to criticise   │
│   or shame.                             │
│                                         │
│   I'll show up honestly,                │
│   encourage generously,                 │
│   and remember that everyone here       │
│   is pursuing something that matters    │
│   to them.                              │
│                                         │
│  ┌─────────────────────────────────┐    │
│  │     ━━━━ Hold to commit ━━━━    │    │
│  └─────────────────────────────────┘    │
│                                         │
└─────────────────────────────────────────┘
```

### 3.2 Long-Press Button Behavior

The "Hold to commit" button uses a **progress-fill animation** during the long press, similar to Instagram's "hold to record" or delete-confirmation patterns.

**States:**

| State | Visual | Duration |
|-------|--------|----------|
| **Idle** | Pill-shaped button, outlined, muted text: "━━━━ Hold to commit ━━━━" | — |
| **Pressing** | Fill animates left-to-right with `primaryContainer` color. Subtle haptic tick at 50% | 1.5 seconds total |
| **Committed** | Button fills completely, text changes to "✓ Committed", brief success haptic | 300ms hold then auto-dismiss |
| **Released early** | Fill resets smoothly to 0%. No error — just resets silently | 200ms ease-out |

**Implementation notes:**

- Use `Modifier.pointerInput` with `detectTapGestures(onLongPress = ...)` or a custom `awaitPointerEvent` loop to track press duration.
- Animate a `Box` width from 0% to 100% over 1.5 seconds using `Animatable<Float>`.
- Fire a single haptic feedback (`HapticFeedbackType.LongPress`) at the commitment threshold.
- The 1.5-second hold is intentional — long enough to feel deliberate, short enough to not feel tedious.

### 3.3 Typography

- **Title ("THE PURSUE COVENANT"):** `titleMedium`, `onSurface`, letter-spacing `2.sp`, centered
- **Body text:** `bodyLarge`, `onSurfaceVariant`, line-height `24.sp`, centered
- **Key phrases** (e.g., "grow, not to judge" / "criticise or shame"): No bold or emphasis — the words carry their own weight. Uniform styling reinforces that every line matters equally.
- **Button text:** `labelLarge`, `onSurfaceVariant` (idle) → `onPrimaryContainer` (filling) → `onPrimary` (committed)

### 3.4 Dismissibility

- The bottom sheet **cannot be dismissed by dragging down or tapping outside** while in this state. The user must either long-press to commit or tap a small "✕" close button in the top-right corner, which cancels the join entirely.
- Closing without committing returns the user to wherever they were — no join occurs, no snackbar, no guilt.

---

## 4. When the Covenant Appears

### 4.1 Trigger Points

| Action | Covenant shown? |
|--------|----------------|
| Joining a group via invite code | ✅ Yes |
| Joining a group via invite link | ✅ Yes |
| Joining a challenge via template | ✅ Yes |
| Joining a challenge via invite link | ✅ Yes |
| Creating a group (creator auto-joins) | ❌ No — creator sets culture by creating |
| Creating a challenge | ❌ No — same rationale |
| Re-joining a group you previously left | ✅ Yes — reaffirm the commitment |

### 4.2 Frequency

The covenant is shown **every time** a user joins a new group or challenge. It is intentionally not a one-time-only prompt. Each group is a new set of people and a new social context — the commitment should be renewed.

However, the covenant is **not** shown when:
- Opening the app
- Logging progress
- Sending a nudge or reaction
- Any other in-app action

---

## 5. Data Model

### 5.1 Tracking Covenant Acceptance

Add a column to `group_memberships` to record when the covenant was accepted:

```sql
ALTER TABLE group_memberships
  ADD COLUMN covenant_accepted_at TIMESTAMP WITH TIME ZONE;
```

When a user completes the long-press and the join request is sent, `covenant_accepted_at` is set to `NOW()` server-side.

**For existing members** (pre-covenant feature): `covenant_accepted_at` will be `NULL`. This is acceptable — they joined before the covenant existed. No retroactive prompting is required, though you could optionally show the covenant once on next app open with a gentler framing: *"We've added the Pursue Covenant. Here's what we're all about."*

### 5.2 No Enforcement Mechanism

The covenant is deliberately **not tied to any automated enforcement**. There is no "three strikes" system, no content moderation AI scanning for negativity, no covenant-violation reports.

**Why:** The covenant is aspirational, not punitive. It works by setting expectations and shaping culture, not by policing behavior. If a member is genuinely toxic, the existing admin tools (remove member) are the right mechanism.

---

## 6. API Changes

### 6.1 Join Endpoint Update

The existing `POST /api/groups/join` endpoint accepts an invite code and creates a membership. Add an optional field to the request body:

```json
{
  "invite_code": "ABC123",
  "covenant_accepted": true
}
```

**Server behavior:**
- If `covenant_accepted` is `true`, set `covenant_accepted_at = NOW()` on the new `group_memberships` row.
- If `covenant_accepted` is `false` or missing, still allow the join (for backward compatibility and edge cases like API-direct joins), but leave `covenant_accepted_at` as `NULL`.
- The client is responsible for showing the covenant UI and only sending the join request after the long-press completes.

### 6.2 No New Endpoints Required

The covenant is a client-side UX gate, not a server-side enforcement mechanism. No new endpoints are needed.

---

## 7. Accessibility

- **Screen readers:** The covenant text is a single readable block. The long-press button announces: *"Hold for one and a half seconds to commit to the Pursue Covenant and join this group."*
- **Motor accessibility:** For users who cannot long-press, provide an alternative: after 3 seconds of the covenant being visible, show a secondary text link beneath the button: *"Can't long-press? Tap here instead."* This tap opens a simple confirmation dialog: *"Join this group? By joining, you accept the Pursue Covenant."* with "Join" / "Cancel" buttons.
- **Text scaling:** The covenant text must remain fully visible at the system's largest font size. Use `rememberScrollState()` on the body content if needed.

---

## 8. Localisation Notes

The covenant should be **translated with care**, not machine-translated. The tone and warmth matter as much as the literal meaning. For the NZ/AU launch, British English spelling is used ("criticise" not "criticize"). When expanding to US/UK/CA markets, maintain regional spelling variants.

Key translation considerations:
- "Covenant" may not carry the same weight in all cultures. Consider "pledge" or "promise" in some locales.
- "Grace" has spiritual connotations in some contexts — translators should use the equivalent of "patience and understanding" if "grace" doesn't land naturally.
- The poetic line structure should be preserved where the target language allows it.

---

## 9. Marketing Site Integration

### 9.1 Covenant Page

Add a `/covenant` page to getpursue.app that displays the covenant text with a brief explanation of why it exists. This serves two purposes:

1. **Pre-download culture setting:** Potential users see what kind of community they're joining before they install.
2. **Shareability:** Group admins can share the covenant link when inviting people: *"This is the kind of group we're building."*

### 9.2 Suggested Page Content

```
THE PURSUE COVENANT

Every time someone joins a group on Pursue, they make this commitment:

[Covenant text]

We built Pursue for people who want to grow alongside others —
not compete against them. The covenant is our way of saying:
your progress is safe here.
```

---

## 10. Implementation Checklist

### 10.1 Database
- [ ] Add `covenant_accepted_at` column to `group_memberships`
- [ ] Run migration
- [ ] Update `POST /api/groups/join` to accept and store `covenant_accepted`

### 10.2 Android
- [ ] Create `CovenantBottomSheet` composable
- [ ] Implement long-press progress animation (1.5s fill)
- [ ] Add haptic feedback at commitment threshold
- [ ] Add success state animation and auto-dismiss
- [ ] Integrate into group join flow (invite code entry → covenant → join)
- [ ] Integrate into challenge join flow (template browse → covenant → join)
- [ ] Integrate into invite link deep-link flow (link → covenant → join)
- [ ] Add accessibility: screen reader labels for long-press button
- [ ] Add accessibility: tap-to-confirm fallback after 3 seconds
- [ ] Add challenge variant prepend line when joining a challenge
- [ ] Test text scaling at max system font size
- [ ] Test on various screen sizes (covenant must not be cut off)

### 10.3 Marketing Site
- [ ] Create `/covenant` page on getpursue.app
- [ ] Add covenant to site navigation (footer → Legal section or About section)
- [ ] OG tags for social sharing of the covenant page

### 10.4 Testing
- [ ] Long-press completes at 1.5s → join request fires with `covenant_accepted: true`
- [ ] Early release resets animation, no join occurs
- [ ] Close button (✕) cancels join entirely
- [ ] Covenant shown for every new group/challenge join
- [ ] Covenant NOT shown when creating a group/challenge
- [ ] Covenant shown when re-joining a previously left group
- [ ] Accessibility fallback (tap-to-confirm) works after 3s delay
- [ ] Screen reader announces button purpose and duration

---

## 11. Success Metrics

The covenant's success is measured by what it prevents, which is hard to quantify directly. Proxy metrics:

| Metric | Signal | Measurement |
|--------|--------|-------------|
| **Join completion rate** | Covenant doesn't deter legitimate joins | (joins completed) / (covenant sheets opened) > 95% |
| **Group retention at 7 days** | Members who affirm the covenant stay longer | Compare 7-day retention pre/post covenant (if launched mid-lifecycle) |
| **Admin-initiated removals** | Fewer toxic members to remove | Track `member_removed` events over time |
| **Nudge opt-out rate** | Members feel safe receiving nudges | `nudge_notifications_enabled = false` rate stays stable or decreases |
| **Covenant page shares** | Admins use it as a culture-setting tool | Track `/covenant` page views and referral sources |

---

## 12. Future Enhancements

- **Group-specific covenant addendum:** Allow group creators to add 1–2 custom lines beneath the standard covenant (e.g., *"In this group, we also commit to keeping our conversations confidential."*). Moderated by character limit (100 chars max) and reviewed by admin only.
- **Annual re-affirmation:** On the anniversary of joining a group, gently surface the covenant again: *"You've been part of Morning Runners for a year. Here's the commitment you made."* No action required — just a reminder.
- **Covenant in weekly recap:** Include a subtle rotating excerpt from the covenant in the weekly group recap email/notification, reinforcing culture passively.
