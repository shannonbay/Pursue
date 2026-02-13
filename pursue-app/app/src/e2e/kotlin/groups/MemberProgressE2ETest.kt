package com.github.shannonbay.pursue.e2e.groups

import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for member progress endpoint.
 *
 * Tests GET /api/groups/:group_id/members/:user_id/progress
 * which returns member info, goal summaries, and paginated activity log.
 */
class MemberProgressE2ETest : E2ETest() {

    // === Success Cases ===

    @Test
    fun `getMemberProgress returns member info goal summaries and activity log`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Create a goal and log some progress
        val goal = testDataHelper.createTestGoal(
            api = api,
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Daily Exercise",
            cadence = "daily",
            metricType = "binary"
        )

        // Log progress using fixed past date (per E2ETESTING.md)
        api.logProgress(
            accessToken = auth.access_token,
            goalId = goal.id,
            value = 1.0,
            note = "Morning workout",
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        // Act
        val response = api.getMemberProgress(
            accessToken = auth.access_token,
            groupId = group.id,
            userId = auth.user!!.id,
            startDate = "2026-02-01",
            endDate = "2026-02-07"
        )

        // Assert - Member info
        assertThat(response.member).isNotNull()
        assertThat(response.member.user_id).isEqualTo(auth.user!!.id)
        assertThat(response.member.display_name).isNotEmpty()
        assertThat(response.member.role).isAnyOf("creator", "admin", "member")
        assertThat(response.member.joined_at).isNotEmpty()

        // Assert - Timeframe
        assertThat(response.timeframe.start_date).isEqualTo("2026-02-01")
        assertThat(response.timeframe.end_date).isEqualTo("2026-02-07")

        // Assert - Goal summaries
        assertThat(response.goal_summaries).isNotEmpty()
        val goalSummary = response.goal_summaries.find { it.goal_id == goal.id }
        assertThat(goalSummary).isNotNull()
        assertThat(goalSummary!!.title).isEqualTo("Daily Exercise")
        assertThat(goalSummary.cadence).isEqualTo("daily")
        assertThat(goalSummary.metric_type).isEqualTo("binary")
        assertThat(goalSummary.completed).isAtLeast(1.0)
        assertThat(goalSummary.total).isAtLeast(1.0)
        assertThat(goalSummary.percentage).isAtLeast(0)

        // Assert - Activity log
        assertThat(response.activity_log).isNotEmpty()
        val logEntry = response.activity_log.find { it.goal_id == goal.id }
        assertThat(logEntry).isNotNull()
        assertThat(logEntry!!.entry_id).isNotEmpty()
        assertThat(logEntry.goal_title).isEqualTo("Daily Exercise")
        assertThat(logEntry.value).isEqualTo(1.0)
        assertThat(logEntry.note).isEqualTo("Morning workout")
        assertThat(logEntry.logged_at).isNotEmpty()

        // Assert - Pagination
        assertThat(response.pagination).isNotNull()
        assertThat(response.pagination.total_in_timeframe).isAtLeast(1)
    }

    @Test
    fun `getMemberProgress with pagination returns next cursor and has_more`() = runTest {
        // Arrange - Create user and group, then log multiple progress entries
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val goal = testDataHelper.createTestGoal(
            api = api,
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Pagination Test Goal",
            cadence = "daily",
            metricType = "binary"
        )

        // Log 3 entries on different dates to have entries to paginate
        for (day in 1..3) {
            val dateStr = "2026-02-0$day"
            api.logProgress(
                accessToken = auth.access_token,
                goalId = goal.id,
                value = 1.0,
                note = "Day $day progress",
                userDate = dateStr,
                userTimezone = "America/New_York"
            )
        }

        // Act - Fetch with limit=2 to force pagination
        val firstPage = api.getMemberProgress(
            accessToken = auth.access_token,
            groupId = group.id,
            userId = auth.user!!.id,
            startDate = "2026-02-01",
            endDate = "2026-02-07",
            limit = 2
        )

        // Assert first page
        assertThat(firstPage.activity_log.size).isEqualTo(2)
        assertThat(firstPage.pagination.has_more).isTrue()
        assertThat(firstPage.pagination.next_cursor).isNotNull()
        assertThat(firstPage.pagination.total_in_timeframe).isAtLeast(3)

        // Act - Fetch second page using cursor
        val secondPage = api.getMemberProgress(
            accessToken = auth.access_token,
            groupId = group.id,
            userId = auth.user!!.id,
            startDate = "2026-02-01",
            endDate = "2026-02-07",
            cursor = firstPage.pagination.next_cursor,
            limit = 2
        )

        // Assert second page
        assertThat(secondPage.activity_log).isNotEmpty()
        // First page entries should not appear on second page
        val firstPageIds = firstPage.activity_log.map { it.entry_id }.toSet()
        val secondPageIds = secondPage.activity_log.map { it.entry_id }.toSet()
        assertThat(firstPageIds.intersect(secondPageIds)).isEmpty()
    }

    @Test
    fun `getMemberProgress shows reactions on activity entries`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val goal = testDataHelper.createTestGoal(
            api = api,
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Reaction Test Goal",
            cadence = "daily",
            metricType = "binary"
        )

        // Log progress
        api.logProgress(
            accessToken = auth.access_token,
            goalId = goal.id,
            value = 1.0,
            note = "With reaction",
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        // Get activity feed to find the activity ID and add a reaction
        val activityResponse = api.getGroupActivity(auth.access_token, group.id, limit = 10)
        val progressActivity = activityResponse.activities.find { it.activity_type == "progress_logged" }
        assertThat(progressActivity).isNotNull()
        assertThat(progressActivity!!.id).isNotNull()

        // Add a reaction to the activity
        api.addOrReplaceReaction(auth.access_token, progressActivity.id!!, "🔥")

        // Act
        val response = api.getMemberProgress(
            accessToken = auth.access_token,
            groupId = group.id,
            userId = auth.user!!.id,
            startDate = "2026-02-01",
            endDate = "2026-02-07"
        )

        // Assert - Activity log entry should have reactions
        val logEntry = response.activity_log.find { it.goal_id == goal.id }
        assertThat(logEntry).isNotNull()
        assertThat(logEntry!!.reactions).isNotEmpty()

        val fireReaction = logEntry.reactions.find { it.emoji == "🔥" }
        assertThat(fireReaction).isNotNull()
        assertThat(fireReaction!!.count).isAtLeast(1)
    }

    @Test
    fun `getMemberProgress premium user can request extended date range`() = runTest {
        // Arrange - getOrCreateSharedUser already upgrades to premium
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act - Request > 30 days range (should succeed for premium)
        val response = api.getMemberProgress(
            accessToken = auth.access_token,
            groupId = group.id,
            userId = auth.user!!.id,
            startDate = "2025-11-01",  // ~3+ months ago
            endDate = "2026-02-07"
        )

        // Assert - Should succeed without 403
        assertThat(response.member).isNotNull()
        assertThat(response.timeframe.start_date).isEqualTo("2025-11-01")
        assertThat(response.timeframe.end_date).isEqualTo("2026-02-07")
    }

    @Test
    fun `getMemberProgress can view another member progress`() = runTest {
        // Arrange - Create a second member
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Create a second user and have them join the group
        val memberAuth = testDataHelper.createTestUser(api, displayName = "Other Member")
        trackUser(memberAuth.user!!.id)

        val invite = api.getGroupInviteCode(creator.access_token, group.id)
        api.joinGroup(memberAuth.access_token, invite.invite_code)
        api.approveMember(creator.access_token, group.id, memberAuth.user!!.id)

        // Member logs some progress
        val goal = testDataHelper.createTestGoal(
            api = api,
            accessToken = creator.access_token,
            groupId = group.id,
            title = "Shared Goal",
            cadence = "daily",
            metricType = "binary"
        )

        api.logProgress(
            accessToken = memberAuth.access_token,
            goalId = goal.id,
            value = 1.0,
            note = "Member's progress",
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        // Act - Creator views member's progress
        val response = api.getMemberProgress(
            accessToken = creator.access_token,
            groupId = group.id,
            userId = memberAuth.user!!.id,
            startDate = "2026-02-01",
            endDate = "2026-02-07"
        )

        // Assert
        assertThat(response.member.user_id).isEqualTo(memberAuth.user!!.id)
        assertThat(response.activity_log).isNotEmpty()
    }

    // === Authorization Tests ===

    @Test
    fun `getMemberProgress without auth returns 401`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Clear tokens so no Authorization header is sent
        SecureTokenManager.getInstance(context).clearTokens()

        // Act
        var exception: ApiException? = null
        try {
            api.getMemberProgress(
                accessToken = "",
                groupId = group.id,
                userId = auth.user!!.id,
                startDate = "2026-02-01",
                endDate = "2026-02-07"
            )
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(401)
    }

    @Test
    fun `getMemberProgress non-member returns 403`() = runTest {
        // Arrange
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Create a non-member user
        val nonMemberAuth = testDataHelper.createTestUser(api, displayName = "Non-Member")
        trackUser(nonMemberAuth.user!!.id)

        // Act - Non-member tries to view creator's progress
        var exception: ApiException? = null
        try {
            api.getMemberProgress(
                accessToken = nonMemberAuth.access_token,
                groupId = group.id,
                userId = creator.user!!.id,
                startDate = "2026-02-01",
                endDate = "2026-02-07"
            )
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }

    @Test
    fun `getMemberProgress target not a member returns 403`() = runTest {
        // Arrange
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Create a user who is NOT a member of the group
        val nonMemberAuth = testDataHelper.createTestUser(api, displayName = "Not A Member")
        trackUser(nonMemberAuth.user!!.id)

        // Act - Creator tries to view non-member's progress
        var exception: ApiException? = null
        try {
            api.getMemberProgress(
                accessToken = creator.access_token,
                groupId = group.id,
                userId = nonMemberAuth.user!!.id,  // Target is not a member
                startDate = "2026-02-01",
                endDate = "2026-02-07"
            )
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("TARGET_NOT_A_MEMBER")
    }

    @Test
    fun `getMemberProgress over 30 days for free user returns 403 SUBSCRIPTION_REQUIRED`() = runTest {
        // Arrange - Create a fresh free (non-premium) user
        val freeUserAuth = testDataHelper.createTestUser(api, displayName = "Free User")
        trackUser(freeUserAuth.user!!.id)

        // Create a new group (free user gets 1 group)
        val group = testDataHelper.createTestGroup(api, freeUserAuth.access_token)
        trackGroup(group.id)

        // Act - Request > 30 days range as free user
        var exception: ApiException? = null
        try {
            api.getMemberProgress(
                accessToken = freeUserAuth.access_token,
                groupId = group.id,
                userId = freeUserAuth.user!!.id,
                startDate = "2025-12-01",  // > 30 days from end_date
                endDate = "2026-02-07"
            )
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("SUBSCRIPTION_REQUIRED")
    }

    @Test
    fun `getMemberProgress group not found returns 404`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val invalidGroupId = "00000000-0000-0000-0000-000000000000"

        // Act
        var exception: ApiException? = null
        try {
            api.getMemberProgress(
                accessToken = auth.access_token,
                groupId = invalidGroupId,
                userId = auth.user!!.id,
                startDate = "2026-02-01",
                endDate = "2026-02-07"
            )
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    // === Validation Tests ===

    @Test
    fun `getMemberProgress invalid date format returns 400`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act - Invalid date format
        var exception: ApiException? = null
        try {
            api.getMemberProgress(
                accessToken = auth.access_token,
                groupId = group.id,
                userId = auth.user!!.id,
                startDate = "02-01-2026",  // Wrong format, should be YYYY-MM-DD
                endDate = "2026-02-07"
            )
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
    }

    @Test
    fun `getMemberProgress end_date before start_date returns 400`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act - end_date before start_date
        var exception: ApiException? = null
        try {
            api.getMemberProgress(
                accessToken = auth.access_token,
                groupId = group.id,
                userId = auth.user!!.id,
                startDate = "2026-02-10",
                endDate = "2026-02-01"  // Before start_date
            )
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
    }

    @Test
    fun `getMemberProgress invalid cursor returns 400`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act - Invalid cursor
        var exception: ApiException? = null
        try {
            api.getMemberProgress(
                accessToken = auth.access_token,
                groupId = group.id,
                userId = auth.user!!.id,
                startDate = "2026-02-01",
                endDate = "2026-02-07",
                cursor = "invalid-cursor-not-base64"
            )
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
        assertThat(exception.errorCode).isEqualTo("INVALID_CURSOR")
    }

    // === Edge Cases ===

    @Test
    fun `getMemberProgress empty activity log returns empty list`() = runTest {
        // Arrange - Create a fresh group with no progress logged
        val auth = getOrCreateSharedUser()

        // Create a new group with no progress entries
        val emptyGroup = testDataHelper.createTestGroup(
            api = api,
            accessToken = auth.access_token,
            name = "Empty Progress Group"
        )
        trackGroup(emptyGroup.id)

        // Act - Query a date range with no entries
        val response = api.getMemberProgress(
            accessToken = auth.access_token,
            groupId = emptyGroup.id,
            userId = auth.user!!.id,
            startDate = "2020-01-01",  // Far in the past
            endDate = "2020-01-07"
        )

        // Assert
        assertThat(response.activity_log).isEmpty()
        assertThat(response.pagination.has_more).isFalse()
        assertThat(response.pagination.next_cursor).isNull()
        assertThat(response.pagination.total_in_timeframe).isEqualTo(0)
    }

    @Test
    fun `getMemberProgress with no goals returns empty goal_summaries`() = runTest {
        // Arrange - Create a fresh group with no goals
        val auth = getOrCreateSharedUser()

        val noGoalsGroup = testDataHelper.createTestGroup(
            api = api,
            accessToken = auth.access_token,
            name = "No Goals Group"
        )
        trackGroup(noGoalsGroup.id)

        // Act
        val response = api.getMemberProgress(
            accessToken = auth.access_token,
            groupId = noGoalsGroup.id,
            userId = auth.user!!.id,
            startDate = "2026-02-01",
            endDate = "2026-02-07"
        )

        // Assert
        assertThat(response.goal_summaries).isEmpty()
    }

    @Test
    fun `getMemberProgress returns numeric goal summary correctly`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Create a numeric goal
        val numericGoal = testDataHelper.createTestGoal(
            api = api,
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Read 50 pages",
            cadence = "daily",
            metricType = "numeric",
            targetValue = 50.0,
            unit = "pg"
        )

        // Log progress
        api.logProgress(
            accessToken = auth.access_token,
            goalId = numericGoal.id,
            value = 25.0,
            note = "Read chapter 1",
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        // Act
        val response = api.getMemberProgress(
            accessToken = auth.access_token,
            groupId = group.id,
            userId = auth.user!!.id,
            startDate = "2026-02-01",
            endDate = "2026-02-07"
        )

        // Assert - Goal summary for numeric goal
        val goalSummary = response.goal_summaries.find { it.goal_id == numericGoal.id }
        assertThat(goalSummary).isNotNull()
        assertThat(goalSummary!!.metric_type).isEqualTo("numeric")
        assertThat(goalSummary.target_value).isEqualTo(50.0)
        assertThat(goalSummary.unit).isEqualTo("pg")
        assertThat(goalSummary.completed).isEqualTo(25.0)
        assertThat(goalSummary.percentage).isAtLeast(0)

        // Assert - Activity log entry
        val logEntry = response.activity_log.find { it.goal_id == numericGoal.id }
        assertThat(logEntry).isNotNull()
        assertThat(logEntry!!.value).isEqualTo(25.0)
        assertThat(logEntry.unit).isEqualTo("pg")
        assertThat(logEntry.metric_type).isEqualTo("numeric")
    }
}
