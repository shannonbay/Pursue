-- Migration: 20260218_add_challenges.sql
-- Description: Add Challenges core backend support

BEGIN;

-- =========================================================
-- Groups: challenge metadata
-- =========================================================

ALTER TABLE groups
  ADD COLUMN IF NOT EXISTS is_challenge BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE groups
  ADD COLUMN IF NOT EXISTS challenge_start_date DATE;

ALTER TABLE groups
  ADD COLUMN IF NOT EXISTS challenge_end_date DATE;

ALTER TABLE groups
  ADD COLUMN IF NOT EXISTS challenge_template_id UUID;

ALTER TABLE groups
  ADD COLUMN IF NOT EXISTS challenge_status VARCHAR(20) DEFAULT NULL;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'chk_groups_challenge_fields'
  ) THEN
    ALTER TABLE groups
      ADD CONSTRAINT chk_groups_challenge_fields CHECK (
        (is_challenge = FALSE AND challenge_start_date IS NULL AND challenge_end_date IS NULL AND challenge_status IS NULL)
        OR
        (is_challenge = TRUE AND challenge_start_date IS NOT NULL AND challenge_end_date IS NOT NULL AND challenge_status IS NOT NULL)
      );
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'chk_groups_challenge_dates'
  ) THEN
    ALTER TABLE groups
      ADD CONSTRAINT chk_groups_challenge_dates CHECK (
        challenge_end_date IS NULL OR challenge_end_date >= challenge_start_date
      );
  END IF;
END $$;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'chk_groups_challenge_status'
  ) THEN
    ALTER TABLE groups
      ADD CONSTRAINT chk_groups_challenge_status CHECK (
        challenge_status IS NULL OR challenge_status IN ('upcoming', 'active', 'completed', 'cancelled')
      );
  END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_groups_challenge_status
  ON groups(is_challenge, challenge_status)
  WHERE is_challenge = TRUE;

CREATE INDEX IF NOT EXISTS idx_groups_challenge_template
  ON groups(challenge_template_id)
  WHERE challenge_template_id IS NOT NULL;

-- =========================================================
-- Challenge templates
-- =========================================================

CREATE TABLE IF NOT EXISTS challenge_templates (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  slug VARCHAR(100) UNIQUE NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  icon_emoji VARCHAR(10) NOT NULL,
  duration_days INTEGER NOT NULL,
  category VARCHAR(50) NOT NULL,
  difficulty VARCHAR(20) NOT NULL DEFAULT 'moderate',
  is_featured BOOLEAN NOT NULL DEFAULT FALSE,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_challenge_templates_category
  ON challenge_templates(category, sort_order);

CREATE INDEX IF NOT EXISTS idx_challenge_templates_featured
  ON challenge_templates(is_featured, sort_order)
  WHERE is_featured = TRUE;

CREATE TABLE IF NOT EXISTS challenge_template_goals (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  template_id UUID NOT NULL REFERENCES challenge_templates(id) ON DELETE CASCADE,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  cadence VARCHAR(20) NOT NULL,
  metric_type VARCHAR(20) NOT NULL,
  target_value DECIMAL(10,2),
  unit VARCHAR(50),
  sort_order INTEGER NOT NULL DEFAULT 0,
  UNIQUE(template_id, sort_order)
);

CREATE INDEX IF NOT EXISTS idx_template_goals_template
  ON challenge_template_goals(template_id, sort_order);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint
    WHERE conname = 'fk_groups_challenge_template'
  ) THEN
    ALTER TABLE groups
      ADD CONSTRAINT fk_groups_challenge_template
      FOREIGN KEY (challenge_template_id)
      REFERENCES challenge_templates(id)
      ON DELETE SET NULL;
  END IF;
END $$;

-- =========================================================
-- Seed templates (full catalog from challenges spec)
-- =========================================================

WITH seed_templates (slug, title, description, icon_emoji, duration_days, category, difficulty, is_featured, sort_order) AS (
  VALUES
    -- Fitness
    ('10k-steps-daily', '10K Steps Daily', 'Hit 10,000 steps every day for 30 days.', '🚶', 30, 'fitness', 'moderate', TRUE, 1),
    ('couch-to-5k', 'Couch to 5K', 'Build a running habit by showing up every day.', '🏃', 60, 'fitness', 'moderate', TRUE, 2),
    ('100-pushups-a-day', '100 Pushups a Day', 'Push your consistency with 100 pushups daily.', '💪', 30, 'fitness', 'hard', FALSE, 3),
    ('30-day-yoga', '30-Day Yoga', 'Complete one yoga session each day.', '🧘', 30, 'fitness', 'easy', FALSE, 4),
    ('10k-steps-week', '10K Steps for a Week', 'A short 7-day movement sprint.', '👟', 7, 'fitness', 'easy', FALSE, 5),
    ('plank-challenge', 'Plank Challenge', 'Daily planks with weekly progression.', '🧱', 30, 'fitness', 'moderate', FALSE, 6),
    ('morning-workout', 'Morning Workout', 'Exercise before 9am for 21 days.', '🌅', 21, 'fitness', 'moderate', FALSE, 7),

    -- Reading
    ('read-bible-one-year', 'Read the Bible in a Year', 'Read through the Bible in 365 days.', '📖', 365, 'reading', 'moderate', TRUE, 1),
    ('read-nt-260-days', 'Read the New Testament in 260 Days', 'Read one chapter per day.', '📚', 260, 'reading', 'easy', FALSE, 2),
    ('book-a-month', 'Book a Month', 'Read for 30 minutes each day.', '📘', 30, 'reading', 'easy', FALSE, 3),
    ('50-books-year', '50 Books in a Year', 'Sustain daily reading and weekly book progress.', '📗', 365, 'reading', 'hard', FALSE, 4),
    
    -- Wellness
    ('21-day-meditation', '21-Day Meditation', 'Meditate 10 minutes daily.', '🧠', 21, 'wellness', 'easy', TRUE, 1),
    ('gratitude-journal-30', '30-Day Gratitude Journal', 'Write daily gratitude entries.', '📝', 30, 'wellness', 'easy', FALSE, 2),
    ('no-social-30', 'No Social Media for 30 Days', 'Stay off social media each day.', '📵', 30, 'wellness', 'hard', FALSE, 3),
    ('8-glasses-water', '8 Glasses of Water', 'Drink 8 glasses each day.', '💧', 30, 'wellness', 'easy', FALSE, 4),
    ('7-hours-sleep', '7 Hours of Sleep', 'Hit at least 7 hours every night.', '😴', 30, 'wellness', 'moderate', FALSE, 5),
    ('digital-detox-weekend', 'Digital Detox Weekend', 'Two days fully off unnecessary screens.', '📴', 2, 'wellness', 'moderate', FALSE, 6),
    ('cold-shower-30', 'Cold Shower Challenge', 'Take a cold shower every day.', '🧊', 30, 'wellness', 'hard', FALSE, 7),

    -- Diet
    ('30-day-no-sugar', '30-Day No Sugar Challenge', 'Avoid added sugar every day for 30 days.', '🍬', 30, 'diet', 'hard', TRUE, 1),
    ('whole30', 'Whole30', 'Eat whole foods only each day.', '🥗', 30, 'diet', 'hard', FALSE, 2),
    ('meatless-monday-month', 'Meatless Monday for a Month', 'No meat on Mondays this month.', '🌱', 30, 'diet', 'easy', FALSE, 3),
    ('cook-at-home', 'Cook at Home', 'Cook at home each day.', '🍳', 30, 'diet', 'moderate', FALSE, 4),
    ('no-alcohol-30', 'No Alcohol for 30 Days', 'Skip alcohol for 30 days.', '🚫', 30, 'diet', 'moderate', FALSE, 5),

    -- Productivity
    ('30-days-coding', '30 Days of Coding', 'Write code every day.', '💻', 30, 'productivity', 'moderate', TRUE, 1),
    ('language-learning-sprint', 'Language Learning Sprint', 'Study language for 15 minutes daily.', '🗣️', 30, 'productivity', 'easy', FALSE, 2),
    ('wake-up-5am', 'Wake Up at 5am', 'Wake by 5am for 21 days.', '⏰', 21, 'productivity', 'hard', FALSE, 3),
    ('inbox-zero-month', 'Inbox Zero for a Month', 'Clear your inbox every day.', '📥', 30, 'productivity', 'moderate', FALSE, 4),
    ('daily-journaling', 'Daily Journaling', 'Write in your journal daily.', '📔', 30, 'productivity', 'easy', FALSE, 5),

    -- Finance
    ('no-spend-30', 'No Spend Challenge', 'No unnecessary purchases each day.', '💸', 30, 'finance', 'hard', FALSE, 1),
    ('save-5-daily', 'Save $5 a Day', 'Save $5 each day.', '💵', 30, 'finance', 'easy', FALSE, 2),
    ('track-every-dollar', 'Track Every Dollar', 'Log every expense each day.', '🧾', 30, 'finance', 'moderate', FALSE, 3),

    -- Social
    ('reach-out-daily', 'Reach Out Daily', 'Contact one person each day.', '🤝', 30, 'social', 'easy', FALSE, 1),
    ('30-days-kindness', '30 Days of Kindness', 'Perform one kind act daily.', '💛', 30, 'social', 'easy', FALSE, 2),
    ('phone-call-day', 'Phone Call a Day', 'Make one phone call each day.', '📞', 21, 'social', 'moderate', FALSE, 3)
)
INSERT INTO challenge_templates (slug, title, description, icon_emoji, duration_days, category, difficulty, is_featured, sort_order)
SELECT slug, title, description, icon_emoji, duration_days, category, difficulty, is_featured, sort_order
FROM seed_templates
ON CONFLICT (slug) DO UPDATE SET
  title = EXCLUDED.title,
  description = EXCLUDED.description,
  icon_emoji = EXCLUDED.icon_emoji,
  duration_days = EXCLUDED.duration_days,
  category = EXCLUDED.category,
  difficulty = EXCLUDED.difficulty,
  is_featured = EXCLUDED.is_featured,
  sort_order = EXCLUDED.sort_order,
  updated_at = NOW();

WITH seed_goals (template_slug, title, description, cadence, metric_type, target_value, unit, sort_order) AS (
  VALUES
    ('10k-steps-daily', '10,000 steps', 'Walk at least 10,000 steps today.', 'daily', 'numeric', 10000, 'steps', 0),
    ('couch-to-5k', 'Run today', 'Complete your run session for the day.', 'daily', 'binary', NULL, NULL, 0),
    ('100-pushups-a-day', '100 pushups', 'Complete 100 pushups today.', 'daily', 'numeric', 100, 'pushups', 0),
    ('10k-steps-week', '10,000 steps', 'Hit 10,000 steps today.', 'daily', 'numeric', 10000, 'steps', 0),
    ('plank-challenge', 'Plank hold', 'Hold a plank today.', 'daily', 'duration', 60, 'seconds', 0),
    ('morning-workout', 'Workout before 9am', 'Finish a workout before 9am.', 'daily', 'binary', NULL, NULL, 0),

    ('read-bible-one-year', 'Read 3 chapters', 'Read 3 chapters today.', 'daily', 'numeric', 3, 'chapters', 0),
    ('read-nt-260-days', 'Read 1 chapter', 'Read 1 chapter today.', 'daily', 'numeric', 1, 'chapters', 0),
    ('book-a-month', 'Read 30 minutes', 'Read for at least 30 minutes.', 'daily', 'duration', 30, 'minutes', 0),
    ('50-books-year', 'Read today', 'Do your daily reading session.', 'daily', 'binary', NULL, NULL, 0),
    ('50-books-year', 'Finish 1 book', 'Complete one book this week.', 'weekly', 'numeric', 1, 'books', 1),

    ('gratitude-journal-30', 'Gratitude entry', 'Write your gratitude list today.', 'daily', 'binary', NULL, NULL, 0),
    ('no-social-30', 'No social media today', 'Avoid social media for the day.', 'daily', 'binary', NULL, NULL, 0),
    ('8-glasses-water', '8 glasses of water', 'Drink 8 glasses of water today.', 'daily', 'numeric', 8, 'glasses', 0),
    ('7-hours-sleep', '7+ hours sleep', 'Sleep at least 7 hours.', 'daily', 'numeric', 7, 'hours', 0),
    ('digital-detox-weekend', 'Stay off screens', 'Stay off non-essential screens today.', 'daily', 'binary', NULL, NULL, 0),
    ('cold-shower-30', 'Cold shower', 'Take a cold shower today.', 'daily', 'binary', NULL, NULL, 0),

    ('30-day-no-sugar', 'No added sugar today', 'Avoid all added sugar today.', 'daily', 'binary', NULL, NULL, 0),
    ('whole30', 'Whole foods only', 'Eat whole foods only today.', 'daily', 'binary', NULL, NULL, 0),
    ('cook-at-home', 'Cooked at home', 'Cook at home today.', 'daily', 'binary', NULL, NULL, 0),
    ('no-alcohol-30', 'No alcohol today', 'Avoid alcohol for the day.', 'daily', 'binary', NULL, NULL, 0),

    ('30-days-coding', 'Write code today', 'Ship at least one coding session today.', 'daily', 'binary', NULL, NULL, 0),
    ('language-learning-sprint', 'Study 15 minutes', 'Practice your language for 15 minutes.', 'daily', 'duration', 15, 'minutes', 0),
    ('wake-up-5am', 'Wake by 5am', 'Wake up by 5am.', 'daily', 'binary', NULL, NULL, 0),
    ('inbox-zero-month', 'Inbox cleared', 'Reach inbox zero today.', 'daily', 'binary', NULL, NULL, 0),
    ('daily-journaling', 'Journal entry', 'Write a journal entry today.', 'daily', 'binary', NULL, NULL, 0),

    ('no-spend-30', 'No unnecessary purchases', 'Avoid non-essential spending today.', 'daily', 'binary', NULL, NULL, 0),
    ('save-5-daily', 'Saved $5', 'Save at least $5 today.', 'daily', 'binary', NULL, NULL, 0),
    ('track-every-dollar', 'Tracked all expenses', 'Log all expenses today.', 'daily', 'binary', NULL, NULL, 0),

    ('reach-out-daily', 'Reached out to someone', 'Contact a friend or family member.', 'daily', 'binary', NULL, NULL, 0),
    ('30-days-kindness', 'One kind act', 'Do one intentional kind act.', 'daily', 'binary', NULL, NULL, 0),
    ('phone-call-day', 'Made a phone call', 'Call at least one person.', 'daily', 'binary', NULL, NULL, 0)
)
INSERT INTO challenge_template_goals (template_id, title, description, cadence, metric_type, target_value, unit, sort_order)
SELECT t.id, g.title, g.description, g.cadence, g.metric_type, g.target_value, g.unit, g.sort_order
FROM seed_goals g
JOIN challenge_templates t ON t.slug = g.template_slug
ON CONFLICT (template_id, sort_order) DO UPDATE SET
  title = EXCLUDED.title,
  description = EXCLUDED.description,
  cadence = EXCLUDED.cadence,
  metric_type = EXCLUDED.metric_type,
  target_value = EXCLUDED.target_value,
  unit = EXCLUDED.unit;

COMMIT;
