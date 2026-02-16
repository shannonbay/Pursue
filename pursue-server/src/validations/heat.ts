import { z } from 'zod';

/**
 * Query schema for GET /api/groups/:group_id/heat/history
 */
export const HeatHistoryQuerySchema = z.object({
  days: z
    .string()
    .optional()
    .transform((val) => (val ? parseInt(val, 10) : undefined))
    .refine((val) => val === undefined || (val >= 1 && val <= 90), {
      message: 'days must be between 1 and 90',
    }),
});

export type HeatHistoryQuery = z.infer<typeof HeatHistoryQuerySchema>;
