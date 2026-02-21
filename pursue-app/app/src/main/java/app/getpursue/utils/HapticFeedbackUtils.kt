package app.getpursue.utils

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Helper for triggering haptic feedback using the View-based API.
 */
object HapticFeedbackUtils {

    /**
     * Triggers a short "click" vibration (standard tap).
     */
    fun vibrateClick(view: View) {
        view.isHapticFeedbackEnabled = true
        view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    /**
     * Triggers a distinct "success" vibration.
     */
    fun vibrateSuccess(view: View) {
        view.isHapticFeedbackEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(
                HapticFeedbackConstants.CONFIRM,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } else {
            view.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        }
    }

    /**
     * Triggers a distinct "error" vibration.
     */
    fun vibrateError(view: View) {
        view.isHapticFeedbackEnabled = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(
                HapticFeedbackConstants.REJECT,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
        } else {
            // Double long-press feel for older versions
            view.performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            )
            view.postDelayed({
                view.performHapticFeedback(
                    HapticFeedbackConstants.LONG_PRESS,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
                )
            }, 50)
        }
    }
}
