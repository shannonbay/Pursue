-- Migration: Add user_consents table for tracking consent to Terms of Service and Privacy Policy
-- Records what was agreed to, when, and from which IP address.

CREATE TABLE IF NOT EXISTS user_consents (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  consent_type VARCHAR(50) NOT NULL,
  agreed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  ip_address VARCHAR(45),
  email_hash VARCHAR(64)  -- SHA-256 of email + salt, set on account deletion
);

CREATE INDEX IF NOT EXISTS idx_user_consents_user_id ON user_consents(user_id);
