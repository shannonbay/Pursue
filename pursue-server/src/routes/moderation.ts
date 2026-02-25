import { Router } from 'express';
import { authenticate } from '../middleware/authenticate.js';
import { reportContent, createDispute } from '../controllers/moderation.js';

const router = Router();

router.post('/reports', authenticate, reportContent);
router.post('/disputes', authenticate, createDispute);

export default router;
