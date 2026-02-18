package app.getpursue.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ChallengeDateUiUtilsTest {

    @Test
    fun `active challenge computes elapsed and remaining`() {
        val result = ChallengeDateUiUtils.computeDayProgress(
            startDate = LocalDate.of(2026, 2, 1),
            endDate = LocalDate.of(2026, 2, 10),
            status = "active",
            today = LocalDate.of(2026, 2, 5)
        )
        assertThat(result.dayLabel).isEqualTo("Day 5/10")
        assertThat(result.daysRemainingLabel).isEqualTo("6 days left")
        assertThat(result.progressPercent).isEqualTo(50)
    }

    @Test
    fun `upcoming challenge stays at day zero`() {
        val result = ChallengeDateUiUtils.computeDayProgress(
            startDate = LocalDate.of(2026, 3, 1),
            endDate = LocalDate.of(2026, 3, 30),
            status = "upcoming",
            today = LocalDate.of(2026, 2, 20),
            now = LocalDateTime.of(2026, 2, 20, 12, 0)
        )
        assertThat(result.dayLabel).isEqualTo("Day 0/30")
        assertThat(result.progressPercent).isEqualTo(0)
    }

    @Test
    fun `upcoming challenge under 24h shows hours rounded up`() {
        val result = ChallengeDateUiUtils.computeDayProgress(
            startDate = LocalDate.of(2026, 3, 1),
            endDate = LocalDate.of(2026, 3, 30),
            status = "upcoming",
            today = LocalDate.of(2026, 2, 28),
            now = LocalDateTime.of(2026, 2, 28, 18, 10)
        )

        assertThat(result.daysRemainingLabel).isEqualTo("Starts in 6 hours")
    }

    @Test
    fun `upcoming challenge with minutes remaining rounds to 1 hour`() {
        val result = ChallengeDateUiUtils.computeDayProgress(
            startDate = LocalDate.of(2026, 3, 1),
            endDate = LocalDate.of(2026, 3, 30),
            status = "upcoming",
            today = LocalDate.of(2026, 2, 28),
            now = LocalDateTime.of(2026, 2, 28, 23, 40)
        )

        assertThat(result.daysRemainingLabel).isEqualTo("Starts in 1 hour")
    }

    @Test
    fun `upcoming challenge at 24h remains day countdown`() {
        val result = ChallengeDateUiUtils.computeDayProgress(
            startDate = LocalDate.of(2026, 3, 1),
            endDate = LocalDate.of(2026, 3, 30),
            status = "upcoming",
            today = LocalDate.of(2026, 2, 28),
            now = LocalDateTime.of(2026, 2, 28, 0, 0)
        )

        assertThat(result.daysRemainingLabel).isEqualTo("Starts in 1 day")
    }

    @Test
    fun `completed challenge reports full progress`() {
        val result = ChallengeDateUiUtils.computeDayProgress(
            startDate = LocalDate.of(2026, 1, 1),
            endDate = LocalDate.of(2026, 1, 7),
            status = "completed",
            today = LocalDate.of(2026, 1, 10)
        )
        assertThat(result.dayLabel).isEqualTo("Day 7/7")
        assertThat(result.daysRemainingLabel).isEqualTo("Completed")
        assertThat(result.progressPercent).isEqualTo(100)
    }

    @Test
    fun `effective status uses local date before server status`() {
        val status = ChallengeDateUiUtils.effectiveStatus(
            startDate = LocalDate.of(2026, 3, 1),
            endDate = LocalDate.of(2026, 3, 30),
            serverStatus = "active",
            today = LocalDate.of(2026, 2, 28)
        )

        assertThat(status).isEqualTo("upcoming")
    }

    @Test
    fun `effective status keeps cancelled terminal`() {
        val status = ChallengeDateUiUtils.effectiveStatus(
            startDate = LocalDate.of(2026, 3, 1),
            endDate = LocalDate.of(2026, 3, 30),
            serverStatus = "cancelled",
            today = LocalDate.of(2026, 3, 10)
        )

        assertThat(status).isEqualTo("cancelled")
    }
}
