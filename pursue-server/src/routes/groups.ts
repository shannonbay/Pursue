import { Router } from 'express';
import {
  createGroup,
  getGroup,
  updateGroup,
  uploadIcon,
  getGroupIcon,
  deleteIcon,
  deleteGroup,
  listMembers,
  listPendingMembers,
  approveMember,
  declineMember,
  updateMemberRole,
  removeMember,
  leaveGroup,
  getGroupInvite,
  regenerateInviteCode,
  joinGroup,
  getActivity,
  validateExportRange,
  exportGroupProgress,
  getMemberProgress,
} from '../controllers/groups.js';
import { getSentToday } from '../controllers/nudges.js';
import { createGoal, listGoals } from '../controllers/goals.js';
import { authenticate } from '../middleware/authenticate.js';
import { exportProgressLimiter } from '../middleware/rateLimiter.js';

const router = Router();

// All routes require authentication
router.use(authenticate);

// POST /api/groups/join (must be before /:group_id)
router.post('/join', joinGroup);

// Group CRUD
router.post('/', createGroup);
router.get('/:group_id/export-progress/validate-range', validateExportRange);
router.get('/:group_id/export-progress', exportProgressLimiter, exportGroupProgress);
router.get('/:group_id', getGroup);
router.patch('/:group_id', updateGroup);
router.delete('/:group_id', deleteGroup);

// Icon management
router.get('/:group_id/icon', getGroupIcon);
router.patch('/:group_id/icon', uploadIcon);
router.delete('/:group_id/icon', deleteIcon);

// Members
router.get('/:group_id/members', listMembers);
router.get('/:group_id/members/pending', listPendingMembers);
router.post('/:group_id/members/:user_id/approve', approveMember);
router.post('/:group_id/members/:user_id/decline', declineMember);
router.delete('/:group_id/members/me', leaveGroup); // Must be before /:user_id
router.patch('/:group_id/members/:user_id', updateMemberRole);
router.delete('/:group_id/members/:user_id', removeMember);
router.get('/:group_id/members/:user_id/progress', getMemberProgress);

// Invite (one active code per group)
router.get('/:group_id/invite', getGroupInvite);
router.post('/:group_id/invite/regenerate', regenerateInviteCode);

// Goals
router.get('/:group_id/goals', listGoals);
router.post('/:group_id/goals', createGoal);

// Activity
router.get('/:group_id/activity', getActivity);

// Nudges
router.get('/:group_id/nudges/sent-today', getSentToday);

export default router;
