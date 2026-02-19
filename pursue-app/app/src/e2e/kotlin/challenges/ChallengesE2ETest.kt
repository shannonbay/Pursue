package com.github.shannonbay.pursue.e2e.challenges

import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.CreateChallengeGoal
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ChallengesE2ETest : E2ETest() {

    private fun datePlus(days: Long): String = LocalDate.now().plusDays(days).toString()
    private fun dateTodayUtc(): String = LocalDate.now(ZoneOffset.UTC).toString()

    private suspend fun firstTemplateId(accessToken: String): String {
        val templates = api.getChallengeTemplates(accessToken)
        assertThat(templates.templates).isNotEmpty()
        return templates.templates.first().id
    }

    @Test
    fun `challenge templates require auth`() = runTest {
        SecureTokenManager.getInstance(context).clearTokens()

        var exception: ApiException? = null
        try {
            api.getChallengeTemplates(accessToken = "")
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(401)
    }

    @Test
    fun `challenge templates list and filters return expected shape`() = runTest {
        val user = getOrCreateSharedUser()
        val all = api.getChallengeTemplates(user.access_token)
        assertThat(all.templates).isNotEmpty()
        assertThat(all.categories).isNotEmpty()
        assertThat(all.templates.first().goals).isNotEmpty()

        val firstCategory = all.templates.first().category
        val byCategory = api.getChallengeTemplates(user.access_token, category = firstCategory)
        assertThat(byCategory.templates).isNotEmpty()
        assertThat(byCategory.templates.all { it.category == firstCategory }).isTrue()

        val featured = api.getChallengeTemplates(user.access_token, featured = true)
        assertThat(featured.templates.all { it.is_featured }).isTrue()
    }

    @Test
    fun `create template challenge succeeds with invite details`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Challenge Creator")
        trackUser(user.user!!.id)
        val templateId = firstTemplateId(user.access_token)

        val response = api.createChallenge(
            accessToken = user.access_token,
            templateId = templateId,
            startDate = datePlus(1),
            groupName = "Template Challenge ${System.currentTimeMillis()}"
        )
        trackGroup(response.challenge.id)

        assertThat(response.challenge.is_challenge).isTrue()
        assertThat(response.challenge.invite_code).startsWith("PURSUE-")
        assertThat(response.challenge.invite_url).contains(response.challenge.invite_code)
        assertThat(response.challenge.goals).isNotEmpty()
    }

    @Test
    fun `free user custom challenge is denied with PREMIUM_REQUIRED`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Free Challenge User")
        trackUser(user.user!!.id)

        var exception: ApiException? = null
        try {
            api.createChallenge(
                accessToken = user.access_token,
                startDate = datePlus(1),
                endDate = datePlus(7),
                groupName = "Custom Free",
                goals = listOf(
                    CreateChallengeGoal(
                        title = "Do thing",
                        cadence = "daily",
                        metric_type = "binary"
                    )
                )
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("PREMIUM_REQUIRED")
    }

    @Test
    fun `premium user custom challenge succeeds`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Premium Challenge User")
        trackUser(user.user!!.id)
        testDataHelper.upgradeToPremium(api, user.access_token)

        val response = api.createChallenge(
            accessToken = user.access_token,
            startDate = datePlus(1),
            endDate = datePlus(10),
            groupName = "Premium Custom ${System.currentTimeMillis()}",
            goals = listOf(
                CreateChallengeGoal(
                    title = "Custom Goal",
                    cadence = "daily",
                    metric_type = "binary"
                )
            )
        )
        trackGroup(response.challenge.id)

        assertThat(response.challenge.name).contains("Premium Custom")
        assertThat(response.challenge.goals).hasSize(1)
    }

    @Test
    fun `free cap create then join challenge is denied`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Cap A")
        trackUser(user.user!!.id)
        val other = testDataHelper.createTestUser(api, displayName = "Cap B")
        trackUser(other.user!!.id)
        val templateId = firstTemplateId(user.access_token)

        val first = api.createChallenge(
            accessToken = user.access_token,
            templateId = templateId,
            startDate = datePlus(1)
        )
        trackGroup(first.challenge.id)

        val second = api.createChallenge(
            accessToken = other.access_token,
            templateId = templateId,
            startDate = datePlus(1)
        )
        trackGroup(second.challenge.id)

        val invite = api.getGroupInviteCode(other.access_token, second.challenge.id)

        var exception: ApiException? = null
        try {
            api.joinGroup(user.access_token, invite.invite_code)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("GROUP_LIMIT_REACHED")
    }

    @Test
    fun `free cap join then create challenge is denied`() = runTest {
        val joiner = testDataHelper.createTestUser(api, displayName = "Join Then Create")
        trackUser(joiner.user!!.id)
        val creator = testDataHelper.createTestUser(api, displayName = "Creator For Join")
        trackUser(creator.user!!.id)
        val templateId = firstTemplateId(creator.access_token)

        val challenge = api.createChallenge(
            accessToken = creator.access_token,
            templateId = templateId,
            startDate = datePlus(1)
        )
        trackGroup(challenge.challenge.id)

        val invite = api.getGroupInviteCode(creator.access_token, challenge.challenge.id)
        api.joinGroup(joiner.access_token, invite.invite_code)
        api.approveMember(creator.access_token, challenge.challenge.id, joiner.user!!.id)

        var exception: ApiException? = null
        try {
            api.createChallenge(
                accessToken = joiner.access_token,
                templateId = templateId,
                startDate = datePlus(1)
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("GROUP_LIMIT_REACHED")
    }

    @Test
    fun `get challenges supports status and metrics`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "List Challenge User")
        trackUser(user.user!!.id)
        val templateId = firstTemplateId(user.access_token)

        val created = api.createChallenge(
            accessToken = user.access_token,
            templateId = templateId,
            startDate = datePlus(1),
            groupName = "List Challenge ${System.currentTimeMillis()}"
        )
        trackGroup(created.challenge.id)

        val expectedStatus = created.challenge.challenge_status
        val list = api.getChallenges(user.access_token, status = expectedStatus)
        val item = list.challenges.find { it.id == created.challenge.id }

        assertThat(item).isNotNull()
        assertThat(item!!.challenge_status).isEqualTo(expectedStatus)
        assertThat(item!!.total_days).isAtLeast(1)
        if (expectedStatus == "active") {
            assertThat(item.days_elapsed).isAtLeast(1)
        } else {
            assertThat(item.days_elapsed).isEqualTo(0)
        }
        assertThat(item.days_remaining).isAtLeast(1)
        assertThat(item.my_completion_rate).isAtLeast(0.0)
    }

    @Test
    fun `cancel challenge enforces creator and state rules`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Cancel Creator")
        trackUser(creator.user!!.id)
        val other = testDataHelper.createTestUser(api, displayName = "Cancel Other")
        trackUser(other.user!!.id)
        val templateId = firstTemplateId(creator.access_token)

        val challenge = api.createChallenge(
            accessToken = creator.access_token,
            templateId = templateId,
            startDate = datePlus(2)
        )
        trackGroup(challenge.challenge.id)

        val invite = api.getGroupInviteCode(creator.access_token, challenge.challenge.id)
        api.joinGroup(other.access_token, invite.invite_code)

        var nonCreatorError: ApiException? = null
        try {
            api.cancelChallenge(other.access_token, challenge.challenge.id)
        } catch (e: ApiException) {
            nonCreatorError = e
        }
        assertThat(nonCreatorError).isNotNull()
        assertThat(nonCreatorError!!.code).isEqualTo(403)

        val cancelled = api.cancelChallenge(creator.access_token, challenge.challenge.id)
        assertThat(cancelled.challenge_status).isEqualTo("cancelled")

        var secondCancelError: ApiException? = null
        try {
            api.cancelChallenge(creator.access_token, challenge.challenge.id)
        } catch (e: ApiException) {
            secondCancelError = e
        }
        assertThat(secondCancelError).isNotNull()
        assertThat(secondCancelError!!.code).isEqualTo(400)
    }

    @Test
    fun `joining cancelled challenge is rejected with CHALLENGE_ENDED`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Ended Creator")
        trackUser(creator.user!!.id)
        val joiner = testDataHelper.createTestUser(api, displayName = "Ended Joiner")
        trackUser(joiner.user!!.id)
        val templateId = firstTemplateId(creator.access_token)

        val challenge = api.createChallenge(
            accessToken = creator.access_token,
            templateId = templateId,
            startDate = datePlus(1)
        )
        trackGroup(challenge.challenge.id)
        val invite = api.getGroupInviteCode(creator.access_token, challenge.challenge.id)
        api.cancelChallenge(creator.access_token, challenge.challenge.id)

        var exception: ApiException? = null
        try {
            api.joinGroup(joiner.access_token, invite.invite_code)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("CHALLENGE_ENDED")
    }

    @Test
    fun `adding goal is blocked only for active challenges`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Goal Lock User")
        trackUser(user.user!!.id)
        val templateId = firstTemplateId(user.access_token)

        val activeChallenge = api.createChallenge(
            accessToken = user.access_token,
            templateId = templateId,
            startDate = dateTodayUtc()
        )
        trackGroup(activeChallenge.challenge.id)

        var activeError: ApiException? = null
        try {
            api.createGoal(
                accessToken = user.access_token,
                groupId = activeChallenge.challenge.id,
                title = "Should Fail",
                cadence = "daily",
                metricType = "binary"
            )
        } catch (e: ApiException) {
            activeError = e
        }
        assertThat(activeError).isNotNull()
        assertThat(activeError!!.code).isEqualTo(403)
        assertThat(activeError.errorCode).isEqualTo("CHALLENGE_GOALS_LOCKED")

        val upcomingUser = testDataHelper.createTestUser(api, displayName = "Goal Lock Upcoming User")
        trackUser(upcomingUser.user!!.id)
        val upcomingTemplateId = firstTemplateId(upcomingUser.access_token)

        val upcomingChallenge = api.createChallenge(
            accessToken = upcomingUser.access_token,
            templateId = upcomingTemplateId,
            startDate = datePlus(5)
        )
        trackGroup(upcomingChallenge.challenge.id)

        val goal = api.createGoal(
            accessToken = upcomingUser.access_token,
            groupId = upcomingChallenge.challenge.id,
            title = "Allowed Goal",
            cadence = "daily",
            metricType = "binary"
        )
        assertThat(goal.id).isNotEmpty()
    }

    @Test
    fun `group payloads include challenge metadata`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Payload User")
        trackUser(user.user!!.id)
        val templateId = firstTemplateId(user.access_token)

        val created = api.createChallenge(
            accessToken = user.access_token,
            templateId = templateId,
            startDate = datePlus(1)
        )
        trackGroup(created.challenge.id)

        val groups = api.getMyGroups(user.access_token)
        val challengeGroup = groups.groups.find { it.id == created.challenge.id }
        assertThat(challengeGroup).isNotNull()
        assertThat(challengeGroup!!.is_challenge).isTrue()
        assertThat(challengeGroup.challenge_start_date).isNotNull()
        assertThat(challengeGroup.challenge_end_date).isNotNull()
        assertThat(challengeGroup.challenge_status).isNotNull()

        val details = api.getGroupDetails(user.access_token, created.challenge.id)
        assertThat(details.is_challenge).isTrue()
        assertThat(details.challenge_start_date).isNotNull()
        assertThat(details.challenge_end_date).isNotNull()
        assertThat(details.challenge_status).isNotNull()
    }

    @Test
    fun `challenge create returns invite referral card data`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Invite Card Creator")
        trackUser(user.user!!.id)
        val templateId = firstTemplateId(user.access_token)

        val created = api.createChallenge(
            accessToken = user.access_token,
            templateId = templateId,
            startDate = datePlus(1)
        )
        trackGroup(created.challenge.id)

        val card = created.challenge.invite_card_data
        assertThat(card).isNotNull()
        assertThat(card!!.card_type).isEqualTo("challenge_invite")
        assertThat(card.invite_url).contains("/challenge/${created.challenge.invite_code}")
        assertThat(card.share_url).contains("/challenge/${created.challenge.invite_code}")
        assertThat(card.qr_url).contains("/challenge/${created.challenge.invite_code}")
        assertThat(card.share_url).contains("utm_source=share")
        assertThat(card.qr_url).contains("utm_source=qr")
        assertThat(card.share_url).contains("utm_medium=challenge_card")
        assertThat(card.qr_url).contains("utm_medium=challenge_card")
        assertThat(card.share_url).contains("ref=")
        assertThat(card.qr_url).contains("ref=")
        assertThat(card.share_url).doesNotContain(user.user!!.id)
        assertThat(card.qr_url).doesNotContain(user.user!!.id)
    }

    @Test
    fun `challenge invite endpoint returns referral card data and regenerate updates code`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Invite Card Endpoint")
        trackUser(user.user!!.id)
        val templateId = firstTemplateId(user.access_token)

        val created = api.createChallenge(
            accessToken = user.access_token,
            templateId = templateId,
            startDate = datePlus(1)
        )
        trackGroup(created.challenge.id)

        val invite = api.getGroupInviteCode(user.access_token, created.challenge.id)
        val inviteCard = invite.invite_card_data
        assertThat(inviteCard).isNotNull()
        assertThat(inviteCard!!.invite_url).contains("/challenge/${invite.invite_code}")
        assertThat(inviteCard.share_url).contains("/challenge/${invite.invite_code}")
        assertThat(inviteCard.qr_url).contains("/challenge/${invite.invite_code}")

        val regenerated = api.regenerateInviteCode(user.access_token, created.challenge.id)
        val regeneratedCard = regenerated.invite_card_data
        assertThat(regenerated.invite_code).isNotEqualTo(invite.invite_code)
        assertThat(regeneratedCard).isNotNull()
        assertThat(regeneratedCard!!.invite_url).contains("/challenge/${regenerated.invite_code}")
        assertThat(regeneratedCard.share_url).contains("/challenge/${regenerated.invite_code}")
        assertThat(regeneratedCard.qr_url).contains("/challenge/${regenerated.invite_code}")
    }

    @Test
    fun `challenge invite referral token is stable per user and different across users`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Invite Token Creator")
        trackUser(creator.user!!.id)
        val member = testDataHelper.createTestUser(api, displayName = "Invite Token Member")
        trackUser(member.user!!.id)
        val templateId = firstTemplateId(creator.access_token)

        val created = api.createChallenge(
            accessToken = creator.access_token,
            templateId = templateId,
            startDate = datePlus(1)
        )
        trackGroup(created.challenge.id)

        val initialInvite = api.getGroupInviteCode(creator.access_token, created.challenge.id)
        api.joinGroup(member.access_token, initialInvite.invite_code)

        val creatorInviteA = api.getGroupInviteCode(creator.access_token, created.challenge.id)
        val creatorInviteB = api.getGroupInviteCode(creator.access_token, created.challenge.id)
        val memberInvite = api.getGroupInviteCode(member.access_token, created.challenge.id)

        val creatorTokenA = creatorInviteA.invite_card_data!!.referral_token
        val creatorTokenB = creatorInviteB.invite_card_data!!.referral_token
        val memberToken = memberInvite.invite_card_data!!.referral_token

        assertThat(creatorTokenA).isEqualTo(creatorTokenB)
        assertThat(memberToken).isNotEqualTo(creatorTokenA)
    }
}
