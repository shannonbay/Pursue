## 4. Authentication & Authorization

### 4.1 JWT Token Structure

**Access Token (1 hour expiry):**
```typescript
{
  user_id: "550e8400-e29b-41d4-a716-446655440000",
  email: "shannon@example.com",
  iat: 1705334400,
  exp: 1705338000
}
```

**Refresh Token (30 days expiry):**
```typescript
{
  user_id: "550e8400-e29b-41d4-a716-446655440000",
  token_id: "refresh-token-uuid",
  iat: 1705334400,
  exp: 1707926400
}
```

### 4.2 Authentication Middleware

```typescript
import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';

export interface AuthRequest extends Request {
  user?: {
    id: string;
    email: string;
  };
}

export async function authenticate(
  req: AuthRequest,
  res: Response,
  next: NextFunction
) {
  try {
    const authHeader = req.headers.authorization;
    
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return res.status(401).json({ error: 'Missing or invalid authorization header' });
    }
    
    const token = authHeader.substring(7);
    
    const payload = jwt.verify(token, process.env.JWT_SECRET!) as {
      user_id: string;
      email: string;
    };
    
    req.user = {
      id: payload.user_id,
      email: payload.email
    };
    
    next();
  } catch (error) {
    return res.status(401).json({ error: 'Invalid or expired token' });
  }
}
```

### 4.3 Authorization Helpers

```typescript
// Check if user is member of group
export async function requireGroupMember(
  userId: string,
  groupId: string
): Promise<{ role: string } | null> {
  const membership = await db
    .selectFrom('group_memberships')
    .select(['role'])
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .executeTakeFirst();
    
  return membership || null;
}

// Check if user is admin or creator
export async function requireGroupAdmin(
  userId: string,
  groupId: string
): Promise<boolean> {
  const membership = await requireGroupMember(userId, groupId);
  return membership?.role === 'admin' || membership?.role === 'creator';
}

// Check if user is creator
export async function requireGroupCreator(
  userId: string,
  groupId: string
): Promise<boolean> {
  const membership = await requireGroupMember(userId, groupId);
  return membership?.role === 'creator';
}
```

---

