-- Migration: 20260222_update_template_active_days.sql
-- Description: Set sensible active_days defaults on challenge template goals
--              where structured rest is a natural part of the activity.
--
-- Bitmask encoding (Sunday-first, matches JS Date.getDay()):
--   Bit 0 (1)  = Sunday
--   Bit 1 (2)  = Monday
--   Bit 2 (4)  = Tuesday
--   Bit 3 (8)  = Wednesday
--   Bit 4 (16) = Thursday
--   Bit 5 (32) = Friday
--   Bit 6 (64) = Saturday
--
-- Named patterns:
--   62  (0111110) = Mon–Fri  (Weekdays)
--   42  (0101010) = Mon, Wed, Fri
--   126 (1111110) = Mon–Sat  (Mon–Sat, Sunday rest)
--   NULL          = Every day (default, no change to behaviour)
--
-- Per spec §7:
--   Morning Workout  → Weekdays (Mon–Fri = 62)
--   Couch to 5K      → Mon, Wed, Fri (42) — rest days between runs
--   Plank Challenge  → Mon–Sat (126) — Sunday rest
--   All others       → NULL (every day, no change)

BEGIN;

-- Morning Workout goals → Weekdays only (Mon–Fri)
UPDATE challenge_template_goals
SET active_days = 62
WHERE template_id = (
    SELECT id FROM challenge_templates WHERE slug = 'morning-workout'
)
AND cadence = 'daily';

-- Couch to 5K goals → Mon, Wed, Fri (rest days between runs)
UPDATE challenge_template_goals
SET active_days = 42
WHERE template_id = (
    SELECT id FROM challenge_templates WHERE slug = 'couch-to-5k'
)
AND cadence = 'daily';

-- Plank Challenge goals → Mon–Sat (Sunday rest)
UPDATE challenge_template_goals
SET active_days = 126
WHERE template_id = (
    SELECT id FROM challenge_templates WHERE slug = 'plank-challenge'
)
AND cadence = 'daily';

COMMIT;
