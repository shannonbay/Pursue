package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.models.TodayGoal
import app.getpursue.models.TodayGroup
import app.getpursue.R
import com.google.android.material.card.MaterialCardView

/**
 * RecyclerView adapter for displaying today's goals grouped by group.
 * Uses item_goal_card (same as GoalsTabFragment) for goal rows: status icon, title,
 * progress bar/text for numeric goals, arrow for details. Card body tap = log progress.
 */
class TodayGoalAdapter(
    private val groups: List<TodayGroup>,
    private val onGoalClick: (TodayGoal, String) -> Unit, // (goal, groupId)
    private val onArrowClick: (TodayGoal) -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_GROUP_HEADER = 0
        private const val TYPE_GOAL = 1
    }

    private data class Item(val type: Int, val group: TodayGroup? = null, val goal: TodayGoal? = null)

    private val items: MutableList<Item> = groups.flatMap { group ->
        listOf(Item(TYPE_GROUP_HEADER, group = group)) +
            group.goals.map { goal -> Item(TYPE_GOAL, group = group, goal = goal) }
    }.toMutableList()

    override fun getItemViewType(position: Int): Int {
        return items[position].type
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            TYPE_GROUP_HEADER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(android.R.layout.simple_list_item_1, parent, false)
                GroupHeaderViewHolder(view)
            }
            else -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_goal_card, parent, false)
                GoalViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            else -> {
                when (holder) {
                    is GroupHeaderViewHolder -> holder.bind(item.group!!)
                    is GoalViewHolder -> holder.bind(item.goal!!, item.group!!.group_id, onGoalClick, onArrowClick)
                }
            }
        }
    }

    override fun getItemCount(): Int = items.size

    /**
     * Update a goal in-place by goalId. Preserves scroll position.
     * Also updates the group header completion count.
     */
    fun updateGoal(goalId: String, completed: Boolean, progressValue: Int?) {
        var goalIndex = -1
        var groupHeaderIndex = -1
        var targetGroupId: String? = null

        // Find the goal and its group
        items.forEachIndexed { index, item ->
            if (item.type == TYPE_GOAL && item.goal?.goal_id == goalId) {
                goalIndex = index
                targetGroupId = item.group?.group_id
            }
        }

        if (goalIndex == -1 || targetGroupId == null) return

        // Find the group header for this goal
        for (i in goalIndex - 1 downTo 0) {
            if (items[i].type == TYPE_GROUP_HEADER && items[i].group?.group_id == targetGroupId) {
                groupHeaderIndex = i
                break
            }
        }

        // Update the goal
        val goalItem = items[goalIndex]
        val updatedGoal = goalItem.goal!!.copy(
            completed = completed,
            progress_value = progressValue
        )

        // Recalculate completed count for this group
        var newCompletedCount = 0
        items.forEach { item ->
            if (item.type == TYPE_GOAL && item.group?.group_id == targetGroupId) {
                val isCompleted = if (item.goal?.goal_id == goalId) completed else item.goal?.completed == true
                if (isCompleted) newCompletedCount++
            }
        }

        // Update group with new completed count
        val updatedGroup = goalItem.group!!.copy(completed_count = newCompletedCount)

        // Update all items in this group with the new group reference
        items.forEachIndexed { index, item ->
            if (item.group?.group_id == targetGroupId) {
                items[index] = if (item.goal?.goal_id == goalId) {
                    item.copy(group = updatedGroup, goal = updatedGoal)
                } else {
                    item.copy(group = updatedGroup)
                }
            }
        }

        // Notify changes
        notifyItemChanged(goalIndex)
        if (groupHeaderIndex != -1) {
            notifyItemChanged(groupHeaderIndex)
        }
    }

    class GroupHeaderViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(android.R.id.text1)

        fun bind(group: TodayGroup) {
            val icon = group.icon_emoji ?: "📁"
            textView.text = "$icon ${group.group_name} (${group.completed_count}/${group.total_count})"
            textView.textSize = 16f
            textView.setTextColor(ContextCompat.getColor(itemView.context, R.color.on_surface))
            textView.setPadding(16, 16, 16, 8)
        }
    }

    class GoalViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val goalCard: MaterialCardView = itemView.findViewById(R.id.goal_card)
        private val arrowButton: ImageButton = itemView.findViewById(R.id.arrow_button)
        private val statusIcon: TextView = itemView.findViewById(R.id.status_icon)
        private val goalTitle: TextView = itemView.findViewById(R.id.goal_title)
        private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
        private val progressText: TextView = itemView.findViewById(R.id.progress_text)
        private val memberStatusContainer: LinearLayout = itemView.findViewById(R.id.member_status_container)

        fun bind(goal: TodayGoal, groupId: String, onGoalClick: (TodayGoal, String) -> Unit, onArrowClick: (TodayGoal) -> Unit) {
            goalCard.setOnClickListener { onGoalClick(goal, groupId) }
            arrowButton.setOnClickListener { onArrowClick(goal) }

            if (goal.completed) {
                statusIcon.text = "✓"
                statusIcon.setTextColor(ContextCompat.getColor(itemView.context, R.color.primary))
            } else {
                statusIcon.text = "○"
                statusIcon.setTextColor(ContextCompat.getColor(itemView.context, R.color.on_surface_variant))
            }

            goalTitle.text = goal.title

            val metricType = goal.metric_type ?: if (goal.target_value != null) "numeric" else "binary"
            if ((metricType == "numeric" || metricType == "duration") && goal.target_value != null && goal.target_value > 0) {
                progressBar.visibility = View.VISIBLE
                progressText.visibility = View.VISIBLE
                val progress = goal.progress_value?.toDouble() ?: 0.0
                val target = goal.target_value.toDouble()
                val displayPercent = ((progress / target) * 100).toInt()
                val barPercent = displayPercent.coerceIn(0, 100)
                progressBar.progress = barPercent
                progressText.text = "${displayPercent}% (${goal.progress_value ?: 0}/${goal.target_value})"
            } else {
                progressBar.visibility = View.GONE
                progressText.visibility = View.GONE
            }

            // Today screen does not show member status (spec 4.4)
            memberStatusContainer.visibility = View.GONE
        }
    }
}
