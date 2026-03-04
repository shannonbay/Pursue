package app.getpursue.data.notifications

import app.getpursue.models.GroupMember

/**
 * In-memory singleton that tracks which user IDs have been observed as
 * `logged_this_period = true` during the current app session.
 *
 * Shared between TodayFragment and GroupDetailFragment so a single member
 * logging once does not trigger confetti twice (once per widget).
 */
object LoggedMembersTracker {
    private val seenLoggedIds = mutableSetOf<String>()

    /**
     * Returns the subset of [members] who are logged today but not yet seen.
     * Marks them as seen before returning.
     */
    fun checkAndMark(members: List<GroupMember>): Set<String> {
        val newlyLogged = members
            .filter { it.logged_this_period }
            .map { it.user_id }
            .filterNot { it in seenLoggedIds }
            .toSet()
        seenLoggedIds.addAll(newlyLogged)
        return newlyLogged
    }
}
