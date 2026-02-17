-- Weekly Group Recap Feature
-- Adds deduplication table and opt-out toggle for weekly recap notifications

-- Deduplication table to prevent double-sends
CREATE TABLE weekly_recaps_sent (
  group_id    UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  week_end    DATE NOT NULL,  -- Sunday date
  sent_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  PRIMARY KEY (group_id, week_end)
);

CREATE INDEX idx_weekly_recaps_sent_group ON weekly_recaps_sent(group_id);
CREATE INDEX idx_weekly_recaps_sent_week_end ON weekly_recaps_sent(week_end);

COMMENT ON TABLE weekly_recaps_sent IS 'Tracks which weekly recaps have been sent to prevent duplicate notifications';
COMMENT ON COLUMN weekly_recaps_sent.week_end IS 'Sunday date marking the end of the recap week (YYYY-MM-DD)';

-- Add opt-out toggle to group memberships
ALTER TABLE group_memberships
  ADD COLUMN weekly_recap_enabled BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN group_memberships.weekly_recap_enabled IS 'Whether the member wants to receive weekly recap notifications for this group';
