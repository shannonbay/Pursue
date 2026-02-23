import dotenv from 'dotenv';
import path from 'path';

// Load .env.test file
dotenv.config({ path: path.resolve(process.cwd(), '.env.test') });

import pg from 'pg';
import { Kysely, PostgresDialect, sql } from 'kysely';
import type { Database } from '../src/database/types';

const { Pool } = pg;

// Ensure test environment variables are set
process.env.NODE_ENV = 'test';
if (!process.env.JWT_SECRET) {
  process.env.JWT_SECRET = 'test-jwt-secret-key-for-testing-purposes-only';
}
if (!process.env.JWT_REFRESH_SECRET) {
  process.env.JWT_REFRESH_SECRET = 'test-jwt-refresh-secret-key-for-testing';
}
if (!process.env.GOOGLE_CLIENT_ID) {
  process.env.GOOGLE_CLIENT_ID = 'test-google-client-id';
}
if (!process.env.CONSENT_HASH_SALT) {
  process.env.CONSENT_HASH_SALT = 'test-consent-hash-salt-for-testing';
}
if (!process.env.INTERNAL_JOB_KEY) {
  process.env.INTERNAL_JOB_KEY = 'test-internal-job-key-for-testing';
}

const TEST_DATABASE_URL = process.env.TEST_DATABASE_URL ||
  'postgresql://postgres:postgres@localhost:5432/pursue_test';

// Also set DATABASE_URL so the app's database module uses the test database
process.env.DATABASE_URL = TEST_DATABASE_URL;

let pool: pg.Pool;
let testDb: Kysely<Database>;

// Run once before all tests
beforeAll(async () => {
  pool = new Pool({ connectionString: TEST_DATABASE_URL });
  testDb = new Kysely<Database>({ dialect: new PostgresDialect({ pool }) });

  // Create tables if they don't exist (run schema)
  await createSchema(testDb);

  console.log('Test database setup complete');
});

// Run after all tests
afterAll(async () => {
  await testDb.destroy();
  console.log('Test database connection closed');
});

// Run before each test (clean slate)
beforeEach(async () => {
  await cleanDatabase(testDb);
});

async function createSchema(db: Kysely<Database>) {
  // Enable UUID extension (wrapped in try-catch to handle parallel test runs)
  try {
    await sql`CREATE EXTENSION IF NOT EXISTS "uuid-ossp"`.execute(db);
  } catch (error: unknown) {
    // Ignore duplicate key error from parallel test runs
    if (error instanceof Error && !error.message.includes('duplicate key')) {
      throw error;
    }
  }

  // Enable pg_trgm for fuzzy search tests
  await sql`CREATE EXTENSION IF NOT EXISTS pg_trgm`.execute(db);

  // Enable pgvector for semantic search (gracefully skip if extension not installed)
  try {
    await sql`CREATE EXTENSION IF NOT EXISTS vector`.execute(db);
  } catch (error: unknown) {
    // pgvector not installed — semantic search will be disabled, trigram fallback applies
    if (error instanceof Error && !error.message.includes('duplicate key')) {
      // Non-fatal: tests proceed with trigram-only mode
    }
  }

  // Create users table
  await sql`
    CREATE TABLE IF NOT EXISTS users (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      email VARCHAR(255) UNIQUE NOT NULL,
      display_name VARCHAR(100) NOT NULL,
      avatar_data BYTEA,
      avatar_mime_type VARCHAR(50),
      password_hash VARCHAR(255),
      timezone VARCHAR(50) DEFAULT 'UTC',
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      deleted_at TIMESTAMP WITH TIME ZONE,
      current_subscription_tier VARCHAR(20) NOT NULL DEFAULT 'free',
      subscription_status VARCHAR(20) NOT NULL DEFAULT 'active',
      group_limit INTEGER NOT NULL DEFAULT 1,
      current_group_count INTEGER NOT NULL DEFAULT 0
    )
  `.execute(db);

  // Add missing columns to users table if they don't exist (for existing databases)
  await sql`
    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'avatar_data'
      ) THEN
        ALTER TABLE users ADD COLUMN avatar_data BYTEA;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'avatar_mime_type'
      ) THEN
        ALTER TABLE users ADD COLUMN avatar_mime_type VARCHAR(50);
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'deleted_at'
      ) THEN
        ALTER TABLE users ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'current_subscription_tier'
      ) THEN
        ALTER TABLE users ADD COLUMN current_subscription_tier VARCHAR(20) NOT NULL DEFAULT 'free';
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'subscription_status'
      ) THEN
        ALTER TABLE users ADD COLUMN subscription_status VARCHAR(20) NOT NULL DEFAULT 'active';
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'group_limit'
      ) THEN
        ALTER TABLE users ADD COLUMN group_limit INTEGER NOT NULL DEFAULT 1;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'current_group_count'
      ) THEN
        ALTER TABLE users ADD COLUMN current_group_count INTEGER NOT NULL DEFAULT 0;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'timezone'
      ) THEN
        ALTER TABLE users ADD COLUMN timezone VARCHAR(50) DEFAULT 'UTC';
      END IF;
    END $$;
  `.execute(db);

  // Create auth_providers table
  await sql`
    CREATE TABLE IF NOT EXISTS auth_providers (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      provider VARCHAR(50) NOT NULL,
      provider_user_id VARCHAR(255) NOT NULL,
      provider_email VARCHAR(255),
      linked_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      UNIQUE(provider, provider_user_id),
      UNIQUE(user_id, provider)
    )
  `.execute(db);

  // Create refresh_tokens table
  await sql`
    CREATE TABLE IF NOT EXISTS refresh_tokens (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      token_hash VARCHAR(255) NOT NULL,
      expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      revoked_at TIMESTAMP WITH TIME ZONE
    )
  `.execute(db);

  // Create password_reset_tokens table
  await sql`
    CREATE TABLE IF NOT EXISTS password_reset_tokens (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      token_hash VARCHAR(255) NOT NULL,
      expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      used_at TIMESTAMP WITH TIME ZONE
    )
  `.execute(db);

  // Create devices table
  await sql`
    CREATE TABLE IF NOT EXISTS devices (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      fcm_token VARCHAR(256) UNIQUE NOT NULL,
      device_name VARCHAR(128),
      platform VARCHAR(20),
      last_active TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
    )
  `.execute(db);

  // Create groups table
  await sql`
    CREATE TABLE IF NOT EXISTS groups (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      name VARCHAR(100) NOT NULL,
      description TEXT,
      icon_emoji VARCHAR(10),
      icon_color VARCHAR(7),
      icon_url TEXT,
      icon_data BYTEA,
      icon_mime_type VARCHAR(50),
      creator_user_id UUID NOT NULL REFERENCES users(id),
      is_challenge BOOLEAN NOT NULL DEFAULT FALSE,
      challenge_start_date DATE,
      challenge_end_date DATE,
      challenge_template_id UUID,
      challenge_status VARCHAR(20),
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      deleted_at TIMESTAMP WITH TIME ZONE
    )
  `.execute(db);

  // Add missing columns if they don't exist (for existing databases)
  await sql`
    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'icon_color'
      ) THEN
        ALTER TABLE groups ADD COLUMN icon_color VARCHAR(7);
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'icon_data'
      ) THEN
        ALTER TABLE groups ADD COLUMN icon_data BYTEA;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'icon_mime_type'
      ) THEN
        ALTER TABLE groups ADD COLUMN icon_mime_type VARCHAR(50);
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'deleted_at'
      ) THEN
        ALTER TABLE groups ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'is_challenge'
      ) THEN
        ALTER TABLE groups ADD COLUMN is_challenge BOOLEAN NOT NULL DEFAULT FALSE;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'challenge_start_date'
      ) THEN
        ALTER TABLE groups ADD COLUMN challenge_start_date DATE;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'challenge_end_date'
      ) THEN
        ALTER TABLE groups ADD COLUMN challenge_end_date DATE;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'challenge_template_id'
      ) THEN
        ALTER TABLE groups ADD COLUMN challenge_template_id UUID;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'challenge_status'
      ) THEN
        ALTER TABLE groups ADD COLUMN challenge_status VARCHAR(20);
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'challenge_invite_card_data'
      ) THEN
        ALTER TABLE groups ADD COLUMN challenge_invite_card_data JSONB;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'icon_url'
      ) THEN
        ALTER TABLE groups ADD COLUMN icon_url TEXT;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'visibility'
      ) THEN
        ALTER TABLE groups ADD COLUMN visibility TEXT NOT NULL DEFAULT 'private'
          CHECK (visibility IN ('public', 'private'));
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'category'
      ) THEN
        ALTER TABLE groups ADD COLUMN category TEXT
          CHECK (category IN ('fitness','nutrition','mindfulness','learning','creativity','productivity','finance','social','lifestyle','sports','other'));
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'spot_limit'
      ) THEN
        ALTER TABLE groups ADD COLUMN spot_limit INTEGER
          CHECK (spot_limit IS NULL OR (spot_limit >= 2 AND spot_limit <= 500));
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'auto_approve'
      ) THEN
        ALTER TABLE groups ADD COLUMN auto_approve BOOLEAN NOT NULL DEFAULT FALSE;
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'comm_platform'
      ) THEN
        ALTER TABLE groups ADD COLUMN comm_platform TEXT
          CHECK (comm_platform IS NULL OR comm_platform IN ('discord','whatsapp','telegram'));
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'comm_link'
      ) THEN
        ALTER TABLE groups ADD COLUMN comm_link TEXT;
      END IF;
    END $$;
  `.execute(db);

  // Add interest_categories to users (for onboarding interest quiz)
  await sql`
    DO $$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'users' AND column_name = 'interest_categories'
      ) THEN
        ALTER TABLE users ADD COLUMN interest_categories TEXT[] NOT NULL DEFAULT '{}';
      END IF;
    END $$
  `.execute(db);

  await sql`
    CREATE TABLE IF NOT EXISTS challenge_templates (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      slug VARCHAR(100) UNIQUE NOT NULL,
      title VARCHAR(200) NOT NULL,
      description TEXT NOT NULL,
      icon_emoji VARCHAR(10) NOT NULL,
      icon_url TEXT,
      duration_days INTEGER NOT NULL,
      category VARCHAR(50) NOT NULL,
      difficulty VARCHAR(20) NOT NULL DEFAULT 'moderate',
      is_featured BOOLEAN NOT NULL DEFAULT FALSE,
      sort_order INTEGER NOT NULL DEFAULT 0,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_challenge_templates_category ON challenge_templates(category, sort_order)`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_challenge_templates_featured ON challenge_templates(is_featured, sort_order) WHERE is_featured = TRUE`.execute(db);
  await sql`
    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'challenge_templates' AND column_name = 'icon_url'
      ) THEN
        ALTER TABLE challenge_templates ADD COLUMN icon_url TEXT;
      END IF;
    END $$;
  `.execute(db);

  await sql`
    CREATE TABLE IF NOT EXISTS challenge_template_goals (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      template_id UUID NOT NULL REFERENCES challenge_templates(id) ON DELETE CASCADE,
      title VARCHAR(200) NOT NULL,
      description TEXT,
      cadence VARCHAR(20) NOT NULL,
      metric_type VARCHAR(20) NOT NULL,
      target_value DECIMAL(10,2),
      unit VARCHAR(50),
      sort_order INTEGER NOT NULL DEFAULT 0,
      UNIQUE(template_id, sort_order)
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_template_goals_template ON challenge_template_goals(template_id, sort_order)`.execute(db);

  await sql`
    DO $$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_groups_challenge_fields'
      ) THEN
        ALTER TABLE groups ADD CONSTRAINT chk_groups_challenge_fields CHECK (
          (is_challenge = FALSE AND challenge_start_date IS NULL AND challenge_end_date IS NULL AND challenge_status IS NULL)
          OR
          (is_challenge = TRUE AND challenge_start_date IS NOT NULL AND challenge_end_date IS NOT NULL AND challenge_status IS NOT NULL)
        );
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_groups_challenge_dates'
      ) THEN
        ALTER TABLE groups ADD CONSTRAINT chk_groups_challenge_dates CHECK (
          challenge_end_date IS NULL OR challenge_end_date >= challenge_start_date
        );
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'chk_groups_challenge_status'
      ) THEN
        ALTER TABLE groups ADD CONSTRAINT chk_groups_challenge_status CHECK (
          challenge_status IS NULL OR challenge_status IN ('upcoming', 'active', 'completed', 'cancelled')
        );
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'fk_groups_challenge_template'
      ) THEN
        ALTER TABLE groups
        ADD CONSTRAINT fk_groups_challenge_template
        FOREIGN KEY (challenge_template_id) REFERENCES challenge_templates(id) ON DELETE SET NULL;
      END IF;
    END $$;
  `.execute(db);

  await sql`CREATE INDEX IF NOT EXISTS idx_groups_challenge_status ON groups(is_challenge, challenge_status) WHERE is_challenge = TRUE`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_groups_challenge_template ON groups(challenge_template_id) WHERE challenge_template_id IS NOT NULL`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_groups_name_trgm ON groups USING gin (name gin_trgm_ops)`.execute(db);

  // Add search_embedding column to groups (for pgvector hybrid search)
  await sql`
    DO $$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'groups' AND column_name = 'search_embedding'
      ) THEN
        BEGIN
          ALTER TABLE groups ADD COLUMN search_embedding vector(1536);
        EXCEPTION WHEN undefined_object THEN
          -- pgvector extension not available — skip silently
          NULL;
        END;
      END IF;
    END $$
  `.execute(db);

  // Create query embedding cache table
  await sql`
    CREATE TABLE IF NOT EXISTS search_query_embeddings (
      query_text  TEXT        PRIMARY KEY,
      embedding   JSONB       NOT NULL,
      created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      expires_at  TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days')
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_search_query_embeddings_expires ON search_query_embeddings(expires_at)`.execute(db);

  // Create group_memberships table
  await sql`
    CREATE TABLE IF NOT EXISTS group_memberships (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      role VARCHAR(20) NOT NULL,
      status VARCHAR(20) NOT NULL DEFAULT 'active',
      joined_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      weekly_recap_enabled BOOLEAN NOT NULL DEFAULT TRUE,
      UNIQUE(group_id, user_id)
    )
  `.execute(db);

  // Add columns if table already existed without them (e.g. from older test runs)
  await sql`
    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'group_memberships' AND column_name = 'status'
      ) THEN
        ALTER TABLE group_memberships ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';
      END IF;
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'group_memberships' AND column_name = 'weekly_recap_enabled'
      ) THEN
        ALTER TABLE group_memberships ADD COLUMN weekly_recap_enabled BOOLEAN NOT NULL DEFAULT TRUE;
      END IF;
    END $$
  `.execute(db);

  // Create invite_codes table (one active code per group; revoked_at null = active)
  await sql`
    DROP TABLE IF EXISTS invite_codes
  `.execute(db);
  await sql`
    CREATE TABLE invite_codes (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
      code VARCHAR(50) UNIQUE NOT NULL,
      created_by_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      revoked_at TIMESTAMP WITH TIME ZONE
    )
  `.execute(db);
  await sql`
    CREATE UNIQUE INDEX idx_invite_codes_active_per_group ON invite_codes(group_id)
    WHERE revoked_at IS NULL
  `.execute(db);
  await sql`
    CREATE INDEX idx_invite_codes_code ON invite_codes(code)
  `.execute(db);

  // Create user_subscriptions table
  await sql`
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
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_user_subscriptions_user_id ON user_subscriptions(user_id)`.execute(db);

  // Create subscription_downgrade_history table
  await sql`
    CREATE TABLE IF NOT EXISTS subscription_downgrade_history (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      downgrade_date TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      previous_tier VARCHAR(20) NOT NULL,
      groups_before_downgrade INTEGER NOT NULL,
      kept_group_id UUID REFERENCES groups(id) ON DELETE SET NULL,
      removed_group_ids UUID[] NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_subscription_downgrade_history_user_id ON subscription_downgrade_history(user_id)`.execute(db);

  // Create subscription_transactions table (after user_subscriptions)
  await sql`
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
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_subscription_transactions_subscription_id ON subscription_transactions(subscription_id)`.execute(db);

  // Create user_consents table
  await sql`
    CREATE TABLE IF NOT EXISTS user_consents (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID REFERENCES users(id) ON DELETE SET NULL,
      consent_type VARCHAR(50) NOT NULL,
      agreed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
      ip_address VARCHAR(45),
      email_hash VARCHAR(64)
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_user_consents_user_id ON user_consents(user_id)`.execute(db);
  // Migrate: make user_id nullable with SET NULL (was NOT NULL CASCADE)
  await sql`ALTER TABLE user_consents ALTER COLUMN user_id DROP NOT NULL`.execute(db);
  // Replace FK constraint if it still uses CASCADE
  await sql`
    DO $$ BEGIN
      IF EXISTS (
        SELECT 1 FROM information_schema.table_constraints
        WHERE table_name = 'user_consents' AND constraint_name = 'user_consents_user_id_fkey'
      ) THEN
        ALTER TABLE user_consents DROP CONSTRAINT user_consents_user_id_fkey;
        ALTER TABLE user_consents ADD CONSTRAINT user_consents_user_id_fkey
          FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;
      END IF;
    END $$
  `.execute(db);
  // Add email_hash column if not exists (for existing test databases)
  await sql`
    DO $$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'user_consents' AND column_name = 'email_hash'
      ) THEN
        ALTER TABLE user_consents ADD COLUMN email_hash VARCHAR(64);
      END IF;
    END $$
  `.execute(db);

  // Create goals table
  await sql`
    CREATE TABLE IF NOT EXISTS goals (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
      title VARCHAR(200) NOT NULL,
      description TEXT,
      cadence VARCHAR(20) NOT NULL,
      metric_type VARCHAR(20) NOT NULL,
      target_value DECIMAL(10,2),
      unit VARCHAR(50),
      created_by_user_id UUID REFERENCES users(id),
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      deleted_at TIMESTAMP WITH TIME ZONE,
      deleted_by_user_id UUID REFERENCES users(id)
    )
  `.execute(db);

  // Add active_days column to goals (migration for existing test databases)
  await sql`
    DO $$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'goals' AND column_name = 'active_days'
      ) THEN
        ALTER TABLE goals ADD COLUMN active_days INTEGER DEFAULT NULL;
      END IF;
    END $$
  `.execute(db);

  // Add active_days column to challenge_template_goals (migration for existing test databases)
  await sql`
    DO $$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'challenge_template_goals' AND column_name = 'active_days'
      ) THEN
        ALTER TABLE challenge_template_goals ADD COLUMN active_days INTEGER DEFAULT NULL;
      END IF;
    END $$
  `.execute(db);

  await sql`CREATE INDEX IF NOT EXISTS idx_goals_title_trgm ON goals USING gin (title gin_trgm_ops)`.execute(db);

  // Create progress_entries table
  await sql`
    CREATE TABLE IF NOT EXISTS progress_entries (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      goal_id UUID NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      value DECIMAL(10,2) NOT NULL,
      note TEXT,
      logged_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      period_start DATE NOT NULL,
      user_timezone VARCHAR(50),
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
    )
  `.execute(db);

  // Create group_activities table
  await sql`
    CREATE TABLE IF NOT EXISTS group_activities (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
      user_id UUID REFERENCES users(id) ON DELETE SET NULL,
      activity_type VARCHAR(50) NOT NULL,
      metadata JSONB,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
    )
  `.execute(db);

  // Create activity_reactions table
  await sql`
    CREATE TABLE IF NOT EXISTS activity_reactions (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      activity_id UUID NOT NULL REFERENCES group_activities(id) ON DELETE CASCADE,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      emoji VARCHAR(10) NOT NULL,
      created_at TIMESTAMPTZ DEFAULT NOW(),
      CONSTRAINT uq_reaction_user_activity UNIQUE (activity_id, user_id)
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_reactions_activity ON activity_reactions(activity_id)`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_reactions_user ON activity_reactions(user_id)`.execute(db);

  // Create nudges table
  await sql`
    CREATE TABLE IF NOT EXISTS nudges (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      sender_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      recipient_user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
      goal_id UUID REFERENCES goals(id) ON DELETE SET NULL,
      sent_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      sender_local_date DATE NOT NULL
    )
  `.execute(db);
  await sql`CREATE UNIQUE INDEX IF NOT EXISTS idx_nudges_daily_limit ON nudges(sender_user_id, recipient_user_id, sender_local_date)`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_nudges_recipient ON nudges(recipient_user_id, sent_at DESC)`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_nudges_sender_daily ON nudges(sender_user_id, sender_local_date)`.execute(db);

  // Create user_notifications table
  await sql`
    CREATE TABLE IF NOT EXISTS user_notifications (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      type VARCHAR(50) NOT NULL,
      actor_user_id UUID REFERENCES users(id) ON DELETE SET NULL,
      group_id UUID REFERENCES groups(id) ON DELETE CASCADE,
      goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
      progress_entry_id UUID REFERENCES progress_entries(id) ON DELETE CASCADE,
      metadata JSONB,
      is_read BOOLEAN NOT NULL DEFAULT FALSE,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_user_notifications_user ON user_notifications(user_id, created_at DESC)`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_user_notifications_unread ON user_notifications(user_id) WHERE is_read = FALSE`.execute(db);

  // Add shareable_card_data column if not exists (migration for existing test databases)
  await sql`
    DO $$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'user_notifications' AND column_name = 'shareable_card_data'
      ) THEN
        ALTER TABLE user_notifications ADD COLUMN shareable_card_data JSONB;
      END IF;
    END $$
  `.execute(db);
  await sql`
    CREATE INDEX IF NOT EXISTS idx_user_notifications_shareable
    ON user_notifications(user_id, type) WHERE shareable_card_data IS NOT NULL
  `.execute(db);

  await sql`
    CREATE TABLE IF NOT EXISTS challenge_completion_push_queue (
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
    )
  `.execute(db);
  await sql`
    CREATE INDEX IF NOT EXISTS idx_challenge_completion_push_queue_pending
    ON challenge_completion_push_queue(status, send_at)
    WHERE status = 'pending'
  `.execute(db);

  // Create user_milestone_grants table
  await sql`
    CREATE TABLE IF NOT EXISTS user_milestone_grants (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      milestone_key VARCHAR(100) NOT NULL,
      goal_id UUID REFERENCES goals(id) ON DELETE SET NULL,
      granted_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
      UNIQUE(user_id, milestone_key)
    )
  `.execute(db);
  await sql`
    DO $$ BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'user_milestone_grants' AND column_name = 'goal_id'
      ) THEN
        ALTER TABLE user_milestone_grants
          ADD COLUMN goal_id UUID REFERENCES goals(id) ON DELETE SET NULL;
      END IF;
    END $$
  `.execute(db);

  // Create referral_tokens table
  await sql`
    CREATE TABLE IF NOT EXISTS referral_tokens (
      token VARCHAR(12) PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      UNIQUE(user_id)
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_referral_tokens_user ON referral_tokens(user_id)`.execute(db);

  // Create progress_photos table
  await sql`
    CREATE TABLE IF NOT EXISTS progress_photos (
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
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_photos_entry ON progress_photos(progress_entry_id)`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_photos_user ON progress_photos(user_id)`.execute(db);

  // Create photo_upload_log table
  await sql`
    CREATE TABLE IF NOT EXISTS photo_upload_log (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_upload_log_user_time ON photo_upload_log(user_id, uploaded_at DESC)`.execute(db);

  // Create group_heat table
  await sql`
    CREATE TABLE IF NOT EXISTS group_heat (
      group_id UUID PRIMARY KEY REFERENCES groups(id) ON DELETE CASCADE,
      heat_score DECIMAL(5,2) NOT NULL DEFAULT 0.00,
      heat_tier SMALLINT NOT NULL DEFAULT 0,
      last_calculated_at TIMESTAMP WITH TIME ZONE,
      streak_days INTEGER NOT NULL DEFAULT 0,
      peak_score DECIMAL(5,2) NOT NULL DEFAULT 0.00,
      peak_date DATE,
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
    )
  `.execute(db);

  // Create group_daily_gcr table
  await sql`
    CREATE TABLE IF NOT EXISTS group_daily_gcr (
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
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_gcr_group_date ON group_daily_gcr(group_id, date DESC)`.execute(db);

  // Create user_logging_patterns table
  await sql`
    CREATE TABLE IF NOT EXISTS user_logging_patterns (
      user_id UUID REFERENCES users(id) ON DELETE CASCADE,
      goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
      day_of_week INTEGER NOT NULL DEFAULT -1,
      typical_hour_start INTEGER NOT NULL,
      typical_hour_end INTEGER NOT NULL,
      confidence_score DECIMAL(3,2) NOT NULL,
      sample_size INTEGER NOT NULL,
      last_calculated_at TIMESTAMPTZ DEFAULT NOW(),
      PRIMARY KEY (user_id, goal_id, day_of_week)
    )
  `.execute(db);

  // Create reminder_history table
  await sql`
    CREATE TABLE IF NOT EXISTS reminder_history (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID REFERENCES users(id) ON DELETE CASCADE,
      goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
      reminder_tier VARCHAR(20) NOT NULL,
      sent_at TIMESTAMPTZ DEFAULT NOW(),
      sent_at_local_date DATE NOT NULL,
      was_effective BOOLEAN,
      social_context JSONB,
      user_timezone VARCHAR(50) NOT NULL
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_reminder_history_user_goal_date ON reminder_history(user_id, goal_id, sent_at_local_date DESC)`.execute(db);

  // Create user_reminder_preferences table
  await sql`
    CREATE TABLE IF NOT EXISTS user_reminder_preferences (
      user_id UUID REFERENCES users(id) ON DELETE CASCADE,
      goal_id UUID REFERENCES goals(id) ON DELETE CASCADE,
      enabled BOOLEAN DEFAULT TRUE,
      mode VARCHAR(20) DEFAULT 'smart',
      fixed_hour INTEGER,
      aggressiveness VARCHAR(20) DEFAULT 'balanced',
      quiet_hours_start INTEGER,
      quiet_hours_end INTEGER,
      last_modified_at TIMESTAMPTZ DEFAULT NOW(),
      PRIMARY KEY (user_id, goal_id)
    )
  `.execute(db);

  // Create weekly_recaps_sent table
  await sql`
    CREATE TABLE IF NOT EXISTS weekly_recaps_sent (
      group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
      week_end DATE NOT NULL,
      sent_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
      PRIMARY KEY (group_id, week_end)
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_weekly_recaps_sent_group ON weekly_recaps_sent(group_id)`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_weekly_recaps_sent_week_end ON weekly_recaps_sent(week_end)`.execute(db);

  // Create challenge_suggestion_log table
  await sql`
    CREATE TABLE IF NOT EXISTS challenge_suggestion_log (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      sent_at TIMESTAMPTZ DEFAULT NOW(),
      dismissed_at TIMESTAMPTZ,
      converted BOOLEAN DEFAULT FALSE,
      UNIQUE(user_id)
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS idx_challenge_suggestion_user ON challenge_suggestion_log(user_id)`.execute(db);

  // Create join_requests table (public group discovery)
  await sql`
    CREATE TABLE IF NOT EXISTS join_requests (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      status TEXT NOT NULL DEFAULT 'pending'
        CHECK (status IN ('pending','approved','declined')),
      note TEXT CHECK (char_length(note) <= 300),
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      reviewed_at TIMESTAMPTZ,
      reviewed_by UUID REFERENCES users(id) ON DELETE SET NULL,
      UNIQUE (group_id, user_id)
    )
  `.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS join_requests_group_pending ON join_requests(group_id) WHERE status = 'pending'`.execute(db);
  await sql`CREATE INDEX IF NOT EXISTS join_requests_user ON join_requests(user_id, status)`.execute(db);

  // Create suggestion_dismissals table
  await sql`
    CREATE TABLE IF NOT EXISTS suggestion_dismissals (
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
      dismissed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (user_id, group_id)
    )
  `.execute(db);

  // Fix FK constraints for goals (CREATE TABLE IF NOT EXISTS won't update existing constraints)
  await sql`
    ALTER TABLE goals DROP CONSTRAINT IF EXISTS goals_created_by_user_id_fkey;
    ALTER TABLE goals ADD CONSTRAINT goals_created_by_user_id_fkey
      FOREIGN KEY (created_by_user_id) REFERENCES users(id) ON DELETE SET NULL;

    ALTER TABLE goals DROP CONSTRAINT IF EXISTS goals_deleted_by_user_id_fkey;
    ALTER TABLE goals ADD CONSTRAINT goals_deleted_by_user_id_fkey
      FOREIGN KEY (deleted_by_user_id) REFERENCES users(id) ON DELETE SET NULL;
  `.execute(db);

  // SQL function for user deletion (privacy-critical path)
  await sql`
    CREATE OR REPLACE FUNCTION delete_user_data(p_user_id UUID)
    RETURNS VOID AS $$
    BEGIN
      DELETE FROM users WHERE id = p_user_id;
      IF NOT FOUND THEN
        RAISE EXCEPTION 'USER_NOT_FOUND: %', p_user_id;
      END IF;
    END;
    $$ LANGUAGE plpgsql
  `.execute(db);
}

async function cleanDatabase(db: Kysely<Database>) {
  // Use TRUNCATE to clean all tables at once - handles FK dependencies automatically
  // All tables listed explicitly to avoid issues with CASCADE not reaching all tables
  await sql`
    TRUNCATE TABLE
      search_query_embeddings,
      challenge_completion_push_queue,
      user_notifications,
      user_milestone_grants,
      referral_tokens,
      activity_reactions,
      nudges,
      progress_photos,
      photo_upload_log,
      user_logging_patterns,
      reminder_history,
      user_reminder_preferences,
      weekly_recaps_sent,
      challenge_template_goals,
      challenge_templates,
      progress_entries,
      goals,
      group_activities,
      group_heat,
      group_daily_gcr,
      join_requests,
      suggestion_dismissals,
      challenge_suggestion_log,
      group_memberships,
      invite_codes,
      groups,
      subscription_transactions,
      subscription_downgrade_history,
      user_subscriptions,
      devices,
      user_consents,
      password_reset_tokens,
      refresh_tokens,
      auth_providers,
      users
    RESTART IDENTITY CASCADE
  `.execute(db);
}

export { testDb };
