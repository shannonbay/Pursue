export type ChallengeWindowStatus = 'upcoming' | 'active' | 'completed' | 'cancelled';

function pad2(value: number): string {
  return String(value).padStart(2, '0');
}

function isValidTimezone(timezone: string): boolean {
  try {
    Intl.DateTimeFormat('en-US', { timeZone: timezone }).format(new Date());
    return true;
  } catch {
    return false;
  }
}

function formatInTimezone(date: Date, timezone: string): {
  year: number;
  month: number;
  day: number;
  hour: number;
} {
  const formatter = new Intl.DateTimeFormat('en-US', {
    timeZone: timezone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    hour12: false,
  });
  const parts = formatter.formatToParts(date);
  const part = (type: string): number => Number(parts.find((p) => p.type === type)?.value ?? '0');
  return {
    year: part('year'),
    month: part('month'),
    day: part('day'),
    hour: part('hour'),
  };
}

export function getDateInTimezone(timezone: string, now: Date = new Date()): string {
  const safeTimezone = isValidTimezone(timezone) ? timezone : 'UTC';
  const date = now.toLocaleDateString('en-CA', { timeZone: safeTimezone });
  return date;
}

export function getCurrentDateUtcPlus14(now: Date = new Date()): string {
  return getDateInTimezone('Pacific/Kiritimati', now);
}

export function getCurrentDateUtcMinus12(now: Date = new Date()): string {
  return getDateInTimezone('Etc/GMT+12', now);
}

export function computeChallengeWindowStatus(
  localDate: string,
  startDate: string | null,
  endDate: string | null,
  challengeStatus: string | null
): ChallengeWindowStatus {
  if (challengeStatus === 'cancelled') {
    return 'cancelled';
  }
  if (!startDate || !endDate) {
    return challengeStatus === 'cancelled' ? 'cancelled' : 'active';
  }
  if (localDate < startDate) {
    return 'upcoming';
  }
  if (localDate > endDate) {
    return 'completed';
  }
  return 'active';
}

export function addDaysDateOnly(dateStr: string, days: number): string {
  const d = new Date(`${dateStr}T00:00:00.000Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

function toUtcFromLocalDateHour(localDate: string, hour: number, timezone: string): Date {
  const safeTimezone = isValidTimezone(timezone) ? timezone : 'UTC';
  const [year, month, day] = localDate.split('-').map(Number);
  const targetEpoch = Date.UTC(year, month - 1, day, hour, 0, 0);
  let guess = new Date(targetEpoch);

  for (let i = 0; i < 4; i++) {
    const local = formatInTimezone(guess, safeTimezone);
    const representedEpoch = Date.UTC(local.year, local.month - 1, local.day, local.hour, 0, 0);
    const diff = targetEpoch - representedEpoch;
    if (diff === 0) break;
    guess = new Date(guess.getTime() + diff);
  }

  return guess;
}

export function computeDeferredSendAt(
  timezone: string,
  baseLocalDate: string,
  preferredHour: number,
  now: Date = new Date()
): Date {
  const safeTimezone = isValidTimezone(timezone) ? timezone : 'UTC';
  const clampedHour = Math.min(23, Math.max(0, preferredHour));
  const nowLocalDate = getDateInTimezone(safeTimezone, now);
  const nowLocalHour = formatInTimezone(now, safeTimezone).hour;

  let targetLocalDate = baseLocalDate;
  if (nowLocalDate > targetLocalDate) {
    targetLocalDate = nowLocalDate;
  }
  if (nowLocalDate === targetLocalDate && nowLocalHour >= clampedHour) {
    return now;
  }

  const sendAt = toUtcFromLocalDateHour(targetLocalDate, clampedHour, safeTimezone);
  if (sendAt.getTime() < now.getTime()) {
    return now;
  }
  return sendAt;
}

export function formatDateTimeLocalLabel(date: Date, timezone: string): string {
  const safeTimezone = isValidTimezone(timezone) ? timezone : 'UTC';
  const parts = formatInTimezone(date, safeTimezone);
  return `${parts.year}-${pad2(parts.month)}-${pad2(parts.day)} ${pad2(parts.hour)}:00`;
}
