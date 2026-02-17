/**
 * Smart Reminders - Zod Validation Schemas
 */

import { z } from 'zod';

// Reminder modes
const reminderModeSchema = z.enum(['smart', 'fixed', 'disabled']);

// Aggressiveness levels
const aggressivenessSchema = z.enum(['gentle', 'balanced', 'persistent']);

// Hour validation (0-23)
const hourSchema = z.number().int().min(0).max(23);

/**
 * Schema for updating reminder preferences
 */
export const UpdateReminderPreferencesSchema = z
  .object({
    enabled: z.boolean().optional(),
    mode: reminderModeSchema.optional(),
    fixed_hour: hourSchema.nullable().optional(),
    aggressiveness: aggressivenessSchema.optional(),
    quiet_hours_start: hourSchema.nullable().optional(),
    quiet_hours_end: hourSchema.nullable().optional(),
  })
  .refine(
    (data) => {
      // If mode is 'fixed', fixed_hour must be provided
      if (data.mode === 'fixed' && data.fixed_hour === undefined) {
        return false;
      }
      return true;
    },
    {
      message: 'fixed_hour is required when mode is "fixed"',
      path: ['fixed_hour'],
    }
  )
  .refine(
    (data) => {
      // Quiet hours: both must be provided or both must be null/undefined
      const hasStart =
        data.quiet_hours_start !== undefined && data.quiet_hours_start !== null;
      const hasEnd =
        data.quiet_hours_end !== undefined && data.quiet_hours_end !== null;
      if (hasStart !== hasEnd) {
        return false;
      }
      return true;
    },
    {
      message: 'Both quiet_hours_start and quiet_hours_end must be provided together',
      path: ['quiet_hours_start'],
    }
  );

/**
 * Schema for recalculate pattern request
 */
export const RecalculatePatternSchema = z.object({
  user_timezone: z.string().min(1).max(50),
});

export type UpdateReminderPreferencesInput = z.infer<
  typeof UpdateReminderPreferencesSchema
>;
export type RecalculatePatternInput = z.infer<typeof RecalculatePatternSchema>;
