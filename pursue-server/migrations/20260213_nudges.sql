-- Nudges: motivational push notifications from group members to teammates
-- One nudge per sender→recipient per calendar day (sender's local date)

CREATE TABLE nudges (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sender_user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id         UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  goal_id          UUID REFERENCES goals(id) ON DELETE SET NULL,
  sent_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  sender_local_date DATE NOT NULL
);

-- Enforce one nudge per sender→recipient per sender-local-date
CREATE UNIQUE INDEX idx_nudges_daily_limit
  ON nudges(sender_user_id, recipient_user_id, sender_local_date);

-- For querying "has this user been nudged recently" on the recipient side
CREATE INDEX idx_nudges_recipient
  ON nudges(recipient_user_id, sent_at DESC);

-- For querying "how many nudges has this sender sent today"
CREATE INDEX idx_nudges_sender_daily
  ON nudges(sender_user_id, sender_local_date);
