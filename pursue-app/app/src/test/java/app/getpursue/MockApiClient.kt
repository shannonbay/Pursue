package app.getpursue

import app.getpursue.data.network.ApiException
import app.getpursue.data.network.CreateGoalResponse
import app.getpursue.data.network.CreateGroupResponse
import app.getpursue.data.network.DeviceRegistrationResponse
import app.getpursue.data.network.GoogleSignInResponse
import app.getpursue.data.network.LoginResponse
import app.getpursue.data.network.RegistrationResponse
import app.getpursue.data.network.User
import app.getpursue.models.CurrentPeriodProgress
import app.getpursue.models.DayCompletion
import app.getpursue.models.GoalBreakdown
import app.getpursue.models.Group
import app.getpursue.models.GroupDetailResponse
import app.getpursue.models.GroupGoalResponse
import app.getpursue.models.GroupGoalsResponse
import app.getpursue.models.GroupsResponse
import app.getpursue.models.HeatmapData
import app.getpursue.models.HeatmapDay
import app.getpursue.models.MemberProgressResponse
import app.getpursue.models.MyProgressResponse
import app.getpursue.models.ProgressEntry
import app.getpursue.models.StreakData
import app.getpursue.models.TodayGoal
import app.getpursue.models.TodayGoalsResponse
import app.getpursue.models.TodayGroup
import app.getpursue.models.UserProgress
import app.getpursue.models.WeeklyActivity

/**
 * Helper object for creating mock API responses in tests.
 */
object MockApiClient {
    
    /**
     * Create a successful registration response.
     */
    fun createSuccessRegistrationResponse(
        accessToken: String = "test_access_token_${System.currentTimeMillis()}",
        refreshToken: String = "test_refresh_token_${System.currentTimeMillis()}",
        userId: String = "user_123",
        email: String = "test@example.com",
        displayName: String = "Test User"
    ): RegistrationResponse {
        return RegistrationResponse(
            access_token = accessToken,
            refresh_token = refreshToken,
            user = User(userId, email, displayName, has_avatar = false, updated_at = null)
        )
    }
    
    /**
     * Create a successful login response.
     */
    fun createSuccessLoginResponse(
        accessToken: String = "test_access_token_${System.currentTimeMillis()}",
        refreshToken: String = "test_refresh_token_${System.currentTimeMillis()}",
        userId: String = "user_123",
        email: String = "test@example.com",
        displayName: String = "Test User"
    ): LoginResponse {
        return LoginResponse(
            access_token = accessToken,
            refresh_token = refreshToken,
            user = User(userId, email, displayName, has_avatar = false, updated_at = null)
        )
    }
    
    /**
     * Create a successful Google Sign-In response.
     */
    fun createSuccessGoogleSignInResponse(
        accessToken: String = "test_access_token_${System.currentTimeMillis()}",
        refreshToken: String = "test_refresh_token_${System.currentTimeMillis()}",
        isNewUser: Boolean = false,
        userId: String = "user_123",
        email: String = "test@example.com",
        displayName: String = "Test User",
        hasAvatar: Boolean = true
    ): GoogleSignInResponse {
        return GoogleSignInResponse(
            access_token = accessToken,
            refresh_token = refreshToken,
            is_new_user = isNewUser,
            user = User(userId, email, displayName, has_avatar = hasAvatar, updated_at = null)
        )
    }
    
    /**
     * Create a Google Sign-In response (convenience method with default values).
     */
    fun createGoogleSignInResponse(
        isNewUser: Boolean = false,
        accessToken: String = "test_access_token_${System.currentTimeMillis()}",
        refreshToken: String = "test_refresh_token_${System.currentTimeMillis()}"
    ): GoogleSignInResponse {
        return createSuccessGoogleSignInResponse(
            accessToken = accessToken,
            refreshToken = refreshToken,
            isNewUser = isNewUser
        )
    }
    
    /**
     * Create an ApiException with a specific error code and message.
     */
    fun createApiException(
        code: Int,
        message: String = "API error"
    ): ApiException {
        return ApiException(code, message)
    }
    
    /**
     * Create a 400 Bad Request exception.
     */
    fun createBadRequestException(message: String = "Invalid registration data"): ApiException {
        return createApiException(400, message)
    }
    
    /**
     * Create a 409 Conflict exception (email already exists).
     */
    fun createConflictException(message: String = "An account with this email already exists"): ApiException {
        return createApiException(409, message)
    }
    
    /**
     * Create a 500 Internal Server Error exception.
     */
    fun createServerErrorException(message: String = "Internal server error"): ApiException {
        return createApiException(500, message)
    }
    
    /**
     * Create a network exception (generic Exception for network errors).
     */
    fun createNetworkException(message: String = "Network error: Connection timeout"): Exception {
        return Exception(message)
    }
    
    /**
     * Create a successful device registration response.
     */
    fun createDeviceRegistrationResponse(
        deviceId: String = "device_123",
        deviceName: String = "Test Device",
        platform: String = "android"
    ): DeviceRegistrationResponse {
        return DeviceRegistrationResponse(deviceId, deviceName, platform)
    }

    /**
     * Create a successful groups response.
     */
    fun createGroupsResponse(
        groups: List<Group> = listOf(
            Group(
                id = "group_1",
                name = "Morning Runners",
                description = "Daily accountability for morning runs",
                icon_emoji = "🏃",
                has_icon = false,
                member_count = 8,
                role = "member",
                joined_at = "2026-01-15T08:00:00Z",
                updated_at = null
            ),
            Group(
                id = "group_2",
                name = "Book Club",
                description = "Reading goals",
                icon_emoji = "📚",
                has_icon = false,
                member_count = 12,
                role = "admin",
                joined_at = "2026-01-10T10:00:00Z",
                updated_at = null
            )
        ),
        total: Int = groups.size
    ): GroupsResponse {
        return GroupsResponse(groups, total)
    }

    /**
     * Create an empty groups response.
     */
    fun createEmptyGroupsResponse(): GroupsResponse {
        return GroupsResponse(groups = emptyList(), total = 0)
    }

    /**
     * Create a successful today's goals response.
     */
    fun createTodayGoalsResponse(
        date: String = "2026-01-16",
        overallCompletionPercent: Int = 40,
        groups: List<TodayGroup> = listOf(
            TodayGroup(
                group_id = "group_1",
                group_name = "Morning Runners",
                has_icon = false,
                icon_emoji = "🏃",
                completed_count = 2,
                total_count = 5,
                goals = listOf(
                    TodayGoal(
                        goal_id = "goal_1",
                        title = "30 min run",
                        completed = true,
                        progress_value = null,
                        target_value = null
                    ),
                    TodayGoal(
                        goal_id = "goal_2",
                        title = "Meditate 10 min",
                        completed = false,
                        progress_value = null,
                        target_value = null
                    )
                )
            ),
            TodayGroup(
                group_id = "group_2",
                group_name = "Book Club",
                has_icon = false,
                icon_emoji = "📚",
                completed_count = 0,
                total_count = 1,
                goals = listOf(
                    TodayGoal(
                        goal_id = "goal_3",
                        title = "Read 30 pages",
                        completed = false,
                        progress_value = null,
                        target_value = null
                    )
                )
            )
        )
    ): TodayGoalsResponse {
        return TodayGoalsResponse(
            date = date,
            overall_completion_percent = overallCompletionPercent,
            groups = groups
        )
    }

    /**
     * Create an empty today's goals response.
     */
    fun createEmptyTodayGoalsResponse(date: String = "2026-01-16"): TodayGoalsResponse {
        return TodayGoalsResponse(
            date = date,
            overall_completion_percent = 0,
            groups = emptyList()
        )
    }

    /**
     * Create a successful my progress response.
     */
    fun createMyProgressResponse(
        currentStreakDays: Int = 7,
        longestStreakDays: Int = 14,
        streakGoalDays: Int? = 30,
        weeklyActivity: List<DayCompletion> = listOf(
            DayCompletion("2026-01-10", true, 100),
            DayCompletion("2026-01-11", true, 100),
            DayCompletion("2026-01-12", true, 100),
            DayCompletion("2026-01-13", false, 0),
            DayCompletion("2026-01-14", true, 100),
            DayCompletion("2026-01-15", false, 0),
            DayCompletion("2026-01-16", true, 100)
        ),
        heatmapDays: List<HeatmapDay> = (1..30).map { day ->
            HeatmapDay(
                date = "2026-01-${String.format("%02d", day)}",
                completion_percent = if (day % 2 == 0) 80 else 50
            )
        },
        goalBreakdown: List<GoalBreakdown> = listOf(
            GoalBreakdown(
                goal_id = "goal_1",
                goal_title = "30 min run",
                completed_count = 24,
                total_count = 30,
                completion_percent = 80
            ),
            GoalBreakdown(
                goal_id = "goal_2",
                goal_title = "Read 50 pages",
                completed_count = 18,
                total_count = 30,
                completion_percent = 60
            )
        )
    ): MyProgressResponse {
        return MyProgressResponse(
            streak = StreakData(
                current_streak_days = currentStreakDays,
                longest_streak_days = longestStreakDays,
                streak_goal_days = streakGoalDays
            ),
            weekly_activity = WeeklyActivity(
                week_start_date = "2026-01-10",
                completion_data = weeklyActivity
            ),
            heatmap = HeatmapData(
                start_date = "2025-12-17",
                end_date = "2026-01-16",
                days = heatmapDays
            ),
            goal_breakdown = goalBreakdown
        )
    }

    /**
     * Create an empty my progress response (no progress data).
     */
    fun createEmptyMyProgressResponse(): MyProgressResponse {
        return MyProgressResponse(
            streak = StreakData(0, 0, null),
            weekly_activity = WeeklyActivity(
                week_start_date = "2026-01-10",
                completion_data = List(7) { DayCompletion("2026-01-${10 + it}", false, 0) }
            ),
            heatmap = HeatmapData(
                start_date = "2025-12-17",
                end_date = "2026-01-16",
                days = List(30) { HeatmapDay("2025-12-${17 + it}", 0) }
            ),
            goal_breakdown = emptyList()
        )
    }

    /**
     * Create a successful create group response.
     */
    fun createCreateGroupResponse(
        groupId: String = "group_${System.currentTimeMillis()}",
        name: String = "Test Group",
        description: String? = "Test description",
        iconEmoji: String? = "🏃",
        iconColor: String? = "#1976D2",
        hasIcon: Boolean = false,
        creatorUserId: String = "user_123",
        memberCount: Int = 1,
        createdAt: String = "2026-01-20T10:00:00Z"
    ): CreateGroupResponse {
        return CreateGroupResponse(
            id = groupId,
            name = name,
            description = description,
            icon_emoji = iconEmoji,
            icon_color = iconColor,
            has_icon = hasIcon,
            creator_user_id = creatorUserId,
            member_count = memberCount,
            created_at = createdAt
        )
    }

    /**
     * Create a successful create goal response.
     */
    fun createCreateGoalResponse(
        goalId: String = "goal_${System.currentTimeMillis()}",
        groupId: String = "group_123",
        title: String = "Test Goal",
        description: String? = null,
        cadence: String = "weekly",
        metricType: String = "binary",
        targetValue: Double? = null,
        unit: String? = null,
        createdByUserId: String = "user_123",
        createdAt: String = "2026-01-20T10:00:00Z"
    ): CreateGoalResponse {
        return CreateGoalResponse(
            id = goalId,
            group_id = groupId,
            title = title,
            description = description,
            cadence = cadence,
            metric_type = metricType,
            target_value = targetValue,
            unit = unit,
            created_by_user_id = createdByUserId,
            created_at = createdAt,
            archived_at = null
        )
    }

    /**
     * Create a successful group detail response.
     */
    fun createGroupDetailResponse(
        groupId: String = "group_${System.currentTimeMillis()}",
        name: String = "Test Group",
        description: String? = "Test description",
        iconEmoji: String? = "🏃",
        iconColor: String? = "#1976D2",
        hasIcon: Boolean = false,
        creatorUserId: String = "creator_123",
        memberCount: Int = 5,
        createdAt: String = "2026-01-01T00:00:00Z",
        userRole: String = "member" // "creator", "admin", or "member"
    ): GroupDetailResponse {
        return GroupDetailResponse(
            id = groupId,
            name = name,
            description = description,
            icon_emoji = iconEmoji,
            icon_color = iconColor,
            has_icon = hasIcon,
            creator_user_id = creatorUserId,
            member_count = memberCount,
            created_at = createdAt,
            user_role = userRole
        )
    }

    // ========== Group Goals Response Helpers ==========

    /**
     * Create a progress entry.
     */
    fun createProgressEntry(
        date: String = "2026-01-20",
        value: Double = 1.0
    ): ProgressEntry {
        return ProgressEntry(date = date, value = value)
    }

    /**
     * Create user progress data.
     */
    fun createUserProgress(
        completed: Double = 2.0,
        total: Double = 3.0,
        percentage: Int = 67,
        entries: List<ProgressEntry> = listOf(
            createProgressEntry("2026-01-20", 1.0),
            createProgressEntry("2026-01-22", 1.0)
        )
    ): UserProgress {
        return UserProgress(
            completed = completed,
            total = total,
            percentage = percentage,
            entries = entries
        )
    }

    /**
     * Create member progress response.
     */
    fun createMemberProgressResponse(
        userId: String = "user_${System.currentTimeMillis()}",
        displayName: String = "Test User",
        avatarUrl: String? = null,
        completed: Double = 2.0,
        percentage: Int = 67
    ): MemberProgressResponse {
        return MemberProgressResponse(
            user_id = userId,
            display_name = displayName,
            avatar_url = avatarUrl,
            completed = completed,
            percentage = percentage
        )
    }

    /**
     * Create current period progress data.
     */
    fun createCurrentPeriodProgress(
        startDate: String = "2026-01-20T00:00:00Z",
        endDate: String = "2026-01-26T23:59:59Z",
        periodType: String = "weekly",
        userProgress: UserProgress = createUserProgress(),
        memberProgress: List<MemberProgressResponse> = listOf(
            createMemberProgressResponse("user_1", "Shannon", completed = 2.0, percentage = 67),
            createMemberProgressResponse("user_2", "Alex", completed = 3.0, percentage = 100)
        )
    ): CurrentPeriodProgress {
        return CurrentPeriodProgress(
            start_date = startDate,
            end_date = endDate,
            period_type = periodType,
            user_progress = userProgress,
            member_progress = memberProgress
        )
    }

    /**
     * Create a group goal response.
     */
    fun createGroupGoalResponse(
        goalId: String = "goal_${System.currentTimeMillis()}",
        groupId: String = "group_123",
        title: String = "Test Goal",
        description: String? = null,
        cadence: String = "weekly",
        metricType: String = "binary",
        targetValue: Double? = 3.0,
        unit: String? = null,
        createdByUserId: String = "user_123",
        createdAt: String = "2026-01-16T10:00:00Z",
        archivedAt: String? = null,
        currentPeriodProgress: CurrentPeriodProgress? = null
    ): GroupGoalResponse {
        return GroupGoalResponse(
            id = goalId,
            group_id = groupId,
            title = title,
            description = description,
            cadence = cadence,
            metric_type = metricType,
            target_value = targetValue,
            unit = unit,
            created_by_user_id = createdByUserId,
            created_at = createdAt,
            archived_at = archivedAt,
            current_period_progress = currentPeriodProgress
        )
    }

    /**
     * Create a successful group goals response.
     */
    fun createGroupGoalsResponse(
        goals: List<GroupGoalResponse> = listOf(
            createGroupGoalResponse(
                goalId = "goal_1",
                title = "Run 3x per week",
                cadence = "weekly",
                metricType = "binary",
                targetValue = 3.0,
                currentPeriodProgress = createCurrentPeriodProgress(
                    userProgress = createUserProgress(completed = 2.0, total = 3.0, percentage = 67)
                )
            ),
            createGroupGoalResponse(
                goalId = "goal_2",
                title = "Read 50 pages",
                cadence = "weekly",
                metricType = "numeric",
                targetValue = 50.0,
                unit = "pages",
                currentPeriodProgress = createCurrentPeriodProgress(
                    userProgress = createUserProgress(
                        completed = 35.0,
                        total = 50.0,
                        percentage = 70,
                        entries = listOf(
                            createProgressEntry("2026-01-20", 15.0),
                            createProgressEntry("2026-01-22", 20.0)
                        )
                    )
                )
            )
        ),
        total: Int = goals.size
    ): GroupGoalsResponse {
        return GroupGoalsResponse(goals = goals, total = total)
    }

    /**
     * Create an empty group goals response.
     */
    fun createEmptyGroupGoalsResponse(): GroupGoalsResponse {
        return GroupGoalsResponse(goals = emptyList(), total = 0)
    }

    /**
     * Create a group goals response with binary goal (daily cadence).
     */
    fun createGroupGoalsResponseWithBinaryDaily(
        completed: Boolean = true
    ): GroupGoalsResponse {
        val userProgress = if (completed) {
            createUserProgress(completed = 1.0, total = 1.0, percentage = 100)
        } else {
            createUserProgress(completed = 0.0, total = 1.0, percentage = 0)
        }
        val goal = createGroupGoalResponse(
            goalId = "goal_daily_1",
            title = "Daily Goal",
            cadence = "daily",
            metricType = "binary",
            targetValue = 1.0,
            currentPeriodProgress = createCurrentPeriodProgress(
                periodType = "daily",
                userProgress = userProgress
            )
        )
        return GroupGoalsResponse(goals = listOf(goal), total = 1)
    }

    /**
     * Create a group goals response with numeric goal (weekly cadence).
     */
    fun createGroupGoalsResponseWithNumericWeekly(
        progressValue: Double = 35.0,
        targetValue: Double = 50.0
    ): GroupGoalsResponse {
        val percentage = ((progressValue / targetValue) * 100).toInt().coerceIn(0, 100)
        val goal = createGroupGoalResponse(
            goalId = "goal_weekly_1",
            title = "Read 50 pages",
            cadence = "weekly",
            metricType = "numeric",
            targetValue = targetValue,
            unit = "pages",
            currentPeriodProgress = createCurrentPeriodProgress(
                periodType = "weekly",
                userProgress = createUserProgress(
                    completed = progressValue,
                    total = targetValue,
                    percentage = percentage
                )
            )
        )
        return GroupGoalsResponse(goals = listOf(goal), total = 1)
    }
}
