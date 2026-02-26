package app.getpursue.data.crashlytics

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Manages the user's opt-in preference for crash reporting (NZ Privacy Act compliance).
 *
 * Defaults to false (opt-out). The preference is stored in SharedPreferences and
 * reflected immediately to the Crashlytics SDK.
 *
 * Consent tracking is per-user: if a different account signs in on the same device,
 * they will be asked again regardless of the previous user's answer.
 */
object CrashlyticsPreference {

    private const val PREFS_NAME = "pursue_privacy_prefs"
    private const val KEY_ENABLED = "crashlytics_collection_enabled"
    private const val KEY_CURRENT_USER_ID = "current_user_id"
    private const val KEY_CONSENT_ASKED_FOR_USER = "crash_consent_asked_for_user"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.setCrashlyticsCollectionEnabled(enabled)
        if (!enabled) {
            crashlytics.setUserId("")
        }
    }

    /**
     * Records the current signed-in user. Must be called after every successful
     * sign-in or registration so that [isConsentAsked] and [markConsentAsked]
     * can track consent per user rather than per device.
     */
    fun setCurrentUser(context: Context, userId: String) {
        prefs(context).edit().putString(KEY_CURRENT_USER_ID, userId).apply()
    }

    /**
     * Returns true only if the consent dialog has already been shown to the
     * currently signed-in user on this device.
     */
    fun isConsentAsked(context: Context): Boolean {
        val p = prefs(context)
        val currentUser = p.getString(KEY_CURRENT_USER_ID, null) ?: return false
        return currentUser == p.getString(KEY_CONSENT_ASKED_FOR_USER, null)
    }

    /**
     * Records that the consent dialog has been shown to the current user.
     * No-op if the current user ID is not yet known.
     */
    fun markConsentAsked(context: Context) {
        val currentUser = prefs(context).getString(KEY_CURRENT_USER_ID, null) ?: return
        prefs(context).edit().putString(KEY_CONSENT_ASKED_FOR_USER, currentUser).apply()
    }
}
