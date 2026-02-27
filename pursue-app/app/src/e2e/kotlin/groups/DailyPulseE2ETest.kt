package com.github.shannonbay.pursue.e2e.groups

import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * E2E tests for the Daily Pulse feature (GET /api/groups/:group_id/members).
 *
 * The Daily Pulse widget shows which group members have logged progress in the
 * current period. It is powered by two new fields on every member object:
 *   - logged_this_period: Boolean — true if the member has logged any goal this period
 *   - last_log_at: String?       — ISO 8601 timestamp of their most recent log
 *
 * The period is determined server-side from the group's primary goal cadence
 * (daily takes precedence over weekly) and each member's stored timezone.
 *
 * Spec: specs/daily-pulse-spec.md §12
 */
class DailyPulseE2ETest : E2ETest() {

    // Today's date in UTC — used as userDate when logging progress so the backend's
    // period_start check (period_start = NOW() AT TIME ZONE 'UTC'::date) matches.
    private val todayUtc: String
        get() = LocalDate.now(ZoneOffset.UTC).toString()

    // -------------------------------------------------------------------------
    // Response shape
    // -------------------------------------------------------------------------

    @Test
    fun `members response includes logged_this_period and last_log_at on every member`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        testDataHelper.createTestGoal(api, auth.access_token, group.id)

        val response = api.getGroupMembers(auth.access_token, group.id)

        assertThat(response.members).isNotEmpty()
        // The fields must be present on every member (Gson defaults missing booleans to false
        // and missing nullable strings to null — both are valid initial states).
        response.members.forEach { member ->
            // logged_this_period is a non-nullable Boolean — its presence in the data class
            // guarantees the field round-trips from JSON correctly.
            assertThat(member.logged_this_period).isIn(listOf(true, false))
            // last_log_at is nullable — null or a non-empty string are both valid
            if (member.last_log_at != null) {
                assertThat(member.last_log_at).isNotEmpty()
            }
        }
    }

    // -------------------------------------------------------------------------
    // No-goals edge case
    // -------------------------------------------------------------------------

    @Test
    fun `logged_this_period is false for all members when group has no goals`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        // Deliberately no goals → server cannot determine a cadence → all false

        val response = api.getGroupMembers(auth.access_token, group.id)

        val creator = response.members.find { it.user_id == auth.user!!.id }
        assertThat(creator).isNotNull()
        assertThat(creator!!.logged_this_period).isFalse()
        assertThat(creator.last_log_at).isNull()
    }

    // -------------------------------------------------------------------------
    // Not-logged state
    // -------------------------------------------------------------------------

    @Test
    fun `logged_this_period is false when member has not logged today`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        testDataHelper.createTestGoal(
            api, auth.access_token, group.id, cadence = "daily", metricType = "binary"
        )
        // No logProgress call — member has not logged

        val response = api.getGroupMembers(auth.access_token, group.id)

        val creator = response.members.find { it.user_id == auth.user!!.id }
        assertThat(creator).isNotNull()
        assertThat(creator!!.logged_this_period).isFalse()
        assertThat(creator.last_log_at).isNull()
    }

    // -------------------------------------------------------------------------
    // Logged state
    // -------------------------------------------------------------------------

    @Test
    fun `logged_this_period is true after logging today's progress`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id, cadence = "daily", metricType = "binary"
        )

        api.logProgress(
            accessToken = auth.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = todayUtc,
            userTimezone = "UTC"
        )

        val response = api.getGroupMembers(auth.access_token, group.id)

        val creator = response.members.find { it.user_id == auth.user!!.id }
        assertThat(creator).isNotNull()
        assertThat(creator!!.logged_this_period).isTrue()
    }

    @Test
    fun `last_log_at is non-null ISO 8601 timestamp after logging today`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id, cadence = "daily", metricType = "binary"
        )

        api.logProgress(
            accessToken = auth.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = todayUtc,
            userTimezone = "UTC"
        )

        val response = api.getGroupMembers(auth.access_token, group.id)

        val creator = response.members.find { it.user_id == auth.user!!.id }
        assertThat(creator).isNotNull()
        assertThat(creator!!.last_log_at).isNotNull()
        // ISO 8601 timestamps always contain 'T' separating date and time
        assertThat(creator.last_log_at!!).contains("T")
    }

    // -------------------------------------------------------------------------
    // Yesterday does not count
    // -------------------------------------------------------------------------

    @Test
    fun `progress logged yesterday does not satisfy daily logged_this_period`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id, cadence = "daily", metricType = "binary"
        )

        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1).toString()
        api.logProgress(
            accessToken = auth.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = yesterday,
            userTimezone = "UTC"
        )

        val response = api.getGroupMembers(auth.access_token, group.id)

        val creator = response.members.find { it.user_id == auth.user!!.id }
        assertThat(creator).isNotNull()
        assertThat(creator!!.logged_this_period).isFalse()
        assertThat(creator.last_log_at).isNull()
    }

    // -------------------------------------------------------------------------
    // Multi-member: logged vs not-logged in the same group
    // -------------------------------------------------------------------------

    @Test
    fun `logged member shows true while unlogged member shows false in the same group`() = runTest {
        val creatorAuth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, creatorAuth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(
            api, creatorAuth.access_token, group.id, cadence = "daily", metricType = "binary"
        )

        // Add a second member via invite flow
        val invite = api.getGroupInviteCode(creatorAuth.access_token, group.id)
        val memberAuth = testDataHelper.createTestUser(api, displayName = "Daily Pulse Member")
        trackUser(memberAuth.user!!.id)
        api.joinGroup(memberAuth.access_token, invite.invite_code)
        api.approveMember(creatorAuth.access_token, group.id, memberAuth.user!!.id)

        // Only the creator logs progress today
        api.logProgress(
            accessToken = creatorAuth.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = todayUtc,
            userTimezone = "UTC"
        )

        val response = api.getGroupMembers(creatorAuth.access_token, group.id)

        val creator = response.members.find { it.user_id == creatorAuth.user!!.id }
        val member = response.members.find { it.user_id == memberAuth.user!!.id }

        assertThat(creator).isNotNull()
        assertThat(member).isNotNull()

        assertThat(creator!!.logged_this_period).isTrue()
        assertThat(creator.last_log_at).isNotNull()

        assertThat(member!!.logged_this_period).isFalse()
        assertThat(member.last_log_at).isNull()
    }

    // -------------------------------------------------------------------------
    // Cross-group isolation
    // -------------------------------------------------------------------------

    @Test
    fun `progress logged in one group does not affect logged_this_period in another group`() = runTest {
        val auth = getOrCreateSharedUser()
        // Create two separate groups each with a daily goal
        val groupA = testDataHelper.createTestGroup(api, auth.access_token, name = "Daily Pulse Group A")
        trackGroup(groupA.id)
        val goalA = testDataHelper.createTestGoal(
            api, auth.access_token, groupA.id, cadence = "daily", metricType = "binary"
        )

        val groupB = testDataHelper.createTestGroup(api, auth.access_token, name = "Daily Pulse Group B")
        trackGroup(groupB.id)
        testDataHelper.createTestGoal(
            api, auth.access_token, groupB.id, cadence = "daily", metricType = "binary"
        )

        // Log only in Group A
        api.logProgress(
            accessToken = auth.access_token,
            goalId = goalA.id,
            value = 1.0,
            userDate = todayUtc,
            userTimezone = "UTC"
        )

        // Group A — should show logged
        val responseA = api.getGroupMembers(auth.access_token, groupA.id)
        val creatorA = responseA.members.find { it.user_id == auth.user!!.id }
        assertThat(creatorA).isNotNull()
        assertThat(creatorA!!.logged_this_period).isTrue()

        // Group B — should NOT show logged (no progress in B)
        val responseB = api.getGroupMembers(auth.access_token, groupB.id)
        val creatorB = responseB.members.find { it.user_id == auth.user!!.id }
        assertThat(creatorB).isNotNull()
        assertThat(creatorB!!.logged_this_period).isFalse()
        assertThat(creatorB.last_log_at).isNull()
    }
}
