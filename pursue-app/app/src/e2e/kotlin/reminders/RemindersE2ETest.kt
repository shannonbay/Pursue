package com.github.shannonbay.pursue.e2e.reminders

import app.getpursue.data.network.ApiException
import java.time.LocalDate
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for smart reminder endpoints.
 *
 * Covers:
 * - GET /api/users/me/reminder-preferences
 * - GET /api/goals/:goal_id/reminder-preferences
 * - PUT /api/goals/:goal_id/reminder-preferences
 * - POST /api/goals/:goal_id/recalculate-pattern
 *
 * See E2ETESTING.md and specs/smart-reminders-spec.md.
 */
class RemindersE2ETest : E2ETest() {

    @Test
    fun `get all reminder preferences returns list`() = runTest {
        val auth = getOrCreateSharedUser()
        val all = api.getAllReminderPreferences(auth.access_token)
        assertThat(all.preferences).isNotNull()
        // Shared user may have preferences from other tests; only assert we got a valid response
    }

    @Test
    fun `get goal reminder preferences returns defaults when none set`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "Reminder test goal"
        )

        val prefs = api.getGoalReminderPreferences(auth.access_token, goal.id)

        assertThat(prefs.goal_id).isEqualTo(goal.id)
        assertThat(prefs.enabled).isTrue()
        assertThat(prefs.mode).isEqualTo("smart")
        assertThat(prefs.aggressiveness).isEqualTo("balanced")
        assertThat(prefs.fixed_hour).isNull()
        assertThat(prefs.quiet_hours_start).isNull()
        assertThat(prefs.quiet_hours_end).isNull()
        assertThat(prefs.last_modified_at).isNull()
        assertThat(prefs.pattern).isNull()
    }

    @Test
    fun `update goal reminder preferences stores and returns updated`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "Reminder update goal"
        )

        val updated = api.updateGoalReminderPreferences(
            accessToken = auth.access_token,
            goalId = goal.id,
            enabled = true,
            mode = "smart",
            aggressiveness = "gentle"
        )

        assertThat(updated.goal_id).isEqualTo(goal.id)
        assertThat(updated.enabled).isTrue()
        assertThat(updated.mode).isEqualTo("smart")
        assertThat(updated.aggressiveness).isEqualTo("gentle")
        assertThat(updated.last_modified_at).isNotEmpty()
    }

    @Test
    fun `get goal reminder preferences after update returns stored values`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "Reminder get-after-update goal"
        )

        api.updateGoalReminderPreferences(
            accessToken = auth.access_token,
            goalId = goal.id,
            enabled = false,
            mode = "disabled",
            aggressiveness = "persistent"
        )

        val prefs = api.getGoalReminderPreferences(auth.access_token, goal.id)
        assertThat(prefs.enabled).isFalse()
        assertThat(prefs.mode).isEqualTo("disabled")
        assertThat(prefs.aggressiveness).isEqualTo("persistent")
        assertThat(prefs.last_modified_at).isNotEmpty()
    }

    @Test
    fun `update fixed mode requires fixed_hour`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "Reminder fixed mode goal"
        )

        val updated = api.updateGoalReminderPreferences(
            accessToken = auth.access_token,
            goalId = goal.id,
            mode = "fixed",
            fixedHour = 14
        )

        assertThat(updated.mode).isEqualTo("fixed")
        assertThat(updated.fixed_hour).isEqualTo(14)
    }

    @Test
    fun `get all reminder preferences includes updated goal`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "Reminder list goal"
        )

        api.updateGoalReminderPreferences(
            accessToken = auth.access_token,
            goalId = goal.id,
            enabled = true,
            mode = "smart"
        )

        val all = api.getAllReminderPreferences(auth.access_token)
        assertThat(all.preferences).isNotEmpty()
        val item = all.preferences.find { it.goal_id == goal.id }
        assertThat(item).isNotNull()
        assertThat(item!!.goal_title).isEqualTo("Reminder list goal")
        assertThat(item.enabled).isTrue()
        assertThat(item.mode).isEqualTo("smart")
    }

    @Test
    fun `recalculate pattern with insufficient data returns pattern null and message`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "Reminder recalc insufficient",
            cadence = "daily",
            metricType = "binary"
        )

        val result = api.recalculateGoalPattern(
            accessToken = auth.access_token,
            goalId = goal.id,
            userTimezone = "America/New_York"
        )

        assertThat(result.goal_id).isEqualTo(goal.id)
        assertThat(result.pattern).isNull()
        assertThat(result.message).isNotNull()
        assertThat(result.message).contains("Insufficient data")
    }

    @Test
    fun `recalculate pattern with enough logs returns pattern`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api,
            auth.access_token,
            group.id,
            title = "Reminder recalc enough",
            cadence = "daily",
            metricType = "binary"
        )
        val userDate = "2026-02-01"
        val userTimezone = "America/New_York"

        repeat(5) { dayOffset ->
            val date = LocalDate.parse(userDate).plusDays(dayOffset.toLong()).toString()
            api.logProgress(
                accessToken = auth.access_token,
                goalId = goal.id,
                value = 1.0,
                userDate = date,
                userTimezone = userTimezone
            )
        }

        val result = api.recalculateGoalPattern(
            accessToken = auth.access_token,
            goalId = goal.id,
            userTimezone = userTimezone
        )

        assertThat(result.goal_id).isEqualTo(goal.id)
        assertThat(result.pattern).isNotNull()
        assertThat(result.pattern!!.typical_hour_start).isAtLeast(0)
        assertThat(result.pattern!!.typical_hour_start).isAtMost(23)
        assertThat(result.pattern!!.typical_hour_end).isAtLeast(0)
        assertThat(result.pattern!!.typical_hour_end).isAtMost(23)
        assertThat(result.pattern!!.sample_size).isAtLeast(5)
        assertThat(result.pattern!!.confidence_score).isAtLeast(0.0)
        assertThat(result.pattern!!.confidence_score).isAtMost(1.0)
        assertThat(result.pattern!!.last_calculated_at).isNotEmpty()
    }

    @Test
    fun `non-member gets 403 for goal reminder preferences`() = runTest {
        val authA = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(api, authA.access_token, group.id)

        val authB = testDataHelper.createTestUser(api, displayName = "Non-member")
        trackUser(authB.user!!.id)

        var exception: Exception? = null
        try {
            api.getGoalReminderPreferences(authB.access_token, goal.id)
        } catch (e: Exception) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        assertThat((exception as ApiException).code).isEqualTo(403)
    }

    @Test
    fun `non-member gets 403 for update goal reminder preferences`() = runTest {
        val authA = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(api, authA.access_token, group.id)

        val authB = testDataHelper.createTestUser(api, displayName = "Non-member B")
        trackUser(authB.user!!.id)

        var exception: Exception? = null
        try {
            api.updateGoalReminderPreferences(
                accessToken = authB.access_token,
                goalId = goal.id,
                enabled = false
            )
        } catch (e: Exception) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        assertThat((exception as ApiException).code).isEqualTo(403)
    }

    @Test
    fun `non-member gets 403 for recalculate pattern`() = runTest {
        val authA = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(api, authA.access_token, group.id)

        val authB = testDataHelper.createTestUser(api, displayName = "Non-member C")
        trackUser(authB.user!!.id)

        var exception: Exception? = null
        try {
            api.recalculateGoalPattern(
                accessToken = authB.access_token,
                goalId = goal.id,
                userTimezone = "America/New_York"
            )
        } catch (e: Exception) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        assertThat((exception as ApiException).code).isEqualTo(403)
    }
}
