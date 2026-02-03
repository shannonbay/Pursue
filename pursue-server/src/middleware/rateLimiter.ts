import rateLimit from 'express-rate-limit';

const isTest = process.env.NODE_ENV === 'test';

// General API rate limit — applies to all /api requests
// Uses library's default IP-based keying to avoid IPv6 issues
export const apiLimiter = rateLimit({
  skip: () => isTest,
  windowMs: 60 * 1000, // 1 minute
  max: 100, // 100 requests per minute
  message: {
    error: {
      message: 'Too many requests, please try again later',
      code: 'RATE_LIMIT_EXCEEDED',
    },
  },
  standardHeaders: true,
  legacyHeaders: false,
});

// Auth endpoints (stricter)
export const authLimiter = rateLimit({
  skip: () => isTest,
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 5, // 5 attempts per window
  skipSuccessfulRequests: true,
  message: {
    error: {
      message: 'Too many authentication attempts, please try again later',
      code: 'AUTH_RATE_LIMIT_EXCEEDED',
    },
  },
  standardHeaders: true,
  legacyHeaders: false,
});

// Password reset (very strict)
export const passwordResetLimiter = rateLimit({
  skip: () => isTest,
  windowMs: 60 * 60 * 1000, // 1 hour
  max: 3, // 3 attempts per hour
  message: {
    error: {
      message: 'Too many password reset attempts, please try again later',
      code: 'PASSWORD_RESET_RATE_LIMIT',
    },
  },
  standardHeaders: true,
  legacyHeaders: false,
});

// Progress logging (60 requests per minute per user)
// Note: This should be applied after authentication middleware
// so req.user is available for keyGenerator
export const progressLimiter = rateLimit({
  skip: () => isTest,
  windowMs: 60 * 1000, // 1 minute
  max: 50, // 50 requests per minute
  keyGenerator: (req) => {
    // Authentication is required for progress endpoints,
    // so req.user should always be available
    const authReq = req as any;
    if (authReq.user?.id) {
      return authReq.user.id;
    }
    // Fallback: use a default key (shouldn't happen if auth middleware is working)
    return 'unauthenticated';
  },
  message: {
    error: {
      message: 'Too many progress entries, please try again later',
      code: 'PROGRESS_RATE_LIMIT_EXCEEDED',
    },
  },
  standardHeaders: true,
  legacyHeaders: false,
});

// Upload limiter (apply to avatar/uploads endpoints)
// Applied after authentication, so req.user will exist
export const uploadLimiter = rateLimit({
  skip: () => isTest,
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 10, // 10 uploads per 15 minutes
  keyGenerator: (req) => {
    const authReq = req as any;
    return authReq.user?.id || 'unknown';
  },
  message: {
    error: {
      message: 'Too many uploads, please try again later',
      code: 'UPLOAD_RATE_LIMIT_EXCEEDED',
    },
  },
  standardHeaders: true,
  legacyHeaders: false,
});
