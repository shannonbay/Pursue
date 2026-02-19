import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, randomEmail } from '../../helpers';
import { updateChallengeStatuses } from '../../../src/services/challenges.service';

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

  it('accepts force_now in test mode and validates invalid values', async () => {
    const creator = await createAuthenticatedUser(randomEmail());

    const group = await testDb
      .insertInto('groups')
      .values({
        name: 'Force Now Challenge',
        creator_user_id: creator.userId,
        is_challenge: true,
        challenge_start_date: '2026-01-01',
        challenge_end_date: '2026-01-01',
        challenge_status: 'active',
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    const invalid = await request(app)
      .post('/api/internal/jobs/update-challenge-statuses')
      .set('x-internal-job-key', process.env.INTERNAL_JOB_KEY!)
      .send({ force_now: 'not-a-date' });
    expect(invalid.status).toBe(400);
    expect(invalid.body.error?.code).toBe('VALIDATION_ERROR');

    const valid = await request(app)
      .post('/api/internal/jobs/update-challenge-statuses')
      .set('x-internal-job-key', process.env.INTERNAL_JOB_KEY!)
      .send({ force_now: '2030-01-01T00:00:00.000Z' });
    expect(valid.status).toBe(200);

    const row = await testDb
      .selectFrom('groups')
      .select('challenge_status')
      .where('id', '=', group.id)
      .executeTakeFirstOrThrow();
    expect(row.challenge_status).toBe('completed');
  });

  it('requires internal job key for completion push processor', async () => {
    const response = await request(app).post('/api/internal/jobs/process-challenge-completion-pushes');
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
        challenge_end_date: datePlus(-2),
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
        period_start: datePlus(-2),
      })
      .execute();

    const response = await request(app)
      .post('/api/internal/jobs/update-challenge-statuses')
      .set('x-internal-job-key', process.env.INTERNAL_JOB_KEY!);
    expect(response.status).toBe(200);
    expect(response.body.success).toBe(true);
    expect(response.body.activated).toBeGreaterThanOrEqual(1);
    expect(response.body.completed).toBeGreaterThanOrEqual(1);
    expect(response.body.completion_pushes_queued).toBeGreaterThanOrEqual(1);

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
    for (const notification of notifications) {
      const card = notification.shareable_card_data as Record<string, unknown>;
      expect(card.card_type).toBe('challenge_completion');
      expect(card.milestone_type).toBe('challenge_completed');
      expect(card.background_image_url).toEqual(expect.stringContaining('/assets/challenge_completion_background.png'));
      expect(card.background_gradient).toEqual(expect.any(Array));
      expect(card.referral_token).toEqual(expect.any(String));
      expect(card.share_url).toEqual(expect.any(String));
      expect(card.qr_url).toEqual(expect.any(String));
      expect(String(card.share_url)).toContain('utm_source=share');
      expect(String(card.qr_url)).toContain('utm_source=qr');
      expect(String(card.share_url)).toContain('utm_medium=challenge_completion_card');
      expect(String(card.qr_url)).toContain('utm_medium=challenge_completion_card');
      expect(String(card.share_url)).toContain('ref=');
      expect(String(card.qr_url)).toContain('ref=');
      expect(String(card.share_url)).not.toContain('/challenge/');
      expect(String(card.qr_url)).not.toContain('/challenge/');
      expect(String(card.share_url)).not.toContain(notification.user_id);
      expect(String(card.qr_url)).not.toContain(notification.user_id);
    }

    const activity = await testDb
      .selectFrom('group_activities')
      .select('id')
      .where('group_id', '=', completed.id)
      .where('activity_type', '=', 'challenge_completed')
      .executeTakeFirst();
    expect(activity).toBeDefined();

    const queuedPushes = await testDb
      .selectFrom('challenge_completion_push_queue')
      .select(['id', 'status'])
      .where('group_id', '=', completed.id)
      .execute();
    expect(queuedPushes.length).toBeGreaterThanOrEqual(2);
    expect(queuedPushes.every((row) => row.status === 'pending')).toBe(true);
  });

  it('processes due queued completion pushes', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const member = await createAuthenticatedUser(randomEmail());

    const challenge = await testDb
      .insertInto('groups')
      .values({
        name: 'Queued Push Challenge',
        creator_user_id: creator.userId,
        is_challenge: true,
        challenge_start_date: datePlus(-10),
        challenge_end_date: datePlus(-1),
        challenge_status: 'completed',
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    await testDb
      .insertInto('challenge_completion_push_queue')
      .values({
        group_id: challenge.id,
        user_id: member.userId,
        send_at: new Date(Date.now() - 60_000),
        status: 'pending',
        attempt_count: 0,
      })
      .execute();

    const response = await request(app)
      .post('/api/internal/jobs/process-challenge-completion-pushes')
      .set('x-internal-job-key', process.env.INTERNAL_JOB_KEY!);

    expect(response.status).toBe(200);
    expect(response.body.success).toBe(true);
    expect(response.body.processed).toBe(1);
    expect(response.body.sent).toBe(1);

    const row = await testDb
      .selectFrom('challenge_completion_push_queue')
      .select(['status', 'attempt_count'])
      .where('group_id', '=', challenge.id)
      .where('user_id', '=', member.userId)
      .executeTakeFirstOrThrow();

    expect(row.status).toBe('sent');
    expect(row.attempt_count).toBe(1);
  });

  it('uses UTC+14 for activation and UTC-12 for completion boundaries', async () => {
    const creator = await createAuthenticatedUser(randomEmail());

    const boundaryGroup = await testDb
      .insertInto('groups')
      .values({
        name: 'Boundary Challenge',
        creator_user_id: creator.userId,
        is_challenge: true,
        challenge_start_date: '2026-03-01',
        challenge_end_date: '2026-03-01',
        challenge_status: 'upcoming',
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    await updateChallengeStatuses(new Date('2026-02-28T09:30:00Z')); // UTC+14 still 2026-02-28

    let row = await testDb
      .selectFrom('groups')
      .select('challenge_status')
      .where('id', '=', boundaryGroup.id)
      .executeTakeFirstOrThrow();
    expect(row.challenge_status).toBe('upcoming');

    await updateChallengeStatuses(new Date('2026-02-28T10:05:00Z')); // UTC+14 is 2026-03-01

    row = await testDb
      .selectFrom('groups')
      .select('challenge_status')
      .where('id', '=', boundaryGroup.id)
      .executeTakeFirstOrThrow();
    expect(row.challenge_status).toBe('active');

    await updateChallengeStatuses(new Date('2026-03-02T11:59:00Z')); // UTC-12 still 2026-03-01

    row = await testDb
      .selectFrom('groups')
      .select('challenge_status')
      .where('id', '=', boundaryGroup.id)
      .executeTakeFirstOrThrow();
    expect(row.challenge_status).toBe('active');

    await updateChallengeStatuses(new Date('2026-03-02T12:01:00Z')); // UTC-12 is 2026-03-02

    row = await testDb
      .selectFrom('groups')
      .select('challenge_status')
      .where('id', '=', boundaryGroup.id)
      .executeTakeFirstOrThrow();
    expect(row.challenge_status).toBe('completed');
  });
});
