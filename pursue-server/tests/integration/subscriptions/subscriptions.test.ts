import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, setUserPremium, createGroupWithGoal } from '../../helpers';

describe('GET /api/users/me/subscription', () => {
  it('returns subscription for free user with no groups', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .get('/api/users/me/subscription')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      tier: 'free',
      status: 'active',
      group_limit: 1,
      current_group_count: 0,
      groups_remaining: 1,
      is_over_limit: false,
      can_create_group: true,
      can_join_group: true,
    });
    expect(response.body.subscription_expires_at).toBeNull();
    expect(response.body.auto_renew).toBeNull();
  });

  it('returns subscription for free user at limit', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Only Group' });

    const response = await request(app)
      .get('/api/users/me/subscription')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      tier: 'free',
      status: 'active',
      group_limit: 1,
      current_group_count: 1,
      groups_remaining: 0,
      is_over_limit: false,
      can_create_group: false,
      can_join_group: false,
    });
  });

  it('returns subscription for premium user', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);
    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Premium Group' });

    const response = await request(app)
      .get('/api/users/me/subscription')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      tier: 'premium',
      status: 'active',
      group_limit: 10,
      current_group_count: 1,
      groups_remaining: 9,
      is_over_limit: false,
      can_create_group: true,
      can_join_group: true,
    });
    expect(response.body.subscription_expires_at).toBeDefined();
    expect(new Date(response.body.subscription_expires_at).getTime()).toBeGreaterThan(Date.now());
  });

  it('returns 401 without auth', async () => {
    const response = await request(app).get('/api/users/me/subscription');
    expect(response.status).toBe(401);
  });
});

describe('GET /api/users/me/subscription/eligibility', () => {
  it('returns eligibility when under limit', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .get('/api/users/me/subscription/eligibility')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      can_create_group: true,
      can_join_group: true,
      current_count: 0,
      limit: 1,
      upgrade_required: false,
    });
  });

  it('returns eligibility when at limit', async () => {
    const { accessToken } = await createAuthenticatedUser();
    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Only Group' });

    const response = await request(app)
      .get('/api/users/me/subscription/eligibility')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      can_create_group: false,
      can_join_group: false,
      reason: 'at_group_limit',
      current_count: 1,
      limit: 1,
      upgrade_required: true,
    });
  });
});

describe('POST /api/subscriptions/upgrade', () => {
  it('upgrades user to premium', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/subscriptions/upgrade')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        platform: 'google_play',
        purchase_token: 'test-purchase-token-123',
        product_id: 'pursue_premium_annual',
      });

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      tier: 'premium',
      status: 'active',
      group_limit: 10,
    });
    expect(response.body.subscription_id).toBeDefined();
    expect(response.body.expires_at).toBeDefined();

    const subRes = await request(app)
      .get('/api/users/me/subscription')
      .set('Authorization', `Bearer ${accessToken}`);
    expect(subRes.body.tier).toBe('premium');
  });

  it('rejects invalid product_id', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/subscriptions/upgrade')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        platform: 'google_play',
        purchase_token: 'token',
        product_id: 'wrong_product',
      });

    expect(response.status).toBe(400);
    expect(response.body.error?.code).toBe('INVALID_PRODUCT');
  });

  it('returns 401 without auth', async () => {
    const response = await request(app)
      .post('/api/subscriptions/upgrade')
      .send({
        platform: 'google_play',
        purchase_token: 'token',
        product_id: 'pursue_premium_annual',
      });
    expect(response.status).toBe(401);
  });
});

describe('POST /api/subscriptions/cancel', () => {
  it('cancels premium subscription', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);

    const response = await request(app)
      .post('/api/subscriptions/cancel')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      status: 'cancelled',
      auto_renew: false,
    });
    expect(response.body.access_until).toBeDefined();
    expect(response.body.message).toBeDefined();
  });

  it('returns 404 when no active premium', async () => {
    const { accessToken } = await createAuthenticatedUser();

    const response = await request(app)
      .post('/api/subscriptions/cancel')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(404);
    expect(response.body.error?.code).toBe('SUBSCRIPTION_NOT_FOUND');
  });
});

describe('POST /api/subscriptions/downgrade/select-group', () => {
  it('returns 400 when user is not over_limit', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Only Group' });
    const groupRes = await request(app)
      .get('/api/users/me/groups')
      .set('Authorization', `Bearer ${accessToken}`);
    const groupId = groupRes.body.groups[0].id;

    const response = await request(app)
      .post('/api/subscriptions/downgrade/select-group')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ keep_group_id: groupId });

    expect(response.status).toBe(400);
    expect(response.body.error?.code).toBe('INVALID_STATE');
  });

  it('selects group when over_limit', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);
    const g1 = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Group A' });
    const g2 = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Group B' });

    // Simulate downgrade: set user to free and over_limit
    await testDb
      .updateTable('users')
      .set({
        current_subscription_tier: 'free',
        subscription_status: 'over_limit',
        group_limit: 1,
      })
      .where('id', '=', userId)
      .execute();

    const response = await request(app)
      .post('/api/subscriptions/downgrade/select-group')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ keep_group_id: g1.body.id });

    expect(response.status).toBe(200);
    expect(response.body.status).toBe('success');
    expect(response.body.kept_group.id).toBe(g1.body.id);
    expect(response.body.kept_group.name).toBe('Group A');
    expect(response.body.removed_groups).toHaveLength(1);
    expect(response.body.removed_groups[0].id).toBe(g2.body.id);
    expect(response.body.read_only_access_until).toBeDefined();
  });

  it('rejects second select-group call after free user has already chosen a group to retain', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);
    const g1 = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Group A' });
    const g2 = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Group B' });

    await testDb
      .updateTable('users')
      .set({
        current_subscription_tier: 'free',
        subscription_status: 'over_limit',
        group_limit: 1,
      })
      .where('id', '=', userId)
      .execute();

    const first = await request(app)
      .post('/api/subscriptions/downgrade/select-group')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ keep_group_id: g1.body.id });

    expect(first.status).toBe(200);
    expect(first.body.kept_group.id).toBe(g1.body.id);

    const second = await request(app)
      .post('/api/subscriptions/downgrade/select-group')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ keep_group_id: g2.body.id });

    expect(second.status).toBe(400);
    expect(second.body.error?.code).toBe('INVALID_STATE');
    expect(second.body.error?.message).toMatch(/not in over_limit state/i);
  });
});

describe('GET /api/groups/:group_id/export-progress/validate-range', () => {
  it('returns valid for free user with 30-day range', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: true });

    const response = await request(app)
      .get(`/api/groups/${groupId}/export-progress/validate-range?start_date=2025-01-01&end_date=2025-01-30`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      valid: true,
      max_days_allowed: 30,
      requested_days: 30,
      subscription_tier: 'free',
    });
  });

  it('returns 400 for free user with range exceeding 30 days', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: true });

    const response = await request(app)
      .get(`/api/groups/${groupId}/export-progress/validate-range?start_date=2025-01-01&end_date=2025-12-31`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(400);
    expect(response.body).toMatchObject({
      valid: false,
      max_days_allowed: 30,
      requested_days: 365,
      subscription_tier: 'free',
      error: 'date_range_exceeds_tier_limit',
    });
    expect(response.body.message).toContain('30 days');
  });

  it('returns valid for premium user with 365-day range', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: true });

    const response = await request(app)
      .get(`/api/groups/${groupId}/export-progress/validate-range?start_date=2025-01-01&end_date=2025-12-31`)
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      valid: true,
      max_days_allowed: 365,
      requested_days: 365,
      subscription_tier: 'premium',
    });
  });

  it('returns 401 without auth', async () => {
    const { accessToken } = await createAuthenticatedUser();
    const { groupId } = await createGroupWithGoal(accessToken, { includeGoal: true });

    const response = await request(app)
      .get(`/api/groups/${groupId}/export-progress/validate-range?start_date=2025-01-01&end_date=2025-01-30`);
    expect(response.status).toBe(401);
  });
});

describe('Group limit enforcement', () => {
  it('blocks free user from creating second group', async () => {
    const { accessToken } = await createAuthenticatedUser();
    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'First Group' });

    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Second Group' });

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('GROUP_LIMIT_REACHED');
    expect(response.body.error?.message).toMatch(/Upgrade to Premium/i);
  });

  it('allows premium user to create up to 10 groups', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);

    for (let i = 0; i < 10; i++) {
      const res = await request(app)
        .post('/api/groups')
        .set('Authorization', `Bearer ${accessToken}`)
        .send({ name: `Group ${i}` });
      expect(res.status).toBe(201);
    }

    const response = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'Eleventh Group' });
    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('GROUP_LIMIT_REACHED');
    expect(response.body.error?.message).toMatch(/Maximum groups reached \(10\)/);
  });
});

describe('Over-limit read-only enforcement', () => {
  it('syncs over_limit when free user has multiple groups (GET /me/subscription)', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);
    await request(app).post('/api/groups').set('Authorization', `Bearer ${accessToken}`).send({ name: 'G1' });
    await request(app).post('/api/groups').set('Authorization', `Bearer ${accessToken}`).send({ name: 'G2' });
    await request(app).post('/api/groups').set('Authorization', `Bearer ${accessToken}`).send({ name: 'G3' });
    await testDb
      .updateTable('users')
      .set({ current_subscription_tier: 'free', subscription_status: 'active', group_limit: 1 })
      .where('id', '=', userId)
      .execute();

    const response = await request(app)
      .get('/api/users/me/subscription')
      .set('Authorization', `Bearer ${accessToken}`);

    expect(response.status).toBe(200);
    expect(response.body).toMatchObject({
      tier: 'free',
      status: 'over_limit',
      current_group_count: 3,
      group_limit: 1,
      is_over_limit: true,
    });
  });

  it('blocks progress logging when over_limit and no group selected (403 SUBSCRIPTION_GROUP_SELECTION_REQUIRED)', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);
    const { groupId, goalId } = await createGroupWithGoal(accessToken, { includeGoal: true });
    await testDb
      .updateTable('users')
      .set({ current_subscription_tier: 'free', subscription_status: 'over_limit', group_limit: 1 })
      .where('id', '=', userId)
      .execute();

    const response = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalId,
        value: 1,
        user_date: '2025-01-15',
        user_timezone: 'America/New_York',
      });

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('SUBSCRIPTION_GROUP_SELECTION_REQUIRED');
    expect(response.body.error?.message).toMatch(/Select which group/i);
  });

  it('blocks goal create when over_limit and no group selected', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);
    const createRes = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ name: 'OverLimit Group' });
    const groupId = createRes.body.id;
    await testDb
      .updateTable('users')
      .set({ current_subscription_tier: 'free', subscription_status: 'over_limit', group_limit: 1 })
      .where('id', '=', userId)
      .execute();

    const response = await request(app)
      .post(`/api/groups/${groupId}/goals`)
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ title: 'New Goal', cadence: 'daily', metric_type: 'binary' });

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('SUBSCRIPTION_GROUP_SELECTION_REQUIRED');
  });

  it('allows progress in kept group after downgrade selection', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);
    const g1 = await request(app).post('/api/groups').set('Authorization', `Bearer ${accessToken}`).send({ name: 'Keep' });
    await request(app).post(`/api/groups/${g1.body.id}/goals`).set('Authorization', `Bearer ${accessToken}`).send({ title: 'Goal in kept', cadence: 'daily', metric_type: 'binary' });
    const goalInG1 = await testDb.selectFrom('goals').select('id').where('group_id', '=', g1.body.id).executeTakeFirstOrThrow();
    await request(app).post('/api/groups').set('Authorization', `Bearer ${accessToken}`).send({ name: 'Other' });
    await testDb
      .updateTable('users')
      .set({ current_subscription_tier: 'free', subscription_status: 'over_limit', group_limit: 1 })
      .where('id', '=', userId)
      .execute();
    await request(app)
      .post('/api/subscriptions/downgrade/select-group')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ keep_group_id: g1.body.id });

    const response = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalInG1.id,
        value: 1,
        user_date: '2025-01-15',
        user_timezone: 'America/New_York',
      });

    expect(response.status).toBe(201);
    expect(response.body.goal_id).toBe(goalInG1.id);
  });

  it('blocks progress in read-only group (not kept) after downgrade selection', async () => {
    const { accessToken, userId } = await createAuthenticatedUser();
    await setUserPremium(userId);
    const g1 = await request(app).post('/api/groups').set('Authorization', `Bearer ${accessToken}`).send({ name: 'Keep' });
    const g2 = await request(app).post('/api/groups').set('Authorization', `Bearer ${accessToken}`).send({ name: 'ReadOnly' });
    await request(app).post(`/api/groups/${g2.body.id}/goals`).set('Authorization', `Bearer ${accessToken}`).send({ title: 'Goal', cadence: 'daily', metric_type: 'binary' });
    const goalInG2 = await testDb.selectFrom('goals').select('id').where('group_id', '=', g2.body.id).executeTakeFirstOrThrow();
    await testDb
      .updateTable('users')
      .set({ current_subscription_tier: 'free', subscription_status: 'over_limit', group_limit: 1 })
      .where('id', '=', userId)
      .execute();
    await request(app)
      .post('/api/subscriptions/downgrade/select-group')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({ keep_group_id: g1.body.id });

    const response = await request(app)
      .post('/api/progress')
      .set('Authorization', `Bearer ${accessToken}`)
      .send({
        goal_id: goalInG2.id,
        value: 1,
        user_date: '2025-01-15',
        user_timezone: 'America/New_York',
      });

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('GROUP_READ_ONLY');
    expect(response.body.error?.message).toMatch(/read-only/);
  });
});
