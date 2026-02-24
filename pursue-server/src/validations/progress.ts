import { z } from 'zod';
import { db } from '../database/index.js';

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Date must be YYYY-MM-DD');

// Helper to check if date is not in the future (relative to user's timezone)
function isNotFutureDateInTimezone(dateStr: string, timezone: string): boolean {
  if (!timezone || typeof timezone !== 'string') {
    return true; // Let schema .min(1) catch empty/invalid; avoid throwing in refine
  }
  try {
    const formatter = new Intl.DateTimeFormat('en-CA', {
      timeZone: timezone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    });
    const todayInUserTz = formatter.format(new Date()); // "YYYY-MM-DD" format
    return dateStr <= todayInUserTz;
  } catch {
    return true; // Invalid timezone; let other validation handle it
  }
}

export const CreateProgressSchema = z
  .object({
    goal_id: z.string().uuid('Invalid goal ID format'),
    value: z.number(),
    note: z.string().max(500, 'Note must be 500 characters or less').optional(),
    log_title: z.string().min(1).max(120).optional(),
    user_date: isoDate,
    user_timezone: z.string().min(1, 'Timezone is required'),
  })
  .strict()
  .refine((data) => isNotFutureDateInTimezone(data.user_date, data.user_timezone), {
    message: 'Date cannot be in the future',
    path: ['user_date'],
  })
  .superRefine(async (data, ctx) => {
    // Fetch goal to validate value against metric_type
    const goal = await db
      .selectFrom('goals')
      .select(['metric_type', 'target_value', 'deleted_at'])
      .where('id', '=', data.goal_id)
      .executeTakeFirst();

    if (!goal) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'Goal not found',
        path: ['goal_id'],
      });
      return;
    }

    if (goal.deleted_at) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'Goal is archived',
        path: ['goal_id'],
      });
      return;
    }

    // Validate value based on metric_type
    switch (goal.metric_type) {
      case 'binary':
        if (data.value !== 0 && data.value !== 1) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Binary goal value must be 0 or 1',
            path: ['value'],
          });
        }
        break;

      case 'numeric':
        if (data.value < 0) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Numeric goal value cannot be negative',
            path: ['value'],
          });
        }
        if (data.value > 999999.99) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Numeric goal value too large (max 999999.99)',
            path: ['value'],
          });
        }
        break;

      case 'duration':
        if (data.value < 0) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Duration cannot be negative',
            path: ['value'],
          });
        }
        if (!Number.isInteger(data.value)) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Duration must be an integer (seconds)',
            path: ['value'],
          });
        }
        break;

      case 'journal':
        if (!data.log_title || data.log_title.trim().length === 0) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Journal goals require a log title',
            path: ['log_title'],
          });
        }
        if (data.value !== 0 && data.value !== 1) {
          ctx.addIssue({
            code: z.ZodIssueCode.custom,
            message: 'Journal goal value must be 0 or 1',
            path: ['value'],
          });
        }
        break;
    }
  });

export type CreateProgressInput = z.infer<typeof CreateProgressSchema>;
