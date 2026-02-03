## 10. Testing

### 10.1 Test Structure

```
tests/
├── unit/
│   ├── auth.test.ts
│   ├── groups.test.ts
│   └── goals.test.ts
├── integration/
│   ├── api/
│   │   ├── auth.test.ts
│   │   ├── groups.test.ts
│   │   └── goals.test.ts
│   └── database/
│       └── queries.test.ts
└── e2e/
    └── user-flows.test.ts
```

### 10.2 Example Integration Test

```typescript
import request from 'supertest';
import { app } from '../src/app';
import { db } from '../src/database';

describe('POST /api/auth/register', () => {
  afterAll(async () => {
    await db.destroy();
  });
  
  it('should create new user', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'securePassword123',
        display_name: 'Test User'
      });
      
    expect(response.status).toBe(201);
    expect(response.body).toHaveProperty('access_token');
    expect(response.body).toHaveProperty('refresh_token');
    expect(response.body.user.email).toBe('test@example.com');
  });
  
  it('should reject duplicate email', async () => {
    // Register first user
    await request(app)
      .post('/api/auth/register')
      .send({
        email: 'duplicate@example.com',
        password: 'password123',
        display_name: 'User One'
      });
      
    // Try to register with same email
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'duplicate@example.com',
        password: 'password456',
        display_name: 'User Two'
      });
      
    expect(response.status).toBe(409);
  });
});
```

---

