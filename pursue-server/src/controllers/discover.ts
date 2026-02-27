import type { Response, NextFunction, Request } from 'express';
import { sql, type SqlBool } from 'kysely';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import {
  DiscoverGroupsQuerySchema,
  JoinRequestSchema,
  UpdateJoinRequestSchema,
} from '../validations/groups.js';
import { ensureGroupExists, requireGroupAdmin } from '../services/authorization.js';
import { createGroupActivity, ACTIVITY_TYPES } from '../services/activity.service.js';
import { sendNotificationToUser } from '../services/fcm.service.js';
import { logger } from '../utils/logger.js';
import { activeDaysToLabels } from '../utils/activeDays.js';
import { getQueryEmbedding } from '../services/embedding.service.js';

// Tier names for heat score mapping
const TIER_NAMES: Record<number, string> = {
  0: 'Cold',
  1: 'Spark',
  2: 'Ember',
  3: 'Flicker',
  4: 'Steady',
  5: 'Warm',
  6: 'Hot',
  7: 'Blazing',
  8: 'Inferno',
  9: 'Supernova',
};

function getTierName(tier: number): string {
  return TIER_NAMES[tier] ?? 'Cold';
}

function computeLangMatch(rowLanguage: string | null, reqLanguage: string): number {
  if (reqLanguage.startsWith('en')) {
    return rowLanguage === null || rowLanguage.startsWith('en') ? 1 : 0;
  }
  return rowLanguage === reqLanguage ? 1 : 0;
}

// GET /api/discover/groups
export async function listPublicGroups(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const query = DiscoverGroupsQuerySchema.parse(req.query);

    const { categories, sort, q, cursor, limit, language } = query;

    const languageMatchSql = language
      ? language.startsWith('en')
        ? sql<number>`CASE WHEN groups.language IS NULL OR groups.language LIKE 'en%' THEN 1 ELSE 0 END`
        : sql<number>`CASE WHEN groups.language = ${language} THEN 1 ELSE 0 END`
      : null;

    // Try to get a semantic embedding for the query (null if OpenAI unavailable/fails)
    const queryEmbedding = q ? await getQueryEmbedding(q) : null;
    const embJson = queryEmbedding ? JSON.stringify(queryEmbedding) : null;

    // Reusable trigram relevance expression (null when no query)
    const trigramSql = q
      ? sql<number>`GREATEST(
          word_similarity(${q}, groups.name),
          COALESCE((
            SELECT MAX(word_similarity(${q}, goals.title))
            FROM goals
            WHERE goals.group_id = groups.id AND goals.deleted_at IS NULL
          ), 0)
        )`
      : null;

    // Combined score: 0.5 * trigram + 0.5 * COALESCE(semantic, trigram)
    // When embedding is null: collapses to pure trigram (same scale)
    const combinedScoreSql = q && trigramSql
      ? sql<number>`
          0.5 * GREATEST(
            word_similarity(${q}, groups.name),
            COALESCE((
              SELECT MAX(word_similarity(${q}, goals.title))
              FROM goals WHERE goals.group_id = groups.id AND goals.deleted_at IS NULL
            ), 0)
          )
          + 0.5 * COALESCE(
            ${embJson !== null
              ? sql`CASE WHEN groups.search_embedding IS NOT NULL
                    THEN 1 - (groups.search_embedding <=> ${embJson}::vector)
                    ELSE NULL END`
              : sql`NULL`
            },
            GREATEST(
              word_similarity(${q}, groups.name),
              COALESCE((
                SELECT MAX(word_similarity(${q}, goals.title))
                FROM goals WHERE goals.group_id = groups.id AND goals.deleted_at IS NULL
              ), 0)
            )
          )`
      : null;

    // Parse comma-separated categories
    const categoryList = categories
      ? categories.split(',').map((c) => c.trim()).filter(Boolean)
      : [];

    let dbQuery = db
      .selectFrom('groups')
      .leftJoin('group_heat', 'group_heat.group_id', 'groups.id')
      .leftJoin(
        db
          .selectFrom('group_memberships')
          .select(['group_id', db.fn.count<string>('id').as('member_count')])
          .where('status', '=', 'active')
          .groupBy('group_id')
          .as('mc'),
        'mc.group_id', 'groups.id'
      )
      .leftJoin(
        db
          .selectFrom('goals')
          .select(['group_id', db.fn.count<string>('id').as('goal_count')])
          .where('deleted_at', 'is', null)
          .groupBy('group_id')
          .as('gc'),
        'gc.group_id', 'groups.id'
      )
      .select([
        'groups.id',
        'groups.name',
        'groups.description',
        'groups.icon_emoji',
        'groups.icon_color',
        'groups.icon_url',
        sql<boolean>`groups.icon_data IS NOT NULL`.as('has_icon'),
        'groups.category',
        'groups.spot_limit',
        'groups.created_at',
        sql<number>`COALESCE(group_heat.heat_score, 0)`.as('heat_score'),
        sql<number>`COALESCE(group_heat.heat_tier, 0)`.as('heat_tier'),
        sql<number>`COALESCE(mc.member_count, 0)`.as('member_count'),
        sql<number>`COALESCE(gc.goal_count, 0)`.as('goal_count'),
        'groups.language',
      ])
      .where('groups.visibility', '=', 'public')
      .where('groups.deleted_at', 'is', null);

    if (combinedScoreSql) {
      // Type assertion needed: Kysely changes the return type on each .select() call
      (dbQuery as any) = (dbQuery as any).select(combinedScoreSql.as('combined_score'));
    }

    // Category filter
    if (categoryList.length > 0) {
      dbQuery = dbQuery.where('groups.category', 'in', categoryList);
    }

    // Text search (name or goal title) — includes fuzzy word_similarity matching + semantic
    if (q) {
      dbQuery = dbQuery.where((eb) =>
        eb.or([
          // Exact substring — keeps full recall for short/precise queries
          eb('groups.name', 'ilike', `%${q}%`),
          // Fuzzy word match — handles typos and partial word matches
          sql<SqlBool>`word_similarity(${q}, groups.name) > 0.3`,
          // Same two checks on goal titles within the group
          eb.exists(
            db.selectFrom('goals')
              .select(sql<number>`1`.as('one'))
              .where(sql<SqlBool>`goals.group_id = groups.id`)
              .where((eb2) =>
                eb2.or([
                  eb2('goals.title', 'ilike', `%${q}%`),
                  sql<SqlBool>`word_similarity(${q}, goals.title) > 0.3`,
                ])
              )
              .where('goals.deleted_at', 'is', null)
          ),
          // Semantic match via pgvector (only when embedding is available)
          ...(embJson !== null ? [
            sql<SqlBool>`groups.search_embedding IS NOT NULL
              AND 1 - (groups.search_embedding <=> ${embJson}::vector) > 0.3`,
          ] : []),
        ])
      );
    }

    // Cursor-based pagination: cursor encodes the sort key + id

    // --- Combined score cursor (q is present) ---
    if (q && combinedScoreSql && cursor) {
      try {
        const decoded = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8'));
        if (languageMatchSql && decoded.lang_match !== undefined) {
          dbQuery = dbQuery.where(
            sql<SqlBool>`(${languageMatchSql}) < ${decoded.lang_match}
              OR ((${languageMatchSql}) = ${decoded.lang_match}
                AND ((${combinedScoreSql}) < ${decoded.combined_score}
                  OR ((${combinedScoreSql}) = ${decoded.combined_score} AND groups.id < ${decoded.id})))`
          );
        } else {
          dbQuery = dbQuery.where(
            sql<SqlBool>`(${combinedScoreSql}) < ${decoded.combined_score}
              OR ((${combinedScoreSql}) = ${decoded.combined_score} AND groups.id < ${decoded.id})`
          );
        }
      } catch { /* invalid cursor — ignore */ }
    }

    // --- Engagement cursor (no q) — two-tier keyset when lang_match present ---
    if (!q && cursor) {
      try {
        const decoded = JSON.parse(Buffer.from(cursor, 'base64url').toString('utf8'));
        if (sort === 'heat') {
          if (languageMatchSql && decoded.lang_match !== undefined) {
            dbQuery = dbQuery.where(
              sql<SqlBool>`(${languageMatchSql}) < ${decoded.lang_match}
                OR ((${languageMatchSql}) = ${decoded.lang_match}
                  AND (COALESCE(group_heat.heat_score, 0) < ${decoded.heat_score}
                    OR (COALESCE(group_heat.heat_score, 0) = ${decoded.heat_score}
                      AND groups.id < ${decoded.id})))`
            );
          } else {
            dbQuery = dbQuery.where((eb) =>
              eb.or([
                eb(sql<number>`COALESCE(group_heat.heat_score, 0)`, '<', decoded.heat_score),
                eb.and([
                  eb(sql<number>`COALESCE(group_heat.heat_score, 0)`, '=', decoded.heat_score),
                  eb('groups.id', '<', decoded.id),
                ]),
              ])
            );
          }
        } else if (sort === 'newest') {
          if (languageMatchSql && decoded.lang_match !== undefined) {
            dbQuery = dbQuery.where(
              sql<SqlBool>`(${languageMatchSql}) < ${decoded.lang_match}
                OR ((${languageMatchSql}) = ${decoded.lang_match}
                  AND (groups.created_at < ${new Date(decoded.created_at)}
                    OR (groups.created_at = ${new Date(decoded.created_at)}
                      AND groups.id < ${decoded.id})))`
            );
          } else {
            dbQuery = dbQuery.where((eb) =>
              eb.or([
                eb('groups.created_at', '<', new Date(decoded.created_at)),
                eb.and([
                  eb('groups.created_at', '=', new Date(decoded.created_at)),
                  eb('groups.id', '<', decoded.id),
                ]),
              ])
            );
          }
        } else if (sort === 'members') {
          if (languageMatchSql && decoded.lang_match !== undefined) {
            dbQuery = dbQuery.where(
              sql<SqlBool>`(${languageMatchSql}) < ${decoded.lang_match}
                OR ((${languageMatchSql}) = ${decoded.lang_match}
                  AND (COALESCE(mc.member_count, 0) < ${decoded.member_count}
                    OR (COALESCE(mc.member_count, 0) = ${decoded.member_count}
                      AND groups.id < ${decoded.id})))`
            );
          } else {
            dbQuery = dbQuery.where((eb) =>
              eb.or([
                eb(sql<number>`COALESCE(mc.member_count, 0)`, '<', decoded.member_count),
                eb.and([
                  eb(sql<number>`COALESCE(mc.member_count, 0)`, '=', decoded.member_count),
                  eb('groups.id', '<', decoded.id),
                ]),
              ])
            );
          }
        }
      } catch {
        // Invalid cursor — ignore and start from beginning
      }
    }

    // Sort — language-first, then combined score / engagement sort
    if (languageMatchSql) {
      (dbQuery as any) = (dbQuery as any).orderBy(languageMatchSql, 'desc');
    }
    if (q && combinedScoreSql) {
      dbQuery = dbQuery
        .orderBy(combinedScoreSql, 'desc')
        .orderBy('groups.id', 'desc');
    } else if (sort === 'heat') {
      dbQuery = dbQuery
        .orderBy(sql`COALESCE(group_heat.heat_score, 0)`, 'desc')
        .orderBy('groups.id', 'desc');
    } else if (sort === 'newest') {
      dbQuery = dbQuery
        .orderBy('groups.created_at', 'desc')
        .orderBy('groups.id', 'desc');
    } else {
      // members
      dbQuery = dbQuery
        .orderBy(sql`COALESCE(mc.member_count, 0)`, 'desc')
        .orderBy('groups.id', 'desc');
    }

    const rows = await dbQuery.limit(limit + 1).execute();

    const hasMore = rows.length > limit;
    const items = hasMore ? rows.slice(0, limit) : rows;

    const groups = items.map((row) => {
      const memberCount = Number(row.member_count);
      const goalCount = Number(row.goal_count);
      const spotLimit = row.spot_limit ? Number(row.spot_limit) : null;
      const spotsLeft = spotLimit !== null ? Math.max(0, spotLimit - memberCount) : null;
      const isFull = spotLimit !== null && memberCount >= spotLimit;

      return {
        id: row.id,
        name: row.name,
        icon_emoji: row.icon_emoji,
        icon_color: row.icon_color,
        icon_url: row.icon_url,
        has_icon: Boolean(row.has_icon),
        category: row.category,
        member_count: memberCount,
        goal_count: goalCount,
        heat_score: Number(row.heat_score),
        heat_tier: Number(row.heat_tier),
        heat_tier_name: getTierName(Number(row.heat_tier)),
        spot_limit: spotLimit,
        spots_left: spotsLeft !== null && spotsLeft <= 10 ? spotsLeft : null,
        is_full: isFull,
        created_at: row.created_at,
      };
    });

    // Build next cursor
    let nextCursor: string | null = null;
    if (hasMore) {
      const last = items[items.length - 1];
      let cursorData: Record<string, unknown>;
      if (q) {
        cursorData = {
          combined_score: (last as any).combined_score ?? 0,
          id: last.id,
        };
      } else if (sort === 'heat') {
        cursorData = { heat_score: Number(last.heat_score), id: last.id };
      } else if (sort === 'newest') {
        cursorData = { created_at: last.created_at, id: last.id };
      } else {
        cursorData = { member_count: Number(last.member_count), id: last.id };
      }
      if (language) {
        cursorData.lang_match = computeLangMatch((last as any).language ?? null, language);
      }
      nextCursor = Buffer.from(JSON.stringify(cursorData)).toString('base64url');
    }

    res.status(200).json({
      groups,
      next_cursor: nextCursor,
      has_more: hasMore,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/discover/groups/:group_id
export async function getPublicGroup(
  req: Request,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    const group_id = String(req.params.group_id);

    const group = await db
      .selectFrom('groups')
      .leftJoin('group_heat', 'group_heat.group_id', 'groups.id')
      .leftJoin(
        db
          .selectFrom('group_memberships')
          .select(['group_id', db.fn.count<string>('id').as('member_count')])
          .where('status', '=', 'active')
          .groupBy('group_id')
          .as('mc'),
        'mc.group_id', 'groups.id'
      )
      .select([
        'groups.id',
        'groups.name',
        'groups.description',
        'groups.icon_emoji',
        'groups.icon_color',
        'groups.icon_url',
        sql<boolean>`groups.icon_data IS NOT NULL`.as('has_icon'),
        'groups.category',
        'groups.spot_limit',
        'groups.created_at',
        sql<number>`COALESCE(group_heat.heat_score, 0)`.as('heat_score'),
        sql<number>`COALESCE(group_heat.heat_tier, 0)`.as('heat_tier'),
        sql<number>`COALESCE(mc.member_count, 0)`.as('member_count'),
      ])
      .where('groups.id', '=', group_id)
      .where('groups.visibility', '=', 'public')
      .where('groups.deleted_at', 'is', null)
      .executeTakeFirst();

    if (!group) {
      throw new ApplicationError('Group not found', 404, 'NOT_FOUND');
    }

    // Fetch active goals
    const goals = await db
      .selectFrom('goals')
      .select(['id', 'title', 'cadence', 'metric_type', 'active_days'])
      .where('group_id', '=', group_id)
      .where('deleted_at', 'is', null)
      .orderBy('created_at', 'asc')
      .execute();

    const memberCount = Number(group.member_count);
    const spotLimit = group.spot_limit ? Number(group.spot_limit) : null;
    const spotsLeft = spotLimit !== null ? Math.max(0, spotLimit - memberCount) : null;
    const isFull = spotLimit !== null && memberCount >= spotLimit;

    res.status(200).json({
      id: group.id,
      name: group.name,
      description: group.description,
      icon_emoji: group.icon_emoji,
      icon_color: group.icon_color,
      icon_url: group.icon_url,
      has_icon: Boolean(group.has_icon),
      category: group.category,
      member_count: memberCount,
      heat_score: Number(group.heat_score),
      heat_tier: Number(group.heat_tier),
      heat_tier_name: getTierName(Number(group.heat_tier)),
      spot_limit: spotLimit,
      spots_left: spotsLeft !== null && spotsLeft <= 10 ? spotsLeft : null,
      is_full: isFull,
      created_at: group.created_at,
      goals: goals.map((g) => ({
        id: g.id,
        title: g.title,
        cadence: g.cadence,
        metric_type: g.metric_type,
        active_days_label: activeDaysToLabels(g.active_days),
      })),
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/discover/suggestions (auth required)
export async function listSuggestions(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }
    // pgvector suggestions deferred — return empty
    res.status(200).json({ suggestions: [] });
  } catch (error) {
    next(error);
  }
}

// DELETE /api/discover/suggestions/:group_id (auth required)
export async function dismissSuggestion(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);

    // Ensure group exists (public)
    const group = await db
      .selectFrom('groups')
      .select('id')
      .where('id', '=', group_id)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    if (!group) {
      throw new ApplicationError('Group not found', 404, 'NOT_FOUND');
    }

    // Upsert dismissal
    await db
      .insertInto('suggestion_dismissals')
      .values({
        user_id: req.user.id,
        group_id,
      })
      .onConflict((oc) =>
        oc.columns(['user_id', 'group_id']).doUpdateSet({
          dismissed_at: sql`NOW()`,
        })
      )
      .execute();

    res.status(204).send();
  } catch (error) {
    next(error);
  }
}

// POST /api/groups/:group_id/join-requests (auth required)
export async function submitJoinRequest(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    const data = JoinRequestSchema.parse(req.body);

    // Ensure group exists and is public
    const group = await db
      .selectFrom('groups')
      .select(['id', 'name', 'visibility', 'spot_limit', 'auto_approve'])
      .where('id', '=', group_id)
      .where('deleted_at', 'is', null)
      .executeTakeFirst();

    if (!group) {
      throw new ApplicationError('Group not found', 404, 'NOT_FOUND');
    }
    if (group.visibility !== 'public') {
      throw new ApplicationError('Group is not public', 403, 'FORBIDDEN');
    }

    // Check not already a member
    const existingMembership = await db
      .selectFrom('group_memberships')
      .select('status')
      .where('group_id', '=', group_id)
      .where('user_id', '=', req.user.id)
      .executeTakeFirst();

    if (existingMembership?.status === 'active') {
      throw new ApplicationError('Already a member of this group', 409, 'ALREADY_MEMBER');
    }

    // Check for existing pending request
    const existingRequest = await db
      .selectFrom('join_requests')
      .select(['id', 'status', 'created_at', 'reviewed_at'])
      .where('group_id', '=', group_id)
      .where('user_id', '=', req.user.id)
      .executeTakeFirst();

    if (existingRequest?.status === 'pending') {
      throw new ApplicationError('Join request already pending', 409, 'ALREADY_REQUESTED');
    }

    // Check decline cooldown: if last decline was within 30 days, block re-request
    if (existingRequest?.status === 'declined') {
      if (existingRequest.reviewed_at) {
        const thirtyDaysAgo = new Date();
        thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);
        if (new Date(existingRequest.reviewed_at as unknown as Date) > thirtyDaysAgo) {
          throw new ApplicationError(
            'Your request was recently declined. Please try again in 30 days.',
            429,
            'COOLDOWN_ACTIVE'
          );
        }
      }
    }

    // Rate limit: max 10 pending requests per user
    const pendingCount = await db
      .selectFrom('join_requests')
      .select(db.fn.count<string>('id').as('count'))
      .where('user_id', '=', req.user.id)
      .where('status', '=', 'pending')
      .executeTakeFirstOrThrow();

    if (Number(pendingCount.count) >= 10) {
      throw new ApplicationError(
        'Maximum pending join requests reached (10). Please wait for responses before requesting more.',
        429,
        'RATE_LIMIT_EXCEEDED'
      );
    }

    // Check spot limit (is group full?)
    if (group.spot_limit !== null) {
      const memberCount = await db
        .selectFrom('group_memberships')
        .select(db.fn.count<string>('id').as('count'))
        .where('group_id', '=', group_id)
        .where('status', '=', 'active')
        .executeTakeFirstOrThrow();

      if (Number(memberCount.count) >= Number(group.spot_limit)) {
        throw new ApplicationError('Group is full', 409, 'GROUP_FULL');
      }
    }

    // Auto-approve if enabled (inserts membership directly)
    if (group.auto_approve) {
      // Create membership
      await db
        .insertInto('group_memberships')
        .values({
          group_id,
          user_id: req.user.id,
          role: 'member',
          status: 'active',
        })
        .execute();

      // Record auto-approved join request
      const joinReq = await db
        .insertInto('join_requests')
        .values({
          group_id,
          user_id: req.user.id,
          note: data.note ?? null,
          status: 'approved',
          reviewed_at: new Date(),
        })
        .returning(['id', 'status', 'created_at'])
        .executeTakeFirstOrThrow();

      await createGroupActivity(group_id, ACTIVITY_TYPES.MEMBER_JOINED, req.user.id);

      res.status(201).json({
        id: joinReq.id,
        group_id,
        status: 'approved',
        note: data.note ?? null,
        created_at: joinReq.created_at,
        auto_approved: true,
      });
      return;
    }

    // Create or update the join request
    let joinReq;
    if (existingRequest) {
      // Re-request after decline — update existing row
      joinReq = await db
        .updateTable('join_requests')
        .set({
          status: 'pending',
          note: data.note ?? null,
          reviewed_at: null,
          reviewed_by: null,
        })
        .where('id', '=', existingRequest.id)
        .returning(['id', 'status', 'created_at'])
        .executeTakeFirstOrThrow();
    } else {
      joinReq = await db
        .insertInto('join_requests')
        .values({
          group_id,
          user_id: req.user.id,
          note: data.note ?? null,
        })
        .returning(['id', 'status', 'created_at'])
        .executeTakeFirstOrThrow();
    }

    // Notify group admins
    try {
      const admins = await db
        .selectFrom('group_memberships')
        .select('user_id')
        .where('group_id', '=', group_id)
        .where('role', '=', 'admin')
        .where('status', '=', 'active')
        .execute();

      const requester = await db
        .selectFrom('users')
        .select('display_name')
        .where('id', '=', req.user.id)
        .executeTakeFirst();

      for (const admin of admins) {
        await sendNotificationToUser(
          admin.user_id,
          {
            title: 'New join request',
            body: `${requester?.display_name ?? 'Someone'} wants to join ${group.name}`,
          },
          {
            type: 'join_request',
            group_id,
            request_id: joinReq.id,
          }
        );
      }
    } catch (err) {
      logger.error('Failed to notify admins of join request', { error: err });
    }

    await createGroupActivity(group_id, ACTIVITY_TYPES.JOIN_REQUEST, req.user.id);

    res.status(201).json({
      id: joinReq.id,
      group_id,
      status: 'pending',
      note: data.note ?? null,
      created_at: joinReq.created_at,
      auto_approved: false,
    });
  } catch (error) {
    next(error);
  }
}

// GET /api/groups/:group_id/join-requests (admin only)
export async function listJoinRequests(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    await ensureGroupExists(group_id);
    await requireGroupAdmin(req.user.id, group_id);

    const requests = await db
      .selectFrom('join_requests')
      .innerJoin('users', 'users.id', 'join_requests.user_id')
      .select([
        'join_requests.id',
        'join_requests.user_id',
        'join_requests.status',
        'join_requests.note',
        'join_requests.created_at',
        'join_requests.reviewed_at',
        'users.display_name',
        'users.created_at as user_created_at',
      ])
      .where('join_requests.group_id', '=', group_id)
      .where('join_requests.status', '=', 'pending')
      .orderBy('join_requests.created_at', 'asc')
      .execute();

    res.status(200).json({
      requests: requests.map((r) => ({
        id: r.id,
        user_id: r.user_id,
        display_name: r.display_name,
        user_created_at: r.user_created_at,
        note: r.note,
        created_at: r.created_at,
      })),
    });
  } catch (error) {
    next(error);
  }
}

// PATCH /api/groups/:group_id/join-requests/:request_id (admin only)
export async function reviewJoinRequest(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const group_id = String(req.params.group_id);
    const request_id = String(req.params.request_id);
    const data = UpdateJoinRequestSchema.parse(req.body);

    await ensureGroupExists(group_id);
    await requireGroupAdmin(req.user.id, group_id);

    // Get the join request
    const joinReq = await db
      .selectFrom('join_requests')
      .select(['id', 'user_id', 'status', 'group_id'])
      .where('id', '=', request_id)
      .where('group_id', '=', group_id)
      .executeTakeFirst();

    if (!joinReq) {
      throw new ApplicationError('Join request not found', 404, 'NOT_FOUND');
    }
    if (joinReq.status !== 'pending') {
      throw new ApplicationError('Join request is no longer pending', 409, 'ALREADY_REVIEWED');
    }

    // Update request status
    const updated = await db
      .updateTable('join_requests')
      .set({
        status: data.status,
        reviewed_at: new Date(),
        reviewed_by: req.user.id,
      })
      .where('id', '=', request_id)
      .returning(['id', 'status', 'user_id', 'reviewed_at'])
      .executeTakeFirstOrThrow();

    if (data.status === 'approved') {
      // Create or update membership to active
      const existingMembership = await db
        .selectFrom('group_memberships')
        .select('id')
        .where('group_id', '=', group_id)
        .where('user_id', '=', joinReq.user_id)
        .executeTakeFirst();

      if (existingMembership) {
        await db
          .updateTable('group_memberships')
          .set({ status: 'active', role: 'member' })
          .where('id', '=', existingMembership.id)
          .execute();
      } else {
        await db
          .insertInto('group_memberships')
          .values({
            group_id,
            user_id: joinReq.user_id,
            role: 'member',
            status: 'active',
          })
          .execute();
      }

      await createGroupActivity(group_id, ACTIVITY_TYPES.MEMBER_APPROVED, req.user.id, {
        new_member_id: joinReq.user_id,
      });

      // Notify the requester
      try {
        const group = await db
          .selectFrom('groups')
          .select('name')
          .where('id', '=', group_id)
          .executeTakeFirst();

        await sendNotificationToUser(
          joinReq.user_id,
          {
            title: 'Join request approved',
            body: `You've been approved to join ${group?.name ?? 'the group'}!`,
          },
          {
            type: 'join_request_approved',
            group_id,
          }
        );
      } catch (err) {
        logger.error('Failed to notify user of join approval', { error: err });
      }
    } else {
      // Declined
      await createGroupActivity(group_id, ACTIVITY_TYPES.MEMBER_DECLINED, req.user.id, {
        declined_user_id: joinReq.user_id,
      });

      // Notify the requester
      try {
        const group = await db
          .selectFrom('groups')
          .select('name')
          .where('id', '=', group_id)
          .executeTakeFirst();

        await sendNotificationToUser(
          joinReq.user_id,
          {
            title: 'Join request not approved',
            body: `Your request to join ${group?.name ?? 'the group'} was not approved.`,
          },
          {
            type: 'join_request_declined',
            group_id,
          }
        );
      } catch (err) {
        logger.error('Failed to notify user of join decline', { error: err });
      }
    }

    res.status(200).json({
      id: updated.id,
      status: updated.status,
      reviewed_at: updated.reviewed_at,
    });
  } catch (error) {
    next(error);
  }
}
