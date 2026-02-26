package app.getpursue.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.crashlytics.CrashlyticsPreference
import app.getpursue.data.network.ApiClient
import app.getpursue.ui.fragments.orientation.OrientationJoinFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Hosts the post-onboarding orientation flow for new users.
 *
 * A 3-step wizard (Join Group → Start Challenge → Create Group)
 * shown once after registration, without bottom navigation.
 */
class OrientationActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_orientation)

        // Mark that orientation has started (for app-killed-mid-flow detection)
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ORIENTATION_STARTED, true)
            .apply()

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.fragment_container, OrientationJoinFragment.newInstance())
            }
        }
    }

    /**
     * Marks orientation complete and navigates to the given destination.
     * Shows the crash reporting consent dialog on first run before navigating.
     */
    fun completeOrientation(destinationIntent: Intent? = null) {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ORIENTATION_COMPLETED, true)
            .apply()

        if (!CrashlyticsPreference.isConsentAsked(this)) {
            showCrashConsentDialog { navigateToMain(destinationIntent) }
        } else {
            navigateToMain(destinationIntent)
        }
    }

    private fun navigateToMain(destinationIntent: Intent? = null) {
        if (destinationIntent != null) {
            // Use TaskStackBuilder to insert MainAppActivity behind the destination
            androidx.core.app.TaskStackBuilder.create(this)
                .addNextIntent(Intent(this, MainAppActivity::class.java))
                .addNextIntent(destinationIntent)
                .startActivities()
        } else {
            val intent = Intent(this, MainAppActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
        }
        finish()
    }

    private fun showCrashConsentDialog(onComplete: () -> Unit) {
        CrashlyticsPreference.markConsentAsked(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_reporting_consent_title)
            .setMessage(R.string.crash_reporting_consent_message)
            .setPositiveButton(R.string.crash_reporting_consent_positive) { _, _ ->
                CrashlyticsPreference.setEnabled(this, true)
                syncCrashConsent("grant")
                onComplete()
            }
            .setNegativeButton(R.string.crash_reporting_consent_negative) { _, _ ->
                onComplete()
            }
            .setCancelable(false)
            .show()
    }

    private fun syncCrashConsent(action: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val token = SecureTokenManager.getInstance(this@OrientationActivity)
                    .getAccessToken() ?: return@launch
                ApiClient.recordConsents(token, listOf("analytics", "crash_reporting"), action)
            } catch (e: Exception) {
                // Non-critical
            }
        }
    }


    companion object {
        const val KEY_ORIENTATION_STARTED = "orientation_started"
        const val KEY_ORIENTATION_COMPLETED = "orientation_completed"

        fun newIntent(context: Context): Intent {
            return Intent(context, OrientationActivity::class.java)
        }
    }
}
