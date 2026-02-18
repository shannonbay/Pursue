package com.github.shannonbay.pursue.e2e.challenges

import app.getpursue.data.network.ApiException
import app.getpursue.data.network.CreateChallengeGoal
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.github.shannonbay.pursue.e2e.config.LocalServerConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class ChallengeLifecycleE2ETest : E2ETest() {

    private fun datePlus(days: Long): String = LocalDate.now().plusDays(days).toString()

    @Test
    fun `lifecycle job requires internal key`() = runTest {
        var noKey: ApiException? = null
        try {
            api.triggerChallengeStatusUpdateJob("")
        } catch (e: ApiException) {
            noKey = e
        }
        assertThat(noKey).isNotNull()
        assertThat(noKey!!.code).isEqualTo(401)

        var wrongKey: ApiException? = null
        try {
            api.triggerChallengeStatusUpdateJob("wrong-key")
        } catch (e: ApiException) {
            wrongKey = e
        }
        assertThat(wrongKey).isNotNull()
        assertThat(wrongKey!!.code).isEqualTo(401)
    }

    @Test
    fun `lifecycle job returns success structure`() = runTest {
        val response = api.triggerChallengeStatusUpdateJob(LocalServerConfig.INTERNAL_JOB_KEY)
        assertThat(response.success).isTrue()
        assertThat(response.activated).isAtLeast(0)
        assertThat(response.completed).isAtLeast(0)
        assertThat(response.completion_notifications).isAtLeast(0)
    }

    @Test
    fun `completion notifications include challenge_completed shareable card when completions occur`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Lifecycle Completion User")
        trackUser(user.user!!.id)
        testDataHelper.upgradeToPremium(api, user.access_token)

        val challenge = api.createChallenge(
            accessToken = user.access_token,
            startDate = datePlus(0),
            endDate = datePlus(0),
            groupName = "Lifecycle ${System.currentTimeMillis()}",
            goals = listOf(
                CreateChallengeGoal(
                    title = "Lifecycle Goal",
                    cadence = "daily",
                    metric_type = "binary"
                )
            )
        )
        trackGroup(challenge.challenge.id)

        val job = api.triggerChallengeStatusUpdateJob(LocalServerConfig.INTERNAL_JOB_KEY)
        assertThat(job.success).isTrue()

        if (job.completion_notifications > 0 || job.completed > 0) {
            val notifications = api.getNotifications(user.access_token, limit = 50)
            val completion = notifications.notifications.find {
                it.type == "milestone_achieved" &&
                    it.metadata?.get("milestone_type") == "challenge_completed"
            }
            assertThat(completion).isNotNull()
            assertThat(completion!!.shareable_card_data).isNotNull()

            val activity = api.getGroupActivity(user.access_token, challenge.challenge.id, limit = 50)
            assertThat(activity.activities.any { it.activity_type == "challenge_completed" }).isTrue()
        }
    }
}
