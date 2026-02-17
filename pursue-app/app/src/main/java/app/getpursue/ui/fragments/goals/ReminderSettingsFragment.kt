package app.getpursue.ui.fragments.goals

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.GoalReminderPreferencesResponse
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Per-goal reminder settings (smart-reminders-spec 7.2).
 * Enable/disable, mode (smart / fixed / off), aggressiveness, quiet hours, pattern display, recalculate.
 */
class ReminderSettingsFragment : Fragment() {

    companion object {
        private const val ARG_GOAL_ID = "goal_id"
        private const val ARG_GOAL_TITLE = "goal_title"
        private const val ARG_GROUP_NAME = "group_name"

        fun newInstance(goalId: String, goalTitle: String, groupName: String): ReminderSettingsFragment {
            return ReminderSettingsFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GOAL_ID, goalId)
                    putString(ARG_GOAL_TITLE, goalTitle)
                    putString(ARG_GROUP_NAME, groupName)
                }
            }
        }
    }

    private var goalId: String = ""
    private var goalTitle: String = ""
    private var groupName: String = ""

    private lateinit var loadingIndicator: ProgressBar
    private lateinit var contentContainer: ViewGroup
    private lateinit var goalTitleText: TextView
    private lateinit var groupNameText: TextView
    private lateinit var enableSwitch: SwitchCompat
    private lateinit var modeSmart: RadioButton
    private lateinit var modeFixed: RadioButton
    private lateinit var modeOff: RadioButton
    private lateinit var smartCard: MaterialCardView
    private lateinit var patternText: TextView
    private lateinit var patternLogsText: TextView
    private lateinit var recalculateButton: MaterialButton
    private lateinit var fixedCard: MaterialCardView
    private lateinit var fixedTimeButton: MaterialButton
    private lateinit var levelGentle: RadioButton
    private lateinit var levelBalanced: RadioButton
    private lateinit var levelPersistent: RadioButton
    private lateinit var quietFromButton: MaterialButton
    private lateinit var quietToButton: MaterialButton
    private lateinit var saveButton: MaterialButton

    private var prefs: GoalReminderPreferencesResponse? = null
    private var fixedHour: Int = 21
    private var quietHoursStart: Int? = null
    private var quietHoursEnd: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goalId = requireArguments().getString(ARG_GOAL_ID)!!
        goalTitle = requireArguments().getString(ARG_GOAL_TITLE)!!
        groupName = requireArguments().getString(ARG_GROUP_NAME)!!
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_reminder_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        (requireActivity() as? AppCompatActivity)?.supportActionBar?.setDisplayHomeAsUpEnabled(true)
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = getString(R.string.reminder_settings_title)

        loadingIndicator = view.findViewById(R.id.reminder_loading)
        contentContainer = view.findViewById(R.id.reminder_content)
        goalTitleText = view.findViewById(R.id.reminder_goal_title)
        groupNameText = view.findViewById(R.id.reminder_group_name)
        enableSwitch = view.findViewById(R.id.reminder_enable_switch)
        modeSmart = view.findViewById(R.id.reminder_mode_smart)
        modeFixed = view.findViewById(R.id.reminder_mode_fixed)
        modeOff = view.findViewById(R.id.reminder_mode_off)
        smartCard = view.findViewById(R.id.reminder_smart_card)
        patternText = view.findViewById(R.id.reminder_pattern_text)
        patternLogsText = view.findViewById(R.id.reminder_pattern_logs_text)
        recalculateButton = view.findViewById(R.id.reminder_recalculate_button)
        fixedCard = view.findViewById(R.id.reminder_fixed_card)
        fixedTimeButton = view.findViewById(R.id.reminder_fixed_time_button)
        levelGentle = view.findViewById(R.id.reminder_level_gentle)
        levelBalanced = view.findViewById(R.id.reminder_level_balanced)
        levelPersistent = view.findViewById(R.id.reminder_level_persistent)
        quietFromButton = view.findViewById(R.id.reminder_quiet_from_button)
        quietToButton = view.findViewById(R.id.reminder_quiet_to_button)
        saveButton = view.findViewById(R.id.reminder_save_button)

        goalTitleText.text = getString(R.string.reminder_settings_goal, goalTitle)
        groupNameText.text = getString(R.string.reminder_settings_group, groupName)

        recalculateButton.setOnClickListener { doRecalculate() }
        fixedTimeButton.setOnClickListener { showFixedTimePicker() }
        quietFromButton.setOnClickListener { showQuietFromPicker() }
        quietToButton.setOnClickListener { showQuietToPicker() }
        saveButton.setOnClickListener { doSave() }

        view.findViewById<android.widget.RadioGroup>(R.id.reminder_mode_group)
            .setOnCheckedChangeListener { _, _ -> updateModeVisibility() }

        loadPreferences()
    }

    private fun loadPreferences() {
        loadingIndicator.visibility = View.VISIBLE
        contentContainer.visibility = View.GONE

        lifecycleScope.launch {
            try {
                val token = SecureTokenManager.getInstance(requireContext()).getAccessToken()
                    ?: run {
                        Toast.makeText(requireContext(), R.string.reminder_load_failed, Toast.LENGTH_SHORT).show()
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                        return@launch
                    }
                val response = withContext(Dispatchers.IO) {
                    ApiClient.getGoalReminderPreferences(token, goalId)
                }
                prefs = response
                fixedHour = response.fixed_hour ?: 21
                quietHoursStart = response.quiet_hours_start
                quietHoursEnd = response.quiet_hours_end
                withContext(Dispatchers.Main) {
                    bindPrefsToUi(response)
                    loadingIndicator.visibility = View.GONE
                    contentContainer.visibility = View.VISIBLE
                }
            } catch (e: ApiException) {
                withContext(Dispatchers.Main) {
                    loadingIndicator.visibility = View.GONE
                    contentContainer.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), getString(R.string.reminder_load_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadingIndicator.visibility = View.GONE
                    contentContainer.visibility = View.VISIBLE
                    Toast.makeText(requireContext(), getString(R.string.reminder_load_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun bindPrefsToUi(p: GoalReminderPreferencesResponse) {
        enableSwitch.isChecked = p.enabled
        when (p.mode) {
            "smart" -> modeSmart.isChecked = true
            "fixed" -> modeFixed.isChecked = true
            else -> modeOff.isChecked = true
        }
        when (p.aggressiveness) {
            "gentle" -> levelGentle.isChecked = true
            "persistent" -> levelPersistent.isChecked = true
            else -> levelBalanced.isChecked = true
        }
        fixedHour = p.fixed_hour ?: 21
        fixedTimeButton.text = formatHour(fixedHour)
        quietHoursStart = p.quiet_hours_start
        quietHoursEnd = p.quiet_hours_end
        quietFromButton.text = if (p.quiet_hours_start != null) formatHour(p.quiet_hours_start) else getString(R.string.reminder_quiet_from)
        quietToButton.text = if (p.quiet_hours_end != null) formatHour(p.quiet_hours_end) else getString(R.string.reminder_quiet_to)

        p.pattern?.let { bindPattern(it.typical_hour_start, it.typical_hour_end, it.confidence_score, it.sample_size) }
            ?: run {
                patternText.visibility = View.GONE
                patternLogsText.visibility = View.GONE
            }
        updateModeVisibility()
    }

    private fun bindPattern(typicalHourStart: Int, typicalHourEnd: Int, confidenceScore: Double, sampleSize: Int) {
        patternText.text = getString(R.string.reminder_pattern_time_range, formatHour(typicalHourStart), formatHour(typicalHourEnd))
        patternText.visibility = View.VISIBLE
        val confidence = when {
            confidenceScore >= 0.7 -> getString(R.string.reminder_confidence_high)
            confidenceScore >= 0.4 -> getString(R.string.reminder_confidence_medium)
            else -> getString(R.string.reminder_confidence_low)
        }
        patternLogsText.text = getString(R.string.reminder_pattern_based_on_logs, sampleSize, confidence)
        patternLogsText.visibility = View.VISIBLE
    }

    private fun updateModeVisibility() {
        val isSmart = modeSmart.isChecked
        val isFixed = modeFixed.isChecked
        smartCard.visibility = if (isSmart) View.VISIBLE else View.GONE
        fixedCard.visibility = if (isFixed) View.VISIBLE else View.GONE
    }

    private fun formatHour(hour: Int): String {
        val c = Calendar.getInstance(Locale.getDefault())
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, 0)
        return java.text.SimpleDateFormat("h:mm a", Locale.getDefault()).format(c.time)
    }

    private fun showFixedTimePicker() {
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(fixedHour.coerceIn(0, 23))
            .setMinute(0)
            .build()
        picker.addOnPositiveButtonClickListener {
            fixedHour = picker.hour
            fixedTimeButton.text = formatHour(fixedHour)
        }
        picker.show(parentFragmentManager, "fixed_time")
    }

    private fun showQuietFromPicker() {
        val current = quietHoursStart ?: 22
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(current.coerceIn(0, 23))
            .setMinute(0)
            .build()
        picker.addOnPositiveButtonClickListener {
            quietHoursStart = picker.hour
            quietFromButton.text = formatHour(picker.hour)
            if (quietHoursEnd == null) {
                quietHoursEnd = (picker.hour + 9).rem(24)
                quietToButton.text = formatHour(quietHoursEnd!!)
            }
        }
        picker.show(parentFragmentManager, "quiet_from")
    }

    private fun showQuietToPicker() {
        val current = quietHoursEnd ?: 7
        val picker = MaterialTimePicker.Builder()
            .setTimeFormat(TimeFormat.CLOCK_12H)
            .setHour(current.coerceIn(0, 23))
            .setMinute(0)
            .build()
        picker.addOnPositiveButtonClickListener {
            quietHoursEnd = picker.hour
            quietToButton.text = formatHour(picker.hour)
            if (quietHoursStart == null) {
                quietHoursStart = (picker.hour - 9 + 24).rem(24)
                quietFromButton.text = formatHour(quietHoursStart!!)
            }
        }
        picker.show(parentFragmentManager, "quiet_to")
    }

    private fun doRecalculate() {
        recalculateButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val token = SecureTokenManager.getInstance(requireContext()).getAccessToken() ?: return@launch
                val timezone = TimeZone.getDefault().id
                val response = withContext(Dispatchers.IO) {
                    ApiClient.recalculateGoalPattern(token, goalId, timezone)
                }
                withContext(Dispatchers.Main) {
                    recalculateButton.isEnabled = true
                    if (response.pattern != null) {
                        val p = response.pattern!!
                        bindPattern(p.typical_hour_start, p.typical_hour_end, p.confidence_score, p.sample_size)
                        Toast.makeText(requireContext(), R.string.reminder_recalculate_success, Toast.LENGTH_SHORT).show()
                    } else {
                        val msg = response.message ?: getString(R.string.reminder_recalculate_insufficient)
                        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    recalculateButton.isEnabled = true
                    Toast.makeText(requireContext(), R.string.reminder_recalculate_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun doSave() {
        val mode = when {
            modeSmart.isChecked -> "smart"
            modeFixed.isChecked -> "fixed"
            else -> "disabled"
        }
        val quietStart = quietHoursStart
        val quietEnd = quietHoursEnd
        if ((quietStart != null) != (quietEnd != null)) {
            Toast.makeText(requireContext(), getString(R.string.reminder_save_failed), Toast.LENGTH_SHORT).show()
            return
        }

        saveButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val token = SecureTokenManager.getInstance(requireContext()).getAccessToken() ?: return@launch
                withContext(Dispatchers.IO) {
                    ApiClient.updateGoalReminderPreferences(
                        accessToken = token,
                        goalId = goalId,
                        enabled = enableSwitch.isChecked,
                        mode = mode,
                        fixedHour = if (mode == "fixed") fixedHour else null,
                        aggressiveness = when {
                            levelGentle.isChecked -> "gentle"
                            levelPersistent.isChecked -> "persistent"
                            else -> "balanced"
                        },
                        quietHoursStart = quietStart,
                        quietHoursEnd = quietEnd
                    )
                }
                withContext(Dispatchers.Main) {
                    saveButton.isEnabled = true
                    Toast.makeText(requireContext(), R.string.reminder_saved, Toast.LENGTH_SHORT).show()
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    saveButton.isEnabled = true
                    Toast.makeText(requireContext(), R.string.reminder_save_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
