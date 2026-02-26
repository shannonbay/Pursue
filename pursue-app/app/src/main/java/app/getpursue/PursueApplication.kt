package app.getpursue

import android.app.Application
import app.getpursue.data.crashlytics.CrashlyticsPreference
import app.getpursue.data.network.ApiClient
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Application class for Pursue app.
 *
 * Initializes ApiClient with context for token management.
 */
class PursueApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize API client (buildClient uses applicationContext to avoid leaking)
        ApiClient.initialize(ApiClient.buildClient(this))
        setupCrashlytics()
    }

    private fun setupCrashlytics() {
        try {
            val crashlytics = FirebaseCrashlytics.getInstance()
            val enabled = CrashlyticsPreference.isEnabled(this)
            crashlytics.setCrashlyticsCollectionEnabled(enabled)
            if (enabled) {
                crashlytics.setCustomKey("app_version_name", BuildConfig.VERSION_NAME)
                crashlytics.setCustomKey("app_version_code", BuildConfig.VERSION_CODE.toString())
                crashlytics.setCustomKey("build_type", BuildConfig.BUILD_TYPE)
            }
        } catch (e: IllegalStateException) {
            // Firebase not initialized — expected in Robolectric test environment
        }
    }
}
