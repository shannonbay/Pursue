import { Router } from 'express';
import { createNudge } from '../controllers/nudges.js';
import { authenticate } from '../middleware/authenticate.js';

const router = Router();

router.use(authenticate);

router.post('/', createNudge);

export default router;
