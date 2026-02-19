ALTER TABLE groups
  ADD COLUMN IF NOT EXISTS challenge_invite_card_data JSONB;

COMMENT ON COLUMN groups.challenge_invite_card_data IS
  'Stored base payload for challenge invite cards (static fields only; attribution fields are generated per requesting user).';
