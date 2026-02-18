package app.getpursue.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

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
            today = LocalDate.of(2026, 2, 20)
        )
        assertThat(result.dayLabel).isEqualTo("Day 0/30")
        assertThat(result.progressPercent).isEqualTo(0)
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
}

