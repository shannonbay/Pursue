-- Migration: Add resource cap indexes and trigger functions
-- Up: apply indexes and trigger functions

-- Helpful composite/indexes for counting and enforcement
CREATE INDEX IF NOT EXISTS idx_groups_creator_active ON groups(creator_user_id) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_memberships_group_role ON group_memberships(group_id, role);
CREATE INDEX IF NOT EXISTS idx_progress_user_period ON progress_entries(user_id, period_start);
CREATE INDEX IF NOT EXISTS idx_devices_user_created_at ON devices(user_id, created_at DESC);

-- Drop triggers if they exist (to allow idempotent re-runs)
DROP TRIGGER IF EXISTS trg_enforce_user_group_creation_limit ON groups;
DROP TRIGGER IF EXISTS trg_enforce_group_members_and_user_join_limits ON group_memberships;
DROP TRIGGER IF EXISTS trg_enforce_group_goals_limit ON goals;
DROP TRIGGER IF EXISTS trg_enforce_invite_max_uses ON invite_codes;

-- Drop functions if they exist
DROP FUNCTION IF EXISTS enforce_user_group_creation_limit();
DROP FUNCTION IF EXISTS enforce_group_members_and_user_join_limits();
DROP FUNCTION IF EXISTS enforce_group_goals_limit();
DROP FUNCTION IF EXISTS enforce_invite_max_uses();

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

-- Down: remove triggers/functions and indexes
-- Note: manual rollback may be required if data exceeds new caps

-- Drop triggers
DROP TRIGGER IF EXISTS trg_enforce_user_group_creation_limit ON groups;
DROP TRIGGER IF EXISTS trg_enforce_group_members_and_user_join_limits ON group_memberships;
DROP TRIGGER IF EXISTS trg_enforce_group_goals_limit ON goals;
DROP TRIGGER IF EXISTS trg_enforce_invite_max_uses ON invite_codes;

-- Drop functions
DROP FUNCTION IF EXISTS enforce_user_group_creation_limit();
DROP FUNCTION IF EXISTS enforce_group_members_and_user_join_limits();
DROP FUNCTION IF EXISTS enforce_group_goals_limit();
DROP FUNCTION IF EXISTS enforce_invite_max_uses();

-- Drop indexes
DROP INDEX IF EXISTS idx_groups_creator_active;
DROP INDEX IF EXISTS idx_memberships_group_role;
DROP INDEX IF EXISTS idx_progress_user_period;
DROP INDEX IF EXISTS idx_devices_user_created_at;
