package app.getpursue.models

import androidx.annotation.StringRes
import app.getpursue.R
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

/**
 * Timeframe presets for member detail screen.
 * 
 * Free users can select timeframes up to 30 days.
 * Premium users can access extended timeframes (3, 6, 12 months).
 */
enum class Timeframe(
    @StringRes val labelRes: Int,
    val isPremium: Boolean
) {
    THIS_WEEK(R.string.timeframe_this_week, false),
    LAST_7_DAYS(R.string.timeframe_last_7_days, false),
    THIS_MONTH(R.string.timeframe_this_month, false),
    LAST_30_DAYS(R.string.timeframe_last_30_days, false),
    LAST_3_MONTHS(R.string.timeframe_last_3_months, true),
    LAST_6_MONTHS(R.string.timeframe_last_6_months, true),
    LAST_12_MONTHS(R.string.timeframe_last_12_months, true);

    /**
     * Returns the date range (start, end) for this timeframe.
     * End date is always today.
     */
    fun getDateRange(): Pair<LocalDate, LocalDate> {
        val today = LocalDate.now()
        val start = when (this) {
            THIS_WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            LAST_7_DAYS -> today.minusDays(6)
            THIS_MONTH -> today.withDayOfMonth(1)
            LAST_30_DAYS -> today.minusDays(29)
            LAST_3_MONTHS -> today.minusDays(89)
            LAST_6_MONTHS -> today.minusDays(179)
            LAST_12_MONTHS -> today.minusDays(364)
        }
        return start to today
    }

    /**
     * Returns the start date as a formatted string (YYYY-MM-DD).
     */
    fun getStartDateString(): String {
        val (start, _) = getDateRange()
        return start.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    /**
     * Returns the end date as a formatted string (YYYY-MM-DD).
     */
    fun getEndDateString(): String {
        val (_, end) = getDateRange()
        return end.format(DateTimeFormatter.ISO_LOCAL_DATE)
    }

    companion object {
        /** Default timeframe selection */
        val DEFAULT = LAST_7_DAYS
    }
}
