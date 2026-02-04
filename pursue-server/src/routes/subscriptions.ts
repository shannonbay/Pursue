import { Router } from 'express';
import { upgrade, verify, cancel, downgradeSelectGroup } from '../controllers/subscriptions.js';
import { authenticate } from '../middleware/authenticate.js';

const router = Router();

router.post('/upgrade', authenticate, upgrade);
router.post('/verify', verify); // Can be called with or without auth (e.g. webhook with user_id in body)
router.post('/cancel', authenticate, cancel);
router.post('/downgrade/select-group', authenticate, downgradeSelectGroup);

export default router;
