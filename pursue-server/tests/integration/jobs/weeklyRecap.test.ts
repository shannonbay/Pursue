import request from 'supertest';
import { sql } from 'kysely';
import { app } from '../../../src/app.js';
import { testDb } from '../../setup.js';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  createProgressEntry,
  createWeeklyRecapSent,
  createGcrDataForWeek,
  setWeeklyRecapEnabled,
  randomEmail,
} from '../../helpers.js';
import {
  buildWeeklyRecap,
  shouldSkipGroup,
  getPreviousSunday,
} from '../../../src/services/weeklyRecap.service.js';

// Helper to get a test week date (use UTC for consistent testing)
const TEST_TIMEZONE = 'America/New_York';
function getTestWeekEnd(): string {
  return getPreviousSunday(TEST_TIMEZONE);
}

function getTestWeekStart(weekEnd: string): string {
  const date = new Date(weekEnd + 'T00:00:00Z');
  date.setUTCDate(date.getUTCDate() - 6);
  return date.toISOString().slice(0, 10);
}

// Mock FCM to avoid actual push notifications in tests
jest.mock('firebase-admin', () => ({
  apps: [],
  initializeApp: jest.fn(),
  credential: {
    cert: jest.fn(),
  },
  messaging: jest.fn(() => ({
    sendEachForMulticast: jest.fn().mockResolvedValue({
      successCount: 1,
      failureCount: 0,
      responses: [{ success: true }],
    }),
    send: jest.fn().mockResolvedValue('message-id'),
  })),
}));

describe('Weekly Recap Integration Tests', () => {
  describe('POST /api/internal/jobs/weekly-recap', () => {
    const INTERNAL_JOB_KEY = process.env.INTERNAL_JOB_KEY || 'test-job-key';

    it('should require internal job key', async () => {
      const response = await request(app)
        .post('/api/internal/jobs/weekly-recap')
        .send({});

      expect(response.status).toBe(401);
    });

    it('should reject invalid job key', async () => {
      const response = await request(app)
        .post('/api/internal/jobs/weekly-recap')
        .set('x-internal-job-key', 'invalid-key')
        .send({});

      expect(response.status).toBe(401);
    });

    it('should process on any day (timezone-aware)', async () => {
      // The job runs every 30 minutes and processes groups where members
      // are experiencing Sunday 7 PM in their timezone
      const response = await request(app)
        .post('/api/internal/jobs/weekly-recap')
        .set('x-internal-job-key', INTERNAL_JOB_KEY)
        .send({});

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('success');
      expect(response.body).toHaveProperty('groups_processed');
    });

    it('should return success with processing stats', async () => {
      const response = await request(app)
        .post('/api/internal/jobs/weekly-recap')
        .set('x-internal-job-key', INTERNAL_JOB_KEY)
        .send({});

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('success');
      expect(response.body).toHaveProperty('groups_processed');
      expect(response.body).toHaveProperty('errors');
      expect(response.body).toHaveProperty('skipped');
    });
  });

  describe('buildWeeklyRecap', () => {
    it('should build full recap with varied progress data', async () => {
      // Create a group with 3 members and progress data
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);

      // Add 2 more members
      const member2 = await addMemberToGroup(creator.accessToken, groupId);
      const member3 = await addMemberToGroup(creator.accessToken, groupId);

      // Create GCR data for the week
      const weekEnd = getTestWeekEnd();
      const weekStartStr = getTestWeekStart(weekEnd);

      await createGcrDataForWeek(groupId, weekStartStr, [0.8, 0.85, 0.9, 0.75, 0.8, 0.85, 0.9]);

      // Create progress entries for streak calculation
      for (let i = 0; i < 7; i++) {
        const date = new Date(weekStartStr + 'T00:00:00Z');
        date.setUTCDate(date.getUTCDate() + i);
        const dateStr = date.toISOString().slice(0, 10);

        await createProgressEntry(goalId!, creator.userId, 1, dateStr);
        if (i < 5) {
          await createProgressEntry(goalId!, member2.memberUserId, 1, dateStr);
        }
      }

      // Build recap
      const recap = await buildWeeklyRecap(groupId, weekStartStr, weekEnd);

      expect(recap.group.id).toBe(groupId);
      expect(recap.completionRate.current).toBeGreaterThan(0);
      expect(recap.highlights.length).toBeGreaterThan(0);
      expect(recap.memberCount).toBe(3);
    });

    it('should calculate per-member completion rates', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);

      const member2 = await addMemberToGroup(creator.accessToken, groupId);

      const weekEnd = getTestWeekEnd();
      const weekStartStr = getTestWeekStart(weekEnd);

      await createGcrDataForWeek(groupId, weekStartStr, [0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5]);

      // Creator completes all 7 days
      for (let i = 0; i < 7; i++) {
        const date = new Date(weekStartStr + 'T00:00:00Z');
        date.setUTCDate(date.getUTCDate() + i);
        await createProgressEntry(goalId!, creator.userId, 1, date.toISOString().slice(0, 10));
      }

      // Member2 completes 3 days
      for (let i = 0; i < 3; i++) {
        const date = new Date(weekStartStr + 'T00:00:00Z');
        date.setUTCDate(date.getUTCDate() + i);
        await createProgressEntry(goalId!, member2.memberUserId, 1, date.toISOString().slice(0, 10));
      }

      const recap = await buildWeeklyRecap(groupId, weekStartStr, weekEnd);

      // Should have highlights for different completion rates
      expect(recap.highlights.length).toBeGreaterThan(0);
    });

    it('should include goal breakdown', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);

      const weekEnd = getTestWeekEnd();
      const weekStartStr = getTestWeekStart(weekEnd);

      await createGcrDataForWeek(groupId, weekStartStr, [0.7, 0.7, 0.7, 0.7, 0.7, 0.7, 0.7]);

      const recap = await buildWeeklyRecap(groupId, weekStartStr, weekEnd);

      expect(recap.goalBreakdown).toBeDefined();
      expect(recap.goalBreakdown.length).toBeGreaterThan(0);
      expect(recap.goalBreakdown[0]).toHaveProperty('goalId');
      expect(recap.goalBreakdown[0]).toHaveProperty('title');
      expect(recap.goalBreakdown[0]).toHaveProperty('completionRate');
    });
  });

  describe('shouldSkipGroup', () => {
    it('should skip if already sent for this week', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken);
      await addMemberToGroup(creator.accessToken, groupId);

      const weekEnd = getTestWeekEnd();
      await createWeeklyRecapSent(groupId, weekEnd);

      const result = await shouldSkipGroup(groupId, weekEnd);

      expect(result.skip).toBe(true);
      expect(result.reason).toMatch(/already_sent/);
    });

    it('should skip if fewer than 2 members', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken);

      const weekEnd = getTestWeekEnd();
      const result = await shouldSkipGroup(groupId, weekEnd);

      expect(result.skip).toBe(true);
      expect(result.reason).toBe('insufficient_members');
    });

    it('should skip if no daily goals', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken, {
        includeGoal: false,
      });
      await addMemberToGroup(creator.accessToken, groupId);

      // Add a weekly goal (not daily)
      await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${creator.accessToken}`)
        .send({
          title: 'Weekly Goal',
          cadence: 'weekly',
          metric_type: 'binary',
        });

      const weekEnd = getTestWeekEnd();
      const result = await shouldSkipGroup(groupId, weekEnd);

      expect(result.skip).toBe(true);
      expect(result.reason).toBe('no_daily_goals');
    });

    it('should skip if group created this week', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken);
      await addMemberToGroup(creator.accessToken, groupId);

      // Get the current week's Sunday
      const weekEnd = getTestWeekEnd();

      const result = await shouldSkipGroup(groupId, weekEnd);

      // Group was created during test, so likely this week
      expect(result.skip).toBe(true);
      expect(result.reason).toBe('group_too_new');
    });

    it('should not skip valid group', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken);
      await addMemberToGroup(creator.accessToken, groupId);

      // Set group created_at to 2 weeks ago
      const twoWeeksAgo = new Date(Date.now() - 14 * 24 * 60 * 60 * 1000).toISOString();
      await sql`UPDATE groups SET created_at = ${twoWeeksAgo} WHERE id = ${groupId}`.execute(testDb);

      const weekEnd = getTestWeekEnd();
      const result = await shouldSkipGroup(groupId, weekEnd);

      expect(result.skip).toBe(false);
    });
  });

  describe('user notifications', () => {
    it('should create user_notifications for each member', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
      const member2 = await addMemberToGroup(creator.accessToken, groupId);

      // Set group to be old enough
      const twoWeeksAgo = new Date(Date.now() - 14 * 24 * 60 * 60 * 1000).toISOString();
      await sql`UPDATE groups SET created_at = ${twoWeeksAgo} WHERE id = ${groupId}`.execute(testDb);

      const weekEnd = getTestWeekEnd();
      const weekStartStr = getTestWeekStart(weekEnd);

      await createGcrDataForWeek(groupId, weekStartStr, [0.7, 0.7, 0.7, 0.7, 0.7, 0.7, 0.7]);

      // Import and call the service directly
      const { sendRecapToGroup } = await import(
        '../../../src/services/weeklyRecap.service.js'
      );
      const recap = await buildWeeklyRecap(groupId, weekStartStr, weekEnd);
      await sendRecapToGroup(groupId, recap);

      // Check notifications were created
      const notifications = await testDb
        .selectFrom('user_notifications')
        .selectAll()
        .where('type', '=', 'weekly_recap')
        .where('group_id', '=', groupId)
        .execute();

      expect(notifications.length).toBe(2); // Creator + 1 member
      expect(notifications[0].metadata).toHaveProperty('week_start');
      expect(notifications[0].metadata).toHaveProperty('completion_rate');
      expect(notifications[0].metadata).toHaveProperty('highlights');
    });

    it('should respect opt-out preference', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken);
      const member2 = await addMemberToGroup(creator.accessToken, groupId);

      // Set group to be old enough
      const twoWeeksAgo = new Date(Date.now() - 14 * 24 * 60 * 60 * 1000).toISOString();
      await sql`UPDATE groups SET created_at = ${twoWeeksAgo} WHERE id = ${groupId}`.execute(testDb);

      // Member2 opts out
      await setWeeklyRecapEnabled(groupId, member2.memberUserId, false);

      const weekEnd = getTestWeekEnd();
      const weekStartStr = getTestWeekStart(weekEnd);

      await createGcrDataForWeek(groupId, weekStartStr, [0.7, 0.7, 0.7, 0.7, 0.7, 0.7, 0.7]);

      const { sendRecapToGroup } = await import(
        '../../../src/services/weeklyRecap.service.js'
      );
      const recap = await buildWeeklyRecap(groupId, weekStartStr, weekEnd);
      const result = await sendRecapToGroup(groupId, recap);

      expect(result.sent).toBe(1); // Only creator
      expect(result.skipped).toBe(1); // Member2 opted out

      // Verify only creator got notification
      const notifications = await testDb
        .selectFrom('user_notifications')
        .selectAll()
        .where('type', '=', 'weekly_recap')
        .where('group_id', '=', groupId)
        .execute();

      expect(notifications.length).toBe(1);
      expect(notifications[0].user_id).toBe(creator.userId);
    });
  });

  describe('deduplication', () => {
    it('should prevent duplicate sends', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken);
      await addMemberToGroup(creator.accessToken, groupId);

      // Set group to be old enough
      const twoWeeksAgo = new Date(Date.now() - 14 * 24 * 60 * 60 * 1000).toISOString();
      await sql`UPDATE groups SET created_at = ${twoWeeksAgo} WHERE id = ${groupId}`.execute(testDb);

      const weekEnd = getTestWeekEnd();
      const weekStartStr = getTestWeekStart(weekEnd);

      await createGcrDataForWeek(groupId, weekStartStr, [0.7, 0.7, 0.7, 0.7, 0.7, 0.7, 0.7]);

      const { sendRecapToGroup } = await import(
        '../../../src/services/weeklyRecap.service.js'
      );

      // Send first time
      const recap = await buildWeeklyRecap(groupId, weekStartStr, weekEnd);
      await sendRecapToGroup(groupId, recap);

      // Check deduplication record
      const dedupRecord = await testDb
        .selectFrom('weekly_recaps_sent')
        .selectAll()
        .where('group_id', '=', groupId)
        .where('week_end', '=', weekEnd)
        .executeTakeFirst();

      expect(dedupRecord).toBeDefined();

      // Try to send again - should be skipped
      const skipCheck = await shouldSkipGroup(groupId, weekEnd);
      expect(skipCheck.skip).toBe(true);
      expect(skipCheck.reason).toBe('already_sent');
    });
  });

  describe('edge cases', () => {
    it('should handle 0% completion week with supportive message', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken);
      await addMemberToGroup(creator.accessToken, groupId);

      const weekEnd = getTestWeekEnd();
      const weekStartStr = getTestWeekStart(weekEnd);

      // Create GCR with 0% completion
      await createGcrDataForWeek(groupId, weekStartStr, [0, 0, 0, 0, 0, 0, 0]);

      const recap = await buildWeeklyRecap(groupId, weekStartStr, weekEnd);

      expect(recap.completionRate.current).toBe(0);
      expect(recap.highlights.length).toBeGreaterThan(0); // Should have fallback
    });

    it('should handle mid-week member join', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);

      // Set group to be old enough
      const twoWeeksAgo = new Date(Date.now() - 14 * 24 * 60 * 60 * 1000).toISOString();
      await sql`UPDATE groups SET created_at = ${twoWeeksAgo} WHERE id = ${groupId}`.execute(testDb);

      const weekEnd = getTestWeekEnd();
      const weekStartStr = getTestWeekStart(weekEnd);

      // Add member mid-week
      const midWeek = new Date(weekStartStr + 'T00:00:00Z');
      midWeek.setUTCDate(midWeek.getUTCDate() + 3);

      const member2 = await addMemberToGroup(creator.accessToken, groupId);

      // Set member's join date to mid-week
      const midWeekIso = midWeek.toISOString();
      await sql`UPDATE group_memberships SET joined_at = ${midWeekIso} WHERE user_id = ${member2.memberUserId} AND group_id = ${groupId}`.execute(testDb);

      await createGcrDataForWeek(groupId, weekStartStr, [0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5]);

      const recap = await buildWeeklyRecap(groupId, weekStartStr, weekEnd);

      // Should build successfully with mid-week joiner
      expect(recap.memberCount).toBe(2);
      expect(recap.highlights.some(h => h.type === 'new_member')).toBe(true);
    });
  });
});
