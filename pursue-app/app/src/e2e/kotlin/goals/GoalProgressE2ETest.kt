package com.github.shannonbay.pursue.e2e.goals

import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * E2E tests for goal progress logging, retrieval, and deletion.
 *
 * Uses POST /api/progress to log; GET /api/goals/:id/progress/me to fetch;
 * DELETE /api/progress/:entry_id to delete (per specs/backend/03-api-endpoints.md).
 */
class GoalProgressE2ETest : E2ETest() {

    @Test
    fun `log progress entry stores in database`() = runTest {
        // Arrange - Create user, group, binary daily goal
        val authResponse = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(
            api,
            authResponse.access_token,
            group.id,
            title = "Run daily",
            cadence = "daily",
            metricType = "binary"
        )
        val userDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val userTimezone = "America/New_York"

        // Act - Log progress (binary: value 1 = completed)
        val logResponse = api.logProgress(
            accessToken = authResponse.access_token,
            goalId = goal.id,
            value = 1.0,
            note = "E2E test run",
            userDate = userDate,
            userTimezone = userTimezone
        )

        // Assert
        assertThat(logResponse.id).isNotEmpty()
        assertThat(logResponse.goal_id).isEqualTo(goal.id)
        assertThat(logResponse.user_id).isEqualTo(authResponse.user!!.id)
        assertThat(logResponse.value).isEqualTo(1.0)
        assertThat(logResponse.period_start).isEqualTo(userDate)
        assertThat(logResponse.logged_at).isNotEmpty()

        // Verify entry appears in get goal progress me
        val progressMe = api.getGoalProgressMe(authResponse.access_token, goal.id)
        assertThat(progressMe.entries).isNotEmpty()
        val entry = progressMe.entries.find { it.id == logResponse.id }
        assertThat(entry).isNotNull()
        assertThat(entry!!.value).isEqualTo(1.0)
    }

    @Test
    fun `cannot log progress for other user's goal`() = runTest {
        // Arrange - User A creates group and goal; User B is not in group
        val authA = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, authA.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, authA.access_token, group.id)

        val authB = testDataHelper.createTestUser(api, displayName = "Other User")
        trackUser(authB.user!!.id)

        val userDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

        // Act - User B tries to log progress for goal in group they are not in
        var exception: Exception? = null
        try {
            api.logProgress(
                accessToken = authB.access_token,
                goalId = goal.id,
                value = 1.0,
                userDate = userDate,
                userTimezone = "America/New_York"
            )
        } catch (e: Exception) {
            exception = e
        }

        // Assert - Backend returns 403 Forbidden
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        assertThat((exception as ApiException).code).isEqualTo(403)
    }

    @Test
    fun `delete progress entry removes entry`() = runTest {
        // Arrange - Create user, group, goal, log progress
        val authResponse = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(
            api,
            authResponse.access_token,
            group.id,
            title = "Run daily",
            cadence = "daily",
            metricType = "binary"
        )
        val userDate = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        val userTimezone = "America/New_York"

        val logResponse = api.logProgress(
            accessToken = authResponse.access_token,
            goalId = goal.id,
            value = 1.0,
            note = "E2E delete test",
            userDate = userDate,
            userTimezone = userTimezone
        )
        assertThat(logResponse.id).isNotEmpty()

        // Act - Delete the progress entry
        api.deleteProgressEntry(authResponse.access_token, logResponse.id)

        // Assert - Entry no longer appears in get goal progress me
        val progressMe = api.getGoalProgressMe(authResponse.access_token, goal.id)
        val entry = progressMe.entries.find { it.id == logResponse.id }
        assertThat(entry).isNull()
    }
}
