package app.getpursue.e2e.recap

import com.google.common.truth.Truth.assertThat
import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.github.shannonbay.pursue.e2e.config.LocalServerConfig
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * E2E tests for the Weekly Group Recap feature.
 *
 * Tests the internal job endpoint (POST /api/internal/jobs/weekly-recap) and
 * the resulting notification-inbox delivery end-to-end.
 *
 * Setup:
 * - Shared owner user + shared group + a daily goal (for notification content)
 * - A second fresh member user who joins the shared group via invite code
 *
 * The forceGroupId + forceWeekEnd overrides (NODE_ENV=test only) bypass timezone
 * filtering so the test runs at any time/day. Set forceWeekEnd to the previous
 * Sunday: "2026-02-15" (the Sunday before 2026-02-17).
 */
class WeeklyRecapE2ETest : E2ETest() {

    companion object {
        // Previous Sunday relative to test date 2026-02-17
        private const val FORCE_WEEK_END = "2026-02-16"
    }

    // Set up shared group + goal once per class via @Before (shared cache handles the user/group)
    private var sharedGroupId: String? = null
    private var sharedGoalId: String? = null

    @Before
    fun ensureGroupAndGoal() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        sharedGroupId = group.id

        // Create a daily goal if not already present
        if (sharedGoalId == null) {
            val goalResp = testDataHelper.createTestGoal(
                api = api,
                accessToken = owner.access_token,
                groupId = group.id,
                title = "Recap Test Goal",
                cadence = "daily",
                metricType = "binary"
            )
            sharedGoalId = goalResp.id
        }
    }

    // -------------------------------------------------------------------------
    // Auth Tests
    // -------------------------------------------------------------------------

    @Test
    fun `trigger job with no key returns 401`() = runTest {
        // Passing an empty string sends x-internal-job-key: "" which the backend
        // rejects as != INTERNAL_JOB_KEY
        var exception: ApiException? = null
        try {
            api.triggerWeeklyRecapJob(internalJobKey = "")
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(401)
    }

    @Test
    fun `trigger job with invalid key returns 401`() = runTest {
        var exception: ApiException? = null
        try {
            api.triggerWeeklyRecapJob(internalJobKey = "wrong-key-value")
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(401)
    }

    // -------------------------------------------------------------------------
    // Basic Response
    // -------------------------------------------------------------------------

    @Test
    fun `trigger job with valid key returns success structure`() = runTest {
        val response = api.triggerWeeklyRecapJob(
            internalJobKey = LocalServerConfig.INTERNAL_JOB_KEY
        )

        assertThat(response.success).isTrue()
        assertThat(response.groups_processed).isAtLeast(0)
        assertThat(response.errors).isAtLeast(0)
        assertThat(response.skipped).isAtLeast(0)
    }

    // -------------------------------------------------------------------------
    // Full Pipeline Tests
    // -------------------------------------------------------------------------

    @Test
    fun `trigger job for specific group creates recap notification`() = runTest {
        val owner = getOrCreateSharedUser()
        val groupId = sharedGroupId ?: error("Group not set up")
        val goalId = sharedGoalId ?: error("Goal not set up")

        // Log a progress entry to make goalBreakdown non-empty
        api.logProgress(
            accessToken = owner.access_token,
            goalId = goalId,
            value = 1.0,
            userDate = "2026-02-15",
            userTimezone = "UTC"
        )

        // Force-trigger the recap for our test group
        val jobResponse = api.triggerWeeklyRecapJob(
            internalJobKey = LocalServerConfig.INTERNAL_JOB_KEY,
            forceGroupId = groupId,
            forceWeekEnd = FORCE_WEEK_END
        )

        assertThat(jobResponse.success).isTrue()

        // Verify notification appears in owner's inbox
        val notificationsResponse = api.getNotifications(owner.access_token)
        val recapNotification = notificationsResponse.notifications.find { it.type == "weekly_recap" }

        assertThat(recapNotification).isNotNull()

        val meta = recapNotification!!.metadata
        assertThat(meta).isNotNull()

        // Verify metadata keys from sendRecapToGroup
        assertThat(meta!!.containsKey("week_start")).isTrue()
        assertThat(meta.containsKey("week_end")).isTrue()
        assertThat(meta.containsKey("completion_rate")).isTrue()
        assertThat(meta.containsKey("highlights")).isTrue()
        assertThat(meta.containsKey("goal_breakdown")).isTrue()

        val weekStart = meta["week_start"]
        val weekEnd = meta["week_end"]
        assertThat(weekStart).isInstanceOf(String::class.java)
        assertThat(weekEnd).isInstanceOf(String::class.java)
        assertThat(weekEnd as String).isEqualTo(FORCE_WEEK_END)

        val highlights = meta["highlights"]
        assertThat(highlights).isInstanceOf(List::class.java)

        val goalBreakdown = meta["goal_breakdown"]
        assertThat(goalBreakdown).isInstanceOf(List::class.java)
    }

    @Test
    fun `both group members receive recap notification`() = runTest {
        val owner = getOrCreateSharedUser()
        val groupId = sharedGroupId ?: error("Group not set up")

        // Create a second member and have them join the shared group
        val member = testDataHelper.createTestUser(api, displayName = "Recap Member")
        trackUser(member.user!!.id)

        val inviteCodeResponse = api.getGroupInviteCode(owner.access_token, groupId)
        api.joinGroup(member.access_token, inviteCodeResponse.invite_code)
        // joinGroup always creates a pending membership — approve so status becomes 'active'
        api.approveMember(owner.access_token, groupId, member.user!!.id)

        // Force-trigger recap for the group (use a different week to avoid dedup from previous test)
        val differentWeekEnd = "2026-02-09" // Sunday before FORCE_WEEK_END
        api.triggerWeeklyRecapJob(
            internalJobKey = LocalServerConfig.INTERNAL_JOB_KEY,
            forceGroupId = groupId,
            forceWeekEnd = differentWeekEnd
        )

        // Verify both owner and member received the notification
        val ownerNotifications = api.getNotifications(owner.access_token)
        val memberNotifications = api.getNotifications(member.access_token)

        val ownerRecap = ownerNotifications.notifications.find {
            it.type == "weekly_recap" && it.metadata?.get("week_end") == differentWeekEnd
        }
        val memberRecap = memberNotifications.notifications.find {
            it.type == "weekly_recap" && it.metadata?.get("week_end") == differentWeekEnd
        }

        assertThat(ownerRecap).isNotNull()
        assertThat(memberRecap).isNotNull()
    }

    // -------------------------------------------------------------------------
    // Deduplication
    // -------------------------------------------------------------------------

    @Test
    fun `triggering same group and weekEnd twice does not create duplicate`() = runTest {
        val owner = getOrCreateSharedUser()
        val groupId = sharedGroupId ?: error("Group not set up")

        val dedupeWeekEnd = "2026-02-02" // Another unique Sunday for this test

        // First trigger
        val firstResponse = api.triggerWeeklyRecapJob(
            internalJobKey = LocalServerConfig.INTERNAL_JOB_KEY,
            forceGroupId = groupId,
            forceWeekEnd = dedupeWeekEnd
        )
        assertThat(firstResponse.success).isTrue()

        // Second trigger — same group + week_end
        val secondResponse = api.triggerWeeklyRecapJob(
            internalJobKey = LocalServerConfig.INTERNAL_JOB_KEY,
            forceGroupId = groupId,
            forceWeekEnd = dedupeWeekEnd
        )
        assertThat(secondResponse.success).isTrue()
        assertThat(secondResponse.groups_processed).isEqualTo(0)

        // Verify only 1 recap notification for this week_end in owner's inbox
        val notifications = api.getNotifications(owner.access_token)
        val recapsForWeek = notifications.notifications.filter {
            it.type == "weekly_recap" && it.metadata?.get("week_end") == dedupeWeekEnd
        }
        assertThat(recapsForWeek).hasSize(1)
    }

    // -------------------------------------------------------------------------
    // Skip Conditions (smoke test)
    // -------------------------------------------------------------------------

    @Test
    fun `job processes zero groups when none match timezone window`() = runTest {
        // Trigger without force params — at an arbitrary time, no groups should match
        // the Sunday-7PM window. This is a smoke test: success=true, groups_processed is
        // a non-negative integer (typically 0 in CI/dev environments).
        val response = api.triggerWeeklyRecapJob(
            internalJobKey = LocalServerConfig.INTERNAL_JOB_KEY
        )

        assertThat(response.success).isTrue()
        assertThat(response.groups_processed).isAtLeast(0)
        assertThat(response.errors).isEqualTo(0)
    }
}
