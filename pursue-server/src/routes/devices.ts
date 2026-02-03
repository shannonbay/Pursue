import { Router } from 'express';
import {
  registerDevice,
  listDevices,
  unregisterDevice,
} from '../controllers/devices.js';
import { authenticate } from '../middleware/authenticate.js';

const router = Router();

// All device routes require authentication
router.post('/register', authenticate, registerDevice);
router.get('/', authenticate, listDevices);
router.delete('/:device_id', authenticate, unregisterDevice);

export default router;
