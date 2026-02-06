package app.getpursue.models

/**
 * Minimal goal data required for the shared log-progress flow (card tap: log, remove, edit).
 * Used by GoalLogProgressHandler so both GoalsTabFragment (GroupGoal) and TodayFragment (TodayGoal)
 * can share the same handler.
 */
data class GoalForLogging(
    val id: String,
    val groupId: String,
    val title: String,
    val metricType: String, // "binary" or "numeric"
    val targetValue: Double?,
    val unit: String?,
    val completed: Boolean,
    val progressValue: Double?,
    val cadence: String = "daily" // For period calculation; Today screen is always daily
)
