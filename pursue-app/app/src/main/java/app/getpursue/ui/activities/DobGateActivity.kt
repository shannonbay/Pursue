package app.getpursue.ui.activities

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import app.getpursue.R
import app.getpursue.data.auth.AuthRepository
import app.getpursue.ui.fragments.onboarding.DobGateFragment

/**
 * Hosts DobGateFragment for users who were already signed in when the age gate
 * was introduced (i.e., they have no DOB on file yet).
 *
 * On success  → launch MainAppActivity, finish.
 * On under-18 → sign out, launch OnboardingActivity with a message, finish.
 */
class DobGateActivity : AppCompatActivity(), DobGateFragment.Callbacks {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dob_gate)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.dob_gate_container, DobGateFragment.newInstance())
            }
        }
    }

    override fun onDobVerified() {
        startActivity(Intent(this, MainAppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onDobAuthFailed() {
        // Token is invalid — sign out and let the user start fresh
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit()
            .remove(MainActivity.KEY_HAS_IDENTITY)
            .remove(MainActivity.KEY_HAS_DATE_OF_BIRTH)
            .apply()
        val authRepository = AuthRepository.getInstance(this)
        authRepository.signOut()

        Toast.makeText(this, "Session expired. Please sign in again.", Toast.LENGTH_LONG).show()
        startActivity(Intent(this, OnboardingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    override fun onDobUnderAge() {
        // Sign out: clear tokens and identity/DOB flags
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE).edit()
            .remove(MainActivity.KEY_HAS_IDENTITY)
            .remove(MainActivity.KEY_HAS_DATE_OF_BIRTH)
            .apply()
        val authRepository = AuthRepository.getInstance(this)
        authRepository.signOut() // also clears tokens via SecureTokenManager

        // Route back to onboarding with a message
        Toast.makeText(this, getString(R.string.dob_gate_under_age_message), Toast.LENGTH_LONG).show()
        startActivity(Intent(this, OnboardingActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}
