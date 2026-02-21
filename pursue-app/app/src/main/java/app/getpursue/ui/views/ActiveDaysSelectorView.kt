package app.getpursue.ui.views

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.util.AttributeSet
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import app.getpursue.R
import com.google.android.material.button.MaterialButton

/**
 * Active Days Selector widget for daily goals.
 *
 * Displays seven circular day-toggle buttons (Sunday-first: S M T W T F S),
 * three preset chips (Every day, Weekdays, Weekends), and a summary label.
 *
 * Day indices use Sunday-first ordering matching the server bitmask:
 *   0 = Sunday, 1 = Monday, 2 = Tuesday, 3 = Wednesday,
 *   4 = Thursday, 5 = Friday, 6 = Saturday
 *
 * Default state: all 7 days active (= "Every day").
 * [getActiveDays] returns null when all 7 are selected (server treats null as every day).
 */
class ActiveDaysSelectorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    companion object {
        private val WEEKDAYS = listOf(1, 2, 3, 4, 5)   // Mon–Fri
        private val WEEKENDS = listOf(0, 6)             // Sun, Sat
        private val ALL_DAYS = (0..6).toList()
        private val DAY_NAMES = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    }

    // Internal state: indices of currently active days
    private val activeDaySet: MutableSet<Int> = mutableSetOf(0, 1, 2, 3, 4, 5, 6)

    private val dayButtons = ArrayList<Button>(7)
    private lateinit var summaryText: TextView
    private lateinit var chipEveryDay: MaterialButton
    private lateinit var chipWeekdays: MaterialButton
    private lateinit var chipWeekends: MaterialButton

    /** Invoked whenever the selection changes. Receives null when all 7 days are active. */
    var onActiveDaysChanged: ((List<Int>?) -> Unit)? = null

    init {
        orientation = VERTICAL
        inflate(context, R.layout.view_active_days_selector, this)
        bindViews()
        setupListeners()
        refreshUI()
    }

    private fun bindViews() {
        summaryText = findViewById(R.id.active_days_summary)
        chipEveryDay = findViewById(R.id.chip_every_day)
        chipWeekdays = findViewById(R.id.chip_weekdays)
        chipWeekends = findViewById(R.id.chip_weekends)

        val dayButtonIds = listOf(
            R.id.day_btn_0, R.id.day_btn_1, R.id.day_btn_2, R.id.day_btn_3,
            R.id.day_btn_4, R.id.day_btn_5, R.id.day_btn_6
        )
        dayButtonIds.forEach { id -> dayButtons.add(findViewById(id)) }
    }

    private fun setupListeners() {
        dayButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener { onDayClicked(index) }
        }
        chipEveryDay.setOnClickListener { setActiveDays(null) }
        chipWeekdays.setOnClickListener { setActiveDays(WEEKDAYS) }
        chipWeekends.setOnClickListener { setActiveDays(WEEKENDS) }
    }

    // ─── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns the current active day selection as a sorted list (0=Sun … 6=Sat),
     * or null if all 7 days are selected (= every day, server default).
     */
    fun getActiveDays(): List<Int>? =
        if (activeDaySet.size == 7) null else activeDaySet.sorted()

    /**
     * Programmatically set the selection. Pass null to select all 7 days.
     * Does NOT fire [onActiveDaysChanged].
     */
    fun setActiveDays(days: List<Int>?) {
        activeDaySet.clear()
        activeDaySet.addAll(days ?: ALL_DAYS)
        refreshUI()
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    private fun onDayClicked(index: Int) {
        if (activeDaySet.contains(index)) {
            if (activeDaySet.size <= 1) {
                Toast.makeText(
                    context,
                    context.getString(R.string.active_days_at_least_one),
                    Toast.LENGTH_SHORT
                ).show()
                return
            }
            activeDaySet.remove(index)
        } else {
            activeDaySet.add(index)
        }
        refreshUI()
        onActiveDaysChanged?.invoke(getActiveDays())
    }

    private fun refreshUI() {
        val primaryColor = ContextCompat.getColor(context, R.color.primary)
        val onPrimaryColor = ContextCompat.getColor(context, R.color.on_primary)
        val surfaceVariantColor = ContextCompat.getColor(context, R.color.surface_variant)
        val onSurfaceVariantColor = ContextCompat.getColor(context, R.color.on_surface_variant)

        // Update day circle buttons
        dayButtons.forEachIndexed { index, btn ->
            val selected = activeDaySet.contains(index)
            btn.backgroundTintList = ColorStateList.valueOf(
                if (selected) primaryColor else surfaceVariantColor
            )
            btn.setTextColor(if (selected) onPrimaryColor else onSurfaceVariantColor)
        }

        // Update preset chip styles
        val sorted = activeDaySet.sorted()
        applyChipStyle(chipEveryDay, sorted.size == 7, primaryColor, onPrimaryColor, onSurfaceVariantColor)
        applyChipStyle(chipWeekdays, sorted == WEEKDAYS, primaryColor, onPrimaryColor, onSurfaceVariantColor)
        applyChipStyle(chipWeekends, sorted == WEEKENDS, primaryColor, onPrimaryColor, onSurfaceVariantColor)

        // Update summary label
        summaryText.text = buildSummaryLabel(sorted)
    }

    private fun applyChipStyle(
        chip: MaterialButton,
        active: Boolean,
        primaryColor: Int,
        onPrimaryColor: Int,
        onSurfaceVariantColor: Int
    ) {
        if (active) {
            chip.backgroundTintList = ColorStateList.valueOf(primaryColor)
            chip.setTextColor(onPrimaryColor)
            chip.strokeWidth = 0
        } else {
            chip.backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            chip.setTextColor(onSurfaceVariantColor)
            chip.strokeWidth = resources.getDimensionPixelSize(R.dimen.active_days_chip_stroke)
        }
    }

    private fun buildSummaryLabel(sorted: List<Int>): String {
        if (sorted.size == 7) return context.getString(R.string.active_days_summary_every_day)
        val daysPerWeek = context.getString(R.string.active_days_days_per_week)
        if (sorted == WEEKDAYS) {
            return "${context.getString(R.string.active_days_weekdays_only)} · ${sorted.size} $daysPerWeek"
        }
        if (sorted == WEEKENDS) {
            return "${context.getString(R.string.active_days_weekends_only)} · ${sorted.size} $daysPerWeek"
        }
        val names = sorted.joinToString(", ") { DAY_NAMES[it] }
        return "$names · ${sorted.size} $daysPerWeek"
    }
}
