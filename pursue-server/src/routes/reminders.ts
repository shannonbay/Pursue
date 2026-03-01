/**
 * Smart Reminders Routes
 */

import { Router } from 'express';
import { authenticate } from '../middleware/authenticate.js';
import { internalJobAuth } from '../middleware/internalJobAuth.js';
import {
  // Internal job endpoints
  processRemindersJob,
  recalculatePatternsJob,
  updateEffectivenessJob,
  // User-facing endpoints
  getAllReminderPreferences,
  getGoalReminderPreferences,
  updateGoalReminderPreferences,
  recalculateGoalPattern,
} from '../controllers/reminders.js';

const router = Router();

// =============================================================================
// Internal Job Endpoints (secured by INTERNAL_JOB_KEY header and IP allowlist)
// =============================================================================

// Process and send reminders (every 15 minutes)
router.post('/internal/jobs/process-reminders', internalJobAuth, processRemindersJob);

// Recalculate logging patterns (weekly)
router.post('/internal/jobs/recalculate-patterns', internalJobAuth, recalculatePatternsJob);

// Update reminder effectiveness metrics (daily)
router.post('/internal/jobs/update-effectiveness', internalJobAuth, updateEffectivenessJob);

// =============================================================================
// User-Facing Endpoints (require authentication)
// =============================================================================

// Get all reminder preferences for current user
router.get('/users/me/reminder-preferences', authenticate, getAllReminderPreferences);

// Get reminder preferences for a specific goal
router.get('/goals/:goal_id/reminder-preferences', authenticate, getGoalReminderPreferences);

// Update reminder preferences for a specific goal
router.put('/goals/:goal_id/reminder-preferences', authenticate, updateGoalReminderPreferences);

// Trigger pattern recalculation for a specific goal
router.post('/goals/:goal_id/recalculate-pattern', authenticate, recalculateGoalPattern);

export default router;
