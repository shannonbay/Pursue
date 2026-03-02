import { z } from 'zod';

const VALID_DURATIONS = [25, 45, 60, 90] as const;

export const CreateSessionSchema = z
  .object({
    focus_duration_minutes: z
      .number()
      .refine((v): v is (typeof VALID_DURATIONS)[number] => (VALID_DURATIONS as readonly number[]).includes(v), {
        message: 'focus_duration_minutes must be 25, 45, 60, or 90',
      }),
  })
  .strict();

export type CreateSessionInput = z.infer<typeof CreateSessionSchema>;

export const CreateSlotSchema = z
  .object({
    scheduled_start: z.string().datetime({ offset: true }).refine((val) => {
      const date = new Date(val);
      const maxDate = new Date();
      maxDate.setDate(maxDate.getDate() + 30);
      return date <= maxDate;
    }, { message: 'scheduled_start must be within 30 days from now' }),
    focus_duration_minutes: z
      .number()
      .refine((v): v is (typeof VALID_DURATIONS)[number] => (VALID_DURATIONS as readonly number[]).includes(v), {
        message: 'focus_duration_minutes must be 25, 45, 60, or 90',
      }),
    note: z.string().max(200, 'note must be 200 characters or fewer').optional(),
  })
  .strict();

export type CreateSlotInput = z.infer<typeof CreateSlotSchema>;
