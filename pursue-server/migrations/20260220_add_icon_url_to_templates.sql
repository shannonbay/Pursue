-- Add icon_url to challenge_templates for bundled icon asset references
ALTER TABLE challenge_templates ADD COLUMN IF NOT EXISTS icon_url TEXT;
