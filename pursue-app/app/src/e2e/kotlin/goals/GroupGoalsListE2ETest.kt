package com.github.shannonbay.pursue.e2e.goals

import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for GET /api/groups/:group_id/goals.
 *
 * Covers list group goals, query params (cadence, archived, include_progress),
 * and error responses (401, 403, 404). Backend must be running at localhost:3000.
 */
class GroupGoalsListE2ETest : E2ETest() {

    @Test
    fun `get group goals returns list`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)

        val response = api.getGroupGoals(auth.access_token, group.id)

        assertThat(response.goals).isNotNull()
        assertThat(response.total).isAtLeast(0)
    }

    @Test
    fun `get group goals includes created goals`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal1 = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "List goal 1 ${System.currentTimeMillis()}",
            cadence = "daily"
        )
        val goal2 = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "List goal 2 ${System.currentTimeMillis()}",
            cadence = "weekly"
        )

        val response = api.getGroupGoals(auth.access_token, group.id)

        assertThat(response.total).isAtLeast(2)
        assertThat(response.goals).hasSize(response.total)
        val ids = response.goals.map { it.id }.toSet()
        assertThat(ids).contains(goal1.id)
        assertThat(ids).contains(goal2.id)
        val g1 = response.goals.find { it.id == goal1.id }
        assertThat(g1).isNotNull()
        assertThat(g1!!.group_id).isEqualTo(group.id)
        assertThat(g1.title).isEqualTo(goal1.title)
        assertThat(g1.cadence).isEqualTo("daily")
        val g2 = response.goals.find { it.id == goal2.id }
        assertThat(g2).isNotNull()
        assertThat(g2!!.cadence).isEqualTo("weekly")
    }

    @Test
    fun `get group goals without auth returns 401`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        SecureTokenManager.getInstance(context).clearTokens()

        var exception: Exception? = null
        try {
            api.getGroupGoals("", group.id)
        } catch (e: Exception) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        assertThat((exception as ApiException).code).isEqualTo(401)
    }

    @Test
    fun `get group goals when not member returns 403`() = runTest {
        val authA = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, authA.access_token)
        trackGroup(group.id)
        testDataHelper.createTestGoal(api, authA.access_token, group.id)

        val authB = testDataHelper.createTestUser(api, displayName = "Non-member")
        trackUser(authB.user!!.id)

        var exception: Exception? = null
        try {
            api.getGroupGoals(authB.access_token, group.id)
        } catch (e: Exception) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        assertThat((exception as ApiException).code).isEqualTo(403)
    }

    @Test
    fun `get group goals with invalid group id returns 404`() = runTest {
        val auth = getOrCreateSharedUser()
        val invalidGroupId = "00000000-0000-0000-0000-000000000000"

        var exception: Exception? = null
        try {
            api.getGroupGoals(auth.access_token, invalidGroupId)
        } catch (e: Exception) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        assertThat((exception as ApiException).code).isEqualTo(404)
    }

    @Test
    fun `get group goals with includeProgress false returns goals without progress`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        testDataHelper.createTestGoal(api, auth.access_token, group.id, title = "No progress ${System.currentTimeMillis()}")

        val response = api.getGroupGoals(auth.access_token, group.id, includeProgress = false)

        assertThat(response.goals).isNotEmpty()
        response.goals.forEach { goal ->
            assertThat(goal.current_period_progress).isNull()
        }
    }

    @Test
    fun `get group goals with includeProgress true returns goals`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        testDataHelper.createTestGoal(api, auth.access_token, group.id, title = "With progress ${System.currentTimeMillis()}")

        val response = api.getGroupGoals(auth.access_token, group.id, includeProgress = true)

        assertThat(response.goals).isNotEmpty()
        assertThat(response.total).isAtLeast(1)
        response.goals.forEach { goal ->
            assertThat(goal.id).isNotEmpty()
            assertThat(goal.group_id).isEqualTo(group.id)
            assertThat(goal.title).isNotEmpty()
            assertThat(goal.cadence).isAnyOf("daily", "weekly", "monthly", "yearly")
            assertThat(goal.metric_type).isAnyOf("binary", "numeric", "duration")
        }
    }

    @Test
    fun `get group goals with cadence filter returns only matching cadence`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "Daily goal ${System.currentTimeMillis()}",
            cadence = "daily"
        )
        testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "Weekly goal ${System.currentTimeMillis()}",
            cadence = "weekly"
        )

        val response = api.getGroupGoals(auth.access_token, group.id, cadence = "daily")

        assertThat(response.goals).isNotEmpty()
        response.goals.forEach { goal ->
            assertThat(goal.cadence).isEqualTo("daily")
        }
        val dailyCount = response.goals.count { it.cadence == "daily" }
        assertThat(response.total).isEqualTo(dailyCount)
    }

    @Test
    fun `get group goals with archived true includes archived goals`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "To archive ${System.currentTimeMillis()}"
        )
        api.deleteGoal(auth.access_token, goal.id)

        val response = api.getGroupGoals(auth.access_token, group.id, archived = true)

        val archivedGoal = response.goals.find { it.id == goal.id }
        assertThat(archivedGoal).isNotNull()
        assertThat(archivedGoal!!.archived_at).isNotNull()
    }
}
