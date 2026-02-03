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

