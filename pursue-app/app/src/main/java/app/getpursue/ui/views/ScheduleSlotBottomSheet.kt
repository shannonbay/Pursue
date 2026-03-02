package app.getpursue.ui.views

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.receivers.SlotAlarmReceiver
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.CalendarConstraints
import com.google.android.material.datepicker.DateValidatorPointForward
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Bottom sheet for scheduling a new focus session slot in a group.
 *
 * Shows a date/time picker, duration selector, and optional note field.
 * On success, creates the slot via REST and sets a 15-minute reminder alarm.
 */
class ScheduleSlotBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val TAG = "ScheduleSlotBottomSheet"
        private const val REMINDER_OFFSET_MINUTES = 15L

        fun show(fragmentManager: FragmentManager, groupId: String): ScheduleSlotBottomSheet {
            return ScheduleSlotBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_GROUP_ID, groupId) }
                show(fragmentManager, TAG)
            }
        }
    }

    /** Called when a slot is successfully posted so the parent can refresh. */
    var onSlotPosted: (() -> Unit)? = null

    private lateinit var groupId: String
    private var selectedDateTime: LocalDateTime? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        groupId = arguments?.getString(ARG_GROUP_ID) ?: ""
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.bottom_sheet_schedule_slot, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val btnPickDateTime: MaterialButton = view.findViewById(R.id.btn_pick_datetime)
        val durationChips: ChipGroup = view.findViewById(R.id.duration_chips)
        val noteInput: TextInputEditText = view.findViewById(R.id.slot_note_input)
        val errorText: TextView = view.findViewById(R.id.slot_error_text)
        val btnCancel: MaterialButton = view.findViewById(R.id.btn_cancel_slot)
        val btnPost: MaterialButton = view.findViewById(R.id.btn_post_slot)

        btnCancel.setOnClickListener { dismiss() }

        btnPickDateTime.setOnClickListener {
            showDateTimePickers(btnPickDateTime)
        }

        btnPost.setOnClickListener {
            val dt = selectedDateTime
            if (dt == null || dt.isBefore(LocalDateTime.now())) {
                errorText.text = getString(R.string.schedule_slot_error_future)
                errorText.visibility = View.VISIBLE
                return@setOnClickListener
            }
            errorText.visibility = View.GONE

            val durationMinutes = when (durationChips.checkedChipId) {
                R.id.schedule_chip_25 -> 25
                R.id.schedule_chip_45 -> 45
                R.id.schedule_chip_60 -> 60
                R.id.schedule_chip_90 -> 90
                else -> 45
            }

            val note = noteInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            postSlot(dt, durationMinutes, note, btnPost)
        }
    }

    private fun showDateTimePickers(btnPickDateTime: MaterialButton) {
        val constraints = CalendarConstraints.Builder()
            .setValidator(DateValidatorPointForward.now())
            .build()

        val datePicker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(getString(R.string.schedule_slot_date_time))
            .setCalendarConstraints(constraints)
            .build()

        datePicker.addOnPositiveButtonClickListener { selectionMs ->
            val pickedDate = Instant.ofEpochMilli(selectionMs)
                .atZone(ZoneId.systemDefault())
                .toLocalDate()

            val timePicker = MaterialTimePicker.Builder()
                .setTimeFormat(TimeFormat.CLOCK_12H)
                .setTitleText(getString(R.string.schedule_slot_date_time))
                .build()

            timePicker.addOnPositiveButtonClickListener {
                val pickedTime = LocalTime.of(timePicker.hour, timePicker.minute)
                selectedDateTime = LocalDateTime.of(pickedDate, pickedTime)

                val formatted = selectedDateTime?.format(
                    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        .withLocale(Locale.getDefault())
                ) ?: ""
                btnPickDateTime.text = formatted
            }

            timePicker.show(childFragmentManager, "time_picker")
        }

        datePicker.show(childFragmentManager, "date_picker")
    }

    private fun postSlot(
        dt: LocalDateTime,
        durationMinutes: Int,
        note: String?,
        btnPost: MaterialButton
    ) {
        btnPost.isEnabled = false
        val isoStart = dt.atZone(ZoneId.systemDefault())
            .withZoneSameInstant(ZoneOffset.UTC)
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

        scope.launch {
            val ctx = context ?: return@launch
            val token = withContext(Dispatchers.IO) {
                SecureTokenManager.getInstance(ctx).getAccessToken()
            } ?: run { btnPost.isEnabled = true; return@launch }

            try {
                val response = withContext(Dispatchers.IO) {
                    ApiClient.createFocusSlot(token, groupId, isoStart, durationMinutes, note)
                }

                // Schedule a 15-minute reminder alarm
                scheduleSlotReminder(ctx, response.slot.id, groupId, isoStart)

                Toast.makeText(ctx, getString(R.string.schedule_slot_posted), Toast.LENGTH_SHORT).show()
                onSlotPosted?.invoke()
                dismiss()
            } catch (e: ApiException) {
                Toast.makeText(ctx, e.message ?: getString(R.string.schedule_slot_failed), Toast.LENGTH_SHORT).show()
                btnPost.isEnabled = true
            }
        }
    }

    private fun scheduleSlotReminder(ctx: Context, slotId: String, groupId: String, scheduledStart: String) {
        val startInstant = try {
            OffsetDateTime.parse(scheduledStart).toInstant()
        } catch (_: Exception) { return }

        val reminderInstant = startInstant.minusSeconds(REMINDER_OFFSET_MINUTES * 60)
        val now = Instant.now()
        if (reminderInstant.isBefore(now)) return

        val intent = Intent(ctx, SlotAlarmReceiver::class.java).apply {
            action = SlotAlarmReceiver.ACTION_SLOT_REMINDER
            putExtra(SlotAlarmReceiver.EXTRA_SLOT_ID, slotId)
            putExtra(SlotAlarmReceiver.EXTRA_GROUP_ID, groupId)
            putExtra(SlotAlarmReceiver.EXTRA_SCHEDULED_START, scheduledStart)
        }

        val requestCode = slotId.hashCode()
        val pendingIntent = PendingIntent.getBroadcast(
            ctx,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = ctx.getSystemService(AlarmManager::class.java)
        val triggerAtMs = reminderInstant.toEpochMilli()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
        }
    }
}
