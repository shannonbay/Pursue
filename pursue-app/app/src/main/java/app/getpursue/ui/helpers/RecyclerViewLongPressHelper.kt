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
                // Skip long-press if touch is on the react button
                if (isTouchOnReactButton(e.x, e.y)) return
                
                val childView = recyclerView.findChildViewUnder(e.x, e.y)
                if (childView != null) {
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
                val reactButton = childView.findViewById<View>(R.id.activity_react_button) ?: return false
                
                if (isTouchOnReactButton(e.x, e.y)) {
                    val activity = childView.getTag(R.id.activity_data_tag) as? GroupActivity
                    val listener = childView.getTag(R.id.reaction_listener_tag) as? ReactionListener
                    if (activity?.id != null && listener != null) {
                        listener.onReactionButtonClick(activity, reactButton)
                        return true
                    }
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
