-- User notifications: personal inbox of events directed at the user
-- Reaction received, nudge received, membership approved/rejected, promoted, removed, milestone

CREATE TABLE user_notifications (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type              VARCHAR(50) NOT NULL,
  actor_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
  group_id          UUID REFERENCES groups(id) ON DELETE CASCADE,
  goal_id           UUID REFERENCES goals(id) ON DELETE CASCADE,
  progress_entry_id UUID REFERENCES progress_entries(id) ON DELETE CASCADE,
  metadata          JSONB,
  is_read           BOOLEAN NOT NULL DEFAULT FALSE,
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_notifications_user ON user_notifications(user_id, created_at DESC);
CREATE INDEX idx_user_notifications_unread ON user_notifications(user_id) WHERE is_read = FALSE;

-- Milestone deduplication: track which milestones have been awarded to avoid duplicates
CREATE TABLE user_milestone_grants (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  milestone_key VARCHAR(100) NOT NULL,
  granted_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, milestone_key)
);
