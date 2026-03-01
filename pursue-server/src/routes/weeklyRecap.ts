import { Router } from 'express';
import { internalJobAuth } from '../middleware/internalJobAuth.js';
import { weeklyRecapJob } from '../controllers/weeklyRecap.js';

const router = Router();

// Internal job endpoint (requires internal job key and allowed IP)
router.post('/internal/jobs/weekly-recap', internalJobAuth, weeklyRecapJob);

export default router;
