package app.getpursue.data.crashlytics

import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Thin wrapper for Crashlytics breadcrumb logging.
 *
 * Breadcrumbs appear in the "Logs" section of a Crashlytics crash report,
 * providing a trail of events leading up to a crash.
 */
object CrashlyticsLogger {

    fun log(event: String) = FirebaseCrashlytics.getInstance().log(event)
}
