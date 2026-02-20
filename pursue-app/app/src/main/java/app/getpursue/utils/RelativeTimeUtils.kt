package app.getpursue.utils

import android.content.Context
import app.getpursue.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Utility for parsing ISO 8601 timestamps (ending with Z) and formatting them as relative time
 * (e.g. "Just now", "2 hours ago", "Yesterday", "Last week").
 */
object RelativeTimeUtils {

    /**
     * Parse an ISO 8601 timestamp (with or without milliseconds) as UTC.
     * Tries yyyy-MM-dd'T'HH:mm:ss'Z' then yyyy-MM-dd'T'HH:mm:ss.SSS'Z'.
     */
    fun parseIsoTimestamp(isoTimestamp: String): Date? {
        val utc = TimeZone.getTimeZone("UTC")
        val withoutMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = utc
        }
        val withMs = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = utc
        }
        return try {
            withoutMs.parse(isoTimestamp)
        } catch (e: Exception) {
            try {
                withMs.parse(isoTimestamp)
            } catch (e: Exception) {
                null
            }
        }
    }

    /**
     * Format an ISO 8601 timestamp as relative time using the full ladder:
     * Just now, x minutes/hours ago, Yesterday, x days ago, Last week, x weeks ago,
     * Last month, x months ago, Last year, x years ago.
     * On parse failure returns the original string.
     */
    fun formatRelativeTime(context: Context, isoTimestamp: String): String {
        return try {
            val timestamp = parseIsoTimestamp(isoTimestamp) ?: return isoTimestamp
            val now = Date()
            val diffMs = now.time - timestamp.time
            val diffMinutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
            val diffHours = TimeUnit.MILLISECONDS.toHours(diffMs)
            val diffDays = TimeUnit.MILLISECONDS.toDays(diffMs)

            when {
                diffMs < 60_000 -> context.getString(R.string.just_now)
                diffMinutes < 60 -> if (diffMinutes == 1L) context.getString(R.string.minute_ago)
                    else context.getString(R.string.minutes_ago, diffMinutes.toInt())
                diffHours < 24 -> if (diffHours == 1L) context.getString(R.string.hour_ago)
                    else context.getString(R.string.hours_ago, diffHours.toInt())
                diffDays == 1L -> context.getString(R.string.yesterday)
                diffDays in 2..6 -> context.getString(R.string.days_ago, diffDays.toInt())
                diffDays in 7..13 -> context.getString(R.string.last_week)
                diffDays in 14..20 -> context.getString(R.string.weeks_ago, 2)
                diffDays in 21..29 -> context.getString(R.string.weeks_ago, (diffDays / 7).toInt())
                diffDays in 30..59 -> context.getString(R.string.last_month)
                diffDays in 60..364 -> context.getString(R.string.months_ago, (diffDays / 30).toInt())
                diffDays in 365..729 -> context.getString(R.string.last_year)
                else -> context.getString(R.string.years_ago, (diffDays / 365).toInt())
            }
        } catch (e: Exception) {
            isoTimestamp
        }
    }
}
