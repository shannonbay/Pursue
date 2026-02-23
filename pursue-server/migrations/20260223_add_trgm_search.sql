-- Enable pg_trgm for fuzzy text search
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- GIN indexes for fast word_similarity / ILIKE queries
CREATE INDEX IF NOT EXISTS idx_groups_name_trgm ON groups USING gin (name gin_trgm_ops);
CREATE INDEX IF NOT EXISTS idx_goals_title_trgm  ON goals  USING gin (title gin_trgm_ops);
