## 6. Error Handling

### 6.1 Error Response Format

All errors follow consistent format:

```json
{
  "error": {
    "message": "Human-readable error message",
    "code": "ERROR_CODE",
    "details": {} // Optional, validation errors, etc.
  }
}
```

### 6.2 HTTP Status Codes

| Code | Meaning | Usage |
|------|---------|-------|
| 200 | OK | Successful GET/PATCH/POST |
| 201 | Created | Successful POST (resource created) |
| 204 | No Content | Successful DELETE |
| 400 | Bad Request | Invalid input, validation errors |
| 401 | Unauthorized | Missing/invalid auth token |
| 403 | Forbidden | Authenticated but not authorized |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Resource already exists, constraint violation |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Unexpected server error |

### 6.3 Global Error Handler

```typescript
import { Request, Response, NextFunction } from 'express';
import { ZodError } from 'zod';

export function errorHandler(
  error: Error,
  req: Request,
  res: Response,
  next: NextFunction
) {
  console.error('Error:', error);
  
  // Zod validation errors
  if (error instanceof ZodError) {
    return res.status(400).json({
      error: {
        message: 'Validation error',
        code: 'VALIDATION_ERROR',
        details: error.errors
      }
    });
  }
  
  // Custom application errors
  if (error.name === 'ApplicationError') {
    return res.status((error as any).statusCode || 400).json({
      error: {
        message: error.message,
        code: (error as any).code
      }
    });
  }
  
  // Default: Internal server error
  return res.status(500).json({
    error: {
      message: 'Internal server error',
      code: 'INTERNAL_ERROR'
    }
  });
}
```

---

