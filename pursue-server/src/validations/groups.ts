import { z } from 'zod';

const hexColor = z.string().regex(/^#[0-9A-Fa-f]{6}$/, 'Invalid hex color');
const optionalEmoji = z.string().max(10).optional();

export const InitialGoalSchema = z
  .object({
    title: z.string().min(1, 'Title is required').max(200),
    description: z.string().max(1000).optional(),
    cadence: z.enum(['daily', 'weekly', 'monthly', 'yearly']),
    metric_type: z.enum(['binary', 'numeric', 'duration']),
    target_value: z.number().positive().max(999_999.99).optional(),
    unit: z.string().max(50).optional()
  })
  .strict()
  .refine(
    (data) => {
      if (data.metric_type === 'numeric' && data.target_value == null) return false;
      return true;
    },
    { message: 'Numeric goals must have target_value', path: ['target_value'] }
  );

export const CreateGroupSchema = z
  .object({
    name: z.string().min(1, 'Name is required').max(100),
    description: z.string().max(500).optional(),
    icon_emoji: optionalEmoji,
    icon_color: hexColor.optional(),
    icon_url: z.string().max(500).optional(),
    initial_goals: z.array(InitialGoalSchema).optional()
  })
  .strict();

export const UpdateGroupSchema = z
  .object({
    name: z.string().min(1).max(100).optional(),
    description: z.string().max(500).optional(),
    icon_emoji: optionalEmoji,
    icon_color: hexColor.optional(),
    icon_url: z.string().max(500).nullable().optional()
  })
  .strict();

export const UpdateMemberRoleSchema = z
  .object({
    role: z.enum(['admin', 'member'])
  })
  .strict();

export const JoinGroupSchema = z
  .object({
    invite_code: z.string().min(1, 'Invite code is required')
  })
  .strict();

export const ExportProgressQuerySchema = z
  .object({
    start_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'start_date must be YYYY-MM-DD'),
    end_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'end_date must be YYYY-MM-DD'),
    user_timezone: z.string().min(1, 'user_timezone is required')
  })
  .strict()
  .superRefine((data, ctx) => {
    let todayInUserTz: string;
    try {
      todayInUserTz = new Date().toLocaleDateString('en-CA', { timeZone: data.user_timezone });
    } catch (e) {
      if (e instanceof RangeError) {
        ctx.addIssue({ code: z.ZodIssueCode.custom, message: 'Invalid IANA timezone', path: ['user_timezone'] });
        return;
      }
      throw e;
    }
    if (data.start_date > todayInUserTz || data.end_date > todayInUserTz) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'Dates cannot be in the future; end_date must be >= start_date; date range cannot exceed 24 months (730 days)',
        path: ['start_date']
      });
      return;
    }
    if (data.end_date < data.start_date) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'Dates cannot be in the future; end_date must be >= start_date; date range cannot exceed 24 months (730 days)',
        path: ['start_date']
      });
      return;
    }
    const start = new Date(data.start_date + 'T00:00:00Z');
    const end = new Date(data.end_date + 'T00:00:00Z');
    const daysDiff = (end.getTime() - start.getTime()) / (1000 * 60 * 60 * 24) + 1;
    if (daysDiff > 730) {
      ctx.addIssue({
        code: z.ZodIssueCode.custom,
        message: 'Dates cannot be in the future; end_date must be >= start_date; date range cannot exceed 24 months (730 days)',
        path: ['start_date']
      });
    }
  });

export const MemberProgressQuerySchema = z
  .object({
    start_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'start_date must be YYYY-MM-DD'),
    end_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'end_date must be YYYY-MM-DD'),
    cursor: z.string().optional(),
    limit: z.coerce.number().int().min(1).max(50).optional().default(50),
  })
  .strict()
  .refine(
    (data) => data.end_date >= data.start_date,
    { message: 'end_date must be >= start_date', path: ['end_date'] }
  );

export type CreateGroupInput = z.infer<typeof CreateGroupSchema>;
export type UpdateGroupInput = z.infer<typeof UpdateGroupSchema>;
export type UpdateMemberRoleInput = z.infer<typeof UpdateMemberRoleSchema>;
export type JoinGroupInput = z.infer<typeof JoinGroupSchema>;
export type ExportProgressQuery = z.infer<typeof ExportProgressQuerySchema>;
export type MemberProgressQuery = z.infer<typeof MemberProgressQuerySchema>;
