package app.getpursue.ui.activities

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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.text.method.LinkMovementMethod
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.MenuProvider
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import app.getpursue.data.auth.AuthRepository
import app.getpursue.data.auth.AuthState
import app.getpursue.data.auth.GoogleSignInHelper
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.config.PolicyConfig
import app.getpursue.data.config.PolicyConfigManager
import app.getpursue.data.fcm.FcmRegistrationHelper
import app.getpursue.data.fcm.FcmTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.notifications.UnreadBadgeManager
import app.getpursue.data.network.ApiException
import app.getpursue.utils.PolicyDateUtils
import app.getpursue.models.Group
import app.getpursue.ui.fragments.groups.CreateGroupFragment
import app.getpursue.ui.fragments.challenges.ChallengeTemplatesFragment
import app.getpursue.ui.fragments.challenges.ChallengeSetupFragment
import app.getpursue.ui.fragments.home.HomeFragment
import app.getpursue.ui.fragments.home.MyProgressFragment
import app.getpursue.ui.fragments.home.NotificationsFragment
import app.getpursue.ui.fragments.home.PremiumFragment
import app.getpursue.ui.fragments.home.ProfileFragment
import app.getpursue.ui.fragments.home.TodayFragment
import app.getpursue.ui.views.JoinGroupBottomSheet
import app.getpursue.R
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.navigation.NavigationBarView
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    private var overLimitDialogShowing = false
    private var overLimitSelectionCompletedThisSession = false
    private var consentDialogShowing = false

    private var billingClient: BillingClient? = null
    private var premiumProductDetails: ProductDetails? = null

    private var notificationBadgeView: TextView? = null
    private var lastBadgeCount: Int = 0
    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            for (purchase in purchases) {
                if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED && !purchase.isAcknowledged) {
                    lifecycleScope.launch {
                        acknowledgeAndVerifyPurchase(purchase)
                    }
                }
            }
        }
    }

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

        // Setup menu provider for toolbar (bell icon + overflow)
        addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.main_toolbar, menu)
                val notificationItem = menu.findItem(R.id.action_notifications)
                val actionView = notificationItem?.actionView
                notificationBadgeView = actionView?.findViewById(R.id.notification_badge)
                // Custom action layouts don't propagate click to onMenuItemSelected,
                // so we set a click listener directly on the action view
                actionView?.setOnClickListener { openNotificationsInbox() }
                applyNotificationBadge()
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
        }, this, androidx.lifecycle.Lifecycle.State.RESUMED)

        observeUnreadBadgeCount()

        // Setup network connectivity monitoring
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback)

        // Retry FCM registration after a delay so the first frame can render
        Handler(Looper.getMainLooper()).postDelayed({ retryFcmRegistrationIfNeeded() }, 2000)

        // Request notification permission once per install (Android 13+)
        requestNotificationPermissionIfNeeded()

        // Setup auth state observation
        authRepository = AuthRepository.Companion.getInstance(this)
        observeAuthState()

        // Load HomeFragment on first launch, or Premium if launched with EXTRA_OPEN_PREMIUM
        if (savedInstanceState == null) {
            if (intent.getBooleanExtra(EXTRA_OPEN_PREMIUM, false)) {
                supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
                supportFragmentManager.commit {
                    replace(R.id.fragment_container, PremiumFragment.Companion.newInstance())
                }
            } else {
                Log.d("MainAppActivity", "Loading HomeFragment on first launch")
                supportFragmentManager.commit {
                    replace(R.id.fragment_container, HomeFragment.Companion.newInstance())
                }
            }
        } else {
            Log.d("MainAppActivity", "Restoring MainAppActivity from saved state")
        }

        supportFragmentManager.addOnBackStackChangedListener { syncToolbarWithFragment() }
        syncToolbarWithFragment()

        // Billing: build client (lightweight, no IPC). Connection deferred to first use.
        billingClient = BillingClient.newBuilder(this)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases()
            .build()

        // Check if user needs to re-consent to updated policies
        checkPolicyConsent()
    }

    /**
     * Connect BillingClient on first use and invoke the callback when ready.
     */
    private fun ensureBillingConnected(onReady: () -> Unit) {
        val client = billingClient ?: return
        if (client.isReady) {
            onReady()
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    queryPremiumProduct()
                    onReady()
                }
            }
            override fun onBillingServiceDisconnected() {}
        })
    }

    private fun queryPremiumProduct() {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId("pursue_premium_annual")
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )
        val params = QueryProductDetailsParams.newBuilder().setProductList(productList).build()
        billingClient?.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && !productDetailsList.isNullOrEmpty()) {
                premiumProductDetails = productDetailsList.first()
            }
        }
    }

    private suspend fun acknowledgeAndVerifyPurchase(purchase: Purchase) {
        val token = SecureTokenManager.Companion.getInstance(this).getAccessToken() ?: return
        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billingClient?.acknowledgePurchase(params) { _ -> }
        try {
            withContext(Dispatchers.IO) {
                ApiClient.upgradeSubscription(
                    token,
                    "google_play",
                    purchase.purchaseToken,
                    purchase.products.firstOrNull() ?: "pursue_premium_annual"
                )
            }
            runOnUiThread {
                Toast.makeText(this, getString(R.string.pursue_premium_subscribe), Toast.LENGTH_SHORT).show()
                supportFragmentManager.popBackStack()
            }
        } catch (e: ApiException) {
            runOnUiThread { Toast.makeText(this, e.message ?: "Upgrade failed", Toast.LENGTH_SHORT).show() }
        }
    }

    /**
     * Observe authentication state and handle sign out.
     */
    private fun observeAuthState() {
        lifecycleScope.launch {
            authRepository.authState.collect { state ->
                when (state) {
                    is AuthState.SignedOut -> {
                        Log.w("MainAppActivity", "authState → SignedOut received (stackTrace=${Thread.currentThread().stackTrace.take(5).joinToString("|") { it.methodName }})")
                        handleSignOut()
                    }
                    is AuthState.SignedIn -> {
                        Log.d("MainAppActivity", "authState → SignedIn received")
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

        // Show toast message (user-initiated sign out or session expired)
        Toast.makeText(
            this,
            getString(R.string.signed_out_toast),
            Toast.LENGTH_LONG
        ).show()

        // Navigate to OnboardingActivity
        val intent = Intent(this, OnboardingActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_PREMIUM, false)) {
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            supportActionBar?.title = getString(R.string.pursue_premium_title)
            supportActionBar?.setDisplayHomeAsUpEnabled(false)
            supportFragmentManager.commit {
                replace(R.id.fragment_container, PremiumFragment.Companion.newInstance())
            }
        }
    }

    override fun onResume() {
        super.onResume()
        fetchUnreadNotificationCount()
    }

    private fun fetchUnreadNotificationCount() {
        lifecycleScope.launch {
            try {
                val token = SecureTokenManager.Companion.getInstance(this@MainAppActivity).getAccessToken()
                Log.d("MainAppActivity", "fetchUnreadNotificationCount: token=${if (token != null) "present" else "null"}")
                if (token != null) {
                    UnreadBadgeManager.fetchUnreadCount(token)
                    Log.d("MainAppActivity", "fetchUnreadNotificationCount: fetched count=${UnreadBadgeManager.unreadCount.value}")
                }
            } catch (e: Exception) {
                Log.e("MainAppActivity", "Failed to fetch unread notification count", e)
            }
        }
    }

    private fun observeUnreadBadgeCount() {
        lifecycleScope.launch {
            Log.d("MainAppActivity", "observeUnreadBadgeCount: starting collection")
            UnreadBadgeManager.unreadCount.collect { count ->
                Log.d("MainAppActivity", "observeUnreadBadgeCount: received count=$count")
                lastBadgeCount = count
                runOnUiThread { applyNotificationBadge() }
            }
        }
    }

    private fun applyNotificationBadge() {
        Log.d("MainAppActivity", "applyNotificationBadge: lastBadgeCount=$lastBadgeCount, notificationBadgeView=${if (notificationBadgeView != null) "present" else "null"}")
        notificationBadgeView?.let { badge ->
            badge.visibility = if (lastBadgeCount > 0) View.VISIBLE else View.GONE
            badge.text = if (lastBadgeCount > 9) "9+" else lastBadgeCount.toString()
            Log.d("MainAppActivity", "applyNotificationBadge: set visibility=${badge.visibility}, text=${badge.text}")
        }
    }

    private fun openNotificationsInbox() {
        supportActionBar?.title = getString(R.string.notifications_section_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.commit {
            replace(R.id.fragment_container, NotificationsFragment.newInstance())
            addToBackStack(null)
        }
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
     * If user is over_limit (subscription expired, multiple groups), show dialog to select one group to keep.
     * Called with already-fetched groups from HomeFragment to avoid a duplicate getMyGroups call.
     */
    private fun checkOverLimitAndShowDialog(groups: List<Group>) {
        if (overLimitDialogShowing) return
        if (overLimitSelectionCompletedThisSession) return
        if (groups.size <= 1) return
        lifecycleScope.launch {
            val token = SecureTokenManager.Companion.getInstance(this@MainAppActivity).getAccessToken() ?: return@launch
            val subscription = try {
                withContext(Dispatchers.IO) { ApiClient.getSubscription(token) }
            } catch (e: ApiException) {
                return@launch
            }
            if (!subscription.is_over_limit) {
                return@launch
            }
            runOnUiThread { showOverLimitDialog(token, groups) }
        }
    }

    private fun showOverLimitDialog(token: String, groups: List<Group>) {
        overLimitDialogShowing = true
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_over_limit_select_group, null as ViewGroup?)
        val radioGroupActual = view.findViewById<RadioGroup>(R.id.over_limit_group_radio_group)
        val groupIds = groups.map { it.id }
        val paddingPx = (16 * resources.displayMetrics.density).toInt()
        groups.forEachIndexed { index, group ->
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = group.name
                setPadding(paddingPx, 0, 0, 0)
            }
            radioGroupActual.addView(radio)
            if (index == 0) radioGroupActual.check(radio.id)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.choose_your_group_dialog_title)
            .setView(view)
            .setNegativeButton(R.string.upgrade_to_premium) { _, _ ->
                overLimitDialogShowing = false
                showPremiumScreen()
            }
            .setPositiveButton(R.string.keep_selected_group) { d, _ ->
                val checkedId = radioGroupActual.checkedRadioButtonId
                val selectedIndex = radioGroupActual.indexOfChild(radioGroupActual.findViewById(checkedId))
                val keepGroupId = groupIds.getOrNull(selectedIndex) ?: groupIds.first()
                overLimitDialogShowing = false
                d.dismiss()
                lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) { ApiClient.downgradeSelectGroup(token, keepGroupId) }
                        overLimitSelectionCompletedThisSession = true
                        runOnUiThread {
                            (supportFragmentManager.findFragmentById(R.id.fragment_container) as? HomeFragment)?.refreshGroups()
                            Toast.makeText(this@MainAppActivity, getString(R.string.keep_selected_group), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: ApiException) {
                        runOnUiThread { Toast.makeText(this@MainAppActivity, e.message, Toast.LENGTH_SHORT).show() }
                    }
                }
            }
            .setOnDismissListener { overLimitDialogShowing = false }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
            setTextColor(ContextCompat.getColor(this@MainAppActivity, R.color.secondary))
            setTypeface(typeface, Typeface.BOLD)
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
            is ChallengeTemplatesFragment -> {
                supportActionBar?.title = getString(R.string.start_challenge)
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
            }
            is ChallengeSetupFragment -> {
                supportActionBar?.title = getString(R.string.challenge_setup_title)
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
            }
            is PremiumFragment -> {
                supportActionBar?.title = getString(R.string.pursue_premium_title)
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
            }
            is MyProgressFragment -> {
                supportActionBar?.title = getString(R.string.my_progress_title)
                supportActionBar?.setDisplayHomeAsUpEnabled(true)
            }
            is NotificationsFragment -> {
                supportActionBar?.title = getString(R.string.notifications_section_title)
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
        supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
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

    override fun onGroupsLoaded(groups: List<Group>) {
        checkOverLimitAndShowDialog(groups)
    }

    override fun onCreateGroup() {
        lifecycleScope.launch {
            val token = SecureTokenManager.Companion.getInstance(this@MainAppActivity).getAccessToken()
            if (token == null) {
                runOnUiThread { proceedToCreateGroup() }
                return@launch
            }
            val eligibility = try {
                withContext(Dispatchers.IO) { ApiClient.getSubscriptionEligibility(token) }
            } catch (e: ApiException) {
                runOnUiThread { Toast.makeText(this@MainAppActivity, e.message, Toast.LENGTH_SHORT).show() }
                return@launch
            }
            runOnUiThread {
                if (eligibility.can_create_group) proceedToCreateGroup()
                else showGroupLimitReachedDialog()
            }
        }
    }

    override fun onStartChallenge() {
        supportActionBar?.title = getString(R.string.start_challenge)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.commit {
            replace(R.id.fragment_container, ChallengeTemplatesFragment.newInstance())
            addToBackStack(null)
        }
    }

    override fun onJoinGroup() {
        lifecycleScope.launch {
            val token = SecureTokenManager.Companion.getInstance(this@MainAppActivity).getAccessToken()
            if (token == null) {
                runOnUiThread { JoinGroupBottomSheet.Companion.show(supportFragmentManager) }
                return@launch
            }
            val eligibility = try {
                withContext(Dispatchers.IO) { ApiClient.getSubscriptionEligibility(token) }
            } catch (e: ApiException) {
                runOnUiThread { Toast.makeText(this@MainAppActivity, e.message, Toast.LENGTH_SHORT).show() }
                return@launch
            }
            runOnUiThread {
                if (eligibility.can_join_group) JoinGroupBottomSheet.Companion.show(supportFragmentManager)
                else showGroupLimitReachedDialog()
            }
        }
    }

    private fun proceedToCreateGroup() {
        supportActionBar?.title = getString(R.string.create_group_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.commit {
            replace(R.id.fragment_container, CreateGroupFragment.Companion.newInstance())
            addToBackStack(null)
        }
    }

    private fun showGroupLimitReachedDialog() {
        val d = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.group_limit_reached_dialog_title)
            .setMessage(getString(R.string.group_limit_reached_message) + "\n\n" + getString(R.string.group_limit_reached_bullets))
            .setNegativeButton(R.string.maybe_later, null)
            .setPositiveButton(R.string.upgrade_to_premium) { _, _ -> showPremiumScreen() }
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(ContextCompat.getColor(this@MainAppActivity, R.color.secondary))
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    /**
     * Opens the Pursue Premium screen. Called from group-limit dialog, over-limit dialog, profile, export-limit dialog.
     */
    fun showPremiumScreen() {
        // Start BillingClient connection lazily so product details are ready by the time the user taps Subscribe
        ensureBillingConnected {}
        supportActionBar?.title = getString(R.string.pursue_premium_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportFragmentManager.commit {
            replace(R.id.fragment_container, PremiumFragment.Companion.newInstance())
            addToBackStack(null)
        }
    }

    /**
     * Launch Google Play Billing purchase flow for Premium. Called from PremiumFragment.
     */
    fun launchPremiumPurchaseFlow() {
        ensureBillingConnected {
            val productDetails = premiumProductDetails
            if (productDetails == null) {
                runOnUiThread {
                    Toast.makeText(this, getString(R.string.pursue_premium_subscribe), Toast.LENGTH_SHORT).show()
                }
                return@ensureBillingConnected
            }
            val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken == null) {
                runOnUiThread {
                    Toast.makeText(this, "Subscription offer not available", Toast.LENGTH_SHORT).show()
                }
                return@ensureBillingConnected
            }
            val productDetailsParamsList = listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offerToken)
                    .build()
            )
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(productDetailsParamsList)
                .build()
            runOnUiThread {
                billingClient?.launchBillingFlow(this, params)
            }
        }
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

    override fun onUpgradeToPremium() {
        showPremiumScreen()
    }

    override fun onSignOut() {
        GoogleSignInHelper(this).signOut()
        FcmTokenManager.getInstance(this).clearToken()
        authRepository.signOut()
    }

    // endregion

    override fun onSupportNavigateUp(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStackImmediate()
            return true
        }
        return super.onSupportNavigateUp()
    }

    private fun checkPolicyConsent() {
        lifecycleScope.launch {
            try {
                val config = withContext(Dispatchers.IO) {
                    PolicyConfigManager.getConfig(this@MainAppActivity)
                } ?: return@launch

                val token = SecureTokenManager.getInstance(this@MainAppActivity)
                    .getAccessToken() ?: return@launch

                val consents = withContext(Dispatchers.IO) {
                    ApiClient.getMyConsents(token)
                }

                val types = consents.consents.map { it.consent_type }
                val needsTerms = PolicyDateUtils.needsReconsent(config.min_required_terms_version, types, "terms ")
                val needsPrivacy = PolicyDateUtils.needsReconsent(config.min_required_privacy_version, types, "privacy policy ")
                val hasOnlyLegacy = types.isNotEmpty() && types.all { it == "privacy_and_terms" }

                if (needsTerms || needsPrivacy || hasOnlyLegacy) {
                    runOnUiThread { showPolicyConsentDialog(config, token) }
                }
            } catch (e: Exception) {
                Log.w("MainAppActivity", "Policy consent check failed, allowing access", e)
            }
        }
    }

    private fun showPolicyConsentDialog(config: PolicyConfig, token: String) {
        if (consentDialogShowing) return
        consentDialogShowing = true

        val view = LayoutInflater.from(this).inflate(R.layout.dialog_consent_confirm, null as ViewGroup?)
        val consentCheckbox = view.findViewById<com.google.android.material.checkbox.MaterialCheckBox>(R.id.consent_checkbox)
        val consentText = view.findViewById<TextView>(R.id.consent_text)

        consentText.text = HtmlCompat.fromHtml(
            getString(R.string.policy_reconsent_text),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        consentText.movementMethod = LinkMovementMethod.getInstance()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.policy_update_title))
            .setView(view)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.consent_continue)) { _, _ ->
                consentDialogShowing = false
                recordPolicyConsent(config, token)
            }
            .create()

        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton?.isEnabled = false

        consentCheckbox.setOnCheckedChangeListener { _, isChecked ->
            positiveButton?.isEnabled = isChecked
        }
    }

    private fun recordPolicyConsent(config: PolicyConfig, token: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApiClient.recordConsents(
                        token,
                        listOf(
                            "terms ${config.min_required_terms_version}",
                            "privacy policy ${config.min_required_privacy_version}"
                        )
                    )
                }
            } catch (e: Exception) {
                Log.w("MainAppActivity", "Failed to record policy consent", e)
            }
        }
    }

    companion object {
        private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"
        const val EXTRA_OPEN_PREMIUM = "extra_open_premium"
    }
}
