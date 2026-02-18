import { Router } from 'express';
import {
  cancelChallenge,
  createChallenge,
  getChallengeTemplates,
  listChallenges,
  updateChallengeStatusesJob,
} from '../controllers/challenges.js';
import { authenticate } from '../middleware/authenticate.js';

const router = Router();

router.post('/internal/jobs/update-challenge-statuses', updateChallengeStatusesJob);

router.get('/challenge-templates', authenticate, getChallengeTemplates);
router.post('/challenges', authenticate, createChallenge);
router.get('/challenges', authenticate, listChallenges);
router.patch('/challenges/:id/cancel', authenticate, cancelChallenge);

export default router;
