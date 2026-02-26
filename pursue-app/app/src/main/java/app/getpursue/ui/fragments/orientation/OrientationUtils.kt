package app.getpursue.ui.fragments.orientation

import android.view.View
import android.widget.TextView
import app.getpursue.R

/**
 * Configures the progress dots include layout for the given step (1–4).
 * Filled dots for current and previous steps, empty dots for future steps.
 */
fun setupProgressDots(dotsView: View, step: Int) {
    val stepLabel = dotsView.findViewById<TextView>(R.id.step_label)
    val dot1 = dotsView.findViewById<View>(R.id.dot1)
    val dot2 = dotsView.findViewById<View>(R.id.dot2)
    val dot3 = dotsView.findViewById<View>(R.id.dot3)
    val dot4 = dotsView.findViewById<View>(R.id.dot4)

    stepLabel.text = dotsView.context.getString(R.string.orientation_step_x_of_4, step)

    dot1.setBackgroundResource(if (step >= 1) R.drawable.bg_dot_filled else R.drawable.bg_dot_empty)
    dot2.setBackgroundResource(if (step >= 2) R.drawable.bg_dot_filled else R.drawable.bg_dot_empty)
    dot3.setBackgroundResource(if (step >= 3) R.drawable.bg_dot_filled else R.drawable.bg_dot_empty)
    dot4.setBackgroundResource(if (step >= 4) R.drawable.bg_dot_filled else R.drawable.bg_dot_empty)
}
