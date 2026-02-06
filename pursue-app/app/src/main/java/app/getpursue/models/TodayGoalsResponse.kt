package app.getpursue.models

/**
 * Data models for Today's goals API responses.
 */
data class TodayGoalsResponse(
    val date: String, // ISO date (YYYY-MM-DD)
    val overall_completion_percent: Int, // 0-100
    val groups: List<TodayGroup>
)

data class TodayGroup(
    val group_id: String,
    val group_name: String,
    val has_icon: Boolean, // true if group has an icon image (BYTEA), false otherwise
    val icon_emoji: String?, // emoji fallback if no icon image
    val completed_count: Int,
    val total_count: Int,
    val goals: List<TodayGoal>
)

data class TodayGoal(
    val goal_id: String,
    val title: String,
    val completed: Boolean,
    val progress_value: Int?, // For numeric goals
    val target_value: Int?, // For numeric goals
    val metric_type: String? = null, // "binary" or "numeric"; if null, derive from target_value
    val unit: String? = null, // Unit for numeric goals (e.g. "miles", "pages")
    val icon_emoji: String? = null // Goal definition emoji for Today screen (spec 4.4)
)
