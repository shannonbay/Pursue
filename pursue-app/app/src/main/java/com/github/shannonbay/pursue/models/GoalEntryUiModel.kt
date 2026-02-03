package com.github.shannonbay.pursue.models

/**
 * UI model for a progress entry in the Goal Detail screen.
 */
data class GoalEntryUiModel(
    val id: String,
    val user_id: String,
    val display_name: String,
    val is_current_user: Boolean,
    val value: Double,
    val note: String?,
    val period_start: String, // ISO date (YYYY-MM-DD)
    val logged_at: String, // ISO 8601 timestamp
    val date_header: String?, // Formatted date header (e.g., "Mon Jan 27") - null if same as previous entry
    val formatted_timestamp: String // Formatted timestamp (e.g., "Logged at 6:30 AM")
)
