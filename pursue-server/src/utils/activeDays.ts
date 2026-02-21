/** Day-of-week bitmask constants (Sunday-first: 0=Sun, 1=Mon, ..., 6=Sat) */
export const DAYS = {
  SUN: 1,    // Bit 0
  MON: 2,    // Bit 1
  TUE: 4,    // Bit 2
  WED: 8,    // Bit 3
  THU: 16,   // Bit 4
  FRI: 32,   // Bit 5
  SAT: 64,   // Bit 6
} as const;

export const ALL_DAYS = 127; // 1111111
export const WEEKDAYS = 62;  // 0111110 (Mon-Fri)
export const WEEKENDS = 65;  // 1000001 (Sun, Sat)

/**
 * Check if a specific day is active for a goal.
 * @param activeDays Bitmask from goals.active_days (null = all days)
 * @param date The date to check
 */
export function isDayActive(activeDays: number | null, date: Date): boolean {
  if (activeDays === null) return true;
  // JavaScript getDay(): 0=Sun, 1=Mon, ..., 6=Sat — matches our bitmask directly
  const bitIndex = date.getDay();
  return (activeDays & (1 << bitIndex)) !== 0;
}

/**
 * Check if a specific day-of-week index (0=Sun, 6=Sat) is active.
 */
export function isDayOfWeekActive(activeDays: number | null, dayOfWeek: number): boolean {
  if (activeDays === null) return true;
  return (activeDays & (1 << dayOfWeek)) !== 0;
}

/**
 * Count the number of active days per week.
 */
export function countActiveDays(activeDays: number | null): number {
  if (activeDays === null) return 7;
  let count = 0;
  for (let i = 0; i < 7; i++) {
    if (activeDays & (1 << i)) count++;
  }
  return count;
}

/**
 * Count active days within a date range (inclusive).
 */
export function countActiveDaysInRange(
  activeDays: number | null,
  startDate: Date,
  endDate: Date
): number {
  if (activeDays === null) {
    return Math.floor((endDate.getTime() - startDate.getTime()) / (1000 * 60 * 60 * 24)) + 1;
  }
  let count = 0;
  const current = new Date(startDate);
  while (current <= endDate) {
    if (isDayActive(activeDays, current)) count++;
    current.setDate(current.getDate() + 1);
  }
  return count;
}

/**
 * Convert bitmask to human-readable label.
 */
export function activeDaysToLabels(activeDays: number | null): string {
  if (activeDays === null || activeDays === ALL_DAYS) return 'Every day';
  if (activeDays === WEEKDAYS) return 'Weekdays only';
  if (activeDays === WEEKENDS) return 'Weekends only';

  const dayNames = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
  const active = dayNames.filter((_, i) => activeDays & (1 << i));
  return active.join(', ');
}

/**
 * Convert array of day indices (0=Sun, 6=Sat) to bitmask.
 */
export function daysToBitmask(days: number[]): number {
  return days.reduce((mask, day) => mask | (1 << day), 0);
}

/**
 * Convert bitmask to array of day indices (0=Sun, 6=Sat).
 */
export function bitmaskToDays(bitmask: number): number[] {
  const days: number[] = [];
  for (let i = 0; i < 7; i++) {
    if (bitmask & (1 << i)) days.push(i);
  }
  return days;
}

/**
 * Serialize active_days bitmask for API responses.
 */
export function serializeActiveDays(activeDays: number | null) {
  return {
    active_days: activeDays !== null ? bitmaskToDays(activeDays) : null,
    active_days_label: activeDaysToLabels(activeDays),
    active_days_count: countActiveDays(activeDays),
  };
}
