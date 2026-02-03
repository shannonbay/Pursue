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

/*
### Rollback Migration

**File**: `migrations/003_simplify_invite_codes_rollback.sql`

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
*/