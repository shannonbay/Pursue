package app.getpursue.models

/**
 * Data models for Group Detail API responses.
 */
data class GroupDetailResponse(
    val id: String,
    val name: String,
    val description: String?,
    val icon_emoji: String?,
    val icon_color: String?,
    val has_icon: Boolean,
    val creator_user_id: String,
    val member_count: Int,
    val created_at: String, // ISO 8601 timestamp
    val user_role: String, // "creator", "admin", or "member"
    val heat: GroupHeat? = null // Group heat momentum data (with extended GCR values)
)
