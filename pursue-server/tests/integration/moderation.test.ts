import request from 'supertest';
import { app } from '../../src/app';
import { testDb } from '../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  createProgressEntry,
  randomEmail,
} from '../helpers';

// =============================================================================
// Name Validation Tests (via existing endpoints)
// =============================================================================

describe('Name Validation', () => {
  describe('Display name validation on register', () => {
    it('rejects reserved name "pursue"', async () => {
      const res = await request(app)
        .post('/api/auth/register')
        .send({
          email: randomEmail(),
          password: 'Test123!@#',
          display_name: 'pursue',
          consent_agreed: true,
        });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('NAME_NOT_AVAILABLE');
    });

    it('rejects reserved name "admin"', async () => {
      const res = await request(app)
        .post('/api/auth/register')
        .send({
          email: randomEmail(),
          password: 'Test123!@#',
          display_name: 'admin',
          consent_agreed: true,
        });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('NAME_NOT_AVAILABLE');
    });

    it('rejects reserved name case-insensitively', async () => {
      const res = await request(app)
        .post('/api/auth/register')
        .send({
          email: randomEmail(),
          password: 'Test123!@#',
          display_name: 'ADMIN',
          consent_agreed: true,
        });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('NAME_NOT_AVAILABLE');
    });

    it('allows a valid non-reserved name', async () => {
      const res = await request(app)
        .post('/api/auth/register')
        .send({
          email: randomEmail(),
          password: 'Test123!@#',
          display_name: 'Alice Johnson',
          consent_agreed: true,
        });

      expect(res.status).toBe(201);
    });
  });

  describe('Display name validation on profile update', () => {
    it('rejects reserved name on PATCH /api/users/me', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ display_name: 'pursue' });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('NAME_NOT_AVAILABLE');
    });

    it('allows valid name on PATCH /api/users/me', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .patch('/api/users/me')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ display_name: 'Bob Smith' });

      expect(res.status).toBe(200);
    });
  });

  describe('Group name validation', () => {
    it('rejects group name containing brand term "Pursue Official"', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ name: 'Pursue Official' });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('NAME_NOT_AVAILABLE');
    });

    it('rejects group name with brand containment (pursue anywhere)', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ name: 'My pursue group' });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('NAME_NOT_AVAILABLE');
    });

    it('rejects group name containing brand term on update', async () => {
      const user = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createGroupWithGoal(user.accessToken, {
        includeGoal: false,
        groupName: 'Running Club',
      });

      const res = await request(app)
        .patch(`/api/groups/${groupId}`)
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ name: 'Pursue Runners' });

      expect(res.status).toBe(400);
      expect(res.body.error.code).toBe('NAME_NOT_AVAILABLE');
    });

    it('allows a valid group name', async () => {
      const user = await createAuthenticatedUser(randomEmail());

      const res = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${user.accessToken}`)
        .send({ name: 'Morning Runners' });

      expect(res.status).toBe(201);
    });
  });
});

// =============================================================================
// Report Endpoint Tests
// =============================================================================

describe('POST /api/reports', () => {
  it('returns 401 when unauthenticated', async () => {
    const res = await request(app).post('/api/reports').send({
      content_type: 'progress_entry',
      content_id: '00000000-0000-0000-0000-000000000001',
      reason: 'spam',
    });

    expect(res.status).toBe(401);
  });

  it('returns 400 for invalid content_type', async () => {
    const user = await createAuthenticatedUser(randomEmail());

    const res = await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        content_type: 'invalid_type',
        content_id: '00000000-0000-0000-0000-000000000001',
        reason: 'spam',
      });

    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('returns 400 for invalid UUID content_id', async () => {
    const user = await createAuthenticatedUser(randomEmail());

    const res = await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        content_type: 'progress_entry',
        content_id: 'not-a-uuid',
        reason: 'spam',
      });

    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('returns 404 for non-existent content', async () => {
    const user = await createAuthenticatedUser(randomEmail());

    // Use a valid UUID v4 format that doesn't exist in the database
    const res = await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        content_type: 'progress_entry',
        content_id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
        reason: 'spam',
      });

    expect(res.status).toBe(404);
  });

  it('returns 403 when reporter is not a group member', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const outsider = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    const res = await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${outsider.accessToken}`)
      .send({
        content_type: 'progress_entry',
        content_id: entryId,
        reason: 'spam',
      });

    expect(res.status).toBe(403);
  });

  it('returns 201 with id when group member reports a progress entry', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    const res = await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({
        content_type: 'progress_entry',
        content_id: entryId,
        reason: 'inappropriate content',
      });

    expect(res.status).toBe(201);
    expect(res.body.id).toBeDefined();
    // Validate it's a UUID
    expect(res.body.id).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
    );
  });

  it('returns 409 ALREADY_REPORTED on duplicate report', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    const payload = {
      content_type: 'progress_entry',
      content_id: entryId,
      reason: 'spam',
    };

    await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send(payload);

    const res = await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send(payload);

    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('ALREADY_REPORTED');
  });

  it('allows any authenticated user to report a username', async () => {
    const reporter = await createAuthenticatedUser(randomEmail());
    const target = await createAuthenticatedUser(randomEmail());

    const res = await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${reporter.accessToken}`)
      .send({
        content_type: 'username',
        content_id: target.userId,
        reason: 'impersonation',
      });

    expect(res.status).toBe(201);
    expect(res.body.id).toBeDefined();
  });
});

// =============================================================================
// Auto-Hide Tests
// =============================================================================

describe('Auto-hide threshold', () => {
  it('auto-hides a progress entry when report count reaches threshold (≤10 members → 2 reports)', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    // Report 1 (from member)
    await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({ content_type: 'progress_entry', content_id: entryId, reason: 'spam' });

    // Entry should still be 'ok' after 1 report (threshold is 2 for ≤10 members)
    let row = await testDb
      .selectFrom('progress_entries')
      .select('moderation_status')
      .where('id', '=', entryId)
      .executeTakeFirstOrThrow();
    expect(row.moderation_status).toBe('ok');

    // Add a second reporter
    const { memberAccessToken: member2Token } = await addMemberToGroup(creator.accessToken, groupId);

    // Report 2 (from different user)
    await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${member2Token}`)
      .send({ content_type: 'progress_entry', content_id: entryId, reason: 'spam' });

    // Entry should now be 'hidden'
    row = await testDb
      .selectFrom('progress_entries')
      .select('moderation_status')
      .where('id', '=', entryId)
      .executeTakeFirstOrThrow();
    expect(row.moderation_status).toBe('hidden');
  });

  it('author can still GET their own hidden entry', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);
    const { memberAccessToken: member2Token } = await addMemberToGroup(creator.accessToken, groupId);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    // Trigger auto-hide with 2 reports
    await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({ content_type: 'progress_entry', content_id: entryId, reason: 'spam' });
    await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${member2Token}`)
      .send({ content_type: 'progress_entry', content_id: entryId, reason: 'spam' });

    // Author (creator) can see their own hidden entry
    const res = await request(app)
      .get(`/api/progress/${entryId}`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    expect(res.status).toBe(200);
  });

  it('non-author gets 404 for hidden entry', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);
    const { memberAccessToken: member2Token } = await addMemberToGroup(creator.accessToken, groupId);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    // Trigger auto-hide
    await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({ content_type: 'progress_entry', content_id: entryId, reason: 'spam' });
    await request(app)
      .post('/api/reports')
      .set('Authorization', `Bearer ${member2Token}`)
      .send({ content_type: 'progress_entry', content_id: entryId, reason: 'spam' });

    // Member cannot see the hidden entry
    const res = await request(app)
      .get(`/api/progress/${entryId}`)
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(res.status).toBe(404);
  });
});

// =============================================================================
// Moderation Status Filtering Tests
// =============================================================================

describe('Moderation status filtering on GET /api/progress/:id', () => {
  it('returns 404 for removed entry (all users)', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(creator.accessToken);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    // Manually set status to 'removed'
    await testDb
      .updateTable('progress_entries')
      .set({ moderation_status: 'removed' })
      .where('id', '=', entryId)
      .execute();

    const res = await request(app)
      .get(`/api/progress/${entryId}`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    expect(res.status).toBe(404);
  });

  it('returns 200 for hidden entry when requested by author', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(creator.accessToken);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    await testDb
      .updateTable('progress_entries')
      .set({ moderation_status: 'hidden' })
      .where('id', '=', entryId)
      .execute();

    const res = await request(app)
      .get(`/api/progress/${entryId}`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    expect(res.status).toBe(200);
  });

  it('returns 404 for hidden entry when requested by non-author group member', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    await testDb
      .updateTable('progress_entries')
      .set({ moderation_status: 'hidden' })
      .where('id', '=', entryId)
      .execute();

    const res = await request(app)
      .get(`/api/progress/${entryId}`)
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(res.status).toBe(404);
  });
});

// =============================================================================
// Dispute Endpoint Tests
// =============================================================================

describe('POST /api/disputes', () => {
  it('returns 401 when unauthenticated', async () => {
    const res = await request(app).post('/api/disputes').send({
      content_type: 'progress_entry',
      content_id: '00000000-0000-0000-0000-000000000001',
    });

    expect(res.status).toBe(401);
  });

  it('returns 400 for explanation over 280 chars', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(user.accessToken);
    const entryId = await createProgressEntry(goalId!, user.userId, 1, '2026-01-01');

    const res = await request(app)
      .post('/api/disputes')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        content_type: 'progress_entry',
        content_id: entryId,
        user_explanation: 'a'.repeat(281),
      });

    expect(res.status).toBe(400);
    expect(res.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('returns 403 when disputing another user\'s progress entry', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const { groupId, goalId } = await createGroupWithGoal(creator.accessToken);
    const { memberAccessToken } = await addMemberToGroup(creator.accessToken, groupId);

    const entryId = await createProgressEntry(goalId!, creator.userId, 1, '2026-01-01');

    // Set to 'removed' so it would be disputable in theory
    await testDb
      .updateTable('progress_entries')
      .set({ moderation_status: 'removed' })
      .where('id', '=', entryId)
      .execute();

    const res = await request(app)
      .post('/api/disputes')
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({
        content_type: 'progress_entry',
        content_id: entryId,
        user_explanation: 'This is a mistake',
      });

    expect(res.status).toBe(403);
  });

  it('returns 201 and updates status to disputed when disputing removed entry', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(user.accessToken);
    const entryId = await createProgressEntry(goalId!, user.userId, 1, '2026-01-01');

    // Manually mark as removed
    await testDb
      .updateTable('progress_entries')
      .set({ moderation_status: 'removed' })
      .where('id', '=', entryId)
      .execute();

    const res = await request(app)
      .post('/api/disputes')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        content_type: 'progress_entry',
        content_id: entryId,
        user_explanation: 'This was unfairly removed',
      });

    expect(res.status).toBe(201);
    expect(res.body.id).toBeDefined();

    // Verify status updated to 'disputed'
    const entry = await testDb
      .selectFrom('progress_entries')
      .select('moderation_status')
      .where('id', '=', entryId)
      .executeTakeFirstOrThrow();
    expect(entry.moderation_status).toBe('disputed');
  });

  it('returns 201 for own progress entry dispute without status change (hidden entry)', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(user.accessToken);
    const entryId = await createProgressEntry(goalId!, user.userId, 1, '2026-01-01');

    // Status is 'hidden' (not 'removed') — dispute filed but no status change
    await testDb
      .updateTable('progress_entries')
      .set({ moderation_status: 'hidden' })
      .where('id', '=', entryId)
      .execute();

    const res = await request(app)
      .post('/api/disputes')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        content_type: 'progress_entry',
        content_id: entryId,
      });

    expect(res.status).toBe(201);

    // Status should remain 'hidden' (only 'removed' transitions to 'disputed')
    const entry = await testDb
      .selectFrom('progress_entries')
      .select('moderation_status')
      .where('id', '=', entryId)
      .executeTakeFirstOrThrow();
    expect(entry.moderation_status).toBe('hidden');
  });
});
