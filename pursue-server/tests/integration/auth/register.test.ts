import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';

describe('POST /api/auth/register', () => {
  it('should register a new user successfully', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User'
      });

    expect(response.status).toBe(201);
    expect(response.body).toHaveProperty('access_token');
    expect(response.body).toHaveProperty('refresh_token');
    expect(response.body.user).toMatchObject({
      email: 'test@example.com',
      display_name: 'Test User'
    });
    expect(response.body.user).toHaveProperty('id');
    expect(response.body.user).toHaveProperty('created_at');
  });

  it('should store user in database with hashed password', async () => {
    const password = 'Test123!@#';

    await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: password,
        display_name: 'Test User'
      });

    const user = await testDb
      .selectFrom('users')
      .selectAll()
      .where('email', '=', 'test@example.com')
      .executeTakeFirst();

    expect(user).toBeDefined();
    expect(user?.password_hash).not.toBe(password);
    expect(user?.password_hash).toMatch(/^\$2[aby]\$/); // bcrypt hash
  });

  it('should create email auth provider', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User'
      });

    const provider = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', response.body.user.id)
      .where('provider', '=', 'email')
      .executeTakeFirst();

    expect(provider).toBeDefined();
    expect(provider?.provider_email).toBe('test@example.com');
  });

  it('should store refresh token in database', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User'
      });

    const refreshToken = await testDb
      .selectFrom('refresh_tokens')
      .selectAll()
      .where('user_id', '=', response.body.user.id)
      .executeTakeFirst();

    expect(refreshToken).toBeDefined();
    expect(refreshToken?.revoked_at).toBeNull();
    expect(new Date(refreshToken!.expires_at).getTime()).toBeGreaterThan(Date.now());
  });

  it('should reject duplicate email', async () => {
    // Create first user
    await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'First User'
      });

    // Try to register with same email
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Different123!@#',
        display_name: 'Second User'
      });

    expect(response.status).toBe(409);
    expect(response.body.error.code).toBe('EMAIL_EXISTS');
  });

  it('should reject duplicate email case-insensitively', async () => {
    await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'First User'
      });

    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'TEST@EXAMPLE.COM',
        password: 'Different123!@#',
        display_name: 'Second User'
      });

    expect(response.status).toBe(409);
  });

  it('should reject password shorter than 8 characters', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: '123',
        display_name: 'Test User'
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject invalid email format', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'not-an-email',
        password: 'Test123!@#',
        display_name: 'Test User'
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject missing email', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        password: 'Test123!@#',
        display_name: 'Test User'
      });

    expect(response.status).toBe(400);
  });

  it('should reject missing password', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        display_name: 'Test User'
      });

    expect(response.status).toBe(400);
  });

  it('should reject missing display_name', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#'
      });

    expect(response.status).toBe(400);
  });

  it('should reject empty display_name', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: ''
      });

    expect(response.status).toBe(400);
  });

  it('should reject display_name longer than 100 characters', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'A'.repeat(101)
      });

    expect(response.status).toBe(400);
  });

  it('should reject display_name exceeding max length (>100 chars)', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'A'.repeat(150)
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should normalize email to lowercase', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'TEST@EXAMPLE.COM',
        password: 'Test123!@#',
        display_name: 'Test User'
      });

    expect(response.status).toBe(201);
    expect(response.body.user.email).toBe('test@example.com');
  });
});
