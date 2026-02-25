import OpenAI from 'openai';
import { sql } from 'kysely';
import { db } from '../database/index.js';
import { logger } from '../utils/logger.js';

// Lazy client — only instantiated when OPENAI_API_KEY is present
function getClient(): OpenAI | null {
  if (!process.env.OPENAI_API_KEY) return null;
  return new OpenAI({
    apiKey: process.env.OPENAI_API_KEY,
    timeout: 3000,   // 3-second hard timeout
    maxRetries: 0,   // No retries on user-facing search path
  });
}

/**
 * Returns an embedding for the search query, using Postgres cache.
 * Returns null if OPENAI_API_KEY is not set or the call fails/times out.
 */
export async function getQueryEmbedding(text: string): Promise<number[] | null> {
  const client = getClient();
  if (!client) return null;

  const normalized = text.trim().toLowerCase().slice(0, 200);

  // 1. Check Postgres cache
  try {
    const cached = await db
      .selectFrom('search_query_embeddings')
      .select('embedding')
      .where('query_text', '=', normalized)
      .where('expires_at', '>', new Date())
      .executeTakeFirst();

    if (cached) {
      logger.debug('[embedding] query cache hit', { query: normalized });
      return cached.embedding as number[];
    }
  } catch (e) {
    logger.warn('[embedding] cache lookup failed — calling OpenAI anyway', { query: normalized, error: e });
  }

  // 2. Call OpenAI (1.5s timeout — fall back to trigram quickly on the search path)
  try {
    const response = await client.embeddings.create(
      { model: 'text-embedding-3-small', input: normalized },
      { timeout: 1500 }
    );
    const embedding = response.data[0].embedding;

    // 3. Write to cache (fire-and-forget — failure is non-fatal)
    db.insertInto('search_query_embeddings')
      .values({
        query_text: normalized,
        embedding: sql`${JSON.stringify(embedding)}::jsonb` as any,
        expires_at: sql`NOW() + INTERVAL '7 days'` as any,
      })
      .onConflict((oc) =>
        oc.column('query_text').doUpdateSet({
          embedding: sql`${JSON.stringify(embedding)}::jsonb` as any,
          expires_at: sql`NOW() + INTERVAL '7 days'` as any,
        })
      )
      .execute()
      .catch((e) => logger.warn('[embedding] cache write failed', { error: e }));

    return embedding;
  } catch (e) {
    logger.warn('[embedding] OpenAI call failed — falling back to trigram', { error: e });
    return null;
  }
}

/**
 * Computes and stores embedding for a group (name + description).
 * Designed for fire-and-forget — caller does NOT await.
 */
export async function embedAndStoreGroup(
  groupId: string,
  name: string,
  description: string | null
): Promise<void> {
  const client = getClient();
  if (!client) return;

  const text = description ? `${name}. ${description}` : name;
  try {
    const response = await client.embeddings.create({
      model: 'text-embedding-3-small',
      input: text,
    });
    const embedding = response.data[0].embedding;

    await db
      .updateTable('groups')
      .set({ search_embedding: sql`${JSON.stringify(embedding)}::vector` as any })
      .where('id', '=', groupId)
      .execute();

    logger.debug('[embedding] group embedding stored', { groupId });
  } catch (e) {
    logger.warn('[embedding] embedAndStoreGroup failed', { groupId, error: e });
  }
}
