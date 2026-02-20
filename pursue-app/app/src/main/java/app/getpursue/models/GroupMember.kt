package app.getpursue.models

/**
 * Data models for Group Members API responses.
 */
data class GroupMembersResponse(
    val members: List<GroupMember>
)

data class GroupMember(
    val user_id: String,
    val display_name: String,
    val has_avatar: Boolean,
    val role: String, // "creator", "admin", or "member"
    val joined_at: String // ISO 8601 timestamp
)
