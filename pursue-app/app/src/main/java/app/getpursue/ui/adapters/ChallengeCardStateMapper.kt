package app.getpursue.ui.adapters

import app.getpursue.models.Group
import app.getpursue.utils.ChallengeDateUiUtils
import java.time.LocalDate
import java.time.LocalDateTime

data class ChallengeCardUiState(
    val statusText: String,
    val dayLabel: String,
    val daysRemainingLabel: String,
    val progressPercent: Int
)

object ChallengeCardStateMapper {
    fun map(
        group: Group,
        today: LocalDate = LocalDate.now(),
        now: LocalDateTime = LocalDateTime.now()
    ): ChallengeCardUiState? {
        if (!group.is_challenge) return null
        val start = group.challenge_start_date?.let { parseDate(it) } ?: return null
        val end = group.challenge_end_date?.let { parseDate(it) } ?: return null
        val status = ChallengeDateUiUtils.effectiveStatus(
            startDate = start,
            endDate = end,
            serverStatus = group.challenge_status,
            today = today
        )
        val progress = ChallengeDateUiUtils.computeDayProgress(
            startDate = start,
            endDate = end,
            status = status,
            today = today,
            now = now
        )
        return ChallengeCardUiState(
            statusText = status.replaceFirstChar { it.uppercase() },
            dayLabel = progress.dayLabel,
            daysRemainingLabel = progress.daysRemainingLabel,
            progressPercent = progress.progressPercent
        )
    }

    private fun parseDate(value: String): LocalDate {
        return try {
            LocalDate.parse(value.take(10))
        } catch (_: Exception) {
            LocalDate.now()
        }
    }
}
