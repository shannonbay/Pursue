-- Migration: Add indexes for member progress endpoint keyset pagination
-- Date: 2026-02-13

-- Index for efficient keyset pagination on user's progress entries
-- Used by GET /api/groups/:group_id/members/:user_id/progress
-- Orders by (logged_at DESC, id DESC) for cursor-based pagination
CREATE INDEX IF NOT EXISTS idx_progress_user_logged_at_id 
  ON progress_entries(user_id, logged_at DESC, id DESC);

-- Index for goal summaries aggregation query
-- Helps filter progress entries by goal and user within a date range
CREATE INDEX IF NOT EXISTS idx_progress_goal_user_period 
  ON progress_entries(goal_id, user_id, period_start);
