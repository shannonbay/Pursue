package app.getpursue.models

/**
 * Data models for Group Goals API responses.
 * 
 * Response from GET /api/groups/:group_id/goals with include_progress=true
 */
data class GroupGoalsResponse(
    val goals: List<GroupGoalResponse>,
    val total: Int
)

/**
 * Goal data with optional progress information.
 */
data class GroupGoalResponse(
    val id: String,
    val group_id: String,
    val title: String,
    val description: String?,
    val cadence: String, // "daily", "weekly", "monthly", "yearly"
    val metric_type: String, // "binary", "numeric", "duration", or "journal"
    val target_value: Double?,
    val unit: String?,
    val log_title_prompt: String? = null, // For journal goals
    val created_by_user_id: String,
    val active_days: List<Int>? = null, // Array of active day numbers (0=Sun..6=Sat), null = every day
    val created_at: String, // ISO 8601 timestamp
    val archived_at: String?, // ISO 8601 timestamp or null
    val current_period_progress: CurrentPeriodProgress? = null // Present when include_progress=true
)

/**
 * Current period progress data for a goal.
 */
data class CurrentPeriodProgress(
    val start_date: String, // ISO 8601 timestamp
    val end_date: String, // ISO 8601 timestamp
    val period_type: String, // "daily", "weekly", "monthly", "yearly"
    val user_progress: UserProgress,
    val member_progress: List<MemberProgressResponse>
)

/**
 * Current user's progress for the current period.
 */
data class UserProgress(
    val completed: Double, // Count for binary, sum for numeric
    val total: Double, // Target value
    val percentage: Int, // 0-100
    val entries: List<ProgressEntry>
)

/**
 * Individual progress entry.
 */
data class ProgressEntry(
    val date: String, // ISO date (YYYY-MM-DD)
    val value: Double // 1 for binary, actual value for numeric
)

/**
 * Member progress information.
 */
data class MemberProgressResponse(
    val user_id: String,
    val display_name: String,
    val avatar_url: String?,
    val completed: Double, // Count for binary, sum for numeric
    val percentage: Int // 0-100
)
