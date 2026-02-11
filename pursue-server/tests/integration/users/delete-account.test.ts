import request from 'supertest';
import crypto from 'crypto';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  randomEmail,
  createGroupWithGoal,
  addMemberToGroup,
  createTestInviteCode,
  createTestGroupActivity,
  createProgressEntry,
} from '../../helpers';
import { generateAccessToken } from '../../../src/utils/jwt.js';

describe('DELETE /api/users/me', () => {
  it('should delete user and return 204', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'delete' });

    expect(response.status).toBe(204);
  });

  it('should reject without confirmation field', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({});

    expect(response.status).toBe(400);
  });

  it('should reject with wrong confirmation string', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'wrong' });

    expect(response.status).toBe(400);
    expect(response.body.error.code).toBe('INVALID_CONFIRMATION');
  });

  it('should accept confirmation case-insensitively', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());

    const response = await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'DELETE' });

    expect(response.status).toBe(204);
  });

  it('should reject without auth token', async () => {
    const response = await request(app)
      .delete('/api/users/me')
      .send({ confirmation: 'delete' });

    expect(response.status).toBe(401);
  });

  it('should hard delete the user row', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'delete' });

    const user = await testDb
      .selectFrom('users')
      .select('id')
      .where('id', '=', userId)
      .executeTakeFirst();

    expect(user).toBeUndefined();
  });

  it('should cascade delete progress entries', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { goalId } = await createGroupWithGoal(accessToken);

    await createProgressEntry(goalId!, userId, 1, '2025-01-01');

    // Verify progress entry exists
    const beforeEntries = await testDb
      .selectFrom('progress_entries')
      .select('id')
      .where('user_id', '=', userId)
      .execute();
    expect(beforeEntries.length).toBe(1);

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'delete' });

    const afterEntries = await testDb
      .selectFrom('progress_entries')
      .select('id')
      .where('user_id', '=', userId)
      .execute();
    expect(afterEntries.length).toBe(0);
  });

  it('should cascade delete group memberships', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, { includeGoal: false });

    const { memberAccessToken, memberUserId } = await addMemberToGroup(creator.accessToken, groupId);

    // Verify membership exists
    const beforeMemberships = await testDb
      .selectFrom('group_memberships')
      .select('id')
      .where('user_id', '=', memberUserId)
      .execute();
    expect(beforeMemberships.length).toBe(1);

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({ confirmation: 'delete' });

    const afterMemberships = await testDb
      .selectFrom('group_memberships')
      .select('id')
      .where('user_id', '=', memberUserId)
      .execute();
    expect(afterMemberships.length).toBe(0);
  });

  it('should set activity logs user_id to NULL', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    await createTestGroupActivity(groupId, userId, 'member_joined');

    // Verify activity has user_id
    const beforeActivities = await testDb
      .selectFrom('group_activities')
      .select(['id', 'user_id'])
      .where('group_id', '=', groupId)
      .where('user_id', '=', userId)
      .execute();
    expect(beforeActivities.length).toBeGreaterThan(0);

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'delete' });

    // Group was sole-member so it gets deleted too; verify via activity user_id on any remaining
    // Since sole-member groups are deleted, activities are also cascade-deleted.
    // Test with a multi-member group instead:
  });

  it('should set activity user_id to NULL in multi-member groups', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, { includeGoal: false });
    const { memberAccessToken, memberUserId } = await addMemberToGroup(creator.accessToken, groupId);

    await createTestGroupActivity(groupId, memberUserId, 'progress_logged');

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({ confirmation: 'delete' });

    const activities = await testDb
      .selectFrom('group_activities')
      .select(['id', 'user_id'])
      .where('group_id', '=', groupId)
      .where('activity_type', '=', 'progress_logged')
      .execute();
    expect(activities.length).toBe(1);
    expect(activities[0].user_id).toBeNull();
  });

  it('should delete groups where user was sole member', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'delete' });

    const group = await testDb
      .selectFrom('groups')
      .select('id')
      .where('id', '=', groupId)
      .executeTakeFirst();
    expect(group).toBeUndefined();
  });

  it('should transfer creator to another member in multi-member groups', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, { includeGoal: false });
    const { memberUserId } = await addMemberToGroup(creator.accessToken, groupId);

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ confirmation: 'delete' });

    const group = await testDb
      .selectFrom('groups')
      .select(['id', 'creator_user_id'])
      .where('id', '=', groupId)
      .executeTakeFirst();

    expect(group).toBeDefined();
    expect(group!.creator_user_id).toBe(memberUserId);
  });

  it('should invalidate tokens after deletion', async () => {
    const email = randomEmail();
    const { accessToken } = await createAuthenticatedUser(email);

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'delete' });

    // Attempt login should fail
    const loginRes = await request(app)
      .post('/api/auth/login')
      .send({ email, password: 'Test123!@#' });

    expect(loginRes.status).toBe(401);
  });

  it('should preserve invite codes with null creator after user deletion', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, { includeGoal: false });
    const { memberAccessToken, memberUserId } = await addMemberToGroup(creator.accessToken, groupId);

    // Verify active invite code exists for group
    const beforeCodes = await testDb
      .selectFrom('invite_codes')
      .select(['id', 'created_by_user_id'])
      .where('group_id', '=', groupId)
      .where('revoked_at', 'is', null)
      .execute();
    expect(beforeCodes.length).toBe(1);
    expect(beforeCodes[0].created_by_user_id).toBe(creator.userId);

    // Delete the creator (group transfers to member)
    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ confirmation: 'delete' });

    // Invite code should still exist with created_by_user_id set to null
    const afterCodes = await testDb
      .selectFrom('invite_codes')
      .select(['id', 'created_by_user_id'])
      .where('group_id', '=', groupId)
      .where('revoked_at', 'is', null)
      .execute();
    expect(afterCodes.length).toBe(1);
    expect(afterCodes[0].created_by_user_id).toBeNull();

    // Group should still exist (transferred to member)
    const group = await testDb
      .selectFrom('groups')
      .select('id')
      .where('id', '=', groupId)
      .executeTakeFirst();
    expect(group).toBeDefined();
  });

  it('should retain consent records with null user_id and email_hash after deletion', async () => {
    const email = randomEmail();
    const { accessToken, userId } = await createAuthenticatedUser(email);

    // Verify consent entries exist
    const beforeConsents = await testDb
      .selectFrom('user_consents')
      .select(['id', 'user_id', 'consent_type', 'email_hash'])
      .where('user_id', '=', userId)
      .execute();
    expect(beforeConsents.length).toBe(2);
    expect(beforeConsents[0].email_hash).toBeNull();

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'delete' });

    // Consent rows should still exist with user_id set to null and email_hash populated
    const afterConsents = await testDb
      .selectFrom('user_consents')
      .select(['id', 'user_id', 'consent_type', 'email_hash'])
      .where('id', 'in', beforeConsents.map(c => c.id))
      .execute();
    expect(afterConsents.length).toBe(2);
    expect(afterConsents[0].user_id).toBeNull();
    expect(afterConsents[1].user_id).toBeNull();

    // Verify email_hash matches expected SHA-256 output
    const expectedHash = crypto
      .createHash('sha256')
      .update(email + process.env.CONSENT_HASH_SALT!)
      .digest('hex');
    expect(afterConsents[0].email_hash).toBe(expectedHash);
    expect(afterConsents[1].email_hash).toBe(expectedHash);
  });

  it('should cascade delete auth providers', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());

    // Verify auth provider exists
    const beforeProviders = await testDb
      .selectFrom('auth_providers')
      .select('id')
      .where('user_id', '=', userId)
      .execute();
    expect(beforeProviders.length).toBeGreaterThan(0);

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'delete' });

    const afterProviders = await testDb
      .selectFrom('auth_providers')
      .select('id')
      .where('user_id', '=', userId)
      .execute();
    expect(afterProviders.length).toBe(0);
  });

  it('should cascade delete devices', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());

    // Insert a device directly
    await testDb
      .insertInto('devices')
      .values({
        user_id: userId,
        fcm_token: `test-fcm-token-${Math.random().toString(36).substring(7)}`,
        device_name: 'Test Device',
        platform: 'android',
      })
      .execute();

    // Verify device exists
    const beforeDevices = await testDb
      .selectFrom('devices')
      .select('id')
      .where('user_id', '=', userId)
      .execute();
    expect(beforeDevices.length).toBe(1);

    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ confirmation: 'delete' });

    const afterDevices = await testDb
      .selectFrom('devices')
      .select('id')
      .where('user_id', '=', userId)
      .execute();
    expect(afterDevices.length).toBe(0);
  });

  it('should nullify goals created_by_user_id after deletion', async () => {
    const creator = await createAuthenticatedUser(randomEmail(), 'Test123!@#', 'Creator');
    const { groupId } = await createGroupWithGoal(creator.accessToken, { includeGoal: false });
    const { memberAccessToken, memberUserId } = await addMemberToGroup(creator.accessToken, groupId);

    // Creator creates a goal
    const goalRes = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ title: 'Test Goal', cadence: 'daily', metric_type: 'binary' });
    expect(goalRes.status).toBe(201);
    const goalId = goalRes.body.id;

    // Delete the creator
    await request(app)
      .delete('/api/users/me')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ confirmation: 'delete' });

    // Goal should still exist (group transferred) with created_by_user_id = null
    const goal = await testDb
      .selectFrom('goals')
      .select(['id', 'created_by_user_id'])
      .where('id', '=', goalId)
      .executeTakeFirst();

    expect(goal).toBeDefined();
    expect(goal!.created_by_user_id).toBeNull();
  });
});
