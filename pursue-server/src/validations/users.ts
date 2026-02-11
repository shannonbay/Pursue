import { z } from 'zod';

export const UpdateUserSchema = z.object({
  display_name: z.string().min(1, 'Display name must be at least 1 character').max(100, 'Display name must be at most 100 characters').optional(),
}).strict();

export const ChangePasswordSchema = z.object({
  current_password: z.string().nullable().optional(),
  new_password: z.string().min(8, 'Password must be at least 8 characters').max(100, 'Password must be at most 100 characters'),
}).strict();

export const DeleteUserSchema = z.object({
  confirmation: z.string().min(1, 'Confirmation is required'),
}).strict();

export const GetGroupsQuerySchema = z.object({
  limit: z.coerce.number().int().min(1).default(50).optional(),
  offset: z.coerce.number().int().min(0).default(0).optional(),
});

export type UpdateUserInput = z.infer<typeof UpdateUserSchema>;
export type ChangePasswordInput = z.infer<typeof ChangePasswordSchema>;
export type DeleteUserInput = z.infer<typeof DeleteUserSchema>;
export type GetGroupsQueryInput = z.infer<typeof GetGroupsQuerySchema>;
