import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import {
  createAuthenticatedUser,
  createGroupWithGoal,
  addMemberToGroup,
  randomEmail,
  setUserPremium,
} from '../../helpers';

// Helper: create a public group via API
async function createPublicGroup(
  token: string,
  options?: {
    name?: string;
    category?: string;
    spot_limit?: number | null;
  }
): Promise<{ groupId: string }> {
  const res = await request(app)
    .post('/api/groups')
    .set('Authorization', `Bearer ${token}`)
    .send({
      name: options?.name ?? 'Public Test Group',
      visibility: 'public',
      category: options?.category ?? 'fitness',
    });
  if (res.status !== 201) {
    throw new Error(`Failed to create public group: ${JSON.stringify(res.body)}`);
  }
  if (options?.spot_limit !== undefined) {
    await request(app)
      .patch(`/api/groups/${res.body.id}`)
      .set('Authorization', `Bearer ${token}`)
      .send({ spot_limit: options.spot_limit });
  }
  return { groupId: res.body.id };
}

// Helper: set heat score for a group directly in the DB
async function setGroupHeat(
  groupId: string,
  heatScore: number,
  heatTier: number
): Promise<void> {
  await testDb
    .insertInto('group_heat')
    .values({
      group_id: groupId,
      heat_score: heatScore,
      heat_tier: heatTier,
      streak_days: 0,
      peak_score: heatScore,
    })
    .onConflict((oc) =>
      oc.column('group_id').doUpdateSet({
        heat_score: heatScore,
        heat_tier: heatTier,
      })
    )
    .execute();
}

// ============================================================
// GET /api/discover/groups
// ============================================================

describe('GET /api/discover/groups', () => {
  it('should return only public non-deleted groups', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId: pubId } = await createPublicGroup(accessToken, { name: 'Public One' });
    // Private group (default)
    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Private Group' });

    const res = await request(app).get('/api/discover/groups');
    expect(res.status).toBe(200);
    const ids = res.body.groups.map((g: any) => g.id);
    expect(ids).toContain(pubId);
    const names = res.body.groups.map((g: any) => g.name);
    expect(names).not.toContain('Private Group');
  });

  it('should not require authentication', async () => {
    const res = await request(app).get('/api/discover/groups');
    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('groups');
  });

  it('should return correct group card fields', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(accessToken, {
      name: 'Fitness Group',
      category: 'fitness',
    });
    await setGroupHeat(groupId, 55, 4);

    const res = await request(app).get('/api/discover/groups');
    expect(res.status).toBe(200);
    const group = res.body.groups.find((g: any) => g.id === groupId);
    expect(group).toBeDefined();
    expect(group).toMatchObject({
      id: groupId,
      name: 'Fitness Group',
      category: 'fitness',
      has_icon: false,
      is_full: false,
    });
    expect(group).toHaveProperty('member_count');
    expect(group).toHaveProperty('goal_count');
    expect(group).toHaveProperty('heat_score');
    expect(group).toHaveProperty('heat_tier');
    expect(group).toHaveProperty('heat_tier_name');
  });

  it('should filter by category', async () => {
    const { accessToken: token1 } = await createAuthenticatedUser(randomEmail());
    const { accessToken: token2 } = await createAuthenticatedUser(randomEmail());
    const { groupId: fitnessId } = await createPublicGroup(token1, { name: 'Fitness Grp', category: 'fitness' });
    const { groupId: learningId } = await createPublicGroup(token2, { name: 'Learning Grp', category: 'learning' });

    const res = await request(app).get('/api/discover/groups?categories=fitness');
    expect(res.status).toBe(200);
    const ids = res.body.groups.map((g: any) => g.id);
    expect(ids).toContain(fitnessId);
    expect(ids).not.toContain(learningId);
  });

  it('should filter by multiple categories', async () => {
    const { accessToken: token1 } = await createAuthenticatedUser(randomEmail());
    const { accessToken: token2 } = await createAuthenticatedUser(randomEmail());
    const { accessToken: token3 } = await createAuthenticatedUser(randomEmail());
    const { groupId: fitnessId } = await createPublicGroup(token1, { name: 'Fitness Multi', category: 'fitness' });
    const { groupId: learningId } = await createPublicGroup(token2, { name: 'Learning Multi', category: 'learning' });
    const { groupId: financeId } = await createPublicGroup(token3, { name: 'Finance Multi', category: 'finance' });

    const res = await request(app).get('/api/discover/groups?categories=fitness,learning');
    expect(res.status).toBe(200);
    const ids = res.body.groups.map((g: any) => g.id);
    expect(ids).toContain(fitnessId);
    expect(ids).toContain(learningId);
    expect(ids).not.toContain(financeId);
  });

  it('should search by name', async () => {
    const { accessToken: token1 } = await createAuthenticatedUser(randomEmail());
    const { accessToken: token2 } = await createAuthenticatedUser(randomEmail());
    const { groupId: matchId } = await createPublicGroup(token1, { name: 'Unique Search Name XYZ', category: 'fitness' });
    const { groupId: otherId } = await createPublicGroup(token2, { name: 'Other Group ABC', category: 'fitness' });

    const res = await request(app).get('/api/discover/groups?q=Unique+Search+Name+XYZ');
    expect(res.status).toBe(200);
    const ids = res.body.groups.map((g: any) => g.id);
    expect(ids).toContain(matchId);
    expect(ids).not.toContain(otherId);
  });

  it('should sort by heat descending by default', async () => {
    const { accessToken: token1 } = await createAuthenticatedUser(randomEmail());
    const { accessToken: token2 } = await createAuthenticatedUser(randomEmail());
    const { groupId: hotId } = await createPublicGroup(token1, { name: 'Hot Group' });
    const { groupId: coldId } = await createPublicGroup(token2, { name: 'Cold Group' });
    await setGroupHeat(hotId, 90, 8);
    await setGroupHeat(coldId, 10, 1);

    const res = await request(app).get('/api/discover/groups?sort=heat&limit=50');
    expect(res.status).toBe(200);
    const ids = res.body.groups.map((g: any) => g.id);
    expect(ids.indexOf(hotId)).toBeLessThan(ids.indexOf(coldId));
  });

  it('should sort by newest', async () => {
    const { accessToken: token1 } = await createAuthenticatedUser(randomEmail());
    const { accessToken: token2 } = await createAuthenticatedUser(randomEmail());
    const { groupId: olderGroupId } = await createPublicGroup(token1, { name: 'Older Newest Sort' });
    await new Promise((r) => setTimeout(r, 10)); // tiny delay
    const { groupId: newerGroupId } = await createPublicGroup(token2, { name: 'Newer Newest Sort' });

    const res = await request(app).get('/api/discover/groups?sort=newest&limit=50');
    expect(res.status).toBe(200);
    const ids = res.body.groups.map((g: any) => g.id);
    // newer should appear before older
    expect(ids.indexOf(newerGroupId)).toBeLessThan(ids.indexOf(olderGroupId));
  });

  it('should support cursor-based pagination', async () => {
    // Create 3 public groups with different users (free tier = 1 group each)
    for (let i = 0; i < 3; i++) {
      const { accessToken } = await createAuthenticatedUser(randomEmail());
      await createPublicGroup(accessToken, { name: `Paginate Group ${i}` });
    }

    const res1 = await request(app).get('/api/discover/groups?limit=2');
    expect(res1.status).toBe(200);
    expect(res1.body.groups.length).toBe(2);
    expect(res1.body.has_more).toBe(true);
    expect(res1.body.next_cursor).toBeTruthy();

    const res2 = await request(app).get(
      `/api/discover/groups?limit=2&cursor=${res1.body.next_cursor}`
    );
    expect(res2.status).toBe(200);
    // Should not return same groups as page 1
    const ids1 = res1.body.groups.map((g: any) => g.id);
    const ids2 = res2.body.groups.map((g: any) => g.id);
    const overlap = ids1.filter((id: string) => ids2.includes(id));
    expect(overlap.length).toBe(0);
  });

  it('should show spots_left when ≤10 remain', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(accessToken, {
      name: 'Limited Spots Group',
      spot_limit: 3,
    });

    const res = await request(app).get('/api/discover/groups?limit=50');
    expect(res.status).toBe(200);
    const group = res.body.groups.find((g: any) => g.id === groupId);
    expect(group).toBeDefined();
    expect(group.spot_limit).toBe(3);
    // Only 1 member (creator), so 2 spots left
    expect(group.spots_left).toBe(2);
    expect(group.is_full).toBe(false);
  });

  it('should show is_full when group is at capacity', async () => {
    const { accessToken, userId: adminId } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(accessToken, { name: 'Full Group Test' });
    // Set spot_limit = 2, add a second member → 2/2 = full
    await testDb.updateTable('groups').set({ spot_limit: 2 }).where('id', '=', groupId).execute();
    const { userId: memberId } = await createAuthenticatedUser(randomEmail());
    await testDb.insertInto('group_memberships').values({
      group_id: groupId, user_id: memberId, role: 'member', status: 'active',
    }).execute();

    const res = await request(app).get('/api/discover/groups?limit=50');
    expect(res.status).toBe(200);
    const group = res.body.groups.find((g: any) => g.id === groupId);
    expect(group).toBeDefined();
    expect(group.is_full).toBe(true);
    expect(group.spots_left).toBe(0);
  });

  it('should reject invalid limit', async () => {
    const res = await request(app).get('/api/discover/groups?limit=100');
    expect(res.status).toBe(400);
  });
});

// ============================================================
// GET /api/discover/groups/:group_id
// ============================================================

describe('GET /api/discover/groups/:group_id', () => {
  it('should return expanded group detail with goals', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(accessToken, {
      name: 'Expanded Detail Group',
      category: 'mindfulness',
    });
    // Add a goal
    await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ title: 'Meditate', cadence: 'daily', metric_type: 'binary' });

    const res = await request(app).get(`/api/discover/groups/${groupId}`);
    expect(res.status).toBe(200);
    expect(res.body).toMatchObject({
      id: groupId,
      name: 'Expanded Detail Group',
      category: 'mindfulness',
    });
    expect(res.body).toHaveProperty('goals');
    expect(Array.isArray(res.body.goals)).toBe(true);
    expect(res.body.goals.length).toBeGreaterThan(0);
    const goal = res.body.goals[0];
    expect(goal).toHaveProperty('id');
    expect(goal).toHaveProperty('title');
    expect(goal).toHaveProperty('cadence');
    expect(goal).toHaveProperty('metric_type');
    expect(goal).toHaveProperty('active_days_label');
  });

  it('should return 404 for private group', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const res1 = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Private Detail Group' });
    const groupId = res1.body.id;

    const res = await request(app).get(`/api/discover/groups/${groupId}`);
    expect(res.status).toBe(404);
  });

  it('should return 404 for non-existent group', async () => {
    const res = await request(app).get(
      '/api/discover/groups/00000000-0000-0000-0000-000000000000'
    );
    expect(res.status).toBe(404);
  });

  it('should not require authentication', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(accessToken, { name: 'No Auth Detail' });
    const res = await request(app).get(`/api/discover/groups/${groupId}`);
    expect(res.status).toBe(200);
  });

  it('should include active_days_label for daily goals with active_days set', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(accessToken, { name: 'Active Days Detail' });

    // Create a goal and set active_days = 62 (Mon-Fri = 1+4+8+16+32 = 62) via DB
    const goal = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ title: 'Daily Run', cadence: 'daily', metric_type: 'binary' });
    const goalId = goal.body.id;

    // Set active_days bitmask for weekdays (Mon-Fri = bits 1-5 = 62)
    await testDb
      .updateTable('goals')
      .set({ active_days: 62 })
      .where('id', '=', goalId)
      .execute();

    const res = await request(app).get(`/api/discover/groups/${groupId}`);
    expect(res.status).toBe(200);
    const foundGoal = res.body.goals.find((g: any) => g.id === goalId);
    expect(foundGoal).toBeDefined();
    expect(foundGoal.active_days_label).toBeTruthy();
    expect(foundGoal.active_days_label).not.toBe('Every day');
  });
});

// ============================================================
// POST /api/groups/:group_id/join-requests
// ============================================================

describe('POST /api/groups/:group_id/join-requests', () => {
  it('should create a pending join request', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Join Request Group' });

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    const res = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({ note: 'I want to join!' });

    expect(res.status).toBe(201);
    expect(res.body).toMatchObject({
      group_id: groupId,
      status: 'pending',
      note: 'I want to join!',
      auto_approved: false,
    });
    expect(res.body).toHaveProperty('id');
    expect(res.body).toHaveProperty('created_at');
  });

  it('should create a join request without a note', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'No Note Group' });

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    const res = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});

    expect(res.status).toBe(201);
    expect(res.body.status).toBe('pending');
    expect(res.body.note).toBeNull();
  });

  it('should return 401 without authentication', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(accessToken);

    const res = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .send({});
    expect(res.status).toBe(401);
  });

  it('should return 403 for private group', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const privateRes = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ name: 'Private For Request' });
    const groupId = privateRes.body.id;

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    const res = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});
    expect(res.status).toBe(403);
  });

  it('should return 409 if already an active member', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Already Member Group' });

    // The admin tries to join their own group
    const res = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({});
    expect(res.status).toBe(409);
  });

  it('should return 409 if request already pending', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Double Request Group' });

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    // First request
    await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});

    // Second request
    const res = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('ALREADY_REQUESTED');
  });

  it('should return 409 if group is full', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Full Group For Request' });
    // Set spot_limit = 2, add a second member → 2/2 = full
    await testDb.updateTable('groups').set({ spot_limit: 2 }).where('id', '=', groupId).execute();
    const { userId: memberId } = await createAuthenticatedUser(randomEmail());
    await testDb.insertInto('group_memberships').values({
      group_id: groupId, user_id: memberId, role: 'member', status: 'active',
    }).execute();

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    const res = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('GROUP_FULL');
  });

  it('should return 429 when max pending requests reached', async () => {
    const { accessToken: userToken, userId } = await createAuthenticatedUser(randomEmail());

    // Directly insert 10 pending join requests into different groups
    for (let i = 0; i < 10; i++) {
      const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
      const { groupId } = await createPublicGroup(adminToken, { name: `Rate Limit Group ${i}` });
      await testDb
        .insertInto('join_requests')
        .values({ group_id: groupId, user_id: userId, status: 'pending' })
        .execute();
    }

    // Now try to submit another
    const { accessToken: adminToken2 } = await createAuthenticatedUser(randomEmail());
    const { groupId: newGroupId } = await createPublicGroup(adminToken2, { name: 'Rate Limit Final' });

    const res = await request(app)
      .post(`/api/groups/${newGroupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});
    expect(res.status).toBe(429);
    expect(res.body.error.code).toBe('RATE_LIMIT_EXCEEDED');
  });

  it('should enforce 30-day cooldown after a recent decline', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Cooldown Group' });
    const { accessToken: userToken, userId } = await createAuthenticatedUser(randomEmail());

    // Insert a declined request with recent reviewed_at (within 30 days)
    await testDb
      .insertInto('join_requests')
      .values({
        group_id: groupId,
        user_id: userId,
        status: 'declined',
        reviewed_at: new Date(),
      })
      .execute();

    // Re-requesting should be blocked with cooldown
    const res = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});
    expect(res.status).toBe(429);
    expect(res.body.error.code).toBe('COOLDOWN_ACTIVE');
  });

  it('should auto-approve and create membership when auto_approve is set (premium)', async () => {
    const { accessToken: adminToken, userId: adminId } = await createAuthenticatedUser(randomEmail());
    await setUserPremium(adminId);
    const { groupId } = await createPublicGroup(adminToken, { name: 'Auto Approve Group' });

    // Set spot_limit + auto_approve
    await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ spot_limit: 10, auto_approve: true });

    const { accessToken: userToken, userId } = await createAuthenticatedUser(randomEmail());
    const res = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});

    expect(res.status).toBe(201);
    expect(res.body.status).toBe('approved');
    expect(res.body.auto_approved).toBe(true);

    // Verify membership was created
    const membership = await testDb
      .selectFrom('group_memberships')
      .select(['status', 'role'])
      .where('group_id', '=', groupId)
      .where('user_id', '=', userId)
      .executeTakeFirst();
    expect(membership).toBeDefined();
    expect(membership?.status).toBe('active');
  });
});

// ============================================================
// GET /api/groups/:group_id/join-requests
// ============================================================

describe('GET /api/groups/:group_id/join-requests', () => {
  it('should return pending requests for admin', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'List Requests Group' });

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({ note: 'Please accept me' });

    const res = await request(app)
      .get(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${adminToken}`);

    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('requests');
    expect(Array.isArray(res.body.requests)).toBe(true);
    expect(res.body.requests.length).toBe(1);
    const req_ = res.body.requests[0];
    expect(req_).toHaveProperty('id');
    expect(req_).toHaveProperty('user_id');
    expect(req_).toHaveProperty('display_name');
    expect(req_).toHaveProperty('user_created_at');
    expect(req_).toHaveProperty('note');
    expect(req_.note).toBe('Please accept me');
  });

  it('should return 403 for non-admin member', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Admin Check Group' });

    const { memberAccessToken } = await addMemberToGroup(adminToken, groupId);

    const res = await request(app)
      .get(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${memberAccessToken}`);

    expect(res.status).toBe(403);
  });

  it('should return 401 without authentication', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Auth Check Requests' });

    const res = await request(app).get(`/api/groups/${groupId}/join-requests`);
    expect(res.status).toBe(401);
  });

  it('should only return pending (not approved/declined) requests', async () => {
    const { accessToken: adminToken, userId: adminId } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Filter Requests Group' });
    const { userId: userId1 } = await createAuthenticatedUser(randomEmail());
    const { userId: userId2 } = await createAuthenticatedUser(randomEmail());

    // Insert one pending and one declined directly
    await testDb.insertInto('join_requests').values([
      { group_id: groupId, user_id: userId1, status: 'pending' },
      { group_id: groupId, user_id: userId2, status: 'declined', reviewed_by: adminId, reviewed_at: new Date() },
    ]).execute();

    const res = await request(app)
      .get(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${adminToken}`);

    expect(res.status).toBe(200);
    const statuses = res.body.requests.map((r: any) => r.status ?? 'pending');
    // All returned should be pending (status not in response but user_id should only be userId1)
    const userIds = res.body.requests.map((r: any) => r.user_id);
    expect(userIds).toContain(userId1);
    expect(userIds).not.toContain(userId2);
  });
});

// ============================================================
// PATCH /api/groups/:group_id/join-requests/:request_id
// ============================================================

describe('PATCH /api/groups/:group_id/join-requests/:request_id', () => {
  it('should approve a join request and create membership', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Approve Flow Group' });

    const { accessToken: userToken, userId } = await createAuthenticatedUser(randomEmail());
    const reqRes = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({ note: 'I want in!' });
    const requestId = reqRes.body.id;

    const res = await request(app)
      .patch(`/api/groups/${groupId}/join-requests/${requestId}`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ status: 'approved' });

    expect(res.status).toBe(200);
    expect(res.body.status).toBe('approved');
    expect(res.body).toHaveProperty('reviewed_at');

    // Verify membership
    const membership = await testDb
      .selectFrom('group_memberships')
      .select(['status', 'role'])
      .where('group_id', '=', groupId)
      .where('user_id', '=', userId)
      .executeTakeFirst();
    expect(membership).toBeDefined();
    expect(membership?.status).toBe('active');
    expect(membership?.role).toBe('member');
  });

  it('should decline a join request', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Decline Flow Group' });

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    const reqRes = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});
    const requestId = reqRes.body.id;

    const res = await request(app)
      .patch(`/api/groups/${groupId}/join-requests/${requestId}`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ status: 'declined' });

    expect(res.status).toBe(200);
    expect(res.body.status).toBe('declined');

    // Membership should NOT be created
    const dbReq = await testDb
      .selectFrom('join_requests')
      .select('status')
      .where('id', '=', requestId)
      .executeTakeFirst();
    expect(dbReq?.status).toBe('declined');
  });

  it('should return 403 for non-admin', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Review Auth Group' });

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    const reqRes = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});
    const requestId = reqRes.body.id;

    const { memberAccessToken } = await addMemberToGroup(adminToken, groupId);
    const res = await request(app)
      .patch(`/api/groups/${groupId}/join-requests/${requestId}`)
      .set('Authorization', `Bearer ${memberAccessToken}`)
      .send({ status: 'approved' });
    expect(res.status).toBe(403);
  });

  it('should return 409 if request already reviewed', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Double Review Group' });

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    const reqRes = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});
    const requestId = reqRes.body.id;

    // First review
    await request(app)
      .patch(`/api/groups/${groupId}/join-requests/${requestId}`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ status: 'declined' });

    // Second review
    const res = await request(app)
      .patch(`/api/groups/${groupId}/join-requests/${requestId}`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ status: 'approved' });
    expect(res.status).toBe(409);
    expect(res.body.error.code).toBe('ALREADY_REVIEWED');
  });

  it('should return 404 for non-existent request', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Not Found Request' });

    const res = await request(app)
      .patch(`/api/groups/${groupId}/join-requests/00000000-0000-0000-0000-000000000000`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ status: 'approved' });
    expect(res.status).toBe(404);
  });

  it('should validate status field', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Validation Group' });

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());
    const reqRes = await request(app)
      .post(`/api/groups/${groupId}/join-requests`)
      .set('Authorization', `Bearer ${userToken}`)
      .send({});
    const requestId = reqRes.body.id;

    const res = await request(app)
      .patch(`/api/groups/${groupId}/join-requests/${requestId}`)
      .set('Authorization', `Bearer ${adminToken}`)
      .send({ status: 'pending' }); // invalid
    expect(res.status).toBe(400);
  });
});

// ============================================================
// PATCH /api/groups/:group_id — updateGroup new fields
// ============================================================

describe('PATCH /api/groups/:group_id — visibility, category, spot_limit, comm fields', () => {
  it('should update visibility to public', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ visibility: 'public' });

    expect(res.status).toBe(200);
    expect(res.body.visibility).toBe('public');
  });

  it('should update category', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ category: 'mindfulness' });

    expect(res.status).toBe(200);
    expect(res.body.category).toBe('mindfulness');
  });

  it('should update spot_limit', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ spot_limit: 25 });

    expect(res.status).toBe(200);
    expect(res.body.spot_limit).toBe(25);
  });

  it('should clear spot_limit with null', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ spot_limit: 10 });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ spot_limit: null });

    expect(res.status).toBe(200);
    expect(res.body.spot_limit).toBeNull();
  });

  it('should update comm_platform and comm_link for discord', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        comm_platform: 'discord',
        comm_link: 'https://discord.gg/testserver',
      });

    expect(res.status).toBe(200);
    expect(res.body.comm_platform).toBe('discord');
    expect(res.body.comm_link).toBe('https://discord.gg/testserver');
  });

  it('should reject comm_link without comm_platform', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ comm_link: 'https://discord.gg/test' });

    expect(res.status).toBe(400);
  });

  it('should reject invalid discord URL format', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        comm_platform: 'discord',
        comm_link: 'https://notdiscord.com/test',
      });

    expect(res.status).toBe(400);
  });

  it('should reject auto_approve for free user', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    // Set spot_limit first
    await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ spot_limit: 10 });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ auto_approve: true });

    expect(res.status).toBe(403);
  });

  it('should allow auto_approve for premium user with spot_limit', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    await setUserPremium(userId);
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    // Set spot_limit + auto_approve together
    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ spot_limit: 10, auto_approve: true });

    expect(res.status).toBe(200);
    expect(res.body.auto_approve).toBe(true);
    expect(res.body.spot_limit).toBe(10);
  });

  it('should reject auto_approve without spot_limit for premium user', async () => {
    const { accessToken, userId } = await createAuthenticatedUser(randomEmail());
    await setUserPremium(userId);
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ auto_approve: true });

    expect(res.status).toBe(400);
  });

  it('should reject spot_limit below minimum (2)', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ spot_limit: 1 });

    expect(res.status).toBe(400);
  });

  it('should reject invalid category value', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ category: 'invalid_category' });

    expect(res.status).toBe(400);
  });

  it('should return new fields even when no change is made (empty body)', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: false });

    const res = await request(app)
      .patch(`/api/groups/${groupId}`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({});

    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('visibility');
    expect(res.body).toHaveProperty('category');
    expect(res.body).toHaveProperty('spot_limit');
    expect(res.body).toHaveProperty('auto_approve');
    expect(res.body).toHaveProperty('comm_platform');
    expect(res.body).toHaveProperty('comm_link');
  });
});

// ============================================================
// GET /api/discover/suggestions
// ============================================================

describe('GET /api/discover/suggestions', () => {
  it('should return empty suggestions array (pgvector deferred)', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());

    const res = await request(app)
      .get('/api/discover/suggestions')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(res.status).toBe(200);
    expect(res.body).toHaveProperty('suggestions');
    expect(Array.isArray(res.body.suggestions)).toBe(true);
    expect(res.body.suggestions.length).toBe(0);
  });

  it('should return 401 without authentication', async () => {
    const res = await request(app).get('/api/discover/suggestions');
    expect(res.status).toBe(401);
  });
});

// ============================================================
// DELETE /api/discover/suggestions/:group_id
// ============================================================

describe('DELETE /api/discover/suggestions/:group_id', () => {
  it('should dismiss a suggestion and return 204', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Dismiss Test Group' });

    const { accessToken: userToken, userId } = await createAuthenticatedUser(randomEmail());
    const res = await request(app)
      .delete(`/api/discover/suggestions/${groupId}`)
      .set('Authorization', `Bearer ${userToken}`);

    expect(res.status).toBe(204);

    // Verify dismissal record
    const dismissal = await testDb
      .selectFrom('suggestion_dismissals')
      .select(['user_id', 'group_id'])
      .where('user_id', '=', userId)
      .where('group_id', '=', groupId)
      .executeTakeFirst();
    expect(dismissal).toBeDefined();
  });

  it('should be idempotent (dismiss twice = 204 both times)', async () => {
    const { accessToken: adminToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(adminToken, { name: 'Idempotent Dismiss' });

    const { accessToken: userToken } = await createAuthenticatedUser(randomEmail());

    const res1 = await request(app)
      .delete(`/api/discover/suggestions/${groupId}`)
      .set('Authorization', `Bearer ${userToken}`);
    expect(res1.status).toBe(204);

    const res2 = await request(app)
      .delete(`/api/discover/suggestions/${groupId}`)
      .set('Authorization', `Bearer ${userToken}`);
    expect(res2.status).toBe(204);
  });

  it('should return 401 without authentication', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());
    const { groupId } = await createPublicGroup(accessToken);

    const res = await request(app).delete(`/api/discover/suggestions/${groupId}`);
    expect(res.status).toBe(401);
  });

  it('should return 404 for non-existent group', async () => {
    const { accessToken } = await createAuthenticatedUser(randomEmail());

    const res = await request(app)
      .delete('/api/discover/suggestions/00000000-0000-0000-0000-000000000000')
      .set('Authorization', `Bearer ${accessToken}`);
    expect(res.status).toBe(404);
  });
});
