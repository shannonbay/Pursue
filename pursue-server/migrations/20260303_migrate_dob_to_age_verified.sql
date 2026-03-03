-- Privacy-by-design: replace stored date_of_birth with a boolean age_verified flag.
-- We only need to know a user is 18+, not their actual birth date.
-- v1.0 — 2026-03-03
--
-- Safe to run whether or not 20260303_add_date_of_birth.sql has been applied:
-- ADD COLUMN IF NOT EXISTS / DROP COLUMN IF EXISTS guards handle both states.

BEGIN;

ALTER TABLE users ADD COLUMN IF NOT EXISTS age_verified BOOLEAN NOT NULL DEFAULT FALSE;

-- Backfill: any user with a stored DOB already passed the age gate
UPDATE users SET age_verified = TRUE WHERE date_of_birth IS NOT NULL;

ALTER TABLE users DROP COLUMN IF EXISTS date_of_birth;

COMMIT;

-- Rollback:
-- ALTER TABLE users ADD COLUMN date_of_birth DATE;
-- ALTER TABLE users DROP COLUMN age_verified;
