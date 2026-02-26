package app.getpursue.data.crashlytics

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Sets structured custom keys on Crashlytics crash reports.
 *
 * Keys appear in the "Keys" section of a Crashlytics crash report and
 * provide context about the app state at the time of the crash.
 */
object CrashlyticsKeys {

    fun setActiveScreen(name: String) {
        FirebaseCrashlytics.getInstance().setCustomKey("active_screen", name)
    }

    fun setActiveGroup(groupId: String?) {
        FirebaseCrashlytics.getInstance().setCustomKey("active_group_id", groupId ?: "")
    }

    fun setPremiumStatus(isPremium: Boolean) {
        FirebaseCrashlytics.getInstance().setCustomKey("is_premium", isPremium)
    }
}
