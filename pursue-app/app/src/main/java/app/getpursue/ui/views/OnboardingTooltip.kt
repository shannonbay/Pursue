package app.getpursue.ui.views

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import app.getpursue.R

/**
 * A simple helper to show onboarding tooltips anchored to a view.
 */
object OnboardingTooltip {

    /**
     * Shows a tooltip anchored to the given view.
     */
    fun show(anchor: View, textResId: Int) {
        val context = anchor.context
        val inflater = LayoutInflater.from(context)
        val tooltipView = inflater.inflate(R.layout.layout_onboarding_tooltip, null)
        
        tooltipView.findViewById<TextView>(R.id.tooltip_text).setText(textResId)

        val popupWindow = PopupWindow(
            tooltipView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )

        // Dismiss on click
        tooltipView.setOnClickListener {
            popupWindow.dismiss()
        }

        // Show above the anchor view
        tooltipView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val tooltipHeight = tooltipView.measuredHeight
        
        popupWindow.elevation = 8f
        popupWindow.showAsDropDown(anchor, (anchor.width - tooltipView.measuredWidth) / 2, -(anchor.height + tooltipHeight))
    }
}
