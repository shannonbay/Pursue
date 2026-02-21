BEGIN;

-- Add active_days column to goals
-- Stored as a bitmask integer (0-127) where each bit represents a day:
--   Bit 0 (1)  = Sunday
--   Bit 1 (2)  = Monday
--   Bit 2 (4)  = Tuesday
--   Bit 3 (8)  = Wednesday
--   Bit 4 (16) = Thursday
--   Bit 5 (32) = Friday
--   Bit 6 (64) = Saturday
--
-- 127 (1111111) = every day
-- 62  (0111110) = Mon-Fri (weekdays)
-- 65  (1000001) = Sat+Sun (weekends)
-- NULL = every day (backward compatibility)

ALTER TABLE goals ADD COLUMN active_days INTEGER DEFAULT NULL;

ALTER TABLE goals ADD CONSTRAINT chk_active_days
  CHECK (active_days IS NULL OR (active_days >= 1 AND active_days <= 127));

ALTER TABLE goals ADD CONSTRAINT chk_active_days_cadence
  CHECK (active_days IS NULL OR cadence = 'daily');

-- Add active_days to challenge_template_goals
ALTER TABLE challenge_template_goals ADD COLUMN active_days INTEGER DEFAULT NULL;

ALTER TABLE challenge_template_goals ADD CONSTRAINT chk_template_goal_active_days
  CHECK (active_days IS NULL OR (active_days >= 1 AND active_days <= 127));

ALTER TABLE challenge_template_goals ADD CONSTRAINT chk_template_goal_active_days_cadence
  CHECK (active_days IS NULL OR cadence = 'daily');

COMMIT;
