/**
 * Tests for the heat history endpoint
 */
import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  setUserPremium,
  randomEmail,
} from '../../helpers';

describe('GET /api/groups/:group_id/heat/history', () => {
  it('should require authentication', async () => {
    const response = await request(app)
      .get('/api/groups/some-group-id/heat/history');

    expect(response.status).toBe(401);
  });

  it('should return 404 for non-existent group', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .get('/api/groups/00000000-0000-0000-0000-000000000000/heat/history')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
  });

  it('should require group membership', async () => {
    // Create a group with one user
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: false,
      groupName: 'Private Group',
    });

    // Try to access with a different user
    const otherUser = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Other');

    const response = await request(app)
      .get(`/api/groups/${groupId}/heat/history`)
      .set('Authorization', `Bearer ${otherUser.accessToken}`);

    expect(response.status).toBe(403);
  });

  it('should return only current heat for free users', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'Free User Group',
    });

    // Set up some GCR history
    for (let i = 1; i <= 5; i++) {
      const date = new Date();
      date.setUTCDate(date.getUTCDate() - i);
      const dateStr = date.toISOString().slice(0, 10);

      await testDb
        .insertInto('group_daily_gcr')
        .values({
          group_id: groupId,
          date: dateStr,
          total_possible: 1,
          total_completed: 1,
          gcr: 0.8,
          member_count: 1,
          goal_count: 1,
        })
        .execute();
    }

    const response = await request(app)
      .get(`/api/groups/${groupId}/heat/history`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.group_id).toBe(groupId);
    expect(response.body.current).toBeDefined();
    expect(response.body.current.score).toBeDefined();
    expect(response.body.current.tier).toBeDefined();
    expect(response.body.current.tier_name).toBeDefined();
    expect(response.body.history).toBeNull();
    expect(response.body.stats).toBeNull();
    expect(response.body.premium_required).toBe(true);
  });

  it('should return full history and stats for premium users', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'Premium User Group',
    });

    // Set user to premium
    await setUserPremium(creator.userId);

    // Set up some GCR history
    for (let i = 1; i <= 10; i++) {
      const date = new Date();
      date.setUTCDate(date.getUTCDate() - i);
      const dateStr = date.toISOString().slice(0, 10);

      await testDb
        .insertInto('group_daily_gcr')
        .values({
          group_id: groupId,
          date: dateStr,
          total_possible: 1,
          total_completed: 1,
          gcr: 0.7 + Math.random() * 0.3,
          member_count: 1,
          goal_count: 1,
        })
        .execute();
    }

    // Set heat data with a peak
    await testDb
      .updateTable('group_heat')
      .set({
        heat_score: 65,
        heat_tier: 5,
        peak_score: 85,
        peak_date: '2026-02-10',
      })
      .where('group_id', '=', groupId)
      .execute();

    const response = await request(app)
      .get(`/api/groups/${groupId}/heat/history`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.group_id).toBe(groupId);
    expect(response.body.current).toBeDefined();
    expect(response.body.current.score).toBe(65);
    expect(response.body.current.tier).toBe(5);
    expect(response.body.current.tier_name).toBe('Blaze');
    expect(response.body.history).toBeDefined();
    expect(Array.isArray(response.body.history)).toBe(true);
    expect(response.body.history.length).toBeLessThanOrEqual(30);
    expect(response.body.stats).toBeDefined();
    expect(response.body.stats.peak_score).toBe(85);
    expect(response.body.stats.peak_date).toBe('2026-02-10');
    expect(response.body.premium_required).toBe(false);
  });

  it('should respect days query parameter', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'Days Param Group',
    });

    await setUserPremium(creator.userId);

    // Set up 20 days of history
    for (let i = 1; i <= 20; i++) {
      const date = new Date();
      date.setUTCDate(date.getUTCDate() - i);
      const dateStr = date.toISOString().slice(0, 10);

      await testDb
        .insertInto('group_daily_gcr')
        .values({
          group_id: groupId,
          date: dateStr,
          total_possible: 1,
          total_completed: 1,
          gcr: 0.8,
          member_count: 1,
          goal_count: 1,
        })
        .execute();
    }

    // Request only 7 days
    const response = await request(app)
      .get(`/api/groups/${groupId}/heat/history`)
      .query({ days: 7 })
      .set('Authorization', `Bearer ${creator.accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.history.length).toBeLessThanOrEqual(7);
  });

  it('should validate days parameter (1-90)', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'Validation Group',
    });

    await setUserPremium(creator.userId);

    // Test invalid days value
    const response = await request(app)
      .get(`/api/groups/${groupId}/heat/history`)
      .query({ days: 100 })
      .set('Authorization', `Bearer ${creator.accessToken}`);

    expect(response.status).toBe(400);
  });

  it('should return history items with correct structure', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'Structure Test Group',
    });

    await setUserPremium(creator.userId);

    // Set up history
    const yesterday = new Date();
    yesterday.setUTCDate(yesterday.getUTCDate() - 1);
    const yesterdayStr = yesterday.toISOString().slice(0, 10);

    await testDb
      .insertInto('group_daily_gcr')
      .values({
        group_id: groupId,
        date: yesterdayStr,
        total_possible: 4,
        total_completed: 3,
        gcr: 0.75,
        member_count: 2,
        goal_count: 2,
      })
      .execute();

    const response = await request(app)
      .get(`/api/groups/${groupId}/heat/history`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    expect(response.status).toBe(200);

    if (response.body.history && response.body.history.length > 0) {
      const historyItem = response.body.history[0];
      expect(historyItem.date).toBeDefined();
      expect(historyItem.score).toBeDefined();
      expect(historyItem.tier).toBeDefined();
      expect(historyItem.gcr).toBeDefined();
    }
  });

  it('should allow any group member to access heat history', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, {
      includeGoal: true,
      groupName: 'Member Access Group',
    });

    // Add a member
    const { memberAccessToken, memberUserId } = await addMemberToGroup(creator.accessToken, groupId);

    // Member should be able to access
    const response = await request(app)
      .get(`/api/groups/${groupId}/heat/history`)
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.current).toBeDefined();
  });
});
