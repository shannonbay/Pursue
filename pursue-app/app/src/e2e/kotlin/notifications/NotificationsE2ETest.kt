package com.github.shannonbay.pursue.e2e.notifications

import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

/**
 * E2E tests for notification inbox (specs/pursue-notification-inbox-spec.md).
 *
 * Tests GET/POST/PATCH/DELETE /api/notifications and GET /api/notifications/unread-count,
 * plus notification creation triggers (reaction, nudge, membership, etc.).
 *
 * The shared user accumulates notifications across tests, so tests must NOT assume
 * an empty inbox. Use fresh users for "empty" assertions or compare before/after counts.
 */
class NotificationsE2ETest : E2ETest() {

    private fun todayLocalDate(): String = LocalDate.now().toString()

    /**
     * Adds a second user to the group as an approved member.
     * Returns (accessToken, userId) of the second user.
     */
    private suspend fun addSecondUserToGroup(
        creatorToken: String,
        groupId: String,
        displayName: String = "Second User"
    ): Pair<String, String> {
        val invite = api.getGroupInviteCode(creatorToken, groupId)
        val user = testDataHelper.createTestUser(api, displayName = displayName)
        trackUser(user.user!!.id)
        api.joinGroup(user.access_token, invite.invite_code)
        api.approveMember(creatorToken, groupId, user.user!!.id)
        return user.access_token to user.user!!.id
    }

    /**
     * Creates a progress activity and returns the activity ID.
     * Uses a fixed past date to avoid timezone mismatch (server validates "date cannot be in the future").
     */
    private suspend fun createTestActivity(
        accessToken: String,
        groupId: String
    ): String {
        val goal = testDataHelper.createTestGoal(
            api = api,
            accessToken = accessToken,
            groupId = groupId,
            title = "Notification Test Goal ${System.currentTimeMillis()}",
            cadence = "daily",
            metricType = "binary"
        )
        api.logProgress(
            accessToken = accessToken,
            goalId = goal.id,
            value = 1.0,
            note = "E2E notification test",
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )
        val activityResponse = api.getGroupActivity(accessToken, groupId, limit = 10)
        val activity = activityResponse.activities.find { it.activity_type == "progress_logged" }
        assertThat(activity).isNotNull()
        assertThat(activity!!.id).isNotNull()
        return activity.id!!
    }

    // === GET /api/notifications ===

    @Test
    fun `getNotifications returns notifications list after reaction`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken, activityId, "🔥")

        val response = api.getNotifications(owner.access_token, limit = 50)

        assertThat(response.notifications).isNotEmpty()
        val reactionNotif = response.notifications.find {
            it.type == "reaction_received" && it.metadata?.get("emoji") == "🔥"
        }
        assertThat(reactionNotif).isNotNull()
        assertThat(reactionNotif!!.actor).isNotNull()
        assertThat(reactionNotif.actor!!.display_name).isEqualTo("Reactor")
    }

    @Test
    fun `getNotifications returns empty list for fresh user`() = runTest {
        // Fresh user with no activity — must have zero notifications
        val freshUser = testDataHelper.createTestUser(api, displayName = "Fresh User")
        trackUser(freshUser.user!!.id)

        val response = api.getNotifications(freshUser.access_token, limit = 10)

        assertThat(response.notifications).isEmpty()
        assertThat(response.unread_count).isEqualTo(0)
        assertThat(response.has_more).isFalse()
    }

    @Test
    fun `getNotifications pagination with before_id returns older items`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        // Create two notifications so we have at least 2 items
        val (reactorToken1, _) = addSecondUserToGroup(owner.access_token, group.id, "PaginatorA")
        val activityId1 = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken1, activityId1, "🔥")
        val (reactorToken2, _) = addSecondUserToGroup(owner.access_token, group.id, "PaginatorB")
        val activityId2 = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken2, activityId2, "💪")

        // Fetch first page with limit=1
        val firstPage = api.getNotifications(owner.access_token, limit = 1)
        assertThat(firstPage.notifications).hasSize(1)
        val cursor = firstPage.notifications.first().id

        // Fetch second page using cursor
        val secondPage = api.getNotifications(owner.access_token, limit = 50, beforeId = cursor)

        // Second page should not contain the first page's item
        assertThat(secondPage.notifications.none { it.id == cursor }).isTrue()
        // Second page should have at least one item (the older notification)
        assertThat(secondPage.notifications).isNotEmpty()
    }

    @Test
    fun `getNotifications limit parameter caps results`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken, activityId, "🔥")

        val response = api.getNotifications(owner.access_token, limit = 5)

        assertThat(response.notifications.size).isAtMost(5)
    }

    // === GET /api/notifications/unread-count ===

    @Test
    fun `getUnreadCount returns 0 for fresh user`() = runTest {
        val freshUser = testDataHelper.createTestUser(api, displayName = "Fresh Unread")
        trackUser(freshUser.user!!.id)

        val response = api.getUnreadCount(freshUser.access_token)

        assertThat(response.unread_count).isEqualTo(0)
    }

    @Test
    fun `getUnreadCount increments after notification created`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val before = api.getUnreadCount(owner.access_token).unread_count
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken, activityId, "❤️")

        val after = api.getUnreadCount(owner.access_token).unread_count

        assertThat(after).isGreaterThan(before)
    }

    // === POST /api/notifications/mark-all-read ===

    @Test
    fun `markAllNotificationsRead marks all as read`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken, activityId, "👏")
        assertThat(api.getUnreadCount(owner.access_token).unread_count).isAtLeast(1)

        val response = api.markAllNotificationsRead(owner.access_token)

        assertThat(response.marked_read).isAtLeast(1)
        assertThat(api.getUnreadCount(owner.access_token).unread_count).isEqualTo(0)
        // All notifications should now be read
        val list = api.getNotifications(owner.access_token, limit = 50)
        val anyUnread = list.notifications.any { !it.is_read }
        assertThat(anyUnread).isFalse()
    }

    @Test
    fun `markAllNotificationsRead returns count of marked`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken, activityId, "🎉")

        val response = api.markAllNotificationsRead(owner.access_token)

        assertThat(response.marked_read).isAtLeast(1)
    }

    // === PATCH /api/notifications/:id/read ===

    @Test
    fun `markNotificationRead marks single notification as read`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        // Mark all read first so we start clean, then create a fresh unread one
        api.markAllNotificationsRead(owner.access_token)
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken, activityId, "💪")
        val list = api.getNotifications(owner.access_token, limit = 10)
        val notif = list.notifications.first { !it.is_read }

        val response = api.markNotificationRead(owner.access_token, notif.id)

        assertThat(response.id).isEqualTo(notif.id)
        assertThat(response.is_read).isTrue()
        val updated = api.getNotifications(owner.access_token, limit = 50)
        val updatedNotif = updated.notifications.find { it.id == notif.id }
        assertThat(updatedNotif?.is_read).isTrue()
    }

    @Test
    fun `markNotificationRead returns 404 for non-existent`() = runTest {
        val auth = getOrCreateSharedUser()
        val bogusId = "00000000-0000-0000-0000-000000000000"

        var exception: ApiException? = null
        try {
            api.markNotificationRead(auth.access_token, bogusId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    @Test
    fun `markNotificationRead returns 404 for other user notification`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken, activityId, "🔥")
        val ownerList = api.getNotifications(owner.access_token, limit = 1)
        val notifId = ownerList.notifications.first().id
        val otherUser = testDataHelper.createTestUser(api, displayName = "Other")
        trackUser(otherUser.user!!.id)

        var exception: ApiException? = null
        try {
            api.markNotificationRead(otherUser.access_token, notifId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    // === DELETE /api/notifications/:id ===

    @Test
    fun `deleteNotification removes notification`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken, activityId, "🔥")
        val list = api.getNotifications(owner.access_token, limit = 50)
        val notif = list.notifications.first()

        api.deleteNotification(owner.access_token, notif.id)

        val after = api.getNotifications(owner.access_token, limit = 50)
        assertThat(after.notifications.none { it.id == notif.id }).isTrue()
    }

    @Test
    fun `deleteNotification returns 404 for non-existent`() = runTest {
        val auth = getOrCreateSharedUser()
        val bogusId = "00000000-0000-0000-0000-000000000000"

        var exception: ApiException? = null
        try {
            api.deleteNotification(auth.access_token, bogusId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    @Test
    fun `deleteNotification returns 404 for other user notification`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        api.addOrReplaceReaction(reactorToken, activityId, "🔥")
        val ownerList = api.getNotifications(owner.access_token, limit = 1)
        val notifId = ownerList.notifications.first().id
        val otherUser = testDataHelper.createTestUser(api, displayName = "Other")
        trackUser(otherUser.user!!.id)

        var exception: ApiException? = null
        try {
            api.deleteNotification(otherUser.access_token, notifId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    // === Notification trigger tests ===

    @Test
    fun `reaction creates notification for activity owner`() = runTest {
        val owner = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (reactorToken, _) = addSecondUserToGroup(owner.access_token, group.id, "Reactor")
        val activityId = createTestActivity(owner.access_token, group.id)
        val countBefore = api.getNotifications(owner.access_token, limit = 50)
            .notifications.count { it.type == "reaction_received" }

        api.addOrReplaceReaction(reactorToken, activityId, "🔥")

        val list = api.getNotifications(owner.access_token, limit = 50)
        val countAfter = list.notifications.count { it.type == "reaction_received" }
        assertThat(countAfter).isGreaterThan(countBefore)
        val reactionNotif = list.notifications.find {
            it.type == "reaction_received" && it.metadata?.get("emoji") == "🔥"
        }
        assertThat(reactionNotif).isNotNull()
        assertThat(reactionNotif!!.group?.id).isEqualTo(group.id)
        assertThat(reactionNotif.actor?.display_name).isEqualTo("Reactor")
    }

    @Test
    fun `self-reaction does not create notification`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)
        val countBefore = api.getNotifications(auth.access_token, limit = 50).notifications.size

        api.addOrReplaceReaction(auth.access_token, activityId, "🔥")

        val countAfter = api.getNotifications(auth.access_token, limit = 50).notifications.size
        // Self-reaction should not add any notification
        assertThat(countAfter).isEqualTo(countBefore)
    }

    @Test
    fun `nudge creates notification for recipient`() = runTest {
        val sender = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (recipientToken, recipientId) = addSecondUserToGroup(sender.access_token, group.id, "Recipient")
        api.sendNudge(
            accessToken = sender.access_token,
            recipientUserId = recipientId,
            groupId = group.id,
            senderLocalDate = todayLocalDate()
        )

        val list = api.getNotifications(recipientToken, limit = 10)
        val nudgeNotif = list.notifications.find { it.type == "nudge_received" }
        assertThat(nudgeNotif).isNotNull()
        assertThat(nudgeNotif!!.actor).isNotNull()
        assertThat(nudgeNotif.group?.id).isEqualTo(group.id)
    }

    @Test
    fun `membership approval creates notification`() = runTest {
        val admin = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, admin.access_token, name = "Approval Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val invite = api.getGroupInviteCode(admin.access_token, group.id)
        val applicant = testDataHelper.createTestUser(api, displayName = "Applicant")
        trackUser(applicant.user!!.id)
        api.joinGroup(applicant.access_token, invite.invite_code)

        api.approveMember(admin.access_token, group.id, applicant.user!!.id)

        val list = api.getNotifications(applicant.access_token, limit = 10)
        val approvedNotif = list.notifications.find { it.type == "membership_approved" }
        assertThat(approvedNotif).isNotNull()
        assertThat(approvedNotif!!.group?.name).isEqualTo(group.name)
    }

    @Test
    fun `promotion to admin creates notification`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (memberToken, memberId) = addSecondUserToGroup(creator.access_token, group.id, "Member")

        api.updateMemberRole(creator.access_token, group.id, memberId, "admin")

        val list = api.getNotifications(memberToken, limit = 10)
        val promotedNotif = list.notifications.find { it.type == "promoted_to_admin" }
        assertThat(promotedNotif).isNotNull()
        assertThat(promotedNotif!!.group?.id).isEqualTo(group.id)
        assertThat(promotedNotif.actor?.id).isEqualTo(creator.user!!.id)
    }

    @Test
    fun `member removal creates notification`() = runTest {
        val admin = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val (memberToken, memberId) = addSecondUserToGroup(admin.access_token, group.id, "To Remove")

        api.removeMember(admin.access_token, group.id, memberId)

        val list = api.getNotifications(memberToken, limit = 10)
        val removedNotif = list.notifications.find { it.type == "removed_from_group" }
        assertThat(removedNotif).isNotNull()
        assertThat(removedNotif!!.group?.id).isEqualTo(group.id)
    }
}
