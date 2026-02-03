# Backend Spec Updates - Pending Member Approval Queue

## Database Schema Changes

Already updated in main spec:
- Added `status` column to `group_memberships` (values: 'pending', 'active', 'declined')
- Added partial index for efficient pending member queries

## New API Endpoints

### GET /api/groups/:group_id/members/pending
Get pending join requests (admin only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "pending_members": [
    {
      "user_id": "user-uuid",
      "display_name": "Shannon Thompson",
      "has_avatar": true,
      "requested_at": "2026-01-16T10:30:00Z"
    },
    {
      "user_id": "user-uuid-2",
      "display_name": "Alex Chen",
      "has_avatar": false,
      "requested_at": "2026-01-16T11:15:00Z"
    }
  ]
}
```

**Server Logic:**
1. Verify user is admin or creator
2. Fetch memberships where status='pending'
3. Join with users table to get display names
4. Return pending members ordered by requested_at

**Authorization:**
- User must be admin or creator

---

### POST /api/groups/:group_id/members/:user_id/approve
Approve pending member (admin only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "success": true
}
```

**Server Logic:**
1. Verify requesting user is admin or creator
2. Find membership with status='pending'
3. Update status to 'active'
4. Create group_activity entry (type='member_approved')
5. Send FCM to approved user: "You've been approved to join Morning Runners"
6. Send FCM to all active members: "Shannon joined the group"
7. Return success

**Authorization:**
- User must be admin or creator

**Errors:**
- 404: User not found or no pending request
- 403: Not authorized

---

### POST /api/groups/:group_id/members/:user_id/decline
Decline pending member (admin only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "success": true
}
```

**Server Logic:**
1. Verify requesting user is admin or creator
2. Find membership with status='pending'
3. Update status to 'declined'
4. Create group_activity entry (type='member_declined')
5. Send FCM to declined user: "Your request to join Morning Runners was declined"
6. Return success

**Notes:**
- Declined memberships remain in database (soft rejection)
- User can submit new join request after being declined
- Admins can see history of declined requests

**Authorization:**
- User must be admin or creator

**Errors:**
- 404: User not found or no pending request
- 403: Not authorized

---

## Modified Endpoints

### POST /api/groups/join
Join group via invite code.

**Changes:**
- Now creates membership with status='pending' instead of 'active'
- Returns pending status to user
- Sends FCM to admins instead of all members

**Response (200 OK):**
```json
{
  "status": "pending",
  "group": {
    "id": "group-uuid",
    "name": "Morning Runners",
    "member_count": 9
  },
  "message": "Join request sent to group admins for approval"
}
```

### GET /api/groups/:group_id/members
List group members (active only).

**Changes:**
- Now filters for status='active' only
- Pending members excluded (use /pending endpoint instead)

**Server Logic:**
- Fetch memberships where status='active'
- Join with users table
- Order by role (creator first, then admins, then members)

---

## Activity Type Updates

New activity types added:
- `join_request` - pending member waiting for approval
- `member_approved` - admin approved join request  
- `member_declined` - admin declined join request

Existing types remain unchanged.

### Group activity metadata for approval/decline

When creating `group_activity` entries for `member_approved` and `member_declined`, the activity `user` is the admin who performed the action (approver/decliner). The app displays the **approved/declined** user in the feed (e.g. "Test was approved to join the group"), so the backend must include that user in **metadata**:

**member_approved** – metadata must include:
- `approved_user_display_name` – display name of the user who was approved (e.g. `"Test"`)
- `approved_user_id` – user id of the approved user (used by the app for "(You)" when the viewer is that user)

**member_declined** – metadata must include:
- `declined_user_display_name` – display name of the user whose request was declined
- `declined_user_id` – user id of the declined user (used by the app for "(You)" when the viewer is that user)

---

## FCM Notification Updates

### Join Request Notification (to admins)
```typescript
await sendToGroupAdmins(
  groupId,
  {
    title: "New Join Request",
    body: "Shannon wants to join Morning Runners"
  },
  {
    type: "join_request",
    group_id: groupId,
    user_id: requestingUserId
  }
);
```

### Approval Notification (to approved user)
```typescript
await sendToUser(
  userId,
  {
    title: "Request Approved",
    body: "You've been approved to join Morning Runners"
  },
  {
    type: "member_approved",
    group_id: groupId
  }
);
```

### Decline Notification (to declined user)
```typescript
await sendToUser(
  userId,
  {
    title: "Request Declined",
    body: "Your request to join Morning Runners was declined"
  },
  {
    type: "member_declined",
    group_id: groupId
  }
);
```
