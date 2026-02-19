-- Migration: 20260221_seed_challenge_icon_urls.sql
-- Description: Set icon_url for challenge templates to use bundled Android assets

BEGIN;

UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_steps' WHERE slug = '10k-steps-daily';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_running' WHERE slug = 'couch-to-5k';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_strength' WHERE slug = '100-pushups-a-day';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_walking' WHERE slug = '10k-steps-week';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_planking' WHERE slug = 'plank-challenge';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_sunrise' WHERE slug = 'morning-workout';

UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_books' WHERE slug = 'read-bible-one-year';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_book' WHERE slug = 'read-nt-260-days';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_book' WHERE slug = 'book-a-month';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_books' WHERE slug = '50-books-year';

UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_journal' WHERE slug = 'gratitude-journal-30';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_socialmediaban' WHERE slug = 'no-social-30';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_sleep' WHERE slug = '7-hours-sleep';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_phone' WHERE slug = 'digital-detox-weekend';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_coldshower' WHERE slug = 'cold-shower-30';

UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_lightning' WHERE slug = '30-day-no-sugar';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_salad' WHERE slug = 'whole30';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_frypan' WHERE slug = 'cook-at-home';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_socialmediaban' WHERE slug = 'no-alcohol-30';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_water' WHERE slug = '8-glasses-water';

UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_laptop' WHERE slug = '30-days-coding';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_speaking' WHERE slug = 'language-learning-sprint';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_alarmclock' WHERE slug = 'wake-up-5am';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_inbox' WHERE slug = 'inbox-zero-month';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_journal' WHERE slug = 'daily-journaling';

UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_cash' WHERE slug = 'no-spend-30';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_cash' WHERE slug = 'save-5-daily';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_budgeting' WHERE slug = 'track-every-dollar';

UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_handshake' WHERE slug = 'reach-out-daily';
UPDATE challenge_templates SET icon_url = 'res://drawable/ic_icon_phone' WHERE slug = 'phone-call-day';

-- Note: 30-days-kindness stays with its yellow heart emoji and no icon_url.

COMMIT;
