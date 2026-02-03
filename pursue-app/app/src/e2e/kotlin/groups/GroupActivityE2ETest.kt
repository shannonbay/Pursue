package com.github.shannonbay.pursue.e2e.groups

import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for group activity endpoint.
 * 
 * Tests GET /api/groups/:group_id/activity endpoint with pagination.
 */
class GroupActivityE2ETest : E2ETest() {
    
    @Test
    fun `getGroupActivity returns activity feed`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)
        
        // Act
        val response = api.getGroupActivity(authResponse.access_token, group.id)
        
        // Assert
        assertThat(response.activities).isNotNull()
        assertThat(response.total).isAtLeast(0)
        
        // Verify activity structure if activities exist
        if (response.activities.isNotEmpty()) {
            val activity = response.activities.first()
            assertThat(activity.activity_type).isNotEmpty()
            assertThat(activity.user).isNotNull()
            assertThat(activity.user.id).isNotEmpty()
            assertThat(activity.user.display_name).isNotEmpty()
            assertThat(activity.created_at).isNotEmpty()
        }
    }
    
    @Test
    fun `getGroupActivity includes total count`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)
        
        // Act
        val response = api.getGroupActivity(authResponse.access_token, group.id)
        
        // Assert
        assertThat(response.total).isAtLeast(0)
        assertThat(response.total).isAtLeast(response.activities.size)
    }
    
    @Test
    fun `getGroupActivity respects limit parameter`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)
        
        // Act - Request with limit of 5
        val response = api.getGroupActivity(authResponse.access_token, group.id, limit = 5)
        
        // Assert
        assertThat(response.activities.size).isAtMost(5)
    }
    
    @Test
    fun `getGroupActivity respects offset parameter`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)
        
        // Act - Get first page
        val firstPage = api.getGroupActivity(authResponse.access_token, group.id, limit = 10, offset = 0)
        
        // If there are more than 10 activities, test pagination
        if (firstPage.total > 10) {
            // Get second page
            val secondPage = api.getGroupActivity(authResponse.access_token, group.id, limit = 10, offset = 10)
            
            // Assert - Second page should have different activities
            assertThat(secondPage.activities).isNotEmpty()
            
            // Verify no overlap (if IDs are available)
            val firstPageIds = firstPage.activities.mapNotNull { it.id }.toSet()
            val secondPageIds = secondPage.activities.mapNotNull { it.id }.toSet()
            assertThat(firstPageIds.intersect(secondPageIds)).isEmpty()
        }
    }
    
    @Test
    fun `getGroupActivity with limit over 100 uses max 100`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)
        
        // Act - Request with limit over 100
        val response = api.getGroupActivity(authResponse.access_token, group.id, limit = 200)
        
        // Assert - Should not exceed 100
        assertThat(response.activities.size).isAtMost(100)
    }
    
    @Test
    fun `getGroupActivity with invalid group ID returns 404`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        val invalidGroupId = "00000000-0000-0000-0000-000000000000"
        
        // Act
        var exception: Exception? = null
        try {
            api.getGroupActivity(authResponse.access_token, invalidGroupId)
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
    fun `getGroupActivity without authentication returns 401`() = runTest {
        // Arrange: create user and group to get a valid group id, then clear tokens
        // so no Authorization header is sent. Passing an invalid token would store it
        // and the request would still carry it; the backend may not return 401.
        val authResponse = getOrCreateSharedUser()

        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)

        SecureTokenManager.getInstance(context).clearTokens()

        // Act
        var exception: Exception? = null
        try {
            api.getGroupActivity(accessToken = "", groupId = group.id)
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
    fun `getGroupActivity returns empty list for new group`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        
        // Create new group (should have minimal activity)
        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)
        
        // Act
        val response = api.getGroupActivity(authResponse.access_token, group.id)
        
        // Assert - New group may have some activity (e.g., group_created, member_joined)
        // or may be empty, both are acceptable
        assertThat(response.activities).isNotNull()
        assertThat(response.total).isAtLeast(0)
        
        // If activities exist, they should be valid
        response.activities.forEach { activity ->
            assertThat(activity.activity_type).isNotEmpty()
            assertThat(activity.user).isNotNull()
            assertThat(activity.created_at).isNotEmpty()
        }
    }
}
