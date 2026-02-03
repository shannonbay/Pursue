# UI Spec Updates - Pending Member Approval Queue

## New Screens

### Pending Approvals Screen

**Navigation:** Group Detail → Members Tab → Pending badge (admin only)

**Purpose:** Allow admins to review and approve/decline join requests

**Layout:**
```
┌─────────────────────────────────────┐
│ ← Pending Requests              ✓ All│ ← Back button + "Approve All"
├─────────────────────────────────────┤
│ 3 people waiting for approval       │
├─────────────────────────────────────┤
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ 👤 Shannon Thompson             │ │
│ │ Requested 2 hours ago           │ │
│ │                                 │ │
│ │ [✓ Approve]    [✗ Decline]     │ │ ← Action buttons
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ 👤 Alex Chen                    │ │
│ │ Requested 5 hours ago           │ │
│ │                                 │ │
│ │ [✓ Approve]    [✗ Decline]     │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ 👤 Jamie Lee                    │ │
│ │ Requested 1 day ago             │ │
│ │                                 │ │
│ │ [✓ Approve]    [✗ Decline]     │ │
│ └─────────────────────────────────┘ │
│                                     │
└─────────────────────────────────────┘
```

**UI Elements:**

**Pending Member Card:**
- Avatar (64dp circular)
- Display name (Title Medium)
- "Requested X ago" timestamp (Body Small, OnSurfaceVariant)
- Approve button (Filled tonal button, Success green #43A047)
- Decline button (Outlined button, Error red text)
- 16dp padding, 8dp spacing between buttons

**Header:**
- Back button (←)
- Title: "Pending Requests"
- "Approve All" action (text button, shows if 2+ pending)
- Count badge: "3 people waiting for approval"

**Interactions:**

**Approve Single Member:**
1. User taps "✓ Approve" on Shannon's card
2. Show progress indicator on card
3. Call `POST /api/groups/:group_id/members/:user_id/approve`
4. On success:
   - Card animates out (slide left, 200ms)
   - Toast: "Shannon approved"
   - Badge count decrements
   - Send FCM to Shannon
   - Send FCM to all active members
5. On error:
   - Show toast: "Failed to approve Shannon"
   - Keep card visible
   - Allow retry

**Decline Single Member:**
1. User taps "✗ Decline" on Alex's card
2. Show confirmation dialog:
   ```
   ┌─────────────────────────────┐
   │ Decline join request?       │
   ├─────────────────────────────┤
   │                             │
   │ Alex won't be able to see   │
   │ group content. They can     │
   │ request to join again.      │
   │                             │
   │ [Cancel]         [Decline]  │
   └─────────────────────────────┘
   ```
3. If confirmed:
   - Show progress on card
   - Call `POST /api/groups/:group_id/members/:user_id/decline`
   - Card animates out
   - Toast: "Request declined"
   - Send FCM to Alex

**Approve All:**
1. User taps "✓ All" in header
2. Show confirmation dialog:
   ```
   ┌─────────────────────────────┐
   │ Approve all 3 requests?     │
   ├─────────────────────────────┤
   │                             │
   │ Shannon Thompson            │
   │ Alex Chen                   │
   │ Jamie Lee                   │
   │                             │
   │ [Cancel]        [Approve]   │
   └─────────────────────────────┘
   ```
3. If confirmed:
   - Show progress overlay
   - Call approve API for each user sequentially
   - On complete: Navigate back to Members tab
   - Toast: "3 members approved"

**Empty State:**
```
┌─────────────────────────────────────┐
│ ← Pending Requests                  │
├─────────────────────────────────────┤
│                                     │
│          ✓                          │
│                                     │
│   All caught up!                    │
│                                     │
│   No pending join requests          │
│                                     │
└─────────────────────────────────────┘
```

---

## Modified Screens

### Members Tab (Group Detail)

**Updated Layout (Admin View):**
```
┌─────────────────────────────────────┐
│ ↑ Pending Requests (3)         →    │ ← New: Pending badge (admin only)
├─────────────────────────────────────┤
│                                     │
│ Members (6)                         │
│ ┌─────────────────────────────────┐ │
│ │ 👤 Shannon Thompson     👑      │ │ ← Crown = Creator
│ │ Last active: 2 hours ago        │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ 👤 Alex Johnson         🛡      │ │ ← Shield = Admin
│ │ Last active: 5 hours ago        │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ 👤 Jamie Chen                   │ │
│ │ Last active: Yesterday          │ │
│ └─────────────────────────────────┘ │
│                                     │
└─────────────────────────────────────┘
                                 [👤+] ← FAB: Invite Members
```

**Pending Requests Card (Admin Only):**
- Only visible if `pending_count > 0` AND user is admin/creator
- Background: Primary container (light blue)
- Icon: ⏳ or 👥 with badge
- Text: "Pending Requests (3)"
- Arrow (→) on right
- Tap → Navigate to Pending Approvals Screen
- 48dp min height
- Appears ABOVE member list

**API Calls:**
- On Members tab load:
  - `GET /api/groups/:group_id/members` (active members)
  - `GET /api/groups/:group_id/members/pending` (if admin) - for count badge

**Real-time Updates:**
- Listen for FCM notifications:
  - `join_request` → Increment pending badge, vibrate
  - `member_approved` → Refresh member list
  - `member_declined` → Decrement pending badge

---

### Join Group Flow (User Side)

**Updated Post-Join Screen:**

After user submits invite code, they see:

```
┌─────────────────────────────────────┐
│ ⏳ Request Sent                      │
├─────────────────────────────────────┤
│                                     │
│          [Hourglass Icon]           │
│                                     │
│   Waiting for approval              │
│                                     │
│   Your request to join              │
│   "Morning Runners" has been        │
│   sent to the group admins.         │
│                                     │
│   You'll get a notification         │
│   when they respond.                │
│                                     │
│   ┌───────────────────────────────┐ │
│   │         Done               │  │
│   └───────────────────────────────┘ │
│                                     │
└─────────────────────────────────────┘
```

**"Done" Button:**
- Navigate back to Home screen
- User will receive FCM when approved/declined

**Pending State in Home Screen:**

If user has pending join requests, show in group list:

```
┌─────────────────────────────────────┐
│ Your Groups (2)                     │
├─────────────────────────────────────┤
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ 🏃 Morning Runners              │ │
│ │ 8 members · 5 goals             │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ ⏳ Book Club (Pending)          │ │ ← Grayed out
│ │ Waiting for admin approval...   │ │
│ └─────────────────────────────────┘ │
│                                     │
└─────────────────────────────────────┘
```

**Pending Group Card:**
- Grayed out (lower opacity 0.6)
- Hourglass icon (⏳)
- "(Pending)" suffix in title
- Subtitle: "Waiting for admin approval..."
- Not tappable (disabled state)
- Removed from list when approved or declined

---

## FCM Notification Handling

### Join Request (Admin Receives)
```kotlin
data class JoinRequestNotification(
    val type: "join_request",
    val group_id: String,
    val user_id: String,
    val user_name: String
)

// Display:
Title: "New Join Request"
Body: "Shannon wants to join Morning Runners"
Action: Navigate to Pending Approvals Screen
Badge: Increment pending count on Members tab
```

### Approval (User Receives)
```kotlin
data class ApprovalNotification(
    val type: "member_approved",
    val group_id: String,
    val group_name: String
)

// Display:
Title: "Request Approved ✓"
Body: "You can now access Morning Runners"
Action: Navigate to Group Detail
Effect: 
  - Remove from pending groups
  - Add to active groups
  - Confetti animation on group detail screen
```

### Decline (User Receives)
```kotlin
data class DeclineNotification(
    val type: "member_declined",
    val group_id: String,
    val group_name: String
)

// Display:
Title: "Request Declined"
Body: "Your request to join Morning Runners was not approved"
Action: None (informational only)
Effect:
  - Remove from pending groups
  - Do NOT add to active groups
  - User can request again with invite code
```

---

## Activity Feed Updates

New activity types in Group Activity Feed:

```
┌─────────────────────────────────────┐
│ Today                               │
│ ┌─────────────────────────────────┐ │
│ │ 👤 Shannon requested to join    │ │ ← join_request
│ │ 2 hours ago                     │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ ✓ Alex was approved to join     │ │ ← member_approved
│ │ 5 hours ago                     │ │
│ └─────────────────────────────────┘ │
│                                     │
│ ┌─────────────────────────────────┐ │
│ │ ✗ Jamie's request was declined  │ │ ← member_declined
│ │ 1 day ago                       │ │
│ └─────────────────────────────────┘ │
│                                     │
└─────────────────────────────────────┘
```

**Icons:**
- join_request: ⏳ or 👤 with "?"
- member_approved: ✓ or 👤 with "✓"
- member_declined: ✗ or 👤 with "✗"

---

## Implementation Notes

### API Call Sequences

**Admin opens Members tab:**
1. `GET /api/groups/:group_id/members` → active members
2. `GET /api/groups/:group_id/members/pending` → pending count for badge

**Admin approves member:**
1. `POST /api/groups/:group_id/members/:user_id/approve`
2. Server sends FCM to approved user
3. Server sends FCM to all active members
4. Update UI: remove from pending list, add to members list

**User joins group:**
1. `POST /api/groups/join` with invite code
2. Response: `{"status": "pending", ...}`
3. Show "Request Sent" screen
4. Add to "pending groups" in local state
5. Wait for FCM approval/decline notification

### Error Handling

**Approve fails (network error):**
- Toast: "Failed to approve Shannon. Try again."
- Keep card in pending list
- Retry button available

**Approve fails (already approved by another admin):**
- Toast: "Shannon was already approved"
- Remove card from list
- Refresh member list

**User tries to join when already pending:**
- API returns 409 Conflict
- Show dialog: "You already have a pending request for this group"

---

## UI/UX Guidelines

**Pending Badge Visibility:**
- ONLY show to admins/creator
- Auto-refresh count every 30 seconds
- Animate badge when new request comes in (pulse)

**Approval Speed:**
- Aim for <200ms API response time
- Show immediate optimistic UI (remove card, show toast)
- Rollback on failure

**Notifications:**
- Join request → Vibrate + sound (high priority)
- Approval → Confetti + success sound
- Decline → Info tone only (neutral)

**Accessibility:**
- Announce "3 pending requests" with TalkBack
- "Approve" button: "Approve Shannon Thompson's request"
- "Decline" button: "Decline Shannon Thompson's request"
- Empty state: "No pending join requests"
