import { Router } from 'express';
import {
  cancelChallenge,
  createChallenge,
  dismissChallengeSuggestion,
  getChallengeTemplates,
  listChallenges,
  processChallengeCompletionPushesJob,
  updateChallengeStatusesJob,
} from '../controllers/challenges.js';
import { authenticate } from '../middleware/authenticate.js';

const router = Router();

router.post('/internal/jobs/update-challenge-statuses', updateChallengeStatusesJob);
router.post('/internal/jobs/process-challenge-completion-pushes', processChallengeCompletionPushesJob);

router.get('/group-templates', authenticate, getChallengeTemplates);
// 301 redirect from old path — remove after one release cycle
router.get('/challenge-templates', (req, res) => {
  const qs = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : '';
  res.redirect(301, `/api/group-templates${qs}`);
});
router.post('/challenges', authenticate, createChallenge);
router.get('/challenges', authenticate, listChallenges);
router.patch('/challenges/:id/cancel', authenticate, cancelChallenge);
router.patch('/challenges/suggestions/dismiss', authenticate, dismissChallengeSuggestion);

export default router;
