## 5. Push Notifications (FCM)

### 5.1 Send Notification to Group

```typescript
import admin from 'firebase-admin';

export async function sendGroupNotification(
  groupId: string,
  notification: {
    title: string;
    body: string;
  },
  data: Record<string, string>
) {
  // Get all FCM tokens for group members
  const devices = await db
    .selectFrom('devices')
    .innerJoin('group_memberships', 'devices.user_id', 'group_memberships.user_id')
    .select(['devices.fcm_token'])
    .where('group_memberships.group_id', '=', groupId)
    .execute();
    
  const tokens = devices.map(d => d.fcm_token);
  
  if (tokens.length === 0) {
    return;
  }
  
  // Send multicast message
  const message = {
    notification,
    data,
    tokens
  };
  
  const response = await admin.messaging().sendMulticast(message);
  
  // Handle failed tokens (device uninstalled, invalid token)
  if (response.failureCount > 0) {
    const failedTokens: string[] = [];
    response.responses.forEach((resp, idx) => {
      if (!resp.success) {
        failedTokens.push(tokens[idx]);
      }
    });
    
    // Remove failed tokens from database
    if (failedTokens.length > 0) {
      await db
        .deleteFrom('devices')
        .where('fcm_token', 'in', failedTokens)
        .execute();
    }
  }
}
```

### 5.2 Notification Examples

**Progress Logged:**
```typescript
await sendGroupNotification(
  groupId,
  {
    title: "Morning Runners",
    body: "Shannon completed '30 min run'"
  },
  {
    type: "progress_logged",
    group_id: groupId,
    goal_id: goalId,
    user_id: userId
  }
);
```

**Member Joined:**
```typescript
await sendGroupNotification(
  groupId,
  {
    title: "Morning Runners",
    body: "Jamie joined the group"
  },
  {
    type: "member_joined",
    group_id: groupId,
    user_id: newUserId
  }
);
```

---

