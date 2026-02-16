-- Migration: 20260216_add_group_heat.sql
-- Description: Add Group Heat feature tables for tracking group momentum

-- Create group_heat table - stores current heat state per group
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

-- Trigger for updated_at
CREATE TRIGGER update_group_heat_updated_at 
  BEFORE UPDATE ON group_heat
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

-- Create group_daily_gcr table - stores computed daily GCR values
CREATE TABLE group_daily_gcr (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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

-- Initialize group_heat for all existing groups
INSERT INTO group_heat (group_id, heat_score, heat_tier)
SELECT id, 0.00, 0 FROM groups WHERE deleted_at IS NULL
ON CONFLICT (group_id) DO NOTHING;
