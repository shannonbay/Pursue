package app.getpursue.ui.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import app.getpursue.R
import app.getpursue.data.analytics.AnalyticsEvents
import app.getpursue.data.analytics.AnalyticsLogger
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.utils.ImageUtils
import com.bumptech.glide.Glide
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Bottom sheet shown when a user taps a member who has NOT logged yet.
 * Lets the current user send a nudge (or shows "already nudged" if they already have).
 */
class PulseNudgeBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val TAG = "PulseNudgeBottomSheet"
        private const val ARG_MEMBER_ID    = "member_id"
        private const val ARG_DISPLAY_NAME = "display_name"
        private const val ARG_HAS_AVATAR   = "has_avatar"
        private const val ARG_GROUP_ID     = "group_id"

        fun show(
            fm: FragmentManager,
            memberId: String,
            displayName: String,
            hasAvatar: Boolean,
            groupId: String
        ) {
            PulseNudgeBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_MEMBER_ID, memberId)
                    putString(ARG_DISPLAY_NAME, displayName)
                    putBoolean(ARG_HAS_AVATAR, hasAvatar)
                    putString(ARG_GROUP_ID, groupId)
                }
            }.show(fm, TAG)
        }
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_pulse_nudge, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val memberId    = arguments?.getString(ARG_MEMBER_ID) ?: return
        val displayName = arguments?.getString(ARG_DISPLAY_NAME) ?: ""
        val hasAvatar   = arguments?.getBoolean(ARG_HAS_AVATAR) ?: false
        val groupId     = arguments?.getString(ARG_GROUP_ID) ?: return

        val avatarImg      = view.findViewById<ImageView>(R.id.pulse_nudge_avatar)
        val nameView       = view.findViewById<TextView>(R.id.pulse_nudge_name)
        val alreadySentMsg = view.findViewById<TextView>(R.id.pulse_nudge_already_sent)
        val nudgeButton    = view.findViewById<MaterialButton>(R.id.pulse_nudge_button)

        nameView.text    = displayName
        nudgeButton.text = getString(R.string.pulse_nudge_cta, displayName)

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

        val ctx = requireContext()
        val token = SecureTokenManager.getInstance(ctx).getAccessToken()
        if (token == null) {
            nudgeButton.isEnabled = false
            return
        }

        val today = LocalDate.now().toString()

        // Check if already nudged today
        scope.launch {
            try {
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.getNudgesSentToday(token, groupId, today)
                }
                if (!isAdded) return@launch
                val alreadyNudged = memberId in resp.nudged_user_ids
                if (alreadyNudged) {
                    nudgeButton.isEnabled = false
                    nudgeButton.alpha = 0.5f
                    alreadySentMsg.text = getString(R.string.pulse_already_nudged, displayName)
                    alreadySentMsg.visibility = View.VISIBLE
                }
            } catch (_: Exception) {
                // Non-fatal: leave button enabled
            }
        }

        nudgeButton.setOnClickListener {
            nudgeButton.isEnabled = false
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        ApiClient.sendNudge(
                            accessToken     = token,
                            recipientUserId = memberId,
                            groupId         = groupId,
                            senderLocalDate = today
                        )
                    }
                    if (!isAdded) return@launch
                    AnalyticsLogger.logEvent(
                        AnalyticsEvents.DAILY_PULSE_NUDGE_SENT,
                        Bundle().apply { putString("group_id", groupId) }
                    )
                    Toast.makeText(ctx, getString(R.string.nudge_sent, displayName), Toast.LENGTH_SHORT).show()
                    dismiss()
                } catch (e: ApiException) {
                    if (!isAdded) return@launch
                    val msg = when {
                        e.message?.contains("ALREADY_NUDGED") == true ->
                            getString(R.string.already_nudged_today, displayName)
                        e.message?.contains("DAILY_SEND_LIMIT") == true ->
                            getString(R.string.nudge_daily_limit)
                        else -> getString(R.string.nudge_failed)
                    }
                    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                    nudgeButton.isEnabled = true
                } catch (_: Exception) {
                    if (!isAdded) return@launch
                    Toast.makeText(ctx, getString(R.string.nudge_failed), Toast.LENGTH_SHORT).show()
                    nudgeButton.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scope.cancel()
    }
}
