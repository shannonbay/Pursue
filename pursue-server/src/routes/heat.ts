import { Router } from 'express';
import { authenticate } from '../middleware/authenticate.js';
import { calculateHeatJob, getHeatHistory } from '../controllers/heat.js';

const router = Router();

// Internal job endpoint (no user auth, uses internal job key)
router.post('/internal/jobs/calculate-heat', calculateHeatJob);

// Heat history endpoint (user auth required, premium-gated in controller)
router.get('/groups/:group_id/heat/history', authenticate, getHeatHistory);

export default router;
