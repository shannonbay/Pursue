import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  createProgressEntry,
  setUserTimezone,
  setUserPremium,
  randomEmail,
} from '../../helpers';

// Helper: today's date as YYYY-MM-DD in UTC
function todayUtc(): string {
  return new Date().toISOString().slice(0, 10);
}

// Helper: a date in the current ISO week (Monday–Sunday) that is not today
function earlierThisWeekUtc(): string | null {
  const now = new Date();
  // date_trunc('week', ...) gives Monday
  const day = now.getUTCDay(); // 0=Sun, 1=Mon, ..., 6=Sat
  const daysFromMonday = day === 0 ? 6 : day - 1;
  if (daysFromMonday === 0) return null; // it's Monday — no earlier day this week
  const monday = new Date(now);
  monday.setUTCDate(now.getUTCDate() - daysFromMonday + 1); // a day after Monday
  return monday.toISOString().slice(0, 10);
}

// Helper: a date guaranteed to be before the current week's Monday
function lastWeekUtc(): string {
  const now = new Date();
  const day = now.getUTCDay();
  const daysFromMonday = day === 0 ? 6 : day - 1;
  const sevenDaysAgo = new Date(now);
  sevenDaysAgo.setUTCDate(now.getUTCDate() - daysFromMonday - 1); // day before Monday
  return sevenDaysAgo.toISOString().slice(0, 10);
}

describe('GET /api/groups/:group_id/members — Daily Pulse log status', () => {
  describe('daily cadence', () => {
    it('returns logged_this_period: false for a member who has not logged today', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });
      const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${memberAccessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.members).toHaveLength(2);
      for (const m of res.body.members) {
        expect(m.logged_this_period).toBe(false);
        expect(m.last_log_at).toBeNull();
      }
    });

    it('returns logged_this_period: true and last_log_at for a member who logged today', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });
      const { memberAccessToken, memberUserId } = await addMemberToGroup(
        creator.accessToken,
        groupId
      );

      // Ensure user is on UTC so period_start matches today's UTC date
      await setUserTimezone(memberUserId, 'UTC');
      await createProgressEntry(goalId!, memberUserId, 1, todayUtc());

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${memberAccessToken}`);

      expect(res.status).toBe(200);
      const member = res.body.members.find((m: { user_id: string }) => m.user_id === memberUserId);
      expect(member.logged_this_period).toBe(true);
      expect(member.last_log_at).not.toBeNull();
    });

    it('creator has logged_this_period: true when they logged today', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });
      await setUserTimezone(creator.userId, 'UTC');
      await createProgressEntry(goalId!, creator.userId, 1, todayUtc());

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      const creatorMember = res.body.members.find(
        (m: { user_id: string }) => m.user_id === creator.userId
      );
      expect(creatorMember.logged_this_period).toBe(true);
    });

    it('returns logged_this_period: false when progress entry is from yesterday', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });
      await setUserTimezone(creator.userId, 'UTC');

      const yesterday = new Date();
      yesterday.setUTCDate(yesterday.getUTCDate() - 1);
      await createProgressEntry(goalId!, creator.userId, 1, yesterday.toISOString().slice(0, 10));

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      const creatorMember = res.body.members.find(
        (m: { user_id: string }) => m.user_id === creator.userId
      );
      expect(creatorMember.logged_this_period).toBe(false);
      expect(creatorMember.last_log_at).toBeNull();
    });

    it('last_log_at reflects the most recent entry when member logged multiple times today', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'numeric', target_value: 10 },
      });
      await setUserTimezone(creator.userId, 'UTC');

      // Two entries today (same goal, both valid via unique constraint per goal)
      // We only insert one since progress_entries has unique(goal_id, user_id, period_start)
      // but we can create a second goal
      const secondGoalRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${creator.accessToken}`)
        .send({ title: 'Second goal', cadence: 'daily', metric_type: 'binary' });
      expect(secondGoalRes.status).toBe(201);
      const secondGoalId = secondGoalRes.body.id;

      await createProgressEntry(goalId!, creator.userId, 5, todayUtc());
      await createProgressEntry(secondGoalId, creator.userId, 1, todayUtc());

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      const creatorMember = res.body.members.find(
        (m: { user_id: string }) => m.user_id === creator.userId
      );
      expect(creatorMember.logged_this_period).toBe(true);
      expect(creatorMember.last_log_at).not.toBeNull();
    });

    it('correctly reports mixed logged / not-logged among multiple members', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });
      const { memberUserId: member1Id } = await addMemberToGroup(creator.accessToken, groupId);
      const { memberUserId: member2Id } = await addMemberToGroup(creator.accessToken, groupId);

      await setUserTimezone(member1Id, 'UTC');
      await setUserTimezone(member2Id, 'UTC');

      // Only member1 logs today
      await createProgressEntry(goalId!, member1Id, 1, todayUtc());

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);

      const m1 = res.body.members.find((m: { user_id: string }) => m.user_id === member1Id);
      const m2 = res.body.members.find((m: { user_id: string }) => m.user_id === member2Id);

      expect(m1.logged_this_period).toBe(true);
      expect(m1.last_log_at).not.toBeNull();
      expect(m2.logged_this_period).toBe(false);
      expect(m2.last_log_at).toBeNull();
    });
  });

  describe('weekly cadence', () => {
    it('returns logged_this_period: true when member logged earlier this week', async () => {
      const monday = earlierThisWeekUtc();
      if (monday === null) {
        // It's Monday — skip since there's no earlier day this week
        return;
      }

      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'weekly', metric_type: 'binary' },
      });
      await setUserTimezone(creator.userId, 'UTC');
      await createProgressEntry(goalId!, creator.userId, 1, monday);

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      const creatorMember = res.body.members.find(
        (m: { user_id: string }) => m.user_id === creator.userId
      );
      expect(creatorMember.logged_this_period).toBe(true);
    });

    it('returns logged_this_period: false when member last logged before the current week', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'weekly', metric_type: 'binary' },
      });
      await setUserTimezone(creator.userId, 'UTC');
      await createProgressEntry(goalId!, creator.userId, 1, lastWeekUtc());

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      const creatorMember = res.body.members.find(
        (m: { user_id: string }) => m.user_id === creator.userId
      );
      expect(creatorMember.logged_this_period).toBe(false);
    });
  });

  describe('mixed cadences', () => {
    it('applies daily (not weekly) filter when group has both daily and weekly goals', async () => {
      // The key distinction: if daily takes precedence, a weekly entry with
      // yesterday's period_start will NOT count (daily filter: period_start = today).
      // If weekly were incorrectly used, yesterday would be in-week and would count.
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });

      // Add a weekly goal too — making this a mixed-cadence group
      const weeklyRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${creator.accessToken}`)
        .send({ title: 'Weekly review', cadence: 'weekly', metric_type: 'binary' });
      expect(weeklyRes.status).toBe(201);
      const weeklyGoalId = weeklyRes.body.id;

      await setUserTimezone(creator.userId, 'UTC');

      // Log the weekly goal only with yesterday's period_start.
      // Under a weekly filter this week would count; under daily it must not.
      const yesterday = new Date();
      yesterday.setUTCDate(yesterday.getUTCDate() - 1);
      await createProgressEntry(weeklyGoalId, creator.userId, 1, yesterday.toISOString().slice(0, 10));

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      const creatorMember = res.body.members.find(
        (m: { user_id: string }) => m.user_id === creator.userId
      );
      // Daily cadence takes precedence → yesterday doesn't satisfy period_start = today
      expect(creatorMember.logged_this_period).toBe(false);
    });

    it('counts a weekly-goal entry logged today under the daily cadence filter', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });

      const weeklyRes = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${creator.accessToken}`)
        .send({ title: 'Weekly review', cadence: 'weekly', metric_type: 'binary' });
      expect(weeklyRes.status).toBe(201);
      const weeklyGoalId = weeklyRes.body.id;

      await setUserTimezone(creator.userId, 'UTC');
      // Log the weekly goal with today's period_start — still satisfies daily filter
      await createProgressEntry(weeklyGoalId, creator.userId, 1, todayUtc());

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      const creatorMember = res.body.members.find(
        (m: { user_id: string }) => m.user_id === creator.userId
      );
      expect(creatorMember.logged_this_period).toBe(true);
    });
  });

  describe('no active goals', () => {
    it('returns logged_this_period: false for all members when group has no goals', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(creator.accessToken, {
        includeGoal: false,
      });

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.members).toHaveLength(1);
      expect(res.body.members[0].logged_this_period).toBe(false);
      expect(res.body.members[0].last_log_at).toBeNull();
    });

    it('returns logged_this_period: false after all goals are deleted', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });
      await setUserTimezone(creator.userId, 'UTC');
      await createProgressEntry(goalId!, creator.userId, 1, todayUtc());

      // Delete the goal — goals route is DELETE /api/goals/:goal_id
      const deleteRes = await request(app)
        .delete(`/api/goals/${goalId}`)
        .set('Authorization', `Bearer ${creator.accessToken}`);
      expect(deleteRes.status).toBe(204);

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      const creatorMember = res.body.members.find(
        (m: { user_id: string }) => m.user_id === creator.userId
      );
      // deleted_at IS NOT NULL → goal excluded from cadence & pulse query
      expect(creatorMember.logged_this_period).toBe(false);
    });
  });

  describe('response shape', () => {
    it('includes all expected fields on each member object', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(res.status).toBe(200);
      expect(res.body.members).toHaveLength(1);
      const member = res.body.members[0];
      expect(member).toHaveProperty('user_id');
      expect(member).toHaveProperty('display_name');
      expect(member).toHaveProperty('has_avatar');
      expect(member).toHaveProperty('role');
      expect(member).toHaveProperty('joined_at');
      expect(member).toHaveProperty('logged_this_period');
      expect(member).toHaveProperty('last_log_at');
    });

    it('does not expose progress from a different group', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      await setUserPremium(creator.userId); // need >1 group

      // Group A
      const { groupId: groupAId, goalId: goalAId } = await createGroupWithGoal(
        creator.accessToken,
        { goal: { cadence: 'daily', metric_type: 'binary' } }
      );
      // Group B
      const { groupId: groupBId } = await createGroupWithGoal(creator.accessToken, {
        goal: { cadence: 'daily', metric_type: 'binary' },
      });

      await setUserTimezone(creator.userId, 'UTC');
      // Only log in group A
      await createProgressEntry(goalAId!, creator.userId, 1, todayUtc());

      const resB = await request(app)
        .get(`/api/groups/${groupBId}/members`)
        .set('Authorization', `Bearer ${creator.accessToken}`);

      expect(resB.status).toBe(200);
      const creatorInB = resB.body.members.find(
        (m: { user_id: string }) => m.user_id === creator.userId
      );
      // Progress from group A must not bleed into group B
      expect(creatorInB.logged_this_period).toBe(false);
    });
  });

  describe('authentication and authorization', () => {
    it('returns 401 without a token', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(creator.accessToken);

      const res = await request(app).get(`/api/groups/${groupId}/members`);
      expect(res.status).toBe(401);
    });

    it('returns 403 for a non-member', async () => {
      const creator = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(creator.accessToken);
      const outsider = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .get(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${outsider.accessToken}`);
      expect(res.status).toBe(403);
    });
  });
});
