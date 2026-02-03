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
    initial_goals: z.array(InitialGoalSchema).optional()
  })
  .strict();

export const UpdateGroupSchema = z
  .object({
    name: z.string().min(1).max(100).optional(),
    description: z.string().max(500).optional(),
    icon_emoji: optionalEmoji,
    icon_color: hexColor.optional()
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

export type CreateGroupInput = z.infer<typeof CreateGroupSchema>;
export type UpdateGroupInput = z.infer<typeof UpdateGroupSchema>;
export type UpdateMemberRoleInput = z.infer<typeof UpdateMemberRoleSchema>;
export type JoinGroupInput = z.infer<typeof JoinGroupSchema>;
