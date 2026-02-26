import { Router } from 'express';
import {
  uploadAvatar,
  getUserAvatar,
  deleteAvatar,
  getCurrentUser,
  updateCurrentUser,
  changePassword,
  getAuthProviders,
  getSubscription,
  getSubscriptionEligibilityHandler,
  getUserGroups,
  getUserConsents,
  getUserConsentStatus,
  recordConsents,
  getConsentEmailHash,
  deleteCurrentUser,
} from '../controllers/users.js';
import { authenticate } from '../middleware/authenticate.js';
import { uploadLimiter } from '../middleware/rateLimiter.js';
import { logger } from '../utils/logger.js';

const router = Router();

// User profile routes (must come before /:user_id routes)
router.get('/me', authenticate, getCurrentUser);
router.patch('/me', authenticate, updateCurrentUser);
router.post('/me/password', authenticate, changePassword);
router.get('/me/providers', authenticate, getAuthProviders);
router.get('/me/subscription', authenticate, getSubscription);
router.get('/me/subscription/eligibility', authenticate, getSubscriptionEligibilityHandler);
router.get('/me/groups', authenticate, getUserGroups);
router.get('/me/consents/status', authenticate, getUserConsentStatus);
router.get('/me/consents', authenticate, getUserConsents);
router.post('/me/consents', authenticate, recordConsents);
router.post('/consent-hash', getConsentEmailHash);
router.delete('/me', authenticate, deleteCurrentUser);

// Avatar routes
// Temporary debug logging middleware (conditional on DEBUG_AVATAR)
const DEBUG_AVATAR = process.env.DEBUG_AVATAR === 'true';
const avatarRequestLogger = (req: any, _res: any, next: any) => {
  if (DEBUG_AVATAR) {
    logger.debug('Avatar request received', {
      method: req.method,
      path: req.path,
      contentType: req.get('Content-Type'),
      hasAuthorization: !!req.get('Authorization'),
    });
  }
  next();
};

router.post('/me/avatar', avatarRequestLogger, authenticate, uploadLimiter, uploadAvatar);
router.delete('/me/avatar', avatarRequestLogger, authenticate, deleteAvatar);
router.get('/:user_id/avatar', avatarRequestLogger, getUserAvatar); // Public endpoint (no auth required)

export default router;
