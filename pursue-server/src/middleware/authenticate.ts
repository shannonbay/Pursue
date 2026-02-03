import type { Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';
import type { AuthRequest } from '../types/express.js';
import { logger } from '../utils/logger.js';

interface JWTPayload {
  user_id: string;
  email: string;
}

export async function authenticate(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  // Conditional logging for avatar endpoint debugging
  const DEBUG_AVATAR = process.env.DEBUG_AVATAR === 'true';
  if (DEBUG_AVATAR && req.path?.includes('/avatar')) {
    logger.debug('Authenticate - Avatar request', {
      path: req.path,
      method: req.method,
    });
  }
  
  try {
    const authHeader = req.headers.authorization;

    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      res.status(401).json({
        error: {
          message: 'Missing or invalid authorization header',
          code: 'UNAUTHORIZED',
        },
      });
      return;
    }

    const token = authHeader.substring(7);

    const payload = jwt.verify(token, process.env.JWT_SECRET!) as JWTPayload;

    req.user = {
      id: payload.user_id,
      email: payload.email,
    };

    next();
  } catch (error) {
    res.status(401).json({
      error: {
        message: 'Invalid or expired token',
        code: 'INVALID_TOKEN',
      },
    });
  }
}
