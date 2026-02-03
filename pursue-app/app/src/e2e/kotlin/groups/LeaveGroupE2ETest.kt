package com.github.shannonbay.pursue.e2e.groups

import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for leave group endpoint.
 *
 * Tests DELETE /api/groups/:group_id/members/me (self-removal).
 * Spec: creator as sole member leaves -> group deleted; otherwise membership removed.
 */
class LeaveGroupE2ETest : E2ETest() {

    @Test
    fun `leaveGroup succeeds and group no longer in getMyGroups`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()

        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)

        // Act
        api.leaveGroup(authResponse.access_token, group.id)

        // Assert - Creator as sole member leaves -> group deleted per spec; group should not appear in list
        val groupsResponse = api.getMyGroups(authResponse.access_token)
        val leftGroup = groupsResponse.groups.find { it.id == group.id }
        assertThat(leftGroup).isNull()
    }

    @Test
    fun `leaveGroup with invalid group ID returns 404`() = runTest {
        // Arrange
        val authResponse = getOrCreateSharedUser()

        val invalidGroupId = "00000000-0000-0000-0000-000000000000"

        // Act
        var exception: Exception? = null
        try {
            api.leaveGroup(authResponse.access_token, invalidGroupId)
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
    fun `leaveGroup without authentication returns 401`() = runTest {
        // Arrange: create group with shared user, then clear tokens so no Authorization header is sent
        val authResponse = getOrCreateSharedUser()

        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)

        SecureTokenManager.getInstance(context).clearTokens()

        // Act
        var exception: Exception? = null
        try {
            api.leaveGroup("", group.id)
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
    fun `leaveGroup when not a member returns 404 or 403`() = runTest {
        // Arrange: create group, leave once (succeed), then try to leave again
        val authResponse = getOrCreateSharedUser()

        val group = testDataHelper.createTestGroup(api, authResponse.access_token)
        trackGroup(group.id)

        api.leaveGroup(authResponse.access_token, group.id)

        // Act - Second leave (user is no longer a member)
        var exception: Exception? = null
        try {
            api.leaveGroup(authResponse.access_token, group.id)
        } catch (e: Exception) {
            exception = e
        }

        // Assert - Backend may return 404 (group gone or not found) or 403 (forbidden)
        assertThat(exception).isNotNull()
        assertThat(exception).isInstanceOf(ApiException::class.java)
        val apiException = exception as ApiException
        assertThat(apiException.code).isAnyOf(404, 403)
    }
}
