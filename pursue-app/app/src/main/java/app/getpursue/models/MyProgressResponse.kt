package app.getpursue.models

/**
 * Data models for My Progress API responses.
 */
data class MyProgressResponse(
    val streak: StreakData,
    val weekly_activity: WeeklyActivity,
    val heatmap: HeatmapData,
    val goal_breakdown: List<GoalBreakdown>
)

data class StreakData(
    val current_streak_days: Int,
    val longest_streak_days: Int,
    val streak_goal_days: Int? // Optional goal (e.g., 30 days)
)

data class WeeklyActivity(
    val week_start_date: String, // ISO date (Monday)
    val completion_data: List<DayCompletion> // 7 days
)

data class DayCompletion(
    val date: String, // ISO date
    val completed: Boolean,
    val completion_percent: Int // 0-100
)

data class HeatmapData(
    val start_date: String, // ISO date (30 days ago)
    val end_date: String, // ISO date (today)
    val days: List<HeatmapDay>
)

data class HeatmapDay(
    val date: String, // ISO date
    val completion_percent: Int // 0-100
)

data class GoalBreakdown(
    val goal_id: String,
    val goal_title: String,
    val completed_count: Int,
    val total_count: Int,
    val completion_percent: Int // 0-100
)
