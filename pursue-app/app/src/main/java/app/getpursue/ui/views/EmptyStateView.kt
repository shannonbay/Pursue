package app.getpursue.ui.views

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import app.getpursue.R

/**
 * Reusable empty state view component.
 */
class EmptyStateView private constructor(private val rootView: View) {
    
    fun setOnBrowseGroupsClickListener(listener: View.OnClickListener) {
        rootView.findViewById<View>(R.id.browse_groups_button)?.setOnClickListener(listener)
    }
    
    fun setOnViewTodaysGoalsClickListener(listener: View.OnClickListener) {
        rootView.findViewById<View>(R.id.view_todays_goals_button)?.setOnClickListener(listener)
    }
    
    companion object {
        fun inflateToday(inflater: LayoutInflater, parent: ViewGroup): EmptyStateView {
            val view = inflater.inflate(R.layout.empty_state_today, parent, false)
            return EmptyStateView(view)
        }
        
        fun inflateProgress(inflater: LayoutInflater, parent: ViewGroup): EmptyStateView {
            val view = inflater.inflate(R.layout.empty_state_progress, parent, false)
            return EmptyStateView(view)
        }
    }
    
    val view: View
        get() = rootView
}
