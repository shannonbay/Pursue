-- Migration: Enable RLS on public tables
-- Version: 20260212_enable_rls_on_public_tables
-- Created: 2026-02-12
-- Description: Enables Row Level Security (RLS) on all public schema tables exposed to PostgREST.
--              Table owners and superusers bypass RLS, so the Express backend (using the DB
--              owner/service role) continues to work unchanged. This addresses Supabase security
--              warnings for tables in schemas exposed to PostgREST.

-- ============================================================================
-- Enable RLS on all public tables
-- ============================================================================

ALTER TABLE users ENABLE ROW LEVEL SECURITY;
ALTER TABLE auth_providers ENABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_tokens ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_consents ENABLE ROW LEVEL SECURITY;
ALTER TABLE devices ENABLE ROW LEVEL SECURITY;
ALTER TABLE groups ENABLE ROW LEVEL SECURITY;
ALTER TABLE group_memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE invite_codes ENABLE ROW LEVEL SECURITY;
ALTER TABLE goals ENABLE ROW LEVEL SECURITY;
ALTER TABLE progress_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE group_activities ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription_downgrade_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscription_transactions ENABLE ROW LEVEL SECURITY;

-- ============================================================================
-- VERIFICATION
-- ============================================================================

DO $$
DECLARE
  non_rls_count INTEGER;
BEGIN
  SELECT COUNT(*) INTO non_rls_count
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = 'public'
  AND c.relkind = 'r'
  AND c.relname IN (
    'users', 'auth_providers', 'refresh_tokens', 'password_reset_tokens',
    'user_consents', 'devices', 'groups', 'group_memberships', 'invite_codes',
    'goals', 'progress_entries', 'group_activities', 'user_subscriptions',
    'subscription_downgrade_history', 'subscription_transactions'
  )
  AND c.relrowsecurity = false;

  IF non_rls_count > 0 THEN
    RAISE EXCEPTION 'RLS verification failed: % table(s) still without RLS enabled', non_rls_count;
  END IF;

  RAISE NOTICE 'RLS enabled on all 15 public tables';
END $$;

-- ============================================================================
-- ROLLBACK SCRIPT (in case migration needs to be reversed)
-- ============================================================================

/*
ALTER TABLE users DISABLE ROW LEVEL SECURITY;
ALTER TABLE auth_providers DISABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens DISABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_tokens DISABLE ROW LEVEL SECURITY;
ALTER TABLE user_consents DISABLE ROW LEVEL SECURITY;
ALTER TABLE devices DISABLE ROW LEVEL SECURITY;
ALTER TABLE groups DISABLE ROW LEVEL SECURITY;
ALTER TABLE group_memberships DISABLE ROW LEVEL SECURITY;
ALTER TABLE invite_codes DISABLE ROW LEVEL SECURITY;
ALTER TABLE goals DISABLE ROW LEVEL SECURITY;
ALTER TABLE progress_entries DISABLE ROW LEVEL SECURITY;
ALTER TABLE group_activities DISABLE ROW LEVEL SECURITY;
ALTER TABLE user_subscriptions DISABLE ROW LEVEL SECURITY;
ALTER TABLE subscription_downgrade_history DISABLE ROW LEVEL SECURITY;
ALTER TABLE subscription_transactions DISABLE ROW LEVEL SECURITY;
*/

-- ============================================================================
-- MIGRATION COMPLETE
-- ============================================================================

SELECT
  'RLS enabled on 15 public tables' AS status,
  NOW() AS completed_at;
