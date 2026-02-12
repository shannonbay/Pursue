package app.getpursue.models

/**
 * Data models for Group Activity API responses.
 */
data class GroupActivityResponse(
    val activities: List<GroupActivity>,
    val total: Int
)

data class GroupActivity(
    val id: String?,
    val activity_type: String, // "progress_logged", "member_joined", "member_left", "member_promoted", "member_removed", "goal_added", "goal_archived", "group_renamed"
    val user: ActivityUser?,
    val metadata: Map<String, Any>?, // Activity-specific metadata (goal_id, goal_title, value, etc.)
    val photo: ActivityPhoto? = null, // Progress photo for progress_logged activities
    val created_at: String, // ISO 8601 timestamp
    val reactions: List<ActivityReaction>? = null, // Reaction counts per emoji
    val reaction_summary: ReactionSummary? = null // Top reactors summary
)

data class ActivityUser(
    val id: String,
    val display_name: String
)

data class ActivityPhoto(
    val id: String,
    val url: String,
    val width: Int,
    val height: Int,
    val expires_at: String // ISO 8601 timestamp
)

data class ActivityReaction(
    val emoji: String,
    val count: Int,
    val reactor_ids: List<String>,
    val current_user_reacted: Boolean
)

data class ReactionSummary(
    val total_count: Int,
    val top_reactors: List<TopReactor>
)

data class TopReactor(
    val user_id: String,
    val display_name: String
)
