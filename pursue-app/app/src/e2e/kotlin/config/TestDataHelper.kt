package com.github.shannonbay.pursue.e2e.config

import android.content.Context
import app.getpursue.data.network.ApiException
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.CreateGoalResponse
import app.getpursue.data.network.RegistrationResponse
import app.getpursue.models.Group
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * Helper class for creating and managing test data in E2E tests.
 */
class TestDataHelper(private val context: Context) {

    /** Delay (ms) before retry when rate limited (429). */
    private val rateLimitRetryDelayMs = 2_000L

    /**
     * Create a test user with unique email.
     * Stores access and refresh tokens in SecureTokenManager so subsequent
     * authenticated calls via the E2EApiClient proxy use them.
     *
     * On HTTP 429 (rate limit), retries once after a short delay. If the backend
     * auth limiter is strict (e.g. 5/15min), relax it for localhost (see E2ETESTING.md).
     *
     * @param api E2E API client
     * @param displayName Display name for the user (default: "Test User")
     * @param password Password for the user (default: "TestPass123!")
     * @return RegistrationResponse with access token and user data
     */
    suspend fun createTestUser(
        api: E2EApiClient,
        displayName: String = "Test User",
        password: String = "TestPass123!"
    ): RegistrationResponse {
        val email = "test-${UUID.randomUUID()}@example.com"

        return registerWithRetry(api, displayName, email, password, retriesLeft = 1)
    }

    private suspend fun registerWithRetry(
        api: E2EApiClient,
        displayName: String,
        email: String,
        password: String,
        retriesLeft: Int
    ): RegistrationResponse {
        return try {
            val res = api.register(displayName, email, password)
            SecureTokenManager.getInstance(context).storeTokens(res.access_token, res.refresh_token)
            res
        } catch (e: ApiException) {
            if (e.code == 429 && retriesLeft > 0) {
                delay(rateLimitRetryDelayMs)
                registerWithRetry(api, displayName, email, password, retriesLeft - 1)
            } else {
                throw Exception("Failed to create test user: ${e.message}", e)
            }
        }
    }

    /**
     * Delete user (cleanup).
     *
     * Note: This assumes a DELETE /api/users/:id endpoint exists in the backend.
     * If not, this will fail silently during cleanup.
     */
    suspend fun deleteUser(userId: String) {
        try {
            // TODO: Implement if DELETE /api/users/:id endpoint exists
            // For now, users will remain in the database after tests
            // This is acceptable for E2E tests as they use unique emails
            println("⚠️  User deletion not implemented - user $userId will remain in database")
        } catch (e: Exception) {
            // Ignore errors during cleanup
            println("⚠️  Failed to delete user $userId: ${e.message}")
        }
    }

    /**
     * Delete group (cleanup).
     *
     * Note: This assumes a DELETE /api/groups/:id endpoint exists in the backend.
     * If not, this will fail silently during cleanup.
     */
    suspend fun deleteGroup(groupId: String) {
        try {
            // TODO: Implement if DELETE /api/groups/:id endpoint exists
            // For now, groups will remain in the database after tests
            println("⚠️  Group deletion not implemented - group $groupId will remain in database")
        } catch (e: Exception) {
            // Ignore errors during cleanup
            println("⚠️  Failed to delete group $groupId: ${e.message}")
        }
    }
    
    /**
     * Create a test group.
     * 
     * Note: This assumes a POST /api/groups endpoint exists in the backend.
     * If not, this function will throw an exception.
     * 
     * @param api E2E API client
     * @param accessToken Access token for authentication
     * @param name Group name (default: "Test Group {UUID}")
     * @param description Group description (default: "E2E test group")
     * @return Group object with group data
     */
    suspend fun createTestGroup(
        api: E2EApiClient,
        accessToken: String,
        name: String = "Test Group ${UUID.randomUUID()}",
        description: String = "E2E test group",
        iconEmoji: String? = null,
        iconColor: String? = null
    ): Group {
        return try {
            val response = api.createGroup(
                accessToken = accessToken,
                name = name,
                description = description,
                iconEmoji = iconEmoji,
                iconColor = iconColor
            )
            
            // Convert CreateGroupResponse to Group model
            // Note: Group model has different fields than CreateGroupResponse
            // Group uses role, joined_at, updated_at instead of creator_user_id, created_at
            Group(
                id = response.id,
                name = response.name,
                description = response.description,
                icon_emoji = response.icon_emoji,
                has_icon = response.has_icon,
                member_count = response.member_count,
                role = "creator", // User who created the group is the creator
                joined_at = response.created_at, // Use created_at as joined_at
                updated_at = response.created_at // Use created_at as updated_at
            )
        } catch (e: ApiException) {
            throw Exception("Failed to create test group: ${e.message}", e)
        }
    }
    
    /**
     * Upgrade user to premium using mock token (backend must be started with NODE_ENV=test).
     * Call after createTestUser or getOrCreateSharedUser to allow creating multiple groups.
     */
    suspend fun upgradeToPremium(api: E2EApiClient, accessToken: String) {
        api.upgradeSubscription(
            accessToken = accessToken,
            platform = "google_play",
            purchaseToken = "mock-token-e2e",
            productId = "pursue_premium_annual"
        )
    }

    /**
     * Create a test goal in a group (POST /api/groups/:group_id/goals).
     * Caller must be admin or creator of the group.
     *
     * @param api E2E API client
     * @param accessToken Access token for authentication
     * @param groupId Group ID to create goal in
     * @param title Goal title (default: "Test Goal {UUID}")
     * @param description Optional description
     * @param cadence One of: daily, weekly, monthly, yearly (default: daily)
     * @param metricType One of: binary, numeric, duration (default: binary)
     * @param targetValue For numeric goals (optional)
     * @param unit For numeric goals, e.g. "pages" (optional)
     * @return CreateGoalResponse with created goal data
     */
    suspend fun createTestGoal(
        api: E2EApiClient,
        accessToken: String,
        groupId: String,
        title: String = "Test Goal ${UUID.randomUUID()}",
        description: String? = null,
        cadence: String = "daily",
        metricType: String = "binary",
        targetValue: Double? = null,
        unit: String? = null
    ): CreateGoalResponse {
        return try {
            api.createGoal(
                accessToken = accessToken,
                groupId = groupId,
                title = title,
                description = description,
                cadence = cadence,
                metricType = metricType,
                targetValue = targetValue,
                unit = unit
            )
        } catch (e: ApiException) {
            throw Exception("Failed to create test goal: ${e.message}", e)
        }
    }
}
