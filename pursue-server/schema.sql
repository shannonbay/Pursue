-- PostgreSQL 17.x
-- Pursue Database Schema

-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pg_trgm;         -- fuzzy text search
CREATE EXTENSION IF NOT EXISTS vector;          -- pgvector for semantic search

-- Users table
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  email VARCHAR(255) UNIQUE NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  avatar_url TEXT,
  avatar_data BYTEA,
  avatar_mime_type VARCHAR(50),
  password_hash VARCHAR(255), -- NULL for Google-only users
  timezone VARCHAR(50) DEFAULT 'UTC', -- Cached timezone for smart reminders
  interest_categories TEXT[] NOT NULL DEFAULT '{}',
  current_subscription_tier VARCHAR(20) NOT NULL DEFAULT 'free'
    CHECK (current_subscription_tier IN ('free', 'premium')),
  subscription_status VARCHAR(20) NOT NULL DEFAULT 'active'
    CHECK (subscription_status IN ('active', 'cancelled', 'expired', 'grace_period', 'over_limit')),
  group_limit INTEGER NOT NULL DEFAULT 1,
  current_group_count INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  deleted_at TIMESTAMP WITH TIME ZONE -- NULL if active, timestamp if soft deleted
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_subscription_tier ON users(current_subscription_tier);
CREATE INDEX idx_users_subscription_status ON users(subscription_status);

COMMENT ON COLUMN users.deleted_at IS 'Soft delete timestamp. NULL = active user, non-NULL = soft deleted. Prevents deleted users from logging in or being accessed.';
COMMENT ON COLUMN users.timezone IS 'Cached timezone from last progress log. Used by smart reminders for background jobs. Updated when user logs progress with a different timezone.';

-- Auth providers table (email, google)
CREATE TABLE auth_providers (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider VARCHAR(50) NOT NULL, -- 'email', 'google'
  provider_user_id VARCHAR(255) NOT NULL, -- Google user ID
  provider_email VARCHAR(255), -- Email from provider
  linked_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  UNIQUE(provider, provider_user_id),
  UNIQUE(user_id, provider)
);

CREATE INDEX idx_auth_providers_user ON auth_providers(user_id);
CREATE INDEX idx_auth_providers_lookup ON auth_providers(provider, provider_user_id);

-- Refresh tokens
CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash VARCHAR(255) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);

-- Password reset tokens
CREATE TABLE password_reset_tokens (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash VARCHAR(255) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  used_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_password_reset_user ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_token ON password_reset_tokens(token_hash);

-- User consents (Terms of Service, Privacy Policy)
CREATE TABLE user_consents (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  consent_type VARCHAR(50) NOT NULL,
  agreed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  ip_address VARCHAR(45),
  email_hash VARCHAR(64)  -- SHA-256 of email + salt, set on account deletion
);

CREATE INDEX idx_user_consents_user_id ON user_consents(user_id);

-- Devices (for FCM push notifications)
CREATE TABLE devices (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  fcm_token VARCHAR(256) UNIQUE NOT NULL,
  device_name VARCHAR(128),
  platform VARCHAR(20), -- 'android', 'ios', 'web'
  last_active TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_devices_user ON devices(user_id);
CREATE INDEX idx_devices_fcm_token ON devices(fcm_token);

-- Groups
CREATE TABLE groups (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  name VARCHAR(100) NOT NULL,
  description TEXT,
  icon_emoji VARCHAR(10),          -- Optional emoji (e.g., "🏃", "📚")
  icon_color VARCHAR(7),            -- Hex color for emoji background (e.g., "#1976D2")
  icon_url TEXT,                    -- Optional uploaded image URL (Cloud Storage)
  icon_data BYTEA,
  icon_mime_type VARCHAR(50),
  creator_user_id UUID NOT NULL REFERENCES users(id),
  is_challenge BOOLEAN NOT NULL DEFAULT FALSE,
  challenge_start_date DATE,
  challenge_end_date DATE,
  template_id UUID,
  challenge_status VARCHAR(20) DEFAULT NULL,
  challenge_invite_card_data JSONB,
  visibility TEXT NOT NULL DEFAULT 'private' CHECK (visibility IN ('public', 'private')),
  category TEXT CHECK (category IN ('fitness','nutrition','mindfulness','learning','creativity','productivity','finance','social','lifestyle','sports','other')),
  spot_limit INTEGER CHECK (spot_limit IS NULL OR (spot_limit >= 2 AND spot_limit <= 500)),
  auto_approve BOOLEAN NOT NULL DEFAULT FALSE,
  comm_platform TEXT CHECK (comm_platform IS NULL OR comm_platform IN ('discord','whatsapp','telegram')),
  comm_link TEXT,
  search_embedding vector(1536),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  deleted_at TIMESTAMP WITH TIME ZONE -- NULL if active, timestamp if soft deleted
);

CREATE INDEX idx_groups_creator ON groups(creator_user_id);
CREATE INDEX idx_groups_public ON groups(visibility, deleted_at) WHERE visibility = 'public';
CREATE INDEX idx_groups_challenge_status ON groups(is_challenge, challenge_status) WHERE is_challenge = TRUE;
CREATE INDEX idx_groups_template ON groups(template_id) WHERE template_id IS NOT NULL;
CREATE INDEX idx_groups_name_trgm ON groups USING gin (name gin_trgm_ops);
CREATE INDEX idx_groups_search_embedding ON groups USING hnsw (search_embedding vector_cosine_ops);

ALTER TABLE groups ADD CONSTRAINT chk_groups_challenge_fields CHECK (
  (is_challenge = FALSE AND challenge_start_date IS NULL AND challenge_end_date IS NULL AND challenge_status IS NULL)
  OR
  (is_challenge = TRUE AND challenge_start_date IS NOT NULL AND challenge_end_date IS NOT NULL AND challenge_status IS NOT NULL)
);

ALTER TABLE groups ADD CONSTRAINT chk_groups_challenge_dates CHECK (
  challenge_end_date IS NULL OR challenge_end_date >= challenge_start_date
);

ALTER TABLE groups ADD CONSTRAINT chk_groups_challenge_status CHECK (
  challenge_status IS NULL OR challenge_status IN ('upcoming', 'active', 'completed', 'cancelled')
);

-- Group templates (shared library for challenge templates and ongoing group templates)
CREATE TABLE group_templates (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  slug VARCHAR(100) UNIQUE NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT NOT NULL,
  icon_emoji VARCHAR(10) NOT NULL,
  icon_url TEXT,
  duration_days INTEGER,                       -- NULL for ongoing (non-challenge) templates
  category VARCHAR(50) NOT NULL,
  difficulty VARCHAR(20) NOT NULL DEFAULT 'moderate',
  is_featured BOOLEAN NOT NULL DEFAULT FALSE,
  is_challenge BOOLEAN NOT NULL DEFAULT TRUE,  -- TRUE = time-boxed challenge, FALSE = ongoing group
  default_mode VARCHAR(20) NOT NULL DEFAULT 'challenge'
    CHECK (default_mode IN ('group', 'challenge', 'either')),
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_group_templates_category ON group_templates(category, sort_order);
CREATE INDEX idx_group_templates_featured ON group_templates(is_featured, sort_order) WHERE is_featured = TRUE;

CREATE TABLE group_template_goals (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  template_id UUID NOT NULL REFERENCES group_templates(id) ON DELETE CASCADE,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  cadence VARCHAR(20) NOT NULL,
  metric_type VARCHAR(20) NOT NULL,
  target_value DECIMAL(10,2),
  unit VARCHAR(50),
  log_title_prompt VARCHAR(80),                -- Prompt shown to users for journal goals
  sort_order INTEGER NOT NULL DEFAULT 0,
  active_days INTEGER DEFAULT NULL, -- Bitmask for day-of-week scheduling (1=Mon..64=Sun, NULL=every day)
  UNIQUE(template_id, sort_order),
  CONSTRAINT chk_template_goal_active_days CHECK (active_days IS NULL OR (active_days >= 1 AND active_days <= 127)),
  CONSTRAINT chk_template_goal_active_days_cadence CHECK (active_days IS NULL OR cadence = 'daily')
);

CREATE INDEX idx_group_template_goals ON group_template_goals(template_id, sort_order);

ALTER TABLE groups ADD CONSTRAINT fk_groups_template
  FOREIGN KEY (template_id) REFERENCES group_templates(id) ON DELETE SET NULL;

-- Group memberships
CREATE TABLE group_memberships (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role VARCHAR(20) NOT NULL, -- 'creator', 'admin', 'member'
  status VARCHAR(20) NOT NULL DEFAULT 'active', -- 'pending', 'active', 'declined'
  joined_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  weekly_recap_enabled BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE(group_id, user_id)
);

CREATE INDEX idx_memberships_group ON group_memberships(group_id);
CREATE INDEX idx_memberships_user ON group_memberships(user_id);
CREATE INDEX idx_memberships_pending ON group_memberships(group_id, user_id, joined_at) WHERE status = 'pending';
CREATE INDEX idx_memberships_user_status ON group_memberships(user_id, status, joined_at DESC) WHERE status IN ('pending', 'declined');

-- Invite codes (one active code per group, permanent and reusable unless regenerated)
CREATE TABLE invite_codes (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  code VARCHAR(50) UNIQUE NOT NULL,
  created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  revoked_at TIMESTAMP WITH TIME ZONE
);

COMMENT ON TABLE invite_codes IS 'Each group has exactly one active invite code. Codes are permanent and reusable unless regenerated.';
COMMENT ON COLUMN invite_codes.revoked_at IS 'NULL = code is active, timestamp = code was revoked (replaced by new code)';

CREATE UNIQUE INDEX idx_invite_codes_active_per_group ON invite_codes(group_id)
  WHERE revoked_at IS NULL;
CREATE INDEX idx_invite_codes_code ON invite_codes(code);

-- Goals
CREATE TABLE goals (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  cadence VARCHAR(20) NOT NULL, -- 'daily', 'weekly', 'monthly', 'yearly'
  metric_type VARCHAR(20) NOT NULL -- 'binary', 'numeric', 'duration', 'journal'
    CONSTRAINT goals_metric_type_check CHECK (metric_type IN ('binary', 'numeric', 'duration', 'journal')),
  target_value DECIMAL(10,2), -- For numeric and duration goals (NULL for journal)
  unit VARCHAR(50), -- e.g., 'km', 'pages', 'minutes'
  log_title_prompt VARCHAR(80), -- Prompt shown to users when logging journal entries
  active_days INTEGER DEFAULT NULL, -- Bitmask for day-of-week scheduling (1=Mon..64=Sun, NULL=every day)
  created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  deleted_at TIMESTAMP WITH TIME ZONE, -- Soft delete: NULL if active, timestamp if deleted
  deleted_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL, -- Who deleted it (for audit)
  CONSTRAINT chk_active_days CHECK (active_days IS NULL OR (active_days >= 1 AND active_days <= 127)),
  CONSTRAINT chk_active_days_cadence CHECK (active_days IS NULL OR cadence = 'daily')
);

CREATE INDEX idx_goals_group ON goals(group_id);
CREATE INDEX idx_goals_active ON goals(group_id) WHERE deleted_at IS NULL;
-- Optimized index for goals query with ordering (spec recommendation)
CREATE INDEX idx_goals_group_id_archived ON goals(group_id, deleted_at, created_at DESC);
CREATE INDEX idx_goals_title_trgm ON goals USING gin (title gin_trgm_ops);

COMMENT ON COLUMN goals.deleted_at IS 'Soft delete timestamp. NULL = active, non-NULL = deleted. Preserves historical progress data and enables restoration.';

-- Progress entries
CREATE TABLE progress_entries (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  goal_id UUID NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  value DECIMAL(10,2) NOT NULL, -- 1 for binary/journal true, 0 for false, numeric for others
  note TEXT,
  log_title VARCHAR(120),       -- Required for journal goals; the headline of what was done
  logged_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(), -- When user logged it (for feed sorting)
  period_start DATE NOT NULL, -- User's local date: '2026-01-17' (not UTC timestamp!)
  user_timezone VARCHAR(50), -- e.g., 'America/New_York' (for reference)
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  moderation_status TEXT NOT NULL DEFAULT 'ok'
    CHECK (moderation_status IN ('ok', 'flagged', 'hidden', 'removed', 'disputed')),
  moderation_note TEXT,
  moderation_updated_at TIMESTAMPTZ
);

CREATE INDEX idx_progress_goal_user ON progress_entries(goal_id, user_id, period_start);
CREATE INDEX idx_progress_user_recent ON progress_entries(user_id, logged_at DESC);
CREATE INDEX idx_progress_period ON progress_entries(period_start);
-- Optimized indexes for batch progress queries (spec recommendations)
CREATE INDEX idx_progress_goal_date ON progress_entries(goal_id, period_start DESC);
CREATE INDEX idx_progress_goal_user_date ON progress_entries(goal_id, user_id, period_start DESC);
-- Member progress endpoint: keyset pagination index (user_id, logged_at DESC, id DESC)
CREATE INDEX idx_progress_user_logged_at_id ON progress_entries(user_id, logged_at DESC, id DESC);
-- Member progress endpoint: goal summaries aggregation
CREATE INDEX idx_progress_goal_user_period ON progress_entries(goal_id, user_id, period_start);
-- Journal entries: filter by log_title presence
CREATE INDEX idx_progress_journal_entries ON progress_entries(goal_id, period_start DESC) WHERE log_title IS NOT NULL;

COMMENT ON COLUMN progress_entries.period_start IS 'User local date (DATE not TIMESTAMP). For daily goal, this is the user local day they completed it, e.g., 2026-01-17. Critical for timezone handling - a Friday workout at 11 PM EST should count for Friday, not Saturday UTC.';

-- Progress photos (GCS-backed, 7-day expiry via lifecycle rules)
CREATE TABLE progress_photos (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  progress_entry_id UUID NOT NULL UNIQUE REFERENCES progress_entries(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  gcs_object_path VARCHAR(512) NOT NULL,
  width_px INTEGER NOT NULL,
  height_px INTEGER NOT NULL,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days'),
  gcs_deleted_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_photos_entry ON progress_photos(progress_entry_id);
CREATE INDEX idx_photos_user ON progress_photos(user_id);
CREATE INDEX idx_photos_expiry ON progress_photos(expires_at) WHERE gcs_deleted_at IS NULL;

-- Permanent upload log for quota enforcement (rolling 7-day window)
CREATE TABLE photo_upload_log (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_upload_log_user_time ON photo_upload_log(user_id, uploaded_at DESC);

-- Nudges (motivational push notifications from group members)
CREATE TABLE nudges (
  id               UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  sender_user_id   UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id         UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  goal_id          UUID REFERENCES goals(id) ON DELETE SET NULL,
  sent_at          TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  sender_local_date DATE NOT NULL
);

CREATE UNIQUE INDEX idx_nudges_daily_limit
  ON nudges(sender_user_id, recipient_user_id, sender_local_date);
CREATE INDEX idx_nudges_recipient
  ON nudges(recipient_user_id, sent_at DESC);
CREATE INDEX idx_nudges_sender_daily
  ON nudges(sender_user_id, sender_local_date);

-- Group activity feed
CREATE TABLE group_activities (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id UUID REFERENCES users(id) ON DELETE SET NULL, -- NULL if user deleted
  activity_type VARCHAR(50) NOT NULL,
  metadata JSONB,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_activities_group ON group_activities(group_id, created_at DESC);
CREATE INDEX idx_activities_type ON group_activities(activity_type);

-- Activity reactions (one reaction per user per activity; replacing emoji replaces the row)
CREATE TABLE activity_reactions (
  id          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  activity_id UUID NOT NULL REFERENCES group_activities(id) ON DELETE CASCADE,
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  emoji       VARCHAR(10) NOT NULL,
  created_at  TIMESTAMPTZ DEFAULT NOW(),
  CONSTRAINT uq_reaction_user_activity UNIQUE (activity_id, user_id)
);

CREATE INDEX idx_reactions_activity ON activity_reactions(activity_id);
CREATE INDEX idx_reactions_user ON activity_reactions(user_id);

-- User notifications: personal inbox of events directed at the user
CREATE TABLE user_notifications (
  id                UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  type              VARCHAR(50) NOT NULL,
  actor_user_id     UUID REFERENCES users(id) ON DELETE SET NULL,
  group_id          UUID REFERENCES groups(id) ON DELETE CASCADE,
  goal_id           UUID REFERENCES goals(id) ON DELETE CASCADE,
  progress_entry_id UUID REFERENCES progress_entries(id) ON DELETE CASCADE,
  metadata          JSONB,
  shareable_card_data JSONB,  -- Pre-rendered card data for shareable milestones
  is_read           BOOLEAN NOT NULL DEFAULT FALSE,
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_user_notifications_user ON user_notifications(user_id, created_at DESC);
CREATE INDEX idx_user_notifications_unread ON user_notifications(user_id) WHERE is_read = FALSE;
CREATE INDEX idx_user_notifications_shareable ON user_notifications(user_id, type) WHERE shareable_card_data IS NOT NULL;

-- Deferred push queue for challenge completion notifications
CREATE TABLE challenge_completion_push_queue (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  send_at TIMESTAMP WITH TIME ZONE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'sent', 'failed')),
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  UNIQUE(group_id, user_id)
);

CREATE INDEX idx_challenge_completion_push_queue_pending
  ON challenge_completion_push_queue(status, send_at)
  WHERE status = 'pending';

-- Milestone deduplication: track which milestones have been awarded
CREATE TABLE user_milestone_grants (
  id            UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  milestone_key VARCHAR(100) NOT NULL,
  goal_id       UUID REFERENCES goals(id) ON DELETE SET NULL,
  granted_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, milestone_key)
);

-- Stable referral token per user for milestone share attribution
CREATE TABLE referral_tokens (
  token      VARCHAR(12) PRIMARY KEY,
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  UNIQUE(user_id)
);

CREATE INDEX idx_referral_tokens_user ON referral_tokens(user_id);

-- =========================
-- Subscription Tables
-- =========================

CREATE TABLE user_subscriptions (
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

CREATE INDEX idx_user_subscriptions_user_id ON user_subscriptions(user_id);
CREATE INDEX idx_user_subscriptions_status ON user_subscriptions(status);
CREATE INDEX idx_user_subscriptions_expires_at ON user_subscriptions(expires_at) WHERE status = 'active';

CREATE TABLE subscription_downgrade_history (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  downgrade_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  previous_tier VARCHAR(20) NOT NULL,
  groups_before_downgrade INTEGER NOT NULL,
  kept_group_id UUID REFERENCES groups(id) ON DELETE SET NULL,
  removed_group_ids UUID[] NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_subscription_downgrade_history_user_id ON subscription_downgrade_history(user_id);

CREATE TABLE subscription_transactions (
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

CREATE INDEX idx_subscription_transactions_subscription_id ON subscription_transactions(subscription_id);
CREATE INDEX idx_subscription_transactions_platform_transaction_id ON subscription_transactions(platform, platform_transaction_id);

-- Group heat: momentum indicator based on rolling completion rates
CREATE TABLE group_heat (
  group_id UUID PRIMARY KEY REFERENCES groups(id) ON DELETE CASCADE,
  heat_score DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  heat_tier SMALLINT NOT NULL DEFAULT 0,
  last_calculated_at TIMESTAMP WITH TIME ZONE,
  streak_days INTEGER NOT NULL DEFAULT 0,
  peak_score DECIMAL(5,2) NOT NULL DEFAULT 0.00,
  peak_date DATE,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- Group daily GCR: stores computed daily group completion rates
CREATE TABLE group_daily_gcr (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  date DATE NOT NULL,
  total_possible INTEGER NOT NULL,
  total_completed INTEGER NOT NULL,
  gcr DECIMAL(5,4) NOT NULL,
  member_count INTEGER NOT NULL,
  goal_count INTEGER NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  UNIQUE(group_id, date)
);

CREATE INDEX idx_gcr_group_date ON group_daily_gcr(group_id, date DESC);

-- Triggers for updated_at timestamps
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_groups_updated_at BEFORE UPDATE ON groups
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_group_templates_updated_at BEFORE UPDATE ON group_templates
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_group_heat_updated_at BEFORE UPDATE ON group_heat
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- =========================
-- Resource caps and helpful indexes
-- =========================

-- Helpful composite/indexes for counting and enforcement
CREATE INDEX IF NOT EXISTS idx_groups_creator_active ON groups(creator_user_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_memberships_group_role ON group_memberships(group_id, role);
CREATE INDEX IF NOT EXISTS idx_progress_user_period ON progress_entries(user_id, period_start);
CREATE INDEX IF NOT EXISTS idx_devices_user_created_at ON devices(user_id, created_at DESC);

-- =========================
-- Hard-cap enforcement trigger functions
-- These enforce absolute limits (not time-windowed). Choose conservative caps.
-- Update thresholds here if product requirements change.
-- =========================

-- Max groups a single user can create
CREATE FUNCTION enforce_user_group_creation_limit()
RETURNS TRIGGER AS $$
DECLARE
  existing_count INTEGER;
  max_groups INTEGER := 10; -- hard cap: 10 groups per user
BEGIN
  SELECT COUNT(*) INTO existing_count FROM groups WHERE creator_user_id = NEW.creator_user_id AND deleted_at IS NULL;
  IF existing_count >= max_groups THEN
    RAISE EXCEPTION 'USER_GROUP_LIMIT_EXCEEDED: user % has created % groups (limit %)', NEW.creator_user_id, existing_count, max_groups;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_enforce_user_group_creation_limit
BEFORE INSERT ON groups
FOR EACH ROW EXECUTE FUNCTION enforce_user_group_creation_limit();

-- Max members per group and max groups per user (membership)
CREATE FUNCTION enforce_group_members_and_user_join_limits()
RETURNS TRIGGER AS $$
DECLARE
  member_count INTEGER;
  user_group_count INTEGER;
  max_members_per_group INTEGER := 50; -- hard cap: 50 members per group
  max_groups_per_user INTEGER := 10; -- hard cap: 10 group memberships per user
BEGIN
  SELECT COUNT(*) INTO member_count FROM group_memberships WHERE group_id = NEW.group_id;
  IF member_count >= max_members_per_group THEN
    RAISE EXCEPTION 'GROUP_MEMBER_LIMIT_EXCEEDED: group % has % members (limit %)', NEW.group_id, member_count, max_members_per_group;
  END IF;

  SELECT COUNT(*) INTO user_group_count FROM group_memberships WHERE user_id = NEW.user_id;
  IF user_group_count >= max_groups_per_user THEN
    RAISE EXCEPTION 'USER_GROUP_MEMBERSHIP_LIMIT_EXCEEDED: user % member of % groups (limit %)', NEW.user_id, user_group_count, max_groups_per_user;
  END IF;

  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_enforce_group_members_and_user_join_limits
BEFORE INSERT ON group_memberships
FOR EACH ROW EXECUTE FUNCTION enforce_group_members_and_user_join_limits();

-- Max goals per group
CREATE FUNCTION enforce_group_goals_limit()
RETURNS TRIGGER AS $$
DECLARE
  goal_count INTEGER;
  max_goals_per_group INTEGER := 100; -- hard cap: 100 goals per group
BEGIN
  SELECT COUNT(*) INTO goal_count FROM goals WHERE group_id = NEW.group_id AND deleted_at IS NULL;
  IF goal_count >= max_goals_per_group THEN
    RAISE EXCEPTION 'GROUP_GOALS_LIMIT_EXCEEDED: group % has % goals (limit %)', NEW.group_id, goal_count, max_goals_per_group;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_enforce_group_goals_limit
BEFORE INSERT ON goals
FOR EACH ROW EXECUTE FUNCTION enforce_group_goals_limit();

-- Enforce invite max_uses never below current_uses
CREATE FUNCTION enforce_invite_max_uses()
RETURNS TRIGGER AS $$
BEGIN
  IF (NEW.max_uses IS NOT NULL) AND (NEW.current_uses IS NOT NULL) AND (NEW.current_uses > NEW.max_uses) THEN
    RAISE EXCEPTION 'INVITE_MAX_USES_EXCEEDED: current_uses (%) cannot exceed max_uses (%)', NEW.current_uses, NEW.max_uses;
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_enforce_invite_max_uses
BEFORE INSERT OR UPDATE ON invite_codes
FOR EACH ROW EXECUTE FUNCTION enforce_invite_max_uses();

-- Notes:
-- These triggers enforce absolute caps. Time-windowed or sliding-window limits
-- (e.g., X creations per day) should be implemented using Redis or a similar
-- fast counter store and are not covered by these DB triggers.

-- =========================
-- User deletion (privacy-critical)
-- =========================

-- Deletes the user row; FK constraints handle all related data:
--   CASCADE: auth_providers, refresh_tokens, password_reset_tokens, devices,
--            group_memberships, progress_entries, user_subscriptions,
--            subscription_downgrade_history
--   SET NULL: goals.created_by_user_id, goals.deleted_by_user_id,
--             group_activities.user_id, invite_codes.created_by_user_id,
--             user_consents.user_id
CREATE OR REPLACE FUNCTION delete_user_data(p_user_id UUID)
RETURNS VOID AS $$
BEGIN
  DELETE FROM users WHERE id = p_user_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'USER_NOT_FOUND: %', p_user_id;
  END IF;
END;
$$ LANGUAGE plpgsql;

-- =========================
-- Smart Reminders System
-- =========================

-- User logging patterns (cached for performance)
CREATE TABLE user_logging_patterns (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  day_of_week INTEGER NOT NULL DEFAULT -1, -- -1 for general, 0-6 for day-specific
  typical_hour_start INTEGER NOT NULL,     -- 0-23 (user's local time)
  typical_hour_end INTEGER NOT NULL,       -- 0-23 (user's local time)
  confidence_score DECIMAL(3,2) NOT NULL,  -- 0.00 - 1.00
  sample_size INTEGER NOT NULL,
  last_calculated_at TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (user_id, goal_id, day_of_week)
);

CREATE INDEX idx_patterns_recalc 
  ON user_logging_patterns(last_calculated_at) 
  WHERE confidence_score > 0.3;

COMMENT ON COLUMN user_logging_patterns.day_of_week 
  IS '-1 for general pattern, 0-6 for day-specific. Using -1 instead of NULL because NULL cannot participate in composite primary keys reliably.';

COMMENT ON COLUMN user_logging_patterns.confidence_score 
  IS 'Based on sample size and consistency. <0.3 = unreliable, use defaults.';

-- Reminder history (prevent spam, track effectiveness)
CREATE TABLE reminder_history (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  reminder_tier VARCHAR(20) NOT NULL, -- 'gentle', 'supportive', 'last_chance'
  sent_at TIMESTAMPTZ DEFAULT NOW(),
  sent_at_local_date DATE NOT NULL,   -- User's local date when sent
  was_effective BOOLEAN,              -- Did user log same day after this?
  social_context JSONB,
  user_timezone VARCHAR(50) NOT NULL
);

CREATE INDEX idx_reminder_history_user_goal_date
  ON reminder_history(user_id, goal_id, sent_at_local_date DESC);

CREATE INDEX idx_reminder_history_user_date
  ON reminder_history(user_id, sent_at_local_date)
  WHERE was_effective IS NULL;

CREATE INDEX idx_reminder_history_effectiveness 
  ON reminder_history(reminder_tier, was_effective) 
  WHERE was_effective IS NOT NULL;

COMMENT ON COLUMN reminder_history.was_effective 
  IS 'TRUE if user logged the goal on the same local date after this reminder. NULL if not yet evaluated.';

COMMENT ON COLUMN reminder_history.sent_at_local_date
  IS 'User local date when sent. Critical for timezone-aware "already reminded today" checks.';

-- User reminder preferences (per-goal control)
CREATE TABLE user_reminder_preferences (
  user_id UUID REFERENCES users(id) ON DELETE CASCADE,
  goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
  enabled BOOLEAN DEFAULT TRUE,
  mode VARCHAR(20) DEFAULT 'smart',        -- 'smart', 'fixed', 'disabled'
  fixed_hour INTEGER,                      -- 0-23, used if mode='fixed'
  aggressiveness VARCHAR(20) DEFAULT 'balanced', -- 'gentle', 'balanced', 'persistent'
  quiet_hours_start INTEGER,               -- 0-23, NULL if no quiet hours
  quiet_hours_end INTEGER,                 -- 0-23, NULL if no quiet hours
  last_modified_at TIMESTAMPTZ DEFAULT NOW(),
  PRIMARY KEY (user_id, goal_id)
);

CREATE INDEX idx_preferences_enabled 
  ON user_reminder_preferences(user_id) 
  WHERE enabled = TRUE;

COMMENT ON COLUMN user_reminder_preferences.mode 
  IS 'smart = pattern-based, fixed = same time daily, disabled = never remind';

COMMENT ON COLUMN user_reminder_preferences.aggressiveness 
  IS 'gentle = last_chance only, balanced = all tiers, persistent = shorter delays';

-- =========================
-- Weekly Group Recap System
-- =========================

-- Weekly recaps sent: deduplication table
CREATE TABLE weekly_recaps_sent (
  group_id    UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  week_end    DATE NOT NULL,  -- Sunday date
  sent_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  PRIMARY KEY (group_id, week_end)
);

CREATE INDEX idx_weekly_recaps_sent_group ON weekly_recaps_sent(group_id);
CREATE INDEX idx_weekly_recaps_sent_week_end ON weekly_recaps_sent(week_end);

COMMENT ON TABLE weekly_recaps_sent IS 'Tracks which weekly recaps have been sent to prevent duplicate notifications';
COMMENT ON COLUMN weekly_recaps_sent.week_end IS 'Sunday date marking the end of the recap week (YYYY-MM-DD)';
COMMENT ON COLUMN group_memberships.weekly_recap_enabled IS 'Whether the member wants to receive weekly recap notifications for this group';

-- Group Suggestion Log (formerly challenge_suggestion_log)
CREATE TABLE group_suggestion_log (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  sent_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  dismissed_at TIMESTAMP WITH TIME ZONE,
  converted BOOLEAN DEFAULT FALSE,     -- User created/joined a challenge after this
  UNIQUE(user_id)
);

CREATE INDEX idx_group_suggestion_user ON group_suggestion_log(user_id);

COMMENT ON TABLE group_suggestion_log IS 'Tracks challenge suggestions sent to users to avoid nagging and enforce rate limits';

-- =========================
-- Public Group Discovery
-- =========================

-- Join requests from users wanting to join public groups
CREATE TABLE join_requests (
  id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id     UUID        NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id      UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  status       TEXT        NOT NULL DEFAULT 'pending'
    CHECK (status IN ('pending','approved','declined')),
  note         TEXT        CHECK (char_length(note) <= 300),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  reviewed_at  TIMESTAMPTZ,
  reviewed_by  UUID        REFERENCES users(id) ON DELETE SET NULL,
  UNIQUE (group_id, user_id)
);

CREATE INDEX join_requests_group_pending ON join_requests(group_id) WHERE status = 'pending';
CREATE INDEX join_requests_user ON join_requests(user_id, status);

-- Query embedding cache (JSONB for easy round-trip without vector parsing)
CREATE TABLE search_query_embeddings (
  query_text  TEXT        PRIMARY KEY,
  embedding   JSONB       NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days')
);
CREATE INDEX idx_search_query_embeddings_expires ON search_query_embeddings(expires_at);

-- Dismissed group suggestions (suppress for 30 days)
CREATE TABLE suggestion_dismissals (
  user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id      UUID        NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  dismissed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, group_id)
);

-- =========================
-- Content Moderation
-- =========================

CREATE INDEX idx_progress_entries_moderation
  ON progress_entries(moderation_status) WHERE moderation_status <> 'ok';

CREATE TABLE content_reports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  reporter_user_id UUID NOT NULL REFERENCES users(id),
  content_type TEXT NOT NULL CHECK (content_type IN ('progress_entry', 'group', 'username')),
  content_id UUID NOT NULL,
  reason TEXT NOT NULL,
  created_at TIMESTAMPTZ DEFAULT now(),
  reviewed BOOLEAN DEFAULT false,
  reviewed_at TIMESTAMPTZ,
  outcome TEXT
);
CREATE INDEX idx_content_reports_content ON content_reports(content_type, content_id);
CREATE INDEX idx_content_reports_reporter ON content_reports(reporter_user_id);
CREATE UNIQUE INDEX idx_content_reports_unique_reporter
  ON content_reports(reporter_user_id, content_type, content_id);

CREATE TABLE content_disputes (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id),
  content_type TEXT NOT NULL,
  content_id UUID NOT NULL,
  user_explanation TEXT,
  created_at TIMESTAMPTZ DEFAULT now(),
  resolved BOOLEAN DEFAULT false,
  resolved_at TIMESTAMPTZ,
  outcome TEXT
);
CREATE INDEX idx_content_disputes_user ON content_disputes(user_id);
CREATE INDEX idx_content_disputes_content ON content_disputes(content_type, content_id);

CREATE VIEW moderation_queue AS
SELECT
  pe.id,
  pe.moderation_status,
  pe.log_title,
  pe.note,
  pe.created_at,
  u.display_name AS username,
  g.name AS group_name,
  COUNT(cr.id) AS report_count,
  MAX(cr.created_at) AS latest_report_at
FROM progress_entries pe
JOIN users u ON pe.user_id = u.id
JOIN goals go ON pe.goal_id = go.id
JOIN groups g ON go.group_id = g.id
LEFT JOIN content_reports cr ON cr.content_id = pe.id AND cr.content_type = 'progress_entry'
WHERE pe.moderation_status IN ('flagged', 'hidden')
GROUP BY pe.id, u.display_name, g.name
ORDER BY latest_report_at DESC NULLS LAST;

-- =========================
-- Group Membership Views
-- =========================

CREATE OR REPLACE VIEW active_group_members AS
SELECT
    gm.id,
    gm.group_id,
    gm.user_id,
    gm.role,
    gm.joined_at,
    u.email,
    u.display_name,
    u.avatar_mime_type,
    CASE WHEN u.avatar_data IS NOT NULL THEN true ELSE false END AS has_avatar
FROM group_memberships gm
INNER JOIN users u ON gm.user_id = u.id
WHERE gm.status = 'active'
AND u.deleted_at IS NULL;

COMMENT ON VIEW active_group_members IS 'View of active group members with user details. Excludes pending and declined members.';

ALTER VIEW active_group_members SET (security_invoker = true);

CREATE OR REPLACE VIEW pending_group_members AS
SELECT
    gm.id,
    gm.group_id,
    gm.user_id,
    gm.joined_at AS requested_at,
    u.email,
    u.display_name,
    u.avatar_mime_type,
    CASE WHEN u.avatar_data IS NOT NULL THEN true ELSE false END AS has_avatar
FROM group_memberships gm
INNER JOIN users u ON gm.user_id = u.id
WHERE gm.status = 'pending'
AND u.deleted_at IS NULL;

COMMENT ON VIEW pending_group_members IS 'View of pending join requests with user details';

ALTER VIEW pending_group_members SET (security_invoker = true);

-- =========================
-- Group Membership Helper Functions and Triggers
-- =========================

CREATE OR REPLACE FUNCTION get_pending_member_count(p_group_id UUID)
RETURNS INTEGER
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    pending_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO pending_count
    FROM group_memberships
    WHERE group_id = p_group_id
    AND status = 'pending';
    RETURN pending_count;
END;
$$;

COMMENT ON FUNCTION get_pending_member_count IS 'Returns count of pending members for a given group';

CREATE OR REPLACE FUNCTION is_group_admin(p_user_id UUID, p_group_id UUID)
RETURNS BOOLEAN
LANGUAGE plpgsql
STABLE
AS $$
DECLARE
    user_role VARCHAR(20);
BEGIN
    SELECT role INTO user_role
    FROM group_memberships
    WHERE user_id = p_user_id
    AND group_id = p_group_id
    AND status = 'active'
    LIMIT 1;
    RETURN user_role IN ('creator', 'admin');
END;
$$;

COMMENT ON FUNCTION is_group_admin IS 'Returns true if user is an active admin or creator of the group';

CREATE OR REPLACE FUNCTION prevent_duplicate_pending_requests()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    existing_pending_count INTEGER;
BEGIN
    IF NEW.status = 'pending' THEN
        SELECT COUNT(*) INTO existing_pending_count
        FROM group_memberships
        WHERE group_id = NEW.group_id
        AND user_id = NEW.user_id
        AND status = 'pending'
        AND id != NEW.id;
        IF existing_pending_count > 0 THEN
            RAISE EXCEPTION 'User already has a pending request for this group'
                USING ERRCODE = '23505',
                      DETAIL = 'user_id: ' || NEW.user_id || ', group_id: ' || NEW.group_id;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS check_duplicate_pending_requests ON group_memberships;
CREATE TRIGGER check_duplicate_pending_requests
BEFORE INSERT OR UPDATE ON group_memberships
FOR EACH ROW
EXECUTE FUNCTION prevent_duplicate_pending_requests();

COMMENT ON TRIGGER check_duplicate_pending_requests ON group_memberships IS 'Prevents duplicate pending requests from same user to same group';
