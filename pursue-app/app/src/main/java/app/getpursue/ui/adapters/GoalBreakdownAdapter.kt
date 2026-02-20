package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.models.GoalBreakdown
import app.getpursue.R

/**
 * RecyclerView adapter for displaying goal breakdown.
 */
class GoalBreakdownAdapter(
    private val goals: List<GoalBreakdown>
) : RecyclerView.Adapter<GoalBreakdownAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_goal_breakdown, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(goals[position])
    }

    override fun getItemCount(): Int = goals.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val goalTitle: TextView = itemView.findViewById(R.id.goal_title)
        private val goalProgress: ProgressBar = itemView.findViewById(R.id.goal_progress)
        private val goalProgressText: TextView = itemView.findViewById(R.id.goal_progress_text)

        fun bind(goal: GoalBreakdown) {
            goalTitle.text = goal.goal_title
            goalProgress.progress = goal.completion_percent
            goalProgressText.text = "${goal.completion_percent}% (${goal.completed_count}/${goal.total_count})"
        }
    }
}
