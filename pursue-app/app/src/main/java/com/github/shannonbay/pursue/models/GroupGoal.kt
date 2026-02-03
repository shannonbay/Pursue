package com.github.shannonbay.pursue.models

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
    val metric_type: String, // "binary" or "numeric"
    val target_value: Double?, // For numeric goals
    val unit: String? = null, // Unit for numeric goals (e.g., "miles", "pages")
    val created_at: String, // ISO 8601 timestamp
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
