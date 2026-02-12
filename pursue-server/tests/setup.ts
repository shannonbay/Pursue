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

  // Create users table
  await sql`
    CREATE TABLE IF NOT EXISTS users (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      email VARCHAR(255) UNIQUE NOT NULL,
      display_name VARCHAR(100) NOT NULL,
      avatar_data BYTEA,
      avatar_mime_type VARCHAR(50),
      password_hash VARCHAR(255),
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
      icon_data BYTEA,
      icon_mime_type VARCHAR(50),
      creator_user_id UUID NOT NULL REFERENCES users(id),
      created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
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
    END $$;
  `.execute(db);

  // Create group_memberships table
  await sql`
    CREATE TABLE IF NOT EXISTS group_memberships (
      id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
      group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
      user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
      role VARCHAR(20) NOT NULL,
      status VARCHAR(20) NOT NULL DEFAULT 'active',
      joined_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
      UNIQUE(group_id, user_id)
    )
  `.execute(db);

  // Add status column if table already existed without it (e.g. from older test runs)
  await sql`
    DO $$
    BEGIN
      IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'group_memberships' AND column_name = 'status'
      ) THEN
        ALTER TABLE group_memberships ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';
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
  // Delete all data in reverse order of dependencies
  await db.deleteFrom('photo_upload_log').execute();
  await db.deleteFrom('progress_photos').execute();
  await db.deleteFrom('progress_entries').execute();
  await db.deleteFrom('goals').execute();
  await db.deleteFrom('activity_reactions').execute();
  await db.deleteFrom('group_activities').execute();
  await db.deleteFrom('invite_codes').execute();
  await db.deleteFrom('group_memberships').execute();
  await db.deleteFrom('subscription_transactions').execute();
  await db.deleteFrom('subscription_downgrade_history').execute();
  await db.deleteFrom('groups').execute();
  await db.deleteFrom('user_subscriptions').execute();
  await db.deleteFrom('devices').execute();
  await db.deleteFrom('password_reset_tokens').execute();
  await db.deleteFrom('refresh_tokens').execute();
  await db.deleteFrom('auth_providers').execute();
  await db.deleteFrom('user_consents').execute();
  await db.deleteFrom('users').execute();
}

export { testDb };
