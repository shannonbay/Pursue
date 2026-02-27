package app.getpursue.ui.views

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import app.getpursue.R
import app.getpursue.data.analytics.AnalyticsEvents
import app.getpursue.data.analytics.AnalyticsLogger
import app.getpursue.data.network.ApiClient
import app.getpursue.models.GroupMember
import app.getpursue.utils.GrayscaleTransformation
import app.getpursue.utils.ImageUtils
import com.bumptech.glide.Glide
import android.os.Bundle

/**
 * Daily Pulse widget — a horizontal avatar row showing who has logged in the current period.
 *
 * Tier behaviour:
 *  - Small  (1–5 members): all visible, no scroll or fade
 *  - Medium (6–20):        horizontally scrollable, right-edge fade
 *  - Large  (21+):         scrollable + fade + "+N more ›" pill tapping opens PulseGridBottomSheet
 *
 * Hide conditions: members.size <= 1, or !hasGoals.
 */
class DailyPulseWidget @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private enum class Tier { SMALL, MEDIUM, LARGE }

    companion object {
        private const val MAX_AVATARS_BEFORE_PILL = 20

        private val PULSE_COLORS = listOf(
            "#1976D2", "#F9A825", "#388E3C", "#C62828",
            "#6A1B9A", "#00838F", "#E65100", "#37474F"
        )

        fun avatarColorForUser(userId: String): Int {
            val h = Math.abs(userId.hashCode())
            return Color.parseColor(PULSE_COLORS[h % PULSE_COLORS.size])
        }
    }

    private val headerLabel: TextView
    private val headerCount: TextView
    private val scrollView: HorizontalScrollView
    private val avatarContainer: LinearLayout
    private val fadeRight: View

    private var lastMembers: List<GroupMember> = emptyList()
    private var fragmentManager: FragmentManager? = null
    private var groupId: String = ""
    private var currentUserId: String = ""
    private var memberNudgeGroups: Map<String, String> = emptyMap()

    init {
        LayoutInflater.from(context).inflate(R.layout.view_daily_pulse, this, true)
        headerLabel    = findViewById(R.id.pulse_header_label)
        headerCount    = findViewById(R.id.pulse_header_count)
        scrollView     = findViewById(R.id.pulse_scroll_view)
        avatarContainer = findViewById(R.id.pulse_avatar_container)
        fadeRight      = findViewById(R.id.pulse_fade_right)
    }

    /**
     * Call once from the hosting fragment to enable bottom-sheet navigation from avatar taps.
     */
    fun setFragmentManager(fm: FragmentManager, groupId: String) {
        this.fragmentManager = fm
        this.groupId = groupId
    }

    /** Show a minimal loading state (3 placeholder circles). */
    fun showLoading() {
        visibility = View.VISIBLE
        avatarContainer.removeAllViews()
        headerLabel.text = context.getString(R.string.pulse_header_today)
        headerCount.visibility = View.GONE
        fadeRight.visibility = View.GONE
        repeat(3) {
            val placeholder = View(context).apply {
                val size = dpToPx(40)
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = dpToPx(4)
                }
                setBackgroundResource(R.drawable.circle_background)
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    context.getColor(R.color.surface_variant)
                )
                alpha = 0.5f
            }
            avatarContainer.addView(placeholder)
        }
    }

    /** Hide the widget entirely. */
    fun showHidden() {
        visibility = View.GONE
    }

    /**
     * Bind fresh member data to the widget.
     *
     * @param members      List of group members (already includes logged_this_period).
     * @param currentUserId ID of the signed-in user (for sort order).
     * @param hasGoals     Whether the group has at least one goal.
     * @param animate      Whether to animate newly-logged members (false on first load).
     */
    fun bindMembers(
        members: List<GroupMember>,
        currentUserId: String,
        hasGoals: Boolean,
        animate: Boolean = false,
        memberNudgeGroups: Map<String, String> = emptyMap()
    ) {
        this.memberNudgeGroups = memberNudgeGroups
        this.currentUserId = currentUserId

        if (members.size <= 1 || !hasGoals) {
            showHidden()
            return
        }

        visibility = View.VISIBLE
        val sorted = sortMembers(members, currentUserId)
        val tier = when {
            members.size <= 5  -> Tier.SMALL
            members.size <= 20 -> Tier.MEDIUM
            else               -> Tier.LARGE
        }

        val loggedCount = members.count { it.logged_this_period }
        val allLogged   = loggedCount == members.size

        // Header label
        headerLabel.text = if (allLogged)
            context.getString(R.string.pulse_header_everyone_logged)
        else
            context.getString(R.string.pulse_header_today)

        // Header count (hidden for Small)
        if (tier == Tier.SMALL) {
            headerCount.visibility = View.GONE
        } else {
            headerCount.text = context.getString(R.string.pulse_logged_count, loggedCount, members.size)
            headerCount.visibility = View.VISIBLE
        }

        // Fade overlay
        fadeRight.visibility = if (tier == Tier.SMALL) View.GONE else View.VISIBLE

        // Determine which members changed logged state (for animation)
        val newlyLogged: Set<String> = if (animate && lastMembers.isNotEmpty()) {
            val oldNotLogged = lastMembers.filter { !it.logged_this_period }.map { it.user_id }.toSet()
            members.filter { it.logged_this_period && it.user_id in oldNotLogged }.map { it.user_id }.toSet()
        } else {
            emptySet()
        }

        // Render avatars
        avatarContainer.removeAllViews()
        val displayList = if (tier == Tier.LARGE) sorted.take(MAX_AVATARS_BEFORE_PILL) else sorted
        displayList.forEachIndexed { index, member ->
            val avatarView = buildAvatarView(member)
            avatarContainer.addView(avatarView)
            if (animate && member.user_id in newlyLogged) {
                Handler(Looper.getMainLooper()).postDelayed(
                    { animateLoggedIn(avatarView) },
                    (80L * index)
                )
            }
        }

        // Add "+N more ›" pill for Large tier
        if (tier == Tier.LARGE) {
            val remaining = members.size - MAX_AVATARS_BEFORE_PILL
            val pill = buildPillView(remaining, sorted)
            avatarContainer.addView(pill)
        }

        lastMembers = members
    }

    /**
     * Called from the 60-second poll in GroupDetailFragment with fresh member data.
     * Diffs against lastMembers and animates any newly-logged members.
     */
    fun updateMembers(freshMembers: List<GroupMember>, animate: Boolean = true) {
        if (visibility != View.VISIBLE) return
        bindMembers(freshMembers, currentUserId, hasGoals = true, animate = animate,
            memberNudgeGroups = this.memberNudgeGroups)
    }

    /**
     * Optimistically marks the signed-in user as having logged for this period.
     * Triggers the colored-ring + scale animation on their avatar.
     * No-op if the widget isn't visible or data isn't loaded yet.
     */
    fun markCurrentUserAsLogged() {
        if (visibility != View.VISIBLE) return
        if (currentUserId.isEmpty()) return
        val alreadyLogged = lastMembers.any { it.user_id == currentUserId && it.logged_this_period }
        if (alreadyLogged) return

        val now = java.time.Instant.now().toString()
        val updated = lastMembers.map { member ->
            if (member.user_id == currentUserId)
                member.copy(logged_this_period = true, last_log_at = now)
            else
                member
        }
        updateMembers(updated, animate = true)
    }

    // --- Private helpers ---

    private fun sortMembers(members: List<GroupMember>, currentUserId: String): List<GroupMember> {
        val logged  = members
            .filter { it.logged_this_period }
            .sortedByDescending { it.last_log_at ?: "" }
        val unlogged = members
            .filter { !it.logged_this_period }
            .sortedBy { it.display_name }
        return logged + unlogged
    }

    private fun buildAvatarView(member: GroupMember): View {
        val v   = LayoutInflater.from(context).inflate(R.layout.item_pulse_avatar, avatarContainer, false)
        val img = v.findViewById<ImageView>(R.id.avatar_image)
        val isActive = member.logged_this_period

        if (member.has_avatar) {
            val avatarUrl = "${ApiClient.getBaseUrl()}/users/${member.user_id}/avatar"
            var requestBuilder = Glide.with(context).load(avatarUrl)
            if (!isActive) {
                requestBuilder = requestBuilder.transform(GrayscaleTransformation())
            }
            requestBuilder.circleCrop().into(img)
        } else {
            val color = avatarColorForUser(member.user_id)
            img.setImageDrawable(ImageUtils.createLetterAvatar(context, member.display_name, color))
        }

        if (isActive) {
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dpToPx(3), avatarColorForUser(member.user_id))
            }
        } else {
            v.background = null
        }

        v.alpha = if (isActive) 1f else 0.5f
        img.contentDescription = if (isActive)
            context.getString(R.string.pulse_logged_today, member.display_name)
        else
            context.getString(R.string.pulse_not_logged, member.display_name)

        if (member.user_id != currentUserId) {
            img.setOnClickListener { onAvatarTap(member) }
        }
        return v
    }

    private fun buildPillView(remaining: Int, allSorted: List<GroupMember>): View {
        val pill = TextView(context).apply {
            val hPad = dpToPx(10)
            val vPad = dpToPx(8)
            setPadding(hPad, vPad, hPad, vPad)
            text = context.getString(R.string.pulse_n_more, remaining)
            textSize = 12f
            setTextColor(context.getColor(R.color.on_surface_variant))
            background = context.getDrawable(R.drawable.bg_pulse_pill)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(40)
            ).apply {
                marginEnd = dpToPx(4)
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            gravity = android.view.Gravity.CENTER
            setOnClickListener {
                AnalyticsLogger.logEvent(
                    AnalyticsEvents.DAILY_PULSE_GRID_OPENED,
                    Bundle().apply { putString("group_id", groupId) }
                )
                fragmentManager?.let { fm ->
                    PulseGridBottomSheet.show(fm, allSorted, currentUserId, groupId)
                }
            }
        }
        return pill
    }

    private fun onAvatarTap(member: GroupMember) {
        AnalyticsLogger.logEvent(
            AnalyticsEvents.DAILY_PULSE_AVATAR_TAPPED,
            Bundle().apply {
                putString("group_id", groupId)
                putString("logged", member.logged_this_period.toString())
            }
        )
        val fm = fragmentManager ?: return
        if (member.logged_this_period) {
            PulseLoggedMemberBottomSheet.show(
                fm,
                memberId    = member.user_id,
                displayName = member.display_name,
                hasAvatar   = member.has_avatar,
                lastLogAt   = member.last_log_at
            )
        } else {
            PulseNudgeBottomSheet.show(
                fm,
                memberId    = member.user_id,
                displayName = member.display_name,
                hasAvatar   = member.has_avatar,
                groupId     = memberNudgeGroups[member.user_id] ?: groupId
            )
        }
    }

    private fun animateLoggedIn(avatarView: View) {
        val scale = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(avatarView, "scaleX", 1f, 1.1f, 1f).apply { duration = 250 },
                ObjectAnimator.ofFloat(avatarView, "scaleY", 1f, 1.1f, 1f).apply { duration = 250 },
                ObjectAnimator.ofFloat(avatarView, "alpha", 0.5f, 1f).apply { duration = 200 }
            )
        }
        scale.start()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * context.resources.displayMetrics.density).toInt()
}
