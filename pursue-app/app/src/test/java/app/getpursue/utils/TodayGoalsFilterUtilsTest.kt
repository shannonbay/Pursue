package app.getpursue.utils

import app.getpursue.models.Group
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TodayGoalsFilterUtilsTest {

    @Test
    fun `includes non challenge groups`() {
        val group = Group(
            id = "g1",
            name = "Regular Group",
            description = null,
            icon_emoji = null,
            has_icon = false,
            member_count = 3,
            role = "member",
            joined_at = "2026-02-01T00:00:00Z",
            updated_at = null,
            is_challenge = false
        )
        assertTrue(TodayGoalsFilterUtils.shouldIncludeGroup(group, LocalDate.of(2026, 2, 10)))
    }

    @Test
    fun `includes challenge only when effective local status is active`() {
        val today = LocalDate.of(2026, 2, 10)
        val upcoming = baseChallenge(start = "2026-02-11", end = "2026-03-10", status = "active")
        val active = baseChallenge(start = "2026-02-01", end = "2026-03-10", status = "upcoming")
        val completed = baseChallenge(start = "2026-01-01", end = "2026-02-09", status = "active")
        val cancelled = baseChallenge(start = "2026-02-01", end = "2026-03-10", status = "cancelled")

        assertFalse(TodayGoalsFilterUtils.shouldIncludeGroup(upcoming, today))
        assertTrue(TodayGoalsFilterUtils.shouldIncludeGroup(active, today))
        assertFalse(TodayGoalsFilterUtils.shouldIncludeGroup(completed, today))
        assertFalse(TodayGoalsFilterUtils.shouldIncludeGroup(cancelled, today))
    }

    private fun baseChallenge(start: String, end: String, status: String?): Group {
        return Group(
            id = "c1",
            name = "Challenge",
            description = null,
            icon_emoji = null,
            has_icon = false,
            member_count = 5,
            role = "member",
            joined_at = "2026-02-01T00:00:00Z",
            updated_at = null,
            is_challenge = true,
            challenge_start_date = start,
            challenge_end_date = end,
            challenge_status = status
        )
    }
}
