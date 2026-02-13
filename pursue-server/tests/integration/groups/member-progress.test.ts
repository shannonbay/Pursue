import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  createProgressEntry,
  createTestPhoto,
  createTestGroupActivityWithId,
  setUserPremium,
  randomEmail,
} from '../../helpers';

// Mock GCS service for signed URLs
jest.mock('../../../src/services/gcs.service.js', () => ({
  getSignedUrl: jest.fn().mockResolvedValue('https://storage.googleapis.com/signed-url'),
  uploadPhoto: jest.fn().mockResolvedValue(undefined),
  deletePhoto: jest.fn().mockResolvedValue(undefined),
  buildObjectPath: jest.fn().mockReturnValue('test/path.jpg'),
}));

describe('GET /api/groups/:group_id/members/:user_id/progress', () => {
  let creatorToken: string;
  let creatorId: string;
  let memberToken: string;
  let memberId: string;
  let groupId: string;
  let goalId: string;

  beforeEach(async () => {
    // Create creator and group with a goal
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    creatorToken = creator.accessToken;
    creatorId = creator.userId;

    const group = await createGroupWithGoal(creatorToken, {
      includeGoal: true,
      groupName: 'Test Group',
      goal: {
        title: 'Daily Exercise',
        cadence: 'daily',
        metric_type: 'binary',
        target_value: 1,
      },
    });
    groupId = group.groupId;
    goalId = group.goalId!;

    // Add a member to the group
    const member = await addMemberToGroup(creatorToken, groupId);
    memberToken = member.memberAccessToken;
    memberId = member.memberUserId;
  });

  describe('Success cases', () => {
    it('should return member info, goal summaries, and empty activity log', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('member');
      expect(response.body.member.user_id).toBe(memberId);
      expect(response.body.member).toHaveProperty('display_name');
      expect(response.body.member).toHaveProperty('role');
      expect(response.body.member).toHaveProperty('joined_at');

      expect(response.body).toHaveProperty('timeframe');
      expect(response.body.timeframe.start_date).toBe(weekAgo);
      expect(response.body.timeframe.end_date).toBe(today);

      expect(response.body).toHaveProperty('goal_summaries');
      expect(Array.isArray(response.body.goal_summaries)).toBe(true);
      expect(response.body.goal_summaries.length).toBeGreaterThanOrEqual(1);

      expect(response.body).toHaveProperty('activity_log');
      expect(Array.isArray(response.body.activity_log)).toBe(true);

      expect(response.body).toHaveProperty('pagination');
      expect(response.body.pagination.has_more).toBe(false);
      expect(response.body.pagination.next_cursor).toBeNull();
    });

    it('should return progress entries in activity log', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Create progress entries for the member
      await createProgressEntry(goalId, memberId, 1, today, 'Completed today');

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      expect(response.body.activity_log.length).toBe(1);
      expect(response.body.activity_log[0]).toHaveProperty('entry_id');
      expect(response.body.activity_log[0].goal_id).toBe(goalId);
      expect(response.body.activity_log[0].goal_title).toBe('Daily Exercise');
      expect(response.body.activity_log[0].value).toBe(1);
      expect(response.body.activity_log[0].note).toBe('Completed today');
      expect(response.body.pagination.total_in_timeframe).toBe(1);
    });

    it('should calculate goal summaries correctly', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Create 3 progress entries
      const dates = [
        today,
        new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
        new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
      ];

      for (const date of dates) {
        await createProgressEntry(goalId, memberId, 1, date);
      }

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);

      const goalSummary = response.body.goal_summaries.find(
        (g: { goal_id: string }) => g.goal_id === goalId
      );
      expect(goalSummary).toBeDefined();
      expect(goalSummary.completed).toBe(3);
      expect(goalSummary.total).toBeGreaterThanOrEqual(3); // At least 8 days in range
      expect(goalSummary.percentage).toBeGreaterThan(0);
      expect(goalSummary.percentage).toBeLessThanOrEqual(100);
    });

    it('should include photos with signed URLs', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Create progress entry with photo
      const entryId = await createProgressEntry(goalId, memberId, 1, today);
      await createTestPhoto(entryId, memberId);

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      expect(response.body.activity_log.length).toBe(1);
      expect(response.body.activity_log[0].photo_url).toBe(
        'https://storage.googleapis.com/signed-url'
      );
    });

    it('should include reactions aggregated by emoji', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Create progress entry
      const entryId = await createProgressEntry(goalId, memberId, 1, today);

      // Create group activity for the progress entry
      const activityId = await createTestGroupActivityWithId(groupId, memberId, 'progress_logged', {
        progress_entry_id: entryId,
        goal_id: goalId,
        value: 1,
      });

      // Create reactions
      await testDb
        .insertInto('activity_reactions')
        .values([
          { activity_id: activityId, user_id: creatorId, emoji: '🔥' },
          { activity_id: activityId, user_id: memberId, emoji: '🔥' },
        ])
        .execute();

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      expect(response.body.activity_log.length).toBe(1);
      expect(response.body.activity_log[0].reactions).toEqual(
        expect.arrayContaining([expect.objectContaining({ emoji: '🔥', count: 2 })])
      );
    });

    it('should allow member to view their own progress', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${memberToken}`);

      expect(response.status).toBe(200);
      expect(response.body.member.user_id).toBe(memberId);
    });

    it('should allow member to view another member\'s progress', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Member viewing creator's progress
      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${creatorId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${memberToken}`);

      expect(response.status).toBe(200);
      expect(response.body.member.user_id).toBe(creatorId);
    });
  });

  describe('Pagination', () => {
    it('should paginate activity log correctly', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Create multiple progress entries with different dates
      for (let i = 0; i < 5; i++) {
        const date = new Date(Date.now() - i * 24 * 60 * 60 * 1000).toISOString().split('T')[0];
        await createProgressEntry(goalId, memberId, 1, date, `Entry ${i}`);
      }

      // Request first page with limit 2
      const response1 = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today, limit: 2 })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response1.status).toBe(200);
      expect(response1.body.activity_log.length).toBe(2);
      expect(response1.body.pagination.has_more).toBe(true);
      expect(response1.body.pagination.next_cursor).not.toBeNull();
      expect(response1.body.pagination.total_in_timeframe).toBe(5);

      // Request second page
      const response2 = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({
          start_date: weekAgo,
          end_date: today,
          limit: 2,
          cursor: response1.body.pagination.next_cursor,
        })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response2.status).toBe(200);
      expect(response2.body.activity_log.length).toBe(2);
      expect(response2.body.pagination.has_more).toBe(true);

      // Request third (last) page
      const response3 = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({
          start_date: weekAgo,
          end_date: today,
          limit: 2,
          cursor: response2.body.pagination.next_cursor,
        })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response3.status).toBe(200);
      expect(response3.body.activity_log.length).toBe(1);
      expect(response3.body.pagination.has_more).toBe(false);
      expect(response3.body.pagination.next_cursor).toBeNull();
    });

    it('should return entries in descending order by logged_at', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Create entries on different days
      const dates = [
        new Date(Date.now() - 5 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
        new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
        new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString().split('T')[0],
      ];

      for (const date of dates) {
        await createProgressEntry(goalId, memberId, 1, date);
      }

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      expect(response.body.activity_log.length).toBe(3);

      // Verify descending order
      const loggedAtTimes = response.body.activity_log.map(
        (entry: { logged_at: string }) => new Date(entry.logged_at).getTime()
      );
      for (let i = 1; i < loggedAtTimes.length; i++) {
        expect(loggedAtTimes[i - 1]).toBeGreaterThanOrEqual(loggedAtTimes[i]);
      }
    });
  });

  describe('Authorization', () => {
    it('should return 401 without auth token', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today });

      expect(response.status).toBe(401);
    });

    it('should return 403 if requesting user is not a group member', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      const outsider = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Outsider');

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${outsider.accessToken}`);

      expect(response.status).toBe(403);
    });

    it('should return 403 TARGET_NOT_A_MEMBER if target user is not a member', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      const nonMember = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'NonMember');

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${nonMember.userId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(403);
      expect(response.body.error?.code).toBe('TARGET_NOT_A_MEMBER');
    });

    it('should return 404 GROUP_NOT_FOUND for non-existent group', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];
      const fakeGroupId = '00000000-0000-0000-0000-000000000000';

      const response = await request(app)
        .get(`/api/groups/${fakeGroupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(404);
      expect(response.body.error?.code).toBe('NOT_FOUND');
    });
  });

  describe('Premium tier validation', () => {
    it('should return 403 SUBSCRIPTION_REQUIRED for date range > 30 days without premium', async () => {
      const today = new Date().toISOString().split('T')[0];
      const twoMonthsAgo = new Date(Date.now() - 60 * 24 * 60 * 60 * 1000)
        .toISOString()
        .split('T')[0];

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: twoMonthsAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(403);
      expect(response.body.error?.code).toBe('SUBSCRIPTION_REQUIRED');
    });

    it('should allow premium user to request > 30 day range', async () => {
      const today = new Date().toISOString().split('T')[0];
      const twoMonthsAgo = new Date(Date.now() - 60 * 24 * 60 * 60 * 1000)
        .toISOString()
        .split('T')[0];

      // Make creator premium
      await setUserPremium(creatorId);

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: twoMonthsAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
    });

    it('should allow exactly 30 day range for free users', async () => {
      const today = new Date().toISOString().split('T')[0];
      const thirtyDaysAgo = new Date(Date.now() - 29 * 24 * 60 * 60 * 1000)
        .toISOString()
        .split('T')[0];

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: thirtyDaysAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
    });
  });

  describe('Validation', () => {
    it('should return 400 for invalid date format', async () => {
      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: 'invalid', end_date: '2026-02-13' })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(400);
      expect(response.body.error?.code).toBe('VALIDATION_ERROR');
    });

    it('should return 400 for end_date before start_date', async () => {
      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: '2026-02-13', end_date: '2026-02-06' })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(400);
      expect(response.body.error?.code).toBe('INVALID_DATE_RANGE');
    });

    it('should return 400 for malformed cursor', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today, cursor: 'invalid-cursor' })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(400);
      expect(response.body.error?.code).toBe('INVALID_CURSOR');
    });

    it('should return 400 for cursor with invalid structure', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Valid base64 but invalid structure
      const invalidCursor = Buffer.from(JSON.stringify({ foo: 'bar' })).toString('base64');

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today, cursor: invalidCursor })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(400);
      expect(response.body.error?.code).toBe('INVALID_CURSOR');
    });

    it('should respect max limit of 50', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today, limit: 100 })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(400);
    });
  });

  describe('Edge cases', () => {
    it('should return empty activity log when no entries in timeframe', async () => {
      const futureStart = new Date(Date.now() + 30 * 24 * 60 * 60 * 1000)
        .toISOString()
        .split('T')[0];
      const futureEnd = new Date(Date.now() + 37 * 24 * 60 * 60 * 1000)
        .toISOString()
        .split('T')[0];

      // Use a past date range that has no entries
      const oldStart = '2020-01-01';
      const oldEnd = '2020-01-07';

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: oldStart, end_date: oldEnd })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      expect(response.body.activity_log).toEqual([]);
      expect(response.body.pagination.total_in_timeframe).toBe(0);
      expect(response.body.pagination.has_more).toBe(false);
    });

    it('should show 0/total for goals with no progress', async () => {
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Don't create any progress entries

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      const goalSummary = response.body.goal_summaries.find(
        (g: { goal_id: string }) => g.goal_id === goalId
      );
      expect(goalSummary).toBeDefined();
      expect(goalSummary.completed).toBe(0);
      expect(goalSummary.total).toBeGreaterThan(0);
      expect(goalSummary.percentage).toBe(0);
    });

    it('should handle numeric goals correctly', async () => {
      // Create a numeric goal
      const numericGoal = await testDb
        .insertInto('goals')
        .values({
          group_id: groupId,
          title: 'Read pages',
          cadence: 'weekly',
          metric_type: 'numeric',
          target_value: 50,
          unit: 'pages',
        })
        .returning('id')
        .executeTakeFirstOrThrow();

      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 24 * 60 * 60 * 1000).toISOString().split('T')[0];

      // Create progress entries
      await createProgressEntry(numericGoal.id, memberId, 20, today);
      await createProgressEntry(
        numericGoal.id,
        memberId,
        15,
        new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString().split('T')[0]
      );

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/${memberId}/progress`)
        .query({ start_date: weekAgo, end_date: today })
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);

      const goalSummary = response.body.goal_summaries.find(
        (g: { goal_id: string }) => g.goal_id === numericGoal.id
      );
      expect(goalSummary).toBeDefined();
      expect(goalSummary.completed).toBe(35); // 20 + 15
      expect(goalSummary.unit).toBe('pages');
      expect(goalSummary.metric_type).toBe('numeric');
    });
  });
});
