package app.getpursue.ui.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import app.getpursue.R
import app.getpursue.data.network.ApiClient
import app.getpursue.utils.ImageUtils
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Bottom sheet shown when a user taps a member who has already logged today.
 * Displays their avatar, name, and the time they logged.
 */
class PulseLoggedMemberBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "PulseLoggedMemberBottomSheet"
        private const val ARG_MEMBER_ID   = "member_id"
        private const val ARG_DISPLAY_NAME = "display_name"
        private const val ARG_HAS_AVATAR  = "has_avatar"
        private const val ARG_LAST_LOG_AT = "last_log_at"

        fun show(
            fm: FragmentManager,
            memberId: String,
            displayName: String,
            hasAvatar: Boolean,
            lastLogAt: String?
        ) {
            PulseLoggedMemberBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MEMBER_ID, memberId)
                    putString(ARG_DISPLAY_NAME, displayName)
                    putBoolean(ARG_HAS_AVATAR, hasAvatar)
                    putString(ARG_LAST_LOG_AT, lastLogAt)
                }
            }.show(fm, TAG)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_pulse_logged_member, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val memberId    = arguments?.getString(ARG_MEMBER_ID) ?: return
        val displayName = arguments?.getString(ARG_DISPLAY_NAME) ?: ""
        val hasAvatar   = arguments?.getBoolean(ARG_HAS_AVATAR) ?: false
        val lastLogAt   = arguments?.getString(ARG_LAST_LOG_AT)

        val avatarImg  = view.findViewById<ImageView>(R.id.pulse_logged_avatar)
        val nameView   = view.findViewById<TextView>(R.id.pulse_logged_name)
        val statusView = view.findViewById<TextView>(R.id.pulse_logged_status)
        val timeView   = view.findViewById<TextView>(R.id.pulse_logged_time)
        val closeBtn   = view.findViewById<View>(R.id.pulse_logged_close)

        nameView.text   = displayName
        statusView.text = getString(R.string.pulse_logged_status)

        // Avatar
        if (hasAvatar) {
            val avatarUrl = "${ApiClient.getBaseUrl()}/users/$memberId/avatar"
            Glide.with(this).load(avatarUrl).circleCrop().into(avatarImg)
        } else {
            avatarImg.setImageDrawable(
                ImageUtils.createLetterAvatar(
                    requireContext(),
                    displayName,
                    DailyPulseWidget.avatarColorForUser(memberId)
                )
            )
        }

        // Log time
        if (!lastLogAt.isNullOrBlank()) {
            try {
                val zdt  = ZonedDateTime.parse(lastLogAt)
                val time = zdt.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                timeView.text    = getString(R.string.pulse_logged_at_time, time)
                timeView.visibility = View.VISIBLE
            } catch (_: Exception) {
                timeView.visibility = View.GONE
            }
        }

        closeBtn.setOnClickListener { dismiss() }
    }
}
