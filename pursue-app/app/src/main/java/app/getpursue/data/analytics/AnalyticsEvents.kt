package app.getpursue.data.analytics

/**
 * Custom event name and param name constants for Firebase Analytics.
 *
 * Firebase standard events ([com.google.firebase.analytics.FirebaseAnalytics.Event.LOGIN],
 * [com.google.firebase.analytics.FirebaseAnalytics.Event.SIGN_UP],
 * [com.google.firebase.analytics.FirebaseAnalytics.Event.SCREEN_VIEW]) are used
 * directly at call sites.
 *
 * All custom event names are snake_case and ≤40 characters.
 */
object AnalyticsEvents {

    // Screen names (used with AnalyticsLogger.setScreen())
    const val SCREEN_HOME           = "home"
    const val SCREEN_TODAY          = "today"
    const val SCREEN_DISCOVER       = "discover"
    const val SCREEN_PROFILE        = "profile"
    const val SCREEN_GROUP_GOALS    = "group_goals"
    const val SCREEN_GROUP_MEMBERS  = "group_members"
    const val SCREEN_GROUP_ACTIVITY = "group_activity"

    // Groups
    const val GROUP_CREATED        = "group_created"
    const val GROUP_JOINED         = "group_joined"
    const val GROUP_JOIN_REQUESTED = "group_join_requested"

    // Goals
    const val GOAL_CREATED = "goal_created"

    // Progress
    const val PROGRESS_LOGGED         = "progress_logged"
    const val PROGRESS_DELETED        = "progress_deleted"
    const val PROGRESS_PHOTO_UPLOADED = "progress_photo_uploaded"
    const val PROGRESS_PHOTO_FAILED   = "progress_photo_failed"

    // Discover
    const val DISCOVER_SEARCH          = "discover_search"
    const val DISCOVER_FILTER_CATEGORY = "discover_filter_category"
    const val DISCOVER_SORT_CHANGED    = "discover_sort_changed"
    const val DISCOVER_GROUP_TAPPED    = "discover_group_tapped"

    // Param name constants
    object Param {
        const val CADENCE      = "cadence"
        const val METRIC_TYPE  = "metric_type"
        const val VISIBILITY   = "visibility"
        const val CATEGORY     = "category"
        const val GROUP_ID     = "group_id"
        const val TAB          = "tab"
        const val SORT         = "sort"
        const val QUERY        = "query"
        const val STATUS       = "status"
    }
}
