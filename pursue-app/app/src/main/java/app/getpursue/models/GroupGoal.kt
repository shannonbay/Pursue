package app.getpursue.models

/**
 * Data models for Group Goals.
 * 
 * Note: Currently there's no dedicated endpoint to get all goals for a group.
 * Goals may need to be fetched individually or via a future endpoint.
 * For now, this model represents a goal with progress information.
 */
data class GroupGoal(
    val id: String,
    val group_id: String,
    val title: String,
    val description: String?,
    val cadence: String, // "daily", "weekly", "monthly", "yearly"
    val metric_type: String, // "binary", "numeric", "duration", or "journal"
    val target_value: Double?, // For numeric goals
    val unit: String? = null, // Unit for numeric goals (e.g., "miles", "pages")
    val active_days: List<Int>? = null, // Active day numbers (0=Sun..6=Sat), null = every day
    val created_at: String, // ISO 8601 timestamp
    val log_title_prompt: String? = null, // For journal goals: optional prompt shown in the log dialog
    // Progress information (may come from separate endpoint)
    val completed: Boolean = false, // For binary goals
    val progress_value: Double? = null, // For numeric goals
    val member_progress: List<MemberProgress> = emptyList() // Member completion status
)

data class MemberProgress(
    val user_id: String,
    val display_name: String,
    val completed: Boolean, // For binary goals
    val progress_value: Double? = null // For numeric goals
)
