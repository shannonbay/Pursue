import { z } from 'zod';

export const ReportSchema = z.object({
  content_type: z.enum(['progress_entry', 'group', 'username']),
  content_id: z.string().uuid(),
  reason: z.string().min(1).max(500),
});

export const DisputeSchema = z.object({
  content_type: z.string().min(1),
  content_id: z.string().uuid(),
  user_explanation: z.string().max(280).optional(),
});

export type ReportInput = z.infer<typeof ReportSchema>;
export type DisputeInput = z.infer<typeof DisputeSchema>;
