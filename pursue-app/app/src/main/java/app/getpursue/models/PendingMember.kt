package app.getpursue.models

/**
 * Pending join request (Backend spec: GET /api/groups/:group_id/members/pending).
 */
data class PendingMembersResponse(
    val pending_members: List<PendingMember>
)

data class PendingMember(
    val user_id: String,
    val display_name: String,
    val has_avatar: Boolean,
    val requested_at: String // ISO 8601 timestamp
)
