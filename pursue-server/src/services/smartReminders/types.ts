/**
 * Smart Reminders Service - Type Definitions
 */

// Reminder tier levels
export type ReminderTier = 'gentle' | 'supportive' | 'last_chance';

// User reminder preference modes
export type ReminderMode = 'smart' | 'fixed' | 'disabled';

// User aggressiveness levels
export type AggressivenessLevel = 'gentle' | 'balanced' | 'persistent';

/**
 * Logging pattern for a user-goal combination
 */
export interface LoggingPattern {
  userId: string;
  goalId: string;
  dayOfWeek: number; // -1 for general pattern, 0-6 for day-specific
  typicalHourStart: number; // 0-23 (user's local time)
  typicalHourEnd: number; // 0-23 (user's local time)
  confidenceScore: number; // 0.0 - 1.0
  sampleSize: number;
  lastCalculatedAt: Date;
}

/**
 * User reminder preferences for a specific goal
 */
export interface UserPreferences {
  enabled: boolean;
  mode: ReminderMode;
  fixedHour: number | null;
  aggressiveness: AggressivenessLevel;
  quietHoursStart: number | null;
  quietHoursEnd: number | null;
}

/**
 * Reminder history entry
 */
export interface ReminderHistoryEntry {
  id: string;
  userId: string;
  goalId: string;
  reminderTier: ReminderTier;
  sentAt: Date;
  sentAtLocalDate: string; // YYYY-MM-DD
  wasEffective: boolean | null;
  userTimezone: string;
}

/**
 * Decision result from reminder engine
 */
export interface ReminderDecision {
  shouldSend: boolean;
  tier: ReminderTier | null;
  reason: string;
}

/**
 * Social context for motivational messaging
 */
export interface SocialContext {
  groupId: string;
  groupName: string;
  totalMembers: number;
  loggedToday: number;
  percentComplete: number;
  userStreak: number;
  topPerformer?: {
    name: string;
    currentStreak: number;
  };
  groupStreak?: number; // Days everyone has logged
}

/**
 * Candidate goal for reminder processing
 */
export interface ReminderCandidate {
  userId: string;
  goalId: string;
  groupId: string;
  userTimezone: string;
  goalTitle: string;
}

/**
 * Result of processing reminders
 */
export interface ProcessResult {
  sent: number;
  skipped: number;
  errors: number;
}

/**
 * Result of pattern recalculation
 */
export interface PatternRecalculationResult {
  updated: number;
  created: number;
  removed: number;
}

/**
 * Effectiveness data for adaptive suppression
 */
export interface EffectivenessData {
  consecutiveIneffectiveDays: number;
}

/**
 * Notification template for FCM
 */
export interface NotificationTemplate {
  title: string;
  body: string;
  data: Record<string, string>;
}

/**
 * Configuration constants
 */
export const REMINDER_CONFIG = {
  // Maximum reminders per user per day across all goals
  MAX_REMINDERS_PER_USER_PER_DAY: 6,

  // Days after which to start suppression
  SUPPRESSION_THRESHOLD_DAYS: 7,

  // Days after which to fully suppress
  FULL_SUPPRESSION_DAYS: 15,

  // Minimum logs required to calculate pattern
  MIN_LOGS_FOR_PATTERN: 5,

  // Days of history to consider for pattern calculation
  PATTERN_HISTORY_DAYS: 30,

  // Minimum confidence score to use pattern
  MIN_CONFIDENCE_SCORE: 0.3,

  // Default reminder times (in minutes from midnight)
  DEFAULT_GENTLE_TIME: 12 * 60, // Noon
  DEFAULT_SUPPORTIVE_TIME: 17 * 60, // 5 PM
  DEFAULT_LAST_CHANCE_TIME: 21 * 60, // 9 PM
  DEFAULT_CUTOFF_TIME: 23 * 60, // 11 PM (don't send after this)

  // Grace period after pattern window (in minutes)
  GRACE_PERIOD_MINUTES: 30,

  // Hours between gentle and supportive (normal)
  SUPPORTIVE_DELAY_HOURS: 2,

  // Hours between gentle and supportive (persistent)
  SUPPORTIVE_DELAY_HOURS_PERSISTENT: 1,
} as const;
