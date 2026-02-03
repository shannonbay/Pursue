import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createTestUser } from '../../helpers';

describe('POST /api/auth/login', () => {
  beforeEach(async () => {
    await createTestUser('test@example.com', 'Test123!@#', 'Test User');
  });

  it('should login successfully with correct credentials', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#'
      });

    expect(response.status).toBe(200);
    expect(response.body).toHaveProperty('access_token');
    expect(response.body).toHaveProperty('refresh_token');
    expect(response.body.user).toMatchObject({
      email: 'test@example.com',
      display_name: 'Test User'
    });
  });

  it('should login with email case-insensitively', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'TEST@EXAMPLE.COM',
        password: 'Test123!@#'
      });

    expect(response.status).toBe(200);
    expect(response.body.user.email).toBe('test@example.com');
  });

  it('should create refresh token in database on login', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#'
      });

    const refreshToken = await testDb
      .selectFrom('refresh_tokens')
      .selectAll()
      .where('user_id', '=', response.body.user.id)
      .executeTakeFirst();

    expect(refreshToken).toBeDefined();
    expect(refreshToken?.revoked_at).toBeNull();
  });

  it('should reject incorrect password', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com',
        password: 'WrongPassword123!'
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_CREDENTIALS');
  });

  it('should reject non-existent email', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'nonexistent@example.com',
        password: 'Test123!@#'
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_CREDENTIALS');
  });

  it('should reject login for Google-only user (no password)', async () => {
    // Create a user without password (Google-only)
    await testDb
      .insertInto('users')
      .values({
        email: 'google@example.com',
        display_name: 'Google User',
        password_hash: null,
      })
      .execute();

    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'google@example.com',
        password: 'SomePassword123!'
      });

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_CREDENTIALS');
  });

  it('should reject missing email', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        password: 'Test123!@#'
      });

    expect(response.status).toBe(400);
  });

  it('should reject missing password', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com'
      });

    expect(response.status).toBe(400);
  });

  it('should reject invalid email format', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'not-an-email',
        password: 'Test123!@#'
      });

    expect(response.status).toBe(400);
  });

  it('should reject empty password', async () => {
    const response = await request(app)
      .post('/api/auth/login')
      .send({
        email: 'test@example.com',
        password: ''
      });

    expect(response.status).toBe(400);
  });
});
