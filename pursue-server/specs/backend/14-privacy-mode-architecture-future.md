## 14. Privacy Mode Architecture (Future)

### 14.1 E2E Encryption Limitations

**Group-Level Seed Phrases** enable powerful privacy, but come with tradeoffs:

**What Works:**
- ✅ Client-side encryption of goal titles, descriptions, progress notes
- ✅ Server stores encrypted blobs (cannot read)
- ✅ Group members can decrypt with shared seed phrase
- ✅ True end-to-end encryption

**What Doesn't Work (Server is Blind):**

| Feature | Impact | Workaround |
|---------|--------|------------|
| **Weekly Summary Emails** | Cannot read goal titles to send "You completed 'X'" | Send generic: "You completed 5 of 7 goals" |
| **Server-Side Leaderboards** | Cannot rank by goal completion | Client-side only, shared via encrypted messages |
| **Search** | Cannot search encrypted titles | Client caches decrypted data locally |
| **Admin Moderation** | Cannot review content for abuse | Trust-based system, manual reports only |
| **Analytics** | Cannot aggregate across encrypted groups | Collect only metadata (goal count, not content) |
| **FCM Notifications** | Cannot send "Alex completed '30 min run'" | Send "Alex completed a goal" (generic) |

### 14.2 Implementation Strategy

**Client Responsibilities:**
```typescript
// Client encrypts before sending to server
const encryptedGoal = {
  group_id: groupId,
  encrypted_title: encrypt(seedPhrase, "30 min run"),
  encrypted_description: encrypt(seedPhrase, "Run for at least 30 minutes"),
  cadence: "daily", // NOT encrypted (needed for server logic)
  metric_type: "binary", // NOT encrypted
  // Server can see structure, not content
};

// Client decrypts after receiving from server
const decryptedGoal = {
  ...goalFromServer,
  title: decrypt(seedPhrase, goalFromServer.encrypted_title),
  description: decrypt(seedPhrase, goalFromServer.encrypted_description)
};
```

**Server Schema Changes:**
```sql
ALTER TABLE goals ADD COLUMN encrypted_title TEXT;
ALTER TABLE goals ADD COLUMN encrypted_description TEXT;
ALTER TABLE progress_entries ADD COLUMN encrypted_note TEXT;

-- Metadata still visible for logic
-- title, description, note columns NULL for encrypted groups
```

**Client-Side Aggregation:**
```typescript
// Weekly summary - client calculates
const summary = {
  totalGoals: decryptedGoals.length,
  completed: decryptedGoals.filter(g => g.completed).length,
  streak: calculateStreak(decryptedProgressEntries)
};

// Display locally, optionally share encrypted in group chat
```

### 14.3 Recommendation

**Initial Release:** Privacy Mode not included
- Adds 4-6 weeks development time
- Majority of users don't need E2E encryption for fitness goals
- Focus on core server-side features (emails, notifications, real-time sync)

**Future Enhancement:** Consider as premium feature ($5/month)
- Market to privacy-conscious power users
- Clearly communicate limitations (no email summaries, reduced real-time features)
- Provide "Standard" vs "Private" group toggle
- Disable features gracefully for encrypted groups

---

