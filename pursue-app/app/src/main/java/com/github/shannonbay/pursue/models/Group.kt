package com.github.shannonbay.pursue.models

/**
 * Data models for Group-related API responses.
 */
data class GroupsResponse(
    val groups: List<Group>,
    val total: Int
)

data class Group(
    val id: String,
    val name: String,
    val description: String?,
    val icon_emoji: String?,
    val icon_color: String? = null, // hex color for emoji background (e.g. "#1976D2")
    val has_icon: Boolean, // true if group has an icon image (BYTEA), false otherwise
    val member_count: Int,
    val role: String, // "creator", "admin", or "member"
    val joined_at: String, // ISO 8601 timestamp
    val updated_at: String? // ISO 8601 timestamp for cache invalidation
)
