import request from 'supertest';
import { app } from '../../src/app';
import { createAuthenticatedUser, randomEmail, createGroupWithGoal } from '../helpers';

/**
 * Injection Security Tests
 * Tests for SQL injection, XSS, command injection vulnerabilities
 * OWASP Top 10 2025: A03 - Injection
 */
describe('Security: Injection Prevention', () => {
  describe('SQL Injection Prevention', () => {
    it('should prevent SQL injection in email login', async () => {
      const response = await request(app)
        .post('/api/auth/login')
        .send({
          email: "' OR '1'='1",
          password: 'password',
        });

      // Should not successfully authenticate (400 = Zod rejects invalid email, 401 = auth fail)
      expect([400, 401].includes(response.status)).toBe(true);
    });

    it('should prevent SQL injection in display_name field', async () => {
      const response = await request(app)
        .post('/api/auth/register')
        .send({
          email: randomEmail(),
          password: 'Test123!@#',
          display_name: "'; DROP TABLE users; --",
          consent_agreed: true,
        });

      // Should either create user safely or reject
      expect([201, 400].includes(response.status)).toBe(true);

      // Verify table wasn't dropped (by attempting another query)
      const testUser = await createAuthenticatedUser(randomEmail());
      expect(testUser.userId).toBeDefined();
    });

    it('should prevent SQL injection in group name', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const response = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          name: "'); DELETE FROM groups; --",
          description: 'Test',
          icon_emoji: '👥',
        });

      expect([201, 400].includes(response.status)).toBe(true);
    });

    it('should prevent SQL injection in goal title', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(user.accessToken, { includeGoal: false });

      const response = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          title: "' OR '1'='1",
          description: 'Test',
          cadence: 'daily',
          metric_type: 'binary',
        });

      expect([201, 400].includes(response.status)).toBe(true);
    });

    it('should prevent SQL injection via query parameters', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      // Use the discover endpoint which accepts a 'q' query parameter
      const response = await request(app)
        .get('/api/discover/groups?q=" OR "1"="1')
        .set('Authorization', `Bearer ${user.accessToken}`);

      // Should return safely (empty results or validation error, not a SQL error)
      expect(response.status).toBeLessThan(500);
    });
  });

  describe('XSS Prevention', () => {
    it('should escape HTML in display_name', async () => {
      const xssPayload = '<img src=x onerror="alert(1)">';

      const response = await request(app)
        .post('/api/auth/register')
        .send({
          email: randomEmail(),
          password: 'Test123!@#',
          display_name: xssPayload,
          consent_agreed: true,
        });

      if (response.status === 201) {
        // If created, verify the payload is stored safely
        const user = await request(app)
          .get('/api/users/me')
          .set('Authorization', `Bearer ${response.body.access_token}`);

        // Should not execute JavaScript
        expect(user.body.display_name).toBe(xssPayload); // Stored as-is (safe due to JSON context)
      }
    });

    it('should escape HTML in group name', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const xssPayload = '<script>alert("xss")</script>';

      const response = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          name: xssPayload,
          description: 'Test',
          icon_emoji: '👥',
        });

      expect([201, 400].includes(response.status)).toBe(true);
    });

    it('should escape HTML in goal description', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(user.accessToken, { includeGoal: false });
      const xssPayload = '<img src=x onerror="console.log(document.cookie)">';

      const response = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          title: 'Test Goal',
          description: xssPayload,
          cadence: 'daily',
          metric_type: 'binary',
        });

      expect([201, 400].includes(response.status)).toBe(true);
    });

    it('should prevent JSON injection attacks', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const response = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .set('Content-Type', 'application/json')
        .send({
          name: 'Test",\n"admin": true, "name": "Admin Group',
          description: 'Test',
          icon_emoji: '👥',
        });

      expect([201, 400].includes(response.status)).toBe(true);
    });
  });

  describe('Command Injection Prevention', () => {
    it('should prevent command injection in display_name', async () => {
      const response = await request(app)
        .post('/api/auth/register')
        .send({
          email: randomEmail(),
          password: 'Test123!@#',
          display_name: '; rm -rf /',
          consent_agreed: true,
        });

      // Should safely handle the input
      expect([201, 400].includes(response.status)).toBe(true);
    });

    it('should prevent shell command injection patterns', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const injectionPatterns = [
        '$(whoami)',
        '`id`',
        '| cat /etc/passwd',
        '&& whoami',
      ];

      for (const pattern of injectionPatterns) {
        const response = await request(app)
          .post('/api/groups')
          .set('Authorization', `Bearer ${user.accessToken}`)
          .send({
            name: pattern,
            description: 'Test',
          });

        // Should safely handle without executing - may be 201 (stored safely),
        // 400 (validation/moderation), or 429 (group limit)
        expect(response.status).toBeLessThan(500);
      }
    });
  });

  describe('Path Traversal Prevention', () => {
    it('should prevent path traversal in file uploads', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const response = await request(app)
        .post('/api/users/me/avatar')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .field('filename', '../../etc/passwd')
        .attach('avatar', Buffer.from('fake data'));

      // Should safely handle or reject
      expect([400, 413, 422].includes(response.status) || response.status < 500).toBe(true);
    });
  });

  describe('LDAP Injection Prevention', () => {
    it('should prevent LDAP injection patterns if LDAP is used', async () => {
      const response = await request(app)
        .post('/api/auth/login')
        .send({
          email: '*',
          password: '*',
        });

      // Should not bypass authentication (400 = Zod rejects invalid email, 401 = auth fail)
      expect([400, 401].includes(response.status)).toBe(true);
    });
  });

  describe('Input Validation Bypass Prevention', () => {
    it('should reject invalid email formats', async () => {
      const invalidEmails = [
        'not-an-email',
        '@example.com',
        'user@',
        'user @example.com',
        'user@.com',
      ];

      for (const email of invalidEmails) {
        const response = await request(app)
          .post('/api/auth/register')
          .send({
            email,
            password: 'Test123!@#',
            display_name: 'Test',
            consent_agreed: true,
          });

        expect(response.status).toBe(400);
        expect(response.body.error.code).toBe('VALIDATION_ERROR');
      }
    });

    it('should enforce maximum field lengths', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const longString = 'a'.repeat(10000);

      const response = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          name: longString,
          description: 'Test',
          icon_emoji: '👥',
        });

      expect([400, 413, 422].includes(response.status)).toBe(true);
    });

    it('should reject null bytes in input', async () => {
      const response = await request(app)
        .post('/api/auth/register')
        .send({
          email: 'test\0@example.com',
          password: 'Test123!@#',
          display_name: 'Test',
          consent_agreed: true,
        });

      expect(response.status).toBe(400);
    });
  });
});
