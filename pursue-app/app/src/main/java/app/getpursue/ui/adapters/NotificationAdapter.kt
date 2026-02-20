package app.getpursue.ui.adapters

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.NotificationItem
import app.getpursue.utils.RelativeTimeUtils
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy

/**
 * RecyclerView adapter for the notification inbox list.
 *
 * Supports two view types:
 *  - TYPE_DEFAULT: compact row for all standard notification types
 *  - TYPE_RECAP_CARD: full inline weekly recap card for "weekly_recap" type
 */
class NotificationAdapter(
    private val items: List<NotificationItem>,
    private val context: Context,
    private val onItemClick: (NotificationItem) -> Unit,
    private val onShareClick: ((NotificationItem) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_DEFAULT = 0
        private const val TYPE_RECAP_CARD = 1
    }

    override fun getItemViewType(position: Int): Int =
        if (items[position].type == "weekly_recap") TYPE_RECAP_CARD else TYPE_DEFAULT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_RECAP_CARD) {
            RecapCardViewHolder(inflater.inflate(R.layout.item_notification_weekly_recap, parent, false))
        } else {
            NotificationViewHolder(inflater.inflate(R.layout.item_notification, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is RecapCardViewHolder -> holder.bind(items[position], onItemClick)
            is NotificationViewHolder -> holder.bind(items[position], context, onItemClick, onShareClick)
        }
    }

    override fun getItemCount(): Int = items.size

    fun getItem(position: Int): NotificationItem = items[position]

    // ── Standard notification row ──────────────────────────────────────────────

    class NotificationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val avatar: ImageView = itemView.findViewById(R.id.notification_avatar)
        private val avatarOverlay: TextView = itemView.findViewById(R.id.notification_avatar_overlay)
        private val body: TextView = itemView.findViewById(R.id.notification_body)
        private val contextLine: TextView = itemView.findViewById(R.id.notification_context)
        private val unreadDot: View = itemView.findViewById(R.id.notification_unread_dot)
        private val leftAccent: View = itemView.findViewById(R.id.notification_left_accent)
        private val shareIcon: View = itemView.findViewById(R.id.notification_share_icon)

        fun bind(
            item: NotificationItem,
            context: Context,
            onItemClick: (NotificationItem) -> Unit,
            onShareClick: ((NotificationItem) -> Unit)?
        ) {
            body.text = formatBody(context, item)
            body.setTypeface(null, if (item.is_read) android.graphics.Typeface.NORMAL else android.graphics.Typeface.BOLD)
            contextLine.text = formatContext(context, item)
            unreadDot.visibility = if (item.is_read) View.GONE else View.VISIBLE

            setupAvatar(item, context)
            setupLeftAccent(item, context)
            setupOverlay(item)
            setupShareIcon(item, onShareClick)

            itemView.setOnClickListener { onItemClick(item) }
        }

        private fun setupShareIcon(item: NotificationItem, onShareClick: ((NotificationItem) -> Unit)?) {
            val isShareableMilestone = item.type == "milestone_achieved" && item.shareable_card_data != null
            shareIcon.visibility = if (isShareableMilestone && onShareClick != null) View.VISIBLE else View.GONE
            shareIcon.setOnClickListener(null)
            if (isShareableMilestone && onShareClick != null) {
                shareIcon.setOnClickListener { onShareClick(item) }
            }
        }

        private fun formatBody(context: Context, item: NotificationItem): String {
            val actorName = item.actor?.display_name ?: ""
            val groupName = item.group?.name ?: ""
            val goalTitle = item.goal?.title ?: ""
            return when (item.type) {
                "reaction_received" -> {
                    val emoji = (item.metadata?.get("emoji") as? String) ?: "❤️"
                    context.getString(R.string.notification_reaction, actorName, emoji, goalTitle)
                }
                "nudge_received" -> context.getString(R.string.notification_nudge, actorName, goalTitle.ifEmpty { groupName })
                "membership_approved" -> context.getString(R.string.notification_approved, groupName)
                "membership_rejected" -> context.getString(R.string.notification_rejected, groupName)
                "promoted_to_admin" -> context.getString(R.string.notification_promoted, actorName, groupName)
                "removed_from_group" -> context.getString(R.string.notification_removed, groupName)
                "join_request_received" -> context.getString(R.string.notification_join_request, actorName, groupName)
                "milestone_achieved" -> {
                    val milestoneType = item.metadata?.get("milestone_type") as? String
                    when (milestoneType) {
                        "first_log" -> context.getString(R.string.notification_milestone_first)
                        "streak" -> {
                            val count = (item.metadata?.get("streak_count") as? Number)?.toInt() ?: 0
                            context.getString(R.string.notification_milestone_streak, count, goalTitle.ifEmpty { "your goal" })
                        }
                        "total_logs" -> context.getString(R.string.notification_milestone_total_logs)
                        "challenge_completed" -> {
                            val challengeName = item.metadata?.get("challenge_name") as? String
                            val completionRate = ((item.metadata?.get("completion_rate") as? Number)?.toDouble() ?: 0.0) * 100
                            val rounded = completionRate.toInt().coerceIn(0, 100)
                            context.getString(
                                R.string.notification_milestone_challenge_completed,
                                challengeName ?: context.getString(R.string.challenge_default_name),
                                rounded
                            )
                        }
                        else -> context.getString(R.string.notification_milestone_first)
                    }
                }
                "challenge_suggestion" -> context.getString(R.string.notification_challenge_suggestion)
                "challenge_starts_tomorrow" -> context.getString(R.string.notification_challenge_starts_tomorrow, groupName)
                "challenge_started" -> context.getString(R.string.notification_challenge_started, groupName)
                "challenge_countdown" -> {
                    val countdownType = item.metadata?.get("countdown_type") as? String
                    when (countdownType) {
                        "halfway" -> context.getString(R.string.notification_challenge_halfway, groupName)
                        "three_days_left" -> context.getString(R.string.notification_challenge_three_days, groupName)
                        "final_day" -> context.getString(R.string.notification_challenge_final_day, groupName)
                        else -> context.getString(R.string.notification_challenge_suggestion)
                    }
                }
                else -> (item.actor?.display_name ?: "Someone") + " — notification"
            }
        }

        private fun formatContext(context: Context, item: NotificationItem): String {
            val groupName = item.group?.name ?: ""
            val relativeTime = RelativeTimeUtils.formatRelativeTime(context, item.created_at)
            return if (groupName.isNotEmpty()) "$groupName · $relativeTime" else relativeTime
        }

        private fun setupAvatar(item: NotificationItem, context: Context) {
            if (item.type == "milestone_achieved") {
                avatar.setImageResource(R.drawable.ic_pursue_logo)
                avatar.scaleType = ImageView.ScaleType.CENTER_INSIDE
                avatar.setBackgroundResource(R.color.white)
                avatarOverlay.visibility = View.GONE
                return
            }
            if (item.type == "challenge_suggestion") {
                avatar.setImageResource(R.drawable.ic_pursue_logo) // Use logo as base
                avatar.scaleType = ImageView.ScaleType.CENTER_INSIDE
                avatar.setBackgroundResource(R.color.white)
                return
            }
            avatar.scaleType = ImageView.ScaleType.CENTER_CROP
            val imageUrl = item.actor?.avatar_url?.let { url ->
                if (url.startsWith("http")) url else {
                    val base = ApiClient.getBaseUrl()
                    val origin = base.substringBeforeLast("/")
                    "$origin$url"
                }
            }
            if (!imageUrl.isNullOrBlank()) {
                Glide.with(context)
                    .load(imageUrl)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .circleCrop()
                    .placeholder(R.drawable.ic_pursue_logo)
                    .error(R.drawable.ic_pursue_logo)
                    .into(avatar)
                avatar.setBackgroundResource(0)
            } else {
                avatar.setImageResource(R.drawable.ic_pursue_logo)
                avatar.setBackgroundResource(R.color.white)
            }
        }

        private fun setupOverlay(item: NotificationItem) {
            when (item.type) {
                "reaction_received" -> {
                    val emoji = (item.metadata?.get("emoji") as? String) ?: "❤️"
                    avatarOverlay.text = emoji
                    avatarOverlay.visibility = View.VISIBLE
                }
                "nudge_received" -> {
                    avatarOverlay.text = "👋"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "membership_approved" -> {
                    avatarOverlay.text = "✅"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "join_request_received" -> {
                    avatarOverlay.text = "🙋"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "membership_rejected", "removed_from_group" -> {
                    avatarOverlay.text = "✖"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "promoted_to_admin" -> {
                    avatarOverlay.text = "⭐"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "challenge_suggestion" -> {
                    avatarOverlay.text = "🏆"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "challenge_starts_tomorrow" -> {
                    avatarOverlay.text = "🏁"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "challenge_started" -> {
                    avatarOverlay.text = "🚀"
                    avatarOverlay.visibility = View.VISIBLE
                }
                "challenge_countdown" -> {
                    val countdownType = item.metadata?.get("countdown_type") as? String
                    avatarOverlay.text = when (countdownType) {
                        "halfway" -> "🔥"
                        "three_days_left" -> "⏰"
                        "final_day" -> "🏆"
                        else -> "🏆"
                    }
                    avatarOverlay.visibility = View.VISIBLE
                }
                else -> avatarOverlay.visibility = View.GONE
            }
        }

        private fun setupLeftAccent(item: NotificationItem, context: Context) {
            val (colorRes, visible) = when (item.type) {
                "milestone_achieved" -> R.color.milestone_gold_border to true
                "membership_approved" -> R.color.approved_green_border to true
                "membership_rejected", "removed_from_group" -> R.color.on_surface_variant to true
                "challenge_starts_tomorrow" -> R.color.primary to true
                "challenge_countdown" -> {
                    val countdownType = item.metadata?.get("countdown_type") as? String
                    if (countdownType == "final_day") R.color.milestone_gold_border to true
                    else 0 to false
                }
                else -> 0 to false
            }
            leftAccent.visibility = if (visible) View.VISIBLE else View.GONE
            if (visible && colorRes != 0) {
                leftAccent.setBackgroundColor(context.resources.getColor(colorRes, null))
            }
        }
    }

    // ── Weekly recap card ──────────────────────────────────────────────────────

    inner class RecapCardViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val periodLabel: TextView = itemView.findViewById(R.id.recap_period)
        private val groupName: TextView = itemView.findViewById(R.id.recap_group_name)
        private val rateNumber: TextView = itemView.findViewById(R.id.recap_rate_number)
        private val completionBar: ProgressBar = itemView.findViewById(R.id.recap_completion_bar)
        private val deltaText: TextView = itemView.findViewById(R.id.recap_delta_text)
        private val toneText: TextView = itemView.findViewById(R.id.recap_tone_text)
        private val highlightsContainer: LinearLayout = itemView.findViewById(R.id.recap_highlights_container)
        private val highlightsHeader: TextView = itemView.findViewById(R.id.recap_highlights_header)
        private val highlightsDivider: View = itemView.findViewById(R.id.recap_highlights_divider)
        private val goalsContainer: LinearLayout = itemView.findViewById(R.id.recap_goals_container)
        private val goalsHeader: TextView = itemView.findViewById(R.id.recap_goals_header)
        private val goalsDivider: View = itemView.findViewById(R.id.recap_goals_divider)
        private val heatHeader: TextView = itemView.findViewById(R.id.recap_heat_header)
        private val heatDivider: View = itemView.findViewById(R.id.recap_heat_divider)
        private val heatContent: TextView = itemView.findViewById(R.id.recap_heat_content)
        private val openGroupBtn: TextView = itemView.findViewById(R.id.recap_open_group_btn)
        private val unreadDot: View = itemView.findViewById(R.id.recap_unread_dot)

        fun bind(item: NotificationItem, onItemClick: (NotificationItem) -> Unit) {
            val meta = item.metadata

            // Unread indicator
            unreadDot.visibility = if (item.is_read) View.GONE else View.VISIBLE

            // Group name and period
            groupName.text = item.group?.name ?: ""
            val weekStart = metaString(meta, "week_start")
            val weekEnd = metaString(meta, "week_end")
            periodLabel.text = formatPeriod(weekStart, weekEnd)

            // Completion rate hero
            val rate = metaInt(meta, "completion_rate") ?: 0
            val delta = metaInt(meta, "completion_delta") ?: 0
            val rateColor = rateColor(rate)

            rateNumber.text = "$rate%"
            rateNumber.setTextColor(rateColor)
            completionBar.progress = rate
            completionBar.progressTintList = android.content.res.ColorStateList.valueOf(rateColor)
            completionBar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                context.resources.getColor(R.color.surface_variant, null)
            )
            deltaText.text = formatDelta(delta)
            deltaText.setTextColor(
                if (delta >= 0) context.resources.getColor(R.color.primary, null)
                else context.resources.getColor(R.color.on_surface_variant, null)
            )
            toneText.text = toneText(rate)

            // Highlights
            val highlights = metaList(meta, "highlights")
            if (highlights.isNotEmpty()) {
                highlightsContainer.removeAllViews()
                highlights.take(4).forEach { highlight ->
                    val emoji = highlight["emoji"] as? String ?: ""
                    val text = highlight["text"] as? String ?: ""
                    addHighlightRow(highlightsContainer, "$emoji  $text")
                }
                setSection(highlightsHeader, highlightsDivider, highlightsContainer, true)
            } else {
                setSection(highlightsHeader, highlightsDivider, highlightsContainer, false)
            }

            // Goal breakdown
            val goals = metaList(meta, "goal_breakdown")
            if (goals.isNotEmpty()) {
                goalsContainer.removeAllViews()
                goals.take(5).forEach { goal ->
                    val title = goal["title"] as? String ?: ""
                    val goalRate = (goal["completionRate"] as? Number)?.toInt() ?: 0
                    addGoalRow(goalsContainer, title, goalRate)
                }
                setSection(goalsHeader, goalsDivider, goalsContainer, true)
            } else {
                setSection(goalsHeader, goalsDivider, goalsContainer, false)
            }

            // Heat
            val heat = metaMap(meta, "heat")
            if (heat != null) {
                val tierName = heat["tierName"] as? String ?: ""
                val score = (heat["score"] as? Number)?.toInt() ?: 0
                val streakDays = (heat["streakDays"] as? Number)?.toInt() ?: 0
                heatContent.text = "$tierName (Score: $score)  ·  ${streakDays}-day heat streak"
                heatHeader.visibility = View.VISIBLE
                heatDivider.visibility = View.VISIBLE
                heatContent.visibility = View.VISIBLE
            } else {
                heatHeader.visibility = View.GONE
                heatDivider.visibility = View.GONE
                heatContent.visibility = View.GONE
            }

            // Open Group button — navigate by propagating the click
            openGroupBtn.setOnClickListener { onItemClick(item) }
            // Tapping the card itself also navigates
            itemView.setOnClickListener { onItemClick(item) }
        }

        private fun setSection(header: TextView, divider: View, container: LinearLayout, visible: Boolean) {
            val v = if (visible) View.VISIBLE else View.GONE
            header.visibility = v
            divider.visibility = v
            container.visibility = v
        }

        private fun addHighlightRow(container: LinearLayout, text: String) {
            val tv = TextView(context)
            tv.text = text
            tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            tv.setTextColor(context.resources.getColor(R.color.on_surface, null))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (4 * context.resources.displayMetrics.density).toInt()
            tv.layoutParams = lp
            container.addView(tv)
        }

        private fun addGoalRow(container: LinearLayout, title: String, rate: Int) {
            val dp = context.resources.displayMetrics.density
            val row = LinearLayout(context)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = android.view.Gravity.CENTER_VERTICAL
            val rowLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            rowLp.topMargin = (6 * dp).toInt()
            row.layoutParams = rowLp

            // Goal title
            val titleTv = TextView(context)
            titleTv.text = title
            titleTv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            titleTv.setTextColor(context.resources.getColor(R.color.on_surface, null))
            titleTv.maxLines = 1
            titleTv.ellipsize = android.text.TextUtils.TruncateAt.END
            val titleLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            titleTv.layoutParams = titleLp

            // Percentage label
            val pctTv = TextView(context)
            pctTv.text = "$rate%"
            pctTv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            pctTv.setTextColor(context.resources.getColor(R.color.on_surface_variant, null))
            val pctLp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            pctLp.marginStart = (8 * dp).toInt()
            pctTv.layoutParams = pctLp

            // Mini progress bar
            val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
            bar.max = 100
            bar.progress = rate
            bar.progressTintList = android.content.res.ColorStateList.valueOf(
                context.resources.getColor(R.color.primary, null)
            )
            bar.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(
                context.resources.getColor(R.color.surface_variant, null)
            )
            val barLp = LinearLayout.LayoutParams(
                (80 * dp).toInt(),
                (4 * dp).toInt()
            )
            barLp.marginStart = (8 * dp).toInt()
            bar.layoutParams = barLp

            row.addView(titleTv)
            row.addView(pctTv)
            row.addView(bar)
            container.addView(row)
        }

        private fun rateColor(rate: Int): Int {
            val colorRes = when {
                rate >= 90 -> R.color.milestone_gold_border
                rate >= 50 -> R.color.primary
                else -> R.color.on_surface_variant
            }
            return context.resources.getColor(colorRes, null)
        }

        private fun toneText(rate: Int): String = when {
            rate >= 95 -> "Incredible week! Almost perfect."
            rate >= 90 -> "Crushing it! Outstanding week."
            rate >= 80 -> "Strong week! Keep the momentum going."
            rate >= 70 -> "Solid effort — consistency is building."
            rate >= 50 -> "Every log counts — keep showing up."
            rate >= 25 -> "Tough week, but you're still here. That matters."
            else -> "Fresh start next week — your group's got this! 💪"
        }

        private fun formatDelta(delta: Int): String = when {
            delta >= 20 -> "↑ ${delta}% — Massive improvement!"
            delta >= 10 -> "↑ ${delta}% — Nice jump from last week!"
            delta >= 1  -> "↑ ${delta}% — Trending up."
            delta == 0  -> "Holding steady."
            delta >= -9 -> "↓ ${-delta}% — Slight dip — regroup and go again."
            delta >= -19 -> "↓ ${-delta}% — Tougher week — everyone has them."
            else -> "↓ ${-delta}% — Big reset ahead — rally the group next week."
        }

        private fun formatPeriod(weekStart: String?, weekEnd: String?): String {
            if (weekStart == null || weekEnd == null) return ""
            return try {
                val months = arrayOf("Jan","Feb","Mar","Apr","May","Jun",
                                     "Jul","Aug","Sep","Oct","Nov","Dec")
                val startParts = weekStart.split("-")
                val endParts   = weekEnd.split("-")
                val month    = months[startParts[1].toInt() - 1]
                val startDay = startParts[2].toIntOrNull() ?: return ""
                val endDay   = endParts[2].toIntOrNull() ?: return ""
                "$month $startDay–$endDay"
            } catch (_: Exception) { "" }
        }

        // ── Metadata helpers ──────────────────────────────────────────────────

        private fun metaString(meta: Map<String, Any>?, key: String): String? =
            meta?.get(key) as? String

        private fun metaInt(meta: Map<String, Any>?, key: String): Int? =
            (meta?.get(key) as? Number)?.toInt()

        @Suppress("UNCHECKED_CAST")
        private fun metaList(meta: Map<String, Any>?, key: String): List<Map<String, Any>> {
            val raw = meta?.get(key) as? List<*> ?: return emptyList()
            return raw.filterIsInstance<Map<String, Any>>()
        }

        @Suppress("UNCHECKED_CAST")
        private fun metaMap(meta: Map<String, Any>?, key: String): Map<String, Any>? =
            meta?.get(key) as? Map<String, Any>
    }
}
