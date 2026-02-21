package app.getpursue.utils

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * Helper for triggering haptic feedback using the View-based API with a direct Vibrator fallback.
 */
object HapticFeedbackUtils {

    /**
     * Triggers a short "click" vibration (standard tap).
     */
    fun vibrateClick(view: View) {
        view.isHapticFeedbackEnabled = true
        val performed = view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        if (!performed) {
            vibrateDirect(view.context, 20)
        }
    }

    /**
     * Triggers a distinct "success" vibration.
     */
    fun vibrateSuccess(view: View) {
        view.isHapticFeedbackEnabled = true
        val performed = view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        if (!performed) {
            vibrateDirect(view.context, 50)
        }
    }

    /**
     * Triggers a "Light-Heavy" double tap haptic (like a physical toggle).
     */
    fun vibrateToggle(view: View) {
        view.isHapticFeedbackEnabled = true
        // Try to use predefined constants if available (API 34+)
        val performed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            view.performHapticFeedback(
                HapticFeedbackConstants.TOGGLE_ON,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        } else false

        if (!performed) {
            // Manual pattern: Light (10ms) - Pause (40ms) - Heavy (30ms)
            vibratePattern(view.context, longArrayOf(0, 10, 40, 30), intArrayOf(0, 100, 0, 255))
        }
    }

    /**
     * Triggers a single sharp "tick" for heartbeat/progress.
     */
    fun vibrateTick(view: View) {
        view.isHapticFeedbackEnabled = true
        val performed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            view.performHapticFeedback(
                HapticFeedbackConstants.CLOCK_TICK,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        } else {
            view.performHapticFeedback(
                HapticFeedbackConstants.VIRTUAL_KEY,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
        }
        if (!performed) {
            vibrateDirect(view.context, 5)
        }
    }

    /**
     * Triggers a distinct "error" vibration.
     */
    fun vibrateError(view: View) {
        view.isHapticFeedbackEnabled = true
        val performed = view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
        if (!performed) {
            vibrateDirect(view.context, 100) // Slightly longer for error
        }
    }

    private fun vibrateDirect(context: Context, duration: Long) {
        val vibrator = getVibrator(context)
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(duration)
            }
        }
    }

    private fun vibratePattern(context: Context, timings: LongArray, amplitudes: IntArray) {
        val vibrator = getVibrator(context)
        if (vibrator?.hasVibrator() == true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(timings, -1)
            }
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            vibratorManager?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }
}
