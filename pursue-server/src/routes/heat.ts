import { Router } from 'express';
import { authenticate } from '../middleware/authenticate.js';
import { internalJobAuth } from '../middleware/internalJobAuth.js';
import { calculateHeatJob, getHeatHistory } from '../controllers/heat.js';

const router = Router();

// Internal job endpoint (requires internal job key and allowed IP)
router.post('/internal/jobs/calculate-heat', internalJobAuth, calculateHeatJob);

// Heat history endpoint (user auth required, premium-gated in controller)
router.get('/groups/:group_id/heat/history', authenticate, getHeatHistory);

export default router;
