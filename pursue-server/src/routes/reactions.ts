import { Router } from 'express';
import {
  addOrReplaceReaction,
  removeReaction,
  getReactions,
} from '../controllers/reactions.js';
import { authenticate } from '../middleware/authenticate.js';
import { reactionLimiter, reactionReadLimiter } from '../middleware/rateLimiter.js';

const router = Router();

// All routes require authentication
router.use(authenticate);

// Reaction endpoints - mount at /api/activities
router.put('/:activity_id/reactions', reactionLimiter, addOrReplaceReaction);
router.delete('/:activity_id/reactions', reactionLimiter, removeReaction);
router.get('/:activity_id/reactions', reactionReadLimiter, getReactions);

export default router;
