package app.getpursue.ui.helpers

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.models.GroupActivity
import app.getpursue.ui.adapters.ReactionListener

/**
 * Helper class to handle long-press gestures on RecyclerView items.
 * This works around the issue where RecyclerView intercepts touch events for scrolling,
 * preventing child views from receiving long-press events.
 */
class RecyclerViewLongPressHelper(
    private val recyclerView: RecyclerView
) : RecyclerView.OnItemTouchListener {
    
    private val gestureDetector: GestureDetectorCompat
    
    init {
        gestureDetector = GestureDetectorCompat(recyclerView.context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                // Skip long-press if touch is on the react button or overflow menu button
                if (isTouchOnReactButton(e.x, e.y)) return
                val childView = recyclerView.findChildViewUnder(e.x, e.y)
                if (childView != null) {
                    if (isTouchOnView(e.x, e.y, childView, R.id.activity_overflow_button)) return
                    val activity = childView.getTag(R.id.activity_data_tag) as? GroupActivity
                    val listener = childView.getTag(R.id.reaction_listener_tag) as? ReactionListener
                    if (activity?.id != null && listener != null) {
                        childView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        listener.onLongPress(activity, childView, e.rawX, e.rawY)
                    }
                }
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val childView = recyclerView.findChildViewUnder(e.x, e.y) ?: return false

                // Photo taps: perform click directly so taps aren't lost to
                // ViewPager2 / SwipeRefreshLayout touch interception
                if (isTouchOnView(e.x, e.y, childView, R.id.activity_photo)) {
                    childView.findViewById<View>(R.id.activity_photo)?.performClick()
                    return true
                }

                if (isTouchOnReactButton(e.x, e.y)) {
                    val reactButton = childView.findViewById<View>(R.id.activity_react_button)
                    val activity = childView.getTag(R.id.activity_data_tag) as? GroupActivity
                    val listener = childView.getTag(R.id.reaction_listener_tag) as? ReactionListener
                    if (reactButton != null && activity?.id != null && listener != null) {
                        listener.onReactionButtonClick(activity, reactButton)
                        return true
                    }
                }

                if (isTouchOnView(e.x, e.y, childView, R.id.activity_overflow_button)) {
                    childView.findViewById<View>(R.id.activity_overflow_button)?.performClick()
                    return true
                }

                return false
            }
            
            override fun onDown(e: MotionEvent): Boolean {
                return true
            }
        })
    }
    
    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(e)
        return false // Don't intercept - let RecyclerView handle scrolling normally
    }
    
    private fun isTouchOnView(x: Float, y: Float, cardView: View, viewId: Int): Boolean {
        val target = cardView.findViewById<View>(viewId) ?: return false
        if (target.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        val rvLoc = IntArray(2)
        target.getLocationOnScreen(loc)
        recyclerView.getLocationOnScreen(rvLoc)
        val vx = loc[0] - rvLoc[0]
        val vy = loc[1] - rvLoc[1]
        return x >= vx && x <= vx + target.width &&
               y >= vy && y <= vy + target.height
    }

    private fun isTouchOnReactButton(x: Float, y: Float): Boolean {
        val childView = recyclerView.findChildViewUnder(x, y) ?: return false
        val reactButton = childView.findViewById<View>(R.id.activity_react_button) ?: return false
        
        val buttonLocation = IntArray(2)
        val rvLocation = IntArray(2)
        reactButton.getLocationOnScreen(buttonLocation)
        recyclerView.getLocationOnScreen(rvLocation)
        
        val buttonX = buttonLocation[0] - rvLocation[0]
        val buttonY = buttonLocation[1] - rvLocation[1]
        
        return x >= buttonX && x <= buttonX + reactButton.width &&
               y >= buttonY && y <= buttonY + reactButton.height
    }
    
    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        // Not used since we don't intercept
    }
    
    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // Not used
    }
    
    companion object {
        fun attach(recyclerView: RecyclerView): RecyclerViewLongPressHelper {
            val helper = RecyclerViewLongPressHelper(recyclerView)
            recyclerView.addOnItemTouchListener(helper)
            return helper
        }
    }
}
