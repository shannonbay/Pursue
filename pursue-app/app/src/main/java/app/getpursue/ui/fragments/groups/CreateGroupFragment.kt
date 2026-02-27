package app.getpursue.ui.fragments.groups

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.os.Handler
import android.os.Looper
import app.getpursue.data.analytics.AnalyticsEvents
import app.getpursue.data.analytics.AnalyticsLogger
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.fcm.FcmTopicManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.views.ActiveDaysSelectorView
import app.getpursue.ui.views.CovenantBottomSheet
import app.getpursue.ui.views.IconPickerBottomSheet
import app.getpursue.utils.GroupCategories
import app.getpursue.utils.IconUrlUtils
import app.getpursue.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment for creating a new group (UI spec section 4.8).
 *
 * Handles form input, validation, icon selection, and API call to create group.
 */
class CreateGroupFragment : Fragment() {

    interface Callbacks {
        fun onGroupCreated(groupId: String, groupName: String, hasIcon: Boolean, iconEmoji: String?)
    }

    companion object {
        private const val ARG_HIDE_CANCEL_BUTTON = "hide_cancel_button"

        fun newInstance(hideCancelButton: Boolean = false): CreateGroupFragment {
            return CreateGroupFragment().apply {
                arguments = Bundle().apply {
                    putBoolean(ARG_HIDE_CANCEL_BUTTON, hideCancelButton)
                }
            }
        }
    }

    private var callbacks: Callbacks? = null

    private lateinit var iconPreview: TextView
    private lateinit var iconImage: ImageView
    private lateinit var iconContainer: FrameLayout
    private lateinit var chooseIconButton: MaterialButton
    private lateinit var groupNameInput: TextInputLayout
    private lateinit var groupNameEdit: TextInputEditText
    private lateinit var descriptionInput: TextInputLayout
    private lateinit var descriptionEdit: TextInputEditText
    private lateinit var switchPublicListing: SwitchMaterial
    private lateinit var btnVisibilityInfo: ImageView
    private lateinit var containerSpotLimit: LinearLayout
    private lateinit var containerCategory: LinearLayout
    private lateinit var radioGroupSpotLimit: RadioGroup
    private lateinit var radioUnlimited: RadioButton
    private lateinit var radioCustomLimit: RadioButton
    private lateinit var inputSpotLimit: TextInputLayout
    private lateinit var editSpotLimit: TextInputEditText
    private lateinit var inputCategory: TextInputLayout
    private lateinit var dropdownCategory: AutoCompleteTextView
    
    private lateinit var inputGoalTitle: TextInputLayout
    private lateinit var editGoalTitle: TextInputEditText
    private lateinit var toggleCadence: MaterialButtonToggleGroup
    private lateinit var activeDaysSelectorView: ActiveDaysSelectorView
    private lateinit var toggleType: MaterialButtonToggleGroup
    private lateinit var numericFields: LinearLayout
    private lateinit var editTarget: TextInputEditText
    private lateinit var editUnit: TextInputEditText
    private lateinit var journalFields: LinearLayout
    private lateinit var editLogTitlePrompt: TextInputEditText

    private lateinit var createButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var loadingIndicator: ProgressBar

    private var selectedEmoji: String? = null
    private var selectedColor: String = IconPickerBottomSheet.Companion.getRandomDefaultColor()
    private var selectedIconUrl: String? = null
    private var selectedVisibility: String = "private"
    private var selectedCategory: String? = null
    private var selectedSpotLimit: Int? = null

    fun setCallbacks(callbacks: Callbacks) {
        this.callbacks = callbacks
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_create_group, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        iconPreview = view.findViewById(R.id.icon_preview)
        iconImage = view.findViewById(R.id.icon_image)
        iconContainer = view.findViewById(R.id.icon_container)
        chooseIconButton = view.findViewById(R.id.button_choose_icon)
        groupNameInput = view.findViewById(R.id.input_group_name)
        groupNameEdit = view.findViewById(R.id.edit_group_name)
        descriptionInput = view.findViewById(R.id.input_description)
        descriptionEdit = view.findViewById(R.id.edit_description)
        switchPublicListing = view.findViewById(R.id.switch_public_listing)
        btnVisibilityInfo = view.findViewById(R.id.btn_visibility_info)
        containerSpotLimit = view.findViewById(R.id.container_spot_limit)
        containerCategory = view.findViewById(R.id.container_category)
        radioGroupSpotLimit = view.findViewById(R.id.radio_group_spot_limit)
        radioUnlimited = view.findViewById(R.id.radio_unlimited)
        radioCustomLimit = view.findViewById(R.id.radio_custom_limit)
        inputSpotLimit = view.findViewById(R.id.input_spot_limit)
        editSpotLimit = view.findViewById(R.id.edit_spot_limit)
        inputCategory = view.findViewById(R.id.input_category)
        dropdownCategory = view.findViewById(R.id.dropdown_category)
        
        inputGoalTitle = view.findViewById(R.id.input_goal_title)
        editGoalTitle = view.findViewById(R.id.edit_goal_title)
        toggleCadence = view.findViewById(R.id.toggle_cadence)
        activeDaysSelectorView = view.findViewById(R.id.active_days_selector)
        toggleType = view.findViewById(R.id.toggle_type)
        numericFields = view.findViewById(R.id.numeric_fields)
        editTarget = view.findViewById(R.id.edit_target)
        editUnit = view.findViewById(R.id.edit_unit)
        journalFields = view.findViewById(R.id.journal_fields)
        editLogTitlePrompt = view.findViewById(R.id.edit_log_title_prompt)

        createButton = view.findViewById(R.id.button_create)
        cancelButton = view.findViewById(R.id.button_cancel)
        loadingIndicator = view.findViewById(R.id.loading_indicator)

        if (arguments?.getBoolean(ARG_HIDE_CANCEL_BUTTON) == true) {
            cancelButton.visibility = View.GONE
        }

        setupIconPreview()
        setupCategoryDropdown()
        setupVisibilitySwitch()
        setupSpotLimitSection()
        setupGoalSection()
        setupButtons()
        setupTextWatchers()
        
        updateCreateButtonState()
    }

    // ─── Setup helpers ────────────────────────────────────────────────────────

    private fun setupIconPreview() {
        updateIconPreview()
        chooseIconButton.setOnClickListener { showIconPicker() }
    }

    private fun setupCategoryDropdown() {
        val categories = GroupCategories.entries(requireContext())
        val displayNames = categories.map { it.second }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayNames)
        dropdownCategory.setAdapter(adapter)
        dropdownCategory.setOnItemClickListener { _, _, position, _ ->
            selectedCategory = categories[position].first
            inputCategory.error = null
        }
    }

    private fun setupVisibilitySwitch() {
        switchPublicListing.setOnCheckedChangeListener { _, isChecked ->
            selectedVisibility = if (isChecked) "public" else "private"
            containerSpotLimit.visibility = if (isChecked) View.VISIBLE else View.GONE
            containerCategory.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) {
                selectedCategory = null
                dropdownCategory.setText("", false)
                inputCategory.error = null
            }
        }

        btnVisibilityInfo.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.label_public_listing))
                .setMessage(getString(R.string.public_listing_tooltip))
                .setPositiveButton(getString(android.R.string.ok), null)
                .show()
        }
    }

    private fun setupSpotLimitSection() {
        radioGroupSpotLimit.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_unlimited -> {
                    inputSpotLimit.visibility = View.GONE
                    selectedSpotLimit = null
                }
                R.id.radio_custom_limit -> {
                    inputSpotLimit.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupGoalSection() {
        // Default selections
        toggleCadence.check(R.id.btn_daily)
        toggleType.check(R.id.btn_binary)

        // Show active days selector only for daily cadence
        activeDaysSelectorView.visibility = View.VISIBLE
        toggleCadence.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                activeDaysSelectorView.visibility =
                    if (checkedId == R.id.btn_daily) View.VISIBLE else View.GONE
            }
        }

        // Toggle numeric/journal fields visibility
        toggleType.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                numericFields.visibility = if (checkedId == R.id.btn_numeric) View.VISIBLE else View.GONE
                journalFields.visibility = if (checkedId == R.id.btn_journal) View.VISIBLE else View.GONE
            }
        }
    }

    private fun setupButtons() {
        createButton.setOnClickListener { createGroup() }
        cancelButton.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }
    }

    private fun setupTextWatchers() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateGroupName()
                validateGoalTitle()
                updateCreateButtonState()
                if (selectedEmoji == null && selectedIconUrl == null) updateIconPreview()
            }
        }
        groupNameEdit.addTextChangedListener(watcher)
        editGoalTitle.addTextChangedListener(watcher)

        descriptionEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { validateDescription() }
        })
    }

    // ─── Icon preview ─────────────────────────────────────────────────────────

    private fun updateIconPreview() {
        if (selectedIconUrl != null) {
            iconImage.visibility = View.VISIBLE
            iconPreview.visibility = View.GONE
            IconUrlUtils.loadInto(requireContext(), selectedIconUrl!!, iconImage)
        } else {
            iconImage.visibility = View.GONE
            iconPreview.visibility = View.VISIBLE
            if (selectedEmoji != null) {
                iconPreview.text = selectedEmoji
            } else {
                val name = groupNameEdit.text?.toString()?.take(1)?.uppercase() ?: "?"
                iconPreview.text = if (name.isEmpty()) "?" else name
            }
        }
        try {
            iconContainer.backgroundTintList = ColorStateList.valueOf(Color.parseColor(selectedColor))
        } catch (_: IllegalArgumentException) {}
    }

    private fun showIconPicker() {
        val bottomSheet = IconPickerBottomSheet.Companion.newInstance(R.string.icon_picker_title)
        bottomSheet.setIconSelectionListener(object : IconPickerBottomSheet.IconSelectionListener {
            override fun onIconSelected(emoji: String?, color: String?, iconUrl: String?) {
                if (emoji != null) {
                    selectedEmoji = emoji
                    selectedIconUrl = null
                } else if (iconUrl != null) {
                    selectedIconUrl = iconUrl
                    selectedEmoji = null
                }
                if (color != null) selectedColor = color
                updateIconPreview()
            }
        })
        bottomSheet.show(childFragmentManager, "IconPickerBottomSheet")
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    private fun updateCreateButtonState() {
        val nameOk = !groupNameEdit.text.isNullOrBlank()
        val goalOk = !editGoalTitle.text.isNullOrBlank()
        createButton.isEnabled = nameOk && goalOk
    }

    private fun validateGroupName(): Boolean {
        val name = groupNameEdit.text?.toString()?.trim() ?: ""
        return when {
            name.isEmpty() -> {
                // groupNameInput.error = getString(R.string.group_name_error)
                false
            }
            name.length > 100 -> {
                groupNameInput.error = getString(R.string.group_name_error)
                false
            }
            else -> {
                groupNameInput.error = null
                true
            }
        }
    }

    private fun validateGoalTitle(): Boolean {
        val title = editGoalTitle.text?.toString()?.trim() ?: ""
        return when {
            title.isEmpty() -> {
                false
            }
            title.length > 200 -> {
                inputGoalTitle.error = getString(R.string.goal_title_error)
                false
            }
            else -> {
                inputGoalTitle.error = null
                true
            }
        }
    }

    private fun validateDescription(): Boolean {
        val description = descriptionEdit.text?.toString() ?: ""
        return if (description.length > 500) {
            descriptionInput.error = getString(R.string.description_char_counter, description.length)
            false
        } else {
            descriptionInput.error = null
            true
        }
    }

    private fun validateCategory(): Boolean {
        return if (selectedCategory == null) {
            inputCategory.error = getString(R.string.category_required_error)
            false
        } else {
            inputCategory.error = null
            true
        }
    }

    private fun validateSpotLimit(): Boolean {
        if (radioCustomLimit.isChecked) {
            val raw = editSpotLimit.text?.toString()?.trim() ?: ""
            val value = raw.toIntOrNull()
            return if (value == null || value < 2 || value > 500) {
                inputSpotLimit.error = getString(R.string.spot_limit_error)
                false
            } else {
                inputSpotLimit.error = null
                selectedSpotLimit = value
                true
            }
        }
        selectedSpotLimit = null
        return true
    }

    // ─── Create flow ──────────────────────────────────────────────────────────

    private fun createGroup() {
        if (!validateGroupName()) return
        if (!validateGoalTitle()) return
        if (!validateDescription()) return
        if (selectedVisibility == "public" && !validateCategory()) return
        if (!validateSpotLimit()) return

        val covenant = CovenantBottomSheet.newInstance(isChallenge = false)
        covenant.setCovenantListener(object : CovenantBottomSheet.CovenantListener {
            override fun onCovenantAccepted() {
                performCreateGroup()
            }
        })
        covenant.show(childFragmentManager, "CovenantBottomSheet")
    }

    private fun performCreateGroup() {
        val name = groupNameEdit.text?.toString()?.trim() ?: ""
        val description = descriptionEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val goalTitle = editGoalTitle.text?.toString()?.trim() ?: ""
        
        val cadence = when (toggleCadence.checkedButtonId) {
            R.id.btn_weekly -> "weekly"
            R.id.btn_monthly -> "monthly"
            else -> "daily"
        }
        val metricType = when (toggleType.checkedButtonId) {
            R.id.btn_numeric -> "numeric"
            R.id.btn_journal -> "journal"
            else -> "binary"
        }
        val targetValue = if (metricType == "numeric") {
            editTarget.text?.toString()?.toDoubleOrNull()
        } else null
        val unit = if (metricType == "numeric") {
            editUnit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        } else null
        val logTitlePrompt = if (metricType == "journal") {
            editLogTitlePrompt.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        } else null

        showLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = context ?: return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(context)
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    showLoading(false)
                    Toast.makeText(context, "Please sign in", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // 1. Create group
                val groupResponse = withContext(Dispatchers.IO) {
                    ApiClient.createGroup(
                        accessToken = accessToken,
                        name = name,
                        description = description,
                        iconEmoji = selectedEmoji,
                        iconColor = if (selectedEmoji != null) selectedColor else null,
                        iconUrl = selectedIconUrl,
                        visibility = selectedVisibility,
                        category = selectedCategory,
                        spotLimit = selectedSpotLimit
                    )
                }

                if (!isAdded) return@launch
                val groupId = groupResponse.id
                val groupName = groupResponse.name

                // 2. Create goal
                val activeDays = if (cadence == "daily") activeDaysSelectorView.getActiveDays() else null
                withContext(Dispatchers.IO) {
                    ApiClient.createGoal(
                        accessToken = accessToken,
                        groupId = groupId,
                        title = goalTitle,
                        cadence = cadence,
                        metricType = metricType,
                        targetValue = targetValue,
                        unit = unit,
                        activeDays = activeDays,
                        logTitlePrompt = logTitlePrompt
                    )
                }
                
                if (!isAdded) return@launch

                // 3. Subscribe to topics
                launch { FcmTopicManager.subscribeToGroupTopics(context, groupId) }

                showLoading(false)
                AnalyticsLogger.logEvent(AnalyticsEvents.GROUP_CREATED, Bundle().apply {
                    putString(AnalyticsEvents.Param.VISIBILITY, selectedVisibility)
                    putString(AnalyticsEvents.Param.CATEGORY, selectedCategory ?: "none")
                    putString(AnalyticsEvents.Param.CADENCE, cadence)
                    putString(AnalyticsEvents.Param.METRIC_TYPE, metricType)
                })
                Toast.makeText(context, getString(R.string.group_created), Toast.LENGTH_SHORT).show()

                if (callbacks != null) {
                    callbacks?.onGroupCreated(groupId, groupName, groupResponse.has_icon, groupResponse.icon_emoji)
                } else {
                    // Default behavior: navigate to detail
                    val intent = Intent(context, GroupDetailActivity::class.java).apply {
                        putExtra(GroupDetailActivity.EXTRA_GROUP_ID, groupId)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, groupName)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_VISIBILITY, groupResponse.visibility)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, groupResponse.has_icon)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_ICON_EMOJI, groupResponse.icon_emoji)
                        putExtra(GroupDetailActivity.EXTRA_OPEN_INVITE_SHEET, true)
                    }
                    startActivity(intent)
                    requireActivity().supportFragmentManager.popBackStack()
                }
            } catch (e: ApiException) {
                if (!isAdded) return@launch

                showLoading(false)
                val errorMessage = when (e.code) {
                    400 -> "Invalid group data. Please check your input."
                    401 -> "Please sign in again"
                    500, 503 -> "Server error. Please try again later."
                    else -> e.message ?: getString(R.string.group_creation_failed)
                }
                context?.let { Toast.makeText(it, errorMessage, Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                if (!isAdded) return@launch

                showLoading(false)
                context?.let { Toast.makeText(it, getString(R.string.group_creation_failed), Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        createButton.isEnabled = !show
        cancelButton.isEnabled = !show && arguments?.getBoolean(ARG_HIDE_CANCEL_BUTTON) != true
        groupNameEdit.isEnabled = !show
        descriptionEdit.isEnabled = !show
        chooseIconButton.isEnabled = !show
        switchPublicListing.isEnabled = !show
        radioGroupSpotLimit.isEnabled = !show
        radioUnlimited.isEnabled = !show
        radioCustomLimit.isEnabled = !show
        editSpotLimit.isEnabled = !show
        dropdownCategory.isEnabled = !show
        editGoalTitle.isEnabled = !show
        toggleCadence.isEnabled = !show
        activeDaysSelectorView.isEnabled = !show
        toggleType.isEnabled = !show
        editTarget.isEnabled = !show
        editUnit.isEnabled = !show
        editLogTitlePrompt.isEnabled = !show
    }
}
