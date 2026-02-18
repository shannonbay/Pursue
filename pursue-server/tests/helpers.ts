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
      display_name: displayName,
      consent_agreed: true
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
 * Create a test group activity and return its id
 */
export async function createTestGroupActivityWithId(
  groupId: string,
  userId: string | null,
  activityType: string,
  metadata?: Record<string, unknown>
): Promise<string> {
  const row = await testDb
    .insertInto('group_activities')
    .values({
      group_id: groupId,
      user_id: userId,
      activity_type: activityType,
      metadata: metadata ?? null,
    })
    .returning('id')
    .executeTakeFirstOrThrow();
  return row.id;
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
 * Create a nudge directly in the database for testing
 */
export async function createTestNudge(
  senderId: string,
  recipientId: string,
  groupId: string,
  senderLocalDate: string,
  goalId?: string
): Promise<string> {
  const nudge = await testDb
    .insertInto('nudges')
    .values({
      sender_user_id: senderId,
      recipient_user_id: recipientId,
      group_id: groupId,
      goal_id: goalId ?? null,
      sender_local_date: senderLocalDate,
    })
    .returning('id')
    .executeTakeFirstOrThrow();
  return nudge.id;
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
): Promise<string> {
  const entry = await testDb
    .insertInto('progress_entries')
    .values({
      goal_id: goalId,
      user_id: userId,
      value,
      period_start: periodStart,
      note: note ?? null,
    })
    .returning('id')
    .executeTakeFirstOrThrow();
  return entry.id;
}

/**
 * Create a progress photo record directly in the database for testing
 */
export async function createTestPhoto(
  progressEntryId: string,
  userId: string,
  options?: {
    gcsObjectPath?: string;
    width?: number;
    height?: number;
    expiresAt?: Date;
    gcsDeletedAt?: Date | null;
  }
): Promise<string> {
  const expiresAt = options?.expiresAt ?? new Date(Date.now() + 7 * 24 * 60 * 60 * 1000);

  const photo = await testDb
    .insertInto('progress_photos')
    .values({
      progress_entry_id: progressEntryId,
      user_id: userId,
      gcs_object_path: options?.gcsObjectPath ?? `${userId}/2026/02/${progressEntryId}.jpg`,
      width_px: options?.width ?? 1080,
      height_px: options?.height ?? 810,
      expires_at: expiresAt.toISOString(),
      gcs_deleted_at: options?.gcsDeletedAt ?? null,
    })
    .returning('id')
    .executeTakeFirstOrThrow();

  return photo.id;
}

/**
 * Record a photo upload in the upload log for quota testing
 */
export async function recordPhotoUpload(
  userId: string,
  uploadedAt?: Date
): Promise<void> {
  await testDb
    .insertInto('photo_upload_log')
    .values({
      user_id: userId,
      uploaded_at: (uploadedAt ?? new Date()).toISOString(),
    })
    .execute();
}

/**
 * Create a test notification directly in the database
 */
export async function createTestNotification(
  userId: string,
  type: string,
  options?: {
    actorUserId?: string;
    groupId?: string;
    goalId?: string;
    progressEntryId?: string;
    metadata?: Record<string, unknown>;
    shareableCardData?: Record<string, unknown>;
    isRead?: boolean;
  }
): Promise<string> {
  const notif = await testDb
    .insertInto('user_notifications')
    .values({
      user_id: userId,
      type,
      actor_user_id: options?.actorUserId ?? null,
      group_id: options?.groupId ?? null,
      goal_id: options?.goalId ?? null,
      progress_entry_id: options?.progressEntryId ?? null,
      metadata: options?.metadata ?? null,
      shareable_card_data: options?.shareableCardData ?? null,
      is_read: options?.isRead ?? false,
    })
    .returning('id')
    .executeTakeFirstOrThrow();
  return notif.id;
}

/**
 * Create a minimal valid JPEG buffer for testing
 */
export function createTestJpegBuffer(): Buffer {
  // Minimal valid 1x1 red JPEG (smallest valid JPEG image)
  return Buffer.from([
    0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
    0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF, 0xDB, 0x00, 0x43,
    0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09,
    0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12,
    0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20,
    0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29,
    0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32,
    0x3C, 0x2E, 0x33, 0x34, 0x32, 0xFF, 0xC0, 0x00, 0x0B, 0x08, 0x00, 0x01,
    0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xFF, 0xC4, 0x00, 0x1F, 0x00, 0x00,
    0x01, 0x05, 0x01, 0x01, 0x01, 0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
    0x09, 0x0A, 0x0B, 0xFF, 0xC4, 0x00, 0xB5, 0x10, 0x00, 0x02, 0x01, 0x03,
    0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00, 0x01, 0x7D,
    0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06,
    0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xA1, 0x08,
    0x23, 0x42, 0xB1, 0xC1, 0x15, 0x52, 0xD1, 0xF0, 0x24, 0x33, 0x62, 0x72,
    0x82, 0x09, 0x0A, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x25, 0x26, 0x27, 0x28,
    0x29, 0x2A, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x43, 0x44, 0x45,
    0x46, 0x47, 0x48, 0x49, 0x4A, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59,
    0x5A, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6A, 0x73, 0x74, 0x75,
    0x76, 0x77, 0x78, 0x79, 0x7A, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
    0x8A, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0xA2, 0xA3,
    0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
    0xB7, 0xB8, 0xB9, 0xBA, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9,
    0xCA, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0xDA, 0xE1, 0xE2,
    0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0xEA, 0xF1, 0xF2, 0xF3, 0xF4,
    0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0xFA, 0xFF, 0xDA, 0x00, 0x08, 0x01, 0x01,
    0x00, 0x00, 0x3F, 0x00, 0xFB, 0xD5, 0xDB, 0x20, 0xA8, 0xF1, 0x45, 0x00,
    0x14, 0x51, 0x40, 0x05, 0xFF, 0xD9
  ]);
}

// =============================================================================
// Smart Reminders Test Helpers
// =============================================================================

/**
 * Create a reminder preference directly in the database
 */
export async function createTestReminderPreference(
  userId: string,
  goalId: string,
  options?: {
    enabled?: boolean;
    mode?: 'smart' | 'fixed' | 'disabled';
    fixedHour?: number | null;
    aggressiveness?: 'gentle' | 'balanced' | 'persistent';
    quietHoursStart?: number | null;
    quietHoursEnd?: number | null;
  }
): Promise<void> {
  await testDb
    .insertInto('user_reminder_preferences')
    .values({
      user_id: userId,
      goal_id: goalId,
      enabled: options?.enabled ?? true,
      mode: options?.mode ?? 'smart',
      fixed_hour: options?.fixedHour ?? null,
      aggressiveness: options?.aggressiveness ?? 'balanced',
      quiet_hours_start: options?.quietHoursStart ?? null,
      quiet_hours_end: options?.quietHoursEnd ?? null,
    })
    .execute();
}

/**
 * Create a reminder history entry directly in the database
 */
export async function createTestReminderHistory(
  userId: string,
  goalId: string,
  options?: {
    reminderTier?: 'gentle' | 'supportive' | 'last_chance';
    sentAt?: Date;
    sentAtLocalDate?: string;
    wasEffective?: boolean | null;
    userTimezone?: string;
    socialContext?: Record<string, unknown> | null;
  }
): Promise<string> {
  const today = new Date().toISOString().slice(0, 10);
  const history = await testDb
    .insertInto('reminder_history')
    .values({
      user_id: userId,
      goal_id: goalId,
      reminder_tier: options?.reminderTier ?? 'gentle',
      sent_at: options?.sentAt?.toISOString() ?? new Date().toISOString(),
      sent_at_local_date: options?.sentAtLocalDate ?? today,
      was_effective: options?.wasEffective ?? null,
      user_timezone: options?.userTimezone ?? 'America/New_York',
      social_context: options?.socialContext ?? null,
    })
    .returning('id')
    .executeTakeFirstOrThrow();
  return history.id;
}

/**
 * Create a logging pattern directly in the database
 */
export async function createTestLoggingPattern(
  userId: string,
  goalId: string,
  options?: {
    dayOfWeek?: number;
    typicalHourStart?: number;
    typicalHourEnd?: number;
    confidenceScore?: number;
    sampleSize?: number;
  }
): Promise<void> {
  await testDb
    .insertInto('user_logging_patterns')
    .values({
      user_id: userId,
      goal_id: goalId,
      day_of_week: options?.dayOfWeek ?? -1,
      typical_hour_start: options?.typicalHourStart ?? 8,
      typical_hour_end: options?.typicalHourEnd ?? 10,
      confidence_score: options?.confidenceScore ?? 0.8,
      sample_size: options?.sampleSize ?? 15,
    })
    .execute();
}

/**
 * Set a user's timezone directly in the database
 */
export async function setUserTimezone(
  userId: string,
  timezone: string
): Promise<void> {
  await testDb
    .updateTable('users')
    .set({ timezone })
    .where('id', '=', userId)
    .execute();
}

// =============================================================================
// Weekly Recap Test Helpers
// =============================================================================

/**
 * Create a weekly recap sent record directly in the database
 */
export async function createWeeklyRecapSent(
  groupId: string,
  weekEnd: string
): Promise<void> {
  await testDb
    .insertInto('weekly_recaps_sent')
    .values({
      group_id: groupId,
      week_end: weekEnd,
    })
    .execute();
}

/**
 * Create GCR records for a week
 */
export async function createGcrDataForWeek(
  groupId: string,
  weekStart: string,
  gcrValues: number[]
): Promise<void> {
  for (let i = 0; i < gcrValues.length; i++) {
    const date = new Date(weekStart + 'T00:00:00Z');
    date.setUTCDate(date.getUTCDate() + i);
    const dateStr = date.toISOString().slice(0, 10);

    await testDb
      .insertInto('group_daily_gcr')
      .values({
        group_id: groupId,
        date: dateStr,
        total_possible: 10,
        total_completed: Math.round(gcrValues[i] * 10),
        gcr: gcrValues[i],
        member_count: 2,
        goal_count: 5,
      })
      .execute();
  }
}

/**
 * Set member's weekly recap preference
 */
export async function setWeeklyRecapEnabled(
  groupId: string,
  userId: string,
  enabled: boolean
): Promise<void> {
  await testDb
    .updateTable('group_memberships')
    .set({ weekly_recap_enabled: enabled })
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .execute();
}
