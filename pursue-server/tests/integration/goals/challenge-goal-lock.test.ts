import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, randomEmail } from '../../helpers';

function datePlus(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

describe('Challenge goal lock', () => {
  it('blocks goal creation for active challenges', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const group = await testDb
      .insertInto('groups')
      .values({
        name: 'Active Challenge',
        creator_user_id: creator.userId,
        is_challenge: true,
        challenge_start_date: datePlus(-3),
        challenge_end_date: datePlus(10),
        challenge_status: 'active',
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('group_memberships')
      .values({
        group_id: group.id,
        user_id: creator.userId,
        role: 'creator',
        status: 'active',
      })
      .execute();

    const response = await request(app)
      .post(`/api/groups/${group.id}/goals`)
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({
        title: 'New Goal',
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(403);
    expect(response.body.error?.code).toBe('CHALLENGE_GOALS_LOCKED');
  });

  it('allows goal creation for upcoming challenges', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const group = await testDb
      .insertInto('groups')
      .values({
        name: 'Upcoming Challenge',
        creator_user_id: creator.userId,
        is_challenge: true,
        challenge_start_date: datePlus(3),
        challenge_end_date: datePlus(33),
        challenge_status: 'upcoming',
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('group_memberships')
      .values({
        group_id: group.id,
        user_id: creator.userId,
        role: 'creator',
        status: 'active',
      })
      .execute();

    const response = await request(app)
      .post(`/api/groups/${group.id}/goals`)
      .set('Authorization', `Bearer ${creator.accessToken}`)
      .send({
        title: 'Allowed Goal',
        cadence: 'daily',
        metric_type: 'binary',
      });

    expect(response.status).toBe(201);
  });
});
