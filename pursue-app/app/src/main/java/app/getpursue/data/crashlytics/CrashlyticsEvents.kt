package app.getpursue.data.crashlytics

/**
 * Breadcrumb event constants for Crashlytics logging.
 *
 * Use with [CrashlyticsLogger.log] to record user actions and navigation
 * events that help diagnose crashes.
 */
object CrashlyticsEvents {

    // Navigation
    const val NAV_DASHBOARD = "nav:dashboard"
    const val NAV_GROUP_FEED = "nav:group_feed"
    const val NAV_CREATE_GOAL = "nav:create_goal"
    const val NAV_SETTINGS = "nav:settings"

    // Goal actions
    const val GOAL_SAVE_TAPPED = "goal:save_tapped"
    const val GOAL_DELETE_TAPPED = "goal:delete_tapped"

    // Progress logging
    const val PROGRESS_POST_TAPPED = "progress:post_tapped"
    const val PROGRESS_PHOTO_OPENED = "progress:photo_opened"
    const val PROGRESS_PHOTO_UPLOADED = "progress:photo_uploaded"
    const val PROGRESS_PHOTO_FAILED = "progress:photo_failed"

    // Group actions
    const val GROUP_JOIN_TAPPED = "group:join_tapped"
    const val GROUP_LEAVE_TAPPED = "group:leave_tapped"
    const val GROUP_INVITE_SENT = "group:invite_sent"

    // Firebase
    const val FCM_TOKEN_REFRESHED = "fcm:token_refreshed"

    // Subscriptions
    const val SUBSCRIPTION_CHECK_STARTED = "subscription:check_started"
    const val SUBSCRIPTION_CHECK_COMPLETED = "subscription:check_completed"

    // Auth
    const val USER_LOGGED_IN = "auth:user_logged_in"
    const val USER_LOGGED_OUT = "auth:user_logged_out"
}
