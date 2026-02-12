import { z } from 'zod';

const isoDate = z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'Date must be YYYY-MM-DD');

export const CreateNudgeSchema = z
  .object({
    recipient_user_id: z.string().uuid('Invalid recipient user ID format'),
    group_id: z.string().uuid('Invalid group ID format'),
    goal_id: z.string().uuid('Invalid goal ID format').optional(),
    sender_local_date: isoDate,
  })
  .strict();

export type CreateNudgeInput = z.infer<typeof CreateNudgeSchema>;

export const GetSentTodaySchema = z
  .object({
    sender_local_date: isoDate,
  })
  .strict();

export type GetSentTodayInput = z.infer<typeof GetSentTodaySchema>;
