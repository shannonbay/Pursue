package app.getpursue.utils

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

object ChallengeDateUiUtils {

    data class DayProgress(
        val dayLabel: String,
        val daysRemainingLabel: String,
        val progressPercent: Int
    )

    fun computeDayProgress(
        startDate: LocalDate,
        endDate: LocalDate,
        status: String?,
        today: LocalDate = LocalDate.now(),
        now: LocalDateTime = LocalDateTime.now()
    ): DayProgress {
        val totalDays = totalDays(startDate, endDate)
        if (totalDays <= 0) {
            return DayProgress("Day 0/0", "0 days remaining", 0)
        }

        return when (status) {
            "upcoming" -> {
                val daysUntil = ChronoUnit.DAYS.between(today, startDate).coerceAtLeast(0)
                val hoursUntilStart = if (daysUntil == 1L) {
                    val startAt = startDate.atStartOfDay()
                    val minutesUntil = ChronoUnit.MINUTES.between(now, startAt)
                    if (minutesUntil in 1..1439) ((minutesUntil + 59) / 60).toInt() else null
                } else {
                    null
                }
                DayProgress(
                    dayLabel = "Day 0/$totalDays",
                    daysRemainingLabel = when {
                        hoursUntilStart != null -> "Starts in $hoursUntilStart hour${if (hoursUntilStart == 1) "" else "s"}"
                        daysUntil == 0L -> "Starts today"
                        else -> "Starts in $daysUntil day${if (daysUntil == 1L) "" else "s"}"
                    },
                    progressPercent = 0
                )
            }
            "completed" -> {
                DayProgress(
                    dayLabel = "Day $totalDays/$totalDays",
                    daysRemainingLabel = "Completed",
                    progressPercent = 100
                )
            }
            "cancelled" -> {
                val day = currentDay(startDate, endDate, today).coerceAtLeast(0)
                val percent = ((day.toDouble() / totalDays.toDouble()) * 100).toInt().coerceIn(0, 100)
                DayProgress(
                    dayLabel = "Day $day/$totalDays",
                    daysRemainingLabel = "Cancelled",
                    progressPercent = percent
                )
            }
            else -> {
                val day = currentDay(startDate, endDate, today).coerceAtLeast(1)
                val remaining = ChronoUnit.DAYS.between(today, endDate).coerceAtLeast(0) + 1
                val percent = ((day.toDouble() / totalDays.toDouble()) * 100).toInt().coerceIn(0, 100)
                DayProgress(
                    dayLabel = "Day $day/$totalDays",
                    daysRemainingLabel = "$remaining day${if (remaining == 1L) "" else "s"} left",
                    progressPercent = percent
                )
            }
        }
    }

    fun totalDays(startDate: LocalDate, endDate: LocalDate): Int {
        return (ChronoUnit.DAYS.between(startDate, endDate) + 1).toInt()
    }

    fun effectiveStatus(
        startDate: LocalDate,
        endDate: LocalDate,
        serverStatus: String?,
        today: LocalDate = LocalDate.now()
    ): String {
        if (serverStatus == "cancelled") return "cancelled"
        if (today.isBefore(startDate)) return "upcoming"
        if (today.isAfter(endDate)) return "completed"
        return "active"
    }

    private fun currentDay(startDate: LocalDate, endDate: LocalDate, today: LocalDate): Int {
        if (today.isBefore(startDate)) return 0
        if (today.isAfter(endDate)) return totalDays(startDate, endDate)
        return (ChronoUnit.DAYS.between(startDate, today) + 1).toInt()
    }
}
