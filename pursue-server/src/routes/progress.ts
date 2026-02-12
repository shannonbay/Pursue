import { Router } from 'express';
import {
  createProgress,
  getProgress,
  deleteProgress,
} from '../controllers/progress.js';
import {
  uploadProgressPhoto,
  getProgressPhoto,
} from '../controllers/photos.js';
import { authenticate } from '../middleware/authenticate.js';
import { progressLimiter, uploadLimiter } from '../middleware/rateLimiter.js';

const router = Router();

// All routes require authentication
router.use(authenticate);

// POST /api/progress - Create progress entry (with rate limiting)
router.post('/', progressLimiter, createProgress);

// GET /api/progress/:entry_id - Get specific progress entry
router.get('/:entry_id', getProgress);

// DELETE /api/progress/:entry_id - Delete progress entry
router.delete('/:entry_id', deleteProgress);

// POST /api/progress/:progress_entry_id/photo - Upload photo to progress entry
router.post('/:progress_entry_id/photo', uploadLimiter, ...uploadProgressPhoto);

// GET /api/progress/:progress_entry_id/photo - Get photo for progress entry
router.get('/:progress_entry_id/photo', getProgressPhoto);

export default router;
