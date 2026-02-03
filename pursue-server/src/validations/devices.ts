import { z } from 'zod';

export const RegisterDeviceSchema = z.object({
  fcm_token: z.string().min(1, 'FCM token is required').max(256),
  device_name: z.string().max(128).optional(),
  platform: z.enum(['android', 'ios', 'web']).optional(),
}).strict();

export type RegisterDeviceInput = z.infer<typeof RegisterDeviceSchema>;
