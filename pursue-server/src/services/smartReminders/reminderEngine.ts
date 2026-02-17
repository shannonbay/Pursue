/**
 * Smart Reminders - Reminder Decision Engine
 * 
 * Decides when to send reminders based on patterns, preferences, and context.
 */

import type {
  ReminderDecision,
  LoggingPattern,
  UserPreferences,
  ReminderHistoryEntry,
  EffectivenessData,
  ReminderTier,
} from './types.js';
import { REMINDER_CONFIG } from './types.js';

/**
 * Get current local time in a timezone
 */
function getLocalTime(
  utcDate: Date,
  timezone: string
): { hour: number; minute: number } {
  try {
    const parts = utcDate
      .toLocaleString('en-US', {
        timeZone: timezone,
        hour: 'numeric',
        minute: 'numeric',
        hour12: false,
      })
      .split(':');
    return {
      hour: parseInt(parts[0], 10),
      minute: parseInt(parts[1], 10),
    };
  } catch {
    // Invalid timezone, fall back to UTC
    return {
      hour: utcDate.getUTCHours(),
      minute: utcDate.getUTCMinutes(),
    };
  }
}

/**
 * Check if current hour is within quiet hours
 */
function isQuietHours(
  currentHour: number,
  prefs: { quietHoursStart: number | null; quietHoursEnd: number | null }
): boolean {
  if (prefs.quietHoursStart === null || prefs.quietHoursEnd === null) {
    return false;
  }

  // Handle overnight quiet hours (e.g., 22:00 – 07:00)
  if (prefs.quietHoursStart > prefs.quietHoursEnd) {
    return (
      currentHour >= prefs.quietHoursStart || currentHour < prefs.quietHoursEnd
    );
  }

  return (
    currentHour >= prefs.quietHoursStart && currentHour < prefs.quietHoursEnd
  );
}

/**
 * Check if a specific tier has already been sent today
 */
function hasSentTier(
  reminders: ReminderHistoryEntry[],
  tier: ReminderTier
): boolean {
  return reminders.some((r) => r.reminderTier === tier);
}

/**
 * Evaluate reminder for fixed mode
 */
function evaluateFixedModeReminder(
  prefs: UserPreferences,
  currentHour: number,
  todaysReminders: ReminderHistoryEntry[]
): ReminderDecision {
  if (
    prefs.fixedHour !== null &&
    currentHour === prefs.fixedHour &&
    todaysReminders.length === 0
  ) {
    return {
      shouldSend: true,
      tier: 'gentle',
      reason: 'Fixed time reminder',
    };
  }

  return { shouldSend: false, tier: null, reason: 'Not fixed reminder time' };
}

/**
 * Evaluate reminder using default strategy (no pattern)
 */
function evaluateDefaultReminder(
  currentTimeInMinutes: number,
  todaysReminders: ReminderHistoryEntry[],
  prefs: UserPreferences,
  suppressToLastChanceOnly: boolean
): ReminderDecision {
  const { DEFAULT_GENTLE_TIME, DEFAULT_SUPPORTIVE_TIME, DEFAULT_LAST_CHANCE_TIME, DEFAULT_CUTOFF_TIME } = REMINDER_CONFIG;

  // Tier 1: Gentle at noon
  if (
    !suppressToLastChanceOnly &&
    currentTimeInMinutes >= DEFAULT_GENTLE_TIME &&
    currentTimeInMinutes < DEFAULT_SUPPORTIVE_TIME &&
    !hasSentTier(todaysReminders, 'gentle') &&
    prefs.aggressiveness !== 'gentle'
  ) {
    return {
      shouldSend: true,
      tier: 'gentle',
      reason: 'Default midday reminder (no pattern)',
    };
  }

  // Tier 2: Supportive at 5 PM
  if (
    !suppressToLastChanceOnly &&
    currentTimeInMinutes >= DEFAULT_SUPPORTIVE_TIME &&
    currentTimeInMinutes < DEFAULT_LAST_CHANCE_TIME &&
    !hasSentTier(todaysReminders, 'supportive') &&
    prefs.aggressiveness !== 'gentle'
  ) {
    return {
      shouldSend: true,
      tier: 'supportive',
      reason: 'Default afternoon reminder (no pattern)',
    };
  }

  // Tier 3: Last Chance at 9 PM
  if (
    currentTimeInMinutes >= DEFAULT_LAST_CHANCE_TIME &&
    currentTimeInMinutes < DEFAULT_CUTOFF_TIME &&
    !hasSentTier(todaysReminders, 'last_chance')
  ) {
    return {
      shouldSend: true,
      tier: 'last_chance',
      reason: 'Default evening reminder (no pattern)',
    };
  }

  return { shouldSend: false, tier: null, reason: 'Not default reminder time' };
}

/**
 * Evaluate reminder using smart mode (pattern-based)
 */
function evaluateSmartModeReminder(
  pattern: LoggingPattern | null,
  currentTimeInMinutes: number,
  todaysReminders: ReminderHistoryEntry[],
  prefs: UserPreferences,
  suppressToLastChanceOnly: boolean
): ReminderDecision {
  const { MIN_CONFIDENCE_SCORE, GRACE_PERIOD_MINUTES, SUPPORTIVE_DELAY_HOURS, SUPPORTIVE_DELAY_HOURS_PERSISTENT, DEFAULT_LAST_CHANCE_TIME, DEFAULT_CUTOFF_TIME } = REMINDER_CONFIG;

  // No pattern or low confidence — use default strategy
  if (!pattern || pattern.confidenceScore < MIN_CONFIDENCE_SCORE) {
    return evaluateDefaultReminder(
      currentTimeInMinutes,
      todaysReminders,
      prefs,
      suppressToLastChanceOnly
    );
  }

  // Calculate when reminders should fire (all in minutes from midnight)
  const gentleTime = pattern.typicalHourEnd * 60 + GRACE_PERIOD_MINUTES;
  
  // For 'persistent' aggressiveness, compress the schedule
  const supportiveDelay = prefs.aggressiveness === 'persistent'
    ? SUPPORTIVE_DELAY_HOURS_PERSISTENT * 60
    : SUPPORTIVE_DELAY_HOURS * 60;
  const supportiveTime = gentleTime + supportiveDelay;
  const lastChanceTime = DEFAULT_LAST_CHANCE_TIME;

  // Tier 1: Gentle
  if (
    !suppressToLastChanceOnly &&
    currentTimeInMinutes >= gentleTime &&
    currentTimeInMinutes < supportiveTime &&
    !hasSentTier(todaysReminders, 'gentle') &&
    prefs.aggressiveness !== 'gentle' // 'gentle' mode skips to last_chance only
  ) {
    return {
      shouldSend: true,
      tier: 'gentle',
      reason: 'Past typical logging window',
    };
  }

  // Tier 2: Supportive
  if (
    !suppressToLastChanceOnly &&
    currentTimeInMinutes >= supportiveTime &&
    currentTimeInMinutes < lastChanceTime &&
    !hasSentTier(todaysReminders, 'supportive') &&
    prefs.aggressiveness !== 'gentle'
  ) {
    return {
      shouldSend: true,
      tier: 'supportive',
      reason: 'Still not logged 2+ hours after window',
    };
  }

  // Tier 3: Last Chance
  if (
    currentTimeInMinutes >= lastChanceTime &&
    currentTimeInMinutes < DEFAULT_CUTOFF_TIME &&
    !hasSentTier(todaysReminders, 'last_chance')
  ) {
    return {
      shouldSend: true,
      tier: 'last_chance',
      reason: 'Last chance before day ends',
    };
  }

  return { shouldSend: false, tier: null, reason: 'Not time yet' };
}

/**
 * Main decision function: should we send a reminder?
 */
export function shouldSendReminder(
  userId: string,
  goalId: string,
  userTimezone: string,
  currentTimeUtc: Date,
  prefs: UserPreferences | null,
  pattern: LoggingPattern | null,
  todaysRemindersForGoal: ReminderHistoryEntry[],
  todaysReminderCountAllGoals: number,
  recentEffectiveness: EffectivenessData
): ReminderDecision {
  const { MAX_REMINDERS_PER_USER_PER_DAY, SUPPRESSION_THRESHOLD_DAYS, FULL_SUPPRESSION_DAYS } = REMINDER_CONFIG;

  // 1. Check global daily cap
  if (todaysReminderCountAllGoals >= MAX_REMINDERS_PER_USER_PER_DAY) {
    return { shouldSend: false, tier: null, reason: 'Global daily cap reached' };
  }

  // 2. Check if user has preferences set
  const effectivePrefs: UserPreferences = prefs ?? {
    enabled: true,
    mode: 'smart',
    aggressiveness: 'balanced',
    quietHoursStart: null,
    quietHoursEnd: null,
    fixedHour: null,
  };

  if (!effectivePrefs.enabled || effectivePrefs.mode === 'disabled') {
    return { shouldSend: false, tier: null, reason: 'User disabled reminders' };
  }

  // 3. Get current local time for user
  const localTime = getLocalTime(currentTimeUtc, userTimezone);
  const currentHour = localTime.hour;
  const currentMinutes = localTime.minute;
  const currentTimeInMinutes = currentHour * 60 + currentMinutes;

  // 4. Check quiet hours
  if (isQuietHours(currentHour, effectivePrefs)) {
    return { shouldSend: false, tier: null, reason: 'Quiet hours' };
  }

  // 5. Check adaptive suppression
  if (recentEffectiveness.consecutiveIneffectiveDays >= FULL_SUPPRESSION_DAYS) {
    return {
      shouldSend: false,
      tier: null,
      reason: `Suppressed (${FULL_SUPPRESSION_DAYS}+ days ineffective)`,
    };
  }

  const suppressToLastChanceOnly =
    recentEffectiveness.consecutiveIneffectiveDays >= SUPPRESSION_THRESHOLD_DAYS;

  // 6. Determine tier and timing based on mode
  if (effectivePrefs.mode === 'fixed') {
    return evaluateFixedModeReminder(
      effectivePrefs,
      currentHour,
      todaysRemindersForGoal
    );
  }

  // Smart mode
  return evaluateSmartModeReminder(
    pattern,
    currentTimeInMinutes,
    todaysRemindersForGoal,
    effectivePrefs,
    suppressToLastChanceOnly
  );
}

/**
 * Get default preferences (used when no preferences are stored)
 */
export function getDefaultPreferences(): UserPreferences {
  return {
    enabled: true,
    mode: 'smart',
    aggressiveness: 'balanced',
    quietHoursStart: null,
    quietHoursEnd: null,
    fixedHour: null,
  };
}
