package com.github.shannonbay.pursue.e2e.goals

import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Test

/**
 * E2E tests for Goal endpoints (section 3.4).
 *
 * Covers:
 * - POST /api/groups/:group_id/goals (create)
 * - GET /api/goals/:goal_id
 * - PATCH /api/goals/:goal_id
 * - DELETE /api/goals/:goal_id
 * - GET /api/goals/:goal_id/progress
 * - GET /api/goals/:goal_id/progress/me
 *
 * Backend must be running at localhost:3000.
 */
class GoalsE2ETest : E2ETest() {

    // --- POST /api/groups/:group_id/goals ---

    @Test
    fun `create goal binary daily succeeds`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)

        val title = "Run 30 min ${System.currentTimeMillis()}"
        val description = "Run for at least 30 minutes"
        val created = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = title,
            description = description,
            cadence = "daily",
            metricType = "binary"
        )

        assertThat(created.id).isNotEmpty()
        assertThat(created.group_id).isEqualTo(group.id)
        assertThat(created.title).isEqualTo(title)
        assertThat(created.description).isEqualTo(description)
        assertThat(created.cadence).isEqualTo("daily")
        assertThat(created.metric_type).isEqualTo("binary")
        assertThat(created.target_value).isNull()
        assertThat(created.unit).isNull()
        assertThat(created.created_by_user_id).isEqualTo(auth.user!!.id)
        assertThat(created.created_at).isNotEmpty()
    }

    @Test
    fun `create goal numeric with target and unit succeeds`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)

        val title = "Read 50 pages ${System.currentTimeMillis()}"
        val created = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = title,
            cadence = "daily",
            metricType = "numeric",
            targetValue = 50.0,
            unit = "pages"
        )

        assertThat(created.id).isNotEmpty()
        assertThat(created.group_id).isEqualTo(group.id)
        assertThat(created.title).isEqualTo(title)
        assertThat(created.metric_type).isEqualTo("numeric")
        assertThat(created.target_value).isEqualTo(50.0)
        assertThat(created.unit).isEqualTo("pages")
    }

    @Test
    fun `create goal with empty title fails validation`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)

        var ex: Exception? = null
        try {
            api.createGoal(auth.access_token, group.id, title = "", cadence = "daily", metricType = "binary")
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(400)
    }

    @Test
    fun `create goal without auth returns 401`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        SecureTokenManager.getInstance(context).clearTokens()

        var ex: Exception? = null
        try {
            api.createGoal("", group.id, "Title", cadence = "daily", metricType = "binary")
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(401)
    }

    @Test
    @Ignore("Requires ability to add non-admin member to a group; invite/add-member may not exist")
    fun `create goal as non-admin returns 403`() = runTest {
        // User A creates group, User B is added as non-admin, User B tries to create goal -> 403
    }

    // --- GET /api/goals/:goal_id ---

    @Test
    fun `get goal returns details`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id, title = "Get Me ${System.currentTimeMillis()}")

        val g = api.getGoal(auth.access_token, goal.id)

        assertThat(g.id).isEqualTo(goal.id)
        assertThat(g.group_id).isEqualTo(group.id)
        assertThat(g.title).isEqualTo(goal.title)
        assertThat(g.cadence).isEqualTo(goal.cadence)
        assertThat(g.metric_type).isEqualTo(goal.metric_type)
        assertThat(g.created_at).isNotEmpty()
    }

    @Test
    fun `get goal without auth returns 401`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)
        SecureTokenManager.getInstance(context).clearTokens()

        var ex: Exception? = null
        try {
            api.getGoal("", goal.id)
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(401)
    }

    @Test
    fun `get goal when not member of group returns 403`() = runTest {
        val authA = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, authA.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, authA.access_token, group.id)

        val authB = testDataHelper.createTestUser(api)
        trackUser(authB.user!!.id)
        // authB is not in group

        var ex: Exception? = null
        try {
            api.getGoal(authB.access_token, goal.id)
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(403)
    }

    @Test
    fun `get goal with non-existent id returns 404`() = runTest {
        val auth = getOrCreateSharedUser()
        val fakeId = "00000000-0000-0000-0000-000000000000"

        var ex: Exception? = null
        try {
            api.getGoal(auth.access_token, fakeId)
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(404)
    }

    // --- PATCH /api/goals/:goal_id ---

    @Test
    fun `update goal succeeds`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id, title = "Original ${System.currentTimeMillis()}")

        val updated = api.updateGoal(auth.access_token, goal.id, title = "Updated Title", description = "Updated desc")

        assertThat(updated.id).isEqualTo(goal.id)
        assertThat(updated.title).isEqualTo("Updated Title")
        assertThat(updated.description).isEqualTo("Updated desc")
    }

    @Test
    fun `update goal without auth returns 401`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)
        SecureTokenManager.getInstance(context).clearTokens()

        var ex: Exception? = null
        try {
            api.updateGoal("", goal.id, title = "X")
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(401)
    }

    @Test
    fun `update goal with non-existent id returns 404`() = runTest {
        val auth = getOrCreateSharedUser()
        val fakeId = "00000000-0000-0000-0000-000000000000"

        var ex: Exception? = null
        try {
            api.updateGoal(auth.access_token, fakeId, title = "X")
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(404)
    }

    // --- DELETE /api/goals/:goal_id ---

    @Test
    fun `delete goal returns 204`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)

        api.deleteGoal(auth.access_token, goal.id)
        // 204 no content, no throw

        var ex: Exception? = null
        try {
            api.getGoal(auth.access_token, goal.id)
        } catch (e: Exception) { ex = e }
        // Deleted goal may 404 or still return (soft delete); either is acceptable per spec
        assertThat(ex == null || (ex as? ApiException)?.code == 404).isTrue()
    }

    @Test
    fun `delete goal without auth returns 401`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)
        SecureTokenManager.getInstance(context).clearTokens()

        var ex: Exception? = null
        try {
            api.deleteGoal("", goal.id)
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(401)
    }

    @Test
    fun `delete goal with non-existent id returns 404`() = runTest {
        val auth = getOrCreateSharedUser()
        val fakeId = "00000000-0000-0000-0000-000000000000"

        var ex: Exception? = null
        try {
            api.deleteGoal(auth.access_token, fakeId)
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(404)
    }

    // --- GET /api/goals/:goal_id/progress ---

    @Test
    fun `get goal progress returns goal and list`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)

        val res = api.getGoalProgress(auth.access_token, goal.id)

        assertThat(res.goal.id).isEqualTo(goal.id)
        assertThat(res.goal.title).isEqualTo(goal.title)
        assertThat(res.goal.cadence).isEqualTo(goal.cadence)
        assertThat(res.progress).isNotNull()
        // May be empty if no entries logged
    }

    @Test
    fun `get goal progress with date range`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)

        val res = api.getGoalProgress(auth.access_token, goal.id, startDate = "2026-01-01", endDate = "2026-01-31")

        assertThat(res.goal.id).isEqualTo(goal.id)
        assertThat(res.progress).isNotNull()
    }

    @Test
    fun `get goal progress without auth returns 401`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)
        SecureTokenManager.getInstance(context).clearTokens()

        var ex: Exception? = null
        try {
            api.getGoalProgress("", goal.id)
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(401)
    }

    @Test
    fun `get goal progress with non-existent goal returns 404`() = runTest {
        val auth = getOrCreateSharedUser()
        val fakeId = "00000000-0000-0000-0000-000000000000"

        var ex: Exception? = null
        try {
            api.getGoalProgress(auth.access_token, fakeId)
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(404)
    }

    // --- GET /api/goals/:goal_id/progress/me ---

    @Test
    fun `get goal progress me returns goal_id and entries`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)

        val res = api.getGoalProgressMe(auth.access_token, goal.id)

        assertThat(res.goal_id).isEqualTo(goal.id)
        assertThat(res.entries).isNotNull()
        // May be empty
    }

    @Test
    fun `get goal progress me with date range`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)

        val res = api.getGoalProgressMe(auth.access_token, goal.id, startDate = "2026-01-01", endDate = "2026-01-31")

        assertThat(res.goal_id).isEqualTo(goal.id)
        assertThat(res.entries).isNotNull()
    }

    @Test
    fun `get goal progress me without auth returns 401`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, auth.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)
        SecureTokenManager.getInstance(context).clearTokens()

        var ex: Exception? = null
        try {
            api.getGoalProgressMe("", goal.id)
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(401)
    }

    @Test
    fun `get goal progress me with non-existent goal returns 404`() = runTest {
        val auth = getOrCreateSharedUser()
        val fakeId = "00000000-0000-0000-0000-000000000000"

        var ex: Exception? = null
        try {
            api.getGoalProgressMe(auth.access_token, fakeId)
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(404)
    }
}
