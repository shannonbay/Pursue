package com.github.shannonbay.pursue.models

/**
 * UI model for Goal Detail screen.
 * Combines goal data with progress information for display.
 */
data class GoalDetailUiModel(
    val goal: GoalDetailGoal,
    val currentPeriodProgress: CurrentPeriodProgressUiModel?,
    val streak: StreakUiModel?
)

data class GoalDetailGoal(
    val id: String,
    val group_id: String,
    val title: String,
    val description: String?,
    val cadence: String, // "daily", "weekly", "monthly", "yearly"
    val metric_type: String, // "binary", "numeric", "duration"
    val target_value: Double?,
    val unit: String?,
    val created_at: String
)

data class CurrentPeriodProgressUiModel(
    val start_date: String,
    val end_date: String,
    val period_type: String,
    val user_completed: Double,
    val user_total: Double,
    val user_percentage: Int,
    val display_text: String // e.g., "2 of 3 complete" or "35/50 pages (70%)"
)

data class StreakUiModel(
    val current_streak: Int,
    val best_streak: Int,
    val unit: String // "days" or "weeks"
)
