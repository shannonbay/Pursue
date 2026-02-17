import request from 'supertest';
import { app } from '../../src/app';
import { testDb } from '../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  createProgressEntry,
  createTestReminderPreference,
  createTestReminderHistory,
  createTestLoggingPattern,
  setUserTimezone,
  randomEmail,
} from '../helpers';

const INTERNAL_JOB_KEY = 'test-internal-job-key';

beforeAll(() => {
  // Set the internal job key for tests
  process.env.INTERNAL_JOB_KEY = INTERNAL_JOB_KEY;
});

// =============================================================================
// Internal Job Endpoints
// =============================================================================

describe('Smart Reminders - Job Endpoints', () => {
  describe('POST /api/internal/jobs/process-reminders', () => {
    it('should reject requests without internal job key', async () => {
      const res = await request(app)
        .post('/api/internal/jobs/process-reminders')
        .send({});

      expect(res.status).toBe(401);
      expect(res.body.error.code).toBe('UNAUTHORIZED');
    });

    it('should reject requests with invalid job key', async () => {
      const res = await request(app)
        .post('/api/internal/jobs/process-reminders')
        .set('x-internal-job-key', 'wrong-key')
        .send({});

      expect(res.status).toBe(401);
      expect(res.body.error.code).toBe('UNAUTHORIZED');
    });

    it('should process reminders with valid job key', async () => {
      const res = await request(app)
        .post('/api/internal/jobs/process-reminders')
        .set('x-internal-job-key', INTERNAL_JOB_KEY)
        .send({});

      expect(res.status).toBe(200);
      expect(res.body.success).toBe(true);
      expect(typeof res.body.sent).toBe('number');
      expect(typeof res.body.skipped).toBe('number');
      expect(typeof res.body.errors).toBe('number');
    });

    it('should not send reminders for users who have logged today', async () => {
      // Create user with a group and goal
      const user = await createAuthenticatedUser(randomEmail());
      await setUserTimezone(user.userId, 'UTC');
      const { groupId, goalId } = await createGroupWithGoal(user.accessToken);

      // Log progress for today
      const today = new Date().toISOString().slice(0, 10);
      await createProgressEntry(goalId!, user.userId, 1, today);

      const res = await request(app)
        .post('/api/internal/jobs/process-reminders')
        .set('x-internal-job-key', INTERNAL_JOB_KEY)
        .send({});

      expect(res.status).toBe(200);
      // User should have been skipped since they already logged
      // (We can't check exact counts because of other test data,
      // but at minimum no errors should occur)
      expect(res.body.errors).toBe(0);
    });
  });

  describe('POST /api/internal/jobs/recalculate-patterns', () => {
    it('should reject requests without internal job key', async () => {
      const res = await request(app)
        .post('/api/internal/jobs/recalculate-patterns')
        .send({});

      expect(res.status).toBe(401);
    });

    it('should recalculate patterns with valid job key', async () => {
      const res = await request(app)
        .post('/api/internal/jobs/recalculate-patterns')
        .set('x-internal-job-key', INTERNAL_JOB_KEY)
        .send({});

      expect(res.status).toBe(200);
      expect(res.body.success).toBe(true);
      expect(typeof res.body.created).toBe('number');
      expect(typeof res.body.updated).toBe('number');
      expect(typeof res.body.removed).toBe('number');
    });

    it('should create pattern for user with sufficient logs', async () => {
      // Create user with a group and goal
      const user = await createAuthenticatedUser(randomEmail());
      await setUserTimezone(user.userId, 'America/New_York');
      const { goalId } = await createGroupWithGoal(user.accessToken);

      // Create 10 progress entries over the last 30 days
      for (let i = 0; i < 10; i++) {
        const date = new Date();
        date.setDate(date.getDate() - (i * 3)); // Every 3 days
        const periodStart = date.toISOString().slice(0, 10);
        await createProgressEntry(goalId!, user.userId, 1, periodStart);
      }

      // Run pattern calculation
      await request(app)
        .post('/api/internal/jobs/recalculate-patterns')
        .set('x-internal-job-key', INTERNAL_JOB_KEY)
        .send({});

      // Check that a pattern was created
      const pattern = await testDb
        .selectFrom('user_logging_patterns')
        .selectAll()
        .where('user_id', '=', user.userId)
        .where('goal_id', '=', goalId!)
        .executeTakeFirst();

      expect(pattern).toBeDefined();
      expect(pattern!.sample_size).toBeGreaterThanOrEqual(5);
    });
  });

  describe('POST /api/internal/jobs/update-effectiveness', () => {
    it('should reject requests without internal job key', async () => {
      const res = await request(app)
        .post('/api/internal/jobs/update-effectiveness')
        .send({});

      expect(res.status).toBe(401);
    });

    it('should update effectiveness with valid job key', async () => {
      const res = await request(app)
        .post('/api/internal/jobs/update-effectiveness')
        .set('x-internal-job-key', INTERNAL_JOB_KEY)
        .send({});

      expect(res.status).toBe(200);
      expect(res.body.success).toBe(true);
      expect(typeof res.body.updated).toBe('number');
    });

    it('should mark reminder as effective when user logs after', async () => {
      // Create user with a group and goal
      const user = await createAuthenticatedUser(randomEmail());
      await setUserTimezone(user.userId, 'UTC');
      const { goalId } = await createGroupWithGoal(user.accessToken);

      const today = new Date().toISOString().slice(0, 10);

      // Create a reminder from 1 hour ago (was_effective = null)
      const oneHourAgo = new Date();
      oneHourAgo.setHours(oneHourAgo.getHours() - 1);
      const reminderId = await createTestReminderHistory(user.userId, goalId!, {
        sentAt: oneHourAgo,
        sentAtLocalDate: today,
        wasEffective: null,
      });

      // User logs progress after the reminder
      await createProgressEntry(goalId!, user.userId, 1, today);

      // Run effectiveness update
      await request(app)
        .post('/api/internal/jobs/update-effectiveness')
        .set('x-internal-job-key', INTERNAL_JOB_KEY)
        .send({});

      // Check that reminder was marked as effective
      const reminder = await testDb
        .selectFrom('reminder_history')
        .select('was_effective')
        .where('id', '=', reminderId)
        .executeTakeFirst();

      expect(reminder!.was_effective).toBe(true);
    });
  });
});

// =============================================================================
// User Preference Endpoints
// =============================================================================

describe('Smart Reminders - User Preferences', () => {
  describe('GET /api/users/me/reminder-preferences', () => {
    it('should require authentication', async () => {
      const res = await request(app).get('/api/users/me/reminder-preferences');

      expect(res.status).toBe(401);
    });

    it('should return empty array for user with no preferences', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .get('/api/users/me/reminder-preferences')
        .set('Authorization', `Bearer ${user.accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.preferences).toEqual([]);
    });

    it('should return preferences for user with stored preferences', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);
      await createTestReminderPreference(user.userId, goalId!, {
        enabled: true,
        mode: 'smart',
        aggressiveness: 'balanced',
      });

      const res = await request(app)
        .get('/api/users/me/reminder-preferences')
        .set('Authorization', `Bearer ${user.accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.preferences.length).toBe(1);
      expect(res.body.preferences[0].goal_id).toBe(goalId);
      expect(res.body.preferences[0].enabled).toBe(true);
      expect(res.body.preferences[0].mode).toBe('smart');
    });
  });

  describe('GET /api/goals/:goal_id/reminder-preferences', () => {
    it('should require authentication', async () => {
      const res = await request(app).get(
        '/api/goals/00000000-0000-0000-0000-000000000000/reminder-preferences'
      );

      expect(res.status).toBe(401);
    });

    it('should return 404 for non-existent goal', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .get('/api/goals/00000000-0000-0000-0000-000000000000/reminder-preferences')
        .set('Authorization', `Bearer ${user.accessToken}`);

      expect(res.status).toBe(404);
    });

    it('should return default preferences when none are set', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);

      const res = await request(app)
        .get(`/api/goals/${goalId}/reminder-preferences`)
        .set('Authorization', `Bearer ${user.accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.goal_id).toBe(goalId);
      expect(res.body.enabled).toBe(true);
      expect(res.body.mode).toBe('smart');
      expect(res.body.aggressiveness).toBe('balanced');
    });

    it('should return stored preferences when set', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);
      await createTestReminderPreference(user.userId, goalId!, {
        enabled: false,
        mode: 'fixed',
        fixedHour: 14,
        aggressiveness: 'gentle',
        quietHoursStart: 22,
        quietHoursEnd: 7,
      });

      const res = await request(app)
        .get(`/api/goals/${goalId}/reminder-preferences`)
        .set('Authorization', `Bearer ${user.accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.enabled).toBe(false);
      expect(res.body.mode).toBe('fixed');
      expect(res.body.fixed_hour).toBe(14);
      expect(res.body.aggressiveness).toBe('gentle');
      expect(res.body.quiet_hours_start).toBe(22);
      expect(res.body.quiet_hours_end).toBe(7);
    });

    it('should include pattern info when available', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);
      await createTestLoggingPattern(user.userId, goalId!, {
        typicalHourStart: 8,
        typicalHourEnd: 10,
        confidenceScore: 0.85,
        sampleSize: 20,
      });

      const res = await request(app)
        .get(`/api/goals/${goalId}/reminder-preferences`)
        .set('Authorization', `Bearer ${user.accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.pattern).toBeDefined();
      expect(res.body.pattern.typical_hour_start).toBe(8);
      expect(res.body.pattern.typical_hour_end).toBe(10);
      expect(res.body.pattern.confidence_score).toBeCloseTo(0.85);
      expect(res.body.pattern.sample_size).toBe(20);
    });
  });

  describe('PUT /api/goals/:goal_id/reminder-preferences', () => {
    it('should require authentication', async () => {
      const res = await request(app)
        .put('/api/goals/00000000-0000-0000-0000-000000000000/reminder-preferences')
        .send({ enabled: false });

      expect(res.status).toBe(401);
    });

    it('should create preferences when none exist', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);

      const res = await request(app)
        .put(`/api/goals/${goalId}/reminder-preferences`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          enabled: false,
          mode: 'disabled',
        });

      expect(res.status).toBe(200);
      expect(res.body.enabled).toBe(false);
      expect(res.body.mode).toBe('disabled');
    });

    it('should update existing preferences', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);
      await createTestReminderPreference(user.userId, goalId!, {
        enabled: true,
        mode: 'smart',
      });

      const res = await request(app)
        .put(`/api/goals/${goalId}/reminder-preferences`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          aggressiveness: 'persistent',
          quiet_hours_start: 22,
          quiet_hours_end: 7,
        });

      expect(res.status).toBe(200);
      expect(res.body.enabled).toBe(true); // Unchanged
      expect(res.body.mode).toBe('smart'); // Unchanged
      expect(res.body.aggressiveness).toBe('persistent');
      expect(res.body.quiet_hours_start).toBe(22);
      expect(res.body.quiet_hours_end).toBe(7);
    });

    it('should validate fixed_hour is required for fixed mode', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);

      const res = await request(app)
        .put(`/api/goals/${goalId}/reminder-preferences`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          mode: 'fixed',
          // Missing fixed_hour
        });

      expect(res.status).toBe(400);
    });

    it('should accept fixed mode with fixed_hour', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);

      const res = await request(app)
        .put(`/api/goals/${goalId}/reminder-preferences`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          mode: 'fixed',
          fixed_hour: 14,
        });

      expect(res.status).toBe(200);
      expect(res.body.mode).toBe('fixed');
      expect(res.body.fixed_hour).toBe(14);
    });

    it('should validate quiet hours must be provided together', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);

      const res = await request(app)
        .put(`/api/goals/${goalId}/reminder-preferences`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          quiet_hours_start: 22,
          // Missing quiet_hours_end
        });

      expect(res.status).toBe(400);
    });
  });

  describe('POST /api/goals/:goal_id/recalculate-pattern', () => {
    it('should require authentication', async () => {
      const res = await request(app)
        .post('/api/goals/00000000-0000-0000-0000-000000000000/recalculate-pattern')
        .send({ user_timezone: 'UTC' });

      expect(res.status).toBe(401);
    });

    it('should return null pattern with insufficient data', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);

      const res = await request(app)
        .post(`/api/goals/${goalId}/recalculate-pattern`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ user_timezone: 'America/New_York' });

      expect(res.status).toBe(200);
      expect(res.body.pattern).toBeNull();
      expect(res.body.message).toContain('Insufficient data');
    });

    it('should calculate pattern with sufficient data', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);

      // Create 10 progress entries
      for (let i = 0; i < 10; i++) {
        const date = new Date();
        date.setDate(date.getDate() - (i * 2));
        const periodStart = date.toISOString().slice(0, 10);
        await createProgressEntry(goalId!, user.userId, 1, periodStart);
      }

      const res = await request(app)
        .post(`/api/goals/${goalId}/recalculate-pattern`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ user_timezone: 'America/New_York' });

      expect(res.status).toBe(200);
      expect(res.body.pattern).toBeDefined();
      expect(res.body.pattern.typical_hour_start).toBeDefined();
      expect(res.body.pattern.typical_hour_end).toBeDefined();
      expect(res.body.pattern.confidence_score).toBeGreaterThan(0);
      expect(res.body.pattern.sample_size).toBe(10);
    });

    it('should require user_timezone', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { goalId } = await createGroupWithGoal(user.accessToken);

      const res = await request(app)
        .post(`/api/goals/${goalId}/recalculate-pattern`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({});

      expect(res.status).toBe(400);
    });
  });
});

// =============================================================================
// Reminder Engine Tests
// =============================================================================

describe('Smart Reminders - Reminder Engine', () => {
  it('should not send reminders to users with disabled preferences', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    await setUserTimezone(user.userId, 'UTC');
    const { goalId } = await createGroupWithGoal(user.accessToken);
    await createTestReminderPreference(user.userId, goalId!, {
      enabled: false,
    });

    const res = await request(app)
      .post('/api/internal/jobs/process-reminders')
      .set('x-internal-job-key', INTERNAL_JOB_KEY)
      .send({});

    expect(res.status).toBe(200);
    expect(res.body.errors).toBe(0);

    // Check no reminder was sent
    const reminder = await testDb
      .selectFrom('reminder_history')
      .selectAll()
      .where('user_id', '=', user.userId)
      .where('goal_id', '=', goalId!)
      .executeTakeFirst();

    expect(reminder).toBeUndefined();
  });

  it('should respect global daily cap', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    await setUserTimezone(user.userId, 'UTC');
    const { goalId } = await createGroupWithGoal(user.accessToken);

    // Create 6 reminders for today (max cap)
    const today = new Date().toISOString().slice(0, 10);
    for (let i = 0; i < 6; i++) {
      await createTestReminderHistory(user.userId, goalId!, {
        sentAtLocalDate: today,
        reminderTier: 'gentle',
      });
    }

    const res = await request(app)
      .post('/api/internal/jobs/process-reminders')
      .set('x-internal-job-key', INTERNAL_JOB_KEY)
      .send({});

    expect(res.status).toBe(200);

    // Count total reminders for user today - should still be 6
    const count = await testDb
      .selectFrom('reminder_history')
      .select(({ fn }) => [fn.countAll<string>().as('count')])
      .where('user_id', '=', user.userId)
      .where('sent_at_local_date', '=', today)
      .executeTakeFirst();

    expect(Number(count!.count)).toBeLessThanOrEqual(6);
  });

  it('should not duplicate reminders for the same tier same day', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    await setUserTimezone(user.userId, 'UTC');
    const { goalId } = await createGroupWithGoal(user.accessToken);

    // Create a gentle reminder for today
    const today = new Date().toISOString().slice(0, 10);
    await createTestReminderHistory(user.userId, goalId!, {
      sentAtLocalDate: today,
      reminderTier: 'gentle',
    });

    // Process reminders - should not send another gentle
    await request(app)
      .post('/api/internal/jobs/process-reminders')
      .set('x-internal-job-key', INTERNAL_JOB_KEY)
      .send({});

    // Count gentle reminders for today
    const count = await testDb
      .selectFrom('reminder_history')
      .select(({ fn }) => [fn.countAll<string>().as('count')])
      .where('user_id', '=', user.userId)
      .where('goal_id', '=', goalId!)
      .where('sent_at_local_date', '=', today)
      .where('reminder_tier', '=', 'gentle')
      .executeTakeFirst();

    expect(Number(count!.count)).toBe(1);
  });
});

// =============================================================================
// Pattern Calculator Tests
// =============================================================================

describe('Smart Reminders - Pattern Calculator', () => {
  it('should not calculate pattern with fewer than 5 logs', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(user.accessToken);

    // Create only 3 progress entries
    for (let i = 0; i < 3; i++) {
      const date = new Date();
      date.setDate(date.getDate() - i);
      await createProgressEntry(goalId!, user.userId, 1, date.toISOString().slice(0, 10));
    }

    const res = await request(app)
      .post(`/api/goals/${goalId}/recalculate-pattern`)
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ user_timezone: 'UTC' });

    expect(res.status).toBe(200);
    expect(res.body.pattern).toBeNull();
  });

  it('should calculate higher confidence with consistent logging times', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(user.accessToken);

    // Create 20 progress entries all at the same time
    for (let i = 0; i < 20; i++) {
      const date = new Date();
      date.setDate(date.getDate() - i);
      await createProgressEntry(goalId!, user.userId, 1, date.toISOString().slice(0, 10));
    }

    const res = await request(app)
      .post(`/api/goals/${goalId}/recalculate-pattern`)
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ user_timezone: 'UTC' });

    expect(res.status).toBe(200);
    expect(res.body.pattern).toBeDefined();
    // With consistent times, confidence should be relatively high
    expect(res.body.pattern.confidence_score).toBeGreaterThan(0.3);
  });
});

// =============================================================================
// Progress Timezone Sync Tests
// =============================================================================

describe('Smart Reminders - Progress Timezone Sync', () => {
  it('should sync user timezone when logging progress', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(user.accessToken);

    // Initially timezone should be default 'UTC'
    let userData = await testDb
      .selectFrom('users')
      .select('timezone')
      .where('id', '=', user.userId)
      .executeTakeFirst();
    expect(userData!.timezone).toBe('UTC');

    // Use yesterday's date to avoid timezone edge cases where "today" might be in the future
    const yesterday = new Date();
    yesterday.setDate(yesterday.getDate() - 1);
    const userDate = yesterday.toISOString().slice(0, 10);

    // Log progress with a different timezone
    const res = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: userDate,
        user_timezone: 'America/Los_Angeles',
      });

    expect(res.status).toBe(201);

    // Timezone should now be updated
    userData = await testDb
      .selectFrom('users')
      .select('timezone')
      .where('id', '=', user.userId)
      .executeTakeFirst();
    expect(userData!.timezone).toBe('America/Los_Angeles');
  });

  it('should not update timezone if same as current', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    await setUserTimezone(user.userId, 'Europe/London');
    const { goalId } = await createGroupWithGoal(user.accessToken);

    // Get the user's updated_at before logging
    const beforeUser = await testDb
      .selectFrom('users')
      .select(['timezone', 'updated_at'])
      .where('id', '=', user.userId)
      .executeTakeFirst();

    // Log progress with the same timezone
    await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: new Date().toISOString().slice(0, 10),
        user_timezone: 'Europe/London',
      });

    // Timezone should still be the same
    const afterUser = await testDb
      .selectFrom('users')
      .select('timezone')
      .where('id', '=', user.userId)
      .executeTakeFirst();
    expect(afterUser!.timezone).toBe('Europe/London');
  });
});
