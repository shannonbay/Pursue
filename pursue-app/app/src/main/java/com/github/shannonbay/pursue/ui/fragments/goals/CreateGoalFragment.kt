package com.github.shannonbay.pursue.ui.fragments.goals

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.data.network.ApiException
import com.github.shannonbay.pursue.ui.views.IconPickerBottomSheet
import android.content.Intent
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.ui.activities.MainAppActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fragment for creating a new goal (UI spec section 4.3.5).
 *
 * Handles form input, validation, icon selection, cadence/metric type toggles,
 * and API call to create goal.
 */
class CreateGoalFragment : Fragment() {

    companion object {
        private const val ARG_GROUP_ID = "group_id"

        fun newInstance(groupId: String): CreateGoalFragment {
            return CreateGoalFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                }
            }
        }
    }

    // State variables
    private var groupId: String? = null
    private var selectedEmoji: String = "\uD83C\uDFAF" // Default: 🎯
    private var selectedColor: String = IconPickerBottomSheet.Companion.getRandomDefaultColor()
    private var selectedCadence: String = "weekly"
    private var selectedMetricType: String = "binary"
    private var selectedUnit: String? = null
    private var selectedStartDate: Long? = null // null = today
    private var hasUnsavedChanges: Boolean = false

    // Views
    private lateinit var backButton: ImageButton
    private lateinit var saveButton: ImageButton
    private lateinit var saveButtonBottom: MaterialButton
    private lateinit var iconContainer: FrameLayout
    private lateinit var iconPreview: TextView
    private lateinit var chooseIconButton: MaterialButton
    private lateinit var goalTitleInput: TextInputLayout
    private lateinit var goalTitleEdit: TextInputEditText
    private lateinit var cadenceToggleGroup: MaterialButtonToggleGroup
    private lateinit var metricTypeToggleGroup: MaterialButtonToggleGroup
    private lateinit var targetSection: View
    private lateinit var targetValueInput: TextInputLayout
    private lateinit var targetValueEdit: TextInputEditText
    private lateinit var targetSuffix: TextView
    private lateinit var unitInput: TextInputLayout
    private lateinit var unitDropdown: AutoCompleteTextView
    private lateinit var startDateEdit: TextInputEditText
    private lateinit var loadingIndicator: ProgressBar

    // Unit options
    private val numericUnits = listOf("miles", "km", "meters", "reps", "pages", "items", "count", "chapters", "glasses")
    private val durationUnits = listOf("minutes", "hours")

    private lateinit var backPressedCallback: OnBackPressedCallback

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_create_goal, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initViews(view)
        setupHeader()
        setupIconPicker()
        setupTitleInput()
        setupCadenceToggle()
        setupMetricTypeToggle()
        setupUnitDropdown()
        setupStartDatePicker()
        setupBackPressHandler()

        // Set default selections
        cadenceToggleGroup.check(R.id.button_cadence_weekly)
        metricTypeToggleGroup.check(R.id.button_metric_binary)
        updateTargetSectionVisibility()
    }

    private fun initViews(view: View) {
        backButton = view.findViewById(R.id.back_button)
        saveButton = view.findViewById(R.id.save_button)
        saveButtonBottom = view.findViewById(R.id.button_save_bottom)
        iconContainer = view.findViewById(R.id.icon_container)
        iconPreview = view.findViewById(R.id.icon_preview)
        chooseIconButton = view.findViewById(R.id.button_choose_icon)
        goalTitleInput = view.findViewById(R.id.input_goal_title)
        goalTitleEdit = view.findViewById(R.id.edit_goal_title)
        cadenceToggleGroup = view.findViewById(R.id.cadence_toggle_group)
        metricTypeToggleGroup = view.findViewById(R.id.metric_type_toggle_group)
        targetSection = view.findViewById(R.id.target_section)
        targetValueInput = view.findViewById(R.id.input_target_value)
        targetValueEdit = view.findViewById(R.id.edit_target_value)
        targetSuffix = view.findViewById(R.id.target_suffix)
        unitInput = view.findViewById(R.id.input_unit)
        unitDropdown = view.findViewById(R.id.dropdown_unit)
        startDateEdit = view.findViewById(R.id.edit_start_date)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
    }

    private fun setupHeader() {
        backButton.setOnClickListener {
            handleBackPress()
        }

        saveButton.setOnClickListener {
            createGoal()
        }

        saveButtonBottom.setOnClickListener {
            createGoal()
        }
    }

    private fun setupIconPicker() {
        updateIconPreview()

        chooseIconButton.setOnClickListener {
            hideKeyboard()
            showIconPicker()
        }

        iconContainer.setOnClickListener {
            hideKeyboard()
            showIconPicker()
        }
    }

    private fun showIconPicker() {
        val bottomSheet = IconPickerBottomSheet.Companion.newInstance(R.string.icon_picker_title_goal)
        bottomSheet.setIconSelectionListener(object : IconPickerBottomSheet.IconSelectionListener {
            override fun onIconSelected(emoji: String?, color: String?) {
                if (emoji != null) {
                    selectedEmoji = emoji
                    hasUnsavedChanges = true
                }
                if (color != null) {
                    selectedColor = color
                    hasUnsavedChanges = true
                }
                updateIconPreview()
            }
        })
        bottomSheet.show(childFragmentManager, "IconPickerBottomSheet")
    }

    private fun updateIconPreview() {
        iconPreview.text = selectedEmoji
        try {
            iconContainer.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(selectedColor)
            )
        } catch (e: IllegalArgumentException) {
            // Keep default color if parsing fails
        }
    }

    private fun setupTitleInput() {
        goalTitleEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                hasUnsavedChanges = true
                validateTitle()
            }
        })
    }

    private fun setupCadenceToggle() {
        // Hide keyboard when any cadence button is clicked
        for (i in 0 until cadenceToggleGroup.childCount) {
            cadenceToggleGroup.getChildAt(i).setOnClickListener {
                hideKeyboard()
            }
        }
        
        cadenceToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                hideKeyboard()
                hasUnsavedChanges = true
                selectedCadence = when (checkedId) {
                    R.id.button_cadence_daily -> "daily"
                    R.id.button_cadence_weekly -> "weekly"
                    R.id.button_cadence_monthly -> "monthly"
                    R.id.button_cadence_yearly -> "yearly"
                    else -> "weekly"
                }
                updateTargetSuffix()
            }
        }
    }

    private fun setupMetricTypeToggle() {
        // Hide keyboard when any metric type button is clicked
        for (i in 0 until metricTypeToggleGroup.childCount) {
            metricTypeToggleGroup.getChildAt(i).setOnClickListener {
                hideKeyboard()
            }
        }
        
        metricTypeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                hideKeyboard()
                hasUnsavedChanges = true
                selectedMetricType = when (checkedId) {
                    R.id.button_metric_binary -> "binary"
                    R.id.button_metric_numeric -> "numeric"
                    R.id.button_metric_duration -> "duration"
                    else -> "binary"
                }
                updateTargetSectionVisibility()
            }
        }
    }

    private fun updateTargetSectionVisibility() {
        when (selectedMetricType) {
            "binary" -> {
                targetSection.visibility = View.GONE
                unitInput.visibility = View.GONE
            }
            "numeric" -> {
                targetSection.visibility = View.VISIBLE
                unitInput.visibility = View.VISIBLE
                updateUnitDropdown(numericUnits)
            }
            "duration" -> {
                targetSection.visibility = View.VISIBLE
                unitInput.visibility = View.VISIBLE
                updateUnitDropdown(durationUnits)
            }
        }
        updateTargetSuffix()
    }

    private fun updateTargetSuffix() {
        val suffixText = when {
            (selectedMetricType == "duration" || selectedMetricType == "numeric") && selectedUnit != null -> {
                // Format: "minutes per week", "chapters per week", "miles per day", etc.
                val cadenceText = when (selectedCadence) {
                    "daily" -> getString(R.string.per_day)
                    "weekly" -> getString(R.string.per_week)
                    "monthly" -> getString(R.string.per_month)
                    "yearly" -> getString(R.string.per_year)
                    else -> getString(R.string.per_week)
                }
                "$selectedUnit $cadenceText"
            }
            else -> {
                // Default: "times per week", etc. (for binary or when no unit selected)
                when (selectedCadence) {
                    "daily" -> getString(R.string.target_times_per_day)
                    "weekly" -> getString(R.string.target_times_per_week)
                    "monthly" -> getString(R.string.target_times_per_month)
                    "yearly" -> getString(R.string.target_times_per_year)
                    else -> getString(R.string.target_times_per_week)
                }
            }
        }
        targetSuffix.text = suffixText
    }

    private fun setupUnitDropdown() {
        updateUnitDropdown(numericUnits)

        // Hide keyboard when dropdown is clicked
        unitDropdown.setOnClickListener {
            hideKeyboard()
        }

        unitDropdown.setOnItemClickListener { _, _, position, _ ->
            hideKeyboard()
            hasUnsavedChanges = true
            val units = if (selectedMetricType == "duration") durationUnits else numericUnits
            selectedUnit = units.getOrNull(position)
            updateTargetSuffix() // Update suffix when unit changes
        }
    }

    private fun updateUnitDropdown(units: List<String>) {
        val adapter = object : ArrayAdapter<String>(
            requireContext(),
            android.R.layout.simple_dropdown_item_1line,
            units
        ) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = super.getView(position, convertView, parent)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                textView.setTextColor(requireContext().getColor(R.color.on_surface))
                return view
            }
        }
        unitDropdown.setAdapter(adapter)

        // Clear previous selection if unit not in new list
        if (selectedUnit != null && !units.contains(selectedUnit)) {
            selectedUnit = null
            unitDropdown.setText("", false)
        }
    }

    private fun setupStartDatePicker() {
        startDateEdit.setOnClickListener {
            hideKeyboard()
            showDatePicker()
        }
    }

    private fun showDatePicker() {
        // Create date picker - it will use the app theme (Theme.Pursue) which includes
        // the custom date picker theme overlay for proper text colors in dark mode
        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.start_date_label))
            .setSelection(selectedStartDate ?: MaterialDatePicker.todayInUtcMilliseconds())
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            hasUnsavedChanges = true
            selectedStartDate = selection

            val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
            startDateEdit.setText(dateFormat.format(Date(selection)))
        }

        datePicker.show(childFragmentManager, "DatePicker")
    }

    private fun setupBackPressHandler() {
        backPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                handleBackPress()
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backPressedCallback)
    }

    private fun handleBackPress() {
        if (hasUnsavedChanges) {
            showDiscardChangesDialog()
        } else {
            navigateBack()
        }
    }

    private fun showDiscardChangesDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.discard_changes_title))
            .setMessage(getString(R.string.discard_changes_message))
            .setPositiveButton(getString(R.string.discard)) { _, _ ->
                navigateBack()
            }
            .setNegativeButton(getString(R.string.keep_editing), null)
            .show()
    }

    private fun navigateBack() {
        requireActivity().supportFragmentManager.popBackStack()
    }

    private fun validateTitle(): Boolean {
        val title = goalTitleEdit.text?.toString()?.trim() ?: ""
        return when {
            title.isEmpty() -> {
                goalTitleInput.error = getString(R.string.goal_title_error)
                false
            }
            title.length > 100 -> {
                goalTitleInput.error = getString(R.string.goal_title_error)
                false
            }
            else -> {
                goalTitleInput.error = null
                true
            }
        }
    }

    private fun validateTarget(): Boolean {
        // Target only required for numeric and duration types
        if (selectedMetricType == "binary") {
            return true
        }

        val targetText = targetValueEdit.text?.toString()?.trim() ?: ""
        return if (targetText.isEmpty()) {
            targetValueInput.error = getString(R.string.target_value_required)
            false
        } else {
            targetValueInput.error = null
            true
        }
    }

    private fun validateForm(): Boolean {
        val titleValid = validateTitle()
        val targetValid = validateTarget()
        return titleValid && targetValid
    }

    private fun createGoal() {
        if (!validateForm()) {
            return
        }

        val groupId = this.groupId
        if (groupId == null) {
            Toast.makeText(requireContext(), getString(R.string.goal_creation_failed), Toast.LENGTH_SHORT).show()
            return
        }

        val title = goalTitleEdit.text?.toString()?.trim() ?: ""
        val targetValue = if (selectedMetricType != "binary") {
            targetValueEdit.text?.toString()?.trim()?.toDoubleOrNull()
        } else {
            null
        }
        val unit = if (selectedMetricType != "binary") selectedUnit else null

        showLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch

                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    Handler(Looper.getMainLooper()).post {
                        showLoading(false)
                        Toast.makeText(requireContext(), "Please sign in", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    ApiClient.createGoal(
                        accessToken = accessToken,
                        groupId = groupId,
                        title = title,
                        description = null,
                        cadence = selectedCadence,
                        metricType = selectedMetricType,
                        targetValue = targetValue,
                        unit = unit
                    )
                }

                if (!isAdded) return@launch

                Handler(Looper.getMainLooper()).post {
                    showLoading(false)
                    Toast.makeText(requireContext(), getString(R.string.goal_created), Toast.LENGTH_SHORT).show()
                    hasUnsavedChanges = false
                    navigateBack()
                }
            } catch (e: ApiException) {
                if (!isAdded) return@launch

                Handler(Looper.getMainLooper()).post {
                    showLoading(false)
                    val errorMessage = when (e.code) {
                        400 -> "Invalid goal data. Please check your input."
                        401 -> "Please sign in again"
                        403 -> {
                            if (e.errorCode == "GROUP_READ_ONLY") {
                                val intent = Intent(requireContext(), MainAppActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                                    putExtra(MainAppActivity.EXTRA_OPEN_PREMIUM, true)
                                }
                                startActivity(intent)
                                activity?.finish()
                                return@post
                            }
                            "You don't have permission to create goals in this group."
                        }
                        500, 503 -> "Server error. Please try again later."
                        else -> getString(R.string.goal_creation_failed)
                    }
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch

                Handler(Looper.getMainLooper()).post {
                    showLoading(false)
                    Toast.makeText(requireContext(), getString(R.string.goal_creation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        Handler(Looper.getMainLooper()).post {
            loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
            saveButton.isEnabled = !show
            saveButtonBottom.isEnabled = !show
            backButton.isEnabled = !show
            goalTitleEdit.isEnabled = !show
            chooseIconButton.isEnabled = !show
            targetValueEdit.isEnabled = !show
            unitDropdown.isEnabled = !show
            startDateEdit.isEnabled = !show

            // Disable toggle groups
            for (i in 0 until cadenceToggleGroup.childCount) {
                cadenceToggleGroup.getChildAt(i).isEnabled = !show
            }
            for (i in 0 until metricTypeToggleGroup.childCount) {
                metricTypeToggleGroup.getChildAt(i).isEnabled = !show
            }
        }
    }

    /**
     * Hides the keyboard and clears focus from text inputs.
     */
    private fun hideKeyboard() {
        val imm = ContextCompat.getSystemService(requireContext(), InputMethodManager::class.java)
        val currentFocus = requireActivity().currentFocus
        if (currentFocus != null) {
            imm?.hideSoftInputFromWindow(currentFocus.windowToken, 0)
            currentFocus.clearFocus()
        }
    }
}
