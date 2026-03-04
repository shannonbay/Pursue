package app.getpursue.ui.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.app.Activity
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.vectordrawable.graphics.drawable.AnimatedVectorDrawableCompat
import app.getpursue.R
import kotlin.random.Random

/**
 * Plays a raining-confetti animation over the activity's content window.
 * 10 animated confetti pieces fall from random positions above the screen
 * to below it at constant speed, then the overlay is removed automatically.
 */
fun showConfettiOverlay(activity: Activity) {
    val root = activity.findViewById<FrameLayout>(android.R.id.content)
    root.post {
        val screenW = root.width.toFloat()
        val screenH = root.height.toFloat()
        if (screenW == 0f || screenH == 0f) return@post

        val density = activity.resources.displayMetrics.density
        val pieceW = (180 * density).toInt()
        val pieceH = (255 * density).toInt()

        val overlay = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
        }
        root.addView(overlay)

        val colors = intArrayOf(
            0xFFFFC107.toInt(), // amber
            0xFFE91E63.toInt(), // pink
            0xFF00BCD4.toInt(), // cyan
            0xFF4CAF50.toInt(), // green
            0xFFFF5722.toInt(), // deep orange
            0xFF9C27B0.toInt(), // purple
            0xFF2196F3.toInt(), // blue
            0xFFFF9800.toInt(), // orange
        )

        val childAnimators = mutableListOf<Animator>()

        repeat(10) {
            val drawable = AnimatedVectorDrawableCompat.create(activity, R.drawable.confetti)
                ?: return@repeat
            val color = colors[Random.nextInt(colors.size)]
            drawable.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

            val startX = Random.nextFloat() * (screenW - pieceW)
            val startY = -(pieceH + Random.nextInt(pieceH * 2)).toFloat()
            val endY = screenH + pieceH
            val delay = Random.nextLong(0, 900)

            val imageView = ImageView(activity).apply {
                setImageDrawable(drawable)
                layoutParams = FrameLayout.LayoutParams(pieceW, pieceH).also {
                    it.leftMargin = startX.toInt()
                    it.topMargin = startY.toInt()
                }
            }
            overlay.addView(imageView)
            drawable.start()

            val fall = ObjectAnimator.ofFloat(imageView, "translationY", 0f, endY - startY).apply {
                duration = 2500L
                startDelay = delay
                interpolator = LinearInterpolator()
            }
            childAnimators.add(fall)
        }

        AnimatorSet().apply {
            playTogether(childAnimators)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    root.removeView(overlay)
                }
            })
            start()
        }
    }
}
