import { z } from 'zod';

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Date must be YYYY-MM-DD');

const activeDaysArray = z
  .array(z.number().int().min(0).max(6))
  .min(1, 'At least one active day is required')
  .max(7);

export const CreateGoalSchema = z
  .object({
    title: z.string().min(1, 'Title is required').max(200),
    description: z.string().max(1000).optional(),
    cadence: z.enum(['daily', 'weekly', 'monthly', 'yearly']),
    metric_type: z.enum(['binary', 'numeric', 'duration']),
    target_value: z.number().positive().max(999_999.99).optional(),
    unit: z.string().max(50).optional(),
    active_days: activeDaysArray.optional(),
  })
  .strict()
  .refine(
    (data) => {
      if (data.metric_type === 'numeric' && data.target_value == null) return false;
      return true;
    },
    { message: 'Numeric goals must have target_value', path: ['target_value'] }
  )
  .refine(
    (data) => {
      if (data.active_days && data.cadence !== 'daily') return false;
      return true;
    },
    { message: 'Active days can only be set for daily goals', path: ['active_days'] }
  )
  .refine(
    (data) => {
      if (data.active_days) {
        const unique = new Set(data.active_days);
        return unique.size === data.active_days.length;
      }
      return true;
    },
    { message: 'Active days must not contain duplicates', path: ['active_days'] }
  );

export const UpdateGoalSchema = z
  .object({
    title: z.string().min(1).max(200).optional(),
    description: z.string().max(1000).optional(),
    active_days: activeDaysArray.nullable().optional(),
  })
  .strict()
  .refine(
    (data) => {
      if (data.active_days) {
        const unique = new Set(data.active_days);
        return unique.size === data.active_days.length;
      }
      return true;
    },
    { message: 'Active days must not contain duplicates', path: ['active_days'] }
  );

export const ProgressQuerySchema = z
  .object({
    start_date: isoDate.optional(),
    end_date: isoDate.optional(),
  })
  .strict();

export const ListGoalsQuerySchema = z
  .object({
    cadence: z.enum(['daily', 'weekly', 'monthly', 'yearly']).optional(),
    archived: z.enum(['true', 'false']).optional(),
    include_progress: z.enum(['true', 'false']).optional(),
    user_timezone: z.string().optional(),
  })
  .strict();

export type CreateGoalInput = z.infer<typeof CreateGoalSchema>;
export type UpdateGoalInput = z.infer<typeof UpdateGoalSchema>;
export type ProgressQueryInput = z.infer<typeof ProgressQuerySchema>;
export type ListGoalsQueryInput = z.infer<typeof ListGoalsQuerySchema>;
