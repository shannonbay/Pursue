-- Activity reactions: emoji reactions on activity feed entries
-- One reaction per user per activity (can replace emoji, cannot stack multiple)

CREATE TABLE activity_reactions (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  activity_id UUID NOT NULL REFERENCES group_activities(id) ON DELETE CASCADE,
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji       VARCHAR(10) NOT NULL,
  created_at  TIMESTAMPTZ DEFAULT NOW(),
  CONSTRAINT uq_reaction_user_activity UNIQUE (activity_id, user_id)
);

CREATE INDEX idx_reactions_activity ON activity_reactions(activity_id);
CREATE INDEX idx_reactions_user ON activity_reactions(user_id);
