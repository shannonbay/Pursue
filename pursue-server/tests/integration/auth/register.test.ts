import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';

const VALID_DOB = '1990-01-01';

describe('POST /api/auth/register', () => {
  it('should register a new user successfully', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
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
    expect(response.body.user.has_date_of_birth).toBe(true);
  });

  it('should store user in database with hashed password', async () => {
    const password = 'Test123!@#';

    await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: password,
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
      });

    const user = await testDb
      .selectFrom('users')
      .selectAll()
      .where('email', '=', 'test@example.com')
      .executeTakeFirst();

    expect(user).toBeDefined();
    expect(user?.password_hash).not.toBe(password);
    expect(user?.password_hash).toMatch(/^\$2[aby]\$/); // bcrypt hash
    expect(user?.date_of_birth).toBe(VALID_DOB);
  });

  it('should create email auth provider', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
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
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
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
        display_name: 'First User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
      });

    // Try to register with same email
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Different123!@#',
        display_name: 'Second User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
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
        display_name: 'First User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
      });

    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'TEST@EXAMPLE.COM',
        password: 'Different123!@#',
        display_name: 'Second User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
      });

    expect(response.status).toBe(409);
  });

  it('should reject password shorter than 8 characters', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: '123',
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
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
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject missing email', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        password: 'Test123!@#',
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
      });

    expect(response.status).toBe(400);
  });

  it('should reject missing password', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
      });

    expect(response.status).toBe(400);
  });

  it('should reject missing display_name', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        date_of_birth: VALID_DOB,
      });

    expect(response.status).toBe(400);
  });

  it('should reject empty display_name', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: '',
        date_of_birth: VALID_DOB,
      });

    expect(response.status).toBe(400);
  });

  it('should reject display_name longer than 100 characters', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'A'.repeat(101),
        date_of_birth: VALID_DOB,
      });

    expect(response.status).toBe(400);
  });

  it('should reject display_name exceeding max length (>100 chars)', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'A'.repeat(150),
        date_of_birth: VALID_DOB,
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
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
      });

    expect(response.status).toBe(201);
    expect(response.body.user.email).toBe('test@example.com');
  });

  it('should reject registration without consent_agreed', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject registration with consent_agreed: false', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
        consent_agreed: false
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should create two versioned consent entries from server config', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User',
        date_of_birth: VALID_DOB,
        consent_agreed: true
      });

    expect(response.status).toBe(201);

    const consents = await testDb
      .selectFrom('user_consents')
      .selectAll()
      .where('user_id', '=', response.body.user.id)
      .execute();

    expect(consents).toHaveLength(2);
    const types = consents.map(c => c.consent_type).sort();
    expect(types).toEqual(['privacy policy Mar 1, 2026', 'terms Feb 11, 2026']);
  });

  it('should reject missing date_of_birth', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User',
        consent_agreed: true
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject invalid date_of_birth format', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'Test123!@#',
        display_name: 'Test User',
        date_of_birth: '01/01/1990',
        consent_agreed: true
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject user under 18 years old', async () => {
    const today = new Date();
    const underAge = new Date(today.getFullYear() - 17, today.getMonth(), today.getDate());
    const dob = underAge.toISOString().substring(0, 10);

    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'young@example.com',
        password: 'Test123!@#',
        display_name: 'Young User',
        date_of_birth: dob,
        consent_agreed: true
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('UNDER_AGE');
  });

  it('should accept user exactly 18 years old', async () => {
    const today = new Date();
    const exactly18 = new Date(today.getFullYear() - 18, today.getMonth(), today.getDate());
    const dob = exactly18.toISOString().substring(0, 10);

    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'eighteen@example.com',
        password: 'Test123!@#',
        display_name: 'Adult User',
        date_of_birth: dob,
        consent_agreed: true
      });

    expect(response.status).toBe(201);
    expect(response.body.user.has_date_of_birth).toBe(true);
  });
});
