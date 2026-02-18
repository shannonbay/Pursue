import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, randomEmail } from '../../helpers';

function datePlus(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

async function seedTemplate(slug: string) {
  const template = await testDb
    .insertInto('challenge_templates')
    .values({
      slug,
      title: `Template ${slug}`,
      description: 'Template desc',
      icon_emoji: 'X',
      duration_days: 30,
      category: 'fitness',
      difficulty: 'moderate',
      is_featured: false,
      sort_order: 1,
    })
    .returning(['id'])
    .executeTakeFirstOrThrow();

  await testDb
    .insertInto('challenge_template_goals')
    .values({
      template_id: template.id,
      title: 'Goal',
      description: null,
      cadence: 'daily',
      metric_type: 'binary',
      target_value: null,
      unit: null,
      sort_order: 0,
    })
    .execute();

  return template.id;
}

describe('Challenge join rules', () => {
  it('allows join for upcoming and active challenges', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const joiner = await createAuthenticatedUser(randomEmail());
    const templateId = await seedTemplate('join-upcoming-active');

    const createRes = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ template_id: templateId, start_date: datePlus(1) });
    expect(createRes.status).toBe(201);
    const groupId = createRes.body.challenge.id;

    const inviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creator.accessToken}`);
    expect(inviteRes.status).toBe(200);

    const joinUpcoming = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joiner.accessToken}`)
      .send({ invite_code: inviteRes.body.invite_code });
    expect(joinUpcoming.status).toBe(200);

    await testDb
      .updateTable('groups')
      .set({ challenge_status: 'active', challenge_start_date: datePlus(-1), challenge_end_date: datePlus(20) })
      .where('id', '=', groupId)
      .execute();

    const joiner2 = await createAuthenticatedUser(randomEmail());
    const joinActive = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joiner2.accessToken}`)
      .send({ invite_code: inviteRes.body.invite_code });
    expect(joinActive.status).toBe(200);
  });

  it('rejects join for completed/cancelled challenges', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const joiner = await createAuthenticatedUser(randomEmail());
    const templateId = await seedTemplate('join-ended');

    const createRes = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ template_id: templateId, start_date: datePlus(0) });
    const groupId = createRes.body.challenge.id;

    const inviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creator.accessToken}`);

    await testDb
      .updateTable('groups')
      .set({ challenge_status: 'completed' })
      .where('id', '=', groupId)
      .execute();
    const completedJoin = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joiner.accessToken}`)
      .send({ invite_code: inviteRes.body.invite_code });
    expect(completedJoin.status).toBe(409);
    expect(completedJoin.body.error?.code).toBe('CHALLENGE_ENDED');

    await testDb
      .updateTable('groups')
      .set({ challenge_status: 'cancelled' })
      .where('id', '=', groupId)
      .execute();
    const cancelledJoin = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${joiner.accessToken}`)
      .send({ invite_code: inviteRes.body.invite_code });
    expect(cancelledJoin.status).toBe(409);
    expect(cancelledJoin.body.error?.code).toBe('CHALLENGE_ENDED');
  });

  it('keeps regular-group and challenge limits independent for free users', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const templateId = await seedTemplate('independent-limits');

    const regularGroup = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ name: 'Regular Group' });
    expect(regularGroup.status).toBe(201);

    const challenge = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ template_id: templateId, start_date: datePlus(0) });
    expect(challenge.status).toBe(201);
  });
});
