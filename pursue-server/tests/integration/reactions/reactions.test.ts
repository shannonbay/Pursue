import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  createTestGroupActivityWithId,
  addMemberToGroup,
  randomEmail,
} from '../../helpers';

describe('PUT /api/activities/:activity_id/reactions', () => {
  it('should add reaction successfully', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      userId,
      'progress_logged',
      { goal_title: 'Run', value: 1 }
    );

    const res = await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ emoji: '🔥' });

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      reaction: {
        activity_id: activityId,
        user_id: userId,
        emoji: '🔥',
      },
      replaced: false,
    });
    expect(res.body.reaction).toHaveProperty('created_at');

    const dbRow = await testDb
      .selectFrom('activity_reactions')
      .selectAll()
      .where('activity_id', '=', activityId)
      .where('user_id', '=', userId)
      .executeTakeFirst();

    expect(dbRow).toBeDefined();
    expect(dbRow?.emoji).toBe('🔥');
  });

  it('should replace existing reaction and set replaced: true', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      userId,
      'progress_logged',
      { goal_title: 'Run', value: 1 }
    );

    await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ emoji: '🔥' });

    const res = await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ emoji: '💪' });

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      reaction: { emoji: '💪' },
      replaced: true,
    });

    const count = await testDb
      .selectFrom('activity_reactions')
      .select(testDb.fn.count('id').as('count'))
      .where('activity_id', '=', activityId)
      .where('user_id', '=', userId)
      .executeTakeFirst();

    expect(Number(count?.count)).toBe(1);
  });

  it('should return 400 for invalid emoji', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      userId,
      'progress_logged'
    );

    const res = await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ emoji: '👍' });

    expect(res.status).toBe(400);
    expect(res.body.error?.code).toBe('VALIDATION_ERROR');
  });

  it('should return 404 for non-existent activity', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const fakeId = '00000000-0000-0000-0000-000000000000';

    const res = await request(app)
      .put(`/api/activities/${fakeId}/reactions`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ emoji: '🔥' });

    expect(res.status).toBe(404);
  });

  it('should return 403 when user is not group member', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(creator.accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      creator.userId,
      'progress_logged'
    );

    const nonMember = await createAuthenticatedUser(randomEmail());

    const res = await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${nonMember.accessToken}`)
      .send({ emoji: '🔥' });

    expect(res.status).toBe(403);
  });

  it('should return 401 when unauthenticated', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      userId,
      'progress_logged'
    );

    const res = await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .send({ emoji: '🔥' });

    expect(res.status).toBe(401);
  });
});

describe('DELETE /api/activities/:activity_id/reactions', () => {
  it('should remove reaction successfully', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      userId,
      'progress_logged'
    );

    await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ emoji: '🔥' });

    const res = await request(app)
      .delete(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(204);

    const dbRow = await testDb
      .selectFrom('activity_reactions')
      .selectAll()
      .where('activity_id', '=', activityId)
      .where('user_id', '=', userId)
      .executeTakeFirst();

    expect(dbRow).toBeUndefined();
  });

  it('should return 404 when no reaction exists', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      userId,
      'progress_logged'
    );

    const res = await request(app)
      .delete(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(404);
  });

  it('should return 403 when user is not group member', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(creator.accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      creator.userId,
      'progress_logged'
    );

    const nonMember = await createAuthenticatedUser(randomEmail());

    const res = await request(app)
      .delete(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${nonMember.accessToken}`);

    expect(res.status).toBe(403);
  });
});

describe('GET /api/activities/:activity_id/reactions', () => {
  it('should return full reactor list with user info', async () => {
    const creator = await createAuthenticatedUser(undefined, undefined, 'Creator Name');
    const { groupId } = await createGroupWithGoal(creator.accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      creator.userId,
      'progress_logged'
    );

    const { memberAccessToken, memberUserId } = await addMemberToGroup(
      creator.accessToken,
      groupId
    );

    await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ emoji: '🔥' });

    await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({ emoji: '💪' });

    const res = await request(app)
      .get(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      activity_id: activityId,
      total: 2,
    });
    expect(res.body.reactions).toHaveLength(2);

    const reactorUserIds = res.body.reactions.map((r: { user: { id: string } }) => r.user.id);
    expect(reactorUserIds).toContain(creator.userId);
    expect(reactorUserIds).toContain(memberUserId);

    expect(res.body.reactions.every((r: { emoji: string; user: { display_name: string } }) =>
      ['🔥', '💪'].includes(r.emoji) && r.user.display_name
    )).toBe(true);
  });

  it('should return 403 when user is not group member', async () => {
    const creator = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(creator.accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      creator.userId,
      'progress_logged'
    );

    const nonMember = await createAuthenticatedUser(randomEmail());

    const res = await request(app)
      .get(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${nonMember.accessToken}`);

    expect(res.status).toBe(403);
  });
});

describe('GET /api/groups/:group_id/activity with embedded reactions', () => {
  it('should include reactions and reaction_summary in activity feed', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(undefined, undefined, 'Alice Smith');
    const { groupId } = await createGroupWithGoal(accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      userId,
      'progress_logged',
      { goal_title: 'Run', value: 1 }
    );

    await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ emoji: '🔥' });

    const res = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(200);
    const activity = res.body.activities.find((a: { id: string }) => a.id === activityId);
    expect(activity).toBeDefined();

    expect(activity.reactions).toHaveLength(1);
    expect(activity.reactions[0]).toMatchObject({
      emoji: '🔥',
      count: 1,
      reactor_ids: [userId],
      current_user_reacted: true,
    });

    expect(activity.reaction_summary).toMatchObject({
      total_count: 1,
    });
    expect(activity.reaction_summary.top_reactors).toHaveLength(1);
    expect(activity.reaction_summary.top_reactors[0]).toMatchObject({
      user_id: userId,
      display_name: 'Alice S.',
    });
  });

  it('should set current_user_reacted false when another user reacted', async () => {
    const creator = await createAuthenticatedUser(undefined, undefined, 'Bob Johnson');
    const { groupId } = await createGroupWithGoal(creator.accessToken);
    const activityId = await createTestGroupActivityWithId(
      groupId,
      creator.userId,
      'progress_logged'
    );

    const { memberAccessToken, memberUserId } = await addMemberToGroup(
      creator.accessToken,
      groupId
    );

    await request(app)
      .put(`/api/activities/${activityId}/reactions`)
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({ emoji: '❤️' });

    const res = await request(app)
      .get(`/api/groups/${groupId}/activity`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    const activity = res.body.activities.find((a: { id: string }) => a.id === activityId);
    expect(activity.reactions[0]).toMatchObject({
      emoji: '❤️',
      count: 1,
      reactor_ids: [memberUserId],
      current_user_reacted: false,
    });
  });
});
