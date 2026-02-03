import { Router } from 'express';
import {
  getGoal,
  updateGoal,
  deleteGoal,
  getProgress,
  getProgressMe,
} from '../controllers/goals.js';
import { authenticate } from '../middleware/authenticate.js';

const router = Router();

router.use(authenticate);

// More specific routes first
router.get('/:goal_id/progress/me', getProgressMe);
router.get('/:goal_id/progress', getProgress);
router.get('/:goal_id', getGoal);
router.patch('/:goal_id', updateGoal);
router.delete('/:goal_id', deleteGoal);

export default router;
