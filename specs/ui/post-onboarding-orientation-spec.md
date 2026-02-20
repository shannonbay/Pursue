# Pursue — Post-Onboarding Orientation Flow

**Feature:** New User Orientation  
**Version:** 1.0  
**Status:** Draft  
**Platform:** Android (Material Design 3)  
**Depends On:** Auth flow (`is_new_user` flag), group join flow, challenge creation flow, group creation flow, covenant bottom sheet, invite code system, challenge template browser

---

## 1. Overview

### 1.1 The Problem

After creating an account, a new user lands on the Home screen (Groups List). If they have no groups, they see the existing empty state: an illustration, "No groups yet!", and two buttons — "Join Group" and "Create Group". This empty state doesn't communicate what Pursue *is*, how groups and challenges differ, or guide the user through the most likely first actions. Every second of confusion is a second closer to uninstalling.

The existing empty state (UI Spec §4.2.1, State 3) remains the right fallback for users who have been around but happen to have zero groups. But for a *first-time user*, we need something more intentional.

### 1.2 The Solution

A guided orientation flow that appears **once** immediately after a new user completes registration (when no deep link is pending). The flow is a sequence of up to three full-screen steps, each designed to get the user into a group or challenge as quickly as possible:

1. **Join a Group** — Enter an invite code, scan a QR code, or skip
2. **Start a Challenge** — Browse templates and launch a time-boxed challenge
3. **Create a Group** — Set up an ongoing accountability group from scratch

The user can exit the orientation at any step. If they successfully join or create something at any point, the flow completes and drops them into their new group/challenge. The goal is to get them into *something*, not to walk them through every feature.

### 1.3 Deep Link Bypass

If a user installed the app via an invite deep link (`pursue://invite/{code}` or `getpursue.app/invite/{code}` or `getpursue.app/challenge/{code}`), the orientation flow is **bypassed entirely**. The deep link already provides the user's first action: joining a specific group or challenge.

**Rationale:** Deep link users have the highest intent and the clearest next step. Their friend already told them what Pursue is. The covenant bottom sheet (shown during group join) provides sufficient context about Pursue's culture. Inserting an orientation wizard between "I tapped an invite link" and "I'm in the group" would be pure friction that reduces the invite-to-join conversion rate — Pursue's most critical growth metric.

**Flow for deep link users:**
1. User taps invite link → installs app → creates account
2. App detects pending invite code (via `Intent.data`, deferred deep link store, or clipboard)
3. App calls join flow directly → covenant bottom sheet → user is in the group
4. Home screen shows their new group. Orientation never appears.

### 1.4 Design Goals

- **Speed to value:** Get the user into a group or challenge within 60 seconds of account creation
- **Progressive disclosure:** Don't explain everything — explain enough to take the next action
- **Skippable:** Every step has a clear "Skip" affordance. Nobody is trapped in a wizard
- **Consistent with existing UI:** Uses the same Material Design 3 components, color palette, and spacing defined in the UI Spec (§2)
- **One-time only:** Once dismissed or completed, the orientation never returns. The standard empty state (UI Spec §4.2.1 State 3) handles any future zero-groups scenario

---

## 2. Entry Conditions

### 2.1 When to Show Orientation

The orientation flow is triggered when **all** of the following are true:

1. The auth response returns `is_new_user: true`
2. No pending deep link invite code exists (checked via `Intent.data`, deferred deep link store, and clipboard)
3. The user has zero group memberships (defensive check — should always be true for new users, but guards against edge cases like re-registration with a previously used email)

### 2.2 When to Skip Orientation

Skip the orientation and go straight to the relevant action if:

- A pending invite code exists (deep link user) → Execute join flow directly
- `is_new_user` is `false` (returning user signing in) → Go to Home screen
- User has existing group memberships (edge case) → Go to Home screen

### 2.3 Persistence

Store a boolean `orientation_completed` in local preferences (not server-side — this is a client UX concern). Set to `true` when:

- The user completes any step successfully (joins a group, creates a challenge, or creates a group)
- The user reaches the end of the flow and taps "Skip" on Step 3
- The user exits via system back on Step 1

If the app is force-killed mid-orientation, show the orientation again on next launch (unless `orientation_completed` is already `true` or the user now has groups).

---

## 3. Flow Architecture

### 3.1 Step Sequence

```
Auth Complete (is_new_user: true, no deep link)
     ↓
┌─────────────────────────────┐
│  Step 1: Join a Group       │ ← Most likely path for invited users
│  (Invite code / QR / Skip)  │    who installed directly from Play Store
└──────────┬──────────────────┘
           │
    Joined? ─── YES ──→ Go to Group Detail → Done
           │
           NO (Skip)
           │
           ↓
┌─────────────────────────────┐
│  Step 2: Start a Challenge  │ ← Lowest friction creation path
│  (Template browser)         │    (templates do the heavy lifting)
└──────────┬──────────────────┘
           │
  Created? ─── YES ──→ Go to Challenge Detail → Done
           │
           NO (Skip)
           │
           ↓
┌─────────────────────────────┐
│  Step 3: Create a Group     │ ← Highest friction but highest value
│  (Name, goal, covenant)     │    (for power users who know what they want)
└──────────┬──────────────────┘
           │
  Created? ─── YES ──→ Go to Group Detail → Done
           │
           NO (Skip)
           │
           ↓
     Go to Home (empty state)
```

### 3.2 Step Ordering Rationale

1. **Join a Group first** because many users who install directly (without a deep link) still received a verbal or text recommendation from a friend who said "download Pursue and I'll send you the invite code." These users have the code in their messages or memory — they just didn't use the deep link. Getting them into the right group immediately is the highest-value outcome.

2. **Start a Challenge second** because it's the lowest-friction creation path. Templates eliminate the "what do I name it / what goals do I add" problem. A user who doesn't have a group to join but wants to try Pursue can be tracking a 30-Day No Sugar challenge within 3 taps. Challenges also create natural invite opportunities — once the challenge exists, the user will want to share it.

3. **Create a Group last** because it requires the most decisions (name, description, emoji, at least one goal). It's the right path for users who know exactly what they want — "I want an ongoing running accountability group" — but it's the wrong first suggestion for someone who just downloaded the app on a whim.

---

## 4. Screen Specifications

### 4.1 Step 1: Join a Group

**Purpose:** Let users who already have an invite code join their friend's group immediately.

**Layout:**

```
┌─────────────────────────────────────┐
│                              [Skip] │ ← Text button, top-right
│                                     │
│          [Illustration]             │
│       People connecting             │
│                                     │
│   Been invited to a group?          │ ← Headline Medium
│                                     │
│   If someone sent you an invite     │ ← Body Large, onSurfaceVariant
│   code, enter it below to join      │
│   their group.                      │
│                                     │
│   Invite Code                       │
│   ┌─────────────────────────────────┐
│   │ [Enter invite code]            ││ ← TextField, monospace hint
│   └─────────────────────────────────┘
│                                     │
│   ┌─────────────────────────────────┐
│   │         Join Group              ││ ← Primary button (blue)
│   └─────────────────────────────────┘   Disabled until code entered
│                                     │
│   ── or ──                          │
│                                     │
│   ┌─────────────────────────────────┐
│   │    📷  Scan QR Code            ││ ← Outlined button
│   └─────────────────────────────────┘
│                                     │
│   I don't have an invite code       │ ← Text button, onSurfaceVariant
│                                     │
│   ─────────────────────────────     │
│   Step 1 of 3  [●  ○  ○]           │ ← Progress indicator
│                                     │
└─────────────────────────────────────┘
```

**Behaviour:**

- **"Join Group" button:** Disabled (greyed) until the user enters at least 1 character. On tap:
  1. Show loading spinner on the button
  2. Call `POST /api/groups/join` with the entered code
  3. On success → show covenant bottom sheet → on covenant commit → navigate to Group Detail screen → orientation complete
  4. On error (invalid code, expired, etc.) → show inline error below the text field: "Invalid or expired invite code. Check with your friend and try again."
  5. On 409 (already a member) → show toast: "You're already in this group!" → navigate to Group Detail → orientation complete

- **"Scan QR Code" button:** Opens the device camera in a full-screen QR scanner. On successful scan:
  1. Extract invite code from the QR data URL (`getpursue.app/invite/{code}`)
  2. Populate the invite code field
  3. Auto-trigger the join flow (same as tapping "Join Group")

- **"I don't have an invite code" text button:** Advances to Step 2 (Start a Challenge). This is functionally identical to "Skip" but uses warmer language — it acknowledges the user's situation rather than making them feel like they're skipping something they should be doing.

- **"Skip" (top-right):** Same as "I don't have an invite code" — advances to Step 2.

**Input Validation:**
- Strip whitespace and hyphens from entered code before submission (users might type `PURSUE-ABC123-XYZ789` or `ABC123` — both should work)
- Case-insensitive matching (convert to uppercase before sending)
- Max length: 30 characters (prevents paste accidents)

**QR Scanner Permissions:**
- Request `CAMERA` permission when "Scan QR Code" is tapped
- If permission denied, show rationale: "Camera access is needed to scan QR codes. You can also enter the code manually above."
- If permission permanently denied, show settings prompt

### 4.2 Step 2: Start a Challenge

**Purpose:** Offer the lowest-friction path to engagement. The user picks a template, and the app creates a time-boxed challenge with pre-configured goals.

**Layout:**

```
┌─────────────────────────────────────┐
│ ← Back                       [Skip]│ ← Back returns to Step 1
│                                     │
│   Try a challenge!                  │ ← Headline Medium
│                                     │
│   Challenges are time-boxed goals   │ ← Body Large, onSurfaceVariant
│   — like "30-Day No Sugar" or       │
│   "Read Every Day for a Week."      │
│   Pick a template and start today.  │
│                                     │
│   ┌──── Groups vs Challenges ─────┐ │
│   │ ℹ️ What's the difference      │ │ ← Expandable info card
│   │  between groups and            │ │    surfaceVariant bg
│   │  challenges?              [▼] │ │    Collapsed by default
│   └───────────────────────────────┘ │
│                                     │
│   ✨ Featured                       │
│   ┌───────────┐ ┌───────────┐       │ ← Horizontal scroll (LazyRow)
│   │ 🍬        │ │ 🧘        │       │    Reuses TemplateCard from
│   │ 30-Day    │ │ 21-Day    │       │    challenges-spec §7.1
│   │ No Sugar  │ │ Meditation│       │
│   │ 30d · Hard│ │ 21d · Easy│       │
│   │ [Start]   │ │ [Start]   │       │
│   └───────────┘ └───────────┘       │
│                                     │
│   💪 Fitness                        │
│   ┌───────────┐ ┌───────────┐       │
│   │ 👟        │ │ 💪        │       │
│   │ 10K Steps │ │ 100 Push- │       │
│   │ Daily     │ │ ups/Day   │       │
│   │ 30d · Med │ │ 30d · Hard│       │
│   │ [Start]   │ │ [Start]   │       │
│   └───────────┘ └───────────┘       │
│                                     │
│   📚 Reading                        │
│   ...                               │
│                                     │
│   ─────────────────────────────     │
│   I'd rather create my own group    │ ← Text button → Step 3
│                                     │
│   Step 2 of 3  [●  ●  ○]           │
│                                     │
└─────────────────────────────────────┘
```

**Behaviour:**

- **Template cards:** Fetched from `GET /api/challenge-templates`. Displayed in horizontal scroll rows per category, identical to the challenge template browser defined in challenges-spec §7.1. Tapping "Start" on a template:
  1. Navigate to Challenge Setup Screen (challenges-spec §7.2) — user picks a start date and optionally renames the challenge
  2. On "Create Challenge" → `POST /api/challenges` → navigate to challenge group detail → orientation complete
  3. Back from Challenge Setup returns to this Step 2 screen (user can pick a different template)

- **"Groups vs Challenges" info card:** An expandable card (tap to expand/collapse) that briefly explains the difference. Collapsed by default to keep the screen scannable. Uses `surfaceVariant` background with `onSurfaceVariant` text. See §5 for full content.

- **"I'd rather create my own group" text button:** Advances to Step 3.

- **"Skip" (top-right):** Advances to Step 3.

- **Back arrow (top-left):** Returns to Step 1.

**Template Fetching:**
- Call `GET /api/challenge-templates` on screen entry
- Show shimmer placeholders while loading (2 rows of 2 skeleton cards)
- On error, show inline error with "Retry" button (don't block the whole screen — the user can still skip to Step 3)
- Cache templates in memory for the session (they won't change)

### 4.3 Step 3: Create a Group

**Purpose:** For users who want ongoing (non-time-boxed) accountability — the full Pursue experience. This step requires the most effort but produces the highest-value outcome.

**Layout:**

```
┌─────────────────────────────────────┐
│ ← Back                       [Skip]│
│                                     │
│   Create your group                 │ ← Headline Medium
│                                     │
│   Groups are ongoing — perfect for  │ ← Body Large, onSurfaceVariant
│   daily habits and weekly goals     │
│   with friends. No end date, just   │
│   consistent accountability.        │
│                                     │
│   Group Name *                      │
│   ┌─────────────────────────────────┐
│   │ [e.g. Morning Runners]         ││ ← TextField
│   └─────────────────────────────────┘
│                                     │
│   Icon                              │
│   [😀] [🏃] [📚] [🧘] [💪] [🎯]  │ ← Emoji picker row (scrollable)
│   [🍎] [💧] [✏️] [🎵] [🌅] [⭐]  │
│                                     │
│   Your first goal *                 │
│   ┌─────────────────────────────────┐
│   │ [e.g. Run 30 minutes]         ││ ← TextField
│   └─────────────────────────────────┘
│                                     │
│   Cadence                           │
│   [Daily ✓]  [Weekly]  [Monthly]   │ ← Segmented button row
│                                     │
│   Type                              │
│   [Yes/No ✓]  [Numeric]            │ ← Segmented button row
│                                     │
│   ┌─────────────────────────────────┐
│   │      Create Group               ││ ← Primary button (blue)
│   └─────────────────────────────────┘   Disabled until name + goal filled
│                                     │
│   ─────────────────────────────     │
│   Step 3 of 3  [●  ●  ●]           │
│                                     │
└─────────────────────────────────────┘
```

**Behaviour:**

- **"Create Group" button:** Disabled until both "Group Name" and goal title are filled. On tap:
  1. Show loading spinner on the button
  2. Call `POST /api/groups` with group name, icon emoji, and initial goal
  3. On success → navigate to Group Detail screen → orientation complete
  4. On error → show inline error or toast

- **Group creation does NOT show the covenant.** This is consistent with the existing covenant spec (§4.1): "Creating a group (creator auto-joins) → ❌ No — creator sets culture by creating."

- **Simplified goal creation:** This is a streamlined version of the full Create Goal screen (UI Spec §4.3.5). Only the essential fields are shown: title, cadence, and metric type. Description, target value, and unit are omitted for binary goals — the user can add those later. For numeric goals, show additional fields when "Numeric" is selected:

  ```
  Target (optional)
  ┌─────────────────────────────────┐
  │ [e.g. 30]                      │
  └─────────────────────────────────┘
  Unit (optional)
  ┌─────────────────────────────────┐
  │ [e.g. minutes]                 │
  └─────────────────────────────────┘
  ```

- **"Skip" (top-right):** Navigates to Home screen (empty state). Sets `orientation_completed = true`.

- **Back arrow (top-left):** Returns to Step 2.

**Validation:**
- Group name: 1–100 characters, trimmed. Required.
- Goal title: 1–200 characters, trimmed. Required.
- Cadence: Default "Daily" (pre-selected).
- Metric type: Default "Yes/No" (pre-selected).
- Target value (numeric): Optional. Positive number if provided.
- Unit (numeric): Optional. Max 50 characters.

---

## 5. Groups vs Challenges Explainer

### 5.1 Expandable Info Card

The info card on Step 2 uses a consistent design that can also be reused elsewhere (e.g. the home screen empty state, the template browser, or a future FAQ section).

**Collapsed state:**
```
┌──────────────────────────────────────┐
│  ℹ️  What's the difference between  │
│     groups and challenges?      [▼] │
└──────────────────────────────────────┘
```

**Expanded state:**
```
┌──────────────────────────────────────┐
│  ℹ️  What's the difference between  │
│     groups and challenges?      [▲] │
│                                      │
│  🔄 Groups are ongoing              │
│  Open-ended accountability with no   │
│  end date. Perfect for daily habits  │
│  and long-term goals you want to     │
│  track forever.                      │
│                                      │
│  🏆 Challenges are time-boxed       │
│  A fixed number of days with a clear │
│  start and end. Great for building   │
│  new habits or trying something for  │
│  a set period.                       │
│                                      │
│  Both include all the same features  │
│  — reactions, nudges, progress       │
│  tracking, and group activity feed.  │
└──────────────────────────────────────┘
```

**Styling:**
- Background: `surfaceVariant` (#F5F5F5 light / #1E1E1E dark)
- Corner radius: 12dp
- Padding: 16dp
- Text: `bodyMedium`, `onSurfaceVariant`
- Emoji icons: Inline, 16sp
- Expand/collapse: `AnimatedVisibility` with `expandVertically` transition (200ms)
- Tap target: Entire card header row is tappable (not just the chevron)

---

## 6. Progress Indicator

### 6.1 Step Dots

Each orientation screen shows a progress indicator at the bottom to communicate where the user is in the flow.

```
Step 1 of 3  [●  ○  ○]
Step 2 of 3  [●  ●  ○]
Step 3 of 3  [●  ●  ●]
```

**Styling:**
- Filled dot (●): Primary blue (#1976D2), 8dp diameter
- Empty dot (○): `onSurfaceVariant` (#757575) outline, 8dp diameter
- Spacing: 8dp between dots
- Label: `bodySmall`, `onSurfaceVariant`
- Position: Bottom of screen, centred, 16dp padding

---

## 7. Navigation & Exit Behaviour

### 7.1 Completion Triggers

The orientation is marked complete (and the user navigates away from the flow) when any of these occur:

| Trigger | Destination |
|---------|-------------|
| User joins a group (Step 1) | Group Detail screen |
| User creates a challenge (Step 2) | Challenge Group Detail screen |
| User creates a group (Step 3) | Group Detail screen |
| User taps "Skip" on Step 3 | Home screen (empty state) |
| User taps system back on Step 1 | Home screen (empty state) |

### 7.2 Back Navigation

| From | Back Action |
|------|-------------|
| Step 1 | System back → exit orientation → Home screen (empty state). The user chose to leave the wizard. |
| Step 2 | Back arrow or system back → Step 1 |
| Step 3 | Back arrow or system back → Step 2 |
| Challenge Setup (from Step 2) | Back arrow → Step 2 (template browser) |

### 7.3 Navigation Stack

The orientation flow is a **separate navigation graph** from the main app. When orientation completes:

1. Clear the orientation back stack entirely
2. Navigate to the destination screen (Group Detail or Home)
3. The main app's bottom navigation bar (Home, Today, Profile) becomes visible

During orientation, the bottom navigation bar is **hidden**. The user shouldn't be able to navigate to Today, Profile, or the main Home screen until orientation is complete or explicitly dismissed.

---

## 8. Analytics Events

| Event | When | Properties |
|-------|------|-----------|
| `orientation_started` | Orientation flow begins | — |
| `orientation_step_viewed` | User sees a step | `step` (1, 2, 3) |
| `orientation_join_attempted` | User taps "Join Group" | `method` (code, qr) |
| `orientation_join_succeeded` | User successfully joins a group | `method` (code, qr) |
| `orientation_join_failed` | Join attempt fails | `error_type` (invalid_code, expired, network) |
| `orientation_qr_opened` | User opens QR scanner | — |
| `orientation_template_browsed` | User scrolls through templates | `categories_viewed` |
| `orientation_challenge_started` | User taps "Start" on a template | `template_slug`, `category` |
| `orientation_challenge_created` | Challenge created successfully | `template_slug`, `duration_days` |
| `orientation_group_created` | Group created from Step 3 | — |
| `orientation_step_skipped` | User skips a step | `step` (1, 2, 3) |
| `orientation_completed` | Orientation ends | `completion_type` (joined, challenge_created, group_created, skipped_all) |
| `orientation_deep_link_bypassed` | Orientation skipped due to pending deep link | `invite_type` (group, challenge) |
| `orientation_info_card_expanded` | User expands the groups vs challenges card | — |

---

## 9. Relationship to Existing Screens

### 9.1 Existing Empty State (UI Spec §4.2.1 State 3)

The current home screen empty state remains unchanged and serves a different purpose:

- **Orientation flow:** First-time users, immediately after registration. Guided, progressive, educational.
- **Empty state:** Returning users who have left all their groups or had their groups deleted. Brief, functional, assumes the user already knows what Pursue is.

The empty state's "Join Group" and "Create Group" buttons continue to navigate to the standard join and create flows (not the orientation).

### 9.2 Speed Dial / FAB

The orientation flow does not use the home screen's FAB or speed dial. The orientation screens have their own CTAs inline. Once the user exits orientation, the standard Home screen FAB appears as normal.

### 9.3 Covenant Bottom Sheet

The covenant bottom sheet is shown as part of the join flow (Step 1) — after the invite code is validated but before the user is added to the group. It is **not** shown when creating a group (Step 3) or creating a challenge from a template (Step 2), consistent with the existing covenant spec (§4.1).

If a user joins a challenge via a template in Step 2, they are the *creator* of that challenge, so the covenant is not shown. If they later invite friends who join, those friends will see the covenant.

### 9.4 Challenge Template Browser

Step 2 reuses the template browser components from challenges-spec §7.1 — `ChallengeTemplatesScreen` composable, `TemplateCard`, and `ChallengeTemplateViewModel`. The only difference is context: in the orientation flow, the template browser is embedded within the Step 2 screen (with the added header text and info card) rather than being a standalone screen accessed from the speed dial.

---

## 10. Freemium Considerations

### 10.1 No Premium Gates During Orientation

Nothing in the orientation flow should show a premium upgrade prompt or paywall. All three steps use features available on the free tier:

- **Step 1 (Join Group):** Free users can join groups (up to the join limit)
- **Step 2 (Start a Challenge):** Free users can create 1 challenge from a template
- **Step 3 (Create a Group):** Free users can create 1 group

The "Create Custom Challenge" option (premium-only) is intentionally not surfaced in the orientation template browser. Only the curated template library is shown, keeping the experience clean and free-tier-friendly.

### 10.2 Resource Limit Edge Cases

If a user somehow hits a resource limit during orientation (unlikely for a brand new user, but defensively):

- Group join limit reached → Show message: "You've reached the maximum number of groups. Upgrade to Premium for unlimited groups."
- Challenge creation limit reached → Same pattern
- These edge cases are essentially impossible for truly new users but should be handled gracefully

---

## 11. Accessibility

### 11.1 Screen Reader Support

- Each step has a clear screen title announced on entry (e.g. "Join a Group, step 1 of 3")
- All interactive elements have `contentDescription` set
- The invite code text field announces its label and any error messages
- Progress dots announce: "Step 1 of 3" (not just visual dots)
- QR scanner announces: "Point your camera at a Pursue QR code"
- Expandable info card announces: "What's the difference between groups and challenges? Double tap to expand."

### 11.2 Keyboard/D-Pad Navigation

- All buttons and inputs are focusable in logical tab order
- Invite code field auto-focuses on Step 1 entry (keyboard appears automatically)
- Enter key in the invite code field triggers "Join Group"

### 11.3 Dynamic Text Sizing

- All orientation screens support text up to 200% system font size
- Content scrolls (`rememberScrollState()`) if it overflows the viewport at large text sizes
- Touch targets maintain 48dp minimum at all sizes

---

## 12. Implementation Checklist

### 12.1 Android

**Navigation:**
- [ ] Create `OrientationNavGraph` with 3 step destinations
- [ ] Hide bottom navigation bar during orientation flow
- [ ] Implement `orientation_completed` flag in `EncryptedSharedPreferences`
- [ ] Add deep link pending check in `MainActivity` / `AuthViewModel`
- [ ] Route new users to orientation or join flow based on deep link state

**Step 1 — Join a Group:**
- [ ] `OrientationJoinScreen` composable
- [ ] Invite code text field with validation and error states
- [ ] QR code scanner integration (CameraX + ML Kit Barcode Scanning)
- [ ] Camera permission handling with rationale
- [ ] Connect to existing `POST /api/groups/join` flow
- [ ] Integrate covenant bottom sheet on successful code validation
- [ ] Handle all error states (invalid code, expired, network, already member)

**Step 2 — Start a Challenge:**
- [ ] `OrientationChallengeScreen` composable
- [ ] Embed challenge template browser (reuse `ChallengeTemplatesScreen` components)
- [ ] `GroupsVsChallengesInfoCard` composable (expandable)
- [ ] Shimmer loading state for template fetch
- [ ] Connect "Start" button to `ChallengeSetupScreen`
- [ ] On challenge creation, navigate to group detail and mark orientation complete

**Step 3 — Create a Group:**
- [ ] `OrientationCreateGroupScreen` composable
- [ ] Simplified group creation form (name, emoji, one goal)
- [ ] Emoji picker row (horizontal scrollable `LazyRow`)
- [ ] Cadence segmented button (`SingleChoiceSegmentedButtonRow`: Daily/Weekly/Monthly)
- [ ] Metric type segmented button (Yes-No/Numeric) with conditional target/unit fields
- [ ] Connect to existing `POST /api/groups` endpoint
- [ ] On group creation, navigate to group detail and mark orientation complete

**Progress Indicator:**
- [ ] `OrientationProgressDots` composable (reusable)

**Analytics:**
- [ ] Fire all events listed in §8

### 12.2 Backend

No backend changes required. The orientation flow uses existing endpoints:

- `POST /api/groups/join` (Step 1)
- `GET /api/challenge-templates` (Step 2)
- `POST /api/challenges` (Step 2)
- `POST /api/groups` (Step 3)

### 12.3 Testing

**Unit Tests:**
- [ ] Deep link detection logic (intent data, deferred deep link, clipboard)
- [ ] Orientation entry condition evaluation
- [ ] Invite code sanitisation (whitespace, hyphens, case)
- [ ] Form validation for group creation fields

**Integration Tests:**
- [ ] Full orientation flow: skip all → arrives at empty home screen
- [ ] Full orientation flow: join via code → covenant → arrives at group detail
- [ ] Full orientation flow: create challenge → arrives at challenge detail
- [ ] Full orientation flow: create group → arrives at group detail
- [ ] Deep link bypass: pending invite code → orientation never shown
- [ ] Returning user: `is_new_user: false` → orientation never shown
- [ ] Orientation not shown after `orientation_completed = true`
- [ ] Back navigation between steps works correctly
- [ ] System back on Step 1 exits to home screen

**Manual Tests:**
- [ ] QR scanner works on a variety of devices and lighting conditions
- [ ] Orientation screens render correctly at 200% text size
- [ ] TalkBack reads all screens and interactions properly
- [ ] Dark mode renders correctly on all orientation screens
- [ ] Orientation renders correctly in landscape
- [ ] Force-killing the app mid-orientation → correct behaviour on restart

---

## 13. Success Metrics

| Metric | Target | Measurement |
|--------|--------|-------------|
| **Orientation completion rate** | >70% of non-deep-link new users complete at least one step | `orientation_completed` where `completion_type ≠ skipped_all` / `orientation_started` |
| **Step 1 conversion (Join)** | >30% of users who attempt a join succeed at Step 1 | `orientation_join_succeeded` / `orientation_join_attempted` |
| **Step 2 conversion (Challenge)** | >25% of users who reach Step 2 create a challenge | `orientation_challenge_created` / users who viewed Step 2 |
| **Step 3 conversion (Group)** | >15% of users who reach Step 3 create a group | `orientation_group_created` / users who viewed Step 3 |
| **Skip-all rate** | <30% of non-deep-link new users skip everything | `orientation_completed` where `completion_type = skipped_all` / `orientation_started` |
| **D1 retention uplift** | +10pp vs non-oriented users | Compare D1 retention for orientation completers vs skip-all users |
| **Deep link bypass rate** | Track but don't target — informational | `orientation_deep_link_bypassed` / total new users |

---

## 14. Future Enhancements

- **Smart clipboard detection:** If the user's clipboard contains text matching the invite code format (`PURSUE-XXXXXX-XXXXXX`), auto-populate the invite code field and highlight it: "It looks like you have an invite code — tap Join to use it." Note: Android 12+ shows a system toast when an app reads the clipboard, which may feel intrusive. Evaluate user perception before implementing.
- **Personalised template recommendations:** Use registration context (if collected) to surface relevant templates — fitness templates for a user who mentioned exercise goals, reading templates for book lovers.
- **Social proof on templates:** "1,243 people are doing this challenge right now" — adds urgency and validation.
- **Onboarding tooltips post-orientation:** After the user lands in their first group, show contextual tooltips: "Tap a goal to log progress", "Swipe to see members", etc. These are separate from the orientation flow and should be specced independently.
- **Re-engagement for skip-all users:** If a user skips all orientation steps and hasn't joined a group within 24 hours, send a push notification: "Your friends are waiting! Join a group to get started." Requires the user to have granted notification permission.

---

## 15. Open Questions

1. **Should the QR scanner be a separate full-screen or an overlay?** A full-screen scanner is simpler to implement and more reliable (better camera preview). An overlay within Step 1 is fancier but adds complexity. **Recommendation:** Full-screen scanner (consistent with standard Android patterns).

2. **Should Step 2 show the full template browser or a curated "top 6" selection?** The full browser might overwhelm a new user. **Recommendation:** Show Featured row + 2 category rows (max ~8–10 templates visible). Add "Browse all templates" text button at the bottom for users who want more.

3. **Should we track which step users spend the most time on?** This could inform future optimisations. **Recommendation:** Yes — add `orientation_step_time_spent` analytics event with `step` and `duration_seconds` properties.

4. **What illustration style for Step 1?** Should match the existing empty state illustration style (UI Spec §4.2.1 State 3: "Friendly characters collaborating on goals"). Consider a warm illustration showing two people exchanging an invite code or scanning a QR code.

---

**End of Specification**
