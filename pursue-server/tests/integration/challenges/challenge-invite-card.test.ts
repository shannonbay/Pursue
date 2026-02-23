import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, randomEmail } from '../../helpers';

function datePlus(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

async function seedTemplate(slug: string): Promise<string> {
  const template = await testDb
    .insertInto('group_templates')
    .values({
      slug,
      title: `Template ${slug}`,
      description: 'Template desc',
      icon_emoji: 'X',
      duration_days: 21,
      category: 'fitness',
      difficulty: 'moderate',
      is_featured: false,
      sort_order: 1,
    })
    .returning('id')
    .executeTakeFirstOrThrow();

  await testDb
    .insertInto('group_template_goals')
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

describe('Challenge invite cards', () => {
  it('returns stable per-user referral token and different tokens for different users', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const member = await createAuthenticatedUser(randomEmail());
    const templateId = await seedTemplate('invite-card-referrals');

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

    const joinRes = await request(app)
      .post('/api/groups/join')
      .set('Authorization', `Bearer ${member.accessToken}`)
      .send({ invite_code: inviteRes.body.invite_code });
    expect(joinRes.status).toBe(200);

    const creatorInviteA = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creator.accessToken}`);
    const creatorInviteB = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creator.accessToken}`);
    const memberInvite = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${member.accessToken}`);

    expect(creatorInviteA.status).toBe(200);
    expect(creatorInviteB.status).toBe(200);
    expect(memberInvite.status).toBe(200);
    expect(creatorInviteA.body.invite_card_data.referral_token).toBe(
      creatorInviteB.body.invite_card_data.referral_token
    );
    expect(memberInvite.body.invite_card_data.referral_token).not.toBe(
      creatorInviteA.body.invite_card_data.referral_token
    );
  });

  it('persists base card data on groups and self-heals when missing', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const templateId = await seedTemplate('invite-card-self-heal');

    const createRes = await request(app)
      .post('/api/challenges')
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({ template_id: templateId, start_date: datePlus(1) });
    expect(createRes.status).toBe(201);
    const groupId = createRes.body.challenge.id;

    const groupRow = await testDb
      .selectFrom('groups')
      .select('challenge_invite_card_data')
      .where('id', '=', groupId)
      .executeTakeFirstOrThrow();
    expect(groupRow.challenge_invite_card_data).not.toBeNull();
    expect((groupRow.challenge_invite_card_data as Record<string, unknown>).card_type).toBe('challenge_invite');
    expect((groupRow.challenge_invite_card_data as Record<string, unknown>).referral_token).toBeUndefined();

    await testDb
      .updateTable('groups')
      .set({ challenge_invite_card_data: null })
      .where('id', '=', groupId)
      .execute();

    const inviteRes = await request(app)
      .get(`/api/groups/${groupId}/invite`)
      .set('Authorization', `Bearer ${creator.accessToken}`);
    expect(inviteRes.status).toBe(200);
    expect(inviteRes.body.invite_card_data).toBeTruthy();

    const healedRow = await testDb
      .selectFrom('groups')
      .select('challenge_invite_card_data')
      .where('id', '=', groupId)
      .executeTakeFirstOrThrow();
    expect(healedRow.challenge_invite_card_data).not.toBeNull();
  });

  it('uses challenge deep-link and challenge_card UTM parameters in share and qr URLs', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const templateId = await seedTemplate('invite-card-utm-contract');

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

    const card = inviteRes.body.invite_card_data as Record<string, string>;
    expect(card.share_url).toContain(`/challenge/${inviteRes.body.invite_code}`);
    expect(card.qr_url).toContain(`/challenge/${inviteRes.body.invite_code}`);
    expect(card.share_url).toContain('utm_source=share');
    expect(card.qr_url).toContain('utm_source=qr');
    expect(card.share_url).toContain('utm_medium=challenge_card');
    expect(card.qr_url).toContain('utm_medium=challenge_card');
    expect(card.share_url).toContain('utm_campaign=');
    expect(card.qr_url).toContain('utm_campaign=');
    expect(card.share_url).toContain('ref=');
    expect(card.qr_url).toContain('ref=');
  });
});
