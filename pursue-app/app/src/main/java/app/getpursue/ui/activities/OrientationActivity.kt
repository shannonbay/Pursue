package app.getpursue.ui.activities

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import app.getpursue.R
import app.getpursue.ui.fragments.orientation.OrientationJoinFragment

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
     */
    fun completeOrientation(destinationIntent: Intent? = null) {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ORIENTATION_COMPLETED, true)
            .apply()

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


    companion object {
        const val KEY_ORIENTATION_STARTED = "orientation_started"
        const val KEY_ORIENTATION_COMPLETED = "orientation_completed"

        fun newIntent(context: Context): Intent {
            return Intent(context, OrientationActivity::class.java)
        }
    }
}
