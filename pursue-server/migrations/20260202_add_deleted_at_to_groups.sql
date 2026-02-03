-- Migration: Add deleted_at column to groups table
-- Up: add soft delete support to groups

ALTER TABLE groups ADD COLUMN deleted_at TIMESTAMP WITH TIME ZONE;

-- Down: remove deleted_at column from groups
-- ALTER TABLE groups DROP COLUMN deleted_at;
