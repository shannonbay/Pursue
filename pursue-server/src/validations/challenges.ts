import { z } from 'zod';

const dateRegex = /^\d{4}-\d{2}-\d{2}$/;

const ChallengeGoalSchema = z
  .object({
    title: z.string().min(1).max(200),
    description: z.string().max(1000).optional(),
    cadence: z.enum(['daily', 'weekly', 'monthly', 'yearly']),
    metric_type: z.enum(['binary', 'numeric', 'duration']),
    target_value: z.number().positive().max(999_999.99).optional(),
    unit: z.string().max(50).optional(),
    sort_order: z.number().int().min(0).optional(),
    active_days: z
      .array(z.number().int().min(0).max(6))
      .min(1)
      .max(7)
      .optional(),
  })
  .strict()
  .superRefine((data, ctx) => {
    if (data.metric_type === 'numeric' && data.target_value == null) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'Numeric goals must include target_value',
        path: ['target_value'],
      });
    }
    if (data.active_days && data.cadence !== 'daily') {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'Active days can only be set for daily goals',
        path: ['active_days'],
      });
    }
    if (data.active_days) {
      const unique = new Set(data.active_days);
      if (unique.size !== data.active_days.length) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'Active days must not contain duplicates',
          path: ['active_days'],
        });
      }
    }
  });

export const GetChallengeTemplatesSchema = z
  .object({
    category: z.string().min(1).max(50).optional(),
    featured: z
      .union([z.literal('true'), z.literal('false')])
      .optional()
      .transform((v) => (v === undefined ? undefined : v === 'true')),
  })
  .strict();

export const CreateChallengeSchema = z
  .object({
    template_id: z.string().uuid().optional(),
    start_date: z.string().regex(dateRegex, 'start_date must be YYYY-MM-DD'),
    end_date: z.string().regex(dateRegex, 'end_date must be YYYY-MM-DD').optional(),
    group_name: z.string().min(1).max(100).optional(),
    group_description: z.string().max(500).optional(),
    icon_emoji: z.string().max(10).optional(),
    icon_url: z.string().max(500).optional(),
    goals: z.array(ChallengeGoalSchema).max(10).optional(),
    visibility: z.enum(['public', 'private']).optional(),
  })
  .strict();

export const GetChallengesSchema = z
  .object({
    status: z.enum(['upcoming', 'active', 'completed', 'cancelled']).optional(),
  })
  .strict();

export const ChallengeIdParamSchema = z
  .object({
    id: z.string().uuid(),
  })
  .strict();

export type CreateChallengeInput = z.infer<typeof CreateChallengeSchema>;
