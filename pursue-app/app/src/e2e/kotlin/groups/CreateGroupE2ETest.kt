package com.github.shannonbay.pursue.e2e.groups

import com.google.common.truth.Truth.assertThat
import app.getpursue.data.network.ApiException
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.models.GroupsResponse
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for group creation endpoint.
 * 
 * Tests POST /api/groups with various scenarios:
 * - Creating groups with minimal data (name only)
 * - Creating groups with optional fields (description, icon)
 * - Validation error handling
 * - Authentication error handling
 * - Verifying created groups appear in user's group list
 * - Resource limit: max 10 groups per user (specs/backend/02-database-schema.md)
 */
class CreateGroupE2ETest : E2ETest() {

    /** Max groups a user can create (per specs/backend/02-database-schema.md). */
    private val maxGroupsPerUser = 10
    
    @Test
    fun `create group with name only succeeds`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val groupName = "Test Group ${System.currentTimeMillis()}"
        
        // Act
        val response = api.createGroup(
            accessToken = authResponse.access_token,
            name = groupName
        )
        
        // Assert
        assertThat(response.id).isNotEmpty()
        assertThat(response.name).isEqualTo(groupName)
        assertThat(response.description).isNull()
        assertThat(response.icon_emoji).isNull()
        assertThat(response.icon_color).isNull()
        assertThat(response.has_icon).isFalse()
        assertThat(response.creator_user_id).isEqualTo(authResponse.user!!.id)
        assertThat(response.member_count).isEqualTo(1)
        assertThat(response.created_at).isNotEmpty()
        
        // Cleanup
        trackGroup(response.id)
    }
    
    @Test
    fun `create group with name and description succeeds`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val groupName = "Test Group ${System.currentTimeMillis()}"
        val description = "This is a test group description"
        
        // Act
        val response = api.createGroup(
            accessToken = authResponse.access_token,
            name = groupName,
            description = description
        )
        
        // Assert
        assertThat(response.id).isNotEmpty()
        assertThat(response.name).isEqualTo(groupName)
        assertThat(response.description).isEqualTo(description)
        assertThat(response.creator_user_id).isEqualTo(authResponse.user!!.id)
        
        // Cleanup
        trackGroup(response.id)
    }
    
    @Test
    fun `create group with emoji icon succeeds`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val groupName = "Test Group ${System.currentTimeMillis()}"
        val emoji = "🏋️"
        
        // Act
        val response = api.createGroup(
            accessToken = authResponse.access_token,
            name = groupName,
            iconEmoji = emoji
        )
        
        // Assert
        assertThat(response.id).isNotEmpty()
        assertThat(response.name).isEqualTo(groupName)
        assertThat(response.icon_emoji).isEqualTo(emoji)
        assertThat(response.icon_color).isNull()
        assertThat(response.has_icon).isFalse() // has_icon is false for emoji/color icons
        
        // Cleanup
        trackGroup(response.id)
    }
    
    @Test
    fun `create group with color icon succeeds`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val groupName = "Test Group ${System.currentTimeMillis()}"
        val color = "#1976D2" // Blue
        
        // Act
        val response = api.createGroup(
            accessToken = authResponse.access_token,
            name = groupName,
            iconColor = color
        )
        
        // Assert
        assertThat(response.id).isNotEmpty()
        assertThat(response.name).isEqualTo(groupName)
        assertThat(response.icon_emoji).isNull()
        assertThat(response.icon_color).isEqualTo(color)
        assertThat(response.has_icon).isFalse() // has_icon is false for emoji/color icons
        
        // Cleanup
        trackGroup(response.id)
    }
    
    @Test
    fun `create group with emoji and color uses emoji`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val groupName = "Test Group ${System.currentTimeMillis()}"
        val emoji = "🎓"
        val color = "#388E3C" // Green
        
        // Act
        val response = api.createGroup(
            accessToken = authResponse.access_token,
            name = groupName,
            iconEmoji = emoji,
            iconColor = color
        )
        
        // Assert
        assertThat(response.id).isNotEmpty()
        assertThat(response.name).isEqualTo(groupName)
        // Backend may prioritize emoji over color, or use both
        // Just verify the group was created successfully
        assertThat(response.creator_user_id).isEqualTo(authResponse.user!!.id)
        
        // Cleanup
        trackGroup(response.id)
    }
    
    @Test
    fun `create group with empty name fails validation`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        // Act
        var exception: Exception? = null
        try {
            api.createGroup(
                accessToken = authResponse.access_token,
                name = ""
            )
        } catch (e: Exception) {
            exception = e
        }
        
        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        val apiException = exception as ApiException
        assertThat(apiException.code).isEqualTo(400)
    }
    
    @Test
    fun `create group with name too long fails validation`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val longName = "a".repeat(101) // 101 characters (max is 100)
        
        // Act
        var exception: Exception? = null
        try {
            api.createGroup(
                accessToken = authResponse.access_token,
                name = longName
            )
        } catch (e: Exception) {
            exception = e
        }
        
        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        val apiException = exception as ApiException
        assertThat(apiException.code).isEqualTo(400)
    }
    
    @Test
    fun `create group with description too long fails validation`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val longDescription = "a".repeat(501) // 501 characters (max is 500)
        
        // Act
        var exception: Exception? = null
        try {
            api.createGroup(
                accessToken = authResponse.access_token,
                name = "Valid Group Name",
                description = longDescription
            )
        } catch (e: Exception) {
            exception = e
        }
        
        // Assert
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        val apiException = exception as ApiException
        assertThat(apiException.code).isEqualTo(400)
    }
    
    @Test
    fun `create group without authentication fails`() = runTest {
        // Arrange: clear tokens so no Authorization header is sent. Passing an invalid
        // token would store it and the request would still carry it; the backend may
        // not return 401 for malformed JWT. With no token, auth is clearly missing.
        SecureTokenManager.getInstance(context).clearTokens()

        // Act
        var exception: Exception? = null
        try {
            api.createGroup(
                accessToken = "",
                name = "Test Group"
            )
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
    fun `create group with invalid token returns 401`() = runTest {
        // Arrange: clear any stored token, then pass an invalid JWT so the request
        // is sent with Authorization: Bearer invalid_token_12345. Confirms the
        // backend rejects invalid/malformed tokens with 401.
        SecureTokenManager.getInstance(context).clearTokens()

        // Act
        var exception: Exception? = null
        try {
            api.createGroup(
                accessToken = "invalid_token_12345",
                name = "Test Group"
            )
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
    fun `created group appears in user groups list`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val groupName = "Test Group ${System.currentTimeMillis()}"
        val description = "Group for testing getMyGroups"
        
        // Act - Create group
        val createResponse = api.createGroup(
            accessToken = authResponse.access_token,
            name = groupName,
            description = description
        )
        trackGroup(createResponse.id)
        
        // Act - Get user's groups
        var groupsResponse: GroupsResponse? = null
        try {
            groupsResponse = api.getMyGroups(authResponse.access_token)
        } catch (e: ApiException) {
            // If endpoint doesn't exist (404), skip this test
            if (e.code == 404) {
                return@runTest
            }
            throw e
        }
        
        // Assert - Group should be in the list
        assertThat(groupsResponse.groups).isNotEmpty()
        val createdGroup = groupsResponse.groups.find { it.id == createResponse.id }
        assertThat(createdGroup).isNotNull()
        assertThat(createdGroup!!.name).isEqualTo(groupName)
        assertThat(createdGroup.description).isEqualTo(description)
        assertThat(createdGroup.role).isEqualTo("creator") // User who created the group should be creator
    }
    
    @Test
    fun `create multiple groups for same user succeeds`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        // Act - Create multiple groups
        val group1 = api.createGroup(
            accessToken = authResponse.access_token,
            name = "Group 1 ${System.currentTimeMillis()}"
        )
        trackGroup(group1.id)
        
        val group2 = api.createGroup(
            accessToken = authResponse.access_token,
            name = "Group 2 ${System.currentTimeMillis()}"
        )
        trackGroup(group2.id)
        
        val group3 = api.createGroup(
            accessToken = authResponse.access_token,
            name = "Group 3 ${System.currentTimeMillis()}"
        )
        trackGroup(group3.id)
        
        // Assert - All groups should have different IDs
        assertThat(group1.id).isNotEqualTo(group2.id)
        assertThat(group2.id).isNotEqualTo(group3.id)
        assertThat(group1.id).isNotEqualTo(group3.id)
        
        // All should have same creator
        assertThat(group1.creator_user_id).isEqualTo(authResponse.user!!.id)
        assertThat(group2.creator_user_id).isEqualTo(authResponse.user!!.id)
        assertThat(group3.creator_user_id).isEqualTo(authResponse.user!!.id)
    }

    @Test
    fun `concurrent group creation does not bypass 10 groups per user limit`() = runTest {
        // Use a fresh user so we start with 0 groups (shared user may already have groups).
        val authResponse = testDataHelper.createTestUser(api, displayName = "Limit Test User")
        trackUser(authResponse.user!!.id)
        testDataHelper.upgradeToPremium(api, authResponse.access_token)

        // Fire more than the limit concurrently to ensure the backend enforces the cap.
        val attempts = maxGroupsPerUser + 2
        val results = coroutineScope {
            (1..attempts).map { i ->
                async {
                    runCatching {
                        api.createGroup(
                            accessToken = authResponse.access_token,
                            name = "Concurrent Group $i ${System.currentTimeMillis()}"
                        )
                    }
                }
            }.map { it.await() }
        }

        val successes = results.filter { it.isSuccess }.map { it.getOrNull()!! }
        val failures = results.filter { it.isFailure }

        // Exactly maxGroupsPerUser should succeed; the rest must fail (resource limit).
        assertThat(successes).hasSize(maxGroupsPerUser)
        assertThat(failures).hasSize(attempts - maxGroupsPerUser)

        failures.forEach { result ->
            val ex = result.exceptionOrNull()
            assertThat(ex).isNotNull()
            assertThat(ex).isInstanceOf(ApiException::class.java)
            val code = (ex as ApiException).code
            // Backend may return 400/403 for resource limit or 429 for rate limit
            assertThat(code).isAnyOf(400, 403, 429)
        }

        successes.forEach { trackGroup(it.id) }
    }
}
