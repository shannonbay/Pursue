package app.getpursue.ui.activities

import android.os.Bundle
import android.view.Menu
import android.view.View
import android.view.MenuInflater
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.core.view.WindowCompat
import androidx.fragment.app.commit
import app.getpursue.ui.fragments.groups.GroupDetailFragment
import app.getpursue.ui.fragments.groups.PendingApprovalsFragment
import app.getpursue.ui.fragments.home.PremiumFragment
import app.getpursue.R

/**
 * Activity for displaying Group Detail screen without bottom navigation.
 *
 * Hosts GroupDetailFragment and handles nested navigation (e.g., GoalDetailFragment).
 */
class GroupDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_GROUP_ID = "extra_group_id"
        const val EXTRA_GROUP_NAME = "extra_group_name"
        const val EXTRA_GROUP_HAS_ICON = "extra_group_has_icon"
        const val EXTRA_GROUP_ICON_EMOJI = "extra_group_icon_emoji"
        const val EXTRA_INITIAL_TAB = "extra_initial_tab"
        const val EXTRA_OPEN_PENDING_APPROVALS = "extra_open_pending_approvals"
        const val RESULT_GROUP_DELETED = 1001
        const val RESULT_LEFT_GROUP = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Enable edge-to-edge display for proper WindowInsets handling
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContentView(R.layout.activity_group_detail)

        // Setup Toolbar
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Get group data from Intent
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME) ?: ""
        val hasIcon = intent.getBooleanExtra(EXTRA_GROUP_HAS_ICON, false)
        val iconEmoji = intent.getStringExtra(EXTRA_GROUP_ICON_EMOJI)

        // Set toolbar title
        supportActionBar?.title = groupName

        // Setup menu provider for overflow menu
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                // Menu items are handled by GroupDetailFragment
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    android.R.id.home -> {
                        onBackPressedDispatcher.onBackPressed()
                        true
                    }
                    else -> false
                }
            }
        })

        // Load GroupDetailFragment on first launch
        val initialTab = intent.getIntExtra(EXTRA_INITIAL_TAB, -1)
        val openPendingApprovals = intent.getBooleanExtra(EXTRA_OPEN_PENDING_APPROVALS, false)
        if (savedInstanceState == null && groupId != null) {
            supportFragmentManager.commit {
                replace(
                    R.id.fragment_container,
                    GroupDetailFragment.Companion.newInstance(
                        groupId = groupId,
                        groupName = groupName,
                        hasIcon = hasIcon,
                        iconEmoji = iconEmoji,
                        initialTabIndex = initialTab
                    )
                )
            }
            if (openPendingApprovals) {
                findViewById<View>(R.id.fragment_container)?.post {
                    supportFragmentManager.commit {
                        replace(
                            R.id.fragment_container,
                            PendingApprovalsFragment.Companion.newInstance(groupId, groupName)
                        )
                        addToBackStack(null)
                    }
                }
            }
        }
    }

    /**
     * Opens the Pursue Premium screen within this activity so back returns to the group.
     */
    fun showPremiumScreen() {
        supportActionBar?.title = getString(R.string.pursue_premium_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.commit {
            replace(R.id.fragment_container, PremiumFragment.Companion.newInstance())
            addToBackStack(null)
        }
    }
}
