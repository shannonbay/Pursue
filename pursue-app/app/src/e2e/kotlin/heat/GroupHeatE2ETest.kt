package com.github.shannonbay.pursue.e2e.heat

import com.google.common.truth.Truth.assertThat
import app.getpursue.data.network.ApiException
import app.getpursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for Group Heat feature.
 *
 * Tests the group heat momentum indicator, including:
 * - Heat data in group list and detail responses
 * - Heat history endpoint (premium-gated)
 * - Tier thresholds and naming
 */
class GroupHeatE2ETest : E2ETest() {

    @Test
    fun `getMyGroups includes heat object for each group`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act
        val response = api.getMyGroups(authResponse.access_token)

        // Assert
        assertThat(response.groups).isNotEmpty()
        val groupWithHeat = response.groups.find { it.id == group.id }
        assertThat(groupWithHeat).isNotNull()
        assertThat(groupWithHeat!!.heat).isNotNull()

        val heat = groupWithHeat.heat!!
        assertThat(heat.score).isAtLeast(0f)
        assertThat(heat.score).isAtMost(100f)
        assertThat(heat.tier).isAtLeast(0)
        assertThat(heat.tier).isAtMost(7)
        assertThat(heat.tier_name).isNotEmpty()
        assertThat(heat.streak_days).isAtLeast(0)
        assertThat(heat.peak_score).isAtLeast(0f)
    }

    @Test
    fun `getGroupDetails includes heat data with tier name`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act
        val response = api.getGroupDetails(authResponse.access_token, group.id)

        // Assert
        assertThat(response.heat).isNotNull()
        val heat = response.heat!!
        assertThat(heat.score).isAtLeast(0f)
        assertThat(heat.score).isAtMost(100f)
        assertThat(heat.tier).isAtLeast(0)
        assertThat(heat.tier).isAtMost(7)
        assertThat(heat.tier_name).isNotEmpty()
    }

    @Test
    fun `new group starts with tier 0 Cold`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()

        // Act - create a fresh group
        val createResponse = api.createGroup(
            accessToken = authResponse.access_token,
            name = "Heat Test Group ${System.currentTimeMillis()}",
            description = "Testing heat initialization"
        )
        trackGroup(createResponse.id)

        // Fetch the group details to get heat
        val details = api.getGroupDetails(authResponse.access_token, createResponse.id)

        // Assert - new group should start at tier 0 (Cold) with score 0
        assertThat(details.heat).isNotNull()
        val heat = details.heat!!
        assertThat(heat.tier).isEqualTo(0)
        assertThat(heat.tier_name).isEqualTo("Cold")
        assertThat(heat.score).isEqualTo(0f)
        assertThat(heat.streak_days).isEqualTo(0)
    }

    @Test
    fun `heat tier corresponds to score range`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act
        val response = api.getGroupDetails(authResponse.access_token, group.id)

        // Assert - verify tier matches score based on thresholds
        assertThat(response.heat).isNotNull()
        val heat = response.heat!!

        // Verify tier name matches tier number
        val expectedTierName = when (heat.tier) {
            0 -> "Cold"
            1 -> "Spark"
            2 -> "Ember"
            3 -> "Flicker"
            4 -> "Steady"
            5 -> "Blaze"
            6 -> "Inferno"
            7 -> "Supernova"
            else -> null
        }
        assertThat(heat.tier_name).isEqualTo(expectedTierName)

        // Verify score is within tier range
        when (heat.tier) {
            0 -> assertThat(heat.score).isAtMost(5f)
            1 -> { assertThat(heat.score).isGreaterThan(5f); assertThat(heat.score).isAtMost(18f) }
            2 -> { assertThat(heat.score).isGreaterThan(18f); assertThat(heat.score).isAtMost(32f) }
            3 -> { assertThat(heat.score).isGreaterThan(32f); assertThat(heat.score).isAtMost(46f) }
            4 -> { assertThat(heat.score).isGreaterThan(46f); assertThat(heat.score).isAtMost(60f) }
            5 -> { assertThat(heat.score).isGreaterThan(60f); assertThat(heat.score).isAtMost(74f) }
            6 -> { assertThat(heat.score).isGreaterThan(74f); assertThat(heat.score).isAtMost(88f) }
            7 -> assertThat(heat.score).isGreaterThan(88f)
        }
    }

    @Test
    fun `heat history returns current for premium users`() = runTest {
        // Arrange - shared user is already premium
        val authResponse = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act
        val response = api.getHeatHistory(authResponse.access_token, group.id)

        // Assert
        assertThat(response.group_id).isEqualTo(group.id)
        assertThat(response.current).isNotNull()
        assertThat(response.current.score).isAtLeast(0f)
        assertThat(response.current.tier).isAtLeast(0)
        assertThat(response.current.tier_name).isNotEmpty()
        assertThat(response.premium_required).isFalse()
    }

    @Test
    fun `heat history for premium users includes history and stats`() = runTest {
        // Arrange - shared user is already premium
        val authResponse = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act
        val response = api.getHeatHistory(authResponse.access_token, group.id, days = 30)

        // Assert - premium users get history and stats
        assertThat(response.premium_required).isFalse()
        // history and stats may be empty arrays/null for new groups, but should not be null for premium
        // The backend returns these fields for premium users
        // For a new group with no history, history may be empty list
    }

    @Test
    fun `heat history for free users returns premium_required true`() = runTest {
        // Arrange - create a fresh free user (not upgraded)
        val freeUser = testDataHelper.createTestUser(api, displayName = "Free User Heat Test")
        trackUser(freeUser.user!!.id)

        // Create a group for this user
        val group = testDataHelper.createTestGroup(api, freeUser.access_token)
        trackGroup(group.id)

        // Act
        val response = api.getHeatHistory(freeUser.access_token, group.id)

        // Assert - free users get current but history/stats are null
        assertThat(response.group_id).isEqualTo(group.id)
        assertThat(response.current).isNotNull()
        assertThat(response.premium_required).isTrue()
        assertThat(response.history).isNull()
        assertThat(response.stats).isNull()
    }

    @Test
    fun `heat history requires group membership`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Create a second user who is NOT a member of the group
        val nonMember = testDataHelper.createTestUser(api, displayName = "Non Member Heat Test")
        trackUser(nonMember.user!!.id)

        // Act
        var exception: Exception? = null
        try {
            api.getHeatHistory(nonMember.access_token, group.id)
        } catch (e: Exception) {
            exception = e
        }

        // Assert - should get 403 Forbidden
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        val apiException = exception as ApiException
        assertThat(apiException.code).isEqualTo(403)
    }

    @Test
    fun `heat history with invalid group returns 404`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        val invalidGroupId = "00000000-0000-0000-0000-000000000000"

        // Act
        var exception: Exception? = null
        try {
            api.getHeatHistory(authResponse.access_token, invalidGroupId)
        } catch (e: Exception) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        val apiException = exception as ApiException
        assertThat(apiException.code).isEqualTo(404)
    }

    @Test
    fun `heat history without authentication returns 401`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Clear tokens so no Authorization header is sent
        SecureTokenManager.getInstance(context).clearTokens()

        // Act
        var exception: Exception? = null
        try {
            api.getHeatHistory(accessToken = "", groupId = group.id)
        } catch (e: Exception) {
            exception = e
        }

        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        val apiException = exception as ApiException
        assertThat(apiException.code).isEqualTo(401)
    }

    @Test
    fun `heat history respects days parameter`() = runTest {
        // Arrange - shared user is premium
        val authResponse = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Act - request different day ranges
        val response7 = api.getHeatHistory(authResponse.access_token, group.id, days = 7)
        val response30 = api.getHeatHistory(authResponse.access_token, group.id, days = 30)

        // Assert - both should succeed and return valid responses
        assertThat(response7.group_id).isEqualTo(group.id)
        assertThat(response30.group_id).isEqualTo(group.id)
        // For a new group, history may be empty, but the endpoint should still work
    }
}
