package com.github.shannonbay.pursue.e2e.nudges

import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

/**
 * E2E tests for nudge endpoints (specs/pursue-nudge-spec.md).
 *
 * Tests POST /api/nudges and GET /api/groups/:group_id/nudges/sent-today.
 */
class NudgesE2ETest : E2ETest() {

    private fun todayLocalDate(): String = LocalDate.now().toString()

    /**
     * Adds a second user to the shared group as an approved member.
     * Returns the recipient's auth and userId.
     */
    private suspend fun addRecipientToGroup(creatorToken: String, groupId: String): Pair<String, String> {
        val invite = api.getGroupInviteCode(creatorToken, groupId)
        val recipient = testDataHelper.createTestUser(api, displayName = "Nudge Recipient")
        trackUser(recipient.user!!.id)
        api.joinGroup(recipient.access_token, invite.invite_code)
        api.approveMember(creatorToken, groupId, recipient.user!!.id)
        return recipient.access_token to recipient.user!!.id
    }

    // === POST /api/nudges ===

    @Test
    fun `sendNudge succeeds and returns nudge object`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (_recipientToken, recipientId) = addRecipientToGroup(creator.access_token, group.id)
        val senderLocalDate = todayLocalDate()

        val response = api.sendNudge(
            accessToken = creator.access_token,
            recipientUserId = recipientId,
            groupId = group.id,
            senderLocalDate = senderLocalDate
        )

        assertThat(response.nudge).isNotNull()
        assertThat(response.nudge.id).isNotEmpty()
        assertThat(response.nudge.recipient_user_id).isEqualTo(recipientId)
        assertThat(response.nudge.group_id).isEqualTo(group.id)
        assertThat(response.nudge.sent_at).isNotEmpty()
    }

    @Test
    fun `sendNudge to self returns 400 CANNOT_NUDGE_SELF`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val senderLocalDate = todayLocalDate()

        var exception: ApiException? = null
        try {
            api.sendNudge(
                accessToken = creator.access_token,
                recipientUserId = creator.user!!.id,
                groupId = group.id,
                senderLocalDate = senderLocalDate
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
        assertThat(exception.errorCode).isEqualTo("CANNOT_NUDGE_SELF")
    }

    @Test
    fun `sendNudge as non-member returns 403 NOT_A_MEMBER`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (_recipientToken, recipientId) = addRecipientToGroup(creator.access_token, group.id)
        val nonMember = testDataHelper.createTestUser(api, displayName = "Non-Member")
        trackUser(nonMember.user!!.id)
        val senderLocalDate = todayLocalDate()

        var exception: ApiException? = null
        try {
            api.sendNudge(
                accessToken = nonMember.access_token,
                recipientUserId = recipientId,
                groupId = group.id,
                senderLocalDate = senderLocalDate
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `sendNudge to non-member returns 403 RECIPIENT_NOT_IN_GROUP`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val outsider = testDataHelper.createTestUser(api, displayName = "Outsider")
        trackUser(outsider.user!!.id)
        val senderLocalDate = todayLocalDate()

        var exception: ApiException? = null
        try {
            api.sendNudge(
                accessToken = creator.access_token,
                recipientUserId = outsider.user!!.id,
                groupId = group.id,
                senderLocalDate = senderLocalDate
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("RECIPIENT_NOT_IN_GROUP")
    }

    @Test
    fun `sendNudge twice same day returns 409 ALREADY_NUDGED_TODAY`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (_recipientToken, recipientId) = addRecipientToGroup(creator.access_token, group.id)
        val senderLocalDate = todayLocalDate()

        api.sendNudge(
            accessToken = creator.access_token,
            recipientUserId = recipientId,
            groupId = group.id,
            senderLocalDate = senderLocalDate
        )

        var exception: ApiException? = null
        try {
            api.sendNudge(
                accessToken = creator.access_token,
                recipientUserId = recipientId,
                groupId = group.id,
                senderLocalDate = senderLocalDate
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("ALREADY_NUDGED_TODAY")
    }

    @Test
    fun `sendNudge with goal_id includes goal in response`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val goal = testDataHelper.createTestGoal(
            api = api,
            accessToken = creator.access_token,
            groupId = group.id,
            title = "Nudge Goal Test ${System.currentTimeMillis()}",
            cadence = "daily",
            metricType = "binary"
        )
        val (_recipientToken, recipientId) = addRecipientToGroup(creator.access_token, group.id)
        val senderLocalDate = todayLocalDate()

        val response = api.sendNudge(
            accessToken = creator.access_token,
            recipientUserId = recipientId,
            groupId = group.id,
            goalId = goal.id,
            senderLocalDate = senderLocalDate
        )

        assertThat(response.nudge).isNotNull()
        assertThat(response.nudge.goal_id).isEqualTo(goal.id)
    }

    // === GET /api/groups/:group_id/nudges/sent-today ===

    @Test
    fun `getNudgesSentToday returns empty list initially`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, creator.access_token, name = "Empty Nudges Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val senderLocalDate = todayLocalDate()

        val response = api.getNudgesSentToday(
            accessToken = creator.access_token,
            groupId = group.id,
            senderLocalDate = senderLocalDate
        )

        assertThat(response.nudged_user_ids).isEmpty()
    }

    @Test
    fun `getNudgesSentToday returns nudged user ids after sending`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (_recipientToken, recipientId) = addRecipientToGroup(creator.access_token, group.id)
        val senderLocalDate = todayLocalDate()

        api.sendNudge(
            accessToken = creator.access_token,
            recipientUserId = recipientId,
            groupId = group.id,
            senderLocalDate = senderLocalDate
        )

        val response = api.getNudgesSentToday(
            accessToken = creator.access_token,
            groupId = group.id,
            senderLocalDate = senderLocalDate
        )

        assertThat(response.nudged_user_ids).contains(recipientId)
    }

    @Test
    fun `getNudgesSentToday as non-member returns 403`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val nonMember = testDataHelper.createTestUser(api, displayName = "Non-Member Get")
        trackUser(nonMember.user!!.id)
        val senderLocalDate = todayLocalDate()

        var exception: ApiException? = null
        try {
            api.getNudgesSentToday(
                accessToken = nonMember.access_token,
                groupId = group.id,
                senderLocalDate = senderLocalDate
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }
}
