package com.github.shannonbay.pursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.models.GroupGoal
import com.google.android.material.card.MaterialCardView

/**
 * RecyclerView adapter for displaying goals grouped by cadence.
 * Shows cadence header (Daily, Weekly, Monthly, Yearly), then goals under each cadence.
 */
class GroupGoalsAdapter(
    private val goals: List<GroupGoal>,
    private val onCardBodyClick: (GroupGoal) -> Unit = {},
    private val onArrowClick: (GroupGoal) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_CADENCE_HEADER = 0
        private const val TYPE_GOAL = 1
    }

    private data class Item(
        val type: Int,
        val cadence: String? = null,
        val goal: GroupGoal? = null
    )

    private val items: List<Item> = run {
        val goalsByCadence = goals.groupBy { it.cadence }
        val cadences = listOf("daily", "weekly", "monthly", "yearly")
        
        cadences.flatMap { cadence ->
            val cadenceGoals = goalsByCadence[cadence] ?: emptyList()
            if (cadenceGoals.isEmpty()) {
                emptyList()
            } else {
                listOf(Item(TYPE_CADENCE_HEADER, cadence = cadence)) +
                cadenceGoals.map { goal -> Item(TYPE_GOAL, goal = goal) }
            }
        }
    }

    override fun getItemViewType(position: Int): Int {
        return items[position].type
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_CADENCE_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                CadenceHeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_goal_card, parent, false)
                GoalViewHolder(view, onCardBodyClick, onArrowClick)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            else -> {
                when (holder) {
                    is CadenceHeaderViewHolder -> holder.bind(item.cadence!!)
                    is GoalViewHolder -> holder.bind(item.goal!!)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /**
     * Get the goal at a specific adapter position, or null if the position is a header.
     */
    fun getGoalAtPosition(position: Int): GroupGoal? {
        return if (position in items.indices) {
            items[position].goal
        } else {
            null
        }
    }

    class CadenceHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(android.R.id.text1)

        fun bind(cadence: String) {
            val cadenceText = when (cadence.lowercase()) {
                "daily" -> itemView.context.getString(R.string.daily_goals)
                "weekly" -> itemView.context.getString(R.string.weekly_goals)
                "monthly" -> itemView.context.getString(R.string.monthly_goals)
                "yearly" -> itemView.context.getString(R.string.yearly_goals)
                else -> cadence.replaceFirstChar { it.uppercaseChar() } + " Goals"
            }
            textView.text = cadenceText
            textView.textSize = 16f
            textView.setTextColor(itemView.context.getColor(R.color.on_surface))
            textView.setPadding(16, 16, 16, 8)
        }
    }

    class GoalViewHolder(
        itemView: View,
        private val onCardBodyClick: (GroupGoal) -> Unit,
        private val onArrowClick: (GroupGoal) -> Unit
    ) : RecyclerView.ViewHolder(itemView) {
        private val goalCard: MaterialCardView = itemView.findViewById(R.id.goal_card)
        private val arrowButton: ImageButton = itemView.findViewById(R.id.arrow_button)
        private val statusIcon: TextView = itemView.findViewById(R.id.status_icon)
        private val goalTitle: TextView = itemView.findViewById(R.id.goal_title)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        private val progressText: TextView = itemView.findViewById(R.id.progress_text)
        private val memberStatusContainer: LinearLayout = itemView.findViewById(R.id.member_status_container)
        private val memberStatusText: TextView = itemView.findViewById(R.id.member_status_text)

        fun bind(goal: GroupGoal) {
            // Clear any existing listeners to prevent duplicates
            goalCard.setOnClickListener(null)
            arrowButton.setOnClickListener(null)

            // Goal card click: notify callback and optimistically toggle status icon
            goalCard.setOnClickListener {
                onCardBodyClick(goal)
                val toggledCompleted = !goal.completed
                if (toggledCompleted) {
                    statusIcon.text = "✓"
                    statusIcon.setTextColor(itemView.context.getColor(R.color.primary))
                } else {
                    statusIcon.text = "○"
                    statusIcon.setTextColor(itemView.context.getColor(R.color.on_surface_variant))
                }
            }

            arrowButton.setOnClickListener { onArrowClick(goal) }

            // Status icon (✓ for completed, ○ for incomplete)
            if (goal.completed) {
                statusIcon.text = "✓"
                statusIcon.setTextColor(itemView.context.getColor(R.color.primary))
            } else {
                statusIcon.text = "○"
                statusIcon.setTextColor(itemView.context.getColor(R.color.on_surface_variant))
            }

            goalTitle.text = goal.title

            // Progress bar and text for numeric/duration goals
            if ((goal.metric_type == "numeric" || goal.metric_type == "duration") && goal.target_value != null) {
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                
                val progress = goal.progress_value ?: 0.0
                val target = goal.target_value
                val percent = ((progress / target) * 100).toInt().coerceIn(0, 100)
                
                progressBar.progress = percent
                progressText.text = "$percent% (${progress.toInt()}/${target.toInt()})"
            } else {
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
            }

            // Member status dots
            if (goal.member_progress.isNotEmpty()) {
                memberStatusText.visibility = View.VISIBLE
                val statusText = goal.member_progress.joinToString(" ") { member ->
                    val status = if (member.completed) "✓" else "○"
                    "${member.display_name} $status"
                }
                memberStatusText.text = statusText
            } else {
                memberStatusText.visibility = View.GONE
            }
        }

    }
}
