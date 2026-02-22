-- Migration: 20260222_public_group_listings.sql
-- Description: Public Group Listings & Discovery feature
--   - groups: visibility, category, spot_limit, comm_platform, comm_link, auto_approve
--   - users: interest_categories
--   - new tables: join_requests, suggestion_dismissals

BEGIN;

-- ============================================================
-- Groups: public listing fields
-- ============================================================

ALTER TABLE groups
  ADD COLUMN visibility     TEXT NOT NULL DEFAULT 'private'
    CHECK (visibility IN ('public', 'private')),
  ADD COLUMN category       TEXT
    CHECK (category IN ('fitness','nutrition','mindfulness','learning','creativity','productivity','finance','social','lifestyle','sports','other')),
  ADD COLUMN spot_limit     INTEGER
    CHECK (spot_limit IS NULL OR (spot_limit >= 2 AND spot_limit <= 500)),
  ADD COLUMN auto_approve   BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN comm_platform  TEXT
    CHECK (comm_platform IS NULL OR comm_platform IN ('discord','whatsapp','telegram')),
  ADD COLUMN comm_link      TEXT;

-- Partial index for public group discovery queries
CREATE INDEX idx_groups_public ON groups(visibility, deleted_at)
  WHERE visibility = 'public';

-- ============================================================
-- Users: interest categories from onboarding quiz
-- ============================================================

ALTER TABLE users
  ADD COLUMN interest_categories TEXT[] NOT NULL DEFAULT '{}';

-- ============================================================
-- Join requests table
-- ============================================================

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

CREATE INDEX join_requests_group_pending ON join_requests(group_id)
  WHERE status = 'pending';

CREATE INDEX join_requests_user ON join_requests(user_id, status);

-- ============================================================
-- Suggestion dismissals table
-- ============================================================

CREATE TABLE suggestion_dismissals (
  user_id       UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  group_id      UUID        NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  dismissed_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (user_id, group_id)
);

COMMIT;
