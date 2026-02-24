import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, randomEmail, setUserPremium } from '../../helpers';

async function seedOngoingTemplate(slug: string) {
  const template = await testDb
    .insertInto('group_templates')
    .values({
      slug,
      title: `Ongoing Template ${slug}`,
      description: `Description for ${slug}`,
      icon_emoji: '🌱',
      icon_url: null,
      duration_days: null,
      category: 'lifestyle',
      difficulty: 'easy',
      is_featured: false,
      is_challenge: false,
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
        description: 'First goal',
        cadence: 'daily',
        metric_type: 'binary',
        target_value: null,
        unit: null,
        active_days: null,
        log_title_prompt: null,
        sort_order: 0,
      },
      {
        template_id: template.id,
        title: 'Goal B',
        description: 'Second goal',
        cadence: 'daily',
        metric_type: 'journal',
        target_value: null,
        unit: null,
        active_days: null,
        log_title_prompt: 'What did you do?',
        sort_order: 1,
      },
    ])
    .execute();

  return template;
}

async function seedChallengeTemplate(slug: string) {
  const template = await testDb
    .insertInto('group_templates')
    .values({
      slug,
      title: `Challenge Template ${slug}`,
      description: `Challenge description for ${slug}`,
      icon_emoji: '🏆',
      icon_url: null,
      duration_days: 30,
      category: 'fitness',
      difficulty: 'moderate',
      is_featured: false,
      is_challenge: true,
      sort_order: 1,
    })
    .returning(['id', 'slug'])
    .executeTakeFirstOrThrow();

  return template;
}

describe('Ongoing Group Templates — POST /api/groups with template_id', () => {
  it('creates group from ongoing template with 201 and template_id set', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const template = await seedOngoingTemplate('create-basic');

    const res = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ name: 'My Group', template_id: template.id });

    expect(res.status).toBe(201);
    expect(res.body.template_id).toBe(template.id);
    expect(res.body.goals).toHaveLength(2);
  });

  it('copies goal fields correctly including log_title_prompt', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const template = await seedOngoingTemplate('create-goal-fields');

    const res = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ name: 'My Group', template_id: template.id });

    expect(res.status).toBe(201);
    const goalA = res.body.goals.find((g: any) => g.title === 'Goal A');
    const goalB = res.body.goals.find((g: any) => g.title === 'Goal B');
    expect(goalA).toBeDefined();
    expect(goalA.log_title_prompt).toBeNull();
    expect(goalB).toBeDefined();
    expect(goalB.log_title_prompt).toBe('What did you do?');
  });

  it('uses template icon_emoji as fallback when caller provides none', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const template = await seedOngoingTemplate('icon-fallback');

    const res = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ name: 'My Group', template_id: template.id });

    expect(res.status).toBe(201);
    expect(res.body.icon_emoji).toBe('🌱');
  });

  it('caller-provided icon_emoji overrides template icon', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const template = await seedOngoingTemplate('icon-override');

    const res = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ name: 'My Group', template_id: template.id, icon_emoji: '🔥' });

    expect(res.status).toBe(201);
    expect(res.body.icon_emoji).toBe('🔥');
  });

  it('ignores initial_goals when template_id is provided', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const template = await seedOngoingTemplate('ignore-initial-goals');

    const res = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({
        name: 'My Group',
        template_id: template.id,
        initial_goals: [{ title: 'Extra Goal', cadence: 'daily', metric_type: 'binary' }],
      });

    expect(res.status).toBe(201);
    // Only the 2 template goals, not the extra initial_goal
    expect(res.body.goals).toHaveLength(2);
    expect(res.body.goals.every((g: any) => g.title !== 'Extra Goal')).toBe(true);
  });

  it('rejects a challenge template with 400 VALIDATION_ERROR', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    const template = await seedChallengeTemplate('reject-challenge-tmpl');

    const res = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ name: 'My Group', template_id: template.id });

    expect(res.status).toBe(400);
    expect(res.body.error?.code).toBe('VALIDATION_ERROR');
  });

  it('returns 404 NOT_FOUND for non-existent template_id', async () => {
    const user = await createAuthenticatedUser(randomEmail());

    const res = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ name: 'My Group', template_id: '00000000-0000-0000-0000-000000000000' });

    expect(res.status).toBe(404);
    expect(res.body.error?.code).toBe('NOT_FOUND');
  });

  it('still enforces group limit when template_id is provided', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    // Free tier allows 1 group — create one first
    await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ name: 'First Group' });

    const template = await seedOngoingTemplate('group-limit');

    const res = await request(app)
      .post('/api/groups')
      .set('Authorization', `Bearer ${user.accessToken}`)
      .send({ name: 'Second Group', template_id: template.id });

    expect(res.status).toBe(403);
    expect(res.body.error?.code).toBe('GROUP_LIMIT_REACHED');
  });
});

describe('GET /api/group-templates?is_challenge filter', () => {
  it('returns only ongoing templates when is_challenge=false', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    await seedOngoingTemplate('filter-ongoing-1');
    await seedOngoingTemplate('filter-ongoing-2');
    await seedChallengeTemplate('filter-challenge-1');

    const res = await request(app)
      .get('/api/group-templates?is_challenge=false')
      .set('Authorization', `Bearer ${user.accessToken}`);

    expect(res.status).toBe(200);
    expect(res.body.templates.length).toBeGreaterThanOrEqual(2);
    expect(res.body.templates.every((t: any) => t.is_challenge === false)).toBe(true);
  });

  it('returns only challenge templates when is_challenge=true', async () => {
    const user = await createAuthenticatedUser(randomEmail());
    await seedOngoingTemplate('filter-ongoing-solo');
    await seedChallengeTemplate('filter-challenge-solo');

    const res = await request(app)
      .get('/api/group-templates?is_challenge=true')
      .set('Authorization', `Bearer ${user.accessToken}`);

    expect(res.status).toBe(200);
    expect(res.body.templates.length).toBeGreaterThanOrEqual(1);
    expect(res.body.templates.every((t: any) => t.is_challenge === true)).toBe(true);
  });
});
