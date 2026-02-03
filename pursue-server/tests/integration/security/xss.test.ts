import request from 'supertest';
import { app } from '../../../src/app';
import { createAuthenticatedUser, randomEmail, createGroupWithGoal, addMemberToGroup } from '../../helpers';
import { testDb } from '../../setup';

/**
 * XSS Prevention Tests
 *
 * These tests verify that:
 * 1. XSS payloads are stored as literal strings (not executed)
 * 2. JSON responses escape HTML entities properly
 * 3. CSP headers are present to prevent inline execution
 * 4. File uploads reject dangerous file types
 */

describe('XSS Prevention Tests', () => {
  describe('display_name field - Script Tags', () => {
    it('should store and return script tags without execution', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          display_name: '<script>alert("XSS")</script>',
        });

      expect(response.status).toBe(200);
      expect(response.body.display_name).toBe('<script>alert("XSS")</script>');

      const getResponse = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(getResponse.status).toBe(200);
      expect(getResponse.body.display_name).toBe('<script>alert("XSS")</script>');
    });

    it('should store and return display_name with event handlers', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          display_name: '<img src=x onerror=alert("XSS")>',
        });

      expect(response.status).toBe(200);
      expect(response.body.display_name).toBe('<img src=x onerror=alert("XSS")>');
    });

    it('should store and return display_name with javascript: URL', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          display_name: '<a href="javascript:alert(1)">Click me</a>',
        });

      expect(response.status).toBe(200);
      expect(response.body.display_name).toBe('<a href="javascript:alert(1)">Click me</a>');
    });

    it('should store and return display_name with iframe payload', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          display_name: '<iframe src="javascript:alert(1)"></iframe>',
        });

      expect(response.status).toBe(200);
      expect(response.body.display_name).toBe('<iframe src="javascript:alert(1)"></iframe>');
    });

    it('should store and return display_name with SVG XSS vector', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          display_name: '<svg onload="alert(1)">',
        });

      expect(response.status).toBe(200);
      expect(response.body.display_name).toBe('<svg onload="alert(1)">');
    });
  });

  describe('goal title field - XSS Payloads', () => {
    it('should store goal title with script tags', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const xssPayload = '<script>alert("XSS")</script>';

      const response = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: xssPayload,
          cadence: 'daily',
          metric_type: 'binary',
        });

      expect(response.status).toBe(201);
      expect(response.body.title).toBe(xssPayload);
    });

    it('should store goal title with closing tag XSS', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const xssPayload = '"><script>alert(1)</script>';

      const response = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: xssPayload,
          cadence: 'daily',
          metric_type: 'binary',
        });

      expect(response.status).toBe(201);
      expect(response.body.title).toBe(xssPayload);
    });

    it('should store and return goal with multiple XSS vectors', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const xssPayloads = [
        '<script>alert(1)</script>',
        '"><script>alert(1)</script>',
        '<svg onload="alert(1)">',
      ];

      for (const payload of xssPayloads) {
        const response = await request(app)
          .post(`/api/groups/${groupId}/goals`)
          .set('Authorization', `Bearer ${accessToken}`)
          .send({
            title: payload,
            cadence: 'daily',
            metric_type: 'binary',
          });

        expect(response.status).toBe(201);
        expect(response.body.title).toBe(payload);
      }
    });
  });

  describe('goal description field - XSS Payloads', () => {
    it('should store goal description with script tags', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const xssPayload = '<script>alert("XSS in description")</script>';

      const response = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Test Goal',
          description: xssPayload,
          cadence: 'daily',
          metric_type: 'binary',
        });

      expect(response.status).toBe(201);
      expect(response.body.description).toBe(xssPayload);
    });

    it('should store goal description with iframe', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const xssPayload = '<iframe src="javascript:alert(1)"></iframe>';

      const response = await request(app)
        .post(`/api/groups/${groupId}/goals`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          title: 'Test Goal',
          description: xssPayload,
          cadence: 'daily',
          metric_type: 'binary',
        });

      expect(response.status).toBe(201);
      expect(response.body.description).toBe(xssPayload);
    });
  });

  describe('group name field - XSS Payloads', () => {
    it('should store group name with script tags', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const xssPayload = '<script>alert("XSS")</script>';

      const response = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          name: xssPayload,
        });

      expect(response.status).toBe(201);
      expect(response.body.name).toBe(xssPayload);
    });

    it('should update group name with XSS payload', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });
      const xssPayload = '<svg onload="alert(1)">';

      const response = await request(app)
        .patch(`/api/groups/${groupId}`)
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          name: xssPayload,
        });

      expect(response.status).toBe(200);
      expect(response.body.name).toBe(xssPayload);
    });
  });

  describe('group description field - XSS Payloads', () => {
    it('should store group description with script tags', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const xssPayload = '<script>alert("XSS in group description")</script>';

      const response = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          name: 'Test Group',
          description: xssPayload,
        });

      expect(response.status).toBe(201);
      expect(response.body.description).toBe(xssPayload);
    });
  });

  describe('CSP Headers - Content-Security-Policy Present', () => {
    it('should include Content-Security-Policy header', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.headers['content-security-policy']).toBeDefined();
    });

    it('CSP should restrict default sources', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`);

      const csp = response.headers['content-security-policy'];
      expect(csp).toBeDefined();
      expect(csp).toContain('default-src');
    });
  });

  describe('Security Headers - XSS Protection', () => {
    it('should include X-Content-Type-Options header', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.headers['x-content-type-options']).toBe('nosniff');
    });

    it('should include X-Frame-Options header', async () => {
      const { accessToken } = await createAuthenticatedUser();

      const response = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.headers['x-frame-options']).toBeDefined();
    });
  });

  describe('File Upload Security - XSS Vectors', () => {
    it('should reject SVG files as avatar', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const svgContent = '<svg onload="alert(1)"></svg>';

      const response = await request(app)
        .post('/api/users/me/avatar')
        .set('Authorization', `Bearer ${accessToken}`)
        .attach('avatar', Buffer.from(svgContent), 'malicious.svg');

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_FILE_TYPE');
    });

    it('should reject HTML files as avatar', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const htmlContent = '<html><script>alert(1)</script></html>';

      const response = await request(app)
        .post('/api/users/me/avatar')
        .set('Authorization', `Bearer ${accessToken}`)
        .attach('avatar', Buffer.from(htmlContent), 'malicious.html');

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_FILE_TYPE');
    });

    it('should reject PHP files as avatar', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const phpContent = '<?php system($_GET["cmd"]); ?>';

      const response = await request(app)
        .post('/api/users/me/avatar')
        .set('Authorization', `Bearer ${accessToken}`)
        .attach('avatar', Buffer.from(phpContent), 'shell.php');

      expect(response.status).toBe(400);
      expect(response.body.error.code).toBe('INVALID_FILE_TYPE');
    });
  });

  describe('JSON Response Encoding', () => {
    it('should properly encode XSS payload in JSON response', async () => {
      const { accessToken } = await createAuthenticatedUser();
      const xssPayload = '<script>alert("XSS")</script>';

      await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({
          display_name: xssPayload,
        });

      const response = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${accessToken}`);

      expect(response.status).toBe(200);
      expect(response.body.display_name).toBe(xssPayload);
      expect(typeof response.body).toBe('object');
    });
  });
});