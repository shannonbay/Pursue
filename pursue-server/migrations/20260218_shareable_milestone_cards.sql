-- Shareable Milestone Cards Feature (v1.1 enhancements)
-- Adds pre-rendered card metadata to milestone notifications,
-- referral token infrastructure, and milestone grant goal scoping support.

ALTER TABLE user_notifications
  ADD COLUMN IF NOT EXISTS shareable_card_data JSONB;

-- Partial index for efficient lookup of shareable milestone notifications
CREATE INDEX IF NOT EXISTS idx_user_notifications_shareable
  ON user_notifications(user_id, type)
  WHERE shareable_card_data IS NOT NULL;

COMMENT ON COLUMN user_notifications.shareable_card_data IS
  'Pre-rendered card metadata for shareable milestone notifications. NULL for non-shareable types.';

-- Stable opaque referral tokens for share attribution (avoid exposing user IDs in share URLs)
CREATE TABLE IF NOT EXISTS referral_tokens (
  token      VARCHAR(12) PRIMARY KEY,
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(user_id)
);

CREATE INDEX IF NOT EXISTS idx_referral_tokens_user
  ON referral_tokens(user_id);

-- Add optional goal reference to milestone grants so repeatable streak milestones can remain goal-scoped
ALTER TABLE user_milestone_grants
  ADD COLUMN IF NOT EXISTS goal_id UUID REFERENCES goals(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_user_milestone_grants_goal
  ON user_milestone_grants(goal_id);
