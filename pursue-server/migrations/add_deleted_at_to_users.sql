-- Migration: Add deleted_at column to users table
-- This enables soft delete functionality for users

-- Add deleted_at column (NULL for active users, timestamp for soft-deleted users)
ALTER TABLE users 
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE;

-- Add comment explaining the column
COMMENT ON COLUMN users.deleted_at IS 'Soft delete timestamp. NULL = active user, non-NULL = soft deleted. Prevents deleted users from logging in or being accessed.';
