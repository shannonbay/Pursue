-- Enable pgvector
CREATE EXTENSION IF NOT EXISTS vector;

-- Group search embeddings (text-embedding-3-small = 1536 dims)
ALTER TABLE groups ADD COLUMN IF NOT EXISTS search_embedding vector(1536);

-- HNSW index for fast approximate nearest-neighbor on group embeddings
CREATE INDEX IF NOT EXISTS idx_groups_search_embedding
  ON groups USING hnsw (search_embedding vector_cosine_ops);

-- Query embedding cache (JSONB for easy round-trip without vector parsing)
CREATE TABLE IF NOT EXISTS search_query_embeddings (
  query_text  TEXT        PRIMARY KEY,
  embedding   JSONB       NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at  TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days')
);

CREATE INDEX IF NOT EXISTS idx_search_query_embeddings_expires
  ON search_query_embeddings(expires_at);
