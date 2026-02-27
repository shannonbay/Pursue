package app.getpursue.ui.fragments.groups

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.widget.RadioGroup
import android.os.Looper
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.MenuProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import app.getpursue.data.analytics.AnalyticsEvents
import app.getpursue.data.analytics.AnalyticsLogger
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.ui.views.DailyPulseWidget
import app.getpursue.data.fcm.FcmTopicManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.toChallengeCompletionCardDataOrNull
import app.getpursue.models.GroupDetailResponse
import app.getpursue.models.GroupMember
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.activities.ShareableMilestoneCardActivity
import app.getpursue.ui.fragments.goals.CreateGoalFragment
import app.getpursue.ui.views.IconPickerBottomSheet
import app.getpursue.ui.views.InviteMembersBottomSheet
import app.getpursue.utils.ChallengeDateUiUtils
import app.getpursue.utils.EmojiUtils
import app.getpursue.utils.HeatUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.getpursue.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.TimeZone

/**
 * Group Detail Fragment (UI spec section 4.3).
 * 
 * Displays group information with three tabs (Goals, Members, Activity),
 * header with group icon and details, and context-aware FAB.
 */
class GroupDetailFragment : Fragment() {
    private val gson = Gson()

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_GROUP_NAME = "group_name"
        private const val ARG_GROUP_VISIBILITY = "group_visibility"
        private const val ARG_GROUP_HAS_ICON = "group_has_icon"
        private const val ARG_GROUP_ICON_EMOJI = "group_icon_emoji"
        private const val ARG_INITIAL_TAB = "initial_tab"
        private const val ARG_OPEN_INVITE_SHEET = "open_invite_sheet"

        fun newInstance(
            groupId: String,
            groupName: String,
            visibility: String? = null,
            hasIcon: Boolean = false,
            iconEmoji: String? = null,
            initialTabIndex: Int = -1,
            openInviteSheet: Boolean = false
        ): GroupDetailFragment {
            return GroupDetailFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_GROUP_NAME, groupName)
                    putString(ARG_GROUP_VISIBILITY, visibility)
                    putBoolean(ARG_GROUP_HAS_ICON, hasIcon)
                    putString(ARG_GROUP_ICON_EMOJI, iconEmoji)
                    putInt(ARG_INITIAL_TAB, initialTabIndex)
                    putBoolean(ARG_OPEN_INVITE_SHEET, openInviteSheet)
                }
            }
        }
    }

    private var groupId: String? = null
    private var groupDetail: GroupDetailResponse? = null
    private var hasOpenedInitialInviteSheet = false
    internal var nowProvider: () -> LocalDateTime = { LocalDateTime.now() }

    private lateinit var dailyPulseWidget: DailyPulseWidget
    private var pollingJob: Job? = null
    private var lastKnownMembers: List<GroupMember>? = null
    private var currentUserId: String? = null
    
    private lateinit var groupIconContainer: FrameLayout
    private lateinit var groupIconImage: ImageView
    private lateinit var groupIconEmoji: TextView
    private lateinit var groupIconLetter: TextView
    private lateinit var subtitleMembersGoals: TextView
    private lateinit var iconVisibility: ImageView
    private lateinit var textVisibility: TextView
    private lateinit var visibilityDivider: View
    private lateinit var goalsDivider: View
    private lateinit var textGoalsCount: TextView
    private lateinit var createdBy: TextView
    private lateinit var challengeHeaderContainer: View
    private lateinit var challengeHeaderDayProgress: TextView
    private lateinit var challengeHeaderProgressBar: android.widget.ProgressBar
    private lateinit var challengeShareButton: MaterialButton
    private lateinit var challengeInviteButton: MaterialButton
    private lateinit var heatSection: View
    private lateinit var heatIconDetail: ImageView
    private lateinit var heatTierLabel: TextView
    private lateinit var heatScoreLabel: TextView
    private lateinit var heatDetails: TextView
    private lateinit var commLinkRow: TextView
    private lateinit var tabLayout: TabLayout
    private lateinit var viewPager: ViewPager2
    private lateinit var fabAction: FloatingActionButton

    private var pendingExportGid: String? = null
    private var pendingExportStartDate: String? = null
    private var pendingExportEndDate: String? = null
    private var pendingExportUserTimezone: String? = null

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        onExportUriSelected(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID)
    }

    override fun onResume() {
        super.onResume()
        // Restore group name as toolbar title (use loaded name if available, else from arguments)
        val groupName = groupDetail?.name ?: arguments?.getString(ARG_GROUP_NAME)
        groupName?.let {
            (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = it
        }
        // Refresh when returning from Edit Group (or other nested screens)
        loadGroupDetails()
        startPulsePolling()
    }

    override fun onPause() {
        super.onPause()
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_group_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Initialize views
        groupIconContainer = view.findViewById(R.id.group_icon_container)
        groupIconImage = view.findViewById(R.id.group_icon_image)
        groupIconEmoji = view.findViewById(R.id.group_icon_emoji)
        groupIconLetter = view.findViewById(R.id.group_icon_letter)
        subtitleMembersGoals = view.findViewById(R.id.subtitle_members_goals)
        iconVisibility = view.findViewById(R.id.icon_visibility)
        textVisibility = view.findViewById(R.id.text_visibility)
        visibilityDivider = view.findViewById(R.id.visibility_divider)
        goalsDivider = view.findViewById(R.id.goals_divider)
        textGoalsCount = view.findViewById(R.id.text_goals_count)
        createdBy = view.findViewById(R.id.created_by)
        challengeHeaderContainer = view.findViewById(R.id.challenge_header_container)
        challengeHeaderDayProgress = view.findViewById(R.id.challenge_header_day_progress)
        challengeHeaderProgressBar = view.findViewById(R.id.challenge_header_progress_bar)
        challengeShareButton = view.findViewById(R.id.challenge_share_button)
        challengeInviteButton = view.findViewById(R.id.challenge_invite_button)
        heatSection = view.findViewById(R.id.heat_section)
        heatIconDetail = view.findViewById(R.id.heat_icon_detail)
        heatTierLabel = view.findViewById(R.id.heat_tier_label)
        heatScoreLabel = view.findViewById(R.id.heat_score_label)
        heatDetails = view.findViewById(R.id.heat_details)
        commLinkRow = view.findViewById(R.id.comm_link_row)
        tabLayout = view.findViewById(R.id.tab_layout)
        viewPager = view.findViewById(R.id.view_pager)
        fabAction = view.findViewById(R.id.fab_action)
        dailyPulseWidget = view.findViewById(R.id.daily_pulse_widget)
        dailyPulseWidget.setFragmentManager(childFragmentManager, groupId ?: "")

        // Heat section tap -> show info dialog
        val heatClickListener = View.OnClickListener { showHeatInfoDialog() }
        heatSection.setOnClickListener(heatClickListener)
        heatDetails.setOnClickListener(heatClickListener)
        challengeInviteButton.setOnClickListener { showInviteMembersSheet() }
        challengeShareButton.setOnClickListener { shareChallenge() }

        // Load initial icon from arguments (no icon_color until details load)
        loadGroupIcon(
            hasIcon = arguments?.getBoolean(ARG_GROUP_HAS_ICON) ?: false,
            iconEmoji = arguments?.getString(ARG_GROUP_ICON_EMOJI),
            iconColor = null,
            groupNameText = arguments?.getString(ARG_GROUP_NAME) ?: ""
        )

        // Load initial visibility and basic info from arguments
        updateGoalsCountSubtitle(
            memberCount = 0, // Will be updated on load
            goalCount = 0,   // Will be updated on load
            visibility = arguments?.getString(ARG_GROUP_VISIBILITY)
        )

        // Setup overflow menu in Activity toolbar
        setupOverflowMenu()

        // Setup ViewPager with tabs
        setupViewPager(savedInstanceState)

        // Setup FAB
        setupFAB()
        
        // Apply WindowInsets to FAB for proper positioning above system navigation bar
        setupFABWindowInsets()

        // Initial load and refresh on return from Edit happen in onResume
    }

    private fun setupViewPager(savedInstanceState: Bundle?) {
        val adapter = GroupDetailPagerAdapter(this)
        viewPager.adapter = adapter

        TabLayoutMediator(tabLayout, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_goals)
                1 -> getString(R.string.tab_members)
                2 -> getString(R.string.tab_activity)
                else -> ""
            }
        }.attach()

        val initialTab = arguments?.getInt(ARG_INITIAL_TAB, -1) ?: -1
        if (savedInstanceState == null && initialTab in 0..2) {
            viewPager.setCurrentItem(initialTab, false)
        }

        // Update FAB when tab changes
        var previousTabPosition = 0
        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateFABForTab(position, previousTabPosition)
                previousTabPosition = position
                val screen = when (position) {
                    0 -> AnalyticsEvents.SCREEN_GROUP_GOALS
                    1 -> AnalyticsEvents.SCREEN_GROUP_MEMBERS
                    2 -> AnalyticsEvents.SCREEN_GROUP_ACTIVITY
                    else -> return
                }
                AnalyticsLogger.setScreen(screen)
            }
        })
    }

    private fun setupFAB() {
        // Initial FAB setup - don't call updateFABForTab yet since groupDetail isn't loaded
        // The FAB will be updated after groupDetail loads in loadGroupDetails()
        // For now, just ensure it starts in a known state (hidden)
        fabAction.visibility = View.GONE
        fabAction.alpha = 1f
        fabAction.translationY = 0f
    }
    
    /**
     * Apply WindowInsets to FAB to position it above the system navigation bar.
     * This ensures the FAB is never obscured by the navigation bar on any device.
     */
    private fun setupFABWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(fabAction) { v, insets ->
            val navigationBarInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            // Convert 16dp to pixels for design margin
            val designMarginPx = (16 * resources.displayMetrics.density).toInt()
            val totalBottomMargin = navigationBarInsets.bottom + designMarginPx
            
            v.updateLayoutParams<CoordinatorLayout.LayoutParams> {
                bottomMargin = totalBottomMargin
            }
            insets
        }
    }
    
    
    private fun updateFABForTab(tabPosition: Int, previousPosition: Int = -1) {
        val isAdmin = groupDetail?.let { detail ->
            detail.user_role == "admin" || detail.user_role == "creator"
        } ?: false
        val isActiveChallenge = groupDetail?.let { detail ->
            detail.is_challenge && effectiveChallengeStatus(detail) == "active"
        } ?: false

        when (tabPosition) {
            0 -> { // Goals tab
                if (isAdmin && !isActiveChallenge) {
                    showFABWithAnimation(
                        icon = android.R.drawable.ic_input_add,
                        contentDescription = getString(R.string.fab_add_goal),
                        onClick = {
                            // Navigate to Create Goal screen (Section 4.3.5)
                            val groupId = this@GroupDetailFragment.groupId ?: return@showFABWithAnimation
                            val createGoalFragment = CreateGoalFragment.Companion.newInstance(groupId)
                            requireActivity().supportFragmentManager.commit {
                                replace(R.id.fragment_container, createGoalFragment)
                                addToBackStack(null)
                            }
                        },
                        previousPosition = previousPosition
                    )
                } else {
                    hideFABWithAnimation(previousPosition)
                }
            }
            1 -> { // Members tab - show Invite members FAB for everyone
                showFABWithAnimation(
                    icon = R.drawable.ic_person_add,
                    contentDescription = getString(R.string.fab_invite_members),
                    onClick = { showInviteMembersSheet() },
                    previousPosition = previousPosition
                )
            }
            2 -> { // Activity tab
                hideFABWithAnimation(previousPosition)
            }
        }
    }

    private fun showFABWithAnimation(
        icon: Int,
        contentDescription: String,
        onClick: () -> Unit,
        previousPosition: Int
    ) {
        val wasVisible = fabAction.visibility == View.VISIBLE
        val isMorphTransition = (previousPosition == 0 && viewPager.currentItem == 1) ||
                                (previousPosition == 1 && viewPager.currentItem == 0)

        if (wasVisible && isMorphTransition) {
            // Set content immediately so accessibility and state are correct; morph is visual only
            fabAction.setImageResource(icon)
            fabAction.contentDescription = contentDescription
            fabAction.setOnClickListener { onClick() }
            // Morph animation: scale down → scale up (200ms total)
            val scaleDown = ObjectAnimator.ofFloat(fabAction, "scaleX", 1f, 0f).apply {
                duration = 100
            }
            val scaleDownY = ObjectAnimator.ofFloat(fabAction, "scaleY", 1f, 0f).apply {
                duration = 100
            }
            val scaleUp = ObjectAnimator.ofFloat(fabAction, "scaleX", 0f, 1f).apply {
                duration = 100
                startDelay = 100
            }
            val scaleUpY = ObjectAnimator.ofFloat(fabAction, "scaleY", 0f, 1f).apply {
                duration = 100
                startDelay = 100
            }

            AnimatorSet().apply {
                playTogether(scaleDown, scaleDownY, scaleUp, scaleUpY)
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } else if (!wasVisible) {
            // Slide up animation (200ms) - but only if previousPosition >= 0 (coming from another tab)
            // If previousPosition < 0, this is initial show after data load, so show immediately
            if (previousPosition >= 0) {
                fabAction.setImageResource(icon)
                fabAction.contentDescription = contentDescription
                fabAction.setOnClickListener { onClick() }
                fabAction.visibility = View.VISIBLE
                fabAction.alpha = 0f
                
                // Ensure view is measured before getting height
                fabAction.post {
                    val height = if (fabAction.height > 0) fabAction.height.toFloat() else 56f // Default FAB height
                    fabAction.translationY = height

                    val slideUp = ObjectAnimator.ofFloat(fabAction, "translationY", height, 0f).apply {
                        duration = 200
                    }
                    val fadeIn = ObjectAnimator.ofFloat(fabAction, "alpha", 0f, 1f).apply {
                        duration = 200
                    }

                    AnimatorSet().apply {
                        playTogether(slideUp, fadeIn)
                        interpolator = AccelerateDecelerateInterpolator()
                        start()
                    }
                }
            } else {
                // Initial show after data load - show immediately without animation
                // Reset any previous animation state first
                fabAction.clearAnimation()
                // Cancel any running animations
                fabAction.animate().cancel()
                // Reset all properties to ensure clean state
                fabAction.alpha = 1f
                fabAction.translationY = 0f
                fabAction.scaleX = 1f
                fabAction.scaleY = 1f
                // Set content
                fabAction.setImageResource(icon)
                fabAction.contentDescription = contentDescription
                fabAction.setOnClickListener { onClick() }
                // Show immediately - no animation
                fabAction.visibility = View.VISIBLE
            }
        } else {
            // Just update icon and click listener without animation
            fabAction.setImageResource(icon)
            fabAction.contentDescription = contentDescription
            fabAction.setOnClickListener { onClick() }
        }
    }

    private fun hideFABWithAnimation(previousPosition: Int) {
        if (fabAction.visibility == View.GONE) return

        // Slide down animation (200ms)
        fabAction.post {
            val height = if (fabAction.height > 0) fabAction.height.toFloat() else 56f // Default FAB height
            val slideDown = ObjectAnimator.ofFloat(fabAction, "translationY", 0f, height).apply {
                duration = 200
            }
            val fadeOut = ObjectAnimator.ofFloat(fabAction, "alpha", 1f, 0f).apply {
                duration = 200
            }

            slideDown.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    fabAction.visibility = View.GONE
                    fabAction.alpha = 1f
                    fabAction.translationY = 0f
                }
            })

            AnimatorSet().apply {
                playTogether(slideDown, fadeOut)
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun loadGroupDetails() {
        val groupId = this.groupId ?: return

        lifecycleScope.launch {
            try {
                val context = context ?: return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(context)
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    if (isAdded) {
                        Toast.makeText(context, "Session expired. Please sign in again.", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val (response, members) = coroutineScope {
                    val detailsDeferred = async(Dispatchers.IO) {
                        ApiClient.getGroupDetails(accessToken, groupId)
                    }
                    val membersDeferred = async(Dispatchers.IO) {
                        try {
                            ApiClient.getGroupMembers(accessToken, groupId).members
                        } catch (_: Exception) {
                            null
                        }
                    }
                    // Fetch current user ID once if we don't already have it
                    if (currentUserId == null) {
                        async(Dispatchers.IO) {
                            try {
                                currentUserId = ApiClient.getMyUser(accessToken).id
                            } catch (_: Exception) { /* non-fatal */ }
                        }
                    }
                    val details = detailsDeferred.await()
                    val membersList = membersDeferred.await()
                    Pair(details, membersList)
                }

                if (!isAdded) return@launch
                groupDetail = response

                updateHeader(response, members)

                // Daily Pulse widget
                val uid = currentUserId ?: ""
                // Default hasGoals=true; widget hides itself when members.size <= 1
                if (members != null) {
                    val animate = lastKnownMembers != null
                    dailyPulseWidget.setFragmentManager(childFragmentManager, groupId)
                    dailyPulseWidget.bindMembers(members, uid, hasGoals = true, animate = animate)
                    lastKnownMembers = members
                    if (members.size > 1) {
                        AnalyticsLogger.logEvent(
                            AnalyticsEvents.DAILY_PULSE_VIEWED,
                            Bundle().apply {
                                putString("group_id", groupId)
                                putInt("member_count", members.size)
                                putInt("logged_count", members.count { it.logged_this_period })
                            }
                        )
                    }
                } else {
                    dailyPulseWidget.showLoading()
                }

                updateCommLinkRow(response.comm_platform, response.comm_link)
                loadGroupIcon(
                    hasIcon = response.has_icon,
                    iconEmoji = response.icon_emoji,
                    iconColor = response.icon_color,
                    groupNameText = response.name,
                    iconUrl = response.icon_url
                )
                setupIconTapForAdmin(response)

                viewPager.postDelayed({
                    if (!isAdded) return@postDelayed
                    val isAdmin = response.user_role == "admin" || response.user_role == "creator"
                    val goalsTabFragment = childFragmentManager.fragments.firstOrNull {
                        it is GoalsTabFragment
                    } as? GoalsTabFragment
                    goalsTabFragment?.updateAdminStatus(isAdmin)
                    goalsTabFragment?.updateChallengeLoggingStatus(
                        response.is_challenge,
                        response.challenge_status
                    )
                    val membersTabFragment = childFragmentManager.fragments.firstOrNull {
                        it is MembersTabFragment
                    } as? MembersTabFragment
                    membersTabFragment?.updateAdminStatus(isAdmin)
                    membersTabFragment?.updateCommLink(response.comm_platform, response.comm_link)

                    val currentItem = viewPager.currentItem
                    updateFABForTab(currentItem, -1)

                    // Show invite sheet if requested and not yet shown
                    if (arguments?.getBoolean(ARG_OPEN_INVITE_SHEET) == true && !hasOpenedInitialInviteSheet) {
                        hasOpenedInitialInviteSheet = true
                        showInviteMembersSheet()
                    }
                }, 50)
            } catch (e: ApiException) {
                context?.let { ctx ->
                    Toast.makeText(ctx, "Failed to load group details: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, "Failed to load group details. Please try again.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateCommLinkRow(commPlatform: String?, commLink: String?) {
        if (!commPlatform.isNullOrEmpty() && !commLink.isNullOrEmpty()) {
            val label = when (commPlatform) {
                "discord"  -> getString(R.string.comm_link_join_discord)
                "whatsapp" -> getString(R.string.comm_link_join_whatsapp)
                "telegram" -> getString(R.string.comm_link_join_telegram)
                else       -> return
            }
            commLinkRow.text = "$label →"
            commLinkRow.visibility = View.VISIBLE
            commLinkRow.setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(commLink))
                    startActivity(intent)
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), getString(R.string.comm_link_open_failed), Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            commLinkRow.visibility = View.GONE
        }
    }

    private fun setupOverflowMenu() {
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.group_detail_overflow, menu)

                // Show/hide menu items based on role
                groupDetail?.let { detail ->
                    val isCreator = detail.user_role == "creator"
                    val isAdmin = detail.user_role == "admin" || isCreator

                    menu.findItem(R.id.menu_edit_group).isVisible = isAdmin
                    menu.findItem(R.id.menu_manage_members).isVisible = isAdmin
                    menu.findItem(R.id.menu_invite_members).isVisible = isAdmin
                    menu.findItem(R.id.menu_regenerate_invite).isVisible = isAdmin
                    val challengeStatus = effectiveChallengeStatus(detail)
                    menu.findItem(R.id.menu_cancel_challenge).isVisible =
                        isCreator && detail.is_challenge && (challengeStatus == "upcoming" || challengeStatus == "active")
                    menu.findItem(R.id.menu_delete_group).isVisible = isCreator
                }
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                return when (menuItem.itemId) {
                    R.id.menu_edit_group -> {
                        groupDetail?.let { detail ->
                            val edit = EditGroupFragment.newInstance(
                                groupId = detail.id,
                                name = detail.name,
                                description = detail.description,
                                iconEmoji = detail.icon_emoji,
                                iconColor = detail.icon_color,
                                iconUrl = detail.icon_url,
                                hasIcon = detail.has_icon,
                                isCreator = detail.user_role == "creator",
                                visibility = detail.visibility,
                                category = detail.category,
                                spotLimit = detail.spot_limit,
                                commPlatform = detail.comm_platform,
                                commLink = detail.comm_link
                            )
                            requireActivity().supportFragmentManager.commit {
                                replace(R.id.fragment_container, edit)
                                addToBackStack(null)
                            }
                        } ?: run {
                            Toast.makeText(requireContext(), getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                        }
                        true
                    }
                    R.id.menu_manage_members -> {
                        // TODO: Navigate to manage members flow
                        Toast.makeText(requireContext(), "Manage Members (coming soon)", Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.menu_invite_members -> {
                        showInviteMembersSheet()
                        true
                    }
                    R.id.menu_regenerate_invite -> {
                        showRegenerateInviteConfirmation()
                        true
                    }
                    R.id.menu_cancel_challenge -> {
                        showCancelChallengeConfirmation()
                        true
                    }
                    R.id.menu_export_progress -> {
                        showExportProgressDialog()
                        true
                    }
                    R.id.menu_leave_group -> {
                        val gid = groupId
                        if (gid != null) showLeaveGroupConfirmation(gid) else Toast.makeText(requireContext(), getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                        true
                    }
                    R.id.menu_delete_group -> {
                        val gid = groupId
                        if (gid != null) {
                            val name = groupDetail?.name ?: arguments?.getString(ARG_GROUP_NAME) ?: ""
                            showDeleteGroupConfirmation(name, gid)
                        }
                        true
                    }
                    else -> false
                }
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    private fun showDeleteGroupConfirmation(groupName: String, gid: String) {
        val view = layoutInflater.inflate(R.layout.dialog_delete_group_confirm, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_group_dialog_title, groupName))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val ctx = context ?: return@launch
                        val tokenManager = SecureTokenManager.Companion.getInstance(ctx)
                        val token = tokenManager.getAccessToken()
                        if (token == null) {
                            Toast.makeText(ctx, getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        withContext(Dispatchers.IO) { ApiClient.deleteGroup(token, gid) }
                        if (!isAdded) return@launch
                        Toast.makeText(ctx, getString(R.string.group_deleted_toast), Toast.LENGTH_SHORT).show()
                        activity?.let {
                            it.setResult(GroupDetailActivity.Companion.RESULT_GROUP_DELETED)
                            it.finish()
                        }
                    } catch (e: ApiException) {
                        context?.let { ctx ->
                            Toast.makeText(ctx, e.message ?: getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        context?.let { ctx ->
                            Toast.makeText(ctx, getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .create()
        dialog.show()
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton?.isEnabled = false
        positiveButton?.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondary))
        val editText = view.findViewById<TextInputEditText>(R.id.delete_confirm_input)
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                positiveButton?.isEnabled = (editText.text?.toString()?.trim() == "delete")
            }
        })
    }

    private fun showRegenerateInviteConfirmation() {
        val gid = groupId ?: return
        val view = layoutInflater.inflate(R.layout.dialog_regenerate_invite_confirm, null)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.regenerate_invite_title))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.regenerate_invite_confirm_button)) { _, _ ->
                performRegenerateInvite(gid)
            }
            .create()
        dialog.show()
        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton?.isEnabled = false
        positiveButton?.setTextColor(ContextCompat.getColor(requireContext(), R.color.secondary))
        val editText = view.findViewById<TextInputEditText>(R.id.regenerate_confirm_input)
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                positiveButton?.isEnabled = (editText.text?.toString()?.trim()?.equals("regenerate", ignoreCase = true) == true)
            }
        })
    }

    private fun performRegenerateInvite(gid: String) {
        lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(ctx)
                val token = tokenManager.getAccessToken()
                if (token == null) {
                    Toast.makeText(ctx, getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) { ApiClient.regenerateInviteCode(token, gid) }
                if (!isAdded) return@launch
                Toast.makeText(ctx, getString(R.string.regenerate_code_success), Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                context?.let { ctx ->
                    Toast.makeText(ctx, e.message ?: getString(R.string.regenerate_code_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, getString(R.string.regenerate_code_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLeaveGroupConfirmation(gid: String) {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.menu_leave_group))
            .setMessage(getString(R.string.leave_group_confirmation))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.menu_leave_group)) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val ctx = context ?: return@launch
                        val tokenManager = SecureTokenManager.Companion.getInstance(ctx)
                        val token = tokenManager.getAccessToken()
                        if (token == null) {
                            if (isAdded) Toast.makeText(ctx, getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        withContext(Dispatchers.IO) { ApiClient.leaveGroup(token, gid) }
                        // Unsubscribe from FCM topics for this group
                        FcmTopicManager.unsubscribeFromGroupTopics(gid)
                        if (!isAdded) return@launch
                        Toast.makeText(ctx, getString(R.string.leave_group_toast), Toast.LENGTH_SHORT).show()
                        activity?.let {
                            it.setResult(GroupDetailActivity.Companion.RESULT_LEFT_GROUP)
                            it.finish()
                        }
                    } catch (e: ApiException) {
                        val msg = e.message ?: getString(R.string.error_failed_to_load_groups)
                        context?.let { ctx ->
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        context?.let { ctx ->
                            Toast.makeText(ctx, getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.secondary)
        )
    }

    private fun showExportProgressDialog() {
        val gid = groupId ?: return
        val groupName = groupDetail?.name ?: arguments?.getString(ARG_GROUP_NAME) ?: "Group"
        val view = layoutInflater.inflate(R.layout.dialog_export_progress, null)
        val timeframeGroup = view.findViewById<RadioGroup>(R.id.export_progress_timeframe_group)
        val cancelButton = view.findViewById<MaterialButton>(R.id.export_progress_cancel)
        val exportButton = view.findViewById<MaterialButton>(R.id.export_progress_export)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setCancelable(true)
            .create()

        cancelButton.setOnClickListener { dialog.dismiss() }
        exportButton.setOnClickListener {
            val selectedId = timeframeGroup.checkedRadioButtonId
            val (startDate, endDate) = when (selectedId) {
                R.id.export_progress_30_days -> {
                    val end = LocalDate.now()
                    Pair(end.minusDays(29).format(DateTimeFormatter.ISO_LOCAL_DATE), end.format(DateTimeFormatter.ISO_LOCAL_DATE))
                }
                R.id.export_progress_3_months -> {
                    val end = LocalDate.now()
                    Pair(end.minusMonths(3).plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE), end.format(DateTimeFormatter.ISO_LOCAL_DATE))
                }
                R.id.export_progress_6_months -> {
                    val end = LocalDate.now()
                    Pair(end.minusMonths(6).plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE), end.format(DateTimeFormatter.ISO_LOCAL_DATE))
                }
                R.id.export_progress_12_months -> {
                    val end = LocalDate.now()
                    Pair(end.minusMonths(12).plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE), end.format(DateTimeFormatter.ISO_LOCAL_DATE))
                }
                else -> {
                    val end = LocalDate.now()
                    Pair(end.minusMonths(3).plusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE), end.format(DateTimeFormatter.ISO_LOCAL_DATE))
                }
            }
            val userTimezone = TimeZone.getDefault().id
            val sanitizedName = groupName.replace(Regex("[^a-zA-Z0-9_-]"), "_").take(100)
            val defaultFilename = "Pursue_Progress_${sanitizedName}_${startDate}_to_${endDate}.xlsx"

            lifecycleScope.launch {
                val ctx = context ?: return@launch
                val token = SecureTokenManager.Companion.getInstance(ctx).getAccessToken()
                if (token == null) {
                    if (isAdded) {
                        dialog.dismiss()
                        Toast.makeText(ctx, getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val validation = try {
                    withContext(Dispatchers.IO) { ApiClient.validateExportRange(token, gid, startDate, endDate) }
                } catch (e: ApiException) {
                    if (isAdded) {
                        dialog.dismiss()
                        Toast.makeText(ctx, e.message ?: getString(R.string.export_progress_failed), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (!isAdded) return@launch
                if (!validation.valid) {
                    dialog.dismiss()
                    showExportLimitReachedDialog(startDate, endDate, validation.requested_days, validation.max_days_allowed)
                } else {
                    pendingExportGid = gid
                    pendingExportStartDate = startDate
                    pendingExportEndDate = endDate
                    pendingExportUserTimezone = userTimezone
                    dialog.dismiss()
                    createDocumentLauncher.launch(defaultFilename)
                }
            }
        }

        dialog.show()
    }

    private fun showExportLimitReachedDialog(startDate: String, endDate: String, requestedDays: Int, maxDaysAllowed: Int) {
        val message = getString(R.string.export_limit_reached_message) + "\n\n" +
            getString(R.string.export_limit_reached_selected, startDate, endDate, requestedDays) + "\n\n" +
            getString(R.string.export_limit_reached_upgrade)
        val d = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.export_limit_reached_title)
            .setMessage(message)
            .setNegativeButton(R.string.adjust_dates, null)
            .setPositiveButton(R.string.upgrade_to_premium) { _, _ ->
                (requireActivity() as? GroupDetailActivity)?.showPremiumScreen()
            }
            .show()
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            setTextColor(ContextCompat.getColor(requireContext(), R.color.secondary))
            setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun onExportUriSelected(uri: Uri) {
        val gid = pendingExportGid ?: return
        val startDate = pendingExportStartDate ?: return
        val endDate = pendingExportEndDate ?: return
        val userTimezone = pendingExportUserTimezone ?: return

        pendingExportGid = null
        pendingExportStartDate = null
        pendingExportEndDate = null
        pendingExportUserTimezone = null

        val progressView = layoutInflater.inflate(R.layout.dialog_export_progress_loading, null)
        val progressDialog = MaterialAlertDialogBuilder(requireContext())
            .setView(progressView)
            .setCancelable(false)
            .create()
        progressDialog.show()

        lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(ctx)
                val token = tokenManager.getAccessToken()
                if (token == null) {
                    if (isAdded) {
                        progressDialog.dismiss()
                        Toast.makeText(ctx, getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                val bytes = withContext(Dispatchers.IO) {
                    ApiClient.exportGroupProgress(token, gid, startDate, endDate, userTimezone)
                }
                if (!isAdded) {
                    progressDialog.dismiss()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: throw IOException("Failed to open output stream")
                }
                if (isAdded) {
                    progressDialog.dismiss()
                    showExportSuccessDialog(uri)
                }
            } catch (e: ApiException) {
                if (isAdded) {
                    progressDialog.dismiss()
                    Toast.makeText(context ?: return@launch, e.message ?: getString(R.string.export_progress_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: IOException) {
                if (isAdded) {
                    progressDialog.dismiss()
                    Toast.makeText(context ?: return@launch, getString(R.string.export_progress_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    progressDialog.dismiss()
                    Toast.makeText(context ?: return@launch, getString(R.string.export_progress_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showExportSuccessDialog(exportUri: Uri) {
        if (!isAdded) return
        val openFileIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(exportUri, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putExtra(Intent.EXTRA_STREAM, exportUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.export_progress_success))
            .setPositiveButton(R.string.export_progress_open_file) { _, _ ->
                if (!isAdded) return@setPositiveButton
                try {
                    startActivity(Intent.createChooser(openFileIntent, null))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(requireContext(), getString(R.string.export_progress_no_app_to_open_file), Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.export_progress_share) { _, _ ->
                if (!isAdded) return@setNegativeButton
                startActivity(Intent.createChooser(shareIntent, null))
            }
            .setNeutralButton(android.R.string.ok, null)
            .show()
    }

    private fun setupIconTapForAdmin(detail: GroupDetailResponse) {
        val isAdmin = detail.user_role == "admin" || detail.user_role == "creator"
        groupIconContainer.isClickable = isAdmin
        groupIconContainer.isFocusable = isAdmin
        if (isAdmin) {
            val a = requireContext().theme.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            groupIconContainer.foreground = a.getDrawable(0)
            a.recycle()
            groupIconContainer.setOnClickListener { showIconPickerForDetail() }
        } else {
            groupIconContainer.foreground = null
            groupIconContainer.setOnClickListener(null)
        }
    }

    fun showInviteMembersSheet() {
        val gid = groupId ?: return
        val name = groupDetail?.name ?: arguments?.getString(ARG_GROUP_NAME) ?: ""
        val role = groupDetail?.user_role ?: "member"
        InviteMembersBottomSheet.Companion.show(childFragmentManager, gid, name, role)
    }

    private fun showIconPickerForDetail() {
        val detail = groupDetail ?: return
        val gid = groupId ?: return
        val sheet = IconPickerBottomSheet.Companion.newInstance(
            R.string.icon_picker_title,
            initialEmoji = detail.icon_emoji,
            initialColor = detail.icon_color ?: IconPickerBottomSheet.Companion.getRandomDefaultColor()
        )
        sheet.setIconSelectionListener(object : IconPickerBottomSheet.IconSelectionListener {
            override fun onIconSelected(emoji: String?, color: String?, iconUrl: String?) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val ctx = context ?: return@launch
                        val tokenManager = SecureTokenManager.Companion.getInstance(ctx)
                        val token = tokenManager.getAccessToken()
                        if (token == null) {
                            Toast.makeText(ctx, getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        withContext(Dispatchers.IO) {
                            ApiClient.patchGroup(
                                accessToken = token,
                                groupId = gid,
                                iconEmoji = emoji,
                                iconColor = if (emoji != null) color else null,
                                iconUrl = iconUrl
                            )
                        }
                        if (!isAdded) return@launch
                        Toast.makeText(ctx, getString(R.string.group_updated_toast), Toast.LENGTH_SHORT).show()
                        groupDetail = detail.copy(
                            icon_emoji = emoji,
                            icon_color = if (emoji != null) color else detail.icon_color
                        )
                        val d = groupDetail!!
                        loadGroupIcon(
                            hasIcon = d.has_icon,
                            iconEmoji = d.icon_emoji,
                            iconColor = d.icon_color,
                            groupNameText = d.name,
                            iconUrl = d.icon_url
                        )
                    } catch (e: ApiException) {
                        context?.let { ctx ->
                            Toast.makeText(ctx, e.message ?: getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        context?.let { ctx ->
                            Toast.makeText(ctx, getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        })
        sheet.show(childFragmentManager, "IconPickerBottomSheet")
    }

    private fun updateHeader(detail: GroupDetailResponse, members: List<GroupMember>? = null) {
        val goalsTabFragment = childFragmentManager.fragments.firstOrNull {
            it is GoalsTabFragment
        } as? GoalsTabFragment
        goalsTabFragment?.updateChallengeLoggingStatus(detail.is_challenge, effectiveChallengeStatus(detail))
        val activeGoalsCount = goalsTabFragment?.getActiveGoalsCount() ?: 0

        updateGoalsCountSubtitle(detail.member_count, activeGoalsCount, detail.visibility)

        val creatorName = members?.firstOrNull { it.user_id == detail.creator_user_id || it.role == "creator" }?.display_name
        createdBy.text = getString(R.string.created_by, creatorName ?: getString(R.string.unknown))

        updateChallengeHeader(detail)

        // Update heat section
        updateHeatSection(detail)

        requireActivity().invalidateOptionsMenu()
    }

    /**
     * Update the heat section display in the header.
     * Shows heat tier icon, name, score, streak, and GCR details for tiers 1-7.
     * Hidden for tier 0 (Cold).
     */
    private fun updateHeatSection(detail: GroupDetailResponse) {
        detail.heat?.let { heat ->
            if (heat.tier > 0) {
                // Show heat icon
                HeatUtils.getTierDrawable(heat.tier)?.let { resId ->
                    heatIconDetail.setImageResource(resId)
                    heatIconDetail.contentDescription = HeatUtils.getContentDescription(
                        requireContext(), heat.tier_name, heat.score
                    )
                }
                
                // Show tier name and score
                heatTierLabel.text = heat.tier_name
                heatScoreLabel.text = getString(R.string.heat_score_format, heat.score.toInt())
                heatSection.visibility = View.VISIBLE

                // Build details string (streak, yesterday GCR, baseline)
                val detailParts = mutableListOf<String>()
                if (heat.streak_days > 0) {
                    detailParts.add(getString(R.string.heat_streak_format, heat.streak_days))
                }
                heat.yesterday_gcr?.let { gcr ->
                    val gcrStr = heat.baseline_gcr?.let { baseline ->
                        getString(R.string.heat_gcr_with_baseline, (gcr * 100).toInt(), (baseline * 100).toInt())
                    } ?: getString(R.string.heat_gcr_format, (gcr * 100).toInt())
                    detailParts.add(gcrStr)
                }
                if (detailParts.isNotEmpty()) {
                    heatDetails.text = detailParts.joinToString(" · ")
                    heatDetails.visibility = View.VISIBLE
                } else {
                    heatDetails.visibility = View.GONE
                }
            } else {
                // Tier 0 (Cold) - hide heat section
                heatSection.visibility = View.GONE
                heatDetails.visibility = View.GONE
            }
        } ?: run {
            // No heat data - hide heat section
            heatSection.visibility = View.GONE
            heatDetails.visibility = View.GONE
        }
    }

    private fun updateChallengeHeader(detail: GroupDetailResponse) {
        if (!detail.is_challenge || detail.challenge_start_date.isNullOrBlank() || detail.challenge_end_date.isNullOrBlank()) {
            challengeHeaderContainer.visibility = View.GONE
            return
        }

        val startDate = parseChallengeDate(detail.challenge_start_date)
        val endDate = parseChallengeDate(detail.challenge_end_date)
        val now = nowProvider()
        val localToday = now.toLocalDate()
        val progress = ChallengeDateUiUtils.computeDayProgress(
            startDate = startDate,
            endDate = endDate,
            status = ChallengeDateUiUtils.effectiveStatus(
                startDate = startDate,
                endDate = endDate,
                serverStatus = detail.challenge_status,
                today = localToday
            ),
            today = localToday,
            now = now
        )

        challengeHeaderContainer.visibility = View.VISIBLE
        challengeHeaderDayProgress.text = "${progress.dayLabel} · ${progress.daysRemainingLabel}"
        challengeHeaderProgressBar.progress = progress.progressPercent

        val status = effectiveChallengeStatus(detail)
        if (status == "completed") {
            challengeInviteButton.visibility = View.GONE
            challengeShareButton.text = getString(R.string.challenge_share_result_button)
            challengeShareButton.setOnClickListener { shareChallengeResult() }
        } else {
            challengeInviteButton.visibility = View.VISIBLE
            challengeShareButton.text = getString(R.string.challenge_share_button)
            challengeShareButton.setOnClickListener { shareChallenge() }
        }
    }

    private fun parseChallengeDate(value: String): LocalDate {
        return try {
            LocalDate.parse(value.take(10))
        } catch (_: Exception) {
            LocalDate.now()
        }
    }

    private fun effectiveChallengeStatus(detail: GroupDetailResponse): String {
        if (!detail.is_challenge) return detail.challenge_status ?: "active"
        val start = detail.challenge_start_date?.let { parseChallengeDate(it) }
        val end = detail.challenge_end_date?.let { parseChallengeDate(it) }
        if (start == null || end == null) return detail.challenge_status ?: "active"
        return ChallengeDateUiUtils.effectiveStatus(
            startDate = start,
            endDate = end,
            serverStatus = detail.challenge_status
        )
    }

    private fun shareChallenge() {
        val gid = groupId ?: return
        lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                val token = SecureTokenManager.getInstance(ctx).getAccessToken()
                if (token == null) {
                    Toast.makeText(ctx, getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val invite = withContext(Dispatchers.IO) { ApiClient.getGroupInviteCode(token, gid) }
                val detail = groupDetail
                val inviteCardData = invite.invite_card_data
                if (inviteCardData != null) {
                    val cardJson = gson.toJson(inviteCardData)
                    val intent = ShareableMilestoneCardActivity.createIntent(
                        context = ctx,
                        notificationId = "",
                        cardDataJson = cardJson,
                        autoAction = null,
                        groupId = gid
                    )
                    startActivity(intent)
                } else {
                    val shareText = getString(
                        R.string.challenge_share_text,
                        detail?.name ?: getString(R.string.start_challenge),
                        invite.share_url
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.challenge_share_button)))
                }
            } catch (e: ApiException) {
                context?.let { ctx ->
                    Toast.makeText(ctx, e.message ?: getString(R.string.challenge_share_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, getString(R.string.challenge_share_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun shareChallengeResult() {
        val gid = groupId ?: return
        lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                val token = SecureTokenManager.getInstance(ctx).getAccessToken()
                if (token == null) {
                    Toast.makeText(ctx, getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val notifications = withContext(Dispatchers.IO) { ApiClient.getNotifications(token, limit = 50) }
                val match = notifications.notifications.firstOrNull { notif ->
                    notif.group?.id == gid && notif.toChallengeCompletionCardDataOrNull() != null
                }
                if (match != null) {
                    val cardJson = gson.toJson(match.shareable_card_data)
                    val intent = ShareableMilestoneCardActivity.createIntent(
                        context = ctx,
                        notificationId = match.id,
                        cardDataJson = cardJson,
                        autoAction = null,
                        groupId = gid
                    )
                    startActivity(intent)
                } else {
                    val detail = groupDetail
                    val challengeName = detail?.name ?: getString(R.string.challenge_default_name)
                    val shareText = getString(R.string.challenge_completion_share_text, challengeName, "https://getpursue.app")
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, shareText)
                    }
                    startActivity(Intent.createChooser(intent, getString(R.string.challenge_share_result_button)))
                }
            } catch (e: ApiException) {
                context?.let { ctx ->
                    Toast.makeText(ctx, e.message ?: getString(R.string.challenge_share_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, getString(R.string.challenge_share_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showCancelChallengeConfirmation() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.menu_cancel_challenge))
            .setMessage(getString(R.string.challenge_cancel_confirmation))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.menu_cancel_challenge)) { _, _ ->
                performCancelChallenge()
            }
            .show()
    }

    private fun performCancelChallenge() {
        val gid = groupId ?: return
        lifecycleScope.launch {
            try {
                val ctx = context ?: return@launch
                val token = SecureTokenManager.getInstance(ctx).getAccessToken()
                if (token == null) {
                    Toast.makeText(ctx, getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) { ApiClient.cancelChallenge(token, gid) }
                if (!isAdded) return@launch
                Toast.makeText(ctx, getString(R.string.challenge_cancel_success), Toast.LENGTH_SHORT).show()
                loadGroupDetails()
            } catch (e: ApiException) {
                context?.let { ctx ->
                    Toast.makeText(ctx, e.message ?: getString(R.string.challenge_cancel_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                context?.let { ctx ->
                    Toast.makeText(ctx, getString(R.string.challenge_cancel_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * Show a dialog explaining how the Group Heat score is calculated.
     * Includes the current group's flame icon and tier name.
     */
    private fun showHeatInfoDialog() {
        val heat = groupDetail?.heat ?: return
        val dialogView = layoutInflater.inflate(R.layout.dialog_heat_info, null)

        val icon = dialogView.findViewById<ImageView>(R.id.heat_info_icon)
        val tierLabel = dialogView.findViewById<TextView>(R.id.heat_info_tier)
        val body = dialogView.findViewById<TextView>(R.id.heat_info_body)

        HeatUtils.getTierDrawable(heat.tier)?.let { icon.setImageResource(it) }
        tierLabel.text = heat.tier_name
        body.text = getString(R.string.heat_info_body)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.heat_info_title))
            .setView(dialogView)
            .setPositiveButton(getString(R.string.heat_info_ok), null)
            .show()
    }

    /**
     * Update the goals count in the subtitle.
     * Called by GoalsTabFragment when goals finish loading.
     */
    fun updateGoalsCount(goalCount: Int) {
        groupDetail?.let { detail ->
            updateGoalsCountSubtitle(detail.member_count, goalCount, detail.visibility)
        }
    }

    private fun updateGoalsCountSubtitle(memberCount: Int, goalCount: Int, visibility: String?) {
        subtitleMembersGoals.text = getString(R.string.members_count, memberCount)
        textGoalsCount.text = getString(R.string.active_goals_count, goalCount)
        
        if (visibility != null) {
            textVisibility.text = visibility
            val iconRes = if (visibility == "public") R.drawable.ic_visibility else R.drawable.ic_visibility_off
            iconVisibility.setImageResource(iconRes)
            
            visibilityDivider.visibility = View.VISIBLE
            iconVisibility.visibility = View.VISIBLE
            textVisibility.visibility = View.VISIBLE
        } else {
            visibilityDivider.visibility = View.GONE
            iconVisibility.visibility = View.GONE
            textVisibility.visibility = View.GONE
        }
    }

    private fun loadGroupIcon(
        hasIcon: Boolean,
        iconEmoji: String?,
        iconColor: String?,
        groupNameText: String,
        iconUrl: String? = null
    ) {
        val fallbackColor = ContextCompat.getColor(requireContext(), R.color.primary)
        // Priority 1: icon_url (bundled res:// or remote https://)
        if (iconUrl != null) {
            val loaded = app.getpursue.utils.IconUrlUtils.loadInto(requireContext(), iconUrl, groupIconImage)
            if (loaded) {
                groupIconImage.visibility = View.VISIBLE
                groupIconEmoji.visibility = View.GONE
                groupIconLetter.visibility = View.GONE
                return
            }
        }
        // Priority 2: BYTEA icon from server
        if (hasIcon && groupId != null) {
            groupIconImage.visibility = View.VISIBLE
            groupIconEmoji.visibility = View.GONE
            groupIconLetter.visibility = View.GONE

            val imageUrl = "${ApiClient.getBaseUrl()}/groups/$groupId/icon"
            Glide.with(this)
                .load(imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .centerCrop()
                .circleCrop()
                .error(R.drawable.ic_pursue_logo)
                .into(groupIconImage)
        } else if (iconEmoji != null) {
            groupIconImage.visibility = View.GONE
            groupIconEmoji.visibility = View.VISIBLE
            groupIconLetter.visibility = View.GONE
            val fallbackEmoji = if (groupDetail?.is_challenge == true) "🏆" else "📁"
            groupIconEmoji.text = EmojiUtils.normalizeOrFallback(iconEmoji, fallbackEmoji)
            try {
                groupIconEmoji.backgroundTintList = ColorStateList.valueOf(
                    if (iconColor != null) Color.parseColor(iconColor) else fallbackColor
                )
            } catch (_: IllegalArgumentException) {
                groupIconEmoji.backgroundTintList = ColorStateList.valueOf(fallbackColor)
            }
        } else {
            groupIconImage.visibility = View.GONE
            groupIconEmoji.visibility = View.GONE
            groupIconLetter.visibility = View.VISIBLE
            val firstLetter = groupNameText.takeIf { it.isNotEmpty() }?.first()?.uppercaseChar() ?: '?'
            groupIconLetter.text = firstLetter.toString()
            try {
                groupIconLetter.backgroundTintList = ColorStateList.valueOf(
                    if (iconColor != null) Color.parseColor(iconColor) else fallbackColor
                )
            } catch (_: IllegalArgumentException) {
                groupIconLetter.backgroundTintList = ColorStateList.valueOf(fallbackColor)
            }
        }
    }

    private fun startPulsePolling() {
        pollingJob?.cancel()
        pollingJob = lifecycleScope.launch {
            while (isActive) {
                delay(60_000L)
                if (!isAdded) break
                val gid = groupId ?: break
                val ctx = context ?: break
                val token = SecureTokenManager.getInstance(ctx).getAccessToken() ?: break
                try {
                    val fresh = withContext(Dispatchers.IO) {
                        ApiClient.getGroupMembers(token, gid).members
                    }
                    if (!isAdded) break
                    lastKnownMembers = fresh
                    dailyPulseWidget.updateMembers(fresh, animate = true)
                } catch (_: Exception) { /* silent — poll retry next cycle */ }
            }
        }
    }

    /**
     * ViewPager adapter for Group Detail tabs.
     */
    private inner class GroupDetailPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment {
            val groupId = this@GroupDetailFragment.groupId ?: ""
            val isAdmin = this@GroupDetailFragment.groupDetail?.let { detail ->
                detail.user_role == "admin" || detail.user_role == "creator"
            } ?: false
            
            return when (position) {
                0 -> GoalsTabFragment.newInstance(groupId, isAdmin)
                1 -> MembersTabFragment.newInstance(groupId, isAdmin)
                2 -> ActivityTabFragment.newInstance(groupId)
                else -> throw IllegalArgumentException("Invalid tab position: $position")
            }
        }
    }
}
