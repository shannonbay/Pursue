package app.getpursue.ui.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.DiscoverGroupDetailResponse
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PublicGroupDetailBottomSheet : BottomSheetDialogFragment() {

    private enum class JoinState {
        INITIAL,           // Shows "Request to Join"
        CONFIRMING,        // Shows note field + "Submit Request"
        SUBMITTING,        // Button disabled, text = "Submitting…"
        SUCCESS,           // Shows "Request Pending" (disabled)
        ALREADY_REQUESTED, // Shows "Request Pending" (disabled)
        ALREADY_MEMBER,    // Shows "Already a Member" (disabled)
        FULL               // Shows "Group is Full" (disabled)
    }

    private var groupId: String? = null
    private var joinState = JoinState.INITIAL

    private lateinit var detailLoadingContainer: LinearLayout
    private lateinit var detailContentContainer: LinearLayout
    private lateinit var detailErrorContainer: LinearLayout
    private lateinit var detailGroupIconEmoji: TextView
    private lateinit var detailGroupName: TextView
    private lateinit var detailCategoryBadge: TextView
    private lateinit var detailHeatBadge: TextView
    private lateinit var detailStats: TextView
    private lateinit var detailSpots: TextView
    private lateinit var detailDescription: TextView
    private lateinit var detailGoalsHeader: TextView
    private lateinit var detailGoalsContainer: LinearLayout
    private lateinit var noteInputLayout: TextInputLayout
    private lateinit var noteInput: TextInputEditText
    private lateinit var cancelNoteButton: MaterialButton
    private lateinit var joinButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_public_group_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        detailLoadingContainer = view.findViewById(R.id.detail_loading_container)
        detailContentContainer = view.findViewById(R.id.detail_content_container)
        detailErrorContainer = view.findViewById(R.id.detail_error_container)
        detailGroupIconEmoji = view.findViewById(R.id.detail_group_icon_emoji)
        detailGroupName = view.findViewById(R.id.detail_group_name)
        detailCategoryBadge = view.findViewById(R.id.detail_category_badge)
        detailHeatBadge = view.findViewById(R.id.detail_heat_badge)
        detailStats = view.findViewById(R.id.detail_stats)
        detailSpots = view.findViewById(R.id.detail_spots)
        detailDescription = view.findViewById(R.id.detail_description)
        detailGoalsHeader = view.findViewById(R.id.detail_goals_header)
        detailGoalsContainer = view.findViewById(R.id.detail_goals_container)
        noteInputLayout = view.findViewById(R.id.note_input_layout)
        noteInput = view.findViewById(R.id.note_input)
        cancelNoteButton = view.findViewById(R.id.cancel_note_button)
        joinButton = view.findViewById(R.id.join_button)

        joinButton.setOnClickListener { handleJoinButtonClick() }
        cancelNoteButton.setOnClickListener { transitionToJoinState(JoinState.INITIAL) }

        groupId?.let { loadDetail(it) }
    }

    private fun loadDetail(id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    ApiClient.getPublicGroup(id)
                }
                if (!isAdded) return@launch
                populateDetail(detail)
            } catch (e: Exception) {
                if (!isAdded) return@launch
                detailLoadingContainer.visibility = View.GONE
                detailErrorContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun populateDetail(detail: DiscoverGroupDetailResponse) {
        detailLoadingContainer.visibility = View.GONE

        detailGroupIconEmoji.text = detail.icon_emoji?.takeIf { it.isNotBlank() } ?: "👥"
        detailGroupName.text = detail.name

        val category = detail.category
        if (category != null) {
            detailCategoryBadge.text = category.replaceFirstChar { it.uppercase() }
            detailCategoryBadge.visibility = View.VISIBLE
        }

        if (detail.heat_tier > 1) {
            detailHeatBadge.text = detail.heat_tier_name
            detailHeatBadge.visibility = View.VISIBLE
        }

        val memberText = if (detail.member_count == 1) "1 member" else "${detail.member_count} members"
        val goalText = if (detail.goals.size == 1) "1 goal" else "${detail.goals.size} goals"
        detailStats.text = "$memberText · $goalText"

        val spotsLeft = detail.spots_left
        val spotLimit = detail.spot_limit
        if (spotLimit != null && spotsLeft != null) {
            if (detail.is_full) {
                detailSpots.text = getString(R.string.discover_group_full)
                detailSpots.visibility = View.VISIBLE
            } else {
                detailSpots.text = getString(R.string.discover_spots_left, spotsLeft, spotLimit)
                detailSpots.visibility = View.VISIBLE
            }
        }

        if (!detail.description.isNullOrBlank()) {
            detailDescription.text = detail.description
            detailDescription.visibility = View.VISIBLE
        }

        if (detail.goals.isNotEmpty()) {
            detailGoalsHeader.visibility = View.VISIBLE
            detail.goals.forEach { goal ->
                val goalView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_discover_goal, detailGoalsContainer, false)
                goalView.findViewById<TextView>(R.id.goal_title).text = goal.title
                val meta = buildGoalMeta(goal.cadence, goal.active_days_label)
                val metaView = goalView.findViewById<TextView>(R.id.goal_meta)
                if (meta.isNotBlank()) {
                    metaView.text = meta
                    metaView.visibility = View.VISIBLE
                } else {
                    metaView.visibility = View.GONE
                }
                detailGoalsContainer.addView(goalView)
            }
        }

        if (detail.is_full) {
            transitionToJoinState(JoinState.FULL)
        }

        detailContentContainer.visibility = View.VISIBLE
    }

    private fun buildGoalMeta(cadence: String, activeDaysLabel: String?): String {
        val cadenceStr = when (cadence) {
            "daily" -> "Daily"
            "weekly" -> "Weekly"
            "monthly" -> "Monthly"
            else -> cadence.replaceFirstChar { it.uppercase() }
        }
        return if (!activeDaysLabel.isNullOrBlank()) "$cadenceStr · $activeDaysLabel" else cadenceStr
    }

    private fun handleJoinButtonClick() {
        when (joinState) {
            JoinState.INITIAL -> transitionToJoinState(JoinState.CONFIRMING)
            JoinState.CONFIRMING -> submitJoinRequest()
            else -> { /* no-op for disabled states */ }
        }
    }

    private fun submitJoinRequest() {
        val id = groupId ?: return
        val token = SecureTokenManager.getInstance(requireContext()).getAccessToken()
        if (token == null) {
            Toast.makeText(requireContext(), getString(R.string.discover_sign_in_to_join), Toast.LENGTH_SHORT).show()
            return
        }

        transitionToJoinState(JoinState.SUBMITTING)
        val note = noteInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApiClient.submitJoinRequest(token, id, note)
                }
                if (!isAdded) return@launch
                transitionToJoinState(JoinState.SUCCESS)
                Toast.makeText(requireContext(), getString(R.string.discover_join_request_sent), Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                if (!isAdded) return@launch
                when (e.errorCode) {
                    "ALREADY_REQUESTED" -> transitionToJoinState(JoinState.ALREADY_REQUESTED)
                    "ALREADY_MEMBER" -> transitionToJoinState(JoinState.ALREADY_MEMBER)
                    "SPOT_LIMIT_REACHED" -> transitionToJoinState(JoinState.FULL)
                    else -> {
                        transitionToJoinState(JoinState.INITIAL)
                        Toast.makeText(
                            requireContext(),
                            e.message ?: getString(R.string.error_generic),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                transitionToJoinState(JoinState.INITIAL)
                Toast.makeText(requireContext(), getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun transitionToJoinState(state: JoinState) {
        joinState = state
        when (state) {
            JoinState.INITIAL -> {
                noteInputLayout.visibility = View.GONE
                cancelNoteButton.visibility = View.GONE
                joinButton.text = getString(R.string.discover_join_request_button)
                joinButton.isEnabled = true
            }
            JoinState.CONFIRMING -> {
                noteInputLayout.visibility = View.VISIBLE
                cancelNoteButton.visibility = View.VISIBLE
                joinButton.text = getString(R.string.discover_submit_request_button)
                joinButton.isEnabled = true
            }
            JoinState.SUBMITTING -> {
                joinButton.text = getString(R.string.discover_submitting)
                joinButton.isEnabled = false
                cancelNoteButton.isEnabled = false
            }
            JoinState.SUCCESS, JoinState.ALREADY_REQUESTED -> {
                noteInputLayout.visibility = View.GONE
                cancelNoteButton.visibility = View.GONE
                joinButton.text = getString(R.string.discover_request_pending)
                joinButton.isEnabled = false
            }
            JoinState.ALREADY_MEMBER -> {
                noteInputLayout.visibility = View.GONE
                cancelNoteButton.visibility = View.GONE
                joinButton.text = getString(R.string.discover_already_member)
                joinButton.isEnabled = false
            }
            JoinState.FULL -> {
                noteInputLayout.visibility = View.GONE
                cancelNoteButton.visibility = View.GONE
                joinButton.text = getString(R.string.discover_group_full)
                joinButton.isEnabled = false
            }
        }
    }

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        const val TAG = "PublicGroupDetailBottomSheet"

        fun show(fragmentManager: FragmentManager, groupId: String) {
            val sheet = PublicGroupDetailBottomSheet()
            sheet.arguments = Bundle().apply {
                putString(ARG_GROUP_ID, groupId)
            }
            sheet.show(fragmentManager, TAG)
        }
    }
}
