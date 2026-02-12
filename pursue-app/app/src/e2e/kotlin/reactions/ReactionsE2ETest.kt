package com.github.shannonbay.pursue.e2e.reactions

import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for activity reactions endpoints.
 *
 * Tests PUT/DELETE/GET /api/activities/:activity_id/reactions
 * and embedded reactions in GET /api/groups/:group_id/activity.
 */
class ReactionsE2ETest : E2ETest() {

    /**
     * Helper to create an activity by logging progress.
     * Returns the activity ID from the activity feed.
     */
    private suspend fun createTestActivity(accessToken: String, groupId: String): String {
        // Create a goal and log progress to generate a progress_logged activity
        val goal = testDataHelper.createTestGoal(
            api = api,
            accessToken = accessToken,
            groupId = groupId,
            title = "Reaction Test Goal ${System.currentTimeMillis()}",
            cadence = "daily",
            metricType = "binary"
        )

        // Log progress to create an activity
        api.logProgress(
            accessToken = accessToken,
            goalId = goal.id,
            value = 1.0,
            note = "E2E reaction test",
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        // Fetch activity feed to get the activity ID
        val activityResponse = api.getGroupActivity(accessToken, groupId, limit = 10)
        val progressActivity = activityResponse.activities.find { it.activity_type == "progress_logged" }
        assertThat(progressActivity).isNotNull()
        assertThat(progressActivity!!.id).isNotNull()
        return progressActivity.id!!
    }

    // === PUT /api/activities/:activity_id/reactions ===

    @Test
    fun `addReaction succeeds and returns reaction object`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Act
        val response = api.addOrReplaceReaction(auth.access_token, activityId, "🔥")

        // Assert
        assertThat(response.reaction).isNotNull()
        assertThat(response.reaction.activity_id).isEqualTo(activityId)
        assertThat(response.reaction.user_id).isEqualTo(auth.user!!.id)
        assertThat(response.reaction.emoji).isEqualTo("🔥")
        assertThat(response.reaction.created_at).isNotEmpty()
        assertThat(response.replaced).isFalse()
    }

    @Test
    fun `replaceReaction returns replaced true`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Add initial reaction
        api.addOrReplaceReaction(auth.access_token, activityId, "🔥")

        // Act - Replace with different emoji
        val response = api.addOrReplaceReaction(auth.access_token, activityId, "💪")

        // Assert
        assertThat(response.replaced).isTrue()
        assertThat(response.reaction.emoji).isEqualTo("💪")
    }

    @Test
    fun `addReaction with same emoji returns replaced false`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Add initial reaction
        api.addOrReplaceReaction(auth.access_token, activityId, "🔥")

        // Act - Add same emoji again
        val response = api.addOrReplaceReaction(auth.access_token, activityId, "🔥")

        // Assert - Not replaced since it's the same
        assertThat(response.replaced).isFalse()
        assertThat(response.reaction.emoji).isEqualTo("🔥")
    }

    @Test
    fun `addReaction with invalid emoji returns 400`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Act
        var exception: ApiException? = null
        try {
            api.addOrReplaceReaction(auth.access_token, activityId, "🍕") // Not in allowed list
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
    }

    @Test
    fun `addReaction with non-existent activity returns 404`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val invalidActivityId = "00000000-0000-0000-0000-000000000000"

        // Act
        var exception: ApiException? = null
        try {
            api.addOrReplaceReaction(auth.access_token, invalidActivityId, "🔥")
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    @Test
    fun `addReaction as non-member returns 403`() = runTest {
        // Arrange - User A creates activity, User B is not a member
        val authA = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(authA.access_token, group.id)

        val authB = testDataHelper.createTestUser(api, displayName = "Non-Member")
        trackUser(authB.user!!.id)

        // Act
        var exception: ApiException? = null
        try {
            api.addOrReplaceReaction(authB.access_token, activityId, "🔥")
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }

    // === DELETE /api/activities/:activity_id/reactions ===

    @Test
    fun `removeReaction succeeds`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Add a reaction first
        api.addOrReplaceReaction(auth.access_token, activityId, "🔥")

        // Act - Remove the reaction (should succeed with no exception)
        api.removeReaction(auth.access_token, activityId)

        // Assert - Verify reaction is removed by checking reactions list
        val reactions = api.getReactions(auth.access_token, activityId)
        val userReaction = reactions.reactions.find { it.user.id == auth.user!!.id }
        assertThat(userReaction).isNull()
    }

    @Test
    fun `removeReaction when no reaction exists returns 404`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Don't add any reaction

        // Act
        var exception: ApiException? = null
        try {
            api.removeReaction(auth.access_token, activityId)
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    @Test
    fun `removeReaction as non-member returns 403`() = runTest {
        // Arrange - User A creates activity, User B is not a member
        val authA = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(authA.access_token, group.id)

        val authB = testDataHelper.createTestUser(api, displayName = "Non-Member Remove")
        trackUser(authB.user!!.id)

        // Act
        var exception: ApiException? = null
        try {
            api.removeReaction(authB.access_token, activityId)
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }

    // === GET /api/activities/:activity_id/reactions ===

    @Test
    fun `getReactions returns full reactor list`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Add a reaction
        api.addOrReplaceReaction(auth.access_token, activityId, "🔥")

        // Act
        val response = api.getReactions(auth.access_token, activityId)

        // Assert
        assertThat(response.activity_id).isEqualTo(activityId)
        assertThat(response.reactions).isNotEmpty()
        assertThat(response.total).isAtLeast(1)
    }

    @Test
    fun `getReactions includes user info`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Add a reaction
        api.addOrReplaceReaction(auth.access_token, activityId, "💪")

        // Act
        val response = api.getReactions(auth.access_token, activityId)

        // Assert
        assertThat(response.reactions).isNotEmpty()
        val reaction = response.reactions.first()
        assertThat(reaction.emoji).isEqualTo("💪")
        assertThat(reaction.user).isNotNull()
        assertThat(reaction.user.id).isEqualTo(auth.user!!.id)
        assertThat(reaction.user.display_name).isNotEmpty()
        assertThat(reaction.created_at).isNotEmpty()
    }

    @Test
    fun `getReactions as non-member returns 403`() = runTest {
        // Arrange - User A creates activity, User B is not a member
        val authA = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(authA.access_token, group.id)

        val authB = testDataHelper.createTestUser(api, displayName = "Non-Member Get")
        trackUser(authB.user!!.id)

        // Act
        var exception: ApiException? = null
        try {
            api.getReactions(authB.access_token, activityId)
        } catch (e: ApiException) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }

    // === GET /api/groups/:group_id/activity (embedded reactions) ===

    @Test
    fun `activity feed includes reactions array`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Add a reaction
        api.addOrReplaceReaction(auth.access_token, activityId, "🔥")

        // Act - Fetch activity feed
        val activityResponse = api.getGroupActivity(auth.access_token, group.id, limit = 10)

        // Assert
        val activity = activityResponse.activities.find { it.id == activityId }
        assertThat(activity).isNotNull()
        assertThat(activity!!.reactions).isNotNull()
        assertThat(activity.reactions).isNotEmpty()

        val fireReaction = activity.reactions!!.find { it.emoji == "🔥" }
        assertThat(fireReaction).isNotNull()
        assertThat(fireReaction!!.count).isAtLeast(1)
        assertThat(fireReaction.reactor_ids).contains(auth.user!!.id)
    }

    @Test
    fun `activity feed includes reaction_summary`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Add a reaction
        api.addOrReplaceReaction(auth.access_token, activityId, "❤️")

        // Act - Fetch activity feed
        val activityResponse = api.getGroupActivity(auth.access_token, group.id, limit = 10)

        // Assert
        val activity = activityResponse.activities.find { it.id == activityId }
        assertThat(activity).isNotNull()
        assertThat(activity!!.reaction_summary).isNotNull()
        assertThat(activity.reaction_summary!!.total_count).isAtLeast(1)
        assertThat(activity.reaction_summary!!.top_reactors).isNotEmpty()
    }

    @Test
    fun `current_user_reacted is true after adding reaction`() = runTest {
        // Arrange
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()
        val activityId = createTestActivity(auth.access_token, group.id)

        // Add a reaction
        api.addOrReplaceReaction(auth.access_token, activityId, "👏")

        // Act - Fetch activity feed
        val activityResponse = api.getGroupActivity(auth.access_token, group.id, limit = 10)

        // Assert
        val activity = activityResponse.activities.find { it.id == activityId }
        assertThat(activity).isNotNull()
        assertThat(activity!!.reactions).isNotNull()

        val clapReaction = activity.reactions!!.find { it.emoji == "👏" }
        assertThat(clapReaction).isNotNull()
        assertThat(clapReaction!!.current_user_reacted).isTrue()
    }
}
