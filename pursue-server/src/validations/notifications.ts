import { z } from 'zod';

export const GetNotificationsSchema = z.object({
  limit: z.coerce.number().min(1).max(50).default(30),
  before_id: z.string().uuid().optional(),
});

export type GetNotificationsInput = z.infer<typeof GetNotificationsSchema>;

export const NotificationIdParamSchema = z.object({
  notification_id: z.string().uuid('Invalid notification ID format'),
});

export type NotificationIdParamInput = z.infer<typeof NotificationIdParamSchema>;
