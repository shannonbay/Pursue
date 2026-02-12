import { z } from 'zod';

export const ALLOWED_REACTIONS = ['🔥', '💪', '❤️', '👏', '🤩', '😂'] as const;
export type ReactionEmoji = (typeof ALLOWED_REACTIONS)[number];

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export const AddReactionSchema = z
  .object({
    emoji: z.enum(ALLOWED_REACTIONS),
  })
  .strict();

export const ActivityIdParamSchema = z.object({
  activity_id: z.string().regex(UUID_REGEX, 'Invalid activity ID'),
});

export type AddReactionBody = z.infer<typeof AddReactionSchema>;
export type ActivityIdParam = z.infer<typeof ActivityIdParamSchema>;
