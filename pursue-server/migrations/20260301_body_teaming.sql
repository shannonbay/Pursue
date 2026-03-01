-- Body-Teaming: group-scoped focus sessions with scheduled availability slots
-- v1.0 — 2026-03-01

-- Focus session (group-scoped)
CREATE TABLE IF NOT EXISTS focus_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  host_user_id UUID NOT NULL REFERENCES users(id),
  status TEXT NOT NULL CHECK (status IN ('lobby', 'focus', 'chit-chat', 'ended')),
  focus_duration_minutes INTEGER NOT NULL CHECK (focus_duration_minutes IN (25, 45, 60, 90)),
  started_at TIMESTAMPTZ,
  ended_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_focus_sessions_group ON focus_sessions(group_id, status);

-- Who joined a session and when
CREATE TABLE IF NOT EXISTS focus_session_participants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id UUID NOT NULL REFERENCES focus_sessions(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id),
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  left_at TIMESTAMPTZ,
  UNIQUE(session_id, user_id)
);

-- Scheduled availability slots
CREATE TABLE IF NOT EXISTS focus_slots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  created_by UUID NOT NULL REFERENCES users(id),
  scheduled_start TIMESTAMPTZ NOT NULL,
  focus_duration_minutes INTEGER NOT NULL CHECK (focus_duration_minutes IN (25, 45, 60, 90)),
  note TEXT,
  session_id UUID REFERENCES focus_sessions(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  cancelled_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_focus_slots_group ON focus_slots(group_id, scheduled_start)
  WHERE cancelled_at IS NULL;

-- RSVP to a scheduled slot
CREATE TABLE IF NOT EXISTS focus_slot_rsvps (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slot_id UUID NOT NULL REFERENCES focus_slots(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(slot_id, user_id)
);
