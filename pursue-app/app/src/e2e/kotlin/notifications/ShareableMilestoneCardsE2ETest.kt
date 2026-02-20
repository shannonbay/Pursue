package com.github.shannonbay.pursue.e2e.notifications

import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

/**
 * E2E tests for shareable milestone card data (specs/shareable-milestone-cards-spec.md).
 *
 * Verifies that:
 * - Milestone notifications include non-null `shareable_card_data` with correct field values
 * - Non-shareable notifications (reactions, nudges) have null `shareable_card_data`
 * - 7-day streak milestone generates the correct card data
 * - First-log milestone is deduplicated (fires only once per user)
 *
 * Each test uses fresh users to avoid interference from other tests.
 */
class ShareableMilestoneCardsE2ETest : E2ETest() {

    // === First log milestone ===

    @Test
    fun `first log creates milestone notification with non-null shareable_card_data`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "First Log User")
        trackUser(user.user!!.id)
        val group = testDataHelper.createTestGroup(api, user.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, user.access_token, group.id)

        api.logProgress(
            accessToken = user.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-01-01",
            userTimezone = "America/New_York"
        )

        val response = api.getNotifications(user.access_token, limit = 10)
        val milestoneNotif = response.notifications.find { it.type == "milestone_achieved" }

        assertThat(milestoneNotif).isNotNull()
        assertThat(milestoneNotif!!.shareable_card_data).isNotNull()
    }

    @Test
    fun `first_log shareable_card_data has all required fields with correct values`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Card Fields User")
        trackUser(user.user!!.id)
        val group = testDataHelper.createTestGroup(api, user.access_token)
        trackGroup(group.id)
        val goalTitle = "Morning Run ${System.currentTimeMillis()}"
        val goal = testDataHelper.createTestGoal(
            api, user.access_token, group.id,
            title = goalTitle
        )

        api.logProgress(
            accessToken = user.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-01-01",
            userTimezone = "America/New_York"
        )

        val response = api.getNotifications(user.access_token, limit = 10)
        val cardData = response.notifications
            .find { it.type == "milestone_achieved" }
            ?.shareable_card_data

        assertThat(cardData).isNotNull()
        assertThat(cardData!!["milestone_type"]).isEqualTo("first_log")
        assertThat(cardData["title"]).isEqualTo("First step taken")
        assertThat(cardData["subtitle"]).isEqualTo(goalTitle)
        assertThat(cardData["stat_value"]).isEqualTo("1")
        assertThat(cardData["stat_label"]).isEqualTo("progress logged")
        assertThat(cardData["quote"]).isEqualTo("Every journey begins somewhere")
        assertThat(cardData["background_gradient"]).isEqualTo(listOf("#1E88E5", "#1565C0"))
        assertThat(cardData["goal_icon_emoji"]).isNotNull()
        assertThat(cardData["generated_at"]).isNotNull()
    }

    // === Non-shareable notifications ===

    @Test
    fun `reaction notification has null shareable_card_data`() = runTest {
        val owner = testDataHelper.createTestUser(api, displayName = "Reaction Owner")
        trackUser(owner.user!!.id)
        val group = testDataHelper.createTestGroup(api, owner.access_token)
        trackGroup(group.id)

        val reactor = testDataHelper.createTestUser(api, displayName = "Reactor")
        trackUser(reactor.user!!.id)
        val invite = api.getGroupInviteCode(owner.access_token, group.id)
        api.joinGroup(reactor.access_token, invite.invite_code)
        api.approveMember(owner.access_token, group.id, reactor.user!!.id)

        val goal = testDataHelper.createTestGoal(api, owner.access_token, group.id)
        api.logProgress(
            accessToken = owner.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-01-05",
            userTimezone = "America/New_York"
        )
        val activity = api.getGroupActivity(owner.access_token, group.id, limit = 10)
            .activities.first { it.activity_type == "progress_logged" }
        api.addOrReplaceReaction(reactor.access_token, activity.id!!, "\uD83D\uDD25")

        val notifications = api.getNotifications(owner.access_token, limit = 10)
        val reactionNotif = notifications.notifications
            .find { it.type == "reaction_received" && it.metadata?.get("emoji") == "\uD83D\uDD25" }

        assertThat(reactionNotif).isNotNull()
        assertThat(reactionNotif!!.shareable_card_data).isNull()
    }

    @Test
    fun `nudge notification has null shareable_card_data`() = runTest {
        val sender = testDataHelper.createTestUser(api, displayName = "Nudge Sender")
        trackUser(sender.user!!.id)
        val group = testDataHelper.createTestGroup(api, sender.access_token)
        trackGroup(group.id)

        val recipient = testDataHelper.createTestUser(api, displayName = "Nudge Recipient")
        trackUser(recipient.user!!.id)
        val invite = api.getGroupInviteCode(sender.access_token, group.id)
        api.joinGroup(recipient.access_token, invite.invite_code)
        api.approveMember(sender.access_token, group.id, recipient.user!!.id)

        api.sendNudge(
            accessToken = sender.access_token,
            recipientUserId = recipient.user!!.id,
            groupId = group.id,
            senderLocalDate = LocalDate.now().toString()
        )

        val notifications = api.getNotifications(recipient.access_token, limit = 10)
        val nudgeNotif = notifications.notifications.find { it.type == "nudge_received" }

        assertThat(nudgeNotif).isNotNull()
        assertThat(nudgeNotif!!.shareable_card_data).isNull()
    }

    // === 7-day streak milestone ===

    @Test
    fun `seven day streak creates notification with non-null shareable_card_data`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Streak User")
        trackUser(user.user!!.id)
        val group = testDataHelper.createTestGroup(api, user.access_token)
        trackGroup(group.id)
        val goal = testDataHelper.createTestGoal(api, user.access_token, group.id)

        // Log on 7 consecutive past days ending yesterday; server requires mostRecent to be
        // today or yesterday for the streak to count (see getCurrentStreak in notification.service.ts).
        val yesterday = LocalDate.now().minusDays(1)
        for (i in 6 downTo 0) {
            api.logProgress(
                accessToken = user.access_token,
                goalId = goal.id,
                value = 1.0,
                userDate = yesterday.minusDays(i.toLong()).toString(),
                userTimezone = "America/New_York"
            )
        }

        val response = api.getNotifications(user.access_token, limit = 20)
        val streakNotif = response.notifications.find {
            it.type == "milestone_achieved" &&
                    it.metadata?.get("milestone_type") == "streak" &&
                    (it.metadata["streak_count"] as? Number)?.toInt() == 7
        }

        assertThat(streakNotif).isNotNull()
        assertThat(streakNotif!!.shareable_card_data).isNotNull()
    }

    @Test
    fun `seven_day streak shareable_card_data has correct fields`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Streak Fields User")
        trackUser(user.user!!.id)
        val group = testDataHelper.createTestGroup(api, user.access_token)
        trackGroup(group.id)
        val goalTitle = "Daily Exercise ${System.currentTimeMillis()}"
        val goal = testDataHelper.createTestGoal(
            api, user.access_token, group.id,
            title = goalTitle
        )

        val yesterday = LocalDate.now().minusDays(1)
        for (i in 6 downTo 0) {
            api.logProgress(
                accessToken = user.access_token,
                goalId = goal.id,
                value = 1.0,
                userDate = yesterday.minusDays(i.toLong()).toString(),
                userTimezone = "America/New_York"
            )
        }

        val response = api.getNotifications(user.access_token, limit = 20)
        val cardData = response.notifications
            .find {
                it.type == "milestone_achieved" &&
                        it.metadata?.get("milestone_type") == "streak"
            }
            ?.shareable_card_data

        assertThat(cardData).isNotNull()
        assertThat(cardData!!["milestone_type"]).isEqualTo("streak")
        assertThat(cardData["title"]).isEqualTo("7-day streak!")
        assertThat(cardData["subtitle"]).isEqualTo(goalTitle)
        assertThat(cardData["stat_value"]).isEqualTo("7")
        assertThat(cardData["stat_label"]).isEqualTo("days in a row")
        assertThat(cardData["quote"]).isEqualTo("Consistency is everything")
        assertThat(cardData["background_gradient"]).isEqualTo(listOf("#F57C00", "#E65100"))
        assertThat(cardData["generated_at"]).isNotNull()
    }

    // === Deduplication ===

    @Test
    fun `first_log milestone fires exactly once regardless of subsequent logs`() = runTest {
        val user = testDataHelper.createTestUser(api, displayName = "Dedup User")
        trackUser(user.user!!.id)
        val group = testDataHelper.createTestGroup(api, user.access_token)
        trackGroup(group.id)

        val goal1 = testDataHelper.createTestGoal(api, user.access_token, group.id)
        api.logProgress(
            accessToken = user.access_token,
            goalId = goal1.id,
            value = 1.0,
            userDate = "2026-01-01",
            userTimezone = "America/New_York"
        )

        val goal2 = testDataHelper.createTestGoal(api, user.access_token, group.id)
        api.logProgress(
            accessToken = user.access_token,
            goalId = goal2.id,
            value = 1.0,
            userDate = "2026-01-02",
            userTimezone = "America/New_York"
        )

        val response = api.getNotifications(user.access_token, limit = 20)
        val firstLogNotifications = response.notifications.filter {
            it.type == "milestone_achieved" &&
                    it.metadata?.get("milestone_type") == "first_log"
        }

        assertThat(firstLogNotifications).hasSize(1)
    }
}
