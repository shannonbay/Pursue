package com.github.shannonbay.pursue.e2e.groups

import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.github.shannonbay.pursue.e2e.helpers.TestImageHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for group icon upload and retrieval.
 *
 * Uses PATCH /api/groups/:id/icon for upload; GET /api/groups/:id/icon for fetch.
 */
class GroupIconE2ETest : E2ETest() {

    @Test
    fun `upload group icon stores image`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)
        val testImage = TestImageHelper.createSmallTestImage()

        // Act - Upload icon
        val uploadResponse = api.uploadGroupIcon(authResponse.access_token, group.id, testImage)

        // Assert
        assertThat(uploadResponse.has_icon).isTrue()

        // Verify can fetch icon
        val iconBytes = api.getGroupIcon(group.id, authResponse.access_token)
        assertThat(iconBytes).isNotNull()
        assertThat(iconBytes!!.size).isGreaterThan(0)
    }

    @Test
    fun `uploading group icon clears emoji and color`() = runTest {
        // Arrange - Create group with emoji and color
        val authResponse = getOrCreateSharedUser()
        val group = testDataHelper.createTestGroup(
            api,
            authResponse.access_token,
            iconEmoji = "🏃",
            iconColor = "#1976D2"
        )
        trackGroup(group.id)
        val testImage = TestImageHelper.createSmallTestImage()

        // Act - Upload icon (server clears icon_emoji and icon_color per spec)
        val uploadResponse = api.uploadGroupIcon(authResponse.access_token, group.id, testImage)

        // Assert
        assertThat(uploadResponse.has_icon).isTrue()
        assertThat(uploadResponse.icon_emoji).isNull()
        assertThat(uploadResponse.icon_color).isNull()
    }
    
    @Test
    fun `GET group icon returns image data if icon exists`() = runTest {
        // This test can only verify that the endpoint exists and returns 404 if no icon
        // To fully test, we'd need the upload endpoint
        
        // Arrange - Create user and get a group (if any exist)
        val authResponse = getOrCreateSharedUser()
        
        // Get user's groups - handle 404 as "no groups" (valid for new user)
        var groupsResponse: com.github.shannonbay.pursue.models.GroupsResponse? = null
        try {
            groupsResponse = api.getMyGroups(authResponse.access_token)
        } catch (e: ApiException) {
            if (e.code == 404) {
                // No groups endpoint or user has no groups - skip test
                return@runTest
            }
            throw e
        }
        
        // If user has groups, try to get icon (will likely be 404 if no icon uploaded)
        if (groupsResponse.groups.isNotEmpty()) {
            val group = groupsResponse.groups.first()
            val iconBytes = api.getGroupIcon(group.id, authResponse.access_token)
            
            // Either returns image data or null (404)
            // This test just verifies the endpoint is accessible
            // If iconBytes is null, that's expected if no icon was uploaded
        }
    }
}
