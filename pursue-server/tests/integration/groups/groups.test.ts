import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, createTestInviteCode, createTestGroupActivity, wait, addMemberToGroup } from '../../helpers';

describe('POST /api/groups', () => {
  it('should create a new group successfully', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: 'Morning Runners',
        description: 'Daily accountability for morning runs',
        icon_emoji: '🏃',
        icon_color: '#1976D2',
      });

    expect(response.status).toBe(201);
    expect(response.body).toMatchObject({
      name: 'Morning Runners',
      description: 'Daily accountability for morning runs',
      icon_emoji: '🏃',
      icon_color: '#1976D2',
      member_count: 1,
    });
    expect(response.body).toHaveProperty('id');
    expect(response.body).toHaveProperty('creator_user_id');
    expect(response.body).toHaveProperty('created_at');
    expect(response.body).toHaveProperty('invite_code');
    expect(response.body.invite_code).toMatch(/^PURSUE-[A-Z0-9]{6}-[A-Z0-9]{6}$/);
  });

  it('should create group with initial goals', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: 'Book Club',
        initial_goals: [
          {
            title: 'Read 50 pages',
            cadence: 'daily',
            metric_type: 'numeric',
            target_value: 50,
            unit: 'pages',
          },
        ],
      });

    expect(response.status).toBe(201);
    expect(response.body.name).toBe('Book Club');

    // Verify goal was created
    const goals = await testDb
      .selectFrom('goals')
      .selectAll()
      .where('group_id', '=', response.body.id)
      .execute();

    expect(goals).toHaveLength(1);
    expect(goals[0]).toMatchObject({
      title: 'Read 50 pages',
      cadence: 'daily',
      metric_type: 'numeric',
      target_value: '50.00', // PostgreSQL DECIMAL returns as string
      unit: 'pages',
    });
  });

  it('should require authentication', async () => {
    const response = await request(app).post('/api/groups').send({
      name: 'Test Group',
    });

    expect(response.status).toBe(401);
  });

  it('should validate input', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: '', // Invalid: empty name
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject group name exceeding max length (>100 chars)', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: 'A'.repeat(150),
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });

  it('should reject group description exceeding max length (>500 chars)', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: 'Test Group',
        description: 'A'.repeat(700),
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('VALIDATION_ERROR');
  });
});

describe('GET /api/groups/:group_id', () => {
  it('should get group details for a member', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // Create group
    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .get(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      id: groupId,
      name: 'Test Group',
      member_count: 1,
      user_role: 'creator',
    });
  });

  it('should return 403 for non-members', async () => {
    const { accessToken: token1 } = await createAuthenticatedUser('user1@example.com');
    const { accessToken: token2 } = await createAuthenticatedUser('user2@example.com');

    // User1 creates group
    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${token1}`)
      .send({ name: 'Private Group' });

    const groupId = createResponse.body.id;

    // User2 tries to access
    const response = await request(app)
      .get(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${token2}`);

    expect(response.status).toBe(403);
  });

  it('should return 404 for non-existent group', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .get('/api/groups/00000000-0000-0000-0000-000000000000')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
  });
});

describe('PATCH /api/groups/:group_id', () => {
  it('should update group metadata as admin', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Original Name' });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: 'Updated Name',
        description: 'New description',
      });

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      name: 'Updated Name',
      description: 'New description',
    });
  });

  it('should return 403 for non-admin members', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: memberToken } = await createAuthenticatedUser('member@example.com');

    // Creator creates group
    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    // Create invite and join as member
    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Member tries to update
    const response = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ name: 'Hacked Name' });

    expect(response.status).toBe(403);
  });
});

describe('DELETE /api/groups/:group_id', () => {
  it('should delete group as creator', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'To Delete' });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .delete(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(204);

    // Verify group is deleted
    const group = await testDb
      .selectFrom('groups')
      .select('id')
      .where('id', '=', groupId)
      .executeTakeFirst();

    expect(group).toBeUndefined();
  });

  it('should return 403 for non-creator', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: adminToken } = await createAuthenticatedUser('admin@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    // Promote member to admin (would need to add member first, but for test we'll just try delete)
    const response = await request(app)
      .delete(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${adminToken}`);

    expect(response.status).toBe(403);
  });
});

describe('GET /api/groups/:group_id/invite', () => {
  it('should return invite code as member', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toHaveProperty('invite_code');
    expect(response.body.invite_code).toMatch(/^PURSUE-[A-Z0-9]{6}-[A-Z0-9]{6}$/);
    expect(response.body).toHaveProperty('share_url');
    expect(response.body).toHaveProperty('created_at');
  });

  it('should return 403 for non-member', async () => {
    const { accessToken: token1 } = await createAuthenticatedUser('user1@example.com');
    const { accessToken: token2 } = await createAuthenticatedUser('user2@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${token1}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${token2}`);

    expect(response.status).toBe(403);
  });
});

describe('POST /api/groups/:group_id/invite/regenerate', () => {
  it('should regenerate invite code as admin', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;
    const getInviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${accessToken}`);
    const oldCode = getInviteRes.body.invite_code;

    const response = await request(app)
      .post(`/api/groups/${groupId}/invite/regenerate`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toHaveProperty('invite_code');
    expect(response.body.invite_code).toMatch(/^PURSUE-[A-Z0-9]{6}-[A-Z0-9]{6}$/);
    expect(response.body.invite_code).not.toBe(oldCode);
    expect(response.body.previous_code_revoked).toBe(oldCode);
  });

  it('should return 403 for regular member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: memberToken } = await createAuthenticatedUser('member@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    await addMemberToGroup(creatorToken, groupId);

    const { memberAccessToken } = await addMemberToGroup(creatorToken, groupId);

    const response = await request(app)
      .post(`/api/groups/${groupId}/invite/regenerate`)
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(response.status).toBe(403);
  });

  it('should make old code return 404 on join after regenerate', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: joinerToken } = await createAuthenticatedUser('joiner@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;
    const getInviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);
    const oldCode = getInviteRes.body.invite_code;

    await request(app)
      .post(`/api/groups/${groupId}/invite/regenerate`)
      .set('Authorization', `Bearer ${creatorToken}`);

    const response = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joinerToken}`)
      .send({ invite_code: oldCode });

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('INVALID_INVITE_CODE');
  });
});

describe('Admin cross-group isolation', () => {
  it('admin of Group A cannot update Group B', async () => {
    const { accessToken: tokenA } = await createAuthenticatedUser('adminA@example.com');
    const { accessToken: tokenB } = await createAuthenticatedUser('adminB@example.com');

    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ name: 'Group A' });

    const createB = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenB}`)
      .send({ name: 'Group B' });
    const groupBId = createB.body.id;

    const response = await request(app)
      .patch(`/api/groups/${groupBId}`)
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ name: 'Hacked' });

    expect(response.status).toBe(403);
  });

  it('admin of Group A cannot get invite for Group B', async () => {
    const { accessToken: tokenA } = await createAuthenticatedUser('adminA@example.com');
    const { accessToken: tokenB } = await createAuthenticatedUser('adminB@example.com');

    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ name: 'Group A' });

    const createB = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenB}`)
      .send({ name: 'Group B' });
    const groupBId = createB.body.id;

    const response = await request(app)
      .get(`/api/groups/${groupBId}/invite`)
      .set('Authorization', `Bearer ${tokenA}`);

    expect(response.status).toBe(403);
  });

  it('admin of Group A cannot update member role in Group B', async () => {
    const { accessToken: tokenA } = await createAuthenticatedUser('adminA@example.com');
    const { accessToken: tokenB } = await createAuthenticatedUser('adminB@example.com');
    const { accessToken: tokenC, userId: userIdC } = await createAuthenticatedUser('memberC@example.com');

    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ name: 'Group A' });

    const createB = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenB}`)
      .send({ name: 'Group B' });
    const groupBId = createB.body.id;

    const inviteRes = await request(app)
      .get(`/api/groups/${groupBId}/invite`)
      .set('Authorization', `Bearer ${tokenB}`);
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${tokenC}`)
      .send({ invite_code: inviteRes.body.invite_code });

    const response = await request(app)
      .patch(`/api/groups/${groupBId}/members/${userIdC}`)
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ role: 'admin' });

    expect(response.status).toBe(403);
  });

  it('admin of Group A cannot remove member from Group B', async () => {
    const { accessToken: tokenA } = await createAuthenticatedUser('adminA@example.com');
    const { accessToken: tokenB } = await createAuthenticatedUser('adminB@example.com');
    const { accessToken: tokenC, userId: userIdC } = await createAuthenticatedUser('memberC@example.com');

    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ name: 'Group A' });

    const createB = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenB}`)
      .send({ name: 'Group B' });
    const groupBId = createB.body.id;

    const inviteRes = await request(app)
      .get(`/api/groups/${groupBId}/invite`)
      .set('Authorization', `Bearer ${tokenB}`);
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${tokenC}`)
      .send({ invite_code: inviteRes.body.invite_code });

    const response = await request(app)
      .delete(`/api/groups/${groupBId}/members/${userIdC}`)
      .set('Authorization', `Bearer ${tokenA}`);

    expect(response.status).toBe(403);
  });

  it('admin of Group A cannot upload icon for Group B', async () => {
    const { accessToken: tokenA } = await createAuthenticatedUser('adminA@example.com');
    const { accessToken: tokenB } = await createAuthenticatedUser('adminB@example.com');

    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ name: 'Group A' });

    const createB = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenB}`)
      .send({ name: 'Group B' });
    const groupBId = createB.body.id;

    const testImage = await createTestImageBuffer();

    const response = await request(app)
      .patch(`/api/groups/${groupBId}/icon`)
      .set('Authorization', `Bearer ${tokenA}`)
      .attach('icon', testImage, 'icon.png');

    expect(response.status).toBe(403);
  });

  it('admin of Group A cannot delete icon for Group B', async () => {
    const { accessToken: tokenA } = await createAuthenticatedUser('adminA@example.com');
    const { accessToken: tokenB } = await createAuthenticatedUser('adminB@example.com');

    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ name: 'Group A' });

    const createB = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenB}`)
      .send({ name: 'Group B' });
    const groupBId = createB.body.id;

    const testImage = await createTestImageBuffer();
    await request(app)
      .patch(`/api/groups/${groupBId}/icon`)
      .set('Authorization', `Bearer ${tokenB}`)
      .attach('icon', testImage, 'icon.png');

    const response = await request(app)
      .delete(`/api/groups/${groupBId}/icon`)
      .set('Authorization', `Bearer ${tokenA}`);

    expect(response.status).toBe(403);
  });

  it('admin of Group A cannot create goal in Group B', async () => {
    const { accessToken: tokenA } = await createAuthenticatedUser('adminA@example.com');
    const { accessToken: tokenB } = await createAuthenticatedUser('adminB@example.com');

    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ name: 'Group A' });

    const createB = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenB}`)
      .send({ name: 'Group B' });
    const groupBId = createB.body.id;

    const response = await request(app)
      .post(`/api/groups/${groupBId}/goals`)
      .set('Authorization', `Bearer ${tokenA}`)
      .send({
        title: 'Hacked goal',
        cadence: 'daily',
        metric_type: 'binary',
        target_value: 1,
      });

    expect(response.status).toBe(403);
  });

  it('admin of Group A cannot delete Group B', async () => {
    const { accessToken: tokenA } = await createAuthenticatedUser('adminA@example.com');
    const { accessToken: tokenB } = await createAuthenticatedUser('adminB@example.com');

    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenA}`)
      .send({ name: 'Group A' });

    const createB = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${tokenB}`)
      .send({ name: 'Group B' });
    const groupBId = createB.body.id;

    const response = await request(app)
      .delete(`/api/groups/${groupBId}`)
      .set('Authorization', `Bearer ${tokenA}`);

    expect(response.status).toBe(403);
  });
});

describe('POST /api/groups/join', () => {
  it('should join group with valid invite code', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: joinerToken } = await createAuthenticatedUser('joiner@example.com');

    // Create group (invite code is auto-created)
    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Joinable Group' });

    const groupId = createResponse.body.id;

    // Join group using invite_code from create response
    const response = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joinerToken}`)
      .send({ invite_code: createResponse.body.invite_code });

    expect(response.status).toBe(200);
    expect(response.body.status).toBe('pending');
    expect(response.body.message).toBe('Join request sent to group admins for approval');
    expect(response.body.group).toMatchObject({
      id: groupId,
      name: 'Joinable Group',
      member_count: 1,
    });

    // Verify membership is pending
    const joiner = await testDb
      .selectFrom('users')
      .select('id')
      .where('email', '=', 'joiner@example.com')
      .executeTakeFirstOrThrow();

    const membership = await testDb
      .selectFrom('group_memberships')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('user_id', '=', joiner.id)
      .executeTakeFirst();

    expect(membership).toBeDefined();
    expect(membership?.role).toBe('member');
    expect(membership?.status).toBe('pending');
  });

  it('should return 404 for invalid invite code', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ invite_code: 'PURSUE-INVALID-CODE' });

    expect(response.status).toBe(404);
  });

  it('should return 409 if already a member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: memberToken } = await createAuthenticatedUser('member@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    // Join first time
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Try to join again
    const response = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    expect(response.status).toBe(409);
    expect(response.body.error.code).toBe('ALREADY_MEMBER');
  });

  it('should return 404 for revoked invite code', async () => {
    const { accessToken: creatorToken, userId: creatorId } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: joinerToken } = await createAuthenticatedUser('joiner@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    // Create a revoked invite code directly in DB (e.g. after regenerate)
    const revokedCode = await createTestInviteCode(groupId, creatorId, {
      revoked_at: new Date(),
    });

    const response = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joinerToken}`)
      .send({ invite_code: revokedCode });

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('INVALID_INVITE_CODE');
  });
});

describe('GET /api/groups/:group_id/members', () => {
  it('should list group members', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    await addMemberToGroup(creatorToken, groupId);

    // List members
    const response = await request(app)
      .get(`/api/groups/${groupId}/members`)
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(200);
    expect(response.body.members).toHaveLength(2);
    expect(response.body.members.some((m: { role: string }) => m.role === 'creator')).toBe(true);
    expect(response.body.members.some((m: { role: string }) => m.role === 'member')).toBe(true);
  });
});

describe('Approval endpoints (approve, decline, GET pending)', () => {
  describe('POST /api/groups/:group_id/members/:user_id/approve', () => {
    it('should approve pending member as creator', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const { accessToken: joinerToken, userId: joinerId } = await createAuthenticatedUser('joiner@example.com');

      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'Approve Test Group' });
      const groupId = createRes.body.id;

      const inviteRes = await request(app)
        .get(`/api/groups/${groupId}/invite`)
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({});
      await request(app)
        .post('/api/groups/join')
        .set('Authorization', `Bearer ${joinerToken}`)
        .send({ invite_code: inviteRes.body.invite_code });

      const response = await request(app)
        .post(`/api/groups/${groupId}/members/${joinerId}/approve`)
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      expect(response.body.success).toBe(true);

      const membership = await testDb
        .selectFrom('group_memberships')
        .select('status')
        .where('group_id', '=', groupId)
        .where('user_id', '=', joinerId)
        .executeTakeFirstOrThrow();
      expect(membership.status).toBe('active');
    });

    it('should approve pending member as admin', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'Admin Approve Group' });
      const groupId = createRes.body.id;

      const { memberUserId: adminId, memberAccessToken: adminToken } = await addMemberToGroup(creatorToken, groupId);
      await request(app)
        .patch(`/api/groups/${groupId}/members/${adminId}`)
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ role: 'admin' });

      const { accessToken: joinerToken, userId: joinerId } = await createAuthenticatedUser('joiner2@example.com');
      const inviteRes = await request(app).get(`/api/groups/${groupId}/invite`).set('Authorization', `Bearer ${creatorToken}`);
      await request(app).post('/api/groups/join').set('Authorization', `Bearer ${joinerToken}`).send({ invite_code: inviteRes.body.invite_code });

      const response = await request(app)
        .post(`/api/groups/${groupId}/members/${joinerId}/approve`)
        .set('Authorization', `Bearer ${adminToken}`);

      expect(response.status).toBe(200);
      expect(response.body.success).toBe(true);
    });

    it('should return 403 when regular member tries to approve (privilege escalation)', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'Priv Esc Group' });
      const groupId = createRes.body.id;

      const { memberAccessToken: memberToken } = await addMemberToGroup(creatorToken, groupId);
      const { accessToken: pendingToken, userId: pendingId } = await createAuthenticatedUser('pending@example.com');
      const inviteRes = await request(app).get(`/api/groups/${groupId}/invite`).set('Authorization', `Bearer ${creatorToken}`);
      await request(app).post('/api/groups/join').set('Authorization', `Bearer ${pendingToken}`).send({ invite_code: inviteRes.body.invite_code });

      const response = await request(app)
        .post(`/api/groups/${groupId}/members/${pendingId}/approve`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(response.status).toBe(403);
      expect(response.body.error?.code).toBe('FORBIDDEN');
    });

    it('should return 403 when admin of Group A tries to approve in Group B (cross-group)', async () => {
      const { accessToken: tokenA } = await createAuthenticatedUser('adminA@example.com');
      const { accessToken: tokenB } = await createAuthenticatedUser('adminB@example.com');
      const { accessToken: tokenC, userId: userIdC } = await createAuthenticatedUser('joinerC@example.com');

      await request(app).post('/api/groups').set('Authorization', `Bearer ${tokenA}`).send({ name: 'Group A' });
      const createB = await request(app).post('/api/groups').set('Authorization', `Bearer ${tokenB}`).send({ name: 'Group B' });
      const groupBId = createB.body.id;

      const inviteRes = await request(app)
.get(`/api/groups/${groupBId}/invite`)
      .set('Authorization', `Bearer ${tokenB}`);
      await request(app)
        .post('/api/groups/join')
        .set('Authorization', `Bearer ${tokenC}`)
        .send({ invite_code: inviteRes.body.invite_code });

      const response = await request(app)
        .post(`/api/groups/${groupBId}/members/${userIdC}/approve`)
        .set('Authorization', `Bearer ${tokenA}`);

      expect(response.status).toBe(403);
      expect(response.body.error?.code).toBe('FORBIDDEN');
    });

    it('should return 404 when target user is not a member of the group', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const { userId: strangerId } = await createAuthenticatedUser('stranger@example.com');
      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'NotFound Group' });
      const groupId = createRes.body.id;

      const response = await request(app)
        .post(`/api/groups/${groupId}/members/${strangerId}/approve`)
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(404);
      expect(response.body.error?.code).toBe('NOT_FOUND');
    });

    it('should return 404 when member is not pending (already active)', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'Already Active Group' });
      const groupId = createRes.body.id;
      const { memberUserId: memberId } = await addMemberToGroup(creatorToken, groupId);

      const response = await request(app)
        .post(`/api/groups/${groupId}/members/${memberId}/approve`)
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(404);
      expect(response.body.error?.code).toBe('NOT_FOUND');
    });
  });

  describe('POST /api/groups/:group_id/members/:user_id/decline', () => {
    it('should decline pending member as creator', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const { accessToken: joinerToken, userId: joinerId } = await createAuthenticatedUser('decline-joiner@example.com');

      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'Decline Test Group' });
      const groupId = createRes.body.id;
      const inviteRes = await request(app).get(`/api/groups/${groupId}/invite`).set('Authorization', `Bearer ${creatorToken}`);
      await request(app).post('/api/groups/join').set('Authorization', `Bearer ${joinerToken}`).send({ invite_code: inviteRes.body.invite_code });

      const response = await request(app)
        .post(`/api/groups/${groupId}/members/${joinerId}/decline`)
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      expect(response.body.success).toBe(true);

      const membership = await testDb
        .selectFrom('group_memberships')
        .select('status')
        .where('group_id', '=', groupId)
        .where('user_id', '=', joinerId)
        .executeTakeFirstOrThrow();
      expect(membership.status).toBe('declined');
    });

    it('should return 403 when regular member tries to decline (privilege escalation)', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'Decline Priv Group' });
      const groupId = createRes.body.id;
      const { memberAccessToken: memberToken } = await addMemberToGroup(creatorToken, groupId);
      const { accessToken: pendingToken, userId: pendingId } = await createAuthenticatedUser('decline-pending@example.com');
      const inviteRes = await request(app).get(`/api/groups/${groupId}/invite`).set('Authorization', `Bearer ${creatorToken}`);
      await request(app).post('/api/groups/join').set('Authorization', `Bearer ${pendingToken}`).send({ invite_code: inviteRes.body.invite_code });

      const response = await request(app)
        .post(`/api/groups/${groupId}/members/${pendingId}/decline`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(response.status).toBe(403);
      expect(response.body.error?.code).toBe('FORBIDDEN');
    });

    it('should return 403 when admin of Group A tries to decline in Group B (cross-group)', async () => {
      const { accessToken: tokenA } = await createAuthenticatedUser('declineAdminA@example.com');
      const { accessToken: tokenB } = await createAuthenticatedUser('declineAdminB@example.com');
      const { accessToken: tokenC, userId: userIdC } = await createAuthenticatedUser('declineJoinerC@example.com');

      await request(app).post('/api/groups').set('Authorization', `Bearer ${tokenA}`).send({ name: 'Decline Group A' });
      const createB = await request(app).post('/api/groups').set('Authorization', `Bearer ${tokenB}`).send({ name: 'Decline Group B' });
      const groupBId = createB.body.id;
      const inviteRes = await request(app).get(`/api/groups/${groupBId}/invite`).set('Authorization', `Bearer ${tokenB}`);
      await request(app).post('/api/groups/join').set('Authorization', `Bearer ${tokenC}`).send({ invite_code: inviteRes.body.invite_code });

      const response = await request(app)
        .post(`/api/groups/${groupBId}/members/${userIdC}/decline`)
        .set('Authorization', `Bearer ${tokenA}`);

      expect(response.status).toBe(403);
      expect(response.body.error?.code).toBe('FORBIDDEN');
    });

    it('should return 404 when target user is not a member of the group', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const { userId: strangerId } = await createAuthenticatedUser('declineStranger@example.com');
      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'Decline NotFound Group' });
      const groupId = createRes.body.id;

      const response = await request(app)
        .post(`/api/groups/${groupId}/members/${strangerId}/decline`)
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(404);
      expect(response.body.error?.code).toBe('NOT_FOUND');
    });
  });

  describe('GET /api/groups/:group_id/members/pending', () => {
    it('should list pending members as creator', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const { accessToken: joinerToken, userId: joinerId } = await createAuthenticatedUser('pending-list-joiner@example.com');

      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'Pending List Group' });
      const groupId = createRes.body.id;
      const inviteRes = await request(app).get(`/api/groups/${groupId}/invite`).set('Authorization', `Bearer ${creatorToken}`);
      await request(app).post('/api/groups/join').set('Authorization', `Bearer ${joinerToken}`).send({ invite_code: inviteRes.body.invite_code });

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/pending`)
        .set('Authorization', `Bearer ${creatorToken}`);

      expect(response.status).toBe(200);
      expect(response.body.pending_members).toHaveLength(1);
      expect(response.body.pending_members[0].user_id).toBe(joinerId);
    });

    it('should return 403 when regular member tries to list pending (privilege escalation)', async () => {
      const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
      const createRes = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${creatorToken}`)
        .send({ name: 'Pending Priv Group' });
      const groupId = createRes.body.id;
      const { memberAccessToken: memberToken } = await addMemberToGroup(creatorToken, groupId);

      const response = await request(app)
        .get(`/api/groups/${groupId}/members/pending`)
        .set('Authorization', `Bearer ${memberToken}`);

      expect(response.status).toBe(403);
      expect(response.body.error?.code).toBe('FORBIDDEN');
    });

    it('should return 403 when admin of Group A tries to list pending in Group B (cross-group)', async () => {
      const { accessToken: tokenA } = await createAuthenticatedUser('pendingListA@example.com');
      const { accessToken: tokenB } = await createAuthenticatedUser('pendingListB@example.com');

      await request(app).post('/api/groups').set('Authorization', `Bearer ${tokenA}`).send({ name: 'Pending List Group A' });
      const createB = await request(app).post('/api/groups').set('Authorization', `Bearer ${tokenB}`).send({ name: 'Pending List Group B' });
      const groupBId = createB.body.id;

      const response = await request(app)
        .get(`/api/groups/${groupBId}/members/pending`)
        .set('Authorization', `Bearer ${tokenA}`);

      expect(response.status).toBe(403);
      expect(response.body.error?.code).toBe('FORBIDDEN');
    });
  });
});

describe('Pending member restrictions', () => {
  it('pending member does not count towards group member_count', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: joinerToken } = await createAuthenticatedUser('joiner@example.com');

    const createRes = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Member Count Group' });
    const groupId = createRes.body.id;

    const getBefore = await request(app)
      .get(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${creatorToken}`);
    expect(getBefore.status).toBe(200);
    expect(getBefore.body.member_count).toBe(1);

    const inviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({});
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joinerToken}`)
      .send({ invite_code: inviteRes.body.invite_code });

    const getAfter = await request(app)
      .get(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${creatorToken}`);
    expect(getAfter.status).toBe(200);
    expect(getAfter.body.member_count).toBe(1);
  });

  it('pending member cannot list group members', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: joinerToken } = await createAuthenticatedUser('joiner-members@example.com');

    const createRes = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Members Restriction Group' });
    const groupId = createRes.body.id;

    const inviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({});
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joinerToken}`)
      .send({ invite_code: inviteRes.body.invite_code });

    const response = await request(app)
      .get(`/api/groups/${groupId}/members`)
      .set('Authorization', `Bearer ${joinerToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('PENDING_APPROVAL');
    expect(response.body.error?.message).toContain('pending approval');
  });

  it('pending member cannot get group activity', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: joinerToken } = await createAuthenticatedUser('joiner-activity@example.com');

    const createRes = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Activity Restriction Group' });
    const groupId = createRes.body.id;

    const inviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({});
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joinerToken}`)
      .send({ invite_code: inviteRes.body.invite_code });

    const response = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${joinerToken}`);

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('PENDING_APPROVAL');
    expect(response.body.error?.message).toContain('pending approval');
  });
});

describe('PATCH /api/groups/:group_id/members/:user_id', () => {
  it('should promote member to admin as creator', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const { memberUserId: memberId } = await addMemberToGroup(creatorToken, groupId);

    // Promote to admin
    const response = await request(app)
      .patch(`/api/groups/${groupId}/members/${memberId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    expect(response.status).toBe(200);
    expect(response.body.success).toBe(true);

    // Verify role changed
    const membership = await testDb
      .selectFrom('group_memberships')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('user_id', '=', memberId)
      .executeTakeFirstOrThrow();

    expect(membership.role).toBe('admin');
  });

  it('should promote member to admin as admin', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const { memberUserId: adminId, memberAccessToken: adminToken } = await addMemberToGroup(creatorToken, groupId);

    // Promote first member to admin
    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    const { memberUserId: memberId } = await addMemberToGroup(creatorToken, groupId);

    // Admin promotes member
    const response = await request(app)
      .patch(`/api/groups/${groupId}/members/${memberId}`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ role: 'admin' });

    expect(response.status).toBe(200);
  });

  it('should demote admin to member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const { memberUserId: adminId } = await addMemberToGroup(creatorToken, groupId);

    // Promote to admin
    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    // Demote back to member
    const response = await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'member' });

    expect(response.status).toBe(200);

    const membership = await testDb
      .selectFrom('group_memberships')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('user_id', '=', adminId)
      .executeTakeFirstOrThrow();

    expect(membership.role).toBe('member');
  });

  it('should return 403 if requester is not admin/creator', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const { memberUserId: member1Id, memberAccessToken: member1Token } = await addMemberToGroup(creatorToken, groupId);
    const { memberUserId: member2Id } = await addMemberToGroup(creatorToken, groupId);

    // Member1 tries to promote member2
    const response = await request(app)
      .patch(`/api/groups/${groupId}/members/${member2Id}`)
      .set('Authorization', `Bearer ${member1Token}`)
      .send({ role: 'admin' });

    expect(response.status).toBe(403);
  });

  it('should return 400 if trying to change own role', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const { memberUserId: adminId, memberAccessToken: adminToken } = await addMemberToGroup(creatorToken, groupId);

    // Promote to admin
    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    // Admin tries to change own role
    const response = await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ role: 'member' });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('CANNOT_CHANGE_OWN_ROLE');
  });

  it('should return 400 if trying to change creator role', async () => {
    const { accessToken: creatorToken, userId: creatorId } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const { memberUserId: adminId, memberAccessToken: adminToken } = await addMemberToGroup(creatorToken, groupId);

    // Promote to admin
    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    // Admin tries to change creator role
    const response = await request(app)
      .patch(`/api/groups/${groupId}/members/${creatorId}`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ role: 'admin' });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('CANNOT_DEMOTE_CREATOR');
  });

  it('should return 404 if target user is not a member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: nonMemberToken, userId: nonMemberId } = await createAuthenticatedUser('nonmember@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    // Try to change role of non-member
    const response = await request(app)
      .patch(`/api/groups/${groupId}/members/${nonMemberId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });
});

describe('DELETE /api/groups/:group_id/members/:user_id', () => {
  it('should remove member as admin', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: adminToken, userId: adminId } = await createAuthenticatedUser('admin@example.com');
    const { accessToken: memberToken, userId: memberId } = await createAuthenticatedUser('member@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    const inviteCode = inviteResponse.body.invite_code;

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ invite_code: inviteCode });

    // Promote admin
    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteCode });

    // Admin removes member
    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/${memberId}`)
      .set('Authorization', `Bearer ${adminToken}`);

    expect(response.status).toBe(204);

    // Verify membership deleted
    const membership = await testDb
      .selectFrom('group_memberships')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('user_id', '=', memberId)
      .executeTakeFirst();

    expect(membership).toBeUndefined();

    // Verify activity created
    const activities = await testDb
      .selectFrom('group_activities')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('activity_type', '=', 'member_removed')
      .execute();

    expect(activities.length).toBeGreaterThan(0);
  });

  it('should remove member as creator', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: memberToken, userId: memberId } = await createAuthenticatedUser('member@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Creator removes member
    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/${memberId}`)
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(204);
  });

  it('should return 403 if requester is not admin/creator', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: member1Token } = await createAuthenticatedUser('member1@example.com');
    const { accessToken: member2Token, userId: member2Id } = await createAuthenticatedUser('member2@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    const inviteCode = inviteResponse.body.invite_code;

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member1Token}`)
      .send({ invite_code: inviteCode });

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member2Token}`)
      .send({ invite_code: inviteCode });

    // Member1 tries to remove member2
    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/${member2Id}`)
      .set('Authorization', `Bearer ${member1Token}`);

    expect(response.status).toBe(403);
  });

  it('should return 400 if trying to remove creator', async () => {
    const { accessToken: creatorToken, userId: creatorId } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: adminToken, userId: adminId } = await createAuthenticatedUser('admin@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Promote to admin
    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    // Admin tries to remove creator
    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/${creatorId}`)
      .set('Authorization', `Bearer ${adminToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('CANNOT_REMOVE_CREATOR');
  });

  it('should return 404 if target user is not a member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: nonMemberToken, userId: nonMemberId } = await createAuthenticatedUser('nonmember@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    // Try to remove non-member
    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/${nonMemberId}`)
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });
});

describe('DELETE /api/groups/:group_id/members/me', () => {
  it('should leave group as regular member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: memberToken, userId: memberId } = await createAuthenticatedUser('member@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Leave group
    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/me`)
      .set('Authorization', `Bearer ${memberToken}`);

    expect(response.status).toBe(204);

    // Verify membership deleted
    const membership = await testDb
      .selectFrom('group_memberships')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('user_id', '=', memberId)
      .executeTakeFirst();

    expect(membership).toBeUndefined();

    // Verify activity created
    const activities = await testDb
      .selectFrom('group_activities')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('activity_type', '=', 'member_left')
      .execute();

    expect(activities.length).toBeGreaterThan(0);
  });

  it('should leave group as admin', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: adminToken, userId: adminId } = await createAuthenticatedUser('admin@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Promote to admin
    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    // Admin leaves
    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/me`)
      .set('Authorization', `Bearer ${adminToken}`);

    expect(response.status).toBe(204);
  });

  it('should auto-promote when creator leaves with other active members', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Test Group' });

    const groupId = createResponse.body.id;

    const { memberUserId: promotedId } = await addMemberToGroup(creatorToken, groupId);

    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/me`)
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(204);

    const group = await testDb
      .selectFrom('groups')
      .select(['id', 'creator_user_id'])
      .where('id', '=', groupId)
      .executeTakeFirst();
    expect(group).toBeDefined();
    expect(group!.creator_user_id).toBe(promotedId);

    const memberships = await testDb
      .selectFrom('group_memberships')
      .selectAll()
      .where('group_id', '=', groupId)
      .execute();
    expect(memberships).toHaveLength(1);
    expect(memberships[0].user_id).toBe(promotedId);
    expect(memberships[0].role).toBe('creator');

    const promotedActivities = await testDb
      .selectFrom('group_activities')
      .selectAll()
      .where('group_id', '=', groupId)
      .where('activity_type', '=', 'member_promoted')
      .execute();
    expect(promotedActivities.length).toBeGreaterThanOrEqual(1);
    const autoPromoted = promotedActivities.find(
      (a) => a.metadata && typeof a.metadata === 'object' && (a.metadata as { reason?: string }).reason === 'auto_last_admin_left'
    );
    expect(autoPromoted).toBeDefined();
    expect((autoPromoted!.metadata as { new_role?: string }).new_role).toBe('creator');
  });

  it('should delete group when last member leaves (any role)', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Last Member Group' });

    const groupId = createResponse.body.id;

    const { memberAccessToken: memberToken } = await addMemberToGroup(creatorToken, groupId);

    await request(app)
      .delete(`/api/groups/${groupId}/members/me`)
      .set('Authorization', `Bearer ${creatorToken}`);

    const leaveResponse = await request(app)
      .delete(`/api/groups/${groupId}/members/me`)
      .set('Authorization', `Bearer ${memberToken}`);

    expect(leaveResponse.status).toBe(204);

    const group = await testDb
      .selectFrom('groups')
      .select('id')
      .where('id', '=', groupId)
      .executeTakeFirst();
    expect(group).toBeUndefined();

    const memberships = await testDb
      .selectFrom('group_memberships')
      .select('id')
      .where('group_id', '=', groupId)
      .execute();
    expect(memberships).toHaveLength(0);

    const inviteCodes = await testDb
      .selectFrom('invite_codes')
      .select('id')
      .where('group_id', '=', groupId)
      .execute();
    expect(inviteCodes).toHaveLength(0);

    const activities = await testDb
      .selectFrom('group_activities')
      .select('id')
      .where('group_id', '=', groupId)
      .execute();
    expect(activities).toHaveLength(0);
  });

  it('should delete group if creator is sole member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Sole Member Group' });

    const groupId = createResponse.body.id;

    // Creator leaves (sole member)
    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/me`)
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(204);

    // Verify group is deleted
    const group = await testDb
      .selectFrom('groups')
      .select('id')
      .where('id', '=', groupId)
      .executeTakeFirst();

    expect(group).toBeUndefined();
  });

  it('should auto-promote when last admin leaves with one active member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Admin Leave Group' });

    const groupId = createResponse.body.id;

    const { memberUserId: memberId } = await addMemberToGroup(creatorToken, groupId);
    const { memberUserId: adminId, memberAccessToken: adminToken } = await addMemberToGroup(creatorToken, groupId);

    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/me`)
      .set('Authorization', `Bearer ${adminToken}`);

    expect(response.status).toBe(204);

    const memberships = await testDb
      .selectFrom('group_memberships')
      .selectAll()
      .where('group_id', '=', groupId)
      .execute();
    expect(memberships).toHaveLength(2);
    const creatorMembership = memberships.find((m) => m.role === 'creator');
    const memberMembership = memberships.find((m) => m.user_id === memberId);
    expect(creatorMembership).toBeDefined();
    expect(memberMembership).toBeDefined();
    expect(memberMembership!.role).toBe('member');
  });

  it('should auto-promote earlier-joined member when two members have similar activity (tie-break)', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Tie-break Group' });

    const groupId = createResponse.body.id;

    const { memberUserId: firstId } = await addMemberToGroup(creatorToken, groupId);
    const { memberUserId: secondId } = await addMemberToGroup(creatorToken, groupId);

    const membersBefore = await testDb
      .selectFrom('group_memberships')
      .select(['user_id', 'joined_at'])
      .where('group_id', '=', groupId)
      .where('status', '=', 'active')
      .where('user_id', 'in', [firstId, secondId])
      .orderBy('joined_at', 'asc')
      .execute();

    const earlierJoinedId = membersBefore[0].user_id;

    const response = await request(app)
      .delete(`/api/groups/${groupId}/members/me`)
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(204);

    const remaining = await testDb
      .selectFrom('group_memberships')
      .selectAll()
      .where('group_id', '=', groupId)
      .execute();
    expect(remaining).toHaveLength(2);
    const promoted = remaining.find((m) => m.role === 'creator');
    expect(promoted).toBeDefined();
    expect(promoted!.user_id).toBe(earlierJoinedId);
  });
});

describe('GET /api/groups/:group_id/activity', () => {
  it('should return activity feed', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Activity Group' });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toHaveProperty('activities');
    expect(response.body).toHaveProperty('total');
    expect(Array.isArray(response.body.activities)).toBe(true);
    // Should have at least group_created activity
    expect(response.body.total).toBeGreaterThanOrEqual(1);
  });

  it('should paginate with limit', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { accessToken: member1Token, userId: member1Id } = await createAuthenticatedUser('member1@example.com');
    const { accessToken: member2Token, userId: member2Id } = await createAuthenticatedUser('member2@example.com');
    const { accessToken: member3Token, userId: member3Id } = await createAuthenticatedUser('member3@example.com');
    const { accessToken: member4Token, userId: member4Id } = await createAuthenticatedUser('member4@example.com');
    const { accessToken: member5Token, userId: member5Id } = await createAuthenticatedUser('member5@example.com');
    const { accessToken: member6Token, userId: member6Id } = await createAuthenticatedUser('member6@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Pagination Group' });

    const groupId = createResponse.body.id;

    // Create multiple activities by adding members
    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${accessToken}`);

    const inviteCode = inviteResponse.body.invite_code;

    // Add 6 members to create 6 member_joined activities
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member1Token}`)
      .send({ invite_code: inviteCode });
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member2Token}`)
      .send({ invite_code: inviteCode });
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member3Token}`)
      .send({ invite_code: inviteCode });
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member4Token}`)
      .send({ invite_code: inviteCode });
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member5Token}`)
      .send({ invite_code: inviteCode });
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member6Token}`)
      .send({ invite_code: inviteCode });

    // Request with limit=5
    const response = await request(app)
      .get(`/api/groups/${groupId}/activity?limit=5`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.activities.length).toBeLessThanOrEqual(5);
    expect(response.body.total).toBeGreaterThanOrEqual(7); // group_created + 6 member_joined
  });

  it('should paginate with offset', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { accessToken: member1Token } = await createAuthenticatedUser('member1@example.com');
    const { accessToken: member2Token } = await createAuthenticatedUser('member2@example.com');
    const { accessToken: member3Token } = await createAuthenticatedUser('member3@example.com');
    const { accessToken: member4Token } = await createAuthenticatedUser('member4@example.com');
    const { accessToken: member5Token } = await createAuthenticatedUser('member5@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Offset Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${accessToken}`);

    const inviteCode = inviteResponse.body.invite_code;

    // Add 5 members
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member1Token}`)
      .send({ invite_code: inviteCode });
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member2Token}`)
      .send({ invite_code: inviteCode });
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member3Token}`)
      .send({ invite_code: inviteCode });
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member4Token}`)
      .send({ invite_code: inviteCode });
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member5Token}`)
      .send({ invite_code: inviteCode });

    // Get first page
    const firstPage = await request(app)
      .get(`/api/groups/${groupId}/activity?limit=3&offset=0`)
      .set('Authorization', `Bearer ${accessToken}`);

    // Get second page
    const secondPage = await request(app)
      .get(`/api/groups/${groupId}/activity?limit=3&offset=3`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(firstPage.status).toBe(200);
    expect(secondPage.status).toBe(200);
    expect(firstPage.body.activities.length).toBeLessThanOrEqual(3);
    expect(secondPage.body.activities.length).toBeLessThanOrEqual(3);
    // Verify different activities
    const firstIds = firstPage.body.activities.map((a: { id: string }) => a.id);
    const secondIds = secondPage.body.activities.map((a: { id: string }) => a.id);
    expect(firstIds).not.toEqual(secondIds);
  });

  it('should order activities by created_at descending', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { accessToken: member1Token, userId: member1Id } = await createAuthenticatedUser('member1@example.com');
    const { accessToken: member2Token, userId: member2Id } = await createAuthenticatedUser('member2@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Ordering Group' });

    const groupId = createResponse.body.id;

    // Create activities with delays to ensure different timestamps
    await createTestGroupActivity(groupId, userId, 'group_created');
    await wait(10);
    await createTestGroupActivity(groupId, member1Id, 'member_joined');
    await wait(10);
    await createTestGroupActivity(groupId, member2Id, 'member_joined');

    const response = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    const activities = response.body.activities;
    expect(activities.length).toBeGreaterThanOrEqual(3);

    // Verify descending order
    for (let i = 0; i < activities.length - 1; i++) {
      const current = new Date(activities[i].created_at).getTime();
      const next = new Date(activities[i + 1].created_at).getTime();
      expect(current).toBeGreaterThanOrEqual(next);
    }
  });

  it('should use default limit of 50', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Limit Group' });

    const groupId = createResponse.body.id;

    // Create 60 activities
    for (let i = 0; i < 60; i++) {
      await createTestGroupActivity(groupId, userId, 'member_joined', { index: i });
    }

    const response = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.activities.length).toBeLessThanOrEqual(50);
    expect(response.body.total).toBeGreaterThanOrEqual(60);
  });

  it('should enforce max limit of 100', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Max Limit Group' });

    const groupId = createResponse.body.id;

    // Create 150 activities
    for (let i = 0; i < 150; i++) {
      await createTestGroupActivity(groupId, userId, 'member_joined', { index: i });
    }

    const response = await request(app)
      .get(`/api/groups/${groupId}/activity?limit=200`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.activities.length).toBeLessThanOrEqual(100);
    expect(response.body.total).toBeGreaterThanOrEqual(150);
  });

  it('should return correct total count', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Total Count Group' });

    const groupId = createResponse.body.id;

    // Create exactly 25 activities
    for (let i = 0; i < 25; i++) {
      await createTestGroupActivity(groupId, userId, 'member_joined', { index: i });
    }

    const response = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.total).toBe(26); // group_created + 25 member_joined
  });
});

import sharp from 'sharp';

/**
 * Create a valid test image buffer using sharp
 */
async function createTestImageBuffer(): Promise<Buffer> {
  // Create a small 10x10 red PNG using sharp
  return sharp({
    create: {
      width: 10,
      height: 10,
      channels: 3,
      background: { r: 255, g: 0, b: 0 },
    },
  })
    .png()
    .toBuffer();
}

describe('PATCH /api/groups/:group_id/icon', () => {
  it('should upload icon successfully as admin', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: adminToken, userId: adminId } = await createAuthenticatedUser('admin@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Icon Group', icon_emoji: '🏃', icon_color: '#1976D2' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Promote to admin
    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    const testImage = await createTestImageBuffer();

    const response = await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${adminToken}`)
      .attach('icon', testImage, 'icon.png');

    expect(response.status).toBe(200);
    expect(response.body.has_icon).toBe(true);
    expect(response.body.icon_emoji).toBeNull();
    expect(response.body.icon_color).toBeNull();

    // Verify icon stored in database
    const group = await testDb
      .selectFrom('groups')
      .select(['icon_data', 'icon_mime_type'])
      .where('id', '=', groupId)
      .executeTakeFirstOrThrow();

    expect(group.icon_data).not.toBeNull();
    expect(group.icon_mime_type).toBe('image/webp');
  });

  it('should upload icon successfully as creator', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;
    const testImage = await createTestImageBuffer();

    const response = await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`)
      .attach('icon', testImage, 'icon.png');

    expect(response.status).toBe(200);
    expect(response.body.has_icon).toBe(true);
  });

  it('should return 403 if not admin/creator', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: memberToken } = await createAuthenticatedUser('member@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    const testImage = await createTestImageBuffer();

    const response = await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${memberToken}`)
      .attach('icon', testImage, 'icon.png');

    expect(response.status).toBe(403);
  });

  it('should return 400 if no file provided', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('MISSING_FILE');
  });

  it('should return 400 if invalid file type', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;
    const invalidFile = Buffer.from('not an image');

    const response = await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`)
      .attach('icon', invalidFile, 'file.txt');

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_FILE_TYPE');
  });

  it('should replace old icon when uploading new one', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;
    const testImage1 = await createTestImageBuffer();
    // Create a different colored image for the second upload
    const testImage2 = await sharp({
      create: {
        width: 10,
        height: 10,
        channels: 3,
        background: { r: 0, g: 255, b: 0 }, // Green instead of red
      },
    }).png().toBuffer();

    // Upload first icon
    await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`)
      .attach('icon', testImage1, 'icon1.png');

    // Get first icon data
    const group1 = await testDb
      .selectFrom('groups')
      .select('icon_data')
      .where('id', '=', groupId)
      .executeTakeFirstOrThrow();

    // Upload second icon
    await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`)
      .attach('icon', testImage2, 'icon2.png');

    // Get second icon data
    const group2 = await testDb
      .selectFrom('groups')
      .select('icon_data')
      .where('id', '=', groupId)
      .executeTakeFirstOrThrow();

    // Verify icon data changed
    expect(group2.icon_data).not.toEqual(group1.icon_data);
  });
});

describe('GET /api/groups/:group_id/icon', () => {
  it('should fetch icon successfully as member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: memberToken } = await createAuthenticatedUser('member@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Upload icon first
    const testImage = await createTestImageBuffer();
    await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .attach('icon', testImage, 'icon.png');

    // Fetch icon
    const response = await request(app)
      .get(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${memberToken}`);

    expect(response.status).toBe(200);
    expect(response.headers['content-type']).toContain('image/webp');
    expect(response.headers['cache-control']).toBe('public, max-age=86400');
    expect(response.headers['etag']).toBeDefined();
    expect(Buffer.isBuffer(response.body)).toBe(true);
    expect(response.body.length).toBeGreaterThan(0);
  });

  it('should return 404 if group has no icon', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'No Icon Group' });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .get(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });

  it('should return 403 if not a member', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: nonMemberToken } = await createAuthenticatedUser('nonmember@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;

    const testImage = await createTestImageBuffer();
    await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .attach('icon', testImage, 'icon.png');

    const response = await request(app)
      .get(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${nonMemberToken}`);

    expect(response.status).toBe(403);
  });

  it('should return 304 Not Modified with matching ETag', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;

    const testImage = await createTestImageBuffer();
    await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`)
      .attach('icon', testImage, 'icon.png');

    // First request to get ETag
    const firstResponse = await request(app)
      .get(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(firstResponse.status).toBe(200);
    const etag = firstResponse.headers['etag'];

    // Second request with If-None-Match
    const secondResponse = await request(app)
      .get(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`)
      .set('If-None-Match', etag);

    expect(secondResponse.status).toBe(304);
  });
});

describe('DELETE /api/groups/:group_id/icon', () => {
  it('should delete icon successfully as admin', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: adminToken, userId: adminId } = await createAuthenticatedUser('admin@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Promote to admin
    await request(app)
      .patch(`/api/groups/${groupId}/members/${adminId}`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ role: 'admin' });

    // Upload icon first
    const testImage = await createTestImageBuffer();
    await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .attach('icon', testImage, 'icon.png');

    // Delete icon
    const response = await request(app)
      .delete(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${adminToken}`);

    expect(response.status).toBe(200);
    expect(response.body.has_icon).toBe(false);

    // Verify icon_data is NULL
    const group = await testDb
      .selectFrom('groups')
      .select('icon_data')
      .where('id', '=', groupId)
      .executeTakeFirstOrThrow();

    expect(group.icon_data).toBeNull();
  });

  it('should delete icon successfully as creator', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;

    const testImage = await createTestImageBuffer();
    await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`)
      .attach('icon', testImage, 'icon.png');

    const response = await request(app)
      .delete(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.has_icon).toBe(false);
  });

  it('should return 403 if not admin/creator', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: memberToken } = await createAuthenticatedUser('member@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Icon Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    const testImage = await createTestImageBuffer();
    await request(app)
      .patch(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${creatorToken}`)
      .attach('icon', testImage, 'icon.png');

    const response = await request(app)
      .delete(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${memberToken}`);

    expect(response.status).toBe(403);
  });

  it('should return 200 even if no icon exists', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'No Icon Group' });

    const groupId = createResponse.body.id;

    // Try to delete non-existent icon
    const response = await request(app)
      .delete(`/api/groups/${groupId}/icon`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.has_icon).toBe(false);
  });
});
