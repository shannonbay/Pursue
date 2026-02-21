package com.github.shannonbay.pursue.e2e.config

import android.content.Context
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.CreateGoalResponse
import app.getpursue.data.network.CreateGroupResponse
import app.getpursue.data.network.DeleteAvatarResponse
import app.getpursue.data.network.GetInviteCodeResponse
import app.getpursue.data.network.JoinGroupResponse
import app.getpursue.data.network.ChallengeTemplatesResponse
import app.getpursue.data.network.CreateChallengeGoal
import app.getpursue.data.network.CreateChallengeResponse
import app.getpursue.data.network.ChallengesListResponse
import app.getpursue.data.network.CancelChallengeResponse
import app.getpursue.data.network.ChallengeStatusUpdateJobResponse
import app.getpursue.data.network.RegenerateInviteResponse
import app.getpursue.data.network.PatchGroupIconResponse
import app.getpursue.data.network.UploadProgressPhotoResponse
import app.getpursue.data.network.GetProgressPhotoResponse
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.GetGoalResponse
import app.getpursue.data.network.GoalProgressMeResponse
import app.getpursue.data.network.GoalProgressResponse
import app.getpursue.data.network.LoginResponse
import app.getpursue.data.network.LogProgressResponse
import app.getpursue.data.network.RefreshTokenResponse
import app.getpursue.data.network.RegistrationResponse
import app.getpursue.data.network.UpdateGoalResponse
import app.getpursue.data.network.UpgradeSubscriptionResponse
import app.getpursue.data.network.UploadAvatarResponse
import app.getpursue.data.network.User
import app.getpursue.data.network.UserConsentsResponse
import app.getpursue.data.network.AddReactionResponse
import app.getpursue.data.network.GetReactionsResponse
import app.getpursue.data.network.NudgesSentTodayResponse
import app.getpursue.data.network.SendNudgeResponse
import app.getpursue.data.network.NotificationsResponse
import app.getpursue.data.network.NotificationItem
import app.getpursue.data.network.ChallengeCompletionCardData
import app.getpursue.data.network.UnreadCountResponse
import app.getpursue.data.network.MarkAllReadResponse
import app.getpursue.data.network.MarkNotificationReadResponse
import app.getpursue.data.network.toChallengeCompletionCardDataOrNull
import app.getpursue.data.network.MemberProgressResponse
import app.getpursue.data.network.HeatHistoryResponse
import app.getpursue.data.network.WeeklyRecapJobResponse
import app.getpursue.data.network.GetAllReminderPreferencesResponse
import app.getpursue.data.network.GoalReminderPreferencesResponse
import app.getpursue.data.network.RecalculateGoalPatternResponse
import app.getpursue.data.network.UpdateGoalReminderPreferencesResponse
import app.getpursue.models.GroupsResponse
import app.getpursue.models.GroupDetailResponse
import app.getpursue.models.GroupMembersResponse
import app.getpursue.models.GroupActivityResponse
import app.getpursue.models.GroupGoalsResponse
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
        password: String,
        consentTermsVersion: String? = null,
        consentPrivacyVersion: String? = null
    ): RegistrationResponse = ApiClient.register(
        displayName, email, password,
        consentAgreed = true,
        consentTermsVersion = consentTermsVersion,
        consentPrivacyVersion = consentPrivacyVersion
    )

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

    suspend fun getMemberProgress(
        accessToken: String,
        groupId: String,
        userId: String,
        startDate: String,
        endDate: String,
        cursor: String? = null,
        limit: Int = 50
    ): MemberProgressResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getMemberProgress("", groupId, userId, startDate, endDate, cursor, limit)
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

    // --- Challenge Endpoints ---

    suspend fun getChallengeTemplates(
        accessToken: String,
        category: String? = null,
        featured: Boolean? = null
    ): ChallengeTemplatesResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getChallengeTemplates("", category, featured)
    }

    suspend fun createChallenge(
        accessToken: String,
        templateId: String? = null,
        startDate: String,
        endDate: String? = null,
        groupName: String? = null,
        groupDescription: String? = null,
        iconEmoji: String? = null,
        goals: List<CreateChallengeGoal>? = null
    ): CreateChallengeResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.createChallenge(
            "",
            templateId,
            startDate,
            endDate,
            groupName,
            groupDescription,
            iconEmoji,
            goals
        )
    }

    suspend fun getChallenges(
        accessToken: String,
        status: String? = null
    ): ChallengesListResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getChallenges("", status)
    }

    suspend fun cancelChallenge(accessToken: String, challengeId: String): CancelChallengeResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.cancelChallenge("", challengeId)
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
        unit: String? = null,
        activeDays: List<Int>? = null
    ): CreateGoalResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.createGoal("", groupId, title, description, cadence, metricType, targetValue, unit, activeDays)
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
        description: String? = null,
        activeDays: List<Int>? = null,
        resetActiveDays: Boolean = false
    ): UpdateGoalResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.updateGoal("", goalId, title, description, activeDays, resetActiveDays)
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

    suspend fun deleteAccount(accessToken: String, confirmation: String) {
        storeTokenIfPresent(accessToken)
        ApiClient.deleteAccount("", confirmation)
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

    // --- Progress Photo Endpoints ---

    /**
     * Upload progress photo with ByteArray (creates temp file).
     */
    suspend fun uploadProgressPhoto(
        accessToken: String,
        progressEntryId: String,
        imageBytes: ByteArray,
        width: Int = 256,
        height: Int = 256
    ): UploadProgressPhotoResponse {
        storeTokenIfPresent(accessToken)
        val tempFile = File.createTempFile("e2e_progress_photo_", ".jpg")
        tempFile.writeBytes(imageBytes)
        return try {
            ApiClient.uploadProgressPhoto("", progressEntryId, tempFile, width, height)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * Get progress photo signed URL.
     */
    suspend fun getProgressPhoto(
        accessToken: String,
        progressEntryId: String
    ): GetProgressPhotoResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getProgressPhoto("", progressEntryId)
    }

    /**
     * Get progress photo returning null on 404/410 (for expired/missing).
     */
    suspend fun getProgressPhotoOrNull(
        accessToken: String,
        progressEntryId: String
    ): GetProgressPhotoResponse? {
        return try {
            getProgressPhoto(accessToken, progressEntryId)
        } catch (e: ApiException) {
            if (e.code == 404 || e.code == 410) null else throw e
        }
    }

    suspend fun getMyConsents(accessToken: String): UserConsentsResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getMyConsents("")
    }

    suspend fun recordConsents(accessToken: String, consentTypes: List<String>) {
        storeTokenIfPresent(accessToken)
        ApiClient.recordConsents("", consentTypes)
    }

    suspend fun upgradeSubscription(
        accessToken: String,
        platform: String = "google_play",
        purchaseToken: String,
        productId: String = "pursue_premium_annual"
    ): UpgradeSubscriptionResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.upgradeSubscription("", platform, purchaseToken, productId)
    }

    // --- Reaction Endpoints ---

    suspend fun addOrReplaceReaction(
        accessToken: String,
        activityId: String,
        emoji: String
    ): AddReactionResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.addOrReplaceReaction("", activityId, emoji)
    }

    suspend fun removeReaction(accessToken: String, activityId: String) {
        storeTokenIfPresent(accessToken)
        ApiClient.removeReaction("", activityId)
    }

    suspend fun getReactions(
        accessToken: String,
        activityId: String
    ): GetReactionsResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getReactions("", activityId)
    }

    // --- Nudge Endpoints ---

    suspend fun sendNudge(
        accessToken: String,
        recipientUserId: String,
        groupId: String,
        goalId: String? = null,
        senderLocalDate: String
    ): SendNudgeResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.sendNudge("", recipientUserId, groupId, goalId, senderLocalDate)
    }

    suspend fun getNudgesSentToday(
        accessToken: String,
        groupId: String,
        senderLocalDate: String
    ): NudgesSentTodayResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getNudgesSentToday("", groupId, senderLocalDate)
    }

    // --- Notification Inbox Endpoints ---

    suspend fun getNotifications(
        accessToken: String,
        limit: Int = 30,
        beforeId: String? = null
    ): NotificationsResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getNotifications("", limit, beforeId)
    }

    suspend fun getChallengeCompletionNotifications(
        accessToken: String,
        limit: Int = 50
    ): List<NotificationItem> {
        val safeLimit = limit.coerceIn(1, 50)
        return getNotifications(accessToken, limit = safeLimit).notifications.filter {
            it.type == "milestone_achieved" && it.metadata?.get("milestone_type") == "challenge_completed"
        }
    }

    suspend fun getFirstChallengeCompletionCard(
        accessToken: String,
        limit: Int = 50
    ): ChallengeCompletionCardData? {
        return getChallengeCompletionNotifications(accessToken, limit)
            .firstNotNullOfOrNull { it.toChallengeCompletionCardDataOrNull() }
    }

    suspend fun getUnreadCount(accessToken: String): UnreadCountResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getUnreadCount("")
    }

    suspend fun markAllNotificationsRead(accessToken: String): MarkAllReadResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.markAllNotificationsRead("")
    }

    suspend fun markNotificationRead(
        accessToken: String,
        notificationId: String
    ): MarkNotificationReadResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.markNotificationRead("", notificationId)
    }

    suspend fun deleteNotification(accessToken: String, notificationId: String) {
        storeTokenIfPresent(accessToken)
        ApiClient.deleteNotification("", notificationId)
    }

    // --- Group Heat Endpoints ---

    suspend fun getHeatHistory(
        accessToken: String,
        groupId: String,
        days: Int = 30
    ): HeatHistoryResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getHeatHistory("", groupId, days)
    }

    // --- Smart Reminders ---

    suspend fun getAllReminderPreferences(accessToken: String): GetAllReminderPreferencesResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getAllReminderPreferences("")
    }

    suspend fun getGoalReminderPreferences(
        accessToken: String,
        goalId: String
    ): GoalReminderPreferencesResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.getGoalReminderPreferences("", goalId)
    }

    suspend fun updateGoalReminderPreferences(
        accessToken: String,
        goalId: String,
        enabled: Boolean? = null,
        mode: String? = null,
        fixedHour: Int? = null,
        aggressiveness: String? = null,
        quietHoursStart: Int? = null,
        quietHoursEnd: Int? = null
    ): UpdateGoalReminderPreferencesResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.updateGoalReminderPreferences(
            "", goalId, enabled, mode, fixedHour, aggressiveness, quietHoursStart, quietHoursEnd
        )
    }

    suspend fun recalculateGoalPattern(
        accessToken: String,
        goalId: String,
        userTimezone: String
    ): RecalculateGoalPatternResponse {
        storeTokenIfPresent(accessToken)
        return ApiClient.recalculateGoalPattern("", goalId, userTimezone)
    }

    // --- Internal Jobs ---

    // No storeTokenIfPresent call — internal job uses x-internal-job-key, not Bearer auth
    suspend fun triggerWeeklyRecapJob(
        internalJobKey: String,
        forceGroupId: String? = null,
        forceWeekEnd: String? = null
    ): WeeklyRecapJobResponse {
        return ApiClient.triggerWeeklyRecapJob(internalJobKey, forceGroupId, forceWeekEnd)
    }

    // No storeTokenIfPresent call — internal job uses x-internal-job-key, not Bearer auth
    suspend fun triggerChallengeStatusUpdateJob(
        internalJobKey: String,
        forceNow: String? = null
    ): ChallengeStatusUpdateJobResponse {
        return ApiClient.triggerChallengeStatusUpdateJob(internalJobKey, forceNow)
    }
}
