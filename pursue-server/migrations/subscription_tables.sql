-- Subscription tables and users columns per pursue-subscription-spec.md
-- Run after schema.sql / existing migrations

-- Add subscription tracking to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS current_subscription_tier VARCHAR(20) NOT NULL DEFAULT 'free'
  CHECK (current_subscription_tier IN ('free', 'premium'));
ALTER TABLE users ADD COLUMN IF NOT EXISTS subscription_status VARCHAR(20) NOT NULL DEFAULT 'active'
  CHECK (subscription_status IN ('active', 'cancelled', 'expired', 'grace_period', 'over_limit'));
ALTER TABLE users ADD COLUMN IF NOT EXISTS group_limit INTEGER NOT NULL DEFAULT 1;
ALTER TABLE users ADD COLUMN IF NOT EXISTS current_group_count INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_users_subscription_tier ON users(current_subscription_tier);
CREATE INDEX IF NOT EXISTS idx_users_subscription_status ON users(subscription_status);

-- User subscription tracking
CREATE TABLE IF NOT EXISTS user_subscriptions (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  tier VARCHAR(20) NOT NULL CHECK (tier IN ('free', 'premium')),
  status VARCHAR(20) NOT NULL CHECK (status IN ('active', 'cancelled', 'expired', 'grace_period')),
  started_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ,
  cancelled_at TIMESTAMPTZ,
  platform VARCHAR(20) CHECK (platform IN ('google_play', 'app_store')),
  platform_subscription_id VARCHAR(255),
  platform_purchase_token TEXT,
  auto_renew BOOLEAN DEFAULT true,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, started_at)
);

CREATE INDEX IF NOT EXISTS idx_user_subscriptions_user_id ON user_subscriptions(user_id);
CREATE INDEX IF NOT EXISTS idx_user_subscriptions_status ON user_subscriptions(status);
CREATE INDEX IF NOT EXISTS idx_user_subscriptions_expires_at ON user_subscriptions(expires_at) WHERE status = 'active';

-- Track when users were over limit and which groups they kept
CREATE TABLE IF NOT EXISTS subscription_downgrade_history (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  downgrade_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  previous_tier VARCHAR(20) NOT NULL,
  groups_before_downgrade INTEGER NOT NULL,
  kept_group_id UUID REFERENCES groups(id) ON DELETE SET NULL,
  removed_group_ids UUID[] NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_subscription_downgrade_history_user_id ON subscription_downgrade_history(user_id);

-- Platform transaction history for reconciliation
CREATE TABLE IF NOT EXISTS subscription_transactions (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  subscription_id UUID NOT NULL REFERENCES user_subscriptions(id) ON DELETE CASCADE,
  transaction_type VARCHAR(50) NOT NULL CHECK (transaction_type IN ('purchase', 'renewal', 'cancellation', 'refund')),
  platform VARCHAR(20) NOT NULL CHECK (platform IN ('google_play', 'app_store')),
  platform_transaction_id VARCHAR(255) NOT NULL,
  amount_cents INTEGER,
  currency VARCHAR(3),
  transaction_date TIMESTAMPTZ NOT NULL,
  raw_receipt TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(platform, platform_transaction_id)
);

CREATE INDEX IF NOT EXISTS idx_subscription_transactions_subscription_id ON subscription_transactions(subscription_id);
CREATE INDEX IF NOT EXISTS idx_subscription_transactions_platform_transaction_id ON subscription_transactions(platform, platform_transaction_id);

-- Backfill existing users: set current_group_count from active memberships
UPDATE users u
SET current_group_count = COALESCE(
  (SELECT COUNT(*) FROM group_memberships WHERE user_id = u.id AND status = 'active'),
  0
),
subscription_status = CASE
  WHEN (SELECT COUNT(*) FROM group_memberships WHERE user_id = u.id AND status = 'active') > u.group_limit
  THEN 'over_limit'
  ELSE u.subscription_status
END;
