import request from 'supertest';
import crypto from 'crypto';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  createProgressEntry,
  randomEmail,
} from '../../helpers';
import {
  startOfDay,
  endOfDay,
  startOfWeek,
  endOfWeek,
  startOfMonth,
  endOfMonth,
  startOfYear,
  endOfYear,
  format,
  subDays,
  subWeeks,
  subMonths,
  subYears,
} from 'date-fns';

jest.mock('../../../src/services/fcm.service', () => ({
  sendGroupNotification: jest.fn().mockResolvedValue(undefined),
  buildTopicName: (groupId: string, type: 'progress_logs' | 'group_events') => `${groupId}_${type}`,
  sendToTopic: jest.fn().mockResolvedValue(undefined),
  sendPushNotification: jest.fn().mockResolvedValue(undefined),
  sendNotificationToUser: jest.fn().mockResolvedValue(undefined),
}));

describe('Pending member restrictions', () => {
  it('pending member cannot list group goals', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser();
    const { accessToken: pendingToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Pending');

    const { groupId } = await createGroupWithGoal(creatorToken);

    const inviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${pendingToken}`)
      .send({ invite_code: inviteRes.body.invite_code });

    const response = await request(app)
      .get(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${pendingToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('PENDING_APPROVAL');
    expect(response.body.error?.message).toContain('pending approval');
  });
});

describe('GET /api/groups/:group_id/goals with include_progress', () => {
  describe('Basic Functionality Tests', () => {
    it('include_progress=false returns goals without progress (default behavior)', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken);

      // Add some progress entries
      const today = format(new Date(), 'yyyy-MM-dd');
      await createProgressEntry(goalId!, userId, 1, today);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'false' });

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('goals');
      expect(response.body.goals).toHaveLength(1);
      expect(response.body.goals[0]).not.toHaveProperty('current_period_progress');
      expect(response.body.goals[0]).toMatchObject({
        id: goalId,
        group_id: groupId,
        title: '30 min run',
      });
    });

    it('include_progress=true returns goals with progress structure', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Run 3x per week',
          cadence: 'weekly',
          metric_type: 'binary',
          target_value: 3,
        },
      });

      const now = new Date();
      const weekStart = startOfWeek(now, { weekStartsOn: 1 });
      const weekStartStr = format(weekStart, 'yyyy-MM-dd');

      await createProgressEntry(goalId!, userId, 1, weekStartStr);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('goals');
      expect(response.body.goals).toHaveLength(1);

      const goal = response.body.goals[0];
      expect(goal).toHaveProperty('current_period_progress');
      expect(goal.current_period_progress).toMatchObject({
        start_date: expect.any(String),
        end_date: expect.any(String),
        period_type: 'weekly',
        user_progress: {
          completed: expect.any(Number),
          total: expect.any(Number),
          percentage: expect.any(Number),
          entries: expect.any(Array),
        },
        member_progress: expect.any(Array),
      });

      expect(goal.current_period_progress.user_progress).toMatchObject({
        completed: 1,
        total: 3,
        percentage: expect.any(Number),
        entries: expect.arrayContaining([
          expect.objectContaining({
            date: expect.any(String),
            value: expect.any(Number),
          }),
        ]),
      });

      expect(goal.current_period_progress.member_progress).toBeInstanceOf(Array);
      expect(goal.current_period_progress.member_progress.length).toBeGreaterThan(0);
      const memberProgress = goal.current_period_progress.member_progress[0];
      expect(memberProgress).toMatchObject({
        user_id: expect.any(String),
        display_name: expect.any(String),
        completed: expect.any(Number),
        percentage: expect.any(Number),
      });
      // Verify avatar_url is either null or a string URL
      expect(memberProgress.avatar_url === null || typeof memberProgress.avatar_url === 'string').toBe(true);
    });

    it('invalid include_progress value is handled gracefully', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken);

      // Test with invalid value 'maybe'
      const response1 = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'maybe' });

      expect(response1.status).toBe(200);
      expect(response1.body.goals[0]).not.toHaveProperty('current_period_progress');

      // Test with invalid value '1'
      const response2 = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: '1' });

      expect(response2.status).toBe(200);
      expect(response2.body.goals[0]).not.toHaveProperty('current_period_progress');
    });
  });

  describe('Cadence Tests', () => {
    it('daily cadence calculates correct period bounds', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Daily workout',
          cadence: 'daily',
          metric_type: 'binary',
        },
      });

      const now = new Date();
      const today = format(now, 'yyyy-MM-dd');
      const yesterday = format(subDays(now, 1), 'yyyy-MM-dd');

      await createProgressEntry(goalId!, userId, 1, today);
      await createProgressEntry(goalId!, userId, 1, yesterday);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress.period_type).toBe('daily');

      const periodStart = new Date(goal.current_period_progress.start_date);
      const periodEnd = new Date(goal.current_period_progress.end_date);
      const expectedStart = startOfDay(now);
      const expectedEnd = endOfDay(now);

      expect(periodStart.getTime()).toBe(expectedStart.getTime());
      expect(periodEnd.getTime()).toBe(expectedEnd.getTime());

      // Only today's entries should be included
      expect(goal.current_period_progress.user_progress.entries).toHaveLength(1);
      expect(goal.current_period_progress.user_progress.entries[0].date).toBe(today);
      expect(goal.current_period_progress.user_progress.completed).toBe(1);
    });

    it('weekly cadence calculates correct period bounds (Monday start)', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Weekly goal',
          cadence: 'weekly',
          metric_type: 'binary',
        },
      });

      const now = new Date();
      const weekStart = startOfWeek(now, { weekStartsOn: 1 });
      const weekEnd = endOfWeek(now, { weekStartsOn: 1 });
      const weekStartStr = format(weekStart, 'yyyy-MM-dd');
      const weekEndStr = format(weekEnd, 'yyyy-MM-dd');
      const lastWeekStart = format(subWeeks(weekStart, 1), 'yyyy-MM-dd');

      // Add entries for current week
      await createProgressEntry(goalId!, userId, 1, weekStartStr);
      await createProgressEntry(goalId!, userId, 1, weekEndStr);

      // Add entry for previous week (should be excluded)
      await createProgressEntry(goalId!, userId, 1, lastWeekStart);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress.period_type).toBe('weekly');

      const periodStart = new Date(goal.current_period_progress.start_date);
      const periodEnd = new Date(goal.current_period_progress.end_date);
      const expectedStart = startOfWeek(now, { weekStartsOn: 1 });
      const expectedEnd = endOfWeek(now, { weekStartsOn: 1 });

      expect(periodStart.getTime()).toBe(expectedStart.getTime());
      expect(periodEnd.getTime()).toBe(expectedEnd.getTime());

      // Only current week's entries should be included
      expect(goal.current_period_progress.user_progress.entries.length).toBeGreaterThanOrEqual(1);
      expect(goal.current_period_progress.user_progress.completed).toBeGreaterThanOrEqual(1);
    });

    it('monthly cadence calculates correct period bounds', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Monthly goal',
          cadence: 'monthly',
          metric_type: 'binary',
        },
      });

      const now = new Date();
      const monthStart = startOfMonth(now);
      const monthEnd = endOfMonth(now);
      const monthStartStr = format(monthStart, 'yyyy-MM-dd');
      const monthEndStr = format(monthEnd, 'yyyy-MM-dd');
      const lastMonthEnd = format(subMonths(monthEnd, 1), 'yyyy-MM-dd');

      // Add entries for current month
      await createProgressEntry(goalId!, userId, 1, monthStartStr);
      await createProgressEntry(goalId!, userId, 1, monthEndStr);

      // Add entry for previous month (should be excluded)
      await createProgressEntry(goalId!, userId, 1, lastMonthEnd);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress.period_type).toBe('monthly');

      const periodStart = new Date(goal.current_period_progress.start_date);
      const periodEnd = new Date(goal.current_period_progress.end_date);
      const expectedStart = startOfMonth(now);
      const expectedEnd = endOfMonth(now);

      expect(periodStart.getTime()).toBe(expectedStart.getTime());
      expect(periodEnd.getTime()).toBe(expectedEnd.getTime());

      // Only current month's entries should be included
      expect(goal.current_period_progress.user_progress.entries.length).toBeGreaterThanOrEqual(1);
    });

    it('yearly cadence calculates correct period bounds', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Yearly goal',
          cadence: 'yearly',
          metric_type: 'binary',
        },
      });

      const now = new Date();
      const yearStart = startOfYear(now);
      const yearEnd = endOfYear(now);
      const yearStartStr = format(yearStart, 'yyyy-MM-dd');
      const yearEndStr = format(yearEnd, 'yyyy-MM-dd');
      const lastYearEnd = format(subYears(yearEnd, 1), 'yyyy-MM-dd');

      // Add entries for current year
      await createProgressEntry(goalId!, userId, 1, yearStartStr);
      await createProgressEntry(goalId!, userId, 1, yearEndStr);

      // Add entry for previous year (should be excluded)
      await createProgressEntry(goalId!, userId, 1, lastYearEnd);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress.period_type).toBe('yearly');

      const periodStart = new Date(goal.current_period_progress.start_date);
      const periodEnd = new Date(goal.current_period_progress.end_date);
      const expectedStart = startOfYear(now);
      const expectedEnd = endOfYear(now);

      expect(periodStart.getTime()).toBe(expectedStart.getTime());
      expect(periodEnd.getTime()).toBe(expectedEnd.getTime());

      // Only current year's entries should be included
      expect(goal.current_period_progress.user_progress.entries.length).toBeGreaterThanOrEqual(1);
    });
  });

  describe('Metric Type Tests', () => {
    it('binary goal progress calculation', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Run 3x per week',
          cadence: 'weekly',
          metric_type: 'binary',
          target_value: 3,
        },
      });

      const now = new Date();
      const weekStart = startOfWeek(now, { weekStartsOn: 1 });
      const weekStartStr = format(weekStart, 'yyyy-MM-dd');
      const day2 = format(subDays(weekStart, -2), 'yyyy-MM-dd');

      // Add 2 progress entries
      await createProgressEntry(goalId!, userId, 1, weekStartStr);
      await createProgressEntry(goalId!, userId, 1, day2);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress.user_progress).toMatchObject({
        completed: 2,
        total: 3,
        percentage: 67, // Math.round((2/3) * 100) = 67
        entries: expect.arrayContaining([
          expect.objectContaining({
            date: expect.any(String),
            value: 1,
          }),
        ]),
      });
      expect(goal.current_period_progress.user_progress.entries).toHaveLength(2);
    });

    it('numeric goal progress calculation', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Read 50 pages',
          cadence: 'weekly',
          metric_type: 'numeric',
          target_value: 50,
          unit: 'pages',
        },
      });

      const now = new Date();
      const weekStart = startOfWeek(now, { weekStartsOn: 1 });
      const weekStartStr = format(weekStart, 'yyyy-MM-dd');
      const day2 = format(subDays(weekStart, -2), 'yyyy-MM-dd');

      // Add progress entries: 15 pages, 20 pages
      await createProgressEntry(goalId!, userId, 15, weekStartStr);
      await createProgressEntry(goalId!, userId, 20, day2);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress.user_progress).toMatchObject({
        completed: 35,
        total: 50,
        percentage: 70, // Math.round((35/50) * 100) = 70
        entries: expect.arrayContaining([
          expect.objectContaining({
            date: expect.any(String),
            value: expect.any(Number),
          }),
        ]),
      });
      expect(goal.current_period_progress.user_progress.entries).toHaveLength(2);
    });

    it('duration goal progress calculation', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Exercise 1 hour',
          cadence: 'weekly',
          metric_type: 'duration',
          target_value: 3600, // 1 hour in seconds
        },
      });

      const now = new Date();
      const weekStart = startOfWeek(now, { weekStartsOn: 1 });
      const weekStartStr = format(weekStart, 'yyyy-MM-dd');
      const day2 = format(subDays(weekStart, -2), 'yyyy-MM-dd');

      // Add progress entries: 1800 seconds, 900 seconds
      await createProgressEntry(goalId!, userId, 1800, weekStartStr);
      await createProgressEntry(goalId!, userId, 900, day2);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress.user_progress).toMatchObject({
        completed: 2700,
        total: 3600,
        percentage: 75, // Math.round((2700/3600) * 100) = 75
      });
    });
  });

  describe('Multiple Members Tests', () => {
    it('multiple members progress is included', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Group goal',
          cadence: 'weekly',
          metric_type: 'binary',
        },
      });

      // Add 2 more members
      const { memberAccessToken: memberToken1, memberUserId: memberId1 } =
        await addMemberToGroup(accessToken, groupId);
      const { memberAccessToken: memberToken2, memberUserId: memberId2 } =
        await addMemberToGroup(accessToken, groupId);

      const now = new Date();
      const weekStart = startOfWeek(now, { weekStartsOn: 1 });
      const weekStartStr = format(weekStart, 'yyyy-MM-dd');

      // Each member logs different progress
      await createProgressEntry(goalId!, userId, 1, weekStartStr);
      await createProgressEntry(goalId!, memberId1, 1, weekStartStr);
      await createProgressEntry(goalId!, memberId1, 1, format(subDays(weekStart, -1), 'yyyy-MM-dd'));
      await createProgressEntry(goalId!, memberId2, 1, weekStartStr);
      await createProgressEntry(goalId!, memberId2, 1, format(subDays(weekStart, -1), 'yyyy-MM-dd'));
      await createProgressEntry(goalId!, memberId2, 1, format(subDays(weekStart, -2), 'yyyy-MM-dd'));

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress.member_progress).toHaveLength(3);

      // Verify each member's progress
      const memberProgressMap = new Map(
        goal.current_period_progress.member_progress.map((m: any) => [m.user_id, m])
      );

      expect(memberProgressMap.get(userId)).toMatchObject({
        user_id: userId,
        completed: 1,
        percentage: expect.any(Number),
      });

      expect(memberProgressMap.get(memberId1)).toMatchObject({
        user_id: memberId1,
        completed: 2,
        percentage: expect.any(Number),
      });

      expect(memberProgressMap.get(memberId2)).toMatchObject({
        user_id: memberId2,
        completed: 3,
        percentage: expect.any(Number),
      });

      // Verify user_progress shows current user's progress
      expect(goal.current_period_progress.user_progress.completed).toBe(1);
    });

    it('member avatar URLs are correct', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken);

      // Add a member
      const { memberUserId: memberId } = await addMemberToGroup(accessToken, groupId);

      // Set avatar for one user (by inserting avatar_data)
      await testDb
        .updateTable('users')
        .set({
          avatar_data: Buffer.from('fake-avatar-data'),
          avatar_mime_type: 'image/png',
        })
        .where('id', '=', userId)
        .execute();

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      const memberProgress = goal.current_period_progress.member_progress;

      const userProgress = memberProgress.find((m: any) => m.user_id === userId);
      const memberProgressEntry = memberProgress.find((m: any) => m.user_id === memberId);

      // User with avatar should have avatar_url
      expect(userProgress.avatar_url).toBe(`/api/users/${userId}/avatar`);

      // User without avatar should have null
      expect(memberProgressEntry.avatar_url).toBeNull();
    });
  });

  describe('Edge Cases', () => {
    it('goal with no progress entries', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'New goal',
          cadence: 'weekly',
          metric_type: 'binary',
          target_value: 3,
        },
      });

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress).toBeDefined();
      expect(goal.current_period_progress.user_progress).toMatchObject({
        completed: 0,
        total: 3,
        percentage: 0,
        entries: [],
      });

      // All members should show 0 progress
      goal.current_period_progress.member_progress.forEach((member: any) => {
        expect(member.completed).toBe(0);
        expect(member.percentage).toBe(0);
      });
    });

    it('goal with progress entries outside current period', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Weekly goal',
          cadence: 'weekly',
          metric_type: 'binary',
        },
      });

      const now = new Date();
      const lastWeekStart = format(subWeeks(startOfWeek(now, { weekStartsOn: 1 }), 1), 'yyyy-MM-dd');

      // Add entry for previous week
      await createProgressEntry(goalId!, userId, 1, lastWeekStart);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      const goal = response.body.goals[0];
      expect(goal.current_period_progress.user_progress.entries).toHaveLength(0);
      expect(goal.current_period_progress.user_progress.completed).toBe(0);
    });

    it('multiple goals with different cadences and progress', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      // Create daily goal
      const dailyRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Daily goal',
          cadence: 'daily',
          metric_type: 'binary',
        });
      const dailyGoalId = dailyRes.body.id;

      // Create weekly goal
      const weeklyRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekly goal',
          cadence: 'weekly',
          metric_type: 'binary',
        });
      const weeklyGoalId = weeklyRes.body.id;

      // Create monthly goal
      const monthlyRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Monthly goal',
          cadence: 'monthly',
          metric_type: 'binary',
        });
      const monthlyGoalId = monthlyRes.body.id;

      const now = new Date();
      const today = format(now, 'yyyy-MM-dd');
      const weekStart = format(startOfWeek(now, { weekStartsOn: 1 }), 'yyyy-MM-dd');
      const monthStart = format(startOfMonth(now), 'yyyy-MM-dd');

      // Add progress for each
      await createProgressEntry(dailyGoalId, userId, 1, today);
      await createProgressEntry(weeklyGoalId, userId, 1, weekStart);
      await createProgressEntry(monthlyGoalId, userId, 1, monthStart);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      expect(response.body.goals).toHaveLength(3);

      const dailyGoal = response.body.goals.find((g: any) => g.id === dailyGoalId);
      const weeklyGoal = response.body.goals.find((g: any) => g.id === weeklyGoalId);
      const monthlyGoal = response.body.goals.find((g: any) => g.id === monthlyGoalId);

      expect(dailyGoal.current_period_progress.period_type).toBe('daily');
      expect(weeklyGoal.current_period_progress.period_type).toBe('weekly');
      expect(monthlyGoal.current_period_progress.period_type).toBe('monthly');

      // Each should have correct progress
      expect(dailyGoal.current_period_progress.user_progress.completed).toBe(1);
      expect(weeklyGoal.current_period_progress.user_progress.completed).toBe(1);
      expect(monthlyGoal.current_period_progress.user_progress.completed).toBe(1);
    });

    it('archived goals with progress', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Weekly goal',
          cadence: 'weekly',
          metric_type: 'binary',
        },
      });

      const now = new Date();
      const weekStart = format(startOfWeek(now, { weekStartsOn: 1 }), 'yyyy-MM-dd');
      await createProgressEntry(goalId!, userId, 1, weekStart);

      // Archive the goal
      await request(app)
        .delete(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${accessToken}`);

      // Request with archived=true&include_progress=true
      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ archived: 'true', include_progress: 'true' });

      expect(response.status).toBe(200);
      expect(response.body.goals).toHaveLength(1);
      expect(response.body.goals[0].archived_at).not.toBeNull();
      expect(response.body.goals[0].current_period_progress).toBeDefined();
      expect(response.body.goals[0].current_period_progress.user_progress.completed).toBe(1);
    });

    it('empty goals array with include_progress', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(200);
      expect(response.body.goals).toEqual([]);
      expect(response.body.total).toBe(0);
    });
  });

  describe('Combined Query Parameters', () => {
    it('include_progress=true with cadence filter', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

      // Create goals with different cadences
      const dailyRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Daily goal',
          cadence: 'daily',
          metric_type: 'binary',
        });
      const dailyGoalId = dailyRes.body.id;

      await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Weekly goal',
          cadence: 'weekly',
          metric_type: 'binary',
        });

      const now = new Date();
      const today = format(now, 'yyyy-MM-dd');
      const weekStart = format(startOfWeek(now, { weekStartsOn: 1 }), 'yyyy-MM-dd');

      await createProgressEntry(dailyGoalId, userId, 1, today);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ cadence: 'daily', include_progress: 'true' });

      expect(response.status).toBe(200);
      expect(response.body.goals).toHaveLength(1);
      expect(response.body.goals[0].cadence).toBe('daily');
      expect(response.body.goals[0].current_period_progress).toBeDefined();
      expect(response.body.goals[0].current_period_progress.user_progress.completed).toBe(1);
    });

    it('include_progress=true with archived filter', async () => {
      const { accessToken, userId } = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(accessToken, {
        goal: {
          title: 'Weekly goal',
          cadence: 'weekly',
          metric_type: 'binary',
        },
      });

      const now = new Date();
      const weekStart = format(startOfWeek(now, { weekStartsOn: 1 }), 'yyyy-MM-dd');
      await createProgressEntry(goalId!, userId, 1, weekStart);

      // Create another active goal
      const activeRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Active goal',
          cadence: 'weekly',
          metric_type: 'binary',
        });
      const activeGoalId = activeRes.body.id;
      await createProgressEntry(activeGoalId, userId, 1, weekStart);

      // Archive the first goal
      await request(app)
        .delete(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${accessToken}`);

      // Request with archived=true&include_progress=true
      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .query({ archived: 'true', include_progress: 'true' });

      expect(response.status).toBe(200);
      expect(response.body.goals.length).toBeGreaterThanOrEqual(1);
      const archivedGoal = response.body.goals.find((g: any) => g.id === goalId);
      expect(archivedGoal).toBeDefined();
      expect(archivedGoal.archived_at).not.toBeNull();
      expect(archivedGoal.current_period_progress).toBeDefined();
      expect(archivedGoal.current_period_progress.user_progress.completed).toBe(1);
    });
  });

  describe('Authorization Tests', () => {
    it('non-member cannot access goals with progress', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken);

      // Create another user who is not a member
      const { accessToken: nonMemberToken } = await createAuthenticatedUser(randomEmail());

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${nonMemberToken}`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(403);
    });

    it('unauthenticated request returns 401', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken);

      const response = await request(app)
        .get(`/api/groups/${groupId}/goals`)
        .query({ include_progress: 'true' });

      expect(response.status).toBe(401);
    });
  });
});

describe('POST /api/groups/:group_id/goals', () => {
  beforeEach(() => jest.clearAllMocks());

  it('should create binary goal as admin', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        title: '30 min run',
        description: 'Run for at least 30 minutes',
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(201);
    expect(response.body).toMatchObject({
      group_id: groupId,
      title: '30 min run',
      description: 'Run for at least 30 minutes',
      cadence: 'daily',
      metric_type: 'binary',
      target_value: null,
      unit: null,
      created_by_user_id: userId,
      archived_at: null,
    });
    expect(response.body).toHaveProperty('id');
    expect(response.body).toHaveProperty('created_at');

    const row = await testDb
      .selectFrom('goals')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('title', '=', '30 min run')
      .executeTakeFirst();
    expect(row).toBeDefined();

    const activity = await testDb
      .selectFrom('group_activities')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('activity_type', '=', 'goal_added')
      .executeTakeFirst();
    expect(activity).toBeDefined();
  });

  it('should create numeric goal with target_value and unit', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        title: 'Read 50 pages',
        cadence: 'daily',
        metric_type: 'numeric',
        target_value: 50,
        unit: 'pages',
      });

    expect(response.status).toBe(201);
    expect(response.body).toMatchObject({
      group_id: groupId,
      title: 'Read 50 pages',
      cadence: 'daily',
      metric_type: 'numeric',
      unit: 'pages',
      created_by_user_id: userId,
      archived_at: null,
    });
    expect(response.body.target_value === 50 || response.body.target_value === '50.00').toBe(true);
  });

  it('should return 401 without Authorization header', async () => {
    const { groupId } = await createGroupWithGoal(
      (await createAuthenticatedUser()).accessToken,
      { includeGoal: false }
    );

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .send({
        title: 'Test goal',
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(401);
  });

  it('should return 401 with invalid token', async () => {
    const { groupId } = await createGroupWithGoal(
      (await createAuthenticatedUser()).accessToken,
      { includeGoal: false }
    );

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', 'Bearer invalid-token')
      .send({
        title: 'Test goal',
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_TOKEN');
  });

  it('should return 403 when non-member creates goal', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(creator.accessToken, { includeGoal: false });
    const nonMember = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${nonMember.accessToken}`)
      .send({
        title: 'Test goal',
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });

  it('should return 403 when member (not admin) creates goal', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(creator.accessToken, { includeGoal: false });
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({
        title: 'Test goal',
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });

  it('should return 400 for invalid body (missing title)', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should return 400 for numeric goal without target_value', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        title: 'Read pages',
        cadence: 'daily',
        metric_type: 'numeric',
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should return 404 for non-existent group_id', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeGroupId = crypto.randomUUID();

    const response = await request(app)
      .post(`/api/groups/${fakeGroupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        title: 'Test goal',
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });

  it('should reject goal title exceeding max length (>200 chars)', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        title: 'A'.repeat(250),
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject goal description exceeding max length (>1000 chars)', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        title: 'Test goal',
        description: 'A'.repeat(1500),
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });
});

describe('GET /api/goals/:goal_id', () => {
  it('should return goal when member requests', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .get(`/api/goals/${goalId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      id: goalId,
      group_id: groupId,
      title: '30 min run',
      description: 'Run 30 min',
      cadence: 'daily',
      metric_type: 'binary',
    });
    expect(response.body).toHaveProperty('created_at');
  });

  it('should return 401 without auth', async () => {
    const { goalId } = await createGroupWithGoal(
      (await createAuthenticatedUser()).accessToken
    );

    const response = await request(app).get(`/api/goals/${goalId}`);
    expect(response.status).toBe(401);
  });

  it('should return 401 with invalid token', async () => {
    const { goalId } = await createGroupWithGoal(
      (await createAuthenticatedUser()).accessToken
    );

    const response = await request(app)
      .get(`/api/goals/${goalId}`)
      .set('Authorization', 'Bearer invalid-token');

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_TOKEN');
  });

  it('should return 403 when non-member requests goal', async () => {
    const creator = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(creator.accessToken);
    const nonMember = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .get(`/api/goals/${goalId}`)
      .set('Authorization', `Bearer ${nonMember.accessToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });

  it('should return 404 for non-existent goal_id', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeGoalId = crypto.randomUUID();

    const response = await request(app)
      .get(`/api/goals/${fakeGoalId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });
});

describe('PATCH /api/goals/:goal_id', () => {
  it('should update goal title and description as admin', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .patch(`/api/goals/${goalId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        title: '35 min run',
        description: 'Increased to 35 minutes',
      });

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      id: goalId,
      title: '35 min run',
      description: 'Increased to 35 minutes',
    });

    const row = await testDb
      .selectFrom('goals')
      .select(['title', 'description'])
      .where('id', '=', goalId!)
      .executeTakeFirst();
    expect(row?.title).toBe('35 min run');
    expect(row?.description).toBe('Increased to 35 minutes');
  });

  it('should return 200 with current goal when body has no updates', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .patch(`/api/goals/${goalId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({});

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      id: goalId,
      title: '30 min run',
      description: 'Run 30 min',
    });
  });

  it('should return 401 without auth', async () => {
    const { goalId } = await createGroupWithGoal(
      (await createAuthenticatedUser()).accessToken
    );

    const response = await request(app)
      .patch(`/api/goals/${goalId}`)
      .send({ title: 'Updated' });

    expect(response.status).toBe(401);
  });

  it('should return 403 when member (not admin) updates goal', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

    const response = await request(app)
      .patch(`/api/goals/${goalId}`)
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({ title: 'Hacked title' });

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });

  it('should return 400 for invalid body (title too long)', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .patch(`/api/goals/${goalId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ title: 'a'.repeat(201) });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should return 404 for non-existent goal_id', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeGoalId = crypto.randomUUID();

    const response = await request(app)
      .patch(`/api/goals/${fakeGoalId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ title: 'Updated' });

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });
});

describe('DELETE /api/goals/:goal_id', () => {
  beforeEach(() => jest.clearAllMocks());

  it('should soft-delete goal as admin and return 204', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .delete(`/api/goals/${goalId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(204);

    const row = await testDb
      .selectFrom('goals')
      .select(['deleted_at', 'deleted_by_user_id'])
      .where('id', '=', goalId!)
      .executeTakeFirst();
    expect(row?.deleted_at).not.toBeNull();

    const activity = await testDb
      .selectFrom('group_activities')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('activity_type', '=', 'goal_archived')
      .executeTakeFirst();
    expect(activity).toBeDefined();
  });

  it('should return 401 without auth', async () => {
    const { goalId } = await createGroupWithGoal(
      (await createAuthenticatedUser()).accessToken
    );

    const response = await request(app).delete(`/api/goals/${goalId}`);
    expect(response.status).toBe(401);
  });

  it('should return 403 when member (not admin) deletes goal', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

    const response = await request(app)
      .delete(`/api/goals/${goalId}`)
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');

    const row = await testDb
      .selectFrom('goals')
      .select('deleted_at')
      .where('id', '=', goalId!)
      .executeTakeFirst();
    expect(row?.deleted_at).toBeNull();
  });

  it('should return 404 for non-existent goal_id', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeGoalId = crypto.randomUUID();

    const response = await request(app)
      .delete(`/api/goals/${fakeGoalId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });
});

describe('GET /api/goals/:goal_id/progress', () => {
  it('should return goal and progress when member requests', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(accessToken);
    const userDate = format(new Date(), 'yyyy-MM-dd');
    await createProgressEntry(goalId!, userId, 1, userDate, 'Great run');

    const response = await request(app)
      .get(`/api/goals/${goalId}/progress`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toHaveProperty('goal');
    expect(response.body.goal).toMatchObject({
      id: goalId,
      title: '30 min run',
      cadence: 'daily',
    });
    expect(response.body).toHaveProperty('progress');
    expect(Array.isArray(response.body.progress)).toBe(true);
    const myProgress = response.body.progress.find((p: any) => p.user_id === userId);
    expect(myProgress).toBeDefined();
    expect(myProgress.display_name).toBeDefined();
    expect(myProgress.entries).toHaveLength(1);
    expect(myProgress.entries[0]).toMatchObject({
      value: 1,
      note: 'Great run',
      period_start: userDate,
    });
    expect(myProgress.entries[0]).toHaveProperty('id');
    expect(myProgress.entries[0]).toHaveProperty('logged_at');
  });

  it('should filter by start_date and end_date when provided', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);
    const start = '2025-01-01';
    const end = '2025-01-31';
    await createProgressEntry(goalId!, userId, 1, '2025-01-15', 'Mid month');

    const response = await request(app)
      .get(`/api/goals/${goalId}/progress`)
      .set('Authorization', `Bearer ${accessToken}`)
      .query({ start_date: start, end_date: end });

    expect(response.status).toBe(200);
    expect(response.body.progress).toBeDefined();
    const myProgress = response.body.progress.find((p: any) => p.user_id === userId);
    expect(myProgress?.entries).toHaveLength(1);
    expect(myProgress?.entries[0].period_start).toBe('2025-01-15');
  });

  it('should return 401 without auth', async () => {
    const { goalId } = await createGroupWithGoal(
      (await createAuthenticatedUser()).accessToken
    );

    const response = await request(app).get(`/api/goals/${goalId}/progress`);
    expect(response.status).toBe(401);
  });

  it('should return 403 when non-member requests', async () => {
    const creator = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(creator.accessToken);
    const nonMember = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .get(`/api/goals/${goalId}/progress`)
      .set('Authorization', `Bearer ${nonMember.accessToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });

  it('should return 400 for invalid start_date or end_date', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .get(`/api/goals/${goalId}/progress`)
      .set('Authorization', `Bearer ${accessToken}`)
      .query({ start_date: 'not-a-date', end_date: '2025-01-31' });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should return 404 for non-existent goal_id', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeGoalId = crypto.randomUUID();

    const response = await request(app)
      .get(`/api/goals/${fakeGoalId}/progress`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });
});

describe('GET /api/goals/:goal_id/progress/me', () => {
  it('should return own progress when member requests', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId, goalId } = await createGroupWithGoal(accessToken);
    const userDate = format(new Date(), 'yyyy-MM-dd');
    await createProgressEntry(goalId!, userId, 1, userDate, 'Morning run');

    const response = await request(app)
      .get(`/api/goals/${goalId}/progress/me`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      goal_id: goalId,
    });
    expect(response.body.entries).toHaveLength(1);
    expect(response.body.entries[0]).toMatchObject({
      value: 1,
      note: 'Morning run',
      period_start: userDate,
    });
    expect(response.body.entries[0]).toHaveProperty('id');
    expect(response.body.entries[0]).toHaveProperty('logged_at');
  });

  it('should filter by start_date and end_date when provided', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);
    await createProgressEntry(goalId!, userId, 1, '2025-01-10', 'Entry 1');
    await createProgressEntry(goalId!, userId, 1, '2025-01-20', 'Entry 2');

    const response = await request(app)
      .get(`/api/goals/${goalId}/progress/me`)
      .set('Authorization', `Bearer ${accessToken}`)
      .query({ start_date: '2025-01-01', end_date: '2025-01-15' });

    expect(response.status).toBe(200);
    expect(response.body.entries).toHaveLength(1);
    expect(response.body.entries[0].period_start).toBe('2025-01-10');
  });

  it('should return 401 without auth', async () => {
    const { goalId } = await createGroupWithGoal(
      (await createAuthenticatedUser()).accessToken
    );

    const response = await request(app).get(`/api/goals/${goalId}/progress/me`);
    expect(response.status).toBe(401);
  });

  it('should return 403 when non-member requests', async () => {
    const creator = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(creator.accessToken);
    const nonMember = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .get(`/api/goals/${goalId}/progress/me`)
      .set('Authorization', `Bearer ${nonMember.accessToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error.code).toBe('FORBIDDEN');
  });

  it('should return 400 for invalid start_date or end_date', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { goalId } = await createGroupWithGoal(accessToken);

    const response = await request(app)
      .get(`/api/goals/${goalId}/progress/me`)
      .set('Authorization', `Bearer ${accessToken}`)
      .query({ start_date: 'invalid' });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should return 404 for non-existent goal_id', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeGoalId = crypto.randomUUID();

    const response = await request(app)
      .get(`/api/goals/${fakeGoalId}/progress/me`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });
});

describe('Goals Authorization - Cross-User Access Tests', () => {
  describe('GET /api/goals/:goal_id - User not in group', () => {
    it('should return 403 when user tries to access a goal from a different group', async () => {
      const creator = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);

      const outsideUser = await createAuthenticatedUser(randomEmail());

      const response = await request(app)
        .get(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${outsideUser.accessToken}`);

      expect(response.status).toBe(403);
      expect(response.body.error.code).toBe('FORBIDDEN');
      expect(response.body.error.message.toLowerCase()).toContain('not a member');
    });

    it('should allow access when user is a member of the group', async () => {
      const creator = await createAuthenticatedUser();
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);

      const { memberAccessToken: memberToken } = await addMemberToGroup(creator.accessToken, groupId);

      const response = await request(app)
        .get(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('id', goalId);
      expect(response.body).toHaveProperty('group_id', groupId);
    });
  });

  describe('GET /api/goals/:goal_id/progress - User not in group', () => {
    it('should return 403 when user tries to view progress from a goal outside their group', async () => {
      const creator = await createAuthenticatedUser();
      const { goalId, groupId } = await createGroupWithGoal(creator.accessToken);

      // Add progress to the goal
      await createProgressEntry(goalId!, creator.userId, 1, format(new Date(), 'yyyy-MM-dd'));

      const outsideUser = await createAuthenticatedUser(randomEmail());

      const response = await request(app)
        .get(`/api/goals/${goalId}/progress`)
        .set('Authorization', `Bearer ${outsideUser.accessToken}`);

      expect(response.status).toBe(403);
      expect(response.body.error.code).toBe('FORBIDDEN');
    });

    it('should allow group members to view progress for shared goals', async () => {
      const creator = await createAuthenticatedUser();
      const { goalId, groupId } = await createGroupWithGoal(creator.accessToken);

      // Add progress entry
      await createProgressEntry(goalId!, creator.userId, 1, format(new Date(), 'yyyy-MM-dd'), 'Test entry');

      // Add another member
      const { memberAccessToken: memberToken } = await addMemberToGroup(creator.accessToken, groupId!);

      const response = await request(app)
        .get(`/api/goals/${goalId}/progress`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('goal');
      expect(response.body).toHaveProperty('progress');
      expect(response.body.progress).toBeInstanceOf(Array);
    });
  });

  describe('POST /api/progress - User not in group', () => {
    it('should return 403 when user tries to log progress for a goal outside their group', async () => {
      const creator = await createAuthenticatedUser();
      const { goalId, groupId } = await createGroupWithGoal(creator.accessToken);

      const outsideUser = await createAuthenticatedUser(randomEmail());
      const userDate = format(new Date(), 'yyyy-MM-dd');

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${outsideUser.accessToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate,
          user_timezone: 'UTC',
        });

      expect(response.status).toBe(403);
      expect(response.body.error.code).toBe('FORBIDDEN');
    });

    it('should allow group members to log progress for shared goals', async () => {
      const creator = await createAuthenticatedUser();
      const { goalId, groupId } = await createGroupWithGoal(creator.accessToken);

      // Add another member
      const { memberAccessToken: memberToken, memberUserId: memberId } = await addMemberToGroup(creator.accessToken, groupId!);
      const userDate = format(new Date(), 'yyyy-MM-dd');

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${memberToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate,
          user_timezone: 'UTC',
        });

      expect(response.status).toBe(201);
      expect(response.body).toHaveProperty('id');
      expect(response.body).toHaveProperty('user_id', memberId);
      expect(response.body).toHaveProperty('goal_id', goalId);
    });

    it('should prevent one user from logging progress for another user (even in same group)', async () => {
      const creator = await createAuthenticatedUser();
      const { goalId, groupId } = await createGroupWithGoal(creator.accessToken);

      // Add another member
      const { memberAccessToken: memberToken } = await addMemberToGroup(creator.accessToken, groupId!);

      // Try to log progress for the creator (memberToken is member but trying to use creator's ID)
      // Note: Currently progress creation always uses req.user.id, so member can only log their own progress
      // This test verifies that behavior is enforced
      const userDate = format(new Date(), 'yyyy-MM-dd');

      const response = await request(app)
        .post('/api/progress')
        .set('Authorization', `Bearer ${memberToken}`)
        .send({
          goal_id: goalId,
          value: 1,
          user_date: userDate,
          user_timezone: 'UTC',
        });

      expect(response.status).toBe(201);
      // Verify the logged_in user is recorded as the one logging progress (not the creator)
      expect(response.body.user_id).not.toBe(creator.userId);
    });
  });

  describe('GET /api/goals/:goal_id/progress/me - User not in group', () => {
    it('should return 403 when user tries to view their own progress for a goal outside their group', async () => {
      const creator = await createAuthenticatedUser();
      const { goalId, groupId } = await createGroupWithGoal(creator.accessToken);

      // Add some progress
      await createProgressEntry(goalId!, creator.userId, 1, format(new Date(), 'yyyy-MM-dd'));

      const outsideUser = await createAuthenticatedUser(randomEmail());

      const response = await request(app)
        .get(`/api/goals/${goalId}/progress/me`)
        .set('Authorization', `Bearer ${outsideUser.accessToken}`);

      expect(response.status).toBe(403);
      expect(response.body.error.code).toBe('FORBIDDEN');
    });

    it('should allow members to view their own progress for shared goals', async () => {
      const creator = await createAuthenticatedUser();
      const { goalId, groupId } = await createGroupWithGoal(creator.accessToken);

      // Add another member and log their progress
      const { memberAccessToken: memberToken, memberUserId: memberId } = await addMemberToGroup(creator.accessToken, groupId!);
      const userDate = format(new Date(), 'yyyy-MM-dd');
      await createProgressEntry(goalId!, memberId, 1, userDate, 'Member entry');

      const response = await request(app)
        .get(`/api/goals/${goalId}/progress/me`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(response.status).toBe(200);
      expect(response.body).toHaveProperty('goal_id', goalId);
      expect(response.body).toHaveProperty('entries');
      expect(response.body.entries).toBeInstanceOf(Array);
      expect(response.body.entries.length).toBeGreaterThan(0);
    });
  });
});
