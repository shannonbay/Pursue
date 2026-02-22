package com.github.shannonbay.pursue.e2e.discover

import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for public group discovery and join request lifecycle.
 *
 * Tests cover:
 * - GET /api/discover/groups (list public groups, filter, search, paginate)
 * - GET /api/discover/groups/:id (expanded group detail)
 * - POST /api/groups/:id/join-requests (submit join request)
 * - GET /api/groups/:id/join-requests (admin list pending requests)
 * - PATCH /api/groups/:id/join-requests/:reqId (approve / decline)
 * - GET /api/discover/suggestions (stub — always empty)
 * - DELETE /api/discover/suggestions/:id (dismiss suggestion)
 */
class DiscoverE2ETest : E2ETest() {

    companion object {
        // Cached across tests in this class — not cleaned up (shared state pattern)
        data class SharedGroupState(
            val groupId: String,
            val creatorToken: String,
            val creatorUserId: String
        )
        private var sharedGroupState: SharedGroupState? = null
    }

    /**
     * Lazily creates a shared public group with a goal. The creator is a premium shared user.
     * Cached for the lifetime of the test class run.
     */
    private suspend fun getOrCreateSharedPublicGroup(): SharedGroupState {
        sharedGroupState?.let { return it }
        val creator = getOrCreateSharedUser()
        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "E2E Discover Fitness Group",
            description = "A public group for discover E2E tests",
            category = "fitness",
            visibility = "public"
        )
        // Add a goal so the detail endpoint returns non-empty goals list
        api.createGoal(
            accessToken = creator.access_token,
            groupId = group.id,
            title = "Morning Run",
            cadence = "daily"
        )
        val state = SharedGroupState(
            groupId = group.id,
            creatorToken = creator.access_token,
            creatorUserId = creator.user!!.id
        )
        sharedGroupState = state
        return state
    }

    // ─── GET /api/discover/groups ───────────────────────────────────────────

    @Test
    fun `list public groups returns only public non-deleted groups`() = runTest {
        val shared = getOrCreateSharedPublicGroup()

        // Create a private group (should NOT appear)
        val privateGroup = api.createGroup(
            accessToken = shared.creatorToken,
            name = "Private Group ${System.currentTimeMillis()}",
            visibility = "private"
        )
        trackGroup(privateGroup.id)

        val response = api.listPublicGroups()

        assertThat(response.groups).isNotEmpty()
        val ids = response.groups.map { it.id }
        assertThat(ids).contains(shared.groupId)
        assertThat(ids).doesNotContain(privateGroup.id)
    }

    @Test
    fun `list public groups returns correct shape fields`() = runTest {
        getOrCreateSharedPublicGroup()

        val response = api.listPublicGroups()

        assertThat(response.groups).isNotEmpty()
        val group = response.groups.first()
        // All required fields present
        assertThat(group.id).isNotEmpty()
        assertThat(group.name).isNotEmpty()
        assertThat(group.member_count).isAtLeast(0)
        assertThat(group.goal_count).isAtLeast(0)
        assertThat(group.heat_tier_name).isNotEmpty()
        assertThat(response.has_more).isFalse()
    }

    @Test
    fun `list public groups supports sort by heat`() = runTest {
        getOrCreateSharedPublicGroup()

        val response = api.listPublicGroups(sort = "heat")

        assertThat(response.groups).isNotEmpty()
        // Verify heat scores are in descending order
        val scores = response.groups.map { it.heat_score }
        assertThat(scores).isInOrder(Comparator.reverseOrder<Int>())
    }

    @Test
    fun `list public groups supports sort by newest`() = runTest {
        getOrCreateSharedPublicGroup()

        val response = api.listPublicGroups(sort = "newest")

        assertThat(response.groups).isNotEmpty()
        val dates = response.groups.map { it.created_at }
        assertThat(dates).isInOrder(Comparator.reverseOrder<String>())
    }

    @Test
    fun `list public groups filters by category`() = runTest {
        getOrCreateSharedPublicGroup()  // category = "fitness"

        // Create a group with a different category
        val creator = getOrCreateSharedUser()
        val learningGroup = api.createGroup(
            accessToken = creator.access_token,
            name = "Learning Group ${System.currentTimeMillis()}",
            visibility = "public",
            category = "learning"
        )
        trackGroup(learningGroup.id)

        val fitnessResponse = api.listPublicGroups(categories = "fitness")
        val fitnessIds = fitnessResponse.groups.map { it.id }
        assertThat(fitnessIds).contains(getOrCreateSharedPublicGroup().groupId)
        assertThat(fitnessIds).doesNotContain(learningGroup.id)

        val learningResponse = api.listPublicGroups(categories = "learning")
        val learningIds = learningResponse.groups.map { it.id }
        assertThat(learningIds).contains(learningGroup.id)
    }

    @Test
    fun `list public groups searches by name`() = runTest {
        val shared = getOrCreateSharedPublicGroup()

        // Partial match on group name
        val response = api.listPublicGroups(q = "E2E Discover Fitness")

        assertThat(response.groups).isNotEmpty()
        assertThat(response.groups.map { it.id }).contains(shared.groupId)
    }

    @Test
    fun `list public groups supports cursor pagination`() = runTest {
        getOrCreateSharedPublicGroup()

        // Page 1: limit=1
        val page1 = api.listPublicGroups(sort = "newest", limit = 1)
        assertThat(page1.groups).hasSize(1)

        // If there's only one group, pagination may not be needed
        if (page1.has_more) {
            assertThat(page1.next_cursor).isNotNull()

            // Page 2: use cursor
            val page2 = api.listPublicGroups(sort = "newest", limit = 1, cursor = page1.next_cursor)
            assertThat(page2.groups).hasSize(1)
            // No overlap
            assertThat(page2.groups.first().id).isNotEqualTo(page1.groups.first().id)
        }
    }

    // ─── GET /api/discover/groups/:id ───────────────────────────────────────

    @Test
    fun `get public group returns expanded detail with goals`() = runTest {
        val shared = getOrCreateSharedPublicGroup()

        val detail = api.getPublicGroup(shared.groupId)

        assertThat(detail.id).isEqualTo(shared.groupId)
        assertThat(detail.name).isEqualTo("E2E Discover Fitness Group")
        assertThat(detail.category).isEqualTo("fitness")
        assertThat(detail.member_count).isAtLeast(1)
        assertThat(detail.goals).isNotEmpty()
        assertThat(detail.goals.first().title).isEqualTo("Morning Run")
        assertThat(detail.goals.first().cadence).isEqualTo("daily")
        assertThat(detail.heat_tier_name).isNotEmpty()
    }

    @Test
    fun `get public group returns 404 for private group`() = runTest {
        val creator = getOrCreateSharedUser()
        val privateGroup = api.createGroup(
            accessToken = creator.access_token,
            name = "Private Only ${System.currentTimeMillis()}",
            visibility = "private"
        )
        trackGroup(privateGroup.id)

        var exception: ApiException? = null
        try {
            api.getPublicGroup(privateGroup.id)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    @Test
    fun `get public group returns 404 for non-existent id`() = runTest {
        var exception: ApiException? = null
        try {
            api.getPublicGroup("00000000-0000-0000-0000-000000000000")
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    // ─── POST /api/groups/:id/join-requests ─────────────────────────────────

    @Test
    fun `submit join request creates pending request`() = runTest {
        val shared = getOrCreateSharedPublicGroup()

        val requester = testDataHelper.createTestUser(api, displayName = "Requester ${System.currentTimeMillis()}")
        trackUser(requester.user!!.id)

        val response = api.submitJoinRequest(
            accessToken = requester.access_token,
            groupId = shared.groupId,
            note = "Please let me in!"
        )

        assertThat(response.id).isNotEmpty()
        assertThat(response.group_id).isEqualTo(shared.groupId)
        assertThat(response.status).isEqualTo("pending")
        assertThat(response.note).isEqualTo("Please let me in!")
        assertThat(response.auto_approved).isFalse()
        assertThat(response.created_at).isNotEmpty()
    }

    @Test
    fun `submit join request without note succeeds`() = runTest {
        val shared = getOrCreateSharedPublicGroup()

        val requester = testDataHelper.createTestUser(api, displayName = "No-Note Requester")
        trackUser(requester.user!!.id)

        val response = api.submitJoinRequest(
            accessToken = requester.access_token,
            groupId = shared.groupId
        )

        assertThat(response.status).isEqualTo("pending")
        assertThat(response.note).isNull()
    }

    @Test
    fun `submit join request returns 409 ALREADY_REQUESTED if request already pending`() = runTest {
        val shared = getOrCreateSharedPublicGroup()

        val requester = testDataHelper.createTestUser(api, displayName = "Double Requester")
        trackUser(requester.user!!.id)

        // First request
        api.submitJoinRequest(accessToken = requester.access_token, groupId = shared.groupId)

        // Second request should fail
        var exception: ApiException? = null
        try {
            api.submitJoinRequest(accessToken = requester.access_token, groupId = shared.groupId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("ALREADY_REQUESTED")
    }

    @Test
    fun `submit join request returns 409 ALREADY_MEMBER for group creator`() = runTest {
        val shared = getOrCreateSharedPublicGroup()

        // Creator tries to join their own group
        var exception: ApiException? = null
        try {
            api.submitJoinRequest(
                accessToken = shared.creatorToken,
                groupId = shared.groupId
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("ALREADY_MEMBER")
    }

    @Test
    fun `submit join request returns 403 FORBIDDEN for private group`() = runTest {
        val creator = getOrCreateSharedUser()
        val privateGroup = api.createGroup(
            accessToken = creator.access_token,
            name = "Private JR Test ${System.currentTimeMillis()}",
            visibility = "private"
        )
        trackGroup(privateGroup.id)

        val requester = testDataHelper.createTestUser(api, displayName = "Private Requester")
        trackUser(requester.user!!.id)

        var exception: ApiException? = null
        try {
            api.submitJoinRequest(accessToken = requester.access_token, groupId = privateGroup.id)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
        assertThat(exception.errorCode).isEqualTo("FORBIDDEN")
    }

    @Test
    fun `submit join request returns 401 without auth`() = runTest {
        val shared = getOrCreateSharedPublicGroup()
        SecureTokenManager.getInstance(context).clearTokens()

        var exception: ApiException? = null
        try {
            api.submitJoinRequest(accessToken = "", groupId = shared.groupId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(401)
    }

    // ─── GET /api/groups/:id/join-requests ──────────────────────────────────

    @Test
    fun `list join requests shows pending requests to admin`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "JR List Creator")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "JR Admin Test ${System.currentTimeMillis()}",
            visibility = "public",
            category = "fitness"
        )
        trackGroup(group.id)

        val requester = testDataHelper.createTestUser(api, displayName = "JR Requester")
        trackUser(requester.user!!.id)

        api.submitJoinRequest(
            accessToken = requester.access_token,
            groupId = group.id,
            note = "Hi there"
        )

        val list = api.listJoinRequests(
            accessToken = creator.access_token,
            groupId = group.id
        )

        assertThat(list.requests).hasSize(1)
        val req = list.requests.first()
        assertThat(req.user_id).isEqualTo(requester.user!!.id)
        assertThat(req.display_name).isNotEmpty()
        assertThat(req.note).isEqualTo("Hi there")
        assertThat(req.created_at).isNotEmpty()
    }

    @Test
    fun `list join requests returns 403 for non-admin`() = runTest {
        val shared = getOrCreateSharedPublicGroup()

        val nonAdmin = testDataHelper.createTestUser(api, displayName = "Non-Admin")
        trackUser(nonAdmin.user!!.id)

        var exception: ApiException? = null
        try {
            api.listJoinRequests(accessToken = nonAdmin.access_token, groupId = shared.groupId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }

    // ─── PATCH /api/groups/:id/join-requests/:reqId ──────────────────────────

    @Test
    fun `approve join request makes user an active member`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Approve Creator")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "Approve Test ${System.currentTimeMillis()}",
            visibility = "public",
            category = "fitness"
        )
        trackGroup(group.id)

        val requester = testDataHelper.createTestUser(api, displayName = "Approve Requester")
        trackUser(requester.user!!.id)

        val joinReq = api.submitJoinRequest(
            accessToken = requester.access_token,
            groupId = group.id
        )

        val reviewed = api.reviewJoinRequest(
            accessToken = creator.access_token,
            groupId = group.id,
            requestId = joinReq.id,
            status = "approved"
        )

        assertThat(reviewed.id).isEqualTo(joinReq.id)
        assertThat(reviewed.status).isEqualTo("approved")
        assertThat(reviewed.reviewed_at).isNotEmpty()

        // Verify the requester is now a group member
        val members = api.getGroupMembers(creator.access_token, group.id)
        val memberIds = members.members.map { it.user_id }
        assertThat(memberIds).contains(requester.user!!.id)
    }

    @Test
    fun `decline join request marks request as declined`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Decline Creator")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "Decline Test ${System.currentTimeMillis()}",
            visibility = "public",
            category = "fitness"
        )
        trackGroup(group.id)

        val requester = testDataHelper.createTestUser(api, displayName = "Decline Requester")
        trackUser(requester.user!!.id)

        val joinReq = api.submitJoinRequest(
            accessToken = requester.access_token,
            groupId = group.id
        )

        val reviewed = api.reviewJoinRequest(
            accessToken = creator.access_token,
            groupId = group.id,
            requestId = joinReq.id,
            status = "declined"
        )

        assertThat(reviewed.status).isEqualTo("declined")
        assertThat(reviewed.reviewed_at).isNotEmpty()

        // Verify requester is NOT a member
        val members = api.getGroupMembers(creator.access_token, group.id)
        val memberIds = members.members.map { it.user_id }
        assertThat(memberIds).doesNotContain(requester.user!!.id)
    }

    @Test
    fun `review already-reviewed request returns 409 ALREADY_REVIEWED`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Already Reviewed Creator")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "Already Reviewed Test ${System.currentTimeMillis()}",
            visibility = "public",
            category = "fitness"
        )
        trackGroup(group.id)

        val requester = testDataHelper.createTestUser(api, displayName = "Already Reviewed Requester")
        trackUser(requester.user!!.id)

        val joinReq = api.submitJoinRequest(
            accessToken = requester.access_token,
            groupId = group.id
        )

        // Approve once
        api.reviewJoinRequest(
            accessToken = creator.access_token,
            groupId = group.id,
            requestId = joinReq.id,
            status = "approved"
        )

        // Try to review again
        var exception: ApiException? = null
        try {
            api.reviewJoinRequest(
                accessToken = creator.access_token,
                groupId = group.id,
                requestId = joinReq.id,
                status = "declined"
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("ALREADY_REVIEWED")
    }

    @Test
    fun `review join request returns 403 for non-admin`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Review 403 Creator")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "Review 403 Test ${System.currentTimeMillis()}",
            visibility = "public",
            category = "fitness"
        )
        trackGroup(group.id)

        val requester = testDataHelper.createTestUser(api, displayName = "Review 403 Requester")
        trackUser(requester.user!!.id)

        val joinReq = api.submitJoinRequest(
            accessToken = requester.access_token,
            groupId = group.id
        )

        val nonAdmin = testDataHelper.createTestUser(api, displayName = "Review 403 Non-Admin")
        trackUser(nonAdmin.user!!.id)

        var exception: ApiException? = null
        try {
            api.reviewJoinRequest(
                accessToken = nonAdmin.access_token,
                groupId = group.id,
                requestId = joinReq.id,
                status = "approved"
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }

    // ─── GET /api/discover/suggestions ──────────────────────────────────────

    @Test
    fun `get suggestions returns empty list (pgvector deferred)`() = runTest {
        val user = getOrCreateSharedUser()

        val response = api.getSuggestions(user.access_token)

        assertThat(response.suggestions).isEmpty()
    }

    @Test
    fun `get suggestions requires authentication`() = runTest {
        SecureTokenManager.getInstance(context).clearTokens()

        var exception: ApiException? = null
        try {
            api.getSuggestions(accessToken = "")
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(401)
    }

    // ─── DELETE /api/discover/suggestions/:id ───────────────────────────────

    @Test
    fun `dismiss suggestion returns 204 for existing group`() = runTest {
        val user = getOrCreateSharedUser()
        val shared = getOrCreateSharedPublicGroup()

        // Should not throw
        api.dismissSuggestion(accessToken = user.access_token, groupId = shared.groupId)
        // Idempotent — dismiss again
        api.dismissSuggestion(accessToken = user.access_token, groupId = shared.groupId)
    }

    @Test
    fun `dismiss suggestion returns 404 for non-existent group`() = runTest {
        val user = getOrCreateSharedUser()

        var exception: ApiException? = null
        try {
            api.dismissSuggestion(
                accessToken = user.access_token,
                groupId = "00000000-0000-0000-0000-000000000000"
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    @Test
    fun `dismiss suggestion requires authentication`() = runTest {
        val shared = getOrCreateSharedPublicGroup()
        SecureTokenManager.getInstance(context).clearTokens()

        var exception: ApiException? = null
        try {
            api.dismissSuggestion(accessToken = "", groupId = shared.groupId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(401)
    }

    // ─── Group visibility via patchGroup ────────────────────────────────────

    @Test
    fun `patch group visibility to public makes it discoverable`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Visibility Patcher")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        // Create private group
        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "Visibility Toggle ${System.currentTimeMillis()}",
            visibility = "private",
            category = "fitness"
        )
        trackGroup(group.id)

        // Should NOT appear in discover list
        val beforePatch = api.listPublicGroups(q = group.name)
        assertThat(beforePatch.groups.map { it.id }).doesNotContain(group.id)

        // Patch to public
        api.patchGroup(
            accessToken = creator.access_token,
            groupId = group.id,
            visibility = "public",
            category = "fitness"
        )

        // Now SHOULD appear
        val afterPatch = api.listPublicGroups(q = group.name)
        assertThat(afterPatch.groups.map { it.id }).contains(group.id)
    }

    @Test
    fun `patch group with spot_limit reflects in discover listing`() = runTest {
        val creator = testDataHelper.createTestUser(api, displayName = "Spot Limit Setter")
        trackUser(creator.user!!.id)
        testDataHelper.upgradeToPremium(api, creator.access_token)

        val group = api.createGroup(
            accessToken = creator.access_token,
            name = "Spot Limit Group ${System.currentTimeMillis()}",
            visibility = "public",
            category = "fitness"
        )
        trackGroup(group.id)

        // Set spot_limit = 5
        val patched = api.patchGroup(
            accessToken = creator.access_token,
            groupId = group.id,
            spotLimit = 5
        )

        assertThat(patched.spot_limit).isEqualTo(5)

        // Verify reflected in discover detail
        val detail = api.getPublicGroup(group.id)
        assertThat(detail.spot_limit).isEqualTo(5)
        assertThat(detail.spots_left).isAtLeast(0)
    }
}
