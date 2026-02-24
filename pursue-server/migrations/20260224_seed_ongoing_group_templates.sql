BEGIN;

-- Seed ongoing group templates (is_challenge = FALSE) and two new challenge
-- templates (is_challenge = TRUE) defined in ongoing-group-templates-spec.md.
--
-- Idempotent: ON CONFLICT (slug) DO NOTHING on templates, and goal inserts
-- only run when the template CTE returns a row (i.e. the template was just
-- inserted). Re-running is safe.

-- ─── FITNESS ──────────────────────────────────────────────────────────────────

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'running-club', 'Running Club',
    'Log your runs, share your routes, and keep each other moving. No end date — just consistent miles.',
    '🏃', 'res://drawable/ic_icon_running', 'fitness', 'moderate', TRUE, FALSE, NULL, 'group', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Run today',        NULL, 'daily',  'binary',  NULL, NULL, NULL,  0 FROM t
UNION ALL
SELECT id, 'Weekly distance',  NULL, 'weekly', 'numeric', NULL, 20,   'km',  1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'gym-buddies', 'Gym Buddies',
    'Show up, log it, hold each other accountable. The group that trains together, stays together.',
    '💪', 'res://drawable/ic_icon_planking', 'fitness', 'moderate', FALSE, FALSE, NULL, 'group', 2
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Hit the gym', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'strength-training', 'Strength Training',
    'Get strong together. Log your sessions, track your lifts, and keep each other consistent.',
    '🏋️', 'res://drawable/ic_icon_strength', 'fitness', 'moderate', FALSE, FALSE, NULL, 'group', 3
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Strength session done', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'family-fitness', 'Family Fitness',
    'Get the whole family moving. Track steps, workouts, or whatever active thing you''re doing together.',
    '👨‍👩‍👧', NULL, 'fitness', 'moderate', FALSE, FALSE, NULL, 'group', 4
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Active for 30 minutes', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'nature-walks', 'Nature Walks',
    'Step outside, breathe it in, log it. A gentle accountability group for people who want to make time in nature a regular habit.',
    '🌿', 'res://drawable/ic_icon_walking', 'fitness', 'moderate', FALSE, FALSE, NULL, 'group', 5
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Walk outside today', NULL, 'daily',  'binary',   NULL, NULL, NULL,      0 FROM t
UNION ALL
SELECT id, 'Time outdoors',      NULL, 'weekly', 'duration', NULL, 90,   'minutes', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'daily-steps', 'Daily Steps',
    '10,000 steps is the challenge. This is the lifestyle. A permanent group for people who log their steps every day and want others doing the same.',
    '👟', 'res://drawable/ic_icon_steps', 'fitness', 'moderate', FALSE, FALSE, NULL, 'group', 6
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Step goal reached', NULL, 'daily', 'binary',  NULL, NULL,  NULL,    0 FROM t
UNION ALL
SELECT id, 'Daily steps',       NULL, 'daily', 'numeric', NULL, 10000, 'steps', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- ─── HEALTH & WELLNESS ────────────────────────────────────────────────────────

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'sleep-accountability', 'Sleep Accountability',
    'Sleep is the foundation of everything. Log your hours, hold each other to consistent bedtimes, and feel the difference.',
    '😴', 'res://drawable/ic_icon_sleep', 'mindfulness', 'moderate', FALSE, FALSE, NULL, 'group', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'In bed by target time', NULL, 'daily', 'binary',  NULL, NULL, NULL,    0 FROM t
UNION ALL
SELECT id, 'Hours slept',           NULL, 'daily', 'numeric', NULL, 7,    'hours', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'healthy-eating', 'Healthy Eating',
    'Not a diet — a long-term habit. Log when you ate well, share what''s working, and keep each other on track without the all-or-nothing pressure.',
    '🥗', 'res://drawable/ic_icon_salad', 'mindfulness', 'moderate', FALSE, FALSE, NULL, 'group', 2
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Ate well today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'screen-time', 'Screen Time',
    'Take back your attention. Log your phone-free time, share strategies, and hold each other to less mindless scrolling.',
    '📵', 'res://drawable/ic_icon_socialmediaban', 'mindfulness', 'moderate', FALSE, FALSE, NULL, 'group', 3
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Stayed within screen time limit', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'prayer-group', 'Prayer Group',
    'Pray together consistently. Log your daily quiet time and keep each other anchored.',
    '🙏', 'res://drawable/ic_icon_prayer', 'mindfulness', 'moderate', FALSE, FALSE, NULL, 'group', 4
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Morning prayer / quiet time', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'daily-meditation', 'Daily Meditation',
    'The 21-day challenge gets you started. This is where you stay. A quiet group for people who have made meditation a permanent part of their day.',
    '🧘', 'res://drawable/ic_icon_prayerhands', 'mindfulness', 'moderate', TRUE, FALSE, NULL, 'group', 5
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Meditated today',   NULL, 'daily', 'binary',   NULL, NULL, NULL,      0 FROM t
UNION ALL
SELECT id, 'Meditation session', NULL, 'daily', 'duration', NULL, 10,   'minutes', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'gratitude-journal', 'Gratitude Journal',
    'Three things, every day. A simple practice that compounds over time. Ongoing accountability for people who''ve moved past the challenge and into the habit.',
    '🙏', NULL, 'mindfulness', 'moderate', FALSE, FALSE, NULL, 'group', 6
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Wrote gratitude today', NULL, 'daily', 'journal', 'What are you grateful for today?', NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'alcohol-free', 'Alcohol-Free',
    'Whether you''re sober curious, in recovery, or just done with drinking — this is a long-term accountability group, not a 30-day reset. Quiet, supportive, no pressure.',
    '🚫', NULL, 'mindfulness', 'moderate', FALSE, FALSE, NULL, 'group', 7
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Alcohol-free today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'daily-scripture', 'Daily Scripture',
    'A permanent home for people who want to read scripture consistently and keep each other accountable to the practice.',
    '📖', 'res://drawable/ic_icon_books', 'mindfulness', 'moderate', FALSE, FALSE, NULL, 'group', 8
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Read scripture today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'daily-hydration', 'Daily Hydration',
    'Eight glasses sounds simple. Doing it every day without accountability is harder than it sounds. A low-key group to keep each other drinking enough water.',
    '💧', 'res://drawable/ic_icon_water', 'mindfulness', 'moderate', FALSE, FALSE, NULL, 'group', 9
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Drank enough water today', NULL, 'daily', 'binary',  NULL, NULL, NULL,      0 FROM t
UNION ALL
SELECT id, 'Glasses of water',         NULL, 'daily', 'numeric', NULL, 8,    'glasses', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- ─── MORNING & ROUTINES ───────────────────────────────────────────────────────

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'morning-routines', 'Morning Routines',
    'Win the morning together. Whatever your routine — journaling, exercise, cold shower, reading — log it and stay consistent.',
    '🌅', 'res://drawable/ic_icon_sunrise', 'lifestyle', 'moderate', TRUE, FALSE, NULL, 'group', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Morning routine done', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- ─── LEARNING ─────────────────────────────────────────────────────────────────

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'book-club', 'Book Club',
    'Read together, share thoughts, hold each other to the pace. Works for any genre or reading speed.',
    '📖', 'res://drawable/ic_icon_book', 'learning', 'moderate', TRUE, FALSE, NULL, 'group', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Read today',   NULL, 'daily',  'binary',  NULL, NULL, NULL,    0 FROM t
UNION ALL
SELECT id, 'Pages read',  NULL, 'weekly', 'numeric', NULL, 50,   'pages', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'language-practice', 'Language Practice',
    'Daily practice makes fluent. Keep each other on track whether you''re learning Spanish, Japanese, or anything else.',
    '🗣️', 'res://drawable/ic_icon_speaking', 'learning', 'moderate', FALSE, FALSE, NULL, 'group', 2
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Practice today', NULL, 'daily', 'binary',   NULL, NULL, NULL,      0 FROM t
UNION ALL
SELECT id, 'Study session',  NULL, 'daily', 'duration', NULL, 15,   'minutes', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'polymath-project', 'The Polymath Project',
    'A group for the genuinely curious. Each day, learn one new thing — anything — and log a brief summary of what it was and why it matters. The goal is breadth, not depth.',
    '🧠', 'res://drawable/ic_icon_brain', 'learning', 'moderate', TRUE, FALSE, NULL, 'group', 3
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Learned something new today', NULL, 'daily', 'journal', 'What did you learn today?', NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'skill-building', 'Skill Building',
    'Pick a skill, practise consistently, log your sessions. Whether it''s guitar, woodworking, chess, or watercolour — deliberate practice needs accountability.',
    '🎯', 'res://drawable/ic_icon_skill_building', 'learning', 'moderate', FALSE, FALSE, NULL, 'group', 4
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Practised today',   NULL, 'daily', 'binary',   NULL, NULL, NULL,      0 FROM t
UNION ALL
SELECT id, 'Practice session',  NULL, 'daily', 'duration', NULL, 30,   'minutes', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'daily-coding', 'Daily Coding',
    '30 Days of Code gets you into the habit. This is where you keep it going. A group for developers, learners, and builders who want to write code every day — indefinitely.',
    '💻', 'res://drawable/ic_icon_laptop', 'learning', 'moderate', FALSE, FALSE, NULL, 'group', 5
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Wrote code today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- ─── CREATIVITY ───────────────────────────────────────────────────────────────

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'writers-room', 'Writers'' Room',
    'Write consistently with people who understand the struggle. Log your word count or just that you showed up.',
    '✍️', 'res://drawable/ic_icon_journal', 'creativity', 'moderate', FALSE, FALSE, NULL, 'group', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Write today',  NULL, 'daily', 'binary',  NULL, NULL,  NULL,    0 FROM t
UNION ALL
SELECT id, 'Word count',   NULL, 'daily', 'numeric', NULL, 500,   'words', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'creative-practice', 'Creative Practice',
    'Make something — anything — consistently. Painting, photography, music, pottery, design. The medium doesn''t matter; showing up does.',
    '🎨', 'res://drawable/ic_icon_creative_spark', 'creativity', 'moderate', FALSE, FALSE, NULL, 'group', 2
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Created something today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- ─── LIFESTYLE & HOBBIES ──────────────────────────────────────────────────────

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'gardening-club', 'Gardening Club',
    'Track your garden together — watering, planting, weeding, and harvesting. Perfect for green thumbs who want to stay accountable to their plot.',
    '🌱', 'res://drawable/ic_icon_gardening', 'lifestyle', 'moderate', TRUE, FALSE, NULL, 'group', 2
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Water the garden',        NULL, 'daily',  'binary', NULL::text, NULL::numeric, NULL::text, 0 FROM t
UNION ALL
SELECT id, 'Weekend garden session',  NULL, 'weekly', 'binary', NULL::text, NULL::numeric, NULL::text, 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'bird-watching', 'Bird Watching',
    'Log your sightings, track your life list, and share the joy of birds with people who get it.',
    '🐦', 'res://drawable/ic_icon_bird_watching', 'lifestyle', 'moderate', FALSE, FALSE, NULL, 'group', 3
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Spot a new species',           NULL, 'weekly', 'journal',  'What did you spot today?', NULL, NULL,      0 FROM t
UNION ALL
SELECT id, 'Time outside birdwatching',    NULL, 'weekly', 'duration', NULL,                       60,   'minutes', 1 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'home-cooking', 'Home Cooking',
    'Cook more, eat better, spend less. Log when you cooked at home and share what you made. No recipes required — just the habit.',
    '🍳', 'res://drawable/ic_icon_cooking', 'lifestyle', 'moderate', FALSE, FALSE, NULL, 'group', 4
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Cooked at home', NULL, 'daily', 'journal', 'What did you cook?', NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'weekend-warrior', 'The Weekend Warrior',
    'One home maintenance task per week. Fixing a leak, painting a room, servicing the car — whatever adulting looks like this weekend. Log it and keep the house from falling apart together.',
    '🔧', 'res://drawable/ic_icon_lightning', 'lifestyle', 'moderate', TRUE, FALSE, NULL, 'group', 5
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Completed a home task this week', NULL, 'weekly', 'journal', 'What did you get done this weekend?', NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'diy-home', 'DIY & Home Projects',
    'Keep the momentum going on home projects. Log your progress, share what you''re working on, and get the accountability to actually finish.',
    '🔨', 'res://drawable/ic_icon_workshop', 'lifestyle', 'moderate', FALSE, FALSE, NULL, 'group', 6
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Worked on a project', NULL, 'weekly', 'journal', 'What did you work on?', NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'weekly-stretch', 'The Weekly Stretch',
    'Personal courage as a practice. Once a week, do one thing that makes you uncomfortable — a hard conversation, a new social situation, a creative risk — and log it. Growth lives outside your comfort zone.',
    '🌱', 'res://drawable/ic_icon_dailydiscovery', 'lifestyle', 'moderate', FALSE, FALSE, NULL, 'group', 7
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Did something uncomfortable this week', NULL, 'weekly', 'journal', 'What did you do that pushed your comfort zone?', NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- ─── PRODUCTIVITY ─────────────────────────────────────────────────────────────

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'out-there-doing-it', 'Out There Doing It',
    'No specific project, no specific goal. Just a group of people who show up and prove they did something productive each day. Log it. Anything counts.',
    '✅', 'res://drawable/ic_icon_handshake', 'productivity', 'moderate', TRUE, FALSE, NULL, 'group', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Got something done today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- ─── FINANCE ──────────────────────────────────────────────────────────────────

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'savings-group', 'Savings Group',
    'Save consistently and hold each other to it. Log your contributions and watch the habit stick.',
    '💰', 'res://drawable/ic_icon_cash', 'finance', 'moderate', FALSE, FALSE, NULL, 'group', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Saved today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'budget-accountability', 'Budget Accountability',
    'Track every dollar together. Log when you''ve reviewed your budget or tracked your spending for the day. Awareness is the first step.',
    '📊', 'res://drawable/ic_icon_budgeting', 'finance', 'moderate', FALSE, FALSE, NULL, 'group', 2
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Tracked spending today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- ─── SOCIAL ───────────────────────────────────────────────────────────────────

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    'staying-connected', 'Staying Connected',
    'Relationships need maintenance. A group for people who want to make regular contact with friends and family a non-negotiable habit — not just a 30-day challenge.',
    '📞', 'res://drawable/ic_icon_phone', 'social', 'moderate', FALSE, FALSE, NULL, 'group', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Reached out to someone today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

-- ─── NEW CHALLENGE TEMPLATES (is_challenge = TRUE) ────────────────────────────
-- These appear in the "Start a Challenge" browser, not "Browse Group Ideas".

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    '7-day-launch-sprint', '7-Day Launch Sprint',
    'Seven days of consistent daily logging to kick off a new habit. High intensity, short commitment. Perfect for anyone just getting started and wanting to prove they can show up every day.',
    '⚡', 'res://drawable/ic_icon_alarmclock', 'productivity', 'hard', TRUE, TRUE, 7, 'challenge', 1
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Showed up today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

WITH t AS (
  INSERT INTO group_templates (slug, title, description, icon_emoji, icon_url, category, difficulty, is_featured, is_challenge, duration_days, default_mode, sort_order)
  VALUES (
    '365-day-marathon', 'The 365-Day Marathon',
    'A full year of one non-negotiable habit. Not a sprint — a lifestyle change. For people who are done with 30-day resets and want to build something permanent.',
    '🗓️', 'res://drawable/ic_icon_nature', 'lifestyle', 'hard', FALSE, TRUE, 365, 'challenge', 100
  )
  ON CONFLICT (slug) DO NOTHING
  RETURNING id
)
INSERT INTO group_template_goals (template_id, title, description, cadence, metric_type, log_title_prompt, target_value, unit, sort_order)
SELECT id, 'Did the thing today', NULL, 'daily', 'binary', NULL, NULL, NULL, 0 FROM t
ON CONFLICT (template_id, sort_order) DO NOTHING;

COMMIT;
