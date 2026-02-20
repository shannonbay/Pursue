## 12. Security Architecture & Best Practices

### 12.1 Security Design Principles

**Defense in Depth:**
- Multiple layers of security controls
- No single point of failure in security architecture
- Assume breach mentality - limit damage if one layer fails

**Principle of Least Privilege:**
- Database users have minimal required permissions
- API endpoints validate authorization for every request
- Service accounts scoped to specific resources

**Secure by Default:**
- HTTPS only, no HTTP fallback
- Strict CSP and security headers
- Authentication required for all data endpoints

### 12.2 Authentication & Authorization

**JWT Token Security:**
```typescript
// Token configuration
const JWT_CONFIG = {
  accessToken: {
    expiresIn: '1h',        // Short-lived for security
    algorithm: 'HS256'
  },
  refreshToken: {
    expiresIn: '30d',       // Longer-lived
    algorithm: 'HS256'
  }
};

// Secure token generation
export function generateTokens(userId: string) {
  const accessToken = jwt.sign(
    { user_id: userId, type: 'access' },
    process.env.JWT_SECRET!,
    { expiresIn: '1h' }
  );
  
  const refreshToken = jwt.sign(
    { user_id: userId, type: 'refresh' },
    process.env.JWT_REFRESH_SECRET!,
    { expiresIn: '30d' }
  );
  
  return { accessToken, refreshToken };
}

// Token verification with error handling
export function verifyAccessToken(token: string): { user_id: string } {
  try {
    const decoded = jwt.verify(token, process.env.JWT_SECRET!) as any;
    
    if (decoded.type !== 'access') {
      throw new Error('Invalid token type');
    }
    
    return { user_id: decoded.user_id };
  } catch (error) {
    if (error.name === 'TokenExpiredError') {
      throw new Error('Token expired');
    }
    throw new Error('Invalid token');
  }
}
```

**Authorization Middleware:**
```typescript
// Verify user owns resource or has permission
export async function requireGroupMembership(
  req: Request,
  res: Response,
  next: NextFunction
) {
  const userId = req.user!.user_id;
  const groupId = req.params.groupId;
  
  const membership = await db
    .selectFrom('group_members')
    .select('role')
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .executeTakeFirst();
  
  if (!membership) {
    return res.status(403).json({ error: 'Not a group member' });
  }
  
  req.userRole = membership.role;
  next();
}
```

### 12.3 Input Validation & Sanitization

**Zod Schemas for All Endpoints:**
```typescript
// User registration
const RegisterSchema = z.object({
  email: z.string().email().max(255),
  password: z.string().min(8).max(128),
  display_name: z.string().min(1).max(100).trim()
}).strict();

// Progress entry with business logic validation
const ProgressEntrySchema = z.object({
  goal_id: z.string().uuid(),
  value: z.number().min(0).max(1_000_000),
  note: z.string().max(500).trim().optional(),
  user_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  user_timezone: z.string().min(1).max(50)
}).strict();

// Validation middleware
export function validateBody(schema: z.ZodSchema) {
  return (req: Request, res: Response, next: NextFunction) => {
    try {
      req.body = schema.parse(req.body);
      next();
    } catch (error) {
      if (error instanceof z.ZodError) {
        return res.status(400).json({
          error: 'Validation failed',
          details: error.errors
        });
      }
      next(error);
    }
  };
}
```

### 12.4 SQL Injection Prevention

**Always use parameterized queries** (Kysely does this automatically):

```typescript
// ✅ SAFE - Kysely uses parameterized queries
const user = await db
  .selectFrom('users')
  .selectAll()
  .where('email', '=', userEmail)
  .executeTakeFirst();

// ❌ NEVER DO THIS - SQL injection vulnerability
const user = await db.executeQuery(`
  SELECT * FROM users WHERE email = '${userEmail}'
`);

// ✅ SAFE - Dynamic queries with Kysely
let query = db.selectFrom('progress_entries');

if (startDate) {
  query = query.where('entry_date', '>=', startDate);
}
if (endDate) {
  query = query.where('entry_date', '<=', endDate);
}

const results = await query.execute();
```

### 12.5 Password Security

```typescript
import bcrypt from 'bcrypt';

const BCRYPT_ROUNDS = 10; // Balance security vs performance

// Hash password (10 rounds = ~100ms)
export async function hashPassword(password: string): Promise<string> {
  return bcrypt.hash(password, BCRYPT_ROUNDS);
}

// Verify password with timing-attack protection
export async function verifyPassword(
  password: string,
  hash: string
): Promise<boolean> {
  return bcrypt.compare(password, hash);
}

// Password strength validation
const PasswordSchema = z.string()
  .min(8, 'Password must be at least 8 characters')
  .max(128, 'Password too long')
  .regex(/[a-z]/, 'Must contain lowercase letter')
  .regex(/[A-Z]/, 'Must contain uppercase letter')
  .regex(/[0-9]/, 'Must contain number');
```

### 12.6 Security Headers (Helmet)

```typescript
import helmet from 'helmet';

app.use(helmet({
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'self'"],
      styleSrc: ["'self'", "'unsafe-inline'"],
      scriptSrc: ["'self'"],
      imgSrc: ["'self'", 'data:', 'https:'],
      connectSrc: ["'self'"],
      fontSrc: ["'self'"],
      objectSrc: ["'none'"],
      mediaSrc: ["'self'"],
      frameSrc: ["'none'"]
    }
  },
  hsts: {
    maxAge: 31536000,
    includeSubDomains: true,
    preload: true
  },
  noSniff: true,
  referrerPolicy: { policy: 'strict-origin-when-cross-origin' }
}));
```

### 12.7 CORS Configuration

```typescript
import cors from 'cors';

const allowedOrigins = [
  'https://getpursue.app',
  'https://www.getpursue.app',
  process.env.FRONTEND_URL
].filter(Boolean);

app.use(cors({
  origin: (origin, callback) => {
    // Allow requests with no origin (mobile apps, curl, etc.)
    if (!origin) return callback(null, true);
    
    if (allowedOrigins.includes(origin)) {
      callback(null, true);
    } else {
      callback(new Error('Not allowed by CORS'));
    }
  },
  credentials: true,
  methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'],
  allowedHeaders: ['Content-Type', 'Authorization']
}));
```

### 12.8 Rate Limiting

```typescript
import rateLimit from 'express-rate-limit';

// General API rate limit
const apiLimiter = rateLimit({
  windowMs: 1 * 60 * 1000,        // 1 minute
  max: 100,                        // 100 requests per minute
  message: 'Too many requests, please try again later',
  standardHeaders: true,
  legacyHeaders: false
});

// Stricter limit for authentication endpoints
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,       // 15 minutes
  max: 5,                          // 5 attempts
  message: 'Too many login attempts, please try again later',
  skipSuccessfulRequests: true     // Don't count successful logins
});

// Very strict for file uploads
const uploadLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,       // 15 minutes
  max: 10,                         // 10 uploads
  message: 'Too many uploads, please try again later'
});

app.use('/api/', apiLimiter);
app.use('/api/auth/login', authLimiter);
app.use('/api/auth/register', authLimiter);
app.use('/api/users/me/avatar', uploadLimiter);
app.use('/api/groups/:id/icon', uploadLimiter);
```

### 12.9 File Upload Security

```typescript
import multer from 'multer';
import sharp from 'sharp';

// Strict file validation
const upload = multer({
  limits: {
    fileSize: 5 * 1024 * 1024,    // 5 MB max
    files: 1                       // One file at a time
  },
  fileFilter: (req, file, cb) => {
    // Only allow images
    const allowedMimes = ['image/jpeg', 'image/png', 'image/webp'];
    
    if (allowedMimes.includes(file.mimetype)) {
      cb(null, true);
    } else {
      cb(new Error('Invalid file type. Only JPEG, PNG, and WebP allowed'));
    }
  }
});

// Secure image processing (strips metadata, validates content)
export async function processAndValidateImage(buffer: Buffer): Promise<Buffer> {
  try {
    // Validate it's actually an image (sharp will fail on malicious files)
    const metadata = await sharp(buffer).metadata();
    
    // Additional validation
    if (!metadata.width || !metadata.height) {
      throw new Error('Invalid image');
    }
    
    if (metadata.width > 4096 || metadata.height > 4096) {
      throw new Error('Image too large');
    }
    
    // Process: resize, convert to WebP, strip EXIF
    return sharp(buffer)
      .resize(256, 256, { fit: 'cover' })
      .webp({ quality: 90 })
      .toBuffer();
  } catch (error) {
    throw new Error('Invalid or corrupted image file');
  }
}
```

### 12.10 Secrets Management

```typescript
// ❌ NEVER hardcode secrets
const JWT_SECRET = 'my-secret-key';

// ✅ Use environment variables
const JWT_SECRET = process.env.JWT_SECRET;

// ✅ Validate secrets on startup
if (!process.env.JWT_SECRET || process.env.JWT_SECRET.length < 32) {
  throw new Error('JWT_SECRET must be at least 32 characters');
}

if (!process.env.JWT_REFRESH_SECRET || process.env.JWT_REFRESH_SECRET.length < 32) {
  throw new Error('JWT_REFRESH_SECRET must be at least 32 characters');
}

// Use Google Secret Manager in production
import { SecretManagerServiceClient } from '@google-cloud/secret-manager';

const client = new SecretManagerServiceClient();

async function getSecret(name: string): Promise<string> {
  const [version] = await client.accessSecretVersion({
    name: `projects/${PROJECT_ID}/secrets/${name}/versions/latest`
  });
  
  return version.payload!.data!.toString();
}
```

### 12.11 Database Security

```typescript
// Use least-privilege database users
const DB_CONFIG = {
  // Application user (read/write data)
  app: {
    user: 'pursue_app',
    permissions: ['SELECT', 'INSERT', 'UPDATE', 'DELETE'],
    tables: ['users', 'groups', 'goals', 'progress_entries', 'group_members']
  },
  
  // Migration user (schema changes)
  migration: {
    user: 'pursue_migration',
    permissions: ['ALL'],
    tables: ['ALL']
  },
  
  // Read-only user (analytics, reporting)
  readonly: {
    user: 'pursue_readonly',
    permissions: ['SELECT'],
    tables: ['ALL']
  }
};

// Enable SSL for database connections
const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: {
    rejectUnauthorized: true,
    ca: fs.readFileSync('./certs/server-ca.pem').toString(),
    key: fs.readFileSync('./certs/client-key.pem').toString(),
    cert: fs.readFileSync('./certs/client-cert.pem').toString()
  }
});
```

### 12.12 Audit Logging

```typescript
// Log all sensitive operations
export function auditLog(action: string, userId: string, details: any) {
  logger.info('Audit', {
    action,
    user_id: userId,
    timestamp: new Date().toISOString(),
    details,
    ip: req.ip,
    user_agent: req.get('User-Agent')
  });
}

// Examples
auditLog('user.login', userId, { method: 'email' });
auditLog('group.created', userId, { group_id: groupId });
auditLog('user.deleted', userId, { admin_id: adminId });
auditLog('password.changed', userId, {});
```

### 12.13 Security Monitoring & Alerts

```yaml
# Alert policies
alerts:
  - name: "High Failed Login Rate"
    condition: failed_login_rate > 10/minute
    action: Lock account, notify security team
    
  - name: "Unusual API Access Pattern"
    condition: requests_from_ip > 1000/minute
    action: Rate limit IP, investigate
    
  - name: "SQL Injection Attempt"
    condition: query_contains_sql_keywords
    action: Block request, log incident
    
  - name: "Unauthorized Access Attempt"
    condition: 403_errors > 50/hour from single user
    action: Temporary account lock, investigate
```

### 12.14 Security Checklist

**Development:**
- [ ] All endpoints require authentication (except login/register)
- [ ] All inputs validated with Zod schemas
- [ ] All database queries use parameterized queries
- [ ] Passwords hashed with bcrypt (10+ rounds)
- [ ] JWT secrets are 32+ characters, stored in Secret Manager
- [ ] HTTPS only, HSTS enabled
- [ ] Security headers configured (Helmet)
- [ ] CORS restricted to known origins
- [ ] Rate limiting on all endpoints
- [ ] File uploads validated and sanitized
- [ ] Audit logging for sensitive operations

**Deployment:**
- [ ] Database uses least-privilege users
- [ ] Database connections use SSL/TLS
- [ ] Secrets stored in Google Secret Manager
- [ ] VPC connector for private database access
- [ ] Cloud Armor for DDoS protection
- [ ] Regular security updates (npm audit)
- [ ] Dependency scanning in CI/CD
- [ ] Penetration testing before launch

**Ongoing:**
- [ ] Monitor failed login attempts
- [ ] Review audit logs weekly
- [ ] Update dependencies monthly
- [ ] Security patches applied within 48 hours
- [ ] Annual security audit
- [ ] Incident response plan documented

---

