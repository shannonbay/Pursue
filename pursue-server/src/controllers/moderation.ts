import type { Response, NextFunction } from 'express';
import { db } from '../database/index.js';
import { ApplicationError } from '../middleware/errorHandler.js';
import type { AuthRequest } from '../types/express.js';
import { ReportSchema, DisputeSchema } from '../validations/moderation.js';

/**
 * Calculate the auto-hide threshold for a given member count.
 * ≤10 members → 2 reports; 11–50 → 3; 51+ → min(5, floor(count × 0.10))
 */
function autoHideThreshold(memberCount: number): number {
  if (memberCount <= 10) return 2;
  if (memberCount <= 50) return 3;
  return Math.min(5, Math.floor(memberCount * 0.1));
}

/**
 * POST /api/reports
 * Submit a community report for a piece of content.
 */
export async function reportContent(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = ReportSchema.parse(req.body);
    const reporterId = req.user.id;

    // Verify content exists and reporter has access
    let groupId: string | null = null;

    if (data.content_type === 'progress_entry') {
      // Entry must exist and reporter must be in the same group
      const entry = await db
        .selectFrom('progress_entries')
        .innerJoin('goals', 'progress_entries.goal_id', 'goals.id')
        .select(['progress_entries.id', 'goals.group_id'])
        .where('progress_entries.id', '=', data.content_id)
        .where('goals.deleted_at', 'is', null)
        .executeTakeFirst();

      if (!entry) {
        throw new ApplicationError('Content not found', 404, 'NOT_FOUND');
      }

      groupId = entry.group_id;

      const membership = await db
        .selectFrom('group_memberships')
        .select('id')
        .where('group_id', '=', entry.group_id)
        .where('user_id', '=', reporterId)
        .where('status', '=', 'active')
        .executeTakeFirst();

      if (!membership) {
        throw new ApplicationError('You must be a member of this group to report this content', 403, 'FORBIDDEN');
      }
    } else if (data.content_type === 'group') {
      const group = await db
        .selectFrom('groups')
        .select('id')
        .where('id', '=', data.content_id)
        .where('deleted_at', 'is', null)
        .executeTakeFirst();

      if (!group) {
        throw new ApplicationError('Content not found', 404, 'NOT_FOUND');
      }

      groupId = data.content_id;

      const membership = await db
        .selectFrom('group_memberships')
        .select('id')
        .where('group_id', '=', data.content_id)
        .where('user_id', '=', reporterId)
        .where('status', '=', 'active')
        .executeTakeFirst();

      if (!membership) {
        throw new ApplicationError('You must be a member of this group to report it', 403, 'FORBIDDEN');
      }
    } else if (data.content_type === 'username') {
      // Any authenticated user can report; content_id = target user's UUID
      const targetUser = await db
        .selectFrom('users')
        .select('id')
        .where('id', '=', data.content_id)
        .where('deleted_at', 'is', null)
        .executeTakeFirst();

      if (!targetUser) {
        throw new ApplicationError('Content not found', 404, 'NOT_FOUND');
      }
    }

    // Prevent duplicate reports from the same user
    const existing = await db
      .selectFrom('content_reports')
      .select('id')
      .where('reporter_user_id', '=', reporterId)
      .where('content_type', '=', data.content_type)
      .where('content_id', '=', data.content_id)
      .executeTakeFirst();

    if (existing) {
      throw new ApplicationError('You have already reported this content', 409, 'ALREADY_REPORTED');
    }

    // Insert report
    const report = await db
      .insertInto('content_reports')
      .values({
        reporter_user_id: reporterId,
        content_type: data.content_type,
        content_id: data.content_id,
        reason: data.reason,
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    // Auto-hide logic for progress_entry content
    if (data.content_type === 'progress_entry' && groupId) {
      // Count distinct reporters after this insert
      const reportCountRow = await db
        .selectFrom('content_reports')
        .select(db.fn.count('id').as('count'))
        .where('content_type', '=', 'progress_entry')
        .where('content_id', '=', data.content_id)
        .executeTakeFirstOrThrow();

      const reportCount = Number(reportCountRow.count);

      // Get group member count
      const memberCountRow = await db
        .selectFrom('group_memberships')
        .select(db.fn.count('id').as('count'))
        .where('group_id', '=', groupId)
        .where('status', '=', 'active')
        .executeTakeFirstOrThrow();

      const memberCount = Number(memberCountRow.count);
      const threshold = autoHideThreshold(memberCount);

      if (reportCount >= threshold) {
        // Only auto-hide if currently 'ok' (don't override 'removed' or 'disputed')
        await db
          .updateTable('progress_entries')
          .set({
            moderation_status: 'hidden',
            moderation_updated_at: new Date().toISOString(),
          })
          .where('id', '=', data.content_id)
          .where('moderation_status', '=', 'ok')
          .execute();
      }
    }

    res.status(201).json({ id: report.id });
  } catch (error) {
    next(error);
  }
}

/**
 * POST /api/disputes
 * Submit a dispute for content that was moderated.
 */
export async function createDispute(
  req: AuthRequest,
  res: Response,
  next: NextFunction
): Promise<void> {
  try {
    if (!req.user) {
      throw new ApplicationError('Unauthorized', 401, 'UNAUTHORIZED');
    }

    const data = DisputeSchema.parse(req.body);
    const userId = req.user.id;

    // Verify ownership of the content being disputed
    if (data.content_type === 'progress_entry') {
      const entry = await db
        .selectFrom('progress_entries')
        .select(['id', 'user_id', 'moderation_status'])
        .where('id', '=', data.content_id)
        .executeTakeFirst();

      if (!entry) {
        throw new ApplicationError('Content not found', 404, 'NOT_FOUND');
      }

      if (entry.user_id !== userId) {
        throw new ApplicationError('You can only dispute your own content', 403, 'FORBIDDEN');
      }

      // Insert dispute
      const dispute = await db
        .insertInto('content_disputes')
        .values({
          user_id: userId,
          content_type: data.content_type,
          content_id: data.content_id,
          user_explanation: data.user_explanation ?? null,
        })
        .returning('id')
        .executeTakeFirstOrThrow();

      // If entry is 'removed', update to 'disputed'
      if (entry.moderation_status === 'removed') {
        await db
          .updateTable('progress_entries')
          .set({
            moderation_status: 'disputed',
            moderation_updated_at: new Date().toISOString(),
          })
          .where('id', '=', data.content_id)
          .execute();
      }

      res.status(201).json({ id: dispute.id });
      return;
    }

    if (data.content_type === 'group') {
      const group = await db
        .selectFrom('groups')
        .select(['id', 'creator_user_id'])
        .where('id', '=', data.content_id)
        .where('deleted_at', 'is', null)
        .executeTakeFirst();

      if (!group) {
        throw new ApplicationError('Content not found', 404, 'NOT_FOUND');
      }

      if (group.creator_user_id !== userId) {
        throw new ApplicationError('You can only dispute your own content', 403, 'FORBIDDEN');
      }
    } else if (data.content_type === 'username') {
      // Can only dispute your own username
      if (data.content_id !== userId) {
        throw new ApplicationError('You can only dispute your own content', 403, 'FORBIDDEN');
      }
    }

    const dispute = await db
      .insertInto('content_disputes')
      .values({
        user_id: userId,
        content_type: data.content_type,
        content_id: data.content_id,
        user_explanation: data.user_explanation ?? null,
      })
      .returning('id')
      .executeTakeFirstOrThrow();

    res.status(201).json({ id: dispute.id });
  } catch (error) {
    next(error);
  }
}
