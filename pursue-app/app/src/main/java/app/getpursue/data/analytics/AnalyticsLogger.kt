package app.getpursue.data.analytics

import android.content.Context
import android.os.Bundle
import com.google.firebase.analytics.FirebaseAnalytics

/**
 * Thin wrapper for Firebase Analytics event logging.
 *
 * All calls are guarded by [AnalyticsPreference.isEnabled] so no data
 * is sent unless the user has opted in via the Diagnostics toggle.
 *
 * Must be initialized via [initialize] in [app.getpursue.PursueApplication].
 */
object AnalyticsLogger {
    private var appContext: Context? = null

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun logEvent(event: String, params: Bundle? = null) {
        val ctx = appContext ?: return
        if (!AnalyticsPreference.isEnabled(ctx)) return
        try {
            FirebaseAnalytics.getInstance(ctx).logEvent(event, params)
        } catch (e: Exception) { /* non-critical */ }
    }

    fun setScreen(screenName: String) {
        val ctx = appContext ?: return
        if (!AnalyticsPreference.isEnabled(ctx)) return
        try {
            val bundle = Bundle().apply {
                putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            }
            FirebaseAnalytics.getInstance(ctx).logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
        } catch (e: Exception) { /* non-critical */ }
    }
}
