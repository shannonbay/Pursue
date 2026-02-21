import {
  DAYS,
  ALL_DAYS,
  WEEKDAYS,
  WEEKENDS,
  isDayActive,
  isDayOfWeekActive,
  countActiveDays,
  countActiveDaysInRange,
  activeDaysToLabels,
  daysToBitmask,
  bitmaskToDays,
  serializeActiveDays,
} from '../../src/utils/activeDays';

describe('activeDays utilities', () => {
  describe('constants', () => {
    it('should have correct day values (Sunday-first)', () => {
      expect(DAYS.SUN).toBe(1);
      expect(DAYS.MON).toBe(2);
      expect(DAYS.TUE).toBe(4);
      expect(DAYS.WED).toBe(8);
      expect(DAYS.THU).toBe(16);
      expect(DAYS.FRI).toBe(32);
      expect(DAYS.SAT).toBe(64);
    });

    it('should have correct compound values', () => {
      expect(ALL_DAYS).toBe(127);
      expect(WEEKDAYS).toBe(62); // Mon-Fri
      expect(WEEKENDS).toBe(65); // Sun + Sat
    });
  });

  describe('isDayActive', () => {
    it('should return true for null (every day)', () => {
      const monday = new Date('2026-02-23'); // Monday
      const saturday = new Date('2026-02-28'); // Saturday
      expect(isDayActive(null, monday)).toBe(true);
      expect(isDayActive(null, saturday)).toBe(true);
    });

    it('should return true for ALL_DAYS on any day', () => {
      const sunday = new Date('2026-02-22'); // Sunday
      const wednesday = new Date('2026-02-25'); // Wednesday
      expect(isDayActive(ALL_DAYS, sunday)).toBe(true);
      expect(isDayActive(ALL_DAYS, wednesday)).toBe(true);
    });

    it('should check weekdays correctly', () => {
      const monday = new Date('2026-02-23'); // Monday
      const friday = new Date('2026-02-27'); // Friday
      const saturday = new Date('2026-02-28'); // Saturday
      const sunday = new Date('2026-03-01'); // Sunday
      expect(isDayActive(WEEKDAYS, monday)).toBe(true);
      expect(isDayActive(WEEKDAYS, friday)).toBe(true);
      expect(isDayActive(WEEKDAYS, saturday)).toBe(false);
      expect(isDayActive(WEEKDAYS, sunday)).toBe(false);
    });

    it('should check weekends correctly', () => {
      const monday = new Date('2026-02-23'); // Monday
      const saturday = new Date('2026-02-28'); // Saturday
      const sunday = new Date('2026-03-01'); // Sunday
      expect(isDayActive(WEEKENDS, monday)).toBe(false);
      expect(isDayActive(WEEKENDS, saturday)).toBe(true);
      expect(isDayActive(WEEKENDS, sunday)).toBe(true);
    });

    it('should handle MWF schedule', () => {
      // Mon=2, Wed=8, Fri=32 => 42
      const mwf = DAYS.MON | DAYS.WED | DAYS.FRI; // 2+8+32 = 42
      const monday = new Date('2026-02-23');
      const tuesday = new Date('2026-02-24');
      const wednesday = new Date('2026-02-25');
      const friday = new Date('2026-02-27');
      expect(isDayActive(mwf, monday)).toBe(true);
      expect(isDayActive(mwf, tuesday)).toBe(false);
      expect(isDayActive(mwf, wednesday)).toBe(true);
      expect(isDayActive(mwf, friday)).toBe(true);
    });
  });

  describe('isDayOfWeekActive', () => {
    it('should return true for null', () => {
      expect(isDayOfWeekActive(null, 0)).toBe(true);
      expect(isDayOfWeekActive(null, 6)).toBe(true);
    });

    it('should check by index (0=Sun, 6=Sat)', () => {
      expect(isDayOfWeekActive(WEEKDAYS, 0)).toBe(false); // Sunday
      expect(isDayOfWeekActive(WEEKDAYS, 1)).toBe(true);  // Monday
      expect(isDayOfWeekActive(WEEKDAYS, 5)).toBe(true);  // Friday
      expect(isDayOfWeekActive(WEEKDAYS, 6)).toBe(false); // Saturday
    });
  });

  describe('countActiveDays', () => {
    it('should return 7 for null', () => {
      expect(countActiveDays(null)).toBe(7);
    });

    it('should return 7 for ALL_DAYS', () => {
      expect(countActiveDays(ALL_DAYS)).toBe(7);
    });

    it('should return 5 for WEEKDAYS', () => {
      expect(countActiveDays(WEEKDAYS)).toBe(5);
    });

    it('should return 2 for WEEKENDS', () => {
      expect(countActiveDays(WEEKENDS)).toBe(2);
    });

    it('should return 3 for MWF', () => {
      const mwf = DAYS.MON | DAYS.WED | DAYS.FRI;
      expect(countActiveDays(mwf)).toBe(3);
    });
  });

  describe('countActiveDaysInRange', () => {
    it('should count all days for null', () => {
      const start = new Date('2026-02-23'); // Monday
      const end = new Date('2026-03-01');   // Sunday (7 days)
      expect(countActiveDaysInRange(null, start, end)).toBe(7);
    });

    it('should count weekdays in a full week', () => {
      const start = new Date('2026-02-23'); // Monday
      const end = new Date('2026-03-01');   // Sunday
      expect(countActiveDaysInRange(WEEKDAYS, start, end)).toBe(5);
    });

    it('should count weekends in a full week', () => {
      const start = new Date('2026-02-23'); // Monday
      const end = new Date('2026-03-01');   // Sunday
      expect(countActiveDaysInRange(WEEKENDS, start, end)).toBe(2);
    });

    it('should handle single day range', () => {
      const monday = new Date('2026-02-23');
      expect(countActiveDaysInRange(WEEKDAYS, monday, monday)).toBe(1);
      expect(countActiveDaysInRange(WEEKENDS, monday, monday)).toBe(0);
    });
  });

  describe('activeDaysToLabels', () => {
    it('should return "Every day" for null', () => {
      expect(activeDaysToLabels(null)).toBe('Every day');
    });

    it('should return "Every day" for ALL_DAYS', () => {
      expect(activeDaysToLabels(ALL_DAYS)).toBe('Every day');
    });

    it('should return "Weekdays only" for WEEKDAYS', () => {
      expect(activeDaysToLabels(WEEKDAYS)).toBe('Weekdays only');
    });

    it('should return "Weekends only" for WEEKENDS', () => {
      expect(activeDaysToLabels(WEEKENDS)).toBe('Weekends only');
    });

    it('should return comma-separated names for custom', () => {
      const mwf = DAYS.MON | DAYS.WED | DAYS.FRI;
      expect(activeDaysToLabels(mwf)).toBe('Mon, Wed, Fri');
    });

    it('should list days in Sun-first order', () => {
      const sunMon = DAYS.SUN | DAYS.MON;
      expect(activeDaysToLabels(sunMon)).toBe('Sun, Mon');
    });
  });

  describe('daysToBitmask', () => {
    it('should convert day array to bitmask', () => {
      // 0=Sun, 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat
      expect(daysToBitmask([1, 2, 3, 4, 5])).toBe(WEEKDAYS); // Mon-Fri
      expect(daysToBitmask([0, 6])).toBe(WEEKENDS); // Sun, Sat
      expect(daysToBitmask([0, 1, 2, 3, 4, 5, 6])).toBe(ALL_DAYS);
    });

    it('should handle MWF', () => {
      expect(daysToBitmask([1, 3, 5])).toBe(DAYS.MON | DAYS.WED | DAYS.FRI);
    });

    it('should handle single day', () => {
      expect(daysToBitmask([0])).toBe(DAYS.SUN);
      expect(daysToBitmask([6])).toBe(DAYS.SAT);
    });
  });

  describe('bitmaskToDays', () => {
    it('should convert bitmask to day array', () => {
      expect(bitmaskToDays(WEEKDAYS)).toEqual([1, 2, 3, 4, 5]);
      expect(bitmaskToDays(WEEKENDS)).toEqual([0, 6]);
      expect(bitmaskToDays(ALL_DAYS)).toEqual([0, 1, 2, 3, 4, 5, 6]);
    });

    it('should roundtrip with daysToBitmask', () => {
      const days = [1, 3, 5]; // MWF
      expect(bitmaskToDays(daysToBitmask(days))).toEqual(days);
    });
  });

  describe('serializeActiveDays', () => {
    it('should serialize null as every day', () => {
      const result = serializeActiveDays(null);
      expect(result.active_days).toBeNull();
      expect(result.active_days_label).toBe('Every day');
      expect(result.active_days_count).toBe(7);
    });

    it('should serialize weekdays', () => {
      const result = serializeActiveDays(WEEKDAYS);
      expect(result.active_days).toEqual([1, 2, 3, 4, 5]);
      expect(result.active_days_label).toBe('Weekdays only');
      expect(result.active_days_count).toBe(5);
    });

    it('should serialize weekends', () => {
      const result = serializeActiveDays(WEEKENDS);
      expect(result.active_days).toEqual([0, 6]);
      expect(result.active_days_label).toBe('Weekends only');
      expect(result.active_days_count).toBe(2);
    });
  });
});
