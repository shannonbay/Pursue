import request from 'supertest';
import { sql } from 'kysely';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, createGoogleUser } from '../../helpers';
import { generateAccessToken } from '../../../src/utils/jwt.js';
import { uploadUserAvatar } from '../../../src/services/storage.service.js';
import sharp from 'sharp';

describe('GET /api/users/me', () => {
  it('should return user profile with valid token', async () => {
    const { accessToken, userId, user } = await createAuthenticatedUser();

    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      id: userId,
      email: user.email,
      display_name: user.display_name,
      has_avatar: false,
    });
    expect(response.body).toHaveProperty('created_at');
  });

  it('should return has_avatar true when user has avatar', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // Create a test avatar
    const testImage = await sharp({
      create: {
        width: 10,
        height: 10,
        channels: 3,
        background: { r: 255, g: 0, b: 0 },
      },
    })
      .png()
      .toBuffer();

    await uploadUserAvatar(userId, testImage);

    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.has_avatar).toBe(true);
  });

  it('should return has_avatar false when user has no avatar', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.has_avatar).toBe(false);
  });

  it('should return 401 without Authorization header', async () => {
    const response = await request(app).get('/api/users/me');

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('UNAUTHORIZED');
  });

  it('should return 401 with invalid token format', async () => {
    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', 'invalid-token'); // Missing "Bearer "

    expect(response.status).toBe(401);
  });

  it('should return 401 with invalid JWT token', async () => {
    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', 'Bearer invalid-token-here');

    expect(response.status).toBe(401);
    expect(response.body.error.code).toBe('INVALID_TOKEN');
  });

  it('should return 404 for soft-deleted user', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // Soft delete the user
    await testDb
      .updateTable('users')
      .set({ deleted_at: sql`NOW()` })
      .where('id', '=', userId)
      .execute();

    const response = await request(app)
      .get('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error.code).toBe('NOT_FOUND');
  });
});

describe('PATCH /api/users/me', () => {
  it('should update display_name successfully', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    const response = await request(app)
      .patch('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        display_name: 'Updated Name',
      });

    expect(response.status).toBe(200);
    expect(response.body.display_name).toBe('Updated Name');
    expect(response.body.id).toBe(userId);

    // Verify database was updated
    const user = await testDb
      .selectFrom('users')
      .select(['display_name', 'updated_at'])
      .where('id', '=', userId)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    expect(user?.display_name).toBe('Updated Name');
    expect(user?.updated_at).toBeDefined();
  });

  it('should return current user when no updates provided', async () => {
    const { accessToken, userId, user } = await createAuthenticatedUser();

    const response = await request(app)
      .patch('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({});

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      id: userId,
      email: user.email,
      display_name: user.display_name,
    });
  });

  it('should return 400 for empty display_name', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .patch('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        display_name: '',
      });

    expect(response.status).toBe(400);
  });

  it('should return 400 for display_name > 100 characters', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .patch('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        display_name: 'A'.repeat(101),
      });

    expect(response.status).toBe(400);
  });

  it('should return 400 for invalid input (extra fields)', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .patch('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        display_name: 'Valid Name',
        invalid_field: 'should not be here',
      });

    expect(response.status).toBe(400);
  });

  it('should return 401 without token', async () => {
    const response = await request(app)
      .patch('/api/users/me')
      .send({
        display_name: 'Test',
      });

    expect(response.status).toBe(401);
  });

  it('should return 404 for soft-deleted user', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // Soft delete the user
    await testDb
      .updateTable('users')
      .set({ deleted_at: sql`NOW()` })
      .where('id', '=', userId)
      .execute();

    const response = await request(app)
      .patch('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        display_name: 'Updated',
      });

    expect(response.status).toBe(404);
  });
});

describe('POST /api/users/me/password', () => {
  it('should change password successfully', async () => {
    const { accessToken, userId, user } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/users/me/password')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        current_password: 'Test123!@#',
        new_password: 'NewPassword456!',
      });

    expect(response.status).toBe(200);
    expect(response.body.success).toBe(true);

    // Verify new password works
    const loginResponse = await request(app)
      .post('/api/auth/login')
      .send({
        email: user.email,
        password: 'NewPassword456!',
      });

    expect(loginResponse.status).toBe(200);

    // Verify old password no longer works
    const oldLoginResponse = await request(app)
      .post('/api/auth/login')
      .send({
        email: user.email,
        password: 'Test123!@#',
      });

    expect(oldLoginResponse.status).toBe(401);
  });

  it('should set password for Google-only user', async () => {
    const googleUser = await createGoogleUser('google@example.com', 'google-123', 'Google User');
    const accessToken = generateAccessToken(googleUser.id, googleUser.email);

    const response = await request(app)
      .post('/api/users/me/password')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        current_password: null,
        new_password: 'NewPassword789!',
      });

    expect(response.status).toBe(200);

    // Verify password works for login
    const loginResponse = await request(app)
      .post('/api/auth/login')
      .send({
        email: googleUser.email,
        password: 'NewPassword789!',
      });

    expect(loginResponse.status).toBe(200);

    // Verify email auth_provider was created
    const provider = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', googleUser.id)
      .where('provider', '=', 'email')
      .executeTakeFirst();

    expect(provider).toBeDefined();
  });

  it('should return 400 when current_password is missing for user with password', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/users/me/password')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        new_password: 'NewPassword456!',
      });

    expect(response.status).toBe(400);
  });

  it('should return 400 when current_password is null for user with password', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/users/me/password')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        current_password: null,
        new_password: 'NewPassword456!',
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('PASSWORD_REQUIRED');
  });

  it('should return 400 for invalid current_password', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/users/me/password')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        current_password: 'WrongPassword123!',
        new_password: 'NewPassword456!',
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_PASSWORD');
  });

  it('should return 400 for weak new_password', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/users/me/password')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        current_password: 'Test123!@#',
        new_password: 'short',
      });

    expect(response.status).toBe(400);
  });

  it('should return 400 for new_password > 100 characters', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/users/me/password')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        current_password: 'Test123!@#',
        new_password: 'A'.repeat(101),
      });

    expect(response.status).toBe(400);
  });

  it('should return 401 without token', async () => {
    const response = await request(app)
      .post('/api/users/me/password')
      .send({
        current_password: 'Test123!@#',
        new_password: 'NewPassword456!',
      });

    expect(response.status).toBe(401);
  });

  it('should create email auth_provider when setting password', async () => {
    const googleUser = await createGoogleUser('google2@example.com', 'google-456', 'Google User 2');
    const accessToken = generateAccessToken(googleUser.id, googleUser.email);

    // Verify no email provider exists
    const providerBefore = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', googleUser.id)
      .where('provider', '=', 'email')
      .executeTakeFirst();

    expect(providerBefore).toBeUndefined();

    // Set password
    await request(app)
      .post('/api/users/me/password')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        current_password: null,
        new_password: 'NewPassword789!',
      });

    // Verify email provider was created
    const providerAfter = await testDb
      .selectFrom('auth_providers')
      .selectAll()
      .where('user_id', '=', googleUser.id)
      .where('provider', '=', 'email')
      .executeTakeFirst();

    expect(providerAfter).toBeDefined();
  });
});

describe('GET /api/users/me/providers', () => {
  it('should return providers for user with email provider only', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    const response = await request(app)
      .get('/api/users/me/providers')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.providers).toHaveLength(1);
    expect(response.body.providers[0]).toMatchObject({
      provider: 'email',
      has_password: true,
    });
    expect(response.body.providers[0]).toHaveProperty('linked_at');
  });

  it('should return providers for user with Google provider only', async () => {
    const googleUser = await createGoogleUser('google3@example.com', 'google-789', 'Google User 3');
    const accessToken = generateAccessToken(googleUser.id, googleUser.email);

    const response = await request(app)
      .get('/api/users/me/providers')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.providers).toHaveLength(1);
    expect(response.body.providers[0]).toMatchObject({
      provider: 'google',
      provider_email: googleUser.email,
    });
    expect(response.body.providers[0]).toHaveProperty('linked_at');
  });

  it('should return providers for user with both email and Google', async () => {
    const { accessToken, userId, user } = await createAuthenticatedUser();

    // Add Google provider
    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: userId,
        provider: 'google',
        provider_user_id: 'google-both-123',
        provider_email: user.email,
      })
      .execute();

    const response = await request(app)
      .get('/api/users/me/providers')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.providers).toHaveLength(2);

    const emailProvider = response.body.providers.find((p: { provider: string }) => p.provider === 'email');
    const googleProvider = response.body.providers.find((p: { provider: string }) => p.provider === 'google');

    expect(emailProvider).toMatchObject({
      provider: 'email',
      has_password: true,
    });
    expect(googleProvider).toMatchObject({
      provider: 'google',
      provider_email: user.email,
    });
  });

  it('should return has_password false for email provider without password', async () => {
    // Create user with email provider but no password
    const user = await testDb
      .insertInto('users')
      .values({
        email: 'nopassword@example.com',
        display_name: 'No Password User',
        password_hash: null,
      })
      .returning(['id', 'email'])
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('auth_providers')
      .values({
        user_id: user.id,
        provider: 'email',
        provider_user_id: user.email,
        provider_email: user.email,
      })
      .execute();

    const accessToken = generateAccessToken(user.id, user.email);

    const response = await request(app)
      .get('/api/users/me/providers')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.providers[0]).toMatchObject({
      provider: 'email',
      has_password: false,
    });
  });

  it('should return 401 without token', async () => {
    const response = await request(app).get('/api/users/me/providers');

    expect(response.status).toBe(401);
  });

  it('should return 404 for soft-deleted user', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // Soft delete the user
    await testDb
      .updateTable('users')
      .set({ deleted_at: sql`NOW()` })
      .where('id', '=', userId)
      .execute();

    const response = await request(app)
      .get('/api/users/me/providers')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
  });
});

describe('GET /api/users/me/groups', () => {
  it('should return empty array for user with no groups', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .get('/api/users/me/groups')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.groups).toEqual([]);
    expect(response.body.total).toBe(0);
  });

  it('should return user groups with correct structure', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // Create a group
    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        name: 'Test Group',
        description: 'Test description',
        icon_emoji: '🏃',
        icon_color: '#1976D2',
      });

    const groupId = createResponse.body.id;

    const response = await request(app)
      .get('/api/users/me/groups')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.groups).toHaveLength(1);
    expect(response.body.groups[0]).toMatchObject({
      id: groupId,
      name: 'Test Group',
      description: 'Test description',
      icon_emoji: '🏃',
      icon_color: '#1976D2',
      has_icon: false,
      member_count: 1,
      role: 'creator',
    });
    expect(response.body.groups[0]).toHaveProperty('joined_at');
    expect(response.body.total).toBe(1);
  });

  it('should return groups ordered by joined_at DESC', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // Create multiple groups with delays to ensure different timestamps
    const group1 = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Group 1' });

    await new Promise(resolve => setTimeout(resolve, 10));

    const group2 = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Group 2' });

    await new Promise(resolve => setTimeout(resolve, 10));

    const group3 = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Group 3' });

    const response = await request(app)
      .get('/api/users/me/groups')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.groups).toHaveLength(3);
    expect(response.body.groups[0].id).toBe(group3.body.id); // Most recent first
    expect(response.body.groups[1].id).toBe(group2.body.id);
    expect(response.body.groups[2].id).toBe(group1.body.id);
  });

  it('should paginate with limit', async () => {
    const { accessToken } = await createAuthenticatedUser();

    // Create 7 groups
    for (let i = 0; i < 7; i++) {
      await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ name: `Group ${i}` });
    }

    const response = await request(app)
      .get('/api/users/me/groups?limit=5')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.groups.length).toBeLessThanOrEqual(5);
    expect(response.body.total).toBe(7);
  });

  it('should paginate with offset', async () => {
    const { accessToken } = await createAuthenticatedUser();

    // Create 10 groups
    const groupIds: string[] = [];
    for (let i = 0; i < 10; i++) {
      const createResponse = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ name: `Group ${i}` });
      groupIds.push(createResponse.body.id);
    }

    const firstPage = await request(app)
      .get('/api/users/me/groups?limit=5&offset=0')
      .set('Authorization', `Bearer ${accessToken}`);

    const secondPage = await request(app)
      .get('/api/users/me/groups?limit=5&offset=5')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(firstPage.status).toBe(200);
    expect(secondPage.status).toBe(200);
    expect(firstPage.body.groups.length).toBe(5);
    expect(secondPage.body.groups.length).toBe(5);
    expect(firstPage.body.groups[0].id).not.toBe(secondPage.body.groups[0].id);
    expect(firstPage.body.total).toBe(10);
    expect(secondPage.body.total).toBe(10);
  });

  it('should use default limit of 50', async () => {
    const { accessToken } = await createAuthenticatedUser();

    // Create 60 groups
    for (let i = 0; i < 60; i++) {
      await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ name: `Group ${i}` });
    }

    const response = await request(app)
      .get('/api/users/me/groups')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.groups.length).toBeLessThanOrEqual(50);
    expect(response.body.total).toBe(60);
  });

  it('should enforce max limit of 100', async () => {
    const { accessToken } = await createAuthenticatedUser();

    // Create 150 groups
    for (let i = 0; i < 150; i++) {
      await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ name: `Group ${i}` });
    }

    const response = await request(app)
      .get('/api/users/me/groups?limit=200')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body.groups.length).toBeLessThanOrEqual(100);
    expect(response.body.total).toBe(150);
  });

  it('should return correct member_count for each group', async () => {
    const { accessToken: creatorToken, userId: creatorId } = await createAuthenticatedUser('creator@example.com');
    const { accessToken: member1Token, userId: member1Id } = await createAuthenticatedUser('member1@example.com');
    const { accessToken: member2Token, userId: member2Id } = await createAuthenticatedUser('member2@example.com');

    // Create group
    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Member Count Group' });

    const groupId = createResponse.body.id;

    // Add members via invite
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

    // Approve members so they become active
    await request(app)
      .post(`/api/groups/${groupId}/members/${member1Id}/approve`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post(`/api/groups/${groupId}/members/${member2Id}/approve`)
      .set('Authorization', `Bearer ${creatorToken}`);

    // Get groups as creator
    const response = await request(app)
      .get('/api/users/me/groups')
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(200);
    const group = response.body.groups.find((g: { id: string }) => g.id === groupId);
    expect(group.member_count).toBe(3); // creator + 2 members

    // Verify with database query
    const memberCount = await testDb
      .selectFrom('group_memberships')
      .select((eb) => eb.fn.count('id').as('count'))
      .where('group_id', '=', groupId)
      .where('status', '=', 'active')
      .executeTakeFirst();

    expect(Number(memberCount?.count)).toBe(3);
  });

  it('should not count pending members in member_count', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('pending-creator@example.com');
    const { accessToken: pendingToken } = await createAuthenticatedUser('pending-member@example.com');

    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Pending Count Group' });

    const groupId = createResponse.body.id;

    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${pendingToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    const response = await request(app)
      .get('/api/users/me/groups')
      .set('Authorization', `Bearer ${creatorToken}`);

    expect(response.status).toBe(200);
    const group = response.body.groups.find((g: { id: string }) => g.id === groupId);
    expect(group.member_count).toBe(1); // only creator counted
  });

  it('should return correct role for each group', async () => {
    const { accessToken: creatorToken } = await createAuthenticatedUser('creator2@example.com');
    const { accessToken: memberToken } = await createAuthenticatedUser('member3@example.com');

    // Creator creates group
    const createResponse = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${creatorToken}`)
      .send({ name: 'Role Test Group' });

    const groupId = createResponse.body.id;

    // Add member via invite
    const inviteResponse = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creatorToken}`);

    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${memberToken}`)
      .send({ invite_code: inviteResponse.body.invite_code });

    // Creator sees role as 'creator'
    const creatorResponse = await request(app)
      .get('/api/users/me/groups')
      .set('Authorization', `Bearer ${creatorToken}`);

    const creatorGroup = creatorResponse.body.groups.find((g: { id: string }) => g.id === groupId);
    expect(creatorGroup.role).toBe('creator');

    // Member sees role as 'member'
    const memberResponse = await request(app)
      .get('/api/users/me/groups')
      .set('Authorization', `Bearer ${memberToken}`);

    const memberGroup = memberResponse.body.groups.find((g: { id: string }) => g.id === groupId);
    expect(memberGroup.role).toBe('member');
  });

  it('should return 401 without token', async () => {
    const response = await request(app).get('/api/users/me/groups');

    expect(response.status).toBe(401);
  });

  it('should return 400 for invalid limit (negative)', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .get('/api/users/me/groups?limit=-1')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
  });

  it('should return 400 for invalid offset (negative)', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .get('/api/users/me/groups?offset=-1')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
  });
});

describe('DELETE /api/users/me', () => {
  it('should soft delete user successfully', async () => {
    const { accessToken, userId, user } = await createAuthenticatedUser();

    const response = await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        password: 'Test123!@#',
      });

    expect(response.status).toBe(204);

    // Verify deleted_at is set
    const deletedUser = await testDb
      .selectFrom('users')
      .select(['deleted_at'])
      .where('id', '=', userId)
      .executeTakeFirst();

    expect(deletedUser?.deleted_at).not.toBeNull();

    // Verify user cannot access /me after deletion
    const getResponse = await request(app)
      .get('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(getResponse.status).toBe(404);

    // Verify user cannot login after deletion
    const loginResponse = await request(app)
      .post('/api/auth/login')
      .send({
        email: user.email,
        password: 'Test123!@#',
      });

    expect(loginResponse.status).toBe(401);
  });

  it('should return 400 for missing password', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({});

    expect(response.status).toBe(400);
  });

  it('should return 400 for invalid password', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        password: 'WrongPassword123!',
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_PASSWORD');
  });

  it('should return 400 for Google-only user (no password)', async () => {
    const googleUser = await createGoogleUser('google4@example.com', 'google-999', 'Google User 4');
    const accessToken = generateAccessToken(googleUser.id, googleUser.email);

    const response = await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        password: 'SomePassword123!',
      });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('PASSWORD_REQUIRED');
  });

  it('should return 401 without token', async () => {
    const response = await request(app)
      .delete('/api/users/me')
      .send({
        password: 'Test123!@#',
      });

    expect(response.status).toBe(401);
  });

  it('should return 404 for already deleted user', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    // Soft delete the user
    await testDb
      .updateTable('users')
      .set({ deleted_at: sql`NOW()` })
      .where('id', '=', userId)
      .execute();

    const response = await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        password: 'Test123!@#',
      });

    expect(response.status).toBe(404);
  });

  it('should preserve user data after soft delete', async () => {
    const { accessToken, userId, user } = await createAuthenticatedUser();

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        password: 'Test123!@#',
      });

    // Verify user still exists in database
    const deletedUser = await testDb
      .selectFrom('users')
      .selectAll()
      .where('id', '=', userId)
      .executeTakeFirst();

    expect(deletedUser).toBeDefined();
    expect(deletedUser?.email).toBe(user.email);
    expect(deletedUser?.display_name).toBe(user.display_name);
    expect(deletedUser?.deleted_at).not.toBeNull();
  });
});
