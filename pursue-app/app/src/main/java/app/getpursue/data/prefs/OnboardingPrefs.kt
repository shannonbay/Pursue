package app.getpursue.data.prefs

import android.content.Context
import android.content.SharedPreferences

/**
 * Manages onboarding and "first-time" flags for the user interface.
 */
class OnboardingPrefs private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Whether the "tap to log" tooltip has been shown.
     */
    var hasShownTapToLogTooltip: Boolean
        get() = prefs.getBoolean(KEY_HAS_SHOWN_TAP_TO_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_SHOWN_TAP_TO_LOG, value).apply()

    companion object {
        private const val PREFS_NAME = "pursue_onboarding_prefs"
        private const val KEY_HAS_SHOWN_TAP_TO_LOG = "has_shown_tap_to_log"

        @Volatile
        private var INSTANCE: OnboardingPrefs? = null

        fun getInstance(context: Context): OnboardingPrefs {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: OnboardingPrefs(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
