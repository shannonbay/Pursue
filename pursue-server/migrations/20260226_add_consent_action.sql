-- Add action column to user_consents for bidirectional consent tracking
-- Supports recording opt-outs (revocations) alongside opt-ins (grants)
-- Required for Firebase Crashlytics compliance under NZ Privacy Act 2020

ALTER TABLE user_consents
  ADD COLUMN IF NOT EXISTS action VARCHAR(10) NOT NULL DEFAULT 'grant'
    CHECK (action IN ('grant', 'revoke'));

CREATE INDEX IF NOT EXISTS idx_user_consents_type_time
  ON user_consents(user_id, consent_type, agreed_at DESC);
