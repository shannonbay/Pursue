# Pursue Invite Code Specification

## Overview

This specification defines the invite code system for Pursue groups. Each group has exactly **one active, permanent, reusable invite code**. This design prevents database spam, simplifies user experience, and ensures invite links never expire unexpectedly.

---

## Design Principles

1. **One Code Per Group**: Each group has exactly one active invite code at any time
2. **Permanent & Reusable**: Invite codes don't expire and can be used unlimited times
3. **Automatic Creation**: Invite codes are generated automatically when a group is created
4. **Regeneration Support**: Admins can regenerate codes (revokes old code, creates new one)
5. **No Tracking**: No max_uses or current_uses - codes are simple and always work

---

## Database Schema

### Current Schema (To Be Replaced)

```sql
-- OLD SCHEMA - DO NOT USE
CREATE TABLE invite_codes (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  code VARCHAR(50) UNIQUE NOT NULL,
  created_by_user_id UUID NOT NULL REFERENCES users(id),
  max_uses INTEGER, -- NULL = unlimited
  current_uses INTEGER DEFAULT 0,
  expires_at TIMESTAMP WITH TIME ZONE, -- NULL = never expires
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_invite_codes_group ON invite_codes(group_id);
CREATE INDEX idx_invite_codes_code ON invite_codes(code);
```

### New Schema (Single Reusable Code)

```sql
-- NEW SCHEMA
CREATE TABLE invite_codes (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  code VARCHAR(50) UNIQUE NOT NULL,
  created_by_user_id UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  revoked_at TIMESTAMP WITH TIME ZONE -- NULL = active, timestamp = revoked
);

-- Ensures only one active code per group
CREATE UNIQUE INDEX idx_invite_codes_active_per_group ON invite_codes(group_id) 
WHERE revoked_at IS NULL;

CREATE INDEX idx_invite_codes_code ON invite_codes(code);

COMMENT ON TABLE invite_codes IS 'Each group has exactly one active invite code. Codes are permanent and reusable unless regenerated.';
COMMENT ON COLUMN invite_codes.revoked_at IS 'NULL = code is active, timestamp = code was revoked (replaced by new code)';
```

**Key Changes:**
- ❌ Removed `max_uses` (no limit tracking needed)
- ❌ Removed `current_uses` (no usage tracking needed)
- ❌ Removed `expires_at` (codes never expire)
- ✅ Added `revoked_at` (for code regeneration)
- ✅ Added partial unique index (only one active code per group)

---

## Database Migration

### Migration: Invite Code Simplification

**File**: `migrations/003_simplify_invite_codes.sql`

```sql
-- Migration 003: Simplify Invite Codes
-- Purpose: Convert from multi-code-per-group to single-reusable-code-per-group
-- Date: 2026-02-03

BEGIN;

-- Step 1: Create new temporary table with new schema
CREATE TABLE invite_codes_new (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  code VARCHAR(50) UNIQUE NOT NULL,
  created_by_user_id UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  revoked_at TIMESTAMP WITH TIME ZONE
);

-- Step 2: Migrate existing data
-- For each group, keep only the most recent non-expired invite code
INSERT INTO invite_codes_new (id, group_id, code, created_by_user_id, created_at, revoked_at)
SELECT DISTINCT ON (group_id)
  id,
  group_id,
  code,
  created_by_user_id,
  created_at,
  NULL as revoked_at  -- All migrated codes become active
FROM invite_codes
WHERE 
  (expires_at IS NULL OR expires_at > NOW())  -- Only non-expired codes
  AND (max_uses IS NULL OR current_uses < max_uses)  -- Only non-exhausted codes
ORDER BY group_id, created_at DESC;  -- Take most recent per group

-- Step 3: For groups without any valid invite code, create a new one
INSERT INTO invite_codes_new (group_id, code, created_by_user_id, created_at)
SELECT 
  g.id as group_id,
  'PURSUE-' || UPPER(SUBSTRING(MD5(RANDOM()::TEXT) FROM 1 FOR 6)) || '-' || 
   UPPER(SUBSTRING(MD5(RANDOM()::TEXT) FROM 1 FOR 6)) as code,
  g.creator_user_id,
  NOW()
FROM groups g
WHERE NOT EXISTS (
  SELECT 1 FROM invite_codes_new icn WHERE icn.group_id = g.id
);

-- Step 4: Drop old table and rename new table
DROP TABLE invite_codes;
ALTER TABLE invite_codes_new RENAME TO invite_codes;

-- Step 5: Create indexes
CREATE UNIQUE INDEX idx_invite_codes_active_per_group ON invite_codes(group_id) 
WHERE revoked_at IS NULL;

CREATE INDEX idx_invite_codes_code ON invite_codes(code);

-- Step 6: Add comments
COMMENT ON TABLE invite_codes IS 'Each group has exactly one active invite code. Codes are permanent and reusable unless regenerated.';
COMMENT ON COLUMN invite_codes.revoked_at IS 'NULL = code is active, timestamp = code was revoked (replaced by new code)';

COMMIT;
```

### Rollback Migration

**File**: `migrations/003_simplify_invite_codes_rollback.sql`

```sql
-- Rollback Migration 003
-- WARNING: This rollback loses usage tracking data (current_uses)

BEGIN;

-- Step 1: Create old schema table
CREATE TABLE invite_codes_old (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  code VARCHAR(50) UNIQUE NOT NULL,
  created_by_user_id UUID NOT NULL REFERENCES users(id),
  max_uses INTEGER,
  current_uses INTEGER DEFAULT 0,
  expires_at TIMESTAMP WITH TIME ZONE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Step 2: Migrate back (only active codes)
INSERT INTO invite_codes_old (id, group_id, code, created_by_user_id, created_at)
SELECT id, group_id, code, created_by_user_id, created_at
FROM invite_codes
WHERE revoked_at IS NULL;

-- Step 3: Drop new table and rename
DROP TABLE invite_codes;
ALTER TABLE invite_codes_old RENAME TO invite_codes;

-- Step 4: Recreate old indexes
CREATE INDEX idx_invite_codes_group ON invite_codes(group_id);
CREATE INDEX idx_invite_codes_code ON invite_codes(code);

COMMIT;
```

---

## API Endpoints

### 1. Get Group Invite Code

**Endpoint**: `GET /api/groups/:group_id/invite`

**Purpose**: Retrieve the current active invite code for a group

**Authorization**: User must be a member of the group

**Request**:
```http
GET /api/groups/550e8400-e29b-41d4-a716-446655440000/invite
Authorization: Bearer {access_token}
```

**Response (200 OK)**:
```json
{
  "invite_code": "PURSUE-A7X9K2-M4P8L6",
  "share_url": "https://getpursue.app/join/PURSUE-A7X9K2-M4P8L6",
  "created_at": "2026-02-01T10:30:00Z"
}
```

The client can generate a QR code locally from `share_url` (or from `invite_code` with format `https://getpursue.app/join/{invite_code}`), avoiding a backend round-trip.

**Server Logic**:
```typescript
async function getGroupInviteCode(groupId: string, userId: string) {
  // 1. Verify user is member of group
  const membership = await db
    .selectFrom('group_memberships')
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .executeTakeFirst();
  
  if (!membership) {
    throw new UnauthorizedError('Not a member of this group');
  }
  
  // 2. Get active invite code
  const invite = await db
    .selectFrom('invite_codes')
    .select(['code', 'created_at'])
    .where('group_id', '=', groupId)
    .where('revoked_at', 'is', null)
    .executeTakeFirst();
  
  if (!invite) {
    throw new NotFoundError('No active invite code found');
  }
  
  return {
    invite_code: invite.code,
    share_url: `https://getpursue.app/join/${invite.code}`,
    created_at: invite.created_at
  };
}
```

**Errors**:
- `401 Unauthorized`: User not authenticated
- `403 Forbidden`: User not a member of group
- `404 Not Found`: No active invite code exists (shouldn't happen if created on group creation)

---

### 2. Regenerate Invite Code

**Endpoint**: `POST /api/groups/:group_id/invite/regenerate`

**Purpose**: Create a new invite code and revoke the old one

**Authorization**: User must be admin or creator

**Request**:
```http
POST /api/groups/550e8400-e29b-41d4-a716-446655440000/invite/regenerate
Authorization: Bearer {access_token}
```

**Response (200 OK)**:
```json
{
  "invite_code": "PURSUE-B3N7Q9-R5T2W8",
  "share_url": "https://getpursue.app/join/PURSUE-B3N7Q9-R5T2W8",
  "created_at": "2026-02-03T14:22:00Z",
  "previous_code_revoked": "PURSUE-A7X9K2-M4P8L6"
}
```

**Server Logic**:
```typescript
async function regenerateInviteCode(groupId: string, userId: string) {
  // 1. Verify user is admin or creator
  const membership = await db
    .selectFrom('group_memberships')
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .where('role', 'in', ['admin', 'creator'])
    .executeTakeFirst();
  
  if (!membership) {
    throw new ForbiddenError('Only admins can regenerate invite codes');
  }
  
  return await db.transaction().execute(async (trx) => {
    // 2. Revoke old code
    const oldCode = await trx
      .updateTable('invite_codes')
      .set({ revoked_at: new Date() })
      .where('group_id', '=', groupId)
      .where('revoked_at', 'is', null)
      .returning('code')
      .executeTakeFirst();
    
    // 3. Generate new unique code
    let newCode: string;
    let isUnique = false;
    
    while (!isUnique) {
      newCode = generateInviteCode(); // PURSUE-XXXXXX-XXXXXX
      
      const existing = await trx
        .selectFrom('invite_codes')
        .where('code', '=', newCode)
        .executeTakeFirst();
      
      isUnique = !existing;
    }
    
    // 4. Insert new code
    const invite = await trx
      .insertInto('invite_codes')
      .values({
        group_id: groupId,
        code: newCode,
        created_by_user_id: userId,
        created_at: new Date(),
        revoked_at: null
      })
      .returning(['code', 'created_at'])
      .executeTakeFirst();
    
    // 5. Create activity log
    await trx
      .insertInto('group_activities')
      .values({
        group_id: groupId,
        user_id: userId,
        activity_type: 'invite_code_regenerated',
        metadata: { old_code: oldCode?.code, new_code: invite.code }
      })
      .execute();
    
    return {
      invite_code: invite.code,
      share_url: `https://getpursue.app/join/${invite.code}`,
      created_at: invite.created_at,
      previous_code_revoked: oldCode?.code || null
    };
  });
}

function generateInviteCode(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; // No confusing chars (0,O,1,I)
  const part1 = Array.from({ length: 6 }, () => 
    chars.charAt(Math.floor(Math.random() * chars.length))
  ).join('');
  const part2 = Array.from({ length: 6 }, () => 
    chars.charAt(Math.floor(Math.random() * chars.length))
  ).join('');
  
  return `PURSUE-${part1}-${part2}`;
}
```

**Errors**:
- `401 Unauthorized`: User not authenticated
- `403 Forbidden`: User is not admin or creator
- `404 Not Found`: Group doesn't exist

**Use Cases**:
- Security: Old invite code was shared publicly and needs to be invalidated
- Privacy: User wants to prevent specific people from joining
- Cleanup: Code was leaked or compromised

---

### 3. Join Group via Invite Code

**Endpoint**: `POST /api/groups/join`

**Purpose**: Request to join a group using an invite code. The user is added as a **pending** member; a group admin or creator must approve the request before the user becomes an active member.

**Authorization**: Authenticated user

**Request**:
```http
POST /api/groups/join
Authorization: Bearer {access_token}
Content-Type: application/json

{
  "invite_code": "PURSUE-A7X9K2-M4P8L6"
}
```

**Response (200 OK)**:
```json
{
  "status": "pending",
  "message": "Join request sent to group admins for approval",
  "group": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "name": "Morning Runners",
    "member_count": 1
  }
}
```

**Server Logic**:
```typescript
async function joinGroupViaInvite(inviteCode: string, userId: string) {
  // 1. Find active invite code (not revoked)
  const invite = await db
    .selectFrom('invite_codes')
    .innerJoin('groups', 'groups.id', 'invite_codes.group_id')
    .select([
      'invite_codes.group_id',
      'invite_codes.id as invite_id',
      'groups.name',
      'groups.description',
      'groups.icon_emoji',
      'groups.icon_color'
    ])
    .where('invite_codes.code', '=', inviteCode)
    .where('invite_codes.revoked_at', 'is', null)
    .executeTakeFirst();
  
  if (!invite) {
    throw new NotFoundError('Invalid or revoked invite code');
  }
  
  // 2. Check if user already member (any status)
  const existingMembership = await db
    .selectFrom('group_memberships')
    .where('group_id', '=', invite.group_id)
    .where('user_id', '=', userId)
    .executeTakeFirst();
  
  if (existingMembership) {
    throw new ConflictError('Already a member of this group');
  }
  
  // 3. Check resource limits (max 100 groups per user – count active memberships)
  const userGroupCount = await db
    .selectFrom('group_memberships')
    .where('user_id', '=', userId)
    .where('status', '=', 'active')
    .select(db.fn.count('id').as('count'))
    .executeTakeFirst();
  
  if (Number(userGroupCount?.count || 0) >= 100) {
    throw new ResourceLimitError('Maximum groups limit reached (100)');
  }
  
  // 4. Add user to group with status 'pending' (approval required)
  await db
    .insertInto('group_memberships')
    .values({
      group_id: invite.group_id,
      user_id: userId,
      role: 'member',
      status: 'pending',
      joined_at: new Date()
    })
    .execute();
  
  // 5. Create activity for join request (not member_joined – that happens on approve)
  await createGroupActivity(invite.group_id, ACTIVITY_TYPES.JOIN_REQUEST, userId);
  
  // 6. Get group and active member count for response
  const group = await db
    .selectFrom('groups')
    .select(['id', 'name'])
    .where('id', '=', invite.group_id)
    .executeTakeFirstOrThrow();
  
  const memberCountResult = await db
    .selectFrom('group_memberships')
    .select(db.fn.count('id').as('count'))
    .where('group_id', '=', invite.group_id)
    .where('status', '=', 'active')
    .executeTakeFirstOrThrow();
  
  // 7. Send FCM to admins/creators only (not all members) – "New Join Request"
  const displayName = await getUserDisplayName(userId);
  const adminDevices = await db
    .selectFrom('devices')
    .innerJoin('group_memberships', 'devices.user_id', 'group_memberships.user_id')
    .select(['devices.fcm_token'])
    .where('group_memberships.group_id', '=', invite.group_id)
    .where((eb) =>
      eb.or([
        eb('group_memberships.role', '=', 'admin'),
        eb('group_memberships.role', '=', 'creator'),
      ])
    )
    .execute();
  
  for (const device of adminDevices) {
    sendPushNotification(
      device.fcm_token,
      'New Join Request',
      `${displayName} wants to join ${group.name}`,
      { type: 'join_request', group_id: invite.group_id, user_id: userId }
    ).catch(logError);
  }
  
  return {
    status: 'pending',
    message: 'Join request sent to group admins for approval',
    group: {
      id: group.id,
      name: group.name,
      member_count: Number(memberCountResult.count),
    },
  };
}
```

**Errors**:
- `400 Bad Request`: Invalid request body
- `401 Unauthorized`: User not authenticated
- `404 Not Found`: Invite code doesn't exist or has been revoked
- `409 Conflict`: User is already a member
- `429 Too Many Requests`: User has reached max groups limit (100)

**Note**: After approval (via `POST /api/groups/:group_id/members/:user_id/approve`), the member becomes active and a `member_approved` activity is created; FCM may be sent to the approved user and to group members. Pending members do not count toward `member_count` and have restricted access until approved.

---

## Group Creation Flow

When a new group is created, an invite code is automatically generated.

**Updated Group Creation Logic**:

```typescript
async function createGroup(data: CreateGroupInput, userId: string) {
  return await db.transaction().execute(async (trx) => {
    // 1. Create group
    const group = await trx
      .insertInto('groups')
      .values({
        name: data.name,
        description: data.description,
        icon_emoji: data.icon_emoji,
        icon_color: data.icon_color,
        creator_user_id: userId
      })
      .returning(['id', 'name', 'created_at'])
      .executeTakeFirst();
    
    // 2. Add creator as member
    await trx
      .insertInto('group_memberships')
      .values({
        group_id: group.id,
        user_id: userId,
        role: 'creator'
      })
      .execute();
    
    // 3. Generate invite code
    let inviteCode: string;
    let isUnique = false;
    
    while (!isUnique) {
      inviteCode = generateInviteCode();
      const existing = await trx
        .selectFrom('invite_codes')
        .where('code', '=', inviteCode)
        .executeTakeFirst();
      isUnique = !existing;
    }
    
    await trx
      .insertInto('invite_codes')
      .values({
        group_id: group.id,
        code: inviteCode,
        created_by_user_id: userId
      })
      .execute();
    
    // 4. Create initial goals if provided
    if (data.initial_goals) {
      for (const goal of data.initial_goals) {
        await trx
          .insertInto('goals')
          .values({
            group_id: group.id,
            title: goal.title,
            cadence: goal.cadence,
            metric_type: goal.metric_type,
            created_by_user_id: userId
          })
          .execute();
      }
    }
    
    // 5. Create activity log
    await trx
      .insertInto('group_activities')
      .values({
        group_id: group.id,
        user_id: userId,
        activity_type: 'group_created',
        metadata: { invite_code: inviteCode }
      })
      .execute();
    
    return {
      ...group,
      invite_code: inviteCode,
      member_count: 1,
      user_role: 'creator'
    };
  });
}
```

---

## Mobile UI Updates

### Group Settings Screen

Add "Invite Code" section:

```kotlin
@Composable
fun GroupSettingsScreen(groupId: String) {
    var inviteCode by remember { mutableStateOf<String?>(null) }
    var showShareSheet by remember { mutableStateOf(false) }
    
    LaunchedEffect(groupId) {
        // Fetch invite code on screen load
        val response = apiClient.get("/api/groups/$groupId/invite")
        inviteCode = response.invite_code
    }
    
    Column {
        // ... other settings ...
        
        // Invite Code Section
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Invite Members",
                    style = MaterialTheme.typography.titleMedium
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // Invite code display
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = inviteCode ?: "Loading...",
                        style = MaterialTheme.typography.bodyLarge,
                        fontFamily = FontFamily.Monospace
                    )
                    
                    IconButton(onClick = {
                        clipboardManager.setText(AnnotatedString(inviteCode ?: ""))
                        showSnackbar("Invite code copied!")
                    }) {
                        Icon(Icons.Default.ContentCopy, "Copy")
                    }
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                // Share button
                Button(
                    onClick = { showShareSheet = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Share Invite Link")
                }
                
                // Regenerate button (admin only)
                if (userRole in listOf("admin", "creator")) {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    TextButton(
                        onClick = { showRegenerateDialog = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Regenerate Code")
                    }
                }
            }
        }
    }
    
    // Share sheet
    if (showShareSheet) {
        ShareSheet(
            url = "https://getpursue.app/join/$inviteCode",
            message = "Join my group on Pursue!"
        )
    }
}
```

---

## QR Codes

QR codes are **not** generated by the backend. The client (mobile or web) generates QR codes locally using the invite code or `share_url` from the GET invite / regenerate responses (e.g. encode `https://getpursue.app/join/{invite_code}`). This avoids an extra backend request and keeps the API simple.

---

## Testing Checklist

### Unit Tests
- ✅ `generateInviteCode()` creates valid format (PURSUE-XXXXXX-XXXXXX)
- ✅ Invite code uniqueness check works
- ✅ Only one active code per group constraint enforced
- ✅ Revoked codes cannot be used to join

### Integration Tests
- ✅ Creating group automatically generates invite code
- ✅ Getting invite code requires group membership
- ✅ Regenerating code revokes old code and creates new one
- ✅ Joining via invite code adds user as pending; approval makes them active
- ✅ Revoked invite codes return 404
- ✅ Already-member returns 409 conflict
- ✅ Resource limit (100 groups) is enforced

### End-to-End Tests
- ✅ User creates group → invite code generated
- ✅ User shares invite link → other user submits join request; admin approves → member is active
- ✅ Admin regenerates code → old code stops working
- ✅ Client can generate QR from invite code / share_url
- ✅ FCM notifications sent to admins on join request; to members on approval

---

## Migration Timeline

1. **Development**: Implement new schema and endpoints
2. **Testing**: Run full test suite with new system
3. **Staging Deploy**: Deploy to staging environment
4. **Migration**: Run migration script on production database
5. **Rollout**: Deploy new backend version
6. **Mobile Update**: Release mobile app with new invite UI
7. **Monitoring**: Watch for errors or issues

**Rollback Plan**: Keep migration rollback script ready for 7 days post-deployment

---

## Benefits of New Design

| Old System | New System |
|------------|------------|
| New code created every time | One permanent code per group |
| Database spam risk | No spam - single row per group |
| Complex expiration/max_uses logic | Simple: active or revoked |
| Codes can expire unexpectedly | Codes never expire |
| Users confused by multiple codes | One code, always works |
| Tracking usage adds overhead | No tracking needed |

**Cost Savings**: ~95% reduction in `invite_codes` table size over time

**UX Improvement**: Users never worry about expired links

**Security**: Still maintains regeneration for compromised codes
