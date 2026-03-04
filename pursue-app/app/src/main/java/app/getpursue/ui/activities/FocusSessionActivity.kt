package app.getpursue.ui.activities

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.commit
import app.getpursue.R
import app.getpursue.ui.fragments.sessions.FocusSessionFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Full-screen container for the focus session UI.
 *
 * Receives extras: EXTRA_SESSION_ID, EXTRA_GROUP_ID, EXTRA_GROUP_NAME, EXTRA_IS_HOST.
 * Hosts a single FocusSessionFragment and manages the leave-session confirmation dialog.
 * FLAG_SECURE prevents screenshots during focus sessions.
 */
class FocusSessionActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_GROUP_NAME = "extra_group_name"
        const val EXTRA_IS_HOST = "extra_is_host"
        const val EXTRA_DURATION = "extra_duration"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Prevent screenshots during focus sessions
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContentView(R.layout.activity_focus_session)

        if (savedInstanceState == null) {
            val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: ""  // "" = host creating new session
            val groupId = intent.getStringExtra(EXTRA_GROUP_ID) ?: return
            val groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: ""
            val isHost = intent.getBooleanExtra(EXTRA_IS_HOST, false)
            val duration = intent.getIntExtra(EXTRA_DURATION, 45)

            val fragment = FocusSessionFragment.newInstance(sessionId, groupId, groupName, isHost, duration)
            supportFragmentManager.commit {
                replace(R.id.session_fragment_container, fragment)
            }
        }
    }

    override fun onBackPressed() {
        val fragment = supportFragmentManager.findFragmentById(R.id.session_fragment_container)
                as? FocusSessionFragment
        when {
            fragment?.isInPreview() == true -> super.onBackPressed()  // exit immediately, no dialog
            fragment?.isSessionActive() == true -> showLeaveConfirmation()
            else -> super.onBackPressed()
        }
    }

    private fun showLeaveConfirmation() {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.focus_session_leave_confirm_title))
            .setMessage(getString(R.string.focus_session_leave_confirm_message))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(getString(R.string.focus_session_leave)) { _, _ ->
                val fragment = supportFragmentManager.findFragmentById(R.id.session_fragment_container)
                (fragment as? FocusSessionFragment)?.leaveSession()
            }
            .show()
    }
}
