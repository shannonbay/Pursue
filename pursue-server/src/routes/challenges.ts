import { Router } from 'express';
import {
  cancelChallenge,
  createChallenge,
  getChallengeTemplates,
  listChallenges,
  processChallengeCompletionPushesJob,
  updateChallengeStatusesJob,
} from '../controllers/challenges.js';
import { authenticate } from '../middleware/authenticate.js';

const router = Router();

router.post('/internal/jobs/update-challenge-statuses', updateChallengeStatusesJob);
router.post('/internal/jobs/process-challenge-completion-pushes', processChallengeCompletionPushesJob);

router.get('/challenge-templates', authenticate, getChallengeTemplates);
router.post('/challenges', authenticate, createChallenge);
router.get('/challenges', authenticate, listChallenges);
router.patch('/challenges/:id/cancel', authenticate, cancelChallenge);

export default router;
