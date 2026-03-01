package com.github.shannonbay.pursue.e2e.sessions

import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * E2E tests for Body-Teaming endpoints (specs/body-teaming-spec.md).
 *
 * Covers:
 *   Sessions: POST create, GET active, POST join, POST start, POST end, DELETE leave
 *   Slots:    POST create, GET list, DELETE cancel, POST rsvp, DELETE unrsvp, GET /me/slots
 *
 * Design:
 *   - Session tests use a fresh host user + fresh group per test to guarantee session state
 *     isolation and avoid the 10-group-per-user DB cap for the shared user.
 *   - Slot tests use getOrCreateSharedGroup() since slots are always found by ID; no state
 *     bleed between tests.
 *   - Premium-gating tests use the shared premium user with the shared group.
 */
class FocusSessionsE2ETest : E2ETest() {

    /** Returns an ISO-8601 timestamp N hours from now (always in the future). */
    private fun futureTimestamp(plusHours: Long = 1): String =
        Instant.now().plus(plusHours, ChronoUnit.HOURS).toString()

    /**
     * Helper: invite a new user to a group and approve them.
     * Returns (memberAccessToken, memberUserId).
     */
    private suspend fun addMemberToGroup(creatorToken: String, groupId: String): Pair<String, String> {
        val invite = api.getGroupInviteCode(creatorToken, groupId)
        val member = testDataHelper.createTestUser(api, displayName = "Session Member")
        trackUser(member.user!!.id)
        api.joinGroup(member.access_token, invite.invite_code)
        api.approveMember(creatorToken, groupId, member.user!!.id)
        return member.access_token to member.user!!.id
    }

    // =========================================================================
    // POST /api/groups/:groupId/sessions
    // =========================================================================

    @Test
    fun `createFocusSession returns 201 with lobby session and participant`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val response = api.createFocusSession(auth.access_token, group.id, focusDurationMinutes = 25)

        assertThat(response.session).isNotNull()
        assertThat(response.session.id).isNotEmpty()
        assertThat(response.session.group_id).isEqualTo(group.id)
        assertThat(response.session.host_user_id).isEqualTo(auth.user!!.id)
        assertThat(response.session.status).isEqualTo("lobby")
        assertThat(response.session.focus_duration_minutes).isEqualTo(25)
        assertThat(response.session.started_at).isNull()
        assertThat(response.session.ended_at).isNull()
        assertThat(response.session.participants).isNotNull()
        assertThat(response.session.participants!!.map { it.user_id }).contains(auth.user!!.id)
    }

    @Test
    fun `createFocusSession with 90-min as free user returns 403 PREMIUM_REQUIRED`() = runTest {
        val freeUser = testDataHelper.createTestUser(api, displayName = "Free User")
        trackUser(freeUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, freeUser.access_token)
        trackGroup(group.id)

        var exception: ApiException? = null
        try {
            api.createFocusSession(freeUser.access_token, group.id, focusDurationMinutes = 90)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("PREMIUM_REQUIRED")
    }

    @Test
    fun `createFocusSession with 90-min as premium user returns 201`() = runTest {
        val auth = getOrCreateSharedUser()  // already premium
        val group = getOrCreateSharedGroup()

        val response = api.createFocusSession(auth.access_token, group.id, focusDurationMinutes = 90)

        assertThat(response.session.focus_duration_minutes).isEqualTo(90)
        assertThat(response.session.status).isEqualTo("lobby")
    }

    @Test
    fun `createFocusSession as non-member returns 403`() = runTest {
        val group = getOrCreateSharedGroup()
        val nonMember = testDataHelper.createTestUser(api, displayName = "Non Member")
        trackUser(nonMember.user!!.id)

        var exception: ApiException? = null
        try {
            api.createFocusSession(nonMember.access_token, group.id, focusDurationMinutes = 25)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }

    // =========================================================================
    // GET /api/groups/:groupId/sessions/active
    // =========================================================================

    @Test
    fun `getActiveSessions returns empty list when no sessions exist`() = runTest {
        // Use a fresh user + fresh group to guarantee no pre-existing sessions
        val hostUser = testDataHelper.createTestUser(api, displayName = "Empty Sessions Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "Empty Sessions Group ${System.currentTimeMillis()}")
        trackGroup(group.id)

        val response = api.getActiveSessions(hostUser.access_token, group.id)

        assertThat(response.sessions).isEmpty()
    }

    @Test
    fun `getActiveSessions returns active session with participants`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "Active Sessions Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "Active Sessions Group ${System.currentTimeMillis()}")
        trackGroup(group.id)

        api.createFocusSession(hostUser.access_token, group.id, focusDurationMinutes = 25)

        val response = api.getActiveSessions(hostUser.access_token, group.id)

        assertThat(response.sessions).isNotEmpty()
        val session = response.sessions.first()
        assertThat(session.status).isEqualTo("lobby")
        assertThat(session.group_id).isEqualTo(group.id)
        assertThat(session.participants).isNotNull()
        assertThat(session.participants!!).isNotEmpty()
    }

    // =========================================================================
    // POST /api/groups/:groupId/sessions/:id/join
    // =========================================================================

    @Test
    fun `joinSession adds participant and returns 200 with updated session`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "Join Session Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "Join Session Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val created = api.createFocusSession(hostUser.access_token, group.id, focusDurationMinutes = 25)
        val sessionId = created.session.id

        val (memberToken, memberId) = addMemberToGroup(hostUser.access_token, group.id)

        val response = api.joinSession(memberToken, group.id, sessionId)

        assertThat(response.session.id).isEqualTo(sessionId)
        assertThat(response.session.participants).isNotNull()
        assertThat(response.session.participants!!.map { it.user_id }).contains(memberId)
        // Normal join — not a spawned session
        assertThat(response.spawned).isNotEqualTo(true)
    }

    @Test
    fun `joinSession on ended session returns 409 SESSION_ENDED`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "Ended Session Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "Ended Session Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val created = api.createFocusSession(hostUser.access_token, group.id, focusDurationMinutes = 25)
        val sessionId = created.session.id
        api.endSession(hostUser.access_token, group.id, sessionId)

        var exception: ApiException? = null
        try {
            api.joinSession(hostUser.access_token, group.id, sessionId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("SESSION_ENDED")
    }

    // =========================================================================
    // POST /api/groups/:groupId/sessions/:id/start
    // =========================================================================

    @Test
    fun `startSession by host transitions status to focus`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "Start Session Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "Start Session Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val sessionId = api.createFocusSession(hostUser.access_token, group.id, 25).session.id

        val response = api.startSession(hostUser.access_token, group.id, sessionId)

        assertThat(response.session.status).isEqualTo("focus")
        assertThat(response.session.started_at).isNotNull()
    }

    @Test
    fun `startSession by non-host returns 403 FORBIDDEN`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "NonHost Start Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "NonHost Start Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val sessionId = api.createFocusSession(hostUser.access_token, group.id, 25).session.id
        val (memberToken, _) = addMemberToGroup(hostUser.access_token, group.id)
        api.joinSession(memberToken, group.id, sessionId)

        var exception: ApiException? = null
        try {
            api.startSession(memberToken, group.id, sessionId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `startSession on already-started session returns 409 INVALID_STATUS`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "Double Start Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "Double Start Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val sessionId = api.createFocusSession(hostUser.access_token, group.id, 25).session.id
        api.startSession(hostUser.access_token, group.id, sessionId)

        var exception: ApiException? = null
        try {
            api.startSession(hostUser.access_token, group.id, sessionId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("INVALID_STATUS")
    }

    // =========================================================================
    // POST /api/groups/:groupId/sessions/:id/end
    // =========================================================================

    @Test
    fun `endSession by host transitions status to ended`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "End Session Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "End Session Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val sessionId = api.createFocusSession(hostUser.access_token, group.id, 25).session.id

        val response = api.endSession(hostUser.access_token, group.id, sessionId)

        assertThat(response.session.status).isEqualTo("ended")
        assertThat(response.session.ended_at).isNotNull()
    }

    @Test
    fun `endSession by non-host returns 403 FORBIDDEN`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "NonHost End Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "NonHost End Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val sessionId = api.createFocusSession(hostUser.access_token, group.id, 25).session.id
        val (memberToken, _) = addMemberToGroup(hostUser.access_token, group.id)
        api.joinSession(memberToken, group.id, sessionId)

        var exception: ApiException? = null
        try {
            api.endSession(memberToken, group.id, sessionId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("FORBIDDEN")
    }

    // =========================================================================
    // DELETE /api/groups/:groupId/sessions/:id/leave
    // =========================================================================

    @Test
    fun `leaveSession as participant returns 204`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "Leave Session Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "Leave Session Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val sessionId = api.createFocusSession(hostUser.access_token, group.id, 25).session.id
        val (memberToken, _) = addMemberToGroup(hostUser.access_token, group.id)
        api.joinSession(memberToken, group.id, sessionId)

        // Should not throw
        api.leaveSession(memberToken, group.id, sessionId)

        // Host can still see the session as active
        val active = api.getActiveSessions(hostUser.access_token, group.id)
        assertThat(active.sessions.any { it.id == sessionId }).isTrue()
    }

    @Test
    fun `leaveSession as host promotes next participant`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "Host Leave Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "Host Leave Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val sessionId = api.createFocusSession(hostUser.access_token, group.id, 25).session.id
        val (memberToken, memberId) = addMemberToGroup(hostUser.access_token, group.id)
        api.joinSession(memberToken, group.id, sessionId)

        // Host leaves — member should become new host
        api.leaveSession(hostUser.access_token, group.id, sessionId)

        // Member (now host) can see the session; verify host_user_id updated
        val active = api.getActiveSessions(memberToken, group.id)
        val session = active.sessions.firstOrNull { it.id == sessionId }
        assertThat(session).isNotNull()
        assertThat(session!!.host_user_id).isEqualTo(memberId)
    }

    @Test
    fun `leaveSession as last participant auto-ends the session`() = runTest {
        val hostUser = testDataHelper.createTestUser(api, displayName = "Last Participant Host")
        trackUser(hostUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, hostUser.access_token,
            name = "Last Participant Group ${System.currentTimeMillis()}")
        trackGroup(group.id)
        val sessionId = api.createFocusSession(hostUser.access_token, group.id, 25).session.id

        // Host is the only participant; leaving should end the session
        api.leaveSession(hostUser.access_token, group.id, sessionId)

        val active = api.getActiveSessions(hostUser.access_token, group.id)
        assertThat(active.sessions.none { it.id == sessionId }).isTrue()
    }

    // =========================================================================
    // POST /api/groups/:groupId/slots
    // =========================================================================

    @Test
    fun `createFocusSlot returns 201 with slot`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        val response = api.createFocusSlot(
            accessToken = auth.access_token,
            groupId = group.id,
            scheduledStart = futureTimestamp(2),
            focusDurationMinutes = 45,
            note = "E2E writing sprint"
        )

        assertThat(response.slot).isNotNull()
        assertThat(response.slot.id).isNotEmpty()
        assertThat(response.slot.group_id).isEqualTo(group.id)
        assertThat(response.slot.created_by).isEqualTo(auth.user!!.id)
        assertThat(response.slot.focus_duration_minutes).isEqualTo(45)
        assertThat(response.slot.note).isEqualTo("E2E writing sprint")
        assertThat(response.slot.cancelled_at).isNull()
    }

    @Test
    fun `createFocusSlot with past scheduled_start returns 422`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val pastTimestamp = Instant.now().minus(1, ChronoUnit.HOURS).toString()

        var exception: ApiException? = null
        try {
            api.createFocusSlot(auth.access_token, group.id, pastTimestamp, 25)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(422)
    }

    @Test
    fun `createFocusSlot with 90-min as free user returns 403 PREMIUM_REQUIRED`() = runTest {
        val freeUser = testDataHelper.createTestUser(api, displayName = "Free Slot User")
        trackUser(freeUser.user!!.id)
        val group = testDataHelper.createTestGroup(api, freeUser.access_token)
        trackGroup(group.id)

        var exception: ApiException? = null
        try {
            api.createFocusSlot(freeUser.access_token, group.id, futureTimestamp(1), 90)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("PREMIUM_REQUIRED")
    }

    // =========================================================================
    // GET /api/groups/:groupId/slots
    // =========================================================================

    @Test
    fun `listFocusSlots returns upcoming slot with rsvp_count and user_rsvped`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val slotResponse = api.createFocusSlot(auth.access_token, group.id, futureTimestamp(3), 25)
        val slotId = slotResponse.slot.id

        // RSVP as creator
        api.rsvpFocusSlot(auth.access_token, group.id, slotId)

        val listResponse = api.listFocusSlots(auth.access_token, group.id)

        assertThat(listResponse.slots).isNotEmpty()
        val slot = listResponse.slots.first { it.id == slotId }
        assertThat(slot.rsvp_count).isEqualTo(1)
        assertThat(slot.user_rsvped).isTrue()
    }

    @Test
    fun `listFocusSlots does not return cancelled slots`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val slotId = api.createFocusSlot(auth.access_token, group.id, futureTimestamp(2), 25).slot.id
        api.cancelFocusSlot(auth.access_token, group.id, slotId)

        val listResponse = api.listFocusSlots(auth.access_token, group.id)

        assertThat(listResponse.slots.none { it.id == slotId }).isTrue()
    }

    // =========================================================================
    // DELETE /api/groups/:groupId/slots/:id (cancel)
    // =========================================================================

    @Test
    fun `cancelFocusSlot by creator returns 204`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val slotId = api.createFocusSlot(auth.access_token, group.id, futureTimestamp(2), 25).slot.id

        // Should not throw
        api.cancelFocusSlot(auth.access_token, group.id, slotId)

        // Slot should not appear in list
        val listResponse = api.listFocusSlots(auth.access_token, group.id)
        assertThat(listResponse.slots.none { it.id == slotId }).isTrue()
    }

    @Test
    fun `cancelFocusSlot by non-creator returns 403 FORBIDDEN`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val slotId = api.createFocusSlot(auth.access_token, group.id, futureTimestamp(2), 25).slot.id
        val (memberToken, _) = addMemberToGroup(auth.access_token, group.id)

        var exception: ApiException? = null
        try {
            api.cancelFocusSlot(memberToken, group.id, slotId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("FORBIDDEN")
    }

    // =========================================================================
    // POST /api/groups/:groupId/slots/:id/rsvp
    // =========================================================================

    @Test
    fun `rsvpFocusSlot returns 201 with rsvp object`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val slotId = api.createFocusSlot(auth.access_token, group.id, futureTimestamp(2), 25).slot.id
        val (memberToken, memberId) = addMemberToGroup(auth.access_token, group.id)

        val response = api.rsvpFocusSlot(memberToken, group.id, slotId)

        assertThat(response.rsvp).isNotNull()
        assertThat(response.rsvp.slot_id).isEqualTo(slotId)
        assertThat(response.rsvp.user_id).isEqualTo(memberId)
    }

    @Test
    fun `duplicate rsvpFocusSlot returns 409 ALREADY_RSVPED`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val slotId = api.createFocusSlot(auth.access_token, group.id, futureTimestamp(2), 25).slot.id
        api.rsvpFocusSlot(auth.access_token, group.id, slotId)

        var exception: ApiException? = null
        try {
            api.rsvpFocusSlot(auth.access_token, group.id, slotId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("ALREADY_RSVPED")
    }

    @Test
    fun `rsvpFocusSlot to cancelled slot returns 422 SLOT_CANCELLED`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val slotId = api.createFocusSlot(auth.access_token, group.id, futureTimestamp(2), 25).slot.id
        api.cancelFocusSlot(auth.access_token, group.id, slotId)

        var exception: ApiException? = null
        try {
            api.rsvpFocusSlot(auth.access_token, group.id, slotId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(422)
        assertThat(exception.errorCode).isEqualTo("SLOT_CANCELLED")
    }

    // =========================================================================
    // DELETE /api/groups/:groupId/slots/:id/rsvp
    // =========================================================================

    @Test
    fun `unrsvpFocusSlot returns 204 and removes rsvp`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val slotId = api.createFocusSlot(auth.access_token, group.id, futureTimestamp(2), 25).slot.id
        api.rsvpFocusSlot(auth.access_token, group.id, slotId)

        // Should not throw
        api.unrsvpFocusSlot(auth.access_token, group.id, slotId)

        val listResponse = api.listFocusSlots(auth.access_token, group.id)
        val slot = listResponse.slots.firstOrNull { it.id == slotId }
        assertThat(slot?.rsvp_count).isEqualTo(0)
        assertThat(slot?.user_rsvped).isFalse()
    }

    @Test
    fun `unrsvpFocusSlot when not RSVPed is idempotent (204)`() = runTest {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val slotId = api.createFocusSlot(auth.access_token, group.id, futureTimestamp(2), 25).slot.id

        // Should not throw even though we never RSVPed
        api.unrsvpFocusSlot(auth.access_token, group.id, slotId)
    }

    // =========================================================================
    // GET /api/me/slots
    // =========================================================================

    @Test
    fun `getMySlots returns slots across multiple groups`() = runTest {
        val auth = getOrCreateSharedUser()
        val group1 = getOrCreateSharedGroup()

        // Create group2 owned by a fresh user, then add the shared user as a member
        val freshHost = testDataHelper.createTestUser(api, displayName = "Group2 Host")
        trackUser(freshHost.user!!.id)
        val group2 = testDataHelper.createTestGroup(api, freshHost.access_token,
            name = "My Slots Group 2 ${System.currentTimeMillis()}")
        trackGroup(group2.id)
        val invite2 = api.getGroupInviteCode(freshHost.access_token, group2.id)
        api.joinGroup(auth.access_token, invite2.invite_code)
        api.approveMember(freshHost.access_token, group2.id, auth.user!!.id)

        api.createFocusSlot(auth.access_token, group1.id, futureTimestamp(2), 25)
        api.createFocusSlot(auth.access_token, group2.id, futureTimestamp(3), 45)

        val response = api.getMySlots(auth.access_token)

        // We may have slots from other tests too; verify at least these two groups are present
        val slotGroupIds = response.slots.map { it.group_id }.toSet()
        assertThat(slotGroupIds).containsAtLeast(group1.id, group2.id)
        // Slots include group metadata
        val firstSlot = response.slots.first { it.group_id == group1.id || it.group_id == group2.id }
        assertThat(firstSlot.group_name).isNotNull()
    }

    @Test
    fun `getMySlots does not include slots from groups user is not a member of`() = runTest {
        val auth = getOrCreateSharedUser()
        // Create another user with their own group and slot
        val other = testDataHelper.createTestUser(api, displayName = "Other Slot User")
        trackUser(other.user!!.id)
        val otherGroup = testDataHelper.createTestGroup(api, other.access_token)
        trackGroup(otherGroup.id)
        api.createFocusSlot(other.access_token, otherGroup.id, futureTimestamp(2), 25)

        val response = api.getMySlots(auth.access_token)

        assertThat(response.slots.none { it.group_id == otherGroup.id }).isTrue()
    }
}
