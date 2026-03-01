import { Router } from 'express';
import { upgrade, verify, cancel, downgradeSelectGroup } from '../controllers/subscriptions.js';
import { authenticate } from '../middleware/authenticate.js';

const router = Router();

router.post('/upgrade', authenticate, upgrade);
router.post('/verify', authenticate, verify);
router.post('/cancel', authenticate, cancel);
router.post('/downgrade/select-group', authenticate, downgradeSelectGroup);

export default router;
