-- Migration: 20260223_rename_challenge_tables.sql
-- Rename challenge_templates and related tables to group_templates.
-- These tables are a shared template library used by both time-boxed challenges
-- and (via the new ongoing group templates feature) open-ended groups.

BEGIN;

-- Rename tables
ALTER TABLE challenge_templates      RENAME TO group_templates;
ALTER TABLE challenge_template_goals RENAME TO group_template_goals;
ALTER TABLE challenge_suggestion_log RENAME TO group_suggestion_log;

-- Rename indexes
ALTER INDEX idx_challenge_templates_category RENAME TO idx_group_templates_category;
ALTER INDEX idx_challenge_templates_featured RENAME TO idx_group_templates_featured;
ALTER INDEX idx_template_goals_template      RENAME TO idx_group_template_goals;
ALTER INDEX idx_groups_challenge_template    RENAME TO idx_groups_template;

-- Rename FK constraint on groups table
ALTER TABLE groups
  RENAME CONSTRAINT fk_groups_challenge_template TO fk_groups_template;

-- Rename column on groups table
ALTER TABLE groups
  RENAME COLUMN challenge_template_id TO template_id;

-- Add is_challenge discriminator column to group_templates
ALTER TABLE group_templates
  ADD COLUMN is_challenge BOOLEAN NOT NULL DEFAULT TRUE;

-- All existing templates are challenge templates — make it explicit
UPDATE group_templates SET is_challenge = TRUE;

COMMIT;
