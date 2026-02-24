package com.github.shannonbay.pursue.e2e.goals

import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for journal metric type goals.
 *
 * Verifies:
 * - Creating a journal goal returns log_title_prompt
 * - Journal goal listed in GET /api/groups/:id/goals returns log_title_prompt
 * - Logging progress with log_title stores and returns it
 * - Logging progress without log_title is rejected (400)
 * - Logging progress with invalid value (not 0 or 1) is rejected (400)
 * - Progress entries return log_title via GET /api/goals/:id/progress/me
 */
class JournalGoalE2ETest : E2ETest() {

    companion object {
        private const val USER_DATE = "2026-02-01"
        private const val USER_TIMEZONE = "America/New_York"
    }

    @Test
    fun `create journal goal returns log_title_prompt`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val goal = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Daily Discovery",
            description = "Share what you learned today",
            cadence = "daily",
            metricType = "journal",
            logTitlePrompt = "What did you learn today?"
        )

        assertThat(goal.metric_type).isEqualTo("journal")
        assertThat(goal.log_title_prompt).isEqualTo("What did you learn today?")
        assertThat(goal.target_value).isNull()
    }

    @Test
    fun `list goals returns log_title_prompt on journal goal`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Daily Discovery List Test",
            cadence = "daily",
            metricType = "journal",
            logTitlePrompt = "What did you discover?"
        )

        val goalsResponse = api.getGroupGoals(
            accessToken = auth.access_token,
            groupId = group.id,
            includeProgress = false
        )

        val journalGoal = goalsResponse.goals.find {
            it.metric_type == "journal" && it.log_title_prompt == "What did you discover?"
        }
        assertThat(journalGoal).isNotNull()
        assertThat(journalGoal!!.log_title_prompt).isEqualTo("What did you discover?")
    }

    @Test
    fun `log progress with log_title stores and returns it`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val goal = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Daily Discovery Log Test",
            cadence = "daily",
            metricType = "journal",
            logTitlePrompt = "What did you learn today?"
        )

        val logResponse = api.logProgress(
            accessToken = auth.access_token,
            goalId = goal.id,
            value = 1.0,
            logTitle = "Learned about Kysely query builder",
            userDate = USER_DATE,
            userTimezone = USER_TIMEZONE
        )

        assertThat(logResponse.id).isNotEmpty()
        assertThat(logResponse.goal_id).isEqualTo(goal.id)
        assertThat(logResponse.value).isEqualTo(1.0)
        assertThat(logResponse.log_title).isEqualTo("Learned about Kysely query builder")
    }

    @Test
    fun `log progress for journal goal without log_title is rejected`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val goal = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Daily Discovery No Title Test",
            cadence = "daily",
            metricType = "journal",
            logTitlePrompt = "What did you learn today?"
        )

        var exception: ApiException? = null
        try {
            api.logProgress(
                accessToken = auth.access_token,
                goalId = goal.id,
                value = 1.0,
                logTitle = null,
                userDate = USER_DATE,
                userTimezone = USER_TIMEZONE
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
    }

    @Test
    fun `log progress for journal goal with invalid value is rejected`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val goal = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Daily Discovery Invalid Value Test",
            cadence = "daily",
            metricType = "journal",
            logTitlePrompt = "What did you learn today?"
        )

        var exception: ApiException? = null
        try {
            api.logProgress(
                accessToken = auth.access_token,
                goalId = goal.id,
                value = 2.0,
                logTitle = "Something I learned",
                userDate = USER_DATE,
                userTimezone = USER_TIMEZONE
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
    }

    @Test
    fun `progress me entries include log_title for journal goal`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val goal = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Daily Discovery Progress Me Test",
            cadence = "daily",
            metricType = "journal",
            logTitlePrompt = "What did you learn today?"
        )

        val logResponse = api.logProgress(
            accessToken = auth.access_token,
            goalId = goal.id,
            value = 1.0,
            logTitle = "Learned about PostgreSQL partial indexes",
            userDate = USER_DATE,
            userTimezone = USER_TIMEZONE
        )

        val progressMe = api.getGoalProgressMe(auth.access_token, goal.id)
        val entry = progressMe.entries.find { it.id == logResponse.id }

        assertThat(entry).isNotNull()
        assertThat(entry!!.log_title).isEqualTo("Learned about PostgreSQL partial indexes")
    }
}
