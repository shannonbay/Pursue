package app.getpursue.utils

import app.getpursue.models.Group
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayGoalsFilterUtilsTest {

    @Test
    fun `includes non challenge groups`() {
        val group = Group(
            id = "g1",
            name = "Regular Group",
            description = null,
            icon_emoji = "📁",
            has_icon = false,
            member_count = 3,
            role = "member",
            joined_at = "2026-02-01T00:00:00Z",
            updated_at = null,
            is_challenge = false
        )
        assertTrue(TodayGoalsFilterUtils.shouldIncludeGroup(group))
    }

    @Test
    fun `includes active challenges only`() {
        val active = baseChallenge("active")
        val upcoming = baseChallenge("upcoming")
        val completed = baseChallenge("completed")
        val cancelled = baseChallenge("cancelled")
        val unknown = baseChallenge(null)

        assertTrue(TodayGoalsFilterUtils.shouldIncludeGroup(active))
        assertFalse(TodayGoalsFilterUtils.shouldIncludeGroup(upcoming))
        assertFalse(TodayGoalsFilterUtils.shouldIncludeGroup(completed))
        assertFalse(TodayGoalsFilterUtils.shouldIncludeGroup(cancelled))
        assertFalse(TodayGoalsFilterUtils.shouldIncludeGroup(unknown))
    }

    private fun baseChallenge(status: String?): Group {
        return Group(
            id = "c1",
            name = "Challenge",
            description = null,
            icon_emoji = "🏆",
            has_icon = false,
            member_count = 5,
            role = "member",
            joined_at = "2026-02-01T00:00:00Z",
            updated_at = null,
            is_challenge = true,
            challenge_start_date = "2026-02-20",
            challenge_end_date = "2026-03-20",
            challenge_status = status
        )
    }
}

