import type { Request, Response, NextFunction } from 'express';
import multer from 'multer';
import { ZodError } from 'zod';
import { logger } from '../utils/logger.js';

export class ApplicationError extends Error {
  statusCode: number;
  code: string;

  constructor(message: string, statusCode: number, code: string) {
    super(message);
    this.name = 'ApplicationError';
    this.statusCode = statusCode;
    this.code = code;
  }
}

export function errorHandler(
  error: Error,
  req: Request,
  res: Response,
  _next: NextFunction
): void {
  const DEBUG_AVATAR = process.env.DEBUG_AVATAR === 'true';
  const isAvatarEndpoint = req.path?.includes('/avatar');
  
  // Enhanced logging for avatar endpoints (only if DEBUG_AVATAR is enabled)
  if (DEBUG_AVATAR && isAvatarEndpoint) {
    logger.debug('Error handler - Avatar endpoint', {
      path: req.path,
      method: req.method,
      errorName: error.name,
      errorMessage: error.message,
      errorCode: 'code' in error ? (error as any).code : undefined,
      statusCode: 'statusCode' in error ? (error as any).statusCode : undefined,
      stack: error.stack,
      fullError: JSON.stringify(error, Object.getOwnPropertyNames(error)),
    });
  }
  
  // Always log errors (original behavior) - but skip if already logged above
  if (!(DEBUG_AVATAR && isAvatarEndpoint)) {
    logger.error('Request error', {
      path: req.path,
      method: req.method,
      error: error.message,
      errorName: error.name,
      stack: error.stack,
      code: 'code' in error ? (error as any).code : undefined,
      statusCode: 'statusCode' in error ? (error as any).statusCode : undefined,
    });
  }

  // Zod validation errors
  if (error instanceof ZodError) {
    res.status(400).json({
      error: {
        message: 'Validation error',
        code: 'VALIDATION_ERROR',
        details: error.issues,
      },
    });
    return;
  }

  // Custom application errors
  if (error instanceof ApplicationError) {
    res.status(error.statusCode).json({
      error: {
        message: error.message,
        code: error.code,
      },
    });
    return;
  }

  // Multer errors (e.g. LIMIT_FILE_SIZE)
  if (error instanceof multer.MulterError) {
    const code = error.code === 'LIMIT_FILE_SIZE' ? 'FILE_TOO_LARGE' : error.code;
    const message =
      error.code === 'LIMIT_FILE_SIZE'
        ? 'File too large. Maximum size is 5 MB.'
        : error.message;
    res.status(400).json({
      error: { message, code },
    });
    return;
  }

  // Sharp / image processing errors (can slip through from other code paths)
  const msg = error.message ?? '';
  if (
    /sharp|vips|input buffer|unsupported image|corrupt|expected.*image|decode.*image/i.test(msg)
  ) {
    res.status(400).json({
      error: {
        message: 'Invalid or unsupported image format',
        code: 'INVALID_IMAGE',
      },
    });
    return;
  }

  // Database resource limit exceptions
  if (/(USER_GROUP|GROUP_MEMBER|GROUP_GOALS|USER_GROUP_MEMBERSHIP)_LIMIT_EXCEEDED/.test(msg)) {
    res.status(429).json({
      error: {
        message: msg,
        code: 'RESOURCE_LIMIT_EXCEEDED',
      },
    });
    return;
  }

  // Default: Internal server error
  res.status(500).json({
    error: {
      message: 'Internal server error',
      code: 'INTERNAL_ERROR',
    },
  });
}
