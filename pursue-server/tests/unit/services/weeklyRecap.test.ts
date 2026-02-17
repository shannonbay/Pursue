import {
  buildHighlights,
  selectPrimaryHeadline,
  type WeeklyRecapData,
  type RecapHighlight,
} from '../../../src/services/weeklyRecap.service.js';

describe('Weekly Recap Service - Unit Tests', () => {
  describe('selectPrimaryHeadline', () => {
    const baseRecapData: WeeklyRecapData = {
      group: { id: 'group-1', name: 'Test Group', iconUrl: null },
      period: { startDate: '2026-02-10', endDate: '2026-02-16' },
      completionRate: { current: 75, previous: 70, delta: 5 },
      highlights: [],
      goalBreakdown: [],
      heat: null,
      memberCount: 3,
    };

    it('should select GCR >= 90% headline (priority 1)', () => {
      const recapData = {
        ...baseRecapData,
        completionRate: { current: 92, previous: 85, delta: 7 },
      };

      const headline = selectPrimaryHeadline(recapData);
      expect(headline).toBe('Test Group hit 92% this week! 💪');
    });

    it('should select streak milestone headline (priority 2)', () => {
      const recapData = {
        ...baseRecapData,
        completionRate: { current: 75, previous: 70, delta: 5 },
        highlights: [
          {
            type: 'streak_milestone',
            priority: 10,
            emoji: '🔥',
            text: 'Sarah — 14-day streak!',
            userId: 'user-1',
            displayName: 'Sarah',
          },
        ],
      };

      const headline = selectPrimaryHeadline(recapData);
      expect(headline).toContain('Sarah hit a 14-day streak!');
      expect(headline).toContain('75%');
    });

    it('should select heat tier increased headline (priority 3)', () => {
      const recapData = {
        ...baseRecapData,
        completionRate: { current: 75, previous: 65, delta: 10 },
        heat: {
          score: 68,
          tier: 5,
          tierName: 'Blaze',
          streakDays: 5,
          tierDelta: 1,
        },
      };

      const headline = selectPrimaryHeadline(recapData);
      expect(headline).toBe('Test Group is heating up — now at Blaze! 🔥');
    });

    it('should select improvement headline (priority 4)', () => {
      const recapData = {
        ...baseRecapData,
        completionRate: { current: 75, previous: 65, delta: 10 },
      };

      const headline = selectPrimaryHeadline(recapData);
      expect(headline).toBe('Test Group improved 10% this week — keep climbing! 📈');
    });

    it('should select default headline (priority 5)', () => {
      const recapData = {
        ...baseRecapData,
        completionRate: { current: 75, previous: 75, delta: 0 },
      };

      const headline = selectPrimaryHeadline(recapData);
      expect(headline).toBe('Test Group finished the week at 75% — here\'s your recap.');
    });

    it('should prioritize GCR >= 90% over streak milestone', () => {
      const recapData = {
        ...baseRecapData,
        completionRate: { current: 95, previous: 85, delta: 10 },
        highlights: [
          {
            type: 'streak_milestone',
            priority: 10,
            emoji: '🔥',
            text: 'Sarah — 14-day streak!',
            userId: 'user-1',
            displayName: 'Sarah',
          },
        ],
      };

      const headline = selectPrimaryHeadline(recapData);
      expect(headline).toBe('Test Group hit 95% this week! 💪');
    });
  });

  describe('buildHighlights', () => {
    it('should select streak milestones (priority 10)', () => {
      const highlights = buildHighlights(
        [],
        [
          {
            userId: 'user-1',
            displayName: 'Sarah',
            goalId: 'goal-1',
            streakDays: 14,
            milestonedThisWeek: true,
          },
        ],
        0,
        0,
        0,
        [],
        [],
        null,
        75,
        70
      );

      // Should include both streak milestone and longest streak highlights
      expect(highlights.length).toBeGreaterThanOrEqual(1);
      const streakMilestone = highlights.find(h => h.type === 'streak_milestone');
      expect(streakMilestone).toBeDefined();
      expect(streakMilestone?.priority).toBe(10);
      expect(streakMilestone?.text).toContain('14-day streak');
    });

    it('should select 100% completion members (priority 9)', () => {
      const highlights = buildHighlights(
        [
          { userId: 'user-1', displayName: 'Sarah', rate: 100 },
          { userId: 'user-2', displayName: 'John', rate: 85 },
        ],
        [],
        0,
        0,
        0,
        [],
        [],
        null,
        92,
        85
      );

      // Should include perfect_week highlight, and may include fallback if < 2 highlights
      expect(highlights.length).toBeGreaterThanOrEqual(1);
      const perfectWeek = highlights.find(h => h.type === 'perfect_week');
      expect(perfectWeek).toBeDefined();
      expect(perfectWeek?.priority).toBe(9);
      expect(perfectWeek?.text).toContain('100% completion');
    });

    it('should select longest streak (priority 8)', () => {
      const highlights = buildHighlights(
        [],
        [
          {
            userId: 'user-1',
            displayName: 'Sarah',
            goalId: 'goal-1',
            streakDays: 21,
            milestonedThisWeek: false,
          },
          {
            userId: 'user-2',
            displayName: 'John',
            goalId: 'goal-2',
            streakDays: 14,
            milestonedThisWeek: false,
          },
        ],
        0,
        0,
        0,
        [],
        [],
        null,
        75,
        70
      );

      expect(highlights.some(h => h.type === 'longest_streak')).toBe(true);
      const longestStreak = highlights.find(h => h.type === 'longest_streak');
      expect(longestStreak?.text).toContain('21-day streak (longest!)');
    });

    it('should select most improved member (priority 7)', () => {
      const highlights = buildHighlights(
        [
          { userId: 'user-1', displayName: 'Sarah', rate: 85, previousRate: 60 },
          { userId: 'user-2', displayName: 'John', rate: 75, previousRate: 70 },
        ],
        [],
        0,
        0,
        0,
        [],
        [],
        null,
        80,
        65
      );

      expect(highlights.some(h => h.type === 'most_improved')).toBe(true);
      const mostImproved = highlights.find(h => h.type === 'most_improved');
      expect(mostImproved?.text).toContain('Sarah');
      expect(mostImproved?.text).toContain('25%');
    });

    it('should include photo count if >= 3 (priority 6)', () => {
      const highlights = buildHighlights(
        [],
        [],
        5, // photoCount
        0,
        0,
        [],
        [],
        null,
        75,
        70
      );

      expect(highlights.some(h => h.type === 'photos')).toBe(true);
      const photos = highlights.find(h => h.type === 'photos');
      expect(photos?.text).toBe('5 photo check-ins this week');
    });

    it('should include reaction count if >= 5 (priority 5)', () => {
      const highlights = buildHighlights(
        [],
        [],
        0,
        8, // reactionCount
        0,
        [],
        [],
        null,
        75,
        70
      );

      expect(highlights.some(h => h.type === 'reactions')).toBe(true);
      const reactions = highlights.find(h => h.type === 'reactions');
      expect(reactions?.text).toBe('8 reactions shared');
    });

    it('should include nudge count if >= 3 (priority 4)', () => {
      const highlights = buildHighlights(
        [],
        [],
        0,
        0,
        4, // nudgeCount
        [],
        [],
        null,
        75,
        70
      );

      expect(highlights.some(h => h.type === 'nudges')).toBe(true);
      const nudges = highlights.find(h => h.type === 'nudges');
      expect(nudges?.text).toContain('4 nudges sent');
    });

    it('should include new members (priority 3)', () => {
      const highlights = buildHighlights(
        [],
        [],
        0,
        0,
        0,
        [{ userId: 'user-new', displayName: 'Alex' }],
        [],
        null,
        75,
        70
      );

      expect(highlights.some(h => h.type === 'new_member')).toBe(true);
      const newMember = highlights.find(h => h.type === 'new_member');
      expect(newMember?.text).toContain('Welcome Alex');
    });

    it('should include new goals (priority 1)', () => {
      const highlights = buildHighlights(
        [],
        [],
        0,
        0,
        0,
        [],
        [{ goalId: 'goal-new', title: 'Morning Run' }],
        null,
        75,
        70
      );

      expect(highlights.some(h => h.type === 'new_goal')).toBe(true);
      const newGoal = highlights.find(h => h.type === 'new_goal');
      expect(newGoal?.text).toContain('Morning Run');
    });

    it('should limit to max 4 highlights', () => {
      const highlights = buildHighlights(
        [
          { userId: 'user-1', displayName: 'Sarah', rate: 100 },
          { userId: 'user-2', displayName: 'John', rate: 100 },
        ],
        [
          {
            userId: 'user-3',
            displayName: 'Mike',
            goalId: 'goal-1',
            streakDays: 14,
            milestonedThisWeek: true,
          },
        ],
        5, // photos
        8, // reactions
        4, // nudges
        [{ userId: 'user-new', displayName: 'Alex' }],
        [{ goalId: 'goal-new', title: 'Morning Run' }],
        null,
        92,
        85
      );

      expect(highlights.length).toBeLessThanOrEqual(4);
    });

    it('should limit to max 2 highlights per member', () => {
      const highlights = buildHighlights(
        [{ userId: 'user-1', displayName: 'Sarah', rate: 100, previousRate: 60 }],
        [
          {
            userId: 'user-1',
            displayName: 'Sarah',
            goalId: 'goal-1',
            streakDays: 14,
            milestonedThisWeek: true,
          },
          {
            userId: 'user-1',
            displayName: 'Sarah',
            goalId: 'goal-2',
            streakDays: 21,
            milestonedThisWeek: false,
          },
        ],
        0,
        0,
        0,
        [],
        [],
        null,
        92,
        85
      );

      const sarahHighlights = highlights.filter(h => h.userId === 'user-1');
      expect(sarahHighlights.length).toBeLessThanOrEqual(2);
    });

    it('should add fallback message if fewer than 2 highlights', () => {
      const highlights = buildHighlights(
        [],
        [],
        0, // no photos
        0, // no reactions
        0, // no nudges
        [],
        [],
        null,
        75,
        70
      );

      expect(highlights.length).toBeGreaterThanOrEqual(1);
      expect(highlights.some(h => h.type === 'fallback')).toBe(true);
      const fallback = highlights.find(h => h.type === 'fallback');
      expect(fallback?.text).toBe('Keep logging — consistency compounds!');
    });

    it('should sort by priority (highest first)', () => {
      const highlights = buildHighlights(
        [{ userId: 'user-1', displayName: 'Sarah', rate: 100 }],
        [
          {
            userId: 'user-2',
            displayName: 'Mike',
            goalId: 'goal-1',
            streakDays: 14,
            milestonedThisWeek: true,
          },
        ],
        5, // photos
        8, // reactions
        0,
        [],
        [],
        null,
        92,
        85
      );

      // Streak milestone (priority 10) should come before perfect week (priority 9)
      expect(highlights[0].priority).toBeGreaterThanOrEqual(highlights[1].priority);
      expect(highlights[1].priority).toBeGreaterThanOrEqual(highlights[2]?.priority || 0);
    });
  });
});
