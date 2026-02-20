-- Migration: 20260220_challenge_suggestion_log.sql
-- Description: Track challenge suggestions for users to avoid nagging and enforce rate limits

CREATE TABLE challenge_suggestion_log (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sent_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  dismissed_at TIMESTAMP WITH TIME ZONE,
  converted BOOLEAN DEFAULT FALSE,     -- User created/joined a challenge after this
  UNIQUE(user_id)
);

CREATE INDEX idx_challenge_suggestion_user ON challenge_suggestion_log(user_id);
