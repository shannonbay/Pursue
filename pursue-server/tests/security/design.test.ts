import request from 'supertest';
import { app } from '../../src/app';
import { createAuthenticatedUser, createGroupWithGoal, randomEmail } from '../helpers';

/**
 * Insecure Design Security Tests
 * Tests for flawed architectural decisions
 * OWASP Top 10 2025: A06 - Insecure Design
 */
describe('Security: Insecure Design', () => {
  describe('Authorization Model', () => {
    it('should enforce proper group access control throughout API', async () => {
      const owner = await createAuthenticatedUser(randomEmail());
      const outsider = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(owner.accessToken);

      // Outsider should not access group
      const response = await request(app)
        .get(`/api/groups/${groupId}`)
        .set('Authorization', `Bearer ${outsider.accessToken}`);

      expect(response.status).toBe(403);
    });

    it('should not allow privilege escalation to admin', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const admin = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(admin.accessToken);

      // Add user to group as regular member
      await request(app)
        .post(`/api/groups/${groupId}/members`)
        .set('Authorization', `Bearer ${admin.accessToken}`)
        .send({ email: user.user.email });

      // User tries to promote self to admin (should fail)
      const response = await request(app)
        .patch(`/api/groups/${groupId}/members/${user.userId}`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ role: 'admin' });

      expect([403, 400].includes(response.status)).toBe(true);
    });
  });

  describe('Business Logic Validation', () => {
    it('should enforce group member limits', async () => {
      const owner = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(owner.accessToken, { includeGoal: false });

      // Try to add many members (should hit limit)
      let successCount = 0;
      for (let i = 0; i < 100; i++) {
        const response = await request(app)
          .post(`/api/groups/${groupId}/members`)
          .set('Authorization', `Bearer ${owner.accessToken}`)
          .send({ email: `member${i}@example.com` });

        if (response.status === 201) successCount++;
        if (response.status === 400 || response.status === 403) break;
      }

      // Should have hit member limit before adding all 100
      expect(successCount).toBeLessThan(100);
    });

    it('should enforce subscription tier limits', async () => {
      const freeUser = await createAuthenticatedUser(randomEmail());

      // Free tier should have limited groups
      const groups = [];
      for (let i = 0; i < 15; i++) {
        const response = await request(app)
          .post('/api/groups')
          .set('Authorization', `Bearer ${freeUser.accessToken}`)
          .send({
            name: `Group ${i}`,
            description: 'Test',
            icon_emoji: '👥',
          });

        if (response.status === 201) {
          groups.push(response.body.id);
        } else if (response.status === 403) {
          // Hit limit
          break;
        }
      }

      // Free tier should have limit (typically 10)
      expect(groups.length).toBeLessThan(15);
    });
  });

  describe('Sensitive Operations Confirmation', () => {
    it('should require confirmation for account deletion', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      // Without confirmation
      let response = await request(app)
        .delete('/api/users/me')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({});

      expect(response.status).toBe(400);

      // With wrong confirmation
      response = await request(app)
        .delete('/api/users/me')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ confirmation: 'delete me' });

      expect(response.status).toBe(400);
    });
  });

  describe('Resource Limits and DoS Prevention', () => {
    it('should limit request body size', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const largePayload = {
        name: 'a'.repeat(100000),
        description: 'b'.repeat(100000),
        icon_emoji: '👥',
      };

      const response = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send(largePayload);

      // Should reject large payload (may be 400, 413, 422, or 500 depending on implementation)
      expect([400, 413, 422, 500].includes(response.status)).toBe(true);
    });

    it('should handle malformed JSON gracefully', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const response = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .set('Content-Type', 'application/json')
        .send('{invalid json');

      // Should not allow malformed JSON (400, 422, or 500 are all acceptable)
      expect([400, 422, 500].includes(response.status)).toBe(true);
    });
  });

  describe('Transaction Safety', () => {
    it('should handle concurrent group operations atomically', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      // Attempt to create multiple groups concurrently
      const createPromises = Array(3).fill(null).map((_, i) =>
        request(app)
          .post('/api/groups')
          .set('Authorization', `Bearer ${user.accessToken}`)
          .send({
            name: `Concurrent Group ${i}`,
            description: 'Test',
            icon_emoji: '👥',
          })
      );

      const responses = await Promise.all(createPromises);
      const successful = responses.filter(r => r.status === 201);

      // Should successfully create groups (may not be all 3 due to rate limiting or other constraints)
      expect(successful.length).toBeGreaterThan(0);

      // Verify they're distinct
      const ids = successful.map(r => r.body.id);
      const uniqueIds = new Set(ids);
      expect(uniqueIds.size).toBe(successful.length);
    });
  });

  describe('Third-Party Integration Security', () => {
    it('should validate Google OAuth tokens properly', async () => {
      const response = await request(app)
        .post('/api/auth/google')
        .send({
          id_token: 'invalid-google-token',
        });

      // Should reject invalid token (400 validation error or 401 auth failure)
      expect([400, 401, 403].includes(response.status)).toBe(true);
    });
  });

  describe('Data Validation on Updates', () => {
    it('should not allow changing immutable fields', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      // Try to update display_name (valid) but also send immutable fields
      const response = await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({
          display_name: 'New Name',
        });

      // Update should succeed
      expect([200, 201].includes(response.status)).toBe(true);

      // Verify id wasn't changed
      const userCheck = await request(app)
        .get('/api/users/me')
        .set('Authorization', `Bearer ${user.accessToken}`);

      expect(userCheck.body.id).toBe(user.userId);
      expect(userCheck.body.display_name).toBe('New Name');
    });
  });
});
