import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, randomEmail, setUserPremium } from '../../helpers';

function datePlus(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

async function seedTemplate(slug: string, opts?: { category?: string; featured?: boolean; duration?: number }) {
  const template = await testDb
    .insertInto('group_templates')
    .values({
      slug,
      title: `Template ${slug}`,
      description: `Description for ${slug}`,
      icon_emoji: 'T',
      duration_days: opts?.duration ?? 30,
      category: opts?.category ?? 'fitness',
      difficulty: 'moderate',
      is_featured: opts?.featured ?? false,
      sort_order: 1,
    })
    .returning(['id', 'slug'])
    .executeTakeFirstOrThrow();

  await testDb
    .insertInto('group_template_goals')
    .values([
      {
        template_id: template.id,
        title: 'Goal A',
        description: 'First',
        cadence: 'daily',
        metric_type: 'binary',
        target_value: null,
        unit: null,
        sort_order: 0,
      },
      {
        template_id: template.id,
        title: 'Goal B',
        description: 'Second',
        cadence: 'daily',
        metric_type: 'numeric',
        target_value: 2,
        unit: 'units',
        sort_order: 1,
      },
    ])
    .execute();

  return template;
}

describe('Challenges API', () => {
  it('requires auth for GET /api/group-templates', async () => {
    const response = await request(app).get('/api/group-templates');
    expect(response.status).toBe(401);
  });

  it('lists templates with goals and supports filters', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    await seedTemplate('fitness-one', { category: 'fitness', featured: true });
    await seedTemplate('reading-one', { category: 'reading', featured: false });

    const allRes = await request(app)
      .get('/api/group-templates')
      .set('Authorization', `Bearer ${user.accessToken}`);
    expect(allRes.status).toBe(200);
    expect(allRes.body.templates.length).toBeGreaterThanOrEqual(2);
    expect(allRes.body.categories).toEqual(expect.arrayContaining(['fitness', 'reading']));
    expect(allRes.body.templates[0].goals.length).toBeGreaterThan(0);

    const featuredRes = await request(app)
      .get('/api/group-templates?featured=true')
      .set('Authorization', `Bearer ${user.accessToken}`);
    expect(featuredRes.status).toBe(200);
    expect(featuredRes.body.templates.every((t: any) => t.is_featured === true)).toBe(true);
  });

  it('creates a template challenge', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const template = await seedTemplate('template-create', { duration: 21 });
    const startDate = datePlus(1);

    const response = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        template_id: template.id,
        start_date: startDate,
        group_name: 'My Challenge',
      });

    expect(response.status).toBe(201);
    expect(response.body.challenge.is_challenge).toBe(true);
    expect(response.body.challenge.challenge_status).toBe('upcoming');
    expect(response.body.challenge.template_id).toBe(template.id);
    expect(response.body.challenge.goals.length).toBe(2);
    expect(response.body.challenge.invite_card_data).toMatchObject({
      card_type: 'challenge_invite',
      cta_text: 'Are you in?',
    });
    expect(response.body.challenge.invite_card_data.share_url).toContain('/challenge/');
    expect(response.body.challenge.invite_card_data.qr_url).toContain('/challenge/');
    expect(response.body.challenge.invite_card_data.share_url).toContain('ref=');
    expect(response.body.challenge.invite_card_data.qr_url).toContain('ref=');
    expect(response.body.challenge.invite_card_data.share_url).not.toContain(user.userId);
    expect(response.body.challenge.invite_card_data.qr_url).not.toContain(user.userId);
  });

  it('rejects custom challenges for free users', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const response = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        start_date: datePlus(1),
        end_date: datePlus(10),
        group_name: 'Custom',
        goals: [{ title: 'Do it', cadence: 'daily', metric_type: 'binary' }],
      });

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('PREMIUM_REQUIRED');
  });

  it('allows custom challenges for premium users', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    await setUserPremium(user.userId);

    const response = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        start_date: datePlus(1),
        end_date: datePlus(10),
        group_name: 'Premium Custom',
        icon_emoji: 'Z',
        goals: [{ title: 'Do it', cadence: 'daily', metric_type: 'binary' }],
      });

    expect(response.status).toBe(201);
    expect(response.body.challenge.name).toBe('Premium Custom');
    expect(response.body.challenge.goals.length).toBe(1);
  });

  it('enforces free combined challenge cap: create then join is denied', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const other = await createAuthenticatedUser(randomEmail());
    const template = await seedTemplate('cap-create-join');

    const createRes = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ template_id: template.id, start_date: datePlus(0) });
    expect(createRes.status).toBe(201);

    const otherChallenge = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${other.accessToken}`)
      .send({ template_id: template.id, start_date: datePlus(0) });
    expect(otherChallenge.status).toBe(201);

    const inviteRes = await request(app)
      .get(`/api/groups/${otherChallenge.body.challenge.id}/invite`)
      .set('Authorization', `Bearer ${other.accessToken}`);
    expect(inviteRes.status).toBe(200);

    const joinRes = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ invite_code: inviteRes.body.invite_code });
    expect(joinRes.status).toBe(403);
    expect(joinRes.body.error?.code).toBe('GROUP_LIMIT_REACHED');
  });

  it('enforces free combined challenge cap: join then create is denied once approved', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const creator = await createAuthenticatedUser(randomEmail());
    const template = await seedTemplate('cap-join-create');

    const challengeRes = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ template_id: template.id, start_date: datePlus(0) });
    expect(challengeRes.status).toBe(201);

    const inviteRes = await request(app)
      .get(`/api/groups/${challengeRes.body.challenge.id}/invite`)
      .set('Authorization', `Bearer ${creator.accessToken}`);
    expect(inviteRes.status).toBe(200);

    const joinRes = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ invite_code: inviteRes.body.invite_code });
    expect(joinRes.status).toBe(200);

    const approveRes = await request(app)
      .post(`/api/groups/${challengeRes.body.challenge.id}/members/${user.userId}/approve`)
      .set('Authorization', `Bearer ${creator.accessToken}`);
    expect(approveRes.status).toBe(200);

    const createRes = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ template_id: template.id, start_date: datePlus(0) });
    expect(createRes.status).toBe(403);
    expect(createRes.body.error?.code).toBe('GROUP_LIMIT_REACHED');
  });

  it('lists user challenges and supports status filtering', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const template = await seedTemplate('list-status');
    const createRes = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ template_id: template.id, start_date: datePlus(1) });
    expect(createRes.status).toBe(201);

    const groupId = createRes.body.challenge.id;
    await testDb
      .updateTable('groups')
      .set({ challenge_status: 'active', challenge_start_date: datePlus(-1), challenge_end_date: datePlus(20) })
      .where('id', '=', groupId)
      .execute();

    const activeRes = await request(app)
      .get('/api/challenges?status=active')
      .set('Authorization', `Bearer ${user.accessToken}`);
    expect(activeRes.status).toBe(200);
    expect(activeRes.body.challenges.length).toBe(1);
    expect(activeRes.body.challenges[0].challenge_status).toBe('active');
    expect(activeRes.body.challenges[0]).toHaveProperty('my_completion_rate');
  });

  it('enforces cancel rules and supports successful cancel', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const other = await createAuthenticatedUser(randomEmail());
    const template = await seedTemplate('cancel-rules');

    const createRes = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ template_id: template.id, start_date: datePlus(2) });
    expect(createRes.status).toBe(201);
    const groupId = createRes.body.challenge.id;

    const inviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creator.accessToken}`);
    await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${other.accessToken}`)
      .send({ invite_code: inviteRes.body.invite_code });

    const nonCreator = await request(app)
      .patch(`/api/challenges/${groupId}/cancel`)
      .set('Authorization', `Bearer ${other.accessToken}`);
    expect(nonCreator.status).toBe(403);

    const cancelRes = await request(app)
      .patch(`/api/challenges/${groupId}/cancel`)
      .set('Authorization', `Bearer ${creator.accessToken}`);
    expect(cancelRes.status).toBe(200);
    expect(cancelRes.body.challenge_status).toBe('cancelled');

    await testDb
      .updateTable('groups')
      .set({ challenge_status: 'completed' })
      .where('id', '=', groupId)
      .execute();
    const completedCancelRes = await request(app)
      .patch(`/api/challenges/${groupId}/cancel`)
      .set('Authorization', `Bearer ${creator.accessToken}`);
    expect(completedCancelRes.status).toBe(400);
  });
});
