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

    // Invite
    const val INVITE_LINK_GENERATED    = "invite_link_generated"
    const val INVITE_LINK_SHARED       = "invite_link_shared"
    const val INVITE_LINK_SHARE_TARGET = "invite_link_share_target"
    const val INVITE_LINK_OPENED       = "invite_link_opened"

    // Milestone cards
    const val MILESTONE_CARD_VIEWED             = "milestone_card_viewed"
    const val MILESTONE_CARD_SHARED             = "milestone_card_shared"
    const val MILESTONE_CARD_SHARE_TARGET       = "milestone_card_share_target"
    const val MILESTONE_CARD_SAVED              = "milestone_card_saved"
    const val MILESTONE_CARD_INSTAGRAM_FALLBACK = "milestone_card_instagram_fallback"
    const val CHALLENGE_CARD_VIEWED             = "challenge_card_viewed"
    const val CHALLENGE_CARD_SHARED             = "challenge_card_shared"

    // Referral
    const val REFERRAL_ATTRIBUTED = "referral_attributed"

    // Orientation
    const val ORIENTATION_COMPLETED = "orientation_completed"

    // Daily Pulse
    const val DAILY_PULSE_VIEWED        = "daily_pulse_viewed"
    const val DAILY_PULSE_AVATAR_TAPPED = "daily_pulse_avatar_tapped"
    const val DAILY_PULSE_NUDGE_SENT    = "daily_pulse_nudge_sent"
    const val DAILY_PULSE_GRID_OPENED   = "daily_pulse_grid_opened"

    // Param name constants
    object Param {
        const val CADENCE         = "cadence"
        const val METRIC_TYPE     = "metric_type"
        const val VISIBILITY      = "visibility"
        const val CATEGORY        = "category"
        const val GROUP_ID        = "group_id"
        const val TAB             = "tab"
        const val SORT            = "sort"
        const val QUERY           = "query"
        const val STATUS          = "status"
        const val SOURCE          = "source"
        const val METHOD          = "method"
        const val TARGET_PACKAGE  = "target_package"
        const val MILESTONE_TYPE  = "milestone_type"
        const val CARD_TYPE       = "card_type"
        const val NOTIFICATION_ID = "notification_id"
        const val COMPLETION_RATE = "completion_rate"
        const val INVITE_CODE        = "invite_code"
        const val ORIENTATION_OUTCOME = "orientation_outcome"
    }

    // Source channel constants (used with Param.SOURCE)
    object Source {
        const val DISCOVER      = "discover"
        const val INVITE_QR     = "invite_qr"
        const val INVITE_MANUAL = "invite_manual"
        const val ONBOARDING    = "onboarding"
        const val INVITE_LINK   = "invite_link"
    }

    // Orientation outcome constants (used with Param.ORIENTATION_OUTCOME)
    object OrientationOutcome {
        const val JOINED_STEP_1         = "joined_step_1"
        const val JOIN_REQUESTED_STEP_2 = "join_requested_step_2"
        const val CHALLENGE_STEP_3      = "challenge_step_3"
        const val GROUP_CREATED_STEP_4  = "group_created_step_4"
        const val SKIPPED_ALL           = "skipped_all"
    }

    // Share method constants (used with Param.METHOD)
    object Method {
        const val CLIPBOARD          = "clipboard"
        const val SHARE_SHEET        = "share_sheet"
        const val QR_SHARE           = "qr_share"
        const val INSTAGRAM          = "instagram"
        const val INSTAGRAM_FALLBACK = "instagram_fallback"
    }
}
