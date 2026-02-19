import request from 'supertest';
import { app } from '../../../src/app';
import { testDb } from '../../setup';
import { createAuthenticatedUser, randomEmail } from '../../helpers';

function datePlus(days: number): string {
  const d = new Date();
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

async function createCompletedChallengeForUsers(
  creatorUserId: string,
  memberUserId: string,
  name: string
): Promise<string> {
  const group = await testDb
    .insertInto('groups')
    .values({
      name,
      creator_user_id: creatorUserId,
      is_challenge: true,
      challenge_start_date: datePlus(-10),
      challenge_end_date: datePlus(-2),
      challenge_status: 'active',
      icon_emoji: 'X',
    })
    .returning('id')
    .executeTakeFirstOrThrow();

  await testDb
    .insertInto('group_memberships')
    .values([
      { group_id: group.id, user_id: creatorUserId, role: 'creator', status: 'active' },
      { group_id: group.id, user_id: memberUserId, role: 'member', status: 'active' },
    ])
    .execute();

  const goal = await testDb
    .insertInto('goals')
    .values({
      group_id: group.id,
      title: 'Completion Goal',
      cadence: 'daily',
      metric_type: 'binary',
      created_by_user_id: creatorUserId,
    })
    .returning('id')
    .executeTakeFirstOrThrow();

  await testDb
    .insertInto('progress_entries')
    .values({
      goal_id: goal.id,
      user_id: memberUserId,
      value: 1,
      period_start: datePlus(-2),
    })
    .execute();

  return group.id;
}

describe('Challenge completion cards', () => {
  it('includes image-first payload fields and root referral share URLs', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const member = await createAuthenticatedUser(randomEmail());

    const groupId = await createCompletedChallengeForUsers(
      creator.userId,
      member.userId,
      `Completion Card ${Date.now()}`
    );

    const response = await request(app)
      .post('/api/internal/jobs/update-challenge-statuses')
      .set('x-internal-job-key', process.env.INTERNAL_JOB_KEY!);
    expect(response.status).toBe(200);

    const notifications = await testDb
      .selectFrom('user_notifications')
      .select(['user_id', 'shareable_card_data'])
      .where('group_id', '=', groupId)
      .where('type', '=', 'milestone_achieved')
      .execute();

    expect(notifications).toHaveLength(2);

    const byUser = new Map(notifications.map((n) => [n.user_id, n]));
    const creatorCard = byUser.get(creator.userId)?.shareable_card_data as Record<string, unknown>;
    const memberCard = byUser.get(member.userId)?.shareable_card_data as Record<string, unknown>;
    expect(creatorCard).toBeTruthy();
    expect(memberCard).toBeTruthy();

    expect(creatorCard.card_type).toBe('challenge_completion');
    expect(creatorCard.milestone_type).toBe('challenge_completed');
    expect(creatorCard.background_image_url).toEqual(expect.stringContaining('/assets/challenge_completion_background.png'));
    expect(creatorCard.background_gradient).toEqual(expect.any(Array));
    expect(creatorCard.generated_at).toEqual(expect.any(String));
    expect(creatorCard.referral_token).toEqual(expect.any(String));
    expect(creatorCard.share_url).toEqual(expect.any(String));
    expect(creatorCard.qr_url).toEqual(expect.any(String));

    const creatorShareUrl = String(creatorCard.share_url);
    const creatorQrUrl = String(creatorCard.qr_url);
    expect(creatorShareUrl).toContain('utm_source=share');
    expect(creatorQrUrl).toContain('utm_source=qr');
    expect(creatorShareUrl).toContain('utm_medium=challenge_completion_card');
    expect(creatorQrUrl).toContain('utm_medium=challenge_completion_card');
    expect(creatorShareUrl).toContain('utm_campaign=challenge_completed');
    expect(creatorQrUrl).toContain('utm_campaign=challenge_completed');
    expect(creatorShareUrl).toContain('ref=');
    expect(creatorQrUrl).toContain('ref=');
    expect(creatorShareUrl).not.toContain('/challenge/');
    expect(creatorQrUrl).not.toContain('/challenge/');
    expect(creatorShareUrl).not.toContain(creator.userId);
    expect(creatorQrUrl).not.toContain(creator.userId);

    expect(creatorCard.referral_token).not.toBe(memberCard.referral_token);
  });

  it('keeps referral token stable per user across multiple completion cards', async () => {
    const creator = await createAuthenticatedUser(randomEmail());
    const member = await createAuthenticatedUser(randomEmail());

    const firstGroupId = await createCompletedChallengeForUsers(
      creator.userId,
      member.userId,
      `Completion Stability A ${Date.now()}`
    );
    const secondGroupId = await createCompletedChallengeForUsers(
      creator.userId,
      member.userId,
      `Completion Stability B ${Date.now()}`
    );

    const response = await request(app)
      .post('/api/internal/jobs/update-challenge-statuses')
      .set('x-internal-job-key', process.env.INTERNAL_JOB_KEY!);
    expect(response.status).toBe(200);

    const firstCreatorNotification = await testDb
      .selectFrom('user_notifications')
      .select('shareable_card_data')
      .where('group_id', '=', firstGroupId)
      .where('user_id', '=', creator.userId)
      .where('type', '=', 'milestone_achieved')
      .executeTakeFirstOrThrow();
    const secondCreatorNotification = await testDb
      .selectFrom('user_notifications')
      .select('shareable_card_data')
      .where('group_id', '=', secondGroupId)
      .where('user_id', '=', creator.userId)
      .where('type', '=', 'milestone_achieved')
      .executeTakeFirstOrThrow();
    const firstMemberNotification = await testDb
      .selectFrom('user_notifications')
      .select('shareable_card_data')
      .where('group_id', '=', firstGroupId)
      .where('user_id', '=', member.userId)
      .where('type', '=', 'milestone_achieved')
      .executeTakeFirstOrThrow();
    const secondMemberNotification = await testDb
      .selectFrom('user_notifications')
      .select('shareable_card_data')
      .where('group_id', '=', secondGroupId)
      .where('user_id', '=', member.userId)
      .where('type', '=', 'milestone_achieved')
      .executeTakeFirstOrThrow();

    const creatorTokenA = (firstCreatorNotification.shareable_card_data as Record<string, unknown>).referral_token;
    const creatorTokenB = (secondCreatorNotification.shareable_card_data as Record<string, unknown>).referral_token;
    const memberTokenA = (firstMemberNotification.shareable_card_data as Record<string, unknown>).referral_token;
    const memberTokenB = (secondMemberNotification.shareable_card_data as Record<string, unknown>).referral_token;

    expect(creatorTokenA).toBe(creatorTokenB);
    expect(memberTokenA).toBe(memberTokenB);
    expect(creatorTokenA).not.toBe(memberTokenA);
  });
});
