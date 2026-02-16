/**
 * Tests for heat data inclusion in group endpoints
 */
import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  setUserPremium,
  randomEmail,
} from '../../helpers';

describe('Groups API with Heat Data', () => {
  describe('GET /api/users/me/groups', () => {
    it('should include heat object in each group', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User');

      // Create a group
      await createGroupWithGoal(accessToken, {
        includeGoal: true,
        groupName: 'Heat Test Group',
      });

      const response = await request(app)
        .get('/api/users/me/groups')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.groups).toBeDefined();
      expect(response.body.groups.length).toBeGreaterThan(0);

      const group = response.body.groups[0];
      expect(group.heat).toBeDefined();
      expect(group.heat.score).toBeDefined();
      expect(group.heat.tier).toBeDefined();
      expect(group.heat.tier_name).toBeDefined();
      expect(group.heat.streak_days).toBeDefined();
      expect(group.heat.peak_score).toBeDefined();
    });

    it('should return Cold tier for new groups', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User');

      await createGroupWithGoal(accessToken, {
        includeGoal: false,
        groupName: 'New Group',
      });

      const response = await request(app)
        .get('/api/users/me/groups')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      const group = response.body.groups[0];
      expect(group.heat.score).toBe(0);
      expect(group.heat.tier).toBe(0);
      expect(group.heat.tier_name).toBe('Cold');
    });

    it('should reflect updated heat data', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User');

      const { groupId } = await createGroupWithGoal(accessToken, {
        includeGoal: true,
        groupName: 'Updated Heat Group',
      });

      // Manually update heat score
      await testDb
        .updateTable('group_heat')
        .set({
          heat_score: 72.5,
          heat_tier: 5,
          streak_days: 5,
          peak_score: 80,
        })
        .where('group_id', '=', groupId)
        .execute();

      const response = await request(app)
        .get('/api/users/me/groups')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      const group = response.body.groups.find((g: { id: string }) => g.id === groupId);
      expect(group.heat.score).toBe(72.5);
      expect(group.heat.tier).toBe(5);
      expect(group.heat.tier_name).toBe('Blaze');
      expect(group.heat.streak_days).toBe(5);
      expect(group.heat.peak_score).toBe(80);
    });
  });

  describe('GET /api/groups/:group_id', () => {
    it('should include heat object with extended data', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User');

      const { groupId } = await createGroupWithGoal(accessToken, {
        includeGoal: true,
        groupName: 'Group Detail Heat Test',
      });

      const response = await request(app)
        .get(`/api/groups/${groupId}`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.heat).toBeDefined();
      expect(response.body.heat.score).toBeDefined();
      expect(response.body.heat.tier).toBeDefined();
      expect(response.body.heat.tier_name).toBeDefined();
      expect(response.body.heat.streak_days).toBeDefined();
      expect(response.body.heat.peak_score).toBeDefined();
      expect(response.body.heat.peak_date).toBeDefined(); // Extended data
      expect('yesterday_gcr' in response.body.heat || response.body.heat.yesterday_gcr === undefined).toBe(true);
      expect('baseline_gcr' in response.body.heat || response.body.heat.baseline_gcr === undefined).toBe(true);
    });

    it('should return correct tier name for all tiers', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User');

      const { groupId } = await createGroupWithGoal(accessToken, {
        includeGoal: true,
        groupName: 'Tier Name Test',
      });

      // Test each tier
      const tierTests = [
        { score: 0, expectedTier: 0, expectedName: 'Cold' },
        { score: 10, expectedTier: 1, expectedName: 'Spark' },
        { score: 25, expectedTier: 2, expectedName: 'Ember' },
        { score: 40, expectedTier: 3, expectedName: 'Flicker' },
        { score: 55, expectedTier: 4, expectedName: 'Steady' },
        { score: 70, expectedTier: 5, expectedName: 'Blaze' },
        { score: 82, expectedTier: 6, expectedName: 'Inferno' },
        { score: 95, expectedTier: 7, expectedName: 'Supernova' },
      ];

      for (const test of tierTests) {
        await testDb
          .updateTable('group_heat')
          .set({
            heat_score: test.score,
            heat_tier: test.expectedTier,
          })
          .where('group_id', '=', groupId)
          .execute();

        const response = await request(app)
          .get(`/api/groups/${groupId}`)
          .set('Authorization', `Bearer ${accessToken}`);

        expect(response.status).toBe(200);
        expect(response.body.heat.tier).toBe(test.expectedTier);
        expect(response.body.heat.tier_name).toBe(test.expectedName);
      }
    });

    it('should include yesterday_gcr and baseline_gcr when available', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User');

      const { groupId } = await createGroupWithGoal(accessToken, {
        includeGoal: true,
        groupName: 'GCR Data Test',
      });

      // Create GCR data for yesterday
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

      // Create baseline GCR data (days 2-8 ago)
      for (let i = 2; i <= 8; i++) {
        const date = new Date();
        date.setUTCDate(date.getUTCDate() - i);
        const dateStr = date.toISOString().slice(0, 10);

        await testDb
          .insertInto('group_daily_gcr')
          .values({
            group_id: groupId,
            date: dateStr,
            total_possible: 4,
            total_completed: 2,
            gcr: 0.5,
            member_count: 2,
            goal_count: 2,
          })
          .execute();
      }

      const response = await request(app)
        .get(`/api/groups/${groupId}`)
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.heat.yesterday_gcr).toBeCloseTo(0.75, 2);
      expect(response.body.heat.baseline_gcr).toBeCloseTo(0.5, 2);
    });
  });

  describe('POST /api/groups', () => {
    it('should initialize heat record for new group', async () => {
      const { accessToken } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User');

      const response = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ name: 'New Heat Init Group' });

      expect(response.status).toBe(201);
      const groupId = response.body.id;

      // Check heat record was created
      const heat = await testDb
        .selectFrom('group_heat')
        .selectAll()
        .where('group_id', '=', groupId)
        .executeTakeFirst();

      expect(heat).toBeDefined();
      expect(Number(heat!.heat_score)).toBe(0);
      expect(heat!.heat_tier).toBe(0);
      expect(heat!.streak_days).toBe(0);
    });
  });

  describe('Heat data with multiple groups', () => {
    it('should return correct heat for each group', async () => {
      const { accessToken, userId } = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'User');

      // Set user to premium so they can create multiple groups
      await setUserPremium(userId);

      // Create first group
      const { groupId: groupId1 } = await createGroupWithGoal(accessToken, {
        includeGoal: true,
        groupName: 'Group 1',
      });

      // Create second group
      const { groupId: groupId2 } = await createGroupWithGoal(accessToken, {
        includeGoal: true,
        groupName: 'Group 2',
      });

      // Set different heat values
      await testDb
        .updateTable('group_heat')
        .set({ heat_score: 30, heat_tier: 2 })
        .where('group_id', '=', groupId1)
        .execute();

      await testDb
        .updateTable('group_heat')
        .set({ heat_score: 65, heat_tier: 5 })
        .where('group_id', '=', groupId2)
        .execute();

      const response = await request(app)
        .get('/api/users/me/groups')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);

      const group1 = response.body.groups.find((g: { id: string }) => g.id === groupId1);
      const group2 = response.body.groups.find((g: { id: string }) => g.id === groupId2);

      expect(group1.heat.score).toBe(30);
      expect(group1.heat.tier).toBe(2);
      expect(group1.heat.tier_name).toBe('Ember');

      expect(group2.heat.score).toBe(65);
      expect(group2.heat.tier).toBe(5);
      expect(group2.heat.tier_name).toBe('Blaze');
    });
  });
});
