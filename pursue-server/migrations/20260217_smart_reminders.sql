-- Migration: 20260217_smart_reminders.sql
-- Description: Add Smart Reminders feature tables and users.timezone column

-- ============================================================
-- Add timezone column to users table (cached/last-known timezone)
-- ============================================================
ALTER TABLE users ADD COLUMN IF NOT EXISTS timezone VARCHAR(50) DEFAULT 'UTC';

COMMENT ON COLUMN users.timezone IS 'Cached timezone from last progress log. Used by smart reminders for background jobs. Updated when user logs progress with a different timezone.';

-- ============================================================
-- User logging patterns (cached for performance)
-- ============================================================
CREATE TABLE user_logging_patterns (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  -- -1 for general pattern, 0 (Sun) - 6 (Sat) for day-specific
  day_of_week INTEGER NOT NULL DEFAULT -1,
  typical_hour_start INTEGER NOT NULL, -- 0-23 (user's local time)
  typical_hour_end INTEGER NOT NULL,   -- 0-23 (user's local time)
  confidence_score DECIMAL(3,2) NOT NULL, -- 0.00 - 1.00
  sample_size INTEGER NOT NULL,
  last_calculated_at TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (user_id, goal_id, day_of_week)
);

CREATE INDEX idx_patterns_recalc 
  ON user_logging_patterns(last_calculated_at) 
  WHERE confidence_score > 0.3;

COMMENT ON COLUMN user_logging_patterns.day_of_week 
  IS '-1 for general pattern, 0-6 for day-specific. Using -1 instead of NULL because NULL cannot participate in composite primary keys reliably.';

COMMENT ON COLUMN user_logging_patterns.confidence_score 
  IS 'Based on sample size and consistency. <0.3 = unreliable, use defaults.';

-- ============================================================
-- Reminder history (prevent spam, track effectiveness)
-- ============================================================
CREATE TABLE reminder_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  reminder_tier VARCHAR(20) NOT NULL, -- 'gentle', 'supportive', 'last_chance'
  sent_at TIMESTAMPTZ DEFAULT NOW(),
  sent_at_local_date DATE NOT NULL, -- User's local date when sent (for "today" checks)
  was_effective BOOLEAN, -- Did user log the same day after this reminder?
  social_context JSONB,
  user_timezone VARCHAR(50) NOT NULL
);

CREATE INDEX idx_reminder_history_user_goal_date
  ON reminder_history(user_id, goal_id, sent_at_local_date DESC);

CREATE INDEX idx_reminder_history_user_date
  ON reminder_history(user_id, sent_at_local_date)
  WHERE was_effective IS NULL;

CREATE INDEX idx_reminder_history_effectiveness 
  ON reminder_history(reminder_tier, was_effective) 
  WHERE was_effective IS NOT NULL;

COMMENT ON COLUMN reminder_history.was_effective 
  IS 'TRUE if user logged the goal on the same local date after this reminder was sent. NULL if not yet evaluated.';

COMMENT ON COLUMN reminder_history.sent_at_local_date
  IS 'The user local date when the reminder was sent. Critical for correct "already reminded today" checks across timezones.';

-- ============================================================
-- User reminder preferences (per-goal control)
-- ============================================================
CREATE TABLE user_reminder_preferences (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  enabled BOOLEAN DEFAULT TRUE,
  mode VARCHAR(20) DEFAULT 'smart', -- 'smart', 'fixed', 'disabled'
  fixed_hour INTEGER, -- 0-23 (user local time), used if mode='fixed'
  aggressiveness VARCHAR(20) DEFAULT 'balanced', -- 'gentle', 'balanced', 'persistent'
  quiet_hours_start INTEGER, -- 0-23, NULL if no quiet hours
  quiet_hours_end INTEGER,   -- 0-23, NULL if no quiet hours
  last_modified_at TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (user_id, goal_id)
);

CREATE INDEX idx_preferences_enabled 
  ON user_reminder_preferences(user_id) 
  WHERE enabled = TRUE;

COMMENT ON COLUMN user_reminder_preferences.mode 
  IS 'smart = pattern-based, fixed = same time daily, disabled = never remind';

COMMENT ON COLUMN user_reminder_preferences.aggressiveness 
  IS 'gentle = last_chance only, balanced = all tiers, persistent = shorter delays between tiers';
