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

## 14. Privacy Mode (E2E Encryption) - Architecture Notes

**Future Feature:** Group-level end-to-end encryption using group seed phrases.

### 14.1 How It Works

**Encryption Flow:**
1. Group creator enables "Privacy Mode" when creating group
2. App generates 12-word BIP39 seed phrase
3. Derive AES-256 key from seed phrase using PBKDF2
4. Encrypt group symmetric key with seed-derived key
5. Store encrypted symmetric key on server
6. Encrypt goal titles, descriptions, progress notes client-side
7. Server stores encrypted blobs, cannot read content

**Data Encrypted Client-Side:**
- Goal titles
- Goal descriptions  
- Progress entry notes
- Member display names (optional)

**Data NOT Encrypted (Required for Server Functionality):**
- Group ID, Group name (for routing)
- Goal IDs, metric_type, cadence, target_value (for validation)
- Progress values (numeric data only, no text)
- Timestamps (logged_at, period_start)
- User IDs (for access control)

### 14.2 Critical Limitations

**⚠️ Server Cannot Provide These Features for Encrypted Groups:**

1. **Weekly Summary Emails** 
   - Server blind to goal titles/progress notes
   - Cannot generate "You completed 'Morning Run' 5 times"
   - Would need to show: "You completed Goal #1 5 times" (useless)

2. **Server-Side Leaderboards**
   - Cannot sort by goal title
   - Cannot display achievement names
   - Leaderboards must be computed client-side

3. **Search Functionality**
   - Cannot search goal titles across groups
   - Cannot search progress notes
   - Full-text search impossible

4. **Admin Dashboards**
   - Cannot moderate content (goal titles could be inappropriate)
   - Cannot generate usage analytics (which goals are popular?)
   - Cannot provide support (can't see what user is asking about)

5. **Cross-Group Features**
   - Cannot suggest similar groups
   - Cannot show "trending goals"
   - Cannot detect duplicate group names

### 14.3 Client-Side Requirements

**App Must Handle:**
- All aggregation (weekly totals, streaks, charts)
- All search (within encrypted data)
- All leaderboards (compute locally)
- Export functionality (decrypt and export to CSV/PDF)
- Seed phrase backup and recovery UI

**Example Client-Side Aggregation:**
```typescript
// Client must decrypt all entries, then aggregate
const entries = await fetchProgressEntries(groupId, startDate, endDate);
const decryptedEntries = entries.map(e => ({
  ...e,
  goal_title: decrypt(e.encrypted_goal_title, groupKey),
  note: decrypt(e.encrypted_note, groupKey)
}));

// Now compute weekly total
const weeklyTotal = decryptedEntries.filter(e => e.value === 1).length;
```

### 14.4 Recommended Approach

**Initial Implementation:**
- Standard (unencrypted) groups with server-side features
- Focus on core functionality: real-time sync, notifications, analytics
- Gather user feedback on privacy requirements

**Future Enhancement:**
- Offer "Privacy Mode" as opt-in premium feature if demand exists
- Clearly document limitations (no emails, no server-side aggregations)
- Target audience: Privacy-conscious power users who understand trade-offs

**Alternative Consideration:**
- Server-side encryption at rest (database-level encryption)
- Provides data-at-rest protection without E2E limitations
- Enables all server features while improving security posture

---

