package com.github.shannonbay.pursue.ui.activities

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.os.Build
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.github.shannonbay.pursue.data.auth.AuthRepository
import com.github.shannonbay.pursue.data.auth.AuthState
import com.github.shannonbay.pursue.ui.activities.GroupDetailActivity
import com.github.shannonbay.pursue.ui.fragments.groups.CreateGroupFragment
import com.github.shannonbay.pursue.data.fcm.FcmRegistrationHelper
import com.github.shannonbay.pursue.ui.fragments.home.HomeFragment
import com.github.shannonbay.pursue.ui.views.JoinGroupBottomSheet
import com.github.shannonbay.pursue.ui.fragments.home.MyProgressFragment
import com.github.shannonbay.pursue.ui.fragments.home.ProfileFragment
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.ui.fragments.home.TodayFragment
import com.github.shannonbay.pursue.models.Group
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.navigation.NavigationBarView
import kotlinx.coroutines.launch

/**
 * Main application activity with bottom navigation bar.
 *
 * Hosts the three main screens:
 * - Home (Groups List) - Section 4.2
 * - Today - Section 4.4 (placeholder)
 * - Profile - Section 4.5 (placeholder)
 */
class MainAppActivity : AppCompatActivity(),
    NavigationBarView.OnItemSelectedListener,
    HomeFragment.Callbacks,
    TodayFragment.Callbacks,
    ProfileFragment.Callbacks {

    private lateinit var bottomNavigation: BottomNavigationView
    private lateinit var connectivityManager: ConnectivityManager
    private lateinit var authRepository: AuthRepository

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
    }

    private val groupDetailLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == GroupDetailActivity.RESULT_GROUP_DELETED ||
            result.resultCode == GroupDetailActivity.RESULT_LEFT_GROUP) {
            (supportFragmentManager.findFragmentById(R.id.fragment_container) as? HomeFragment)
                ?.refreshGroups()
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // Network restored - retry FCM registration if needed
            Log.d("MainAppActivity", "Network available, checking FCM registration")
            retryFcmRegistrationIfNeeded()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            // Network capabilities changed (e.g., WiFi connected)
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                Log.d("MainAppActivity", "Network validated, checking FCM registration")
                retryFcmRegistrationIfNeeded()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main_app)

        // Setup Toolbar
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = getString(R.string.groups_title)

        bottomNavigation = findViewById(R.id.bottom_navigation)
        bottomNavigation.setOnItemSelectedListener(this)

        // Setup menu provider for overflow menu
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                // TODO: Add overflow menu items (section 4.2 spec shows ⋮ menu)
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

        // Setup network connectivity monitoring
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Retry FCM registration on app startup if needed
        retryFcmRegistrationIfNeeded()

        // Request notification permission once per install (Android 13+)
        requestNotificationPermissionIfNeeded()

        // Setup auth state observation
        authRepository = AuthRepository.Companion.getInstance(this)
        observeAuthState()

        // Load HomeFragment on first launch
        if (savedInstanceState == null) {
            Log.d("MainAppActivity", "Loading HomeFragment on first launch")
            supportFragmentManager.commit {
                replace(R.id.fragment_container, HomeFragment.Companion.newInstance())
            }
        } else {
            Log.d("MainAppActivity", "Restoring MainAppActivity from saved state")
        }

        supportFragmentManager.addOnBackStackChangedListener { syncToolbarWithFragment() }
        syncToolbarWithFragment()
    }

    /**
     * Observe authentication state and handle sign out.
     */
    private fun observeAuthState() {
        lifecycleScope.launch {
            authRepository.authState.collect { state ->
                when (state) {
                    is AuthState.SignedOut -> handleSignOut()
                    is AuthState.SignedIn -> {
                        // Already signed in, no action needed
                    }
                }
            }
        }
    }

    /**
     * Handle user sign out by navigating to OnboardingActivity.
     */
    private fun handleSignOut() {
        Log.d("MainAppActivity", "Auth state changed to SignedOut, navigating to OnboardingActivity")

        // Show toast message
        Toast.makeText(
            this,
            "Session expired. Please sign in again.",
            Toast.LENGTH_LONG
        ).show()

        // Navigate to OnboardingActivity
        val intent = Intent(this, OnboardingActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister network callback
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Ignore if already unregistered
            Log.d("MainAppActivity", "Network callback already unregistered")
        }
    }

    /**
     * Request POST_NOTIFICATIONS once per install (Android 13+). No rationale; user can enable in settings later.
     */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (prefs.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return
        prefs.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /**
     * Sync toolbar title and up-button visibility with the current fragment.
     * Called on back stack changes and activity restore so the toolbar reflects
     * the visible fragment (e.g. "Groups" when returning from CreateGroupFragment).
     */
    private fun syncToolbarWithFragment() {
        val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_container)
        when (currentFragment) {
            is HomeFragment -> {
                supportActionBar?.title = getString(R.string.groups_title)
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
            }
            is TodayFragment -> {
                supportActionBar?.title = getString(R.string.today_title)
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
            }
            is ProfileFragment -> {
                supportActionBar?.title = getString(R.string.profile_title)
                supportActionBar?.setDisplayHomeAsUpEnabled(false)
            }
            is CreateGroupFragment -> {
                supportActionBar?.title = getString(R.string.create_group_title)
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
            }
            is MyProgressFragment -> {
                supportActionBar?.title = getString(R.string.my_progress_title)
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
            }
            else -> {
                supportActionBar?.title = getString(R.string.groups_title)
                supportActionBar?.setDisplayHomeAsUpEnabled(supportFragmentManager.backStackEntryCount > 0)
            }
        }
    }

    /**
     * Retry FCM token registration if the token exists but hasn't been registered with the server.
     * This is called on app startup and when network connectivity is restored.
     */
    private fun retryFcmRegistrationIfNeeded() {
        lifecycleScope.launch {
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(this@MainAppActivity)
                val accessToken = tokenManager.getAccessToken()

                if (accessToken != null) {
                    FcmRegistrationHelper.registerFcmTokenIfNeeded(
                        this@MainAppActivity,
                        accessToken
                    )
                }
            } catch (e: Exception) {
                Log.w("MainAppActivity", "Failed to retry FCM registration", e)
            }
        }
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        val fragment = when (item.itemId) {
            R.id.nav_home -> {
                supportActionBar?.title = getString(R.string.groups_title)
                HomeFragment.Companion.newInstance()
            }
            R.id.nav_today -> {
                supportActionBar?.title = getString(R.string.today_title)
                TodayFragment.Companion.newInstance()
            }
            R.id.nav_profile -> {
                supportActionBar?.title = getString(R.string.profile_title)
                ProfileFragment.Companion.newInstance()
            }
            else -> return false
        }

        // Clear back stack when switching tabs
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)

        supportFragmentManager.commit {
            replace(R.id.fragment_container, fragment)
        }

        return true
    }

    // region HomeFragment.Callbacks

    override fun onGroupSelected(group: Group) {
        val intent = Intent(this, GroupDetailActivity::class.java).apply {
            putExtra(GroupDetailActivity.EXTRA_GROUP_ID, group.id)
            putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, group.name)
            putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, group.has_icon)
            group.icon_emoji?.let { putExtra(GroupDetailActivity.EXTRA_GROUP_ICON_EMOJI, it) }
        }
        groupDetailLauncher.launch(intent)
    }

    override fun onCreateGroup() {
        supportActionBar?.title = getString(R.string.create_group_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.commit {
            replace(R.id.fragment_container, CreateGroupFragment.Companion.newInstance())
            addToBackStack(null)
        }
    }

    override fun onJoinGroup() {
        JoinGroupBottomSheet.show(supportFragmentManager)
    }

    // endregion

    // region TodayFragment.Callbacks

    override fun onViewGroups() {
        bottomNavigation.selectedItemId = R.id.nav_home
    }

    // endregion

    // region ProfileFragment.Callbacks

    override fun onViewMyProgress() {
        supportActionBar?.title = getString(R.string.my_progress_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.commit {
            replace(R.id.fragment_container, MyProgressFragment.Companion.newInstance())
            addToBackStack(null)
        }
    }

    // endregion

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
            return true
        }
        return super.onSupportNavigateUp()
    }

    companion object {
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
    }
}