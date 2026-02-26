package app.getpursue.data.crashlytics

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Wrapper for recording non-fatal exceptions to Crashlytics.
 *
 * Use for errors that are handled gracefully but should still be tracked
 * (e.g., unexpected API response shapes, recoverable failures).
 */
object CrashlyticsReporter {

    fun reportNonFatal(throwable: Throwable, context: String? = null) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        if (context != null) {
            crashlytics.log("non_fatal_context: $context")
        }
        crashlytics.recordException(throwable)
    }
}
