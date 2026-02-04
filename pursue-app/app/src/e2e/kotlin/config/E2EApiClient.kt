package com.github.shannonbay.pursue.e2e.config

import android.content.Context
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.data.network.CreateGoalResponse
import com.github.shannonbay.pursue.data.network.CreateGroupResponse
import com.github.shannonbay.pursue.data.network.DeleteAvatarResponse
import com.github.shannonbay.pursue.data.network.GetInviteCodeResponse
import com.github.shannonbay.pursue.data.network.JoinGroupResponse
import com.github.shannonbay.pursue.data.network.RegenerateInviteResponse
import com.github.shannonbay.pursue.data.network.PatchGroupIconResponse
import com.github.shannonbay.pursue.data.network.GetGoalResponse
import com.github.shannonbay.pursue.data.network.GoalProgressMeResponse
import com.github.shannonbay.pursue.data.network.GoalProgressResponse
import com.github.shannonbay.pursue.data.network.LoginResponse
import com.github.shannonbay.pursue.data.network.LogProgressResponse
import com.github.shannonbay.pursue.data.network.RefreshTokenResponse
import com.github.shannonbay.pursue.data.network.RegistrationResponse
import com.github.shannonbay.pursue.data.network.UpdateGoalResponse
import com.github.shannonbay.pursue.data.network.UploadAvatarResponse
import com.github.shannonbay.pursue.data.network.User
import com.github.shannonbay.pursue.models.GroupsResponse
import com.github.shannonbay.pursue.models.GroupDetailResponse
import com.github.shannonbay.pursue.models.GroupMembersResponse
import com.github.shannonbay.pursue.models.GroupActivityResponse
import com.github.shannonbay.pursue.models.GroupGoalsResponse
import java.io.File

/**
 * E2E API client: thin proxy over ApiClient.
 *
 * Stores the given accessToken in SecureTokenManager before each authenticated call
 * so AuthInterceptor sees it. Adapts getAvatar/getGroupIcon to return ByteArray?
 * and to accept (userId, accessToken?) and (groupId, accessToken?).
 *
 * E2ETest must call ApiClient.setBaseUrlForE2E(LocalServerConfig.API_BASE_URL) and
 * ApiClient.initialize(ApiClient.buildClient(context)) before using this client.
 */
class E2EApiClient(private val context: Context) {

    private fun storeTokenIfPresent(accessToken: String?) {
        if (!accessToken.isNullOrEmpty()) {
            val tm = SecureTokenManager.getInstance(context)
            tm.storeTokens(accessToken, tm.getRefreshToken() ?: "")
        }
    }

    suspend fun register(
        displayName: String,
        email: String,
        password: String
    ): RegistrationResponse = ApiClient.register(displayName, email, password)

    suspend fun login(email: String, password: String): LoginResponse =
        ApiClient.login(email, password)

    suspend fun refreshToken(refreshToken: String): RefreshTokenResponse =
        ApiClient.refreshToken(refreshToken)

    suspend fun getMyUser(accessToken: String): User {
        storeTokenIfPresent(accessToken)
        return ApiClient.getMyUser("")
    }

    suspend fun getMyGroups(
        accessToken: String,
        limit: Int = 50,
        offset: Int = 0
    ): GroupsResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getMyGroups("", limit, offset)
    }

    suspend fun createGroup(
        accessToken: String,
        name: String,
        description: String? = null,
        iconEmoji: String? = null,
        iconColor: String? = null
    ): CreateGroupResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.createGroup("", name, description, iconEmoji, iconColor)
    }

    suspend fun getGroupDetails(accessToken: String, groupId: String): GroupDetailResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGroupDetails("", groupId)
    }

    suspend fun getGroupMembers(accessToken: String, groupId: String): GroupMembersResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGroupMembers("", groupId)
    }

    suspend fun getGroupActivity(
        accessToken: String,
        groupId: String,
        limit: Int = 50,
        offset: Int = 0
    ): GroupActivityResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGroupActivity("", groupId, limit, offset)
    }

    suspend fun leaveGroup(accessToken: String, groupId: String) {
        storeTokenIfPresent(accessToken)
        ApiClient.leaveGroup("", groupId)
    }

    suspend fun getGroupInviteCode(accessToken: String, groupId: String): GetInviteCodeResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGroupInviteCode("", groupId)
    }

    suspend fun regenerateInviteCode(accessToken: String, groupId: String): RegenerateInviteResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.regenerateInviteCode("", groupId)
    }

    suspend fun joinGroup(accessToken: String, inviteCode: String): JoinGroupResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.joinGroup("", inviteCode)
    }

    suspend fun updateMemberRole(
        accessToken: String,
        groupId: String,
        userId: String,
        role: String
    ) {
        storeTokenIfPresent(accessToken)
        ApiClient.updateMemberRole("", groupId, userId, role)
    }

    suspend fun removeMember(
        accessToken: String,
        groupId: String,
        userId: String
    ) {
        storeTokenIfPresent(accessToken)
        ApiClient.removeMember("", groupId, userId)
    }

    suspend fun approveMember(
        accessToken: String,
        groupId: String,
        userId: String
    ) {
        storeTokenIfPresent(accessToken)
        ApiClient.approveMember("", groupId, userId)
    }

    // --- Goal Endpoints (3.4) ---

    suspend fun createGoal(
        accessToken: String,
        groupId: String,
        title: String,
        description: String? = null,
        cadence: String = "daily",
        metricType: String = "binary",
        targetValue: Double? = null,
        unit: String? = null
    ): CreateGoalResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.createGoal("", groupId, title, description, cadence, metricType, targetValue, unit)
    }

    suspend fun getGroupGoals(
        accessToken: String,
        groupId: String,
        cadence: String? = null,
        archived: Boolean = false,
        includeProgress: Boolean = true
    ): GroupGoalsResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGroupGoals("", groupId, cadence, archived, includeProgress)
    }

    suspend fun getGoal(accessToken: String, goalId: String): GetGoalResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGoal("", goalId)
    }

    suspend fun updateGoal(
        accessToken: String,
        goalId: String,
        title: String? = null,
        description: String? = null
    ): UpdateGoalResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.updateGoal("", goalId, title, description)
    }

    suspend fun deleteGoal(accessToken: String, goalId: String) {
        storeTokenIfPresent(accessToken)
        ApiClient.deleteGoal("", goalId)
    }

    suspend fun getGoalProgress(
        accessToken: String,
        goalId: String,
        startDate: String? = null,
        endDate: String? = null
    ): GoalProgressResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGoalProgress("", goalId, startDate, endDate)
    }

    suspend fun getGoalProgressMe(
        accessToken: String,
        goalId: String,
        startDate: String? = null,
        endDate: String? = null
    ): GoalProgressMeResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGoalProgressMe("", goalId, startDate, endDate)
    }

    suspend fun logProgress(
        accessToken: String,
        goalId: String,
        value: Double,
        note: String? = null,
        userDate: String,
        userTimezone: String
    ): LogProgressResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.logProgress("", goalId, value, note, userDate, userTimezone)
    }

    suspend fun deleteProgressEntry(accessToken: String, entryId: String) {
        storeTokenIfPresent(accessToken)
        ApiClient.deleteProgressEntry("", entryId)
    }

    suspend fun uploadAvatar(accessToken: String, imageFile: File): UploadAvatarResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.uploadAvatar("", imageFile)
    }

    suspend fun uploadAvatar(accessToken: String, imageBytes: ByteArray): UploadAvatarResponse {
        val tempFile = File.createTempFile("e2e_avatar_", ".jpg")
        tempFile.writeBytes(imageBytes)
        return try {
            uploadAvatar(accessToken, tempFile)
        } finally {
            tempFile.delete()
        }
    }

    suspend fun deleteAvatar(accessToken: String): DeleteAvatarResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.deleteAvatar("")
    }

    suspend fun getAvatar(userId: String, accessToken: String? = null): ByteArray? {
        storeTokenIfPresent(accessToken)
        return ApiClient.getAvatar(accessToken ?: "", userId)?.bytes()
    }

    suspend fun getGroupIcon(groupId: String, accessToken: String? = null): ByteArray? {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGroupIcon(accessToken ?: "", groupId)?.bytes()
    }

    suspend fun uploadGroupIcon(
        accessToken: String,
        groupId: String,
        imageBytes: ByteArray
    ): PatchGroupIconResponse {
        storeTokenIfPresent(accessToken)
        val tempFile = File.createTempFile("e2e_group_icon_", ".jpg")
        tempFile.writeBytes(imageBytes)
        return try {
            ApiClient.uploadGroupIcon("", groupId, tempFile)
        } finally {
            tempFile.delete()
        }
    }
}
