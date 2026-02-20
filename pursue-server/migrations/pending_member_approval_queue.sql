-- Migration: Add Pending Member Approval Queue
-- Version: 001_add_member_approval_queue
-- Created: 2026-02-02
-- Description: Adds status column to group_memberships and updates activity types

-- ============================================================================
-- STEP 1: Add status column to group_memberships
-- ============================================================================

-- Add status column with default 'active' to preserve existing behavior
ALTER TABLE group_memberships 
ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';

-- Add check constraint to ensure valid status values
ALTER TABLE group_memberships 
ADD CONSTRAINT group_memberships_status_check 
CHECK (status IN ('pending', 'active', 'declined'));

-- Add comment for documentation
COMMENT ON COLUMN group_memberships.status IS 'Member status: pending (awaiting approval), active (approved), declined (rejected)';

-- ============================================================================
-- STEP 2: Create partial index for efficient pending member queries
-- ============================================================================

-- Partial index for fast lookup of pending members by group
-- This index is only used when querying pending members, keeping it small and efficient
CREATE INDEX idx_memberships_pending 
ON group_memberships(group_id, user_id, joined_at) 
WHERE status = 'pending';

-- Add comment for documentation
COMMENT ON INDEX idx_memberships_pending IS 'Partial index for efficient pending member queries. Only indexes rows where status=pending.';

-- ============================================================================
-- STEP 3: Update existing active members to have explicit 'active' status
-- ============================================================================

-- All existing memberships should be 'active' (this is redundant due to default,
-- but explicit for clarity and to ensure data consistency)
UPDATE group_memberships 
SET status = 'active' 
WHERE status = 'active';

-- Verify: Check that all existing memberships have status 'active'
-- This should return 0 rows
DO $$
DECLARE
    non_active_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO non_active_count
    FROM group_memberships 
    WHERE status != 'active';
    
    IF non_active_count > 0 THEN
        RAISE EXCEPTION 'Migration validation failed: Found % non-active memberships in existing data', non_active_count;
    END IF;
    
    RAISE NOTICE 'Migration validation passed: All existing memberships are active';
END $$;

-- ============================================================================
-- STEP 4: Update group_activities table to support new activity types
-- ============================================================================

-- The group_activities table already stores activity_type as VARCHAR(50),
-- so no schema change needed. Just documenting the new activity types:

COMMENT ON COLUMN group_activities.activity_type IS 
'Activity type: progress_logged, join_request (pending approval), member_approved (admin approved join), member_declined (admin declined join), member_joined (legacy auto-join), member_left, member_promoted, member_removed, goal_added, goal_archived, group_renamed';

-- ============================================================================
-- STEP 5: Create helper function for pending member count (optional)
-- ============================================================================

-- Function to get count of pending members for a group
-- Useful for analytics and UI badge counts
CREATE OR REPLACE FUNCTION get_pending_member_count(p_group_id UUID)
RETURNS INTEGER
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    pending_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO pending_count
    FROM group_memberships
    WHERE group_id = p_group_id
    AND status = 'pending';
    
    RETURN pending_count;
END;
$$;

COMMENT ON FUNCTION get_pending_member_count IS 'Returns count of pending members for a given group';

-- ============================================================================
-- STEP 6: Create helper function to check if user is admin (updated)
-- ============================================================================

-- Updated function that only considers active members
-- Replaces any existing version
CREATE OR REPLACE FUNCTION is_group_admin(p_user_id UUID, p_group_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    user_role VARCHAR(20);
BEGIN
    SELECT role INTO user_role
    FROM group_memberships
    WHERE user_id = p_user_id
    AND group_id = p_group_id
    AND status = 'active'  -- Important: Only active members
    LIMIT 1;
    
    RETURN user_role IN ('creator', 'admin');
END;
$$;

COMMENT ON FUNCTION is_group_admin IS 'Returns true if user is an active admin or creator of the group';

-- ============================================================================
-- STEP 7: Add indexes for common query patterns
-- ============================================================================

-- Index for finding all pending/declined requests by user
-- Useful for "My Pending Requests" screen or user history
CREATE INDEX idx_memberships_user_status 
ON group_memberships(user_id, status, joined_at DESC)
WHERE status IN ('pending', 'declined');

COMMENT ON INDEX idx_memberships_user_status IS 'Index for finding pending/declined requests by user';

-- ============================================================================
-- STEP 8: Create view for active group members (optional but recommended)
-- ============================================================================

-- View that only shows active members
-- Makes queries simpler and more maintainable
CREATE OR REPLACE VIEW active_group_members AS
SELECT 
    gm.id,
    gm.group_id,
    gm.user_id,
    gm.role,
    gm.joined_at,
    u.email,
    u.display_name,
    u.avatar_mime_type,
    CASE WHEN u.avatar_data IS NOT NULL THEN true ELSE false END AS has_avatar
FROM group_memberships gm
INNER JOIN users u ON gm.user_id = u.id
WHERE gm.status = 'active'
AND u.deleted_at IS NULL;

COMMENT ON VIEW active_group_members IS 'View of active group members with user details. Excludes pending and declined members.';

-- ============================================================================
-- STEP 9: Create view for pending members (optional but recommended)
-- ============================================================================

-- View that only shows pending members with user details
CREATE OR REPLACE VIEW pending_group_members AS
SELECT 
    gm.id,
    gm.group_id,
    gm.user_id,
    gm.joined_at AS requested_at,
    u.email,
    u.display_name,
    u.avatar_mime_type,
    CASE WHEN u.avatar_data IS NOT NULL THEN true ELSE false END AS has_avatar
FROM group_memberships gm
INNER JOIN users u ON gm.user_id = u.id
WHERE gm.status = 'pending'
AND u.deleted_at IS NULL;

COMMENT ON VIEW pending_group_members IS 'View of pending join requests with user details';

-- ============================================================================
-- STEP 10: Add trigger to prevent duplicate pending requests (optional)
-- ============================================================================

-- Trigger function to prevent multiple pending requests from same user to same group
CREATE OR REPLACE FUNCTION prevent_duplicate_pending_requests()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    existing_pending_count INTEGER;
BEGIN
    -- Only check if new status is 'pending'
    IF NEW.status = 'pending' THEN
        -- Check if user already has a pending request for this group
        SELECT COUNT(*) INTO existing_pending_count
        FROM group_memberships
        WHERE group_id = NEW.group_id
        AND user_id = NEW.user_id
        AND status = 'pending'
        AND id != NEW.id; -- Exclude the current row on UPDATE
        
        IF existing_pending_count > 0 THEN
            RAISE EXCEPTION 'User already has a pending request for this group'
                USING ERRCODE = '23505', -- unique_violation
                      DETAIL = 'user_id: ' || NEW.user_id || ', group_id: ' || NEW.group_id;
        END IF;
    END IF;
    
    RETURN NEW;
END;
$$;

-- Create trigger
DROP TRIGGER IF EXISTS check_duplicate_pending_requests ON group_memberships;
CREATE TRIGGER check_duplicate_pending_requests
BEFORE INSERT OR UPDATE ON group_memberships
FOR EACH ROW
EXECUTE FUNCTION prevent_duplicate_pending_requests();

COMMENT ON TRIGGER check_duplicate_pending_requests ON group_memberships IS 'Prevents duplicate pending requests from same user to same group';

-- ============================================================================
-- VERIFICATION QUERIES
-- ============================================================================

-- Run these queries to verify the migration was successful

-- 1. Check that status column exists and has correct values
DO $$
BEGIN
    RAISE NOTICE 'Checking status column...';
    IF EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_name = 'group_memberships' 
        AND column_name = 'status'
    ) THEN
        RAISE NOTICE '✓ Status column exists';
    ELSE
        RAISE EXCEPTION '✗ Status column missing';
    END IF;
END $$;

-- 2. Check that partial index exists
DO $$
BEGIN
    RAISE NOTICE 'Checking partial index...';
    IF EXISTS (
        SELECT 1 FROM pg_indexes 
        WHERE tablename = 'group_memberships' 
        AND indexname = 'idx_memberships_pending'
    ) THEN
        RAISE NOTICE '✓ Partial index exists';
    ELSE
        RAISE EXCEPTION '✗ Partial index missing';
    END IF;
END $$;

-- 3. Check that views exist
DO $$
BEGIN
    RAISE NOTICE 'Checking views...';
    IF EXISTS (SELECT 1 FROM pg_views WHERE viewname = 'active_group_members') THEN
        RAISE NOTICE '✓ active_group_members view exists';
    ELSE
        RAISE WARNING '✗ active_group_members view missing (optional)';
    END IF;
    
    IF EXISTS (SELECT 1 FROM pg_views WHERE viewname = 'pending_group_members') THEN
        RAISE NOTICE '✓ pending_group_members view exists';
    ELSE
        RAISE WARNING '✗ pending_group_members view missing (optional)';
    END IF;
END $$;

-- 4. Check constraint exists
DO $$
BEGIN
    RAISE NOTICE 'Checking status constraint...';
    IF EXISTS (
        SELECT 1 FROM pg_constraint 
        WHERE conname = 'group_memberships_status_check'
    ) THEN
        RAISE NOTICE '✓ Status check constraint exists';
    ELSE
        RAISE EXCEPTION '✗ Status check constraint missing';
    END IF;
END $$;

-- 5. Display summary statistics
DO $$
DECLARE
    total_memberships INTEGER;
    active_memberships INTEGER;
    pending_memberships INTEGER;
    declined_memberships INTEGER;
BEGIN
    SELECT COUNT(*) INTO total_memberships FROM group_memberships;
    SELECT COUNT(*) INTO active_memberships FROM group_memberships WHERE status = 'active';
    SELECT COUNT(*) INTO pending_memberships FROM group_memberships WHERE status = 'pending';
    SELECT COUNT(*) INTO declined_memberships FROM group_memberships WHERE status = 'declined';
    
    RAISE NOTICE '';
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Migration Summary:';
    RAISE NOTICE '========================================';
    RAISE NOTICE 'Total memberships: %', total_memberships;
    RAISE NOTICE 'Active: %', active_memberships;
    RAISE NOTICE 'Pending: %', pending_memberships;
    RAISE NOTICE 'Declined: %', declined_memberships;
    RAISE NOTICE '========================================';
END $$;

-- ============================================================================
-- ROLLBACK SCRIPT (in case migration needs to be reversed)
-- ============================================================================

-- Uncomment and run this section to rollback the migration
/*
-- Drop triggers
DROP TRIGGER IF EXISTS check_duplicate_pending_requests ON group_memberships;
DROP FUNCTION IF EXISTS prevent_duplicate_pending_requests();

-- Drop views
DROP VIEW IF EXISTS pending_group_members;
DROP VIEW IF EXISTS active_group_members;

-- Drop helper functions
DROP FUNCTION IF EXISTS is_group_admin(UUID, UUID);
DROP FUNCTION IF EXISTS get_pending_member_count(UUID);

-- Drop indexes
DROP INDEX IF EXISTS idx_memberships_user_status;
DROP INDEX IF EXISTS idx_memberships_pending;

-- Drop constraint
ALTER TABLE group_memberships DROP CONSTRAINT IF EXISTS group_memberships_status_check;

-- Remove status column
ALTER TABLE group_memberships DROP COLUMN IF EXISTS status;

-- Verify rollback
SELECT 'Rollback complete' AS message;
*/

-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================

SELECT 
    '✓ Migration 001_add_member_approval_queue completed successfully' AS status,
    NOW() AS completed_at;