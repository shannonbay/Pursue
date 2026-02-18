package app.getpursue.utils

import app.getpursue.models.Group
import java.time.LocalDate

object TodayGoalsFilterUtils {
    fun shouldIncludeGroup(group: Group, today: LocalDate = LocalDate.now()): Boolean {
        if (!group.is_challenge) return true
        val start = group.challenge_start_date?.let { parseDate(it) } ?: return false
        val end = group.challenge_end_date?.let { parseDate(it) } ?: return false
        val effectiveStatus = ChallengeDateUiUtils.effectiveStatus(
            startDate = start,
            endDate = end,
            serverStatus = group.challenge_status,
            today = today
        )
        return effectiveStatus == "active"
    }

    private fun parseDate(value: String): LocalDate {
        return try {
            LocalDate.parse(value.take(10))
        } catch (_: Exception) {
            LocalDate.MIN
        }
    }
}
