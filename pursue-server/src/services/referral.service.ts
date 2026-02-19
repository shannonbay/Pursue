import crypto from 'crypto';
import { db } from '../database/index.js';

const REFERRAL_SECRET = process.env.REFERRAL_SECRET ?? 'dev-referral-secret';

function generateReferralToken(userId: string, attempt = 0): string {
  const input = attempt > 0 ? `${userId}:${attempt}` : userId;
  const hash = crypto
    .createHmac('sha256', REFERRAL_SECRET)
    .update(input)
    .digest('hex');
  return hash.substring(0, 12);
}

export async function getOrCreateReferralToken(userId: string): Promise<string> {
  const existing = await db
    .selectFrom('referral_tokens')
    .select('token')
    .where('user_id', '=', userId)
    .executeTakeFirst();

  if (existing?.token) return existing.token;

  for (let attempt = 0; attempt < 5; attempt++) {
    const token = generateReferralToken(userId, attempt);
    try {
      await db
        .insertInto('referral_tokens')
        .values({
          token,
          user_id: userId,
        })
        .execute();
      return token;
    } catch (err: unknown) {
      const pgErr = err as { code?: string };
      if (pgErr.code !== '23505') throw err;

      // Handle race where another request inserted this user's token.
      const raced = await db
        .selectFrom('referral_tokens')
        .select('token')
        .where('user_id', '=', userId)
        .executeTakeFirst();
      if (raced?.token) return raced.token;
    }
  }

  throw new Error('Unable to generate unique referral token');
}
