## 7. Rate Limiting

### 7.1 Rate Limit Configuration

```typescript
import rateLimit from 'express-rate-limit';

// General API rate limit
export const apiLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 100, // 100 requests per window
  message: {
    error: {
      message: 'Too many requests, please try again later',
      code: 'RATE_LIMIT_EXCEEDED'
    }
  }
});

// Auth endpoints (stricter)
export const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 5, // 5 attempts per window
  skipSuccessfulRequests: true
});

// Password reset (very strict)
export const passwordResetLimiter = rateLimit({
  windowMs: 60 * 60 * 1000, // 1 hour
  max: 3 // 3 attempts per hour
});

// Progress logging (prevents spam/loops)
export const progressLimiter = rateLimit({
  windowMs: 60 * 1000, // 1 minute
  max: 60, // 60 requests per minute per user
  message: {
    error: {
      message: 'Too many progress entries. Please slow down.',
      code: 'PROGRESS_RATE_LIMIT'
    }
  },
  keyGenerator: (req) => {
    // Rate limit per user, not per IP
    return req.user?.id || req.ip;
  }
});
```

### 7.2 Apply Rate Limiters

```typescript
app.use('/api/auth/login', authLimiter);
app.use('/api/auth/register', authLimiter);
app.use('/api/auth/forgot-password', passwordResetLimiter);
app.use('/api/progress', progressLimiter); // Critical: prevents spam
app.use('/api', apiLimiter);
```

**Why Progress Rate Limiting:**
- Prevents infinite loops in mobile app code
- Stops accidental duplicate submissions
- Protects against malicious spam
- 60/minute = 1 per second, reasonable for manual entry

---

