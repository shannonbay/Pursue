import { Router } from 'express';
import {
  listPublicGroups,
  getPublicGroup,
  listSuggestions,
  dismissSuggestion,
} from '../controllers/discover.js';
import { authenticate } from '../middleware/authenticate.js';

const router = Router();

// Public (no auth required)
router.get('/discover/groups', listPublicGroups);
router.get('/discover/groups/:group_id', getPublicGroup);

// Authenticated
router.get('/discover/suggestions', authenticate, listSuggestions);
router.delete('/discover/suggestions/:group_id', authenticate, dismissSuggestion);

export default router;
