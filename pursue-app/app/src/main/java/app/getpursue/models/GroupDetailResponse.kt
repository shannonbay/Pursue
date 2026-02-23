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
    val icon_url: String? = null,
    val has_icon: Boolean,
    val creator_user_id: String,
    val member_count: Int,
    val created_at: String, // ISO 8601 timestamp
    val user_role: String, // "creator", "admin", or "member"
    val heat: GroupHeat? = null, // Group heat momentum data (with extended GCR values)
    val is_challenge: Boolean = false,
    val challenge_start_date: String? = null,
    val challenge_end_date: String? = null,
    val challenge_status: String? = null,
    val template_id: String? = null,
    val visibility: String? = null,   // "public" | "private"
    val category: String? = null,
    val spot_limit: Int? = null,
    val comm_platform: String? = null, // "discord" | "whatsapp" | "telegram" | null
    val comm_link: String? = null
)
