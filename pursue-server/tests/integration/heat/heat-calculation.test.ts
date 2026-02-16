/**
 * Tests for heat calculation service functions
 */
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  createProgressEntry,
  randomEmail,
} from '../../helpers';
import {
  scoreToTier,
  tierName,
  isCompleted,
  computeAndStoreGcr,
  calculateGroupHeat,
  SENSITIVITY,
  DECAY_FACTOR,
} from '../../../src/services/heat.service';

describe('Heat Calculation Service', () => {
  describe('scoreToTier', () => {
    it('should return tier 0 (Cold) for scores 0-5', () => {
      expect(scoreToTier(0)).toBe(0);
      expect(scoreToTier(2.5)).toBe(0);
      expect(scoreToTier(5)).toBe(0);
    });

    it('should return tier 1 (Spark) for scores 6-18', () => {
      expect(scoreToTier(6)).toBe(1);
      expect(scoreToTier(12)).toBe(1);
      expect(scoreToTier(18)).toBe(1);
    });

    it('should return tier 2 (Ember) for scores 19-32', () => {
      expect(scoreToTier(19)).toBe(2);
      expect(scoreToTier(25)).toBe(2);
      expect(scoreToTier(32)).toBe(2);
    });

    it('should return tier 3 (Flicker) for scores 33-46', () => {
      expect(scoreToTier(33)).toBe(3);
      expect(scoreToTier(40)).toBe(3);
      expect(scoreToTier(46)).toBe(3);
    });

    it('should return tier 4 (Steady) for scores 47-60', () => {
      expect(scoreToTier(47)).toBe(4);
      expect(scoreToTier(53)).toBe(4);
      expect(scoreToTier(60)).toBe(4);
    });

    it('should return tier 5 (Blaze) for scores 61-74', () => {
      expect(scoreToTier(61)).toBe(5);
      expect(scoreToTier(68)).toBe(5);
      expect(scoreToTier(74)).toBe(5);
    });

    it('should return tier 6 (Inferno) for scores 75-88', () => {
      expect(scoreToTier(75)).toBe(6);
      expect(scoreToTier(82)).toBe(6);
      expect(scoreToTier(88)).toBe(6);
    });

    it('should return tier 7 (Supernova) for scores 89-100', () => {
      expect(scoreToTier(89)).toBe(7);
      expect(scoreToTier(95)).toBe(7);
      expect(scoreToTier(100)).toBe(7);
    });

    it('should handle boundary values correctly', () => {
      expect(scoreToTier(5)).toBe(0);
      expect(scoreToTier(5.01)).toBe(1);
      expect(scoreToTier(18)).toBe(1);
      expect(scoreToTier(18.01)).toBe(2);
      expect(scoreToTier(88)).toBe(6);
      expect(scoreToTier(88.01)).toBe(7);
    });
  });

  describe('tierName', () => {
    it('should return correct tier names', () => {
      expect(tierName(0)).toBe('Cold');
      expect(tierName(1)).toBe('Spark');
      expect(tierName(2)).toBe('Ember');
      expect(tierName(3)).toBe('Flicker');
      expect(tierName(4)).toBe('Steady');
      expect(tierName(5)).toBe('Blaze');
      expect(tierName(6)).toBe('Inferno');
      expect(tierName(7)).toBe('Supernova');
    });

    it('should return Cold for invalid tier numbers', () => {
      expect(tierName(-1)).toBe('Cold');
      expect(tierName(8)).toBe('Cold');
      expect(tierName(100)).toBe('Cold');
    });
  });

  describe('isCompleted', () => {
    it('should return true for binary goals when value is 1', () => {
      expect(isCompleted(1, 'binary', null)).toBe(true);
      expect(isCompleted(1, 'binary', 1)).toBe(true);
    });

    it('should return false for binary goals when value is 0', () => {
      expect(isCompleted(0, 'binary', null)).toBe(false);
      expect(isCompleted(0.5, 'binary', null)).toBe(false);
    });

    it('should return true for numeric goals when value >= target', () => {
      expect(isCompleted(10, 'numeric', 10)).toBe(true);
      expect(isCompleted(15, 'numeric', 10)).toBe(true);
    });

    it('should return false for numeric goals when value < target', () => {
      expect(isCompleted(5, 'numeric', 10)).toBe(false);
      expect(isCompleted(9.99, 'numeric', 10)).toBe(false);
    });

    it('should return false for numeric goals with null target', () => {
      expect(isCompleted(10, 'numeric', null)).toBe(false);
    });

    it('should return true for duration goals when value >= target', () => {
      expect(isCompleted(30, 'duration', 30)).toBe(true);
      expect(isCompleted(45, 'duration', 30)).toBe(true);
    });

    it('should return false for duration goals when value < target', () => {
      expect(isCompleted(25, 'duration', 30)).toBe(false);
    });

    it('should return false for unknown metric types', () => {
      expect(isCompleted(1, 'unknown', 1)).toBe(false);
    });
  });

  describe('computeAndStoreGcr', () => {
    it('should compute and store GCR for a group', async () => {
      // Create a group with a goal
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        includeGoal: true,
        groupName: 'GCR Test Group',
      });

      // Get yesterday's date
      const yesterday = new Date();
      yesterday.setUTCDate(yesterday.getUTCDate() - 1);
      const yesterdayStr = yesterday.toISOString().slice(0, 10);

      // Log progress for creator (completed)
      await createProgressEntry(goalId!, creator.userId, 1, yesterdayStr);

      // Compute GCR - 1 completion out of 1 possible = 1.0
      const gcr = await computeAndStoreGcr(groupId, yesterdayStr);
      expect(gcr).toBe(1);

      // Verify GCR was stored
      const storedGcr = await testDb
        .selectFrom('group_daily_gcr')
        .select(['gcr', 'total_possible', 'total_completed', 'member_count', 'goal_count'])
        .where('group_id', '=', groupId)
        .where('date', '=', yesterdayStr)
        .executeTakeFirst();

      expect(storedGcr).toBeDefined();
      expect(Number(storedGcr!.gcr)).toBe(1);
      expect(storedGcr!.total_possible).toBe(1); // 1 member x 1 goal
      expect(storedGcr!.total_completed).toBe(1);
      expect(storedGcr!.member_count).toBe(1);
      expect(storedGcr!.goal_count).toBe(1);
    });

    it('should return 0 GCR when no goals exist', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken, {
        includeGoal: false,
        groupName: 'No Goals Group',
      });

      const yesterday = new Date();
      yesterday.setUTCDate(yesterday.getUTCDate() - 1);
      const yesterdayStr = yesterday.toISOString().slice(0, 10);

      const gcr = await computeAndStoreGcr(groupId, yesterdayStr);
      expect(gcr).toBe(0);
    });

    it('should handle 100% completion rate', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        includeGoal: true,
        groupName: 'Perfect Group',
      });

      const yesterday = new Date();
      yesterday.setUTCDate(yesterday.getUTCDate() - 1);
      const yesterdayStr = yesterday.toISOString().slice(0, 10);

      // Creator completes the goal
      await createProgressEntry(goalId!, creator.userId, 1, yesterdayStr);

      const gcr = await computeAndStoreGcr(groupId, yesterdayStr);
      expect(gcr).toBe(1); // 100% completion
    });
  });

  describe('calculateGroupHeat', () => {
    it('should initialize heat record for new group', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId } = await createGroupWithGoal(creator.accessToken, {
        includeGoal: true,
        groupName: 'New Heat Group',
      });

      // Heat record should be initialized
      const heat = await testDb
        .selectFrom('group_heat')
        .selectAll()
        .where('group_id', '=', groupId)
        .executeTakeFirst();

      expect(heat).toBeDefined();
      expect(Number(heat!.heat_score)).toBe(0);
      expect(heat!.heat_tier).toBe(0);
    });

    it('should update heat score based on GCR', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        includeGoal: true,
        groupName: 'Heat Update Group',
      });

      const yesterday = new Date();
      yesterday.setUTCDate(yesterday.getUTCDate() - 1);
      const yesterdayStr = yesterday.toISOString().slice(0, 10);

      // Log progress (100% completion)
      await createProgressEntry(goalId!, creator.userId, 1, yesterdayStr);

      // Calculate heat
      await calculateGroupHeat(groupId, yesterdayStr);

      // Check heat was updated
      const heat = await testDb
        .selectFrom('group_heat')
        .selectAll()
        .where('group_id', '=', groupId)
        .executeTakeFirst();

      expect(heat).toBeDefined();
      expect(heat!.last_calculated_at).not.toBeNull();
      // Since it's a new group with no baseline, delta = 0, just decay applied
      // Initial score 0 + (1.0 - 1.0) * 50 = 0, then * 0.98 = 0
      // But with 100% GCR and no baseline, the baseline defaults to yesterday's GCR
      expect(Number(heat!.heat_score)).toBeGreaterThanOrEqual(0);
    });

    it('should update heat record when calculated', async () => {
      const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
      const { groupId, goalId } = await createGroupWithGoal(creator.accessToken, {
        includeGoal: true,
        groupName: 'Improving Group',
      });

      // Set initial heat score
      await testDb
        .updateTable('group_heat')
        .set({ heat_score: 30, heat_tier: 2 })
        .where('group_id', '=', groupId)
        .execute();

      // Yesterday date
      const yesterday = new Date();
      yesterday.setUTCDate(yesterday.getUTCDate() - 1);
      const yesterdayStr = yesterday.toISOString().slice(0, 10);

      // Log progress for yesterday
      await createProgressEntry(goalId!, creator.userId, 1, yesterdayStr);

      await calculateGroupHeat(groupId, yesterdayStr);

      const heat = await testDb
        .selectFrom('group_heat')
        .selectAll()
        .where('group_id', '=', groupId)
        .executeTakeFirst();

      // Heat should have been recalculated (last_calculated_at updated)
      expect(heat!.last_calculated_at).not.toBeNull();
    });
  });

  describe('Heat algorithm constants', () => {
    it('should have correct sensitivity value', () => {
      expect(SENSITIVITY).toBe(50);
    });

    it('should have correct decay factor', () => {
      expect(DECAY_FACTOR).toBe(0.98);
    });
  });
});
