package app.getpursue.ui.activities

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import app.getpursue.data.auth.SecureTokenManager

/**
 * Entry point for the app.
 *
 * Determines whether to launch onboarding flow or main app:
 * - If no local identity exists → launch onboarding flow (section 4.1).
 * - If identity exists → launch main app with Home screen (section 4.2).
 */
class MainActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (isFinishing) return

        // Check for FCM deep link data
        val fcmIntent = buildFcmDeepLinkIntent(intent)

        if (hasIdentity()) {
            if (fcmIntent != null) {
                startActivity(fcmIntent)
            } else {
                startActivity(Intent(this, MainAppActivity::class.java))
            }
        } else {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        finish()
    }

    /**
     * Check if intent contains FCM data and build appropriate deep link intent.
     * Returns null if no FCM data or no group_id present.
     */
    private fun buildFcmDeepLinkIntent(intent: Intent): Intent? {
        val groupId = intent.getStringExtra("group_id")?.takeIf { it.isNotBlank() } ?: return null
        val type = intent.getStringExtra("type")
        val groupName = intent.getStringExtra("group_name") ?: ""

        val (initialTab, openPendingApprovals) = when (type) {
            "join_request" -> 1 to true
            "progress_logged" -> 2 to false
            "member_joined", "member_left", "member_promoted", "member_approved",
            "member_removed", "member_declined" -> 1 to false
            else -> 0 to false  // goal_added, goal_archived, group_renamed, etc.
        }

        return Intent(this, GroupDetailActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(GroupDetailActivity.EXTRA_GROUP_ID, groupId)
            putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, groupName)
            putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, false)
            putExtra(GroupDetailActivity.EXTRA_INITIAL_TAB, initialTab)
            putExtra(GroupDetailActivity.EXTRA_OPEN_PENDING_APPROVALS, openPendingApprovals)
        }
    }

    private fun hasIdentity(): Boolean {
        // Check if user has valid tokens stored securely
        val tokenManager = SecureTokenManager.Companion.getInstance(this)
        return tokenManager.hasTokens() || prefs.getBoolean(KEY_HAS_IDENTITY, false)
    }
    companion object {
        const val PREFS_NAME = "pursue_prefs"
        const val KEY_HAS_IDENTITY = "has_identity"
    }
}