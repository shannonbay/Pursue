import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, randomEmail } from '../../helpers';

function datePlus(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

describe('Challenge lifecycle job', () => {
  it('requires internal job key', async () => {
    const response = await request(app).post('/api/internal/jobs/update-challenge-statuses');
    expect(response.status).toBe(401);
  });

  it('transitions statuses and creates completion notifications with shareable card data', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const member = await createAuthenticatedUser(randomEmail());

    const upcoming = await testDb
      .insertInto('groups')
      .values({
        name: 'Upcoming Challenge',
        creator_user_id: creator.userId,
        is_challenge: true,
        challenge_start_date: datePlus(-1),
        challenge_end_date: datePlus(10),
        challenge_status: 'upcoming',
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    const completed = await testDb
      .insertInto('groups')
      .values({
        name: 'Finishing Challenge',
        creator_user_id: creator.userId,
        is_challenge: true,
        challenge_start_date: datePlus(-10),
        challenge_end_date: datePlus(-1),
        challenge_status: 'active',
        icon_emoji: 'Y',
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('group_memberships')
      .values([
        { group_id: upcoming.id, user_id: creator.userId, role: 'creator', status: 'active' },
        { group_id: completed.id, user_id: creator.userId, role: 'creator', status: 'active' },
        { group_id: completed.id, user_id: member.userId, role: 'member', status: 'active' },
      ])
      .execute();

    const goal = await testDb
      .insertInto('goals')
      .values({
        group_id: completed.id,
        title: 'Daily check',
        cadence: 'daily',
        metric_type: 'binary',
        created_by_user_id: creator.userId,
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('progress_entries')
      .values({
        goal_id: goal.id,
        user_id: member.userId,
        value: 1,
        period_start: datePlus(-1),
      })
      .execute();

    const response = await request(app)
      .post('/api/internal/jobs/update-challenge-statuses')
      .set('x-internal-job-key', process.env.INTERNAL_JOB_KEY!);
    expect(response.status).toBe(200);
    expect(response.body.success).toBe(true);
    expect(response.body.activated).toBeGreaterThanOrEqual(1);
    expect(response.body.completed).toBeGreaterThanOrEqual(1);

    const upcomingRow = await testDb
      .selectFrom('groups')
      .select('challenge_status')
      .where('id', '=', upcoming.id)
      .executeTakeFirstOrThrow();
    expect(upcomingRow.challenge_status).toBe('active');

    const completedRow = await testDb
      .selectFrom('groups')
      .select('challenge_status')
      .where('id', '=', completed.id)
      .executeTakeFirstOrThrow();
    expect(completedRow.challenge_status).toBe('completed');

    const notifications = await testDb
      .selectFrom('user_notifications')
      .select(['user_id', 'type', 'metadata', 'shareable_card_data'])
      .where('group_id', '=', completed.id)
      .where('type', '=', 'milestone_achieved')
      .execute();
    expect(notifications.length).toBeGreaterThanOrEqual(2);
    expect(notifications.every((n) => (n.metadata as any)?.milestone_type === 'challenge_completed')).toBe(true);
    expect(notifications.every((n) => n.shareable_card_data != null)).toBe(true);

    const activity = await testDb
      .selectFrom('group_activities')
      .select('id')
      .where('group_id', '=', completed.id)
      .where('activity_type', '=', 'challenge_completed')
      .executeTakeFirst();
    expect(activity).toBeDefined();
  });
});
