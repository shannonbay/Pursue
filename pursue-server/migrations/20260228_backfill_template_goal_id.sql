SET client_encoding = 'UTF8';

-- Migration: backfill template_goal_id on goals that were copied from a template
-- before the template_goal_id column existed (added in 20260228_add_template_goal_id_to_goals.sql).
--
-- Strategy: for each goal in a template-sourced group, find the matching
-- group_template_goals row by (template_id, title) and set the FK.
--
-- Edge case: if a group admin renamed a goal after creation, the title no longer
-- matches and that goal is left with template_goal_id = NULL (English fallback).
--
-- Idempotent: WHERE template_goal_id IS NULL ensures already-set rows are skipped.

UPDATE goals g
SET template_goal_id = tg.id
FROM groups gr
JOIN group_template_goals tg
  ON tg.template_id = gr.template_id
 AND tg.title = g.title
WHERE gr.id = g.group_id
  AND gr.template_id IS NOT NULL
  AND g.template_goal_id IS NULL
  AND g.deleted_at IS NULL;
