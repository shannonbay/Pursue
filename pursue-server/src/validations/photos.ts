import { z } from 'zod';

/**
 * Schema for photo upload metadata (sent as form fields alongside the file).
 * Width and height come from client-side measurement of the processed image.
 */
export const PhotoUploadSchema = z.object({
  width: z.coerce.number().int().min(1).max(4096),
  height: z.coerce.number().int().min(1).max(4096),
});

export type PhotoUploadInput = z.infer<typeof PhotoUploadSchema>;
