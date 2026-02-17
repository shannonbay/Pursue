import { Router } from 'express';
import { weeklyRecapJob } from '../controllers/weeklyRecap.js';

const router = Router();

// Internal job endpoint (no user auth, uses internal job key)
router.post('/internal/jobs/weekly-recap', weeklyRecapJob);

export default router;
