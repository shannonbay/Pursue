import { Router } from 'express';
import {
  register,
  login,
  googleAuth,
  refresh,
  logout,
  forgotPassword,
  resetPassword,
  linkGoogle,
  unlinkProvider,
} from '../controllers/auth.js';
import { authenticate } from '../middleware/authenticate.js';
import { authLimiter, passwordResetLimiter } from '../middleware/rateLimiter.js';

const router = Router();

// Public routes (with rate limiting)
router.post('/register', authLimiter, register);
router.post('/login', authLimiter, login);
router.post('/google', authLimiter, googleAuth);
router.post('/refresh', refresh);
router.post('/forgot-password', passwordResetLimiter, forgotPassword);
router.post('/reset-password', resetPassword);

// Protected routes
router.post('/logout', authenticate, logout);
router.post('/link/google', authenticate, linkGoogle);
router.delete('/unlink/:provider', authenticate, unlinkProvider);

export default router;
