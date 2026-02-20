package com.github.shannonbay.pursue.e2e.groups

import com.google.common.truth.Truth.assertThat
import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for invite code endpoints (specs/Pursue-Invite-Code-Spec.md).
 *
 * Tests verify:
 * - GET /api/groups/:id/invite - member can fetch invite code
 * - POST /api/groups/:id/invite/regenerate - admin can regenerate
 * - POST /api/groups/join - user joins via invite code (pending status)
 * - Full lifecycle: create invite, verify join works, regenerate, old code 404, new code works
 */
class InviteCodeE2ETest : E2ETest() {

    @Test
    fun `get invite code returns code and share_url for group member`() = runTest {
        val authResponse = getOrCreateSharedUser()
        val group = api.createGroup(
            accessToken = authResponse.access_token,
            name = "Invite Test Group ${System.currentTimeMillis()}"
        )
        trackGroup(group.id)

        val response = api.getGroupInviteCode(authResponse.access_token, group.id)

        assertThat(response.invite_code).isNotEmpty()
        assertThat(response.invite_code).startsWith("PURSUE-")
        assertThat(response.share_url).isNotEmpty()
        assertThat(response.share_url).contains(response.invite_code)
        assertThat(response.created_at).isNotEmpty()
    }

    @Test
    fun `regenerate invite code revokes old and returns new`() = runTest {
        val authResponse = getOrCreateSharedUser()
        val group = api.createGroup(
            accessToken = authResponse.access_token,
            name = "Regen Test Group ${System.currentTimeMillis()}"
        )
        trackGroup(group.id)

        val original = api.getGroupInviteCode(authResponse.access_token, group.id)
        val regenerated = api.regenerateInviteCode(authResponse.access_token, group.id)

        assertThat(regenerated.invite_code).isNotEqualTo(original.invite_code)
        assertThat(regenerated.share_url).contains(regenerated.invite_code)
        assertThat(regenerated.previous_code_revoked).isEqualTo(original.invite_code)
    }

    @Test
    fun `join group via invite code returns pending status`() = runTest {
        val creator = getOrCreateSharedUser()
        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "Join Test Group ${System.currentTimeMillis()}"
        )
        trackGroup(group.id)

        val invite = api.getGroupInviteCode(creator.access_token, group.id)

        val joiner = testDataHelper.createTestUser(api, displayName = "Joiner User")
        trackUser(joiner.user!!.id)

        val joinResponse = api.joinGroup(joiner.access_token, invite.invite_code)

        assertThat(joinResponse.status).isEqualTo("pending")
        assertThat(joinResponse.message).isNotEmpty()
        assertThat(joinResponse.group.id).isEqualTo(group.id)
        assertThat(joinResponse.group.name).isEqualTo(group.name)
        assertThat(joinResponse.group.member_count).isAtLeast(1)
    }

    @Test
    fun `join group with invalid code fails with 404`() = runTest {
        val authResponse = getOrCreateSharedUser()

        var exception: Exception? = null
        try {
            api.joinGroup(authResponse.access_token, "PURSUE-INVALID-CODE")
        } catch (e: Exception) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        assertThat((exception as ApiException).code).isEqualTo(404)
    }

    @Test
    fun `invite lifecycle create verify regenerate old code fails new code works`() = runTest {
        // 1. Create invite: creator creates group, fetch invite
        val creator = getOrCreateSharedUser()
        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "Lifecycle Test Group ${System.currentTimeMillis()}"
        )
        trackGroup(group.id)

        val original = api.getGroupInviteCode(creator.access_token, group.id)
        val originalCode = original.invite_code

        // 2. Verify invite works: joiner A joins with original code
        val joinerA = testDataHelper.createTestUser(api, displayName = "Joiner A")
        trackUser(joinerA.user!!.id)

        val firstJoin = api.joinGroup(joinerA.access_token, originalCode)
        assertThat(firstJoin.status).isEqualTo("pending")
        assertThat(firstJoin.group.id).isEqualTo(group.id)

        // 3. Regenerate: creator regenerates, store new code
        val regenerated = api.regenerateInviteCode(creator.access_token, group.id)
        assertThat(regenerated.previous_code_revoked).isEqualTo(originalCode)
        val newCode = regenerated.invite_code

        // 4. Verify old code no longer works: joiner B tries revoked code → 404
        val joinerB = testDataHelper.createTestUser(api, displayName = "Joiner B")
        trackUser(joinerB.user!!.id)

        var joinOldException: Exception? = null
        try {
            api.joinGroup(joinerB.access_token, originalCode)
        } catch (e: Exception) {
            joinOldException = e
        }
        assertThat(joinOldException).isNotNull()
        assertThat(joinOldException).isInstanceOf(ApiException::class.java)
        assertThat((joinOldException as ApiException).code).isEqualTo(404)

        // 5. Verify new code works: joiner B joins with new code → pending
        val secondJoin = api.joinGroup(joinerB.access_token, newCode)
        assertThat(secondJoin.status).isEqualTo("pending")
        assertThat(secondJoin.group.id).isEqualTo(group.id)
    }
}
