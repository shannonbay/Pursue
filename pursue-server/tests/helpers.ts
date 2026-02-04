import request from 'supertest';
import { app } from '../src/app';
import { testDb } from './setup';
import bcrypt from 'bcrypt';
import crypto from 'crypto';

/**
 * Create and authenticate a test user via the API
 */
export async function createAuthenticatedUser(
  email: string = 'test@example.com',
  password: string = 'Test123!@#',
  displayName: string = 'Test User'
) {
  const response = await request(app)
    .post('/api/auth/register')
    .send({
      email,
      password,
      display_name: displayName
    });

  if (response.status !== 201) {
    throw new Error(`Failed to create user: ${JSON.stringify(response.body)}`);
  }

  return {
    userId: response.body.user.id,
    accessToken: response.body.access_token,
    refreshToken: response.body.refresh_token,
    user: response.body.user
  };
}

/**
 * Create a test user directly in the database
 */
export async function createTestUser(
  email: string = 'test@example.com',
  password: string = 'Test123!@#',
  displayName: string = 'Test User'
) {
  const passwordHash = await bcrypt.hash(password, 10);

  const user = await testDb
    .insertInto('users')
    .values({
      email: email.toLowerCase(),
      display_name: displayName,
      password_hash: passwordHash,
    })
    .returning(['id', 'email', 'display_name', 'created_at'])
    .executeTakeFirstOrThrow();

  // Create email auth provider
  await testDb
    .insertInto('auth_providers')
    .values({
      user_id: user.id,
      provider: 'email',
      provider_user_id: email.toLowerCase(),
      provider_email: email.toLowerCase(),
    })
    .execute();

  return {
    ...user,
    password,
  };
}

/**
 * Create a test user with Google auth provider
 */
export async function createGoogleUser(
  email: string = 'google@example.com',
  googleUserId: string = 'google-user-123',
  displayName: string = 'Google User'
) {
  const user = await testDb
    .insertInto('users')
    .values({
      email: email.toLowerCase(),
      display_name: displayName,
      password_hash: null,
    })
    .returning(['id', 'email', 'display_name', 'created_at'])
    .executeTakeFirstOrThrow();

  await testDb
    .insertInto('auth_providers')
    .values({
      user_id: user.id,
      provider: 'google',
      provider_user_id: googleUserId,
      provider_email: email.toLowerCase(),
    })
    .execute();

  return {
    ...user,
    googleUserId,
  };
}

/**
 * Create a password reset token for a user
 */
export async function createPasswordResetToken(userId: string): Promise<string> {
  const plainToken = crypto.randomBytes(32).toString('hex');
  const tokenHash = crypto.createHash('sha256').update(plainToken).digest('hex');

  const expiresAt = new Date();
  expiresAt.setHours(expiresAt.getHours() + 1);

  await testDb
    .insertInto('password_reset_tokens')
    .values({
      user_id: userId,
      token_hash: tokenHash,
      expires_at: expiresAt,
    })
    .execute();

  return plainToken;
}

/**
 * Create an expired password reset token
 */
export async function createExpiredPasswordResetToken(userId: string): Promise<string> {
  const plainToken = crypto.randomBytes(32).toString('hex');
  const tokenHash = crypto.createHash('sha256').update(plainToken).digest('hex');

  const expiresAt = new Date();
  expiresAt.setHours(expiresAt.getHours() - 1); // Expired 1 hour ago

  await testDb
    .insertInto('password_reset_tokens')
    .values({
      user_id: userId,
      token_hash: tokenHash,
      expires_at: expiresAt,
    })
    .execute();

  return plainToken;
}

/**
 * Generate a random email for tests
 */
export function randomEmail(): string {
  const random = Math.random().toString(36).substring(7);
  return `test-${random}@example.com`;
}

/**
 * Wait for async operations
 */
export function wait(ms: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, ms));
}

/**
 * Create a test invite code directly in the database (new schema: revoked_at, no max_uses/expires_at)
 */
export async function createTestInviteCode(
  groupId: string,
  userId: string,
  options?: {
    code?: string;
    revoked_at?: Date | null;
  }
): Promise<string> {
  const code = options?.code || `PURSUE-${Math.random().toString(36).substring(2, 8).toUpperCase()}-${Math.random().toString(36).substring(2, 8).toUpperCase()}`;

  await testDb
    .insertInto('invite_codes')
    .values({
      group_id: groupId,
      code,
      created_by_user_id: userId,
      revoked_at: options?.revoked_at ?? null,
    })
    .execute();

  return code;
}

/**
 * Create a test group activity directly in the database
 */
export async function createTestGroupActivity(
  groupId: string,
  userId: string | null,
  activityType: string,
  metadata?: Record<string, unknown>
): Promise<void> {
  await testDb
    .insertInto('group_activities')
    .values({
      group_id: groupId,
      user_id: userId,
      activity_type: activityType,
      metadata: metadata ?? null,
    })
    .execute();
}

/**
 * Create a group, optionally with one initial goal. Used to obtain groupId and/or goalId for Goal endpoint tests.
 * - includeGoal: false -> group only, returns { groupId }. Use for POST /api/groups/:group_id/goals.
 * - includeGoal: true (default) -> group with one goal, returns { groupId, goalId }.
 */
export async function createGroupWithGoal(
  accessToken: string,
  options?: {
    includeGoal?: boolean;
    groupName?: string;
    goal?: { title?: string; description?: string; cadence?: string; metric_type?: string; target_value?: number; unit?: string };
  }
): Promise<{ groupId: string; goalId?: string }> {
  const includeGoal = options?.includeGoal !== false;
  const name = options?.groupName ?? 'Goal Test Group';
  const defaultGoal = { title: '30 min run', description: 'Run 30 min', cadence: 'daily' as const, metric_type: 'binary' as const };
  const goal = includeGoal ? { ...defaultGoal, ...options?.goal } : undefined;

  const body = goal ? { name, initial_goals: [goal] } : { name };
  const res = await request(app)
    .post('/api/groups')
    .set('Authorization', `Bearer ${accessToken}`)
    .send(body);

  if (res.status !== 201) {
    throw new Error(`Failed to create group: ${JSON.stringify(res.body)}`);
  }
  const groupId = res.body.id;

  if (!includeGoal) {
    return { groupId };
  }
  const row = await testDb
    .selectFrom('goals')
    .where('group_id', '=', groupId)
    .select('id')
    .executeTakeFirst();
  if (!row) {
    throw new Error('createGroupWithGoal: no goal found after create');
  }
  return { groupId, goalId: row.id };
}

/**
 * Add a non-admin member to an existing group via GET invite + join, then approve as creator so the member is active.
 * Returns the new member's token and userId.
 */
export async function addMemberToGroup(
  creatorToken: string,
  groupId: string
): Promise<{ memberAccessToken: string; memberUserId: string }> {
  const invRes = await request(app)
    .get(`/api/groups/${groupId}/invite`)
    .set('Authorization', `Bearer ${creatorToken}`);
  if (invRes.status !== 200) {
    throw new Error(`Failed to get invite: ${JSON.stringify(invRes.body)}`);
  }
  const code = invRes.body.invite_code;
  const member = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Member');
  const joinRes = await request(app)
    .post('/api/groups/join')
    .set('Authorization', `Bearer ${member.accessToken}`)
    .send({ invite_code: code });
  if (joinRes.status !== 200 && joinRes.status !== 201) {
    throw new Error(`Failed to join group: ${JSON.stringify(joinRes.body)}`);
  }
  const approveRes = await request(app)
    .post(`/api/groups/${groupId}/members/${member.userId}/approve`)
    .set('Authorization', `Bearer ${creatorToken}`);
  if (approveRes.status !== 200) {
    throw new Error(`Failed to approve member: ${JSON.stringify(approveRes.body)}`);
  }
  return { memberAccessToken: member.accessToken, memberUserId: member.userId };
}

/**
 * Set a user to premium tier (up to 10 groups) for testing.
 */
export async function setUserPremium(userId: string): Promise<void> {
  const expiresAt = new Date();
  expiresAt.setFullYear(expiresAt.getFullYear() + 1);

  await testDb
    .insertInto('user_subscriptions')
    .values({
      user_id: userId,
      tier: 'premium',
      status: 'active',
      expires_at: expiresAt,
      platform: 'google_play',
      platform_purchase_token: `test-token-${userId}`,
      auto_renew: true,
    })
    .execute();

  await testDb
    .updateTable('users')
    .set({
      current_subscription_tier: 'premium',
      subscription_status: 'active',
      group_limit: 10,
    })
    .where('id', '=', userId)
    .execute();
}

/**
 * Create a progress entry directly in the database for testing
 */
export async function createProgressEntry(
  goalId: string,
  userId: string,
  value: number,
  periodStart: string, // YYYY-MM-DD
  note?: string
): Promise<void> {
  await testDb
    .insertInto('progress_entries')
    .values({
      goal_id: goalId,
      user_id: userId,
      value,
      period_start: periodStart,
      note: note ?? null,
    })
    .execute();
}
