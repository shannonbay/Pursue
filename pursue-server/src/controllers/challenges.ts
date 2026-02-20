import type { NextFunction, Request, Response } from 'express';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import {
  ChallengeIdParamSchema,
  CreateChallengeSchema,
  GetChallengesSchema,
  GetChallengeTemplatesSchema,
} from '../validations/challenges.js';
import {
  canUserCreateOrJoinChallenge,
  getUserSubscriptionState,
} from '../services/subscription.service.js';
import { ensureGroupExists, requireGroupCreator, requireGroupMember } from '../services/authorization.js';
import { createGroupActivity } from '../services/activity.service.js';
import { sendGroupNotification } from '../services/fcm.service.js';
import {
  getChallengeCompletionRateForUser,
  processChallengeCompletionPushes,
  updateChallengeStatuses,
} from '../services/challenges.service.js';
import { getDateInTimezone } from '../utils/timezone.js';
import {
  attachInviteCardAttribution,
  buildChallengeInviteCardBase,
} from '../services/challengeInviteCard.service.js';

function generateInviteCode(): string {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const randomPart = () => {
    let result = '';
    for (let i = 0; i < 6; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    return result;
  };
  return `PURSUE-${randomPart()}-${randomPart()}`;
}

function parseDateOnly(value: string): Date {
  return new Date(`${value}T00:00:00.000Z`);
}

function isoDate(value: Date): string {
  return value.toISOString().slice(0, 10);
}

function addDays(dateStr: string, days: number): string {
  const d = parseDateOnly(dateStr);
  d.setUTCDate(d.getUTCDate() + days);
  return isoDate(d);
}

function daysInclusive(start: string, end: string): number {
  const s = parseDateOnly(start).getTime();
  const e = parseDateOnly(end).getTime();
  if (e < s) return 0;
  return Math.floor((e - s) / (24 * 60 * 60 * 1000)) + 1;
}

// GET /api/challenge-templates
export async function getChallengeTemplates(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const query = GetChallengeTemplatesSchema.parse(req.query);

    let templatesQuery = db
      .selectFrom('challenge_templates')
      .selectAll()
      .orderBy('category', 'asc')
      .orderBy('sort_order', 'asc')
      .orderBy('title', 'asc');

    if (query.category) {
      templatesQuery = templatesQuery.where('category', '=', query.category);
    }
    if (query.featured !== undefined) {
      templatesQuery = templatesQuery.where('is_featured', '=', query.featured);
    }

    const templates = await templatesQuery.execute();
    const templateIds = templates.map((t) => t.id);
    const goals = templateIds.length === 0
      ? []
      : await db
          .selectFrom('challenge_template_goals')
          .select([
            'id',
            'template_id',
            'title',
            'description',
            'cadence',
            'metric_type',
            'target_value',
            'unit',
            'sort_order',
          ])
          .where('template_id', 'in', templateIds)
          .orderBy('template_id', 'asc')
          .orderBy('sort_order', 'asc')
          .execute();

    const goalsByTemplate = new Map<string, typeof goals>();
    for (const goal of goals) {
      if (!goalsByTemplate.has(goal.template_id)) {
        goalsByTemplate.set(goal.template_id, []);
      }
      goalsByTemplate.get(goal.template_id)!.push(goal);
    }

    const categories = [...new Set(templates.map((t) => t.category))];
    res.status(200).json({
      templates: templates.map((template) => ({
        id: template.id,
        slug: template.slug,
        title: template.title,
        description: template.description,
        icon_emoji: template.icon_emoji,
        icon_url: template.icon_url ?? null,
        duration_days: template.duration_days,
        category: template.category,
        difficulty: template.difficulty,
        is_featured: template.is_featured,
        goals: (goalsByTemplate.get(template.id) ?? []).map((g) => ({
          title: g.title,
          description: g.description,
          cadence: g.cadence,
          metric_type: g.metric_type,
          target_value: g.target_value,
          unit: g.unit,
        })),
      })),
      categories,
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/challenges
export async function createChallenge(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = CreateChallengeSchema.parse(req.body);
    const creator = await db
      .selectFrom('users')
      .select('timezone')
      .where('id', '=', req.user.id)
      .executeTakeFirst();
    const today = getDateInTimezone(creator?.timezone ?? 'UTC');
    if (data.start_date < today) {
      throw new ApplicationError('start_date must be today or in the future', 400, 'VALIDATION_ERROR');
    }
    if (data.start_date > addDays(today, 30)) {
      throw new ApplicationError('start_date cannot be more than 30 days in the future', 400, 'VALIDATION_ERROR');
    }

    const challengeEligibility = await canUserCreateOrJoinChallenge(req.user.id);
    if (!challengeEligibility.allowed) {
      const message = challengeEligibility.reason === 'free_tier_limit_reached'
        ? 'Upgrade to Premium to create additional active challenges'
        : 'Maximum active challenges reached (10)';
      throw new ApplicationError(message, 403, 'GROUP_LIMIT_REACHED');
    }

    const userState = await getUserSubscriptionState(req.user.id);
    const isPremium = userState?.current_subscription_tier === 'premium';

    const fromTemplate = Boolean(data.template_id);
    if (!fromTemplate && !isPremium) {
      throw new ApplicationError('Custom challenges require Premium', 403, 'PREMIUM_REQUIRED');
    }

    let template: {
      id: string;
      slug: string;
      title: string;
      icon_emoji: string;
      icon_url: string | null;
      duration_days: number;
    } | null = null;
    let goalsToCreate: Array<{
      title: string;
      description: string | null;
      cadence: string;
      metric_type: string;
      target_value: number | null;
      unit: string | null;
      sort_order: number;
    }> = [];
    let endDate: string;

    if (fromTemplate) {
      template = await db
        .selectFrom('challenge_templates')
        .select(['id', 'slug', 'title', 'icon_emoji', 'icon_url', 'duration_days'])
        .where('id', '=', data.template_id!)
        .executeTakeFirst() ?? null;
      if (!template) {
        throw new ApplicationError('Challenge template not found', 404, 'NOT_FOUND');
      }

      endDate = addDays(data.start_date, template.duration_days - 1);
      const templateGoals = await db
        .selectFrom('challenge_template_goals')
        .select(['title', 'description', 'cadence', 'metric_type', 'target_value', 'unit', 'sort_order'])
        .where('template_id', '=', template.id)
        .orderBy('sort_order', 'asc')
        .execute();
      goalsToCreate = templateGoals.map((g) => ({
        title: g.title,
        description: g.description,
        cadence: g.cadence,
        metric_type: g.metric_type,
        target_value: g.target_value == null ? null : Number(g.target_value),
        unit: g.unit,
        sort_order: g.sort_order,
      }));
    } else {
      if (!data.end_date) {
        throw new ApplicationError('end_date is required for custom challenges', 400, 'VALIDATION_ERROR');
      }
      if (!data.goals || data.goals.length === 0) {
        throw new ApplicationError('goals are required for custom challenges', 400, 'VALIDATION_ERROR');
      }
      if (data.end_date < data.start_date) {
        throw new ApplicationError('end_date must be on or after start_date', 400, 'VALIDATION_ERROR');
      }
      endDate = data.end_date;
      goalsToCreate = data.goals.map((g, i) => ({
        title: g.title,
        description: g.description ?? null,
        cadence: g.cadence,
        metric_type: g.metric_type,
        target_value: g.target_value ?? null,
        unit: g.unit ?? null,
        sort_order: g.sort_order ?? i,
      }));
    }

    const status = data.start_date <= today ? 'active' : 'upcoming';
    const groupName = data.group_name ?? template?.title ?? 'Challenge';
    const iconEmoji = data.icon_emoji ?? template?.icon_emoji ?? null;
    const iconUrl = data.icon_url ?? template?.icon_url ?? null;

    const created = await db.transaction().execute(async (trx) => {
      let inviteCode = '';
      for (let attempt = 0; attempt < 12; attempt++) {
        inviteCode = generateInviteCode();
        const existing = await trx
          .selectFrom('invite_codes')
          .select('id')
          .where('code', '=', inviteCode)
          .executeTakeFirst();
        if (!existing) break;
      }
      if (!inviteCode) {
        throw new ApplicationError('Failed to generate invite code', 500, 'CODE_GENERATION_FAILED');
      }
      const inviteCardBase = buildChallengeInviteCardBase({
        challengeName: groupName,
        startDate: data.start_date,
        endDate,
        iconEmoji,
        inviteCode,
      });

      const group = await trx
        .insertInto('groups')
        .values({
          name: groupName,
          description: data.group_description ?? null,
          icon_emoji: iconEmoji,
          icon_color: null,
          icon_url: iconUrl,
          creator_user_id: req.user!.id,
          is_challenge: true,
          challenge_start_date: data.start_date,
          challenge_end_date: endDate,
          challenge_template_id: template?.id ?? null,
          challenge_status: status,
          challenge_invite_card_data: inviteCardBase,
        })
        .returning([
          'id',
          'name',
          'description',
          'icon_emoji',
          'creator_user_id',
          'challenge_start_date',
          'challenge_end_date',
          'challenge_status',
          'challenge_template_id',
          'challenge_invite_card_data',
          'created_at',
        ])
        .executeTakeFirstOrThrow();

      await trx
        .insertInto('group_memberships')
        .values({
          group_id: group.id,
          user_id: req.user!.id,
          role: 'creator',
          status: 'active',
        })
        .execute();

      await trx
        .insertInto('goals')
        .values(
          goalsToCreate.map((goal) => ({
            group_id: group.id,
            title: goal.title,
            description: goal.description,
            cadence: goal.cadence,
            metric_type: goal.metric_type,
            target_value: goal.target_value,
            unit: goal.unit,
            created_by_user_id: req.user!.id,
          }))
        )
        .execute();

      await trx
        .insertInto('invite_codes')
        .values({
          group_id: group.id,
          code: inviteCode,
          created_by_user_id: req.user!.id,
          revoked_at: null,
        })
        .execute();

      await trx
        .insertInto('group_activities')
        .values({
          group_id: group.id,
          user_id: req.user!.id,
          activity_type: 'challenge_created',
          metadata: {
            challenge_status: status,
            template_id: template?.id ?? null,
            invite_code: inviteCode,
          },
        })
        .execute();

      const goals = await trx
        .selectFrom('goals')
        .select(['id', 'title', 'description', 'cadence', 'metric_type', 'target_value', 'unit', 'created_at'])
        .where('group_id', '=', group.id)
        .where('deleted_at', 'is', null)
        .orderBy('created_at', 'asc')
        .execute();

      await trx
        .updateTable('challenge_suggestion_log')
        .set({ converted: true })
        .where('user_id', '=', req.user!.id)
        .where('converted', '=', false)
        .execute();

      return { group, goals, inviteCode, inviteCardBase };
    });

    const assetBaseUrl = `${req.protocol}://${req.get('host')}`;
    const inviteCardData = await attachInviteCardAttribution(
      created.inviteCardBase,
      created.inviteCode,
      req.user.id,
      template?.slug ?? 'challenge_invite',
      assetBaseUrl
    );

    res.status(201).json({
      challenge: {
        id: created.group.id,
        name: created.group.name,
        is_challenge: true,
        challenge_start_date: created.group.challenge_start_date,
        challenge_end_date: created.group.challenge_end_date,
        challenge_status: created.group.challenge_status,
        challenge_template_id: created.group.challenge_template_id,
        member_count: 1,
        goals: created.goals.map((g) => ({
          id: g.id,
          title: g.title,
          description: g.description,
          cadence: g.cadence,
          metric_type: g.metric_type,
          target_value: g.target_value,
          unit: g.unit,
        })),
        invite_code: created.inviteCode,
        invite_url: `https://getpursue.app/challenge/${created.inviteCode}`,
        invite_card_data: inviteCardData,
      },
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/challenges
export async function listChallenges(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const query = GetChallengesSchema.parse(req.query);
    const user = await db
      .selectFrom('users')
      .select('timezone')
      .where('id', '=', req.user.id)
      .executeTakeFirst();
    const today = getDateInTimezone(user?.timezone ?? 'UTC');

    let q = db
      .selectFrom('group_memberships')
      .innerJoin('groups', 'groups.id', 'group_memberships.group_id')
      .leftJoin('challenge_templates', 'challenge_templates.id', 'groups.challenge_template_id')
      .select([
        'groups.id',
        'groups.name',
        'groups.icon_emoji',
        'groups.icon_url',
        'groups.challenge_start_date',
        'groups.challenge_end_date',
        'groups.challenge_status',
        'groups.challenge_template_id',
        'challenge_templates.title as template_title',
      ])
      .where('group_memberships.user_id', '=', req.user.id)
      .where('group_memberships.status', '=', 'active')
      .where('groups.is_challenge', '=', true)
      .orderBy('groups.challenge_start_date', 'desc');

    if (query.status) {
      q = q.where('groups.challenge_status', '=', query.status);
    }

    const rows = await q.execute();
    const challenges = await Promise.all(rows.map(async (row) => {
      const startDate = row.challenge_start_date!;
      const endDate = row.challenge_end_date!;
      const totalDays = daysInclusive(startDate, endDate);
      const elapsedEnd = today < startDate ? startDate : (today > endDate ? endDate : today);
      const daysElapsed = today < startDate ? 0 : daysInclusive(startDate, elapsedEnd);
      const daysRemaining = today > endDate ? 0 : daysInclusive(today < startDate ? startDate : today, endDate);

      const memberCountRow = await db
        .selectFrom('group_memberships')
        .select(db.fn.count('id').as('count'))
        .where('group_id', '=', row.id)
        .where('status', '=', 'active')
        .executeTakeFirst();

      const completion = await getChallengeCompletionRateForUser(
        row.id,
        req.user!.id,
        startDate,
        endDate
      );

      return {
        id: row.id,
        name: row.name,
        icon_emoji: row.icon_emoji,
        icon_url: row.icon_url ?? null,
        challenge_start_date: startDate,
        challenge_end_date: endDate,
        challenge_status: row.challenge_status,
        days_remaining: daysRemaining,
        days_elapsed: daysElapsed,
        total_days: totalDays,
        member_count: Number(memberCountRow?.count ?? 0),
        my_completion_rate: completion,
        template_title: row.template_title,
      };
    }));

    res.status(200).json({ challenges });
  } catch (error) {
    next(error);
  }
}

// PATCH /api/challenges/:id/cancel
export async function cancelChallenge(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const { id } = ChallengeIdParamSchema.parse(req.params);
    await ensureGroupExists(id);
    await requireGroupCreator(req.user.id, id);
    await requireGroupMember(req.user.id, id);

    const group = await db
      .selectFrom('groups')
      .select(['id', 'name', 'challenge_status', 'is_challenge'])
      .where('id', '=', id)
      .executeTakeFirstOrThrow();

    if (!group.is_challenge) {
      throw new ApplicationError('Group is not a challenge', 400, 'VALIDATION_ERROR');
    }
    if (!group.challenge_status || !['upcoming', 'active'].includes(group.challenge_status)) {
      throw new ApplicationError('Only upcoming or active challenges can be cancelled', 400, 'VALIDATION_ERROR');
    }

    await db
      .updateTable('groups')
      .set({ challenge_status: 'cancelled', updated_at: new Date().toISOString() })
      .where('id', '=', id)
      .execute();

    await createGroupActivity(id, 'challenge_cancelled', req.user.id);

    await sendGroupNotification(
      id,
      {
        title: 'Challenge Cancelled',
        body: `${group.name} has been cancelled by the organizer.`,
      },
      {
        type: 'challenge_cancelled',
        group_id: id,
      },
      { membershipStatus: 'active' }
    );

    res.status(200).json({
      id,
      challenge_status: 'cancelled',
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/internal/jobs/update-challenge-statuses
export async function updateChallengeStatusesJob(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const jobKey = req.headers['x-internal-job-key'];
    const expectedKey = process.env.INTERNAL_JOB_KEY;
    if (!expectedKey) {
      throw new ApplicationError('Internal server error', 500, 'INTERNAL_ERROR');
    }
    if (jobKey !== expectedKey) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    let forcedNow: Date | undefined;
    if (process.env.NODE_ENV === 'test' && typeof req.body?.force_now === 'string') {
      const parsed = new Date(req.body.force_now);
      if (Number.isNaN(parsed.getTime())) {
        throw new ApplicationError('force_now must be a valid ISO-8601 datetime', 400, 'VALIDATION_ERROR');
      }
      forcedNow = parsed;
    }

    const result = await updateChallengeStatuses(forcedNow ?? new Date());
    res.status(200).json({
      success: true,
      ...result,
    });
  } catch (error) {
    next(error);
  }
}

// POST /api/internal/jobs/process-challenge-completion-pushes
export async function processChallengeCompletionPushesJob(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const jobKey = req.headers['x-internal-job-key'];
    const expectedKey = process.env.INTERNAL_JOB_KEY;
    if (!expectedKey) {
      throw new ApplicationError('Internal server error', 500, 'INTERNAL_ERROR');
    }
    if (jobKey !== expectedKey) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const result = await processChallengeCompletionPushes();
    res.status(200).json({
      success: true,
      ...result,
    });
  } catch (error) {
    next(error);
  }
}

/**
 * PATCH /api/challenges/suggestions/dismiss
 * Dismiss the current challenge suggestion for the user.
 */
export async function dismissChallengeSuggestion(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const now = new Date();
    await db
      .updateTable('challenge_suggestion_log')
      .set({ dismissed_at: now.toISOString() })
      .where('user_id', '=', req.user.id)
      .execute();

    res.status(200).json({
      success: true,
      dismissed_at: now.toISOString(),
    });
  } catch (error) {
    next(error);
  }
}
