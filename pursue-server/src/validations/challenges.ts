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
    goals: z.array(ChallengeGoalSchema).max(10).optional(),
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
