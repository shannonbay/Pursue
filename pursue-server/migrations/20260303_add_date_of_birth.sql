-- Age gate: store date of birth for all users
-- v1.0 — 2026-03-03
--
-- NULL for legacy accounts; new email sign-ups provide it at registration.
-- Existing users supply it via the in-app DOB gate on next launch.

ALTER TABLE users ADD COLUMN IF NOT EXISTS date_of_birth DATE;
