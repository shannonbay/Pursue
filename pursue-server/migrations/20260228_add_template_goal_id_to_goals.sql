SET client_encoding = 'UTF8';

-- Migration: add template_goal_id FK to goals for i18n translation lookup
-- When a group is created from a template, goals are copied into the goals table
-- with this FK pointing back to the source group_template_goals row.
-- The goals API uses this to look up translations in group_template_goal_translations.
-- NULL for manually created goals (no template source) — fully backwards compatible.

ALTER TABLE goals
  ADD COLUMN IF NOT EXISTS template_goal_id UUID REFERENCES group_template_goals(id) ON DELETE SET NULL;

COMMENT ON COLUMN goals.template_goal_id IS
  'FK to group_template_goals.id. Non-null only for goals copied from a template at group creation. Used to look up i18n translations in group_template_goal_translations.';
