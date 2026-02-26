package app.getpursue.data.analytics

import android.content.Context
import app.getpursue.data.crashlytics.CrashlyticsPreference
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Manages the Firebase Analytics collection preference.
 *
 * Reads the same SharedPreferences key as [CrashlyticsPreference] — both features
 * are controlled by the single "Diagnostics" toggle in Profile.
 */
object AnalyticsPreference {

    fun isEnabled(context: Context): Boolean =
        CrashlyticsPreference.isEnabled(context)

    fun setCollectionEnabled(context: Context, enabled: Boolean) {
        try {
            FirebaseAnalytics.getInstance(context.applicationContext)
                .setAnalyticsCollectionEnabled(enabled)
        } catch (e: IllegalStateException) {
            // Firebase not initialized — Robolectric test environment
        }
    }
}
