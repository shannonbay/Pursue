package app.getpursue.ui.views

import android.content.Context
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import app.getpursue.R

/**
 * PopupWindow for selecting a reaction emoji on long-press.
 *
 * Displays a horizontal pill with 6 emoji options.
 * Highlights the current user's emoji if already reacted.
 */
class ReactionPickerPopup(
    context: Context,
    private val currentUserEmoji: String?,
    private val onSelect: (emoji: String) -> Unit,
    private val onDismiss: () -> Unit
) : PopupWindow(
    LayoutInflater.from(context).inflate(R.layout.popup_reaction_picker, null),
    ViewGroup.LayoutParams.WRAP_CONTENT,
    ViewGroup.LayoutParams.WRAP_CONTENT,
    true
) {
    companion object {
        val REACTION_EMOJIS = listOf("🔥", "💪", "❤️", "👏", "🤩", "😂")
    }

    private val pickerView: View
        get() = contentView!!

    init {
        isOutsideTouchable = true
        isFocusable = true
        setOnDismissListener { onDismiss() }

        val emojiIds = listOf(
            R.id.emoji_fire,
            R.id.emoji_flex,
            R.id.emoji_heart,
            R.id.emoji_clap,
            R.id.emoji_star,
            R.id.emoji_laugh
        )

        REACTION_EMOJIS.forEachIndexed { index, emoji ->
            val textView = pickerView.findViewById<TextView>(emojiIds[index])
            textView.text = emoji

            if (emoji == currentUserEmoji) {
                textView.setBackgroundResource(R.drawable.reaction_picker_selected_background)
            }

            textView.setOnClickListener {
                textView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                onSelect(emoji)
                dismiss()
            }
        }
    }

    /**
     * Show the popup above the anchor view.
     */
    fun show(anchor: View) {
        pickerView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val width = pickerView.measuredWidth
        val height = pickerView.measuredHeight

        val anchorLocation = IntArray(2)
        anchor.getLocationOnScreen(anchorLocation)
        val rootLocation = IntArray(2)
        anchor.rootView.getLocationOnScreen(rootLocation)

        val anchorCenterX = anchorLocation[0] + anchor.width / 2
        val x = anchorCenterX - rootLocation[0] - width / 2
        val y = anchorLocation[1] - rootLocation[1] - height - 24 // 24px above anchor

        showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, x, y)
        anchor.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
    }
}
