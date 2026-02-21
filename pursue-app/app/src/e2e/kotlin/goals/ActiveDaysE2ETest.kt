package com.github.shannonbay.pursue.e2e.goals

import com.google.common.truth.Truth.assertThat
import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for active_days scheduling on daily goals.
 *
 * Day indices use Sunday-first ordering: 0=Sun, 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri, 6=Sat.
 *
 * Backend must be running at localhost:3000.
 */
class ActiveDaysE2ETest : E2ETest() {

    // Day index constants (Sunday-first: 0=Sun, 1=Mon, ..., 6=Sat)
    companion object {
        val SUNDAY    = 0
        val MONDAY    = 1
        val TUESDAY   = 2
        val WEDNESDAY = 3
        val THURSDAY  = 4
        val FRIDAY    = 5
        val SATURDAY  = 6
        val WEEKDAYS  = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
        val WEEKENDS  = listOf(SUNDAY, SATURDAY)
        val ALL_DAYS  = listOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY)
        val MWF       = listOf(MONDAY, WEDNESDAY, FRIDAY)
    }

    // --- Create goal with active_days ---

    @Test
    fun `create daily goal with weekdays returns active_days array`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val created = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Weekday goal ${System.currentTimeMillis()}",
            cadence = "daily",
            metricType = "binary",
            activeDays = WEEKDAYS
        )

        assertThat(created.active_days).isNotNull()
        assertThat(created.active_days).containsExactlyElementsIn(WEEKDAYS)
        assertThat(created.active_days_count).isEqualTo(5)
        assertThat(created.active_days_label).isEqualTo("Weekdays only")
    }

    @Test
    fun `create daily goal with weekends returns active_days array`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val created = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Weekend goal ${System.currentTimeMillis()}",
            cadence = "daily",
            metricType = "binary",
            activeDays = WEEKENDS
        )

        assertThat(created.active_days).isNotNull()
        assertThat(created.active_days).containsExactlyElementsIn(WEEKENDS)
        assertThat(created.active_days_count).isEqualTo(2)
        assertThat(created.active_days_label).isEqualTo("Weekends only")
    }

    @Test
    fun `create daily goal with MWF returns active_days array`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val created = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "MWF goal ${System.currentTimeMillis()}",
            cadence = "daily",
            metricType = "binary",
            activeDays = MWF
        )

        assertThat(created.active_days).isNotNull()
        assertThat(created.active_days).containsExactlyElementsIn(MWF)
        assertThat(created.active_days_count).isEqualTo(3)
    }

    @Test
    fun `create daily goal with all days returns active_days array`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val created = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "All days goal ${System.currentTimeMillis()}",
            cadence = "daily",
            metricType = "binary",
            activeDays = ALL_DAYS
        )

        assertThat(created.active_days).isNotNull()
        assertThat(created.active_days).containsExactlyElementsIn(ALL_DAYS)
        assertThat(created.active_days_count).isEqualTo(7)
        assertThat(created.active_days_label).isEqualTo("Every day")
    }

    @Test
    fun `create daily goal without active_days returns null active_days`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val created = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "No schedule goal ${System.currentTimeMillis()}",
            cadence = "daily",
            metricType = "binary"
        )

        // null means every day — backward compatible default
        assertThat(created.active_days).isNull()
        assertThat(created.active_days_label).isEqualTo("Every day")
        assertThat(created.active_days_count).isEqualTo(7)
    }

    // --- Validation errors ---

    @Test
    fun `create goal with active_days on weekly cadence returns 400`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        var ex: Exception? = null
        try {
            api.createGoal(
                accessToken = auth.access_token,
                groupId = group.id,
                title = "Invalid ${System.currentTimeMillis()}",
                cadence = "weekly",
                metricType = "binary",
                activeDays = WEEKDAYS
            )
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(400)
    }

    @Test
    fun `create goal with duplicate active_days values returns 400`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        var ex: Exception? = null
        try {
            api.createGoal(
                accessToken = auth.access_token,
                groupId = group.id,
                title = "Dup days ${System.currentTimeMillis()}",
                cadence = "daily",
                metricType = "binary",
                activeDays = listOf(MONDAY, MONDAY, FRIDAY)
            )
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(400)
    }

    @Test
    fun `create goal with day value out of range returns 400`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        var ex: Exception? = null
        try {
            api.createGoal(
                accessToken = auth.access_token,
                groupId = group.id,
                title = "Out of range ${System.currentTimeMillis()}",
                cadence = "daily",
                metricType = "binary",
                activeDays = listOf(7)  // 0-6 valid, 7 invalid
            )
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(400)
    }

    // --- Update active_days ---

    @Test
    fun `update goal active_days changes schedule`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id,
            title = "Update days ${System.currentTimeMillis()}",
            activeDays = WEEKDAYS
        )

        // Change from weekdays to MWF
        val updated = api.updateGoal(
            accessToken = auth.access_token,
            goalId = goal.id,
            activeDays = MWF
        )

        assertThat(updated.active_days).containsExactlyElementsIn(MWF)
        assertThat(updated.active_days_count).isEqualTo(3)
    }

    @Test
    fun `update goal resets active_days to null with resetActiveDays flag`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id,
            title = "Reset days ${System.currentTimeMillis()}",
            activeDays = WEEKDAYS
        )

        // Reset to every day
        val updated = api.updateGoal(
            accessToken = auth.access_token,
            goalId = goal.id,
            resetActiveDays = true
        )

        assertThat(updated.active_days).isNull()
        assertThat(updated.active_days_label).isEqualTo("Every day")
        assertThat(updated.active_days_count).isEqualTo(7)
    }

    @Test
    fun `update goal without active_days param leaves schedule unchanged`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id,
            title = "Unchanged days ${System.currentTimeMillis()}",
            activeDays = WEEKDAYS
        )

        // Update only the title — active_days must be preserved
        val updated = api.updateGoal(
            accessToken = auth.access_token,
            goalId = goal.id,
            title = "Renamed ${System.currentTimeMillis()}"
        )

        assertThat(updated.active_days).containsExactlyElementsIn(WEEKDAYS)
    }

    @Test
    fun `update goal with duplicate active_days returns 400`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(api, auth.access_token, group.id)

        var ex: Exception? = null
        try {
            api.updateGoal(
                accessToken = auth.access_token,
                goalId = goal.id,
                activeDays = listOf(MONDAY, MONDAY)
            )
        } catch (e: Exception) { ex = e }

        assertThat(ex).isInstanceOf(ApiException::class.java)
        assertThat((ex as ApiException).code).isEqualTo(400)
    }

    // --- Get goal includes active_days ---

    @Test
    fun `get goal returns active_days fields`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id,
            title = "Get days ${System.currentTimeMillis()}",
            activeDays = WEEKDAYS
        )

        val fetched = api.getGoal(auth.access_token, goal.id)

        assertThat(fetched.active_days).containsExactlyElementsIn(WEEKDAYS)
        assertThat(fetched.active_days_count).isEqualTo(5)
        assertThat(fetched.active_days_label).isEqualTo("Weekdays only")
    }

    @Test
    fun `get goal with null active_days returns every day label`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id,
            title = "No days ${System.currentTimeMillis()}"
        )

        val fetched = api.getGoal(auth.access_token, goal.id)

        assertThat(fetched.active_days).isNull()
        assertThat(fetched.active_days_label).isEqualTo("Every day")
        assertThat(fetched.active_days_count).isEqualTo(7)
    }

    // --- Progress endpoint includes active_days ---

    @Test
    fun `get goal progress returns active_days on goal object`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id,
            title = "Progress days ${System.currentTimeMillis()}",
            activeDays = MWF
        )

        val res = api.getGoalProgress(auth.access_token, goal.id)

        assertThat(res.goal.active_days).containsExactlyElementsIn(MWF)
        assertThat(res.goal.active_days_count).isEqualTo(3)
    }

    @Test
    fun `get goal progress me returns active_days fields`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api, auth.access_token, group.id,
            title = "Progress me days ${System.currentTimeMillis()}",
            activeDays = WEEKENDS
        )

        val res = api.getGoalProgressMe(auth.access_token, goal.id)

        assertThat(res.active_days).containsExactlyElementsIn(WEEKENDS)
        assertThat(res.active_days_count).isEqualTo(2)
        assertThat(res.active_days_label).isEqualTo("Weekends only")
    }
}
