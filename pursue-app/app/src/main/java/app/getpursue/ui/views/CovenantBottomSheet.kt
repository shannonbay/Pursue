package app.getpursue.ui.views

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import app.getpursue.R
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class CovenantBottomSheet : BottomSheetDialogFragment() {

    interface CovenantListener {
        fun onCovenantAccepted()
    }

    companion object {
        private const val ARG_IS_CHALLENGE = "is_challenge"
        private const val HOLD_DURATION_MS = 1500L
        private const val COMMITTED_DELAY_MS = 300L
        private const val RESET_DURATION_MS = 200L
        private const val FALLBACK_DELAY_MS = 3000L

        fun newInstance(isChallenge: Boolean = false): CovenantBottomSheet {
            return CovenantBottomSheet().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_IS_CHALLENGE, isChallenge)
                }
            }
        }
    }

    private var listener: CovenantListener? = null
    private var fillAnimator: ValueAnimator? = null
    private var resetAnimator: ValueAnimator? = null
    private var committed = false
    private val handler = Handler(Looper.getMainLooper())

    fun setCovenantListener(listener: CovenantListener) {
        this.listener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_covenant, container, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        isCancelable = false

        val closeButton = view.findViewById<ImageButton>(R.id.button_close)
        val challengeLine = view.findViewById<TextView>(R.id.covenant_challenge_line)
        val holdContainer = view.findViewById<FrameLayout>(R.id.hold_button_container)
        val progressFill = view.findViewById<View>(R.id.hold_progress_fill)
        val holdText = view.findViewById<TextView>(R.id.hold_button_text)
        val tapFallback = view.findViewById<TextView>(R.id.tap_fallback)

        // Show challenge line if applicable
        val isChallenge = arguments?.getBoolean(ARG_IS_CHALLENGE, false) ?: false
        if (isChallenge) {
            challengeLine.visibility = View.VISIBLE
        }

        // Close button cancels without proceeding
        closeButton.setOnClickListener {
            dismiss()
        }

        // Hold-to-commit touch handler
        holdContainer.setOnTouchListener { v, event ->
            if (committed) return@setOnTouchListener true

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetAnimator?.cancel()
                    startFillAnimation(progressFill, holdText, v)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!committed) {
                        cancelFillAnimation(progressFill)
                    }
                    true
                }
                else -> false
            }
        }

        // Accessibility tap fallback — shown after 3 seconds
        handler.postDelayed({
            if (isAdded && !committed) {
                tapFallback.visibility = View.VISIBLE
            }
        }, FALLBACK_DELAY_MS)

        tapFallback.setOnClickListener {
            if (committed) return@setOnClickListener
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.covenant_confirm_title))
                .setMessage(getString(R.string.covenant_confirm_message))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.covenant_confirm_join)) { _, _ ->
                    onCommitted(
                        view.findViewById(R.id.hold_progress_fill),
                        view.findViewById(R.id.hold_button_text),
                        view.findViewById(R.id.hold_button_container)
                    )
                }
                .show()
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.setCanceledOnTouchOutside(false)
        dialog.behavior.apply {
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
            isDraggable = false
        }
        return dialog
    }

    private fun startFillAnimation(progressFill: View, holdText: TextView, container: View) {
        fillAnimator?.cancel()

        val parentWidth = (progressFill.parent as View).width
        if (parentWidth == 0) return

        var hapticFired = false

        fillAnimator = ValueAnimator.ofInt(0, parentWidth).apply {
            duration = HOLD_DURATION_MS
            interpolator = LinearInterpolator()
            addUpdateListener { animation ->
                if (!isAdded) {
                    cancel()
                    return@addUpdateListener
                }
                val value = animation.animatedValue as Int
                val params = progressFill.layoutParams
                params.width = value
                progressFill.layoutParams = params

                // Haptic tick at 50%
                val fraction = animation.animatedFraction
                if (fraction >= 0.5f && !hapticFired) {
                    hapticFired = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        container.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    } else {
                        container.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    }
                }

                // Completed
                if (fraction >= 1.0f) {
                    onCommitted(progressFill, holdText, container)
                }
            }
        }
        fillAnimator?.start()
    }

    private fun cancelFillAnimation(progressFill: View) {
        fillAnimator?.cancel()
        fillAnimator = null

        val currentWidth = progressFill.layoutParams.width
        if (currentWidth <= 0) return

        resetAnimator = ValueAnimator.ofInt(currentWidth, 0).apply {
            duration = RESET_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animation ->
                if (!isAdded) {
                    cancel()
                    return@addUpdateListener
                }
                val value = animation.animatedValue as Int
                val params = progressFill.layoutParams
                params.width = value
                progressFill.layoutParams = params
            }
        }
        resetAnimator?.start()
    }

    private fun onCommitted(progressFill: View, holdText: TextView, container: View) {
        if (committed) return
        committed = true

        fillAnimator?.cancel()

        // Fill to 100%
        val parentWidth = (progressFill.parent as View).width
        val params = progressFill.layoutParams
        params.width = parentWidth
        progressFill.layoutParams = params

        // Update text
        holdText.text = getString(R.string.covenant_committed)

        // Success haptic
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            container.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            container.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        // Auto-dismiss after delay and fire callback
        handler.postDelayed({
            if (isAdded) {
                listener?.onCovenantAccepted()
                dismiss()
            }
        }, COMMITTED_DELAY_MS)
    }

    override fun onDestroyView() {
        fillAnimator?.cancel()
        resetAnimator?.cancel()
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }
}
