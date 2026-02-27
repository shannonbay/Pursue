-- Migration: Add covering index for Daily Pulse group member log status query
-- Date: 2026-02-27
--
-- The Daily Pulse widget (GET /api/groups/:group_id/members) needs to determine
-- which members have logged progress in the current period. The query joins
-- progress_entries through goals (to get group_id) and filters by period_start.
--
-- This covering index allows an index-only scan when aggregating per-user
-- log status across all goals in a group for a given period.

CREATE INDEX IF NOT EXISTS idx_progress_goal_period_user
  ON progress_entries(goal_id, period_start, user_id, logged_at DESC);
