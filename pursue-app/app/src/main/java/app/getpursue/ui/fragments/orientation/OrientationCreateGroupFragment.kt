package app.getpursue.ui.fragments.orientation

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.fcm.FcmTopicManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.activities.OrientationActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OrientationCreateGroupFragment : Fragment() {

    private lateinit var inputGroupName: TextInputLayout
    private lateinit var editGroupName: TextInputEditText
    private lateinit var inputGoalTitle: TextInputLayout
    private lateinit var editGoalTitle: TextInputEditText
    private lateinit var toggleCadence: MaterialButtonToggleGroup
    private lateinit var toggleType: MaterialButtonToggleGroup
    private lateinit var numericFields: LinearLayout
    private lateinit var editTarget: TextInputEditText
    private lateinit var editUnit: TextInputEditText
    private lateinit var buttonCreate: MaterialButton
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var emojiRow: LinearLayout

    private var selectedEmoji: String? = null

    private val emojis = listOf(
        "\uD83D\uDE00", "\uD83C\uDFC3", "\uD83D\uDCDA", "\uD83E\uDDD8", "\uD83D\uDCAA", "\uD83C\uDFAF",
        "\uD83C\uDF4E", "\uD83D\uDCA7", "✏\uFE0F", "\uD83C\uDFB5", "\uD83C\uDF05", "⭐"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_orientation_create_group, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        inputGroupName = view.findViewById(R.id.input_group_name)
        editGroupName = view.findViewById(R.id.edit_group_name)
        inputGoalTitle = view.findViewById(R.id.input_goal_title)
        editGoalTitle = view.findViewById(R.id.edit_goal_title)
        toggleCadence = view.findViewById(R.id.toggle_cadence)
        toggleType = view.findViewById(R.id.toggle_type)
        numericFields = view.findViewById(R.id.numeric_fields)
        editTarget = view.findViewById(R.id.edit_target)
        editUnit = view.findViewById(R.id.edit_unit)
        buttonCreate = view.findViewById(R.id.button_create)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        emojiRow = view.findViewById(R.id.emoji_row)

        // Setup progress dots for step 3
        setupProgressDots(view.findViewById(R.id.progress_dots), 3)

        // Back / Skip
        view.findViewById<MaterialButton>(R.id.button_back).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.button_skip).setOnClickListener {
            (requireActivity() as OrientationActivity).completeOrientation()
        }

        // Emoji picker row
        setupEmojiRow()

        // Default selections
        toggleCadence.check(R.id.btn_daily)
        toggleType.check(R.id.btn_binary)

        // Toggle numeric fields visibility
        toggleType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                numericFields.visibility = if (checkedId == R.id.btn_numeric) View.VISIBLE else View.GONE
            }
        }

        // Form validation for create button
        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updateCreateButtonState() }
        }
        editGroupName.addTextChangedListener(textWatcher)
        editGoalTitle.addTextChangedListener(textWatcher)

        buttonCreate.setOnClickListener { createGroup() }
    }

    private fun setupEmojiRow() {
        emojis.forEach { emoji ->
            val tv = TextView(requireContext()).apply {
                text = emoji
                textSize = 24f
                val size = (48 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (4 * resources.displayMetrics.density).toInt()
                }
                gravity = android.view.Gravity.CENTER
                setOnClickListener {
                    selectedEmoji = if (selectedEmoji == emoji) null else emoji
                    highlightSelectedEmoji()
                }
            }
            emojiRow.addView(tv)
        }
    }

    private fun highlightSelectedEmoji() {
        for (i in 0 until emojiRow.childCount) {
            val child = emojiRow.getChildAt(i) as TextView
            child.alpha = if (selectedEmoji == null || child.text == selectedEmoji) 1.0f else 0.4f
        }
    }

    private fun updateCreateButtonState() {
        val nameOk = !editGroupName.text.isNullOrBlank()
        val goalOk = !editGoalTitle.text.isNullOrBlank()
        buttonCreate.isEnabled = nameOk && goalOk
    }

    private fun createGroup() {
        val name = editGroupName.text?.toString()?.trim() ?: ""
        val goalTitle = editGoalTitle.text?.toString()?.trim() ?: ""

        if (name.isEmpty()) {
            inputGroupName.error = getString(R.string.group_name_error)
            return
        }
        if (goalTitle.isEmpty()) {
            inputGoalTitle.error = getString(R.string.goal_title_error)
            return
        }

        val cadence = when (toggleCadence.checkedButtonId) {
            R.id.btn_weekly -> "weekly"
            R.id.btn_monthly -> "monthly"
            else -> "daily"
        }
        val metricType = when (toggleType.checkedButtonId) {
            R.id.btn_numeric -> "numeric"
            else -> "binary"
        }
        val targetValue = if (metricType == "numeric") {
            editTarget.text?.toString()?.toDoubleOrNull()
        } else null
        val unit = if (metricType == "numeric") {
            editUnit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } else null

        setLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SecureTokenManager.getInstance(requireContext()).getAccessToken()
                if (token == null) {
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    setLoading(false)
                    return@launch
                }

                // Create group
                val groupResponse = withContext(Dispatchers.IO) {
                    ApiClient.createGroup(
                        accessToken = token,
                        name = name,
                        iconEmoji = selectedEmoji
                    )
                }
                if (!isAdded) return@launch

                val groupId = groupResponse.id
                val groupName = groupResponse.name

                // Create goal in the new group
                withContext(Dispatchers.IO) {
                    ApiClient.createGoal(
                        accessToken = token,
                        groupId = groupId,
                        title = goalTitle,
                        cadence = cadence,
                        metricType = metricType,
                        targetValue = targetValue,
                        unit = unit
                    )
                }
                if (!isAdded) return@launch

                // Subscribe to FCM topics
                launch { FcmTopicManager.subscribeToGroupTopics(requireContext(), groupId) }

                Toast.makeText(requireContext(), getString(R.string.group_created), Toast.LENGTH_SHORT).show()

                val intent = Intent(requireContext(), GroupDetailActivity::class.java).apply {
                    putExtra(GroupDetailActivity.EXTRA_GROUP_ID, groupId)
                    putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, groupName)
                    putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, selectedEmoji != null)
                    putExtra(GroupDetailActivity.EXTRA_GROUP_ICON_EMOJI, selectedEmoji)
                    putExtra(GroupDetailActivity.EXTRA_OPEN_INVITE_SHEET, true)
                }
                (requireActivity() as OrientationActivity).completeOrientation(intent)
            } catch (e: ApiException) {
                if (!isAdded) return@launch
                setLoading(false)
                Toast.makeText(
                    requireContext(),
                    e.message ?: getString(R.string.group_creation_failed),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                setLoading(false)
                Toast.makeText(requireContext(), getString(R.string.group_creation_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        buttonCreate.isEnabled = !show
        editGroupName.isEnabled = !show
        editGoalTitle.isEnabled = !show
        editTarget.isEnabled = !show
        editUnit.isEnabled = !show
    }

    companion object {
        fun newInstance(): OrientationCreateGroupFragment = OrientationCreateGroupFragment()
    }
}
