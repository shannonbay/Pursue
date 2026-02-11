-- Migration: Harden delete account for privacy compliance
-- Date: 2026-02-11
--
-- 1. Fix FK constraints so PostgreSQL handles cleanup automatically:
--    - goals.created_by_user_id → ON DELETE SET NULL
--    - goals.deleted_by_user_id → ON DELETE SET NULL
--    - invite_codes.created_by_user_id → ON DELETE SET NULL (nullable)
--
-- 2. Add delete_user_data() SQL function for reliable, auditable user deletion.

-- Fix goals.created_by_user_id FK
ALTER TABLE goals DROP CONSTRAINT IF EXISTS goals_created_by_user_id_fkey;
ALTER TABLE goals ADD CONSTRAINT goals_created_by_user_id_fkey
  FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

-- Fix goals.deleted_by_user_id FK
ALTER TABLE goals DROP CONSTRAINT IF EXISTS goals_deleted_by_user_id_fkey;
ALTER TABLE goals ADD CONSTRAINT goals_deleted_by_user_id_fkey
  FOREIGN KEY (deleted_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

-- Fix invite_codes.created_by_user_id FK
-- Make nullable so invite codes survive user deletion (code belongs to the group, not the user).
-- Note: production constraint is named invite_codes_new_created_by_user_id_fkey
-- because migration 003 created invite_codes_new then renamed to invite_codes.
-- PostgreSQL preserves the original constraint name after ALTER TABLE RENAME.
ALTER TABLE invite_codes ALTER COLUMN created_by_user_id DROP NOT NULL;
ALTER TABLE invite_codes DROP CONSTRAINT IF EXISTS invite_codes_new_created_by_user_id_fkey;
ALTER TABLE invite_codes DROP CONSTRAINT IF EXISTS invite_codes_created_by_user_id_fkey;
ALTER TABLE invite_codes ADD CONSTRAINT invite_codes_created_by_user_id_fkey
  FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

-- SQL function for user deletion (privacy-critical path)
-- Deletes the user row; FK constraints handle all related data:
--   CASCADE: auth_providers, refresh_tokens, password_reset_tokens, devices,
--            group_memberships, progress_entries, user_subscriptions,
--            subscription_downgrade_history
--   SET NULL: goals.created_by_user_id, goals.deleted_by_user_id,
--             group_activities.user_id, invite_codes.created_by_user_id
CREATE OR REPLACE FUNCTION delete_user_data(p_user_id UUID)
RETURNS VOID AS $$
BEGIN
  DELETE FROM users WHERE id = p_user_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'USER_NOT_FOUND: %', p_user_id;
  END IF;
END;
$$ LANGUAGE plpgsql;
