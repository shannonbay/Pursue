package com.github.shannonbay.pursue.e2e.challenges

import app.getpursue.data.network.ChallengeCompletionCardData
import app.getpursue.data.network.CreateChallengeGoal
import app.getpursue.data.network.toChallengeCompletionCardDataOrNull
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.github.shannonbay.pursue.e2e.config.LocalServerConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class ChallengeCompletionCardsE2ETest : E2ETest() {

    private fun dateTodayUtc(): String = LocalDate.now(ZoneOffset.UTC).toString()
    private val forceNowIso = "2030-01-01T00:00:00.000Z"

    private suspend fun createOneDayCustomChallenge(accessToken: String, namePrefix: String): String {
        val today = dateTodayUtc()
        val response = api.createChallenge(
            accessToken = accessToken,
            startDate = today,
            endDate = today,
            groupName = "$namePrefix ${System.currentTimeMillis()}",
            goals = listOf(
                CreateChallengeGoal(
                    title = "Completion Goal",
                    cadence = "daily",
                    metric_type = "binary"
                )
            )
        )
        return response.challenge.id
    }

    private suspend fun findCompletionCardForGroup(
        accessToken: String,
        groupId: String,
        retries: Int = 4,
        delayMs: Long = 600L
    ): ChallengeCompletionCardData? {
        repeat(retries) {
            api.triggerChallengeStatusUpdateJob(
                internalJobKey = LocalServerConfig.INTERNAL_JOB_KEY,
                forceNow = forceNowIso
            )
            val notification = api.getChallengeCompletionNotifications(accessToken, limit = 50)
                .firstOrNull { it.group?.id == groupId }
            val card = notification?.toChallengeCompletionCardDataOrNull()
            if (card != null) return card
            delay(delayMs)
        }
        return null
    }

    @Test
    fun `completion card payload has required section 8_2 and additive fields`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Completion Card Payload Creator")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val groupId = createOneDayCustomChallenge(creator.access_token, "Completion Payload")
        trackGroup(groupId)

        val card = findCompletionCardForGroup(creator.access_token, groupId)
        assertThat(card).isNotNull()

        assertThat(card!!.card_type).isEqualTo("challenge_completion")
        assertThat(card.milestone_type).isEqualTo("challenge_completed")
        assertThat(card.title).isNotEmpty()
        assertThat(card.subtitle).isNotEmpty()
        assertThat(card.stat_value).isNotEmpty()
        assertThat(card.stat_label).isNotEmpty()
        assertThat(card.quote).isNotEmpty()
        assertThat(card.goal_icon_emoji).isNotNull()
        assertThat(card.generated_at).isNotNull()
        assertThat(card.background_image_url).contains("/assets/challenge_completion_background.png")
        assertThat(card.background_gradient).isNotNull()
        assertThat(card.referral_token).isNotNull()
        assertThat(card.share_url).isNotNull()
        assertThat(card.qr_url).isNotNull()
    }

    @Test
    fun `completion card uses app root referral urls not challenge deep link`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Completion Card URL Creator")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val groupId = createOneDayCustomChallenge(creator.access_token, "Completion URL")
        trackGroup(groupId)

        val card = findCompletionCardForGroup(creator.access_token, groupId)
        assertThat(card).isNotNull()

        val shareUrl = card!!.share_url!!
        val qrUrl = card.qr_url!!
        assertThat(shareUrl).startsWith("https://getpursue.app")
        assertThat(qrUrl).startsWith("https://getpursue.app")
        assertThat(shareUrl).contains("utm_source=share")
        assertThat(qrUrl).contains("utm_source=qr")
        assertThat(shareUrl).contains("utm_medium=challenge_completion_card")
        assertThat(qrUrl).contains("utm_medium=challenge_completion_card")
        assertThat(shareUrl).contains("utm_campaign=challenge_completed")
        assertThat(qrUrl).contains("utm_campaign=challenge_completed")
        assertThat(shareUrl).contains("ref=")
        assertThat(qrUrl).contains("ref=")
        assertThat(shareUrl).doesNotContain("/challenge/")
        assertThat(qrUrl).doesNotContain("/challenge/")
        assertThat(shareUrl).doesNotContain(creator.user!!.id)
        assertThat(qrUrl).doesNotContain(creator.user!!.id)
    }

    @Test
    fun `referral token is stable per user and different across users`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Completion Token Creator")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val member = testDataHelper.createTestUser(api, displayName = "Completion Token Member")
        trackUser(member.user!!.id)
        testDataHelper.upgradeToPremium(api, member.access_token)

        val firstGroupId = createOneDayCustomChallenge(creator.access_token, "Completion Token A")
        trackGroup(firstGroupId)
        val firstInvite = api.getGroupInviteCode(creator.access_token, firstGroupId)
        api.joinGroup(member.access_token, firstInvite.invite_code)
        runCatching { api.approveMember(creator.access_token, firstGroupId, member.user!!.id) }

        val creatorCardA = findCompletionCardForGroup(creator.access_token, firstGroupId)
        val memberCardA = findCompletionCardForGroup(member.access_token, firstGroupId)

        val secondGroupId = createOneDayCustomChallenge(creator.access_token, "Completion Token B")
        trackGroup(secondGroupId)
        val secondInvite = api.getGroupInviteCode(creator.access_token, secondGroupId)
        api.joinGroup(member.access_token, secondInvite.invite_code)
        runCatching { api.approveMember(creator.access_token, secondGroupId, member.user!!.id) }

        val creatorCardB = findCompletionCardForGroup(creator.access_token, secondGroupId)
        val memberCardB = findCompletionCardForGroup(member.access_token, secondGroupId)

        assertThat(creatorCardA).isNotNull()
        assertThat(memberCardA).isNotNull()
        assertThat(creatorCardB).isNotNull()
        assertThat(memberCardB).isNotNull()

        assertThat(creatorCardA!!.referral_token).isEqualTo(creatorCardB!!.referral_token)
        assertThat(memberCardA!!.referral_token).isEqualTo(memberCardB!!.referral_token)
        assertThat(creatorCardA.referral_token).isNotEqualTo(memberCardA.referral_token)
    }

    @Test
    fun `image first contract retains gradient fallback`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Completion Image Fallback Creator")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val groupId = createOneDayCustomChallenge(creator.access_token, "Completion Image Fallback")
        trackGroup(groupId)

        val card = findCompletionCardForGroup(creator.access_token, groupId)
        assertThat(card).isNotNull()

        assertThat(card!!.background_image_url).contains("/assets/challenge_completion_background.png")
        assertThat(card.background_gradient).isNotNull()
        assertThat(card.background_gradient).isNotEmpty()
    }
}
