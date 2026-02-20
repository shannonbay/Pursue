import { z } from 'zod';

export const UpgradeSubscriptionSchema = z
  .object({
    platform: z.enum(['google_play', 'app_store']),
    purchase_token: z.string().min(1, 'purchase_token is required'),
    product_id: z.string().min(1, 'product_id is required'),
  })
  .strict();

export const VerifySubscriptionSchema = z
  .object({
    platform: z.enum(['google_play', 'app_store']),
    purchase_token: z.string().min(1, 'purchase_token is required'),
    product_id: z.string().min(1, 'product_id is required'),
    user_id: z.string().uuid().optional(),
  })
  .strict();

export const DowngradeSelectGroupSchema = z
  .object({
    keep_group_id: z.string().uuid('keep_group_id must be a valid UUID'),
  })
  .strict();

export const ValidateExportRangeQuerySchema = z
  .object({
    start_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'start_date must be YYYY-MM-DD'),
    end_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/, 'end_date must be YYYY-MM-DD'),
  })
  .strict()
  .refine(
    (data) => {
      const start = new Date(data.start_date);
      const end = new Date(data.end_date);
      return start <= end;
    },
    { message: 'start_date must be before or equal to end_date', path: ['end_date'] }
  );
