import { Router } from 'express';
import { authenticate } from '../middleware/authenticate.js';
import { sessionWriteLimiter } from '../middleware/rateLimiter.js';
import {
  createSession,
  getActiveSessions,
  joinSession,
  startSession,
  endSession,
  leaveSession,
  createSlot,
  listSlots,
  cancelSlot,
  rsvpSlot,
  unrsvpSlot,
  getMySlots,
} from '../controllers/sessions.js';

const router = Router();

// Sessions
router.post('/groups/:groupId/sessions', authenticate, sessionWriteLimiter, createSession);
router.get('/groups/:groupId/sessions/active', authenticate, getActiveSessions);
router.post('/groups/:groupId/sessions/:id/join', authenticate, sessionWriteLimiter, joinSession);
router.post('/groups/:groupId/sessions/:id/start', authenticate, startSession);
router.post('/groups/:groupId/sessions/:id/end', authenticate, endSession);
router.delete('/groups/:groupId/sessions/:id/leave', authenticate, leaveSession);

// Slots
router.post('/groups/:groupId/slots', authenticate, sessionWriteLimiter, createSlot);
router.get('/groups/:groupId/slots', authenticate, listSlots);
router.delete('/groups/:groupId/slots/:id', authenticate, cancelSlot);
router.post('/groups/:groupId/slots/:id/rsvp', authenticate, sessionWriteLimiter, rsvpSlot);
router.delete('/groups/:groupId/slots/:id/rsvp', authenticate, unrsvpSlot);

// User-scoped
router.get('/me/slots', authenticate, getMySlots);

export default router;
