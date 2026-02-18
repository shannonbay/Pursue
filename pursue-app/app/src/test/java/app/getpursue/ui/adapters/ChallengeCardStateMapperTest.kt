package app.getpursue.ui.adapters

import app.getpursue.models.Group
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ChallengeCardStateMapperTest {

    @Test
    fun `returns null for non challenge groups`() {
        val group = Group(
            id = "g1",
            name = "Regular",
            description = null,
            icon_emoji = null,
            has_icon = false,
            member_count = 1,
            role = "member",
            joined_at = "2026-02-01T00:00:00Z",
            updated_at = null
        )
        assertThat(ChallengeCardStateMapper.map(group)).isNull()
    }

    @Test
    fun `maps active challenge state`() {
        val group = Group(
            id = "g2",
            name = "Challenge",
            description = null,
            icon_emoji = "🔥",
            has_icon = false,
            member_count = 3,
            role = "creator",
            joined_at = "2026-02-01T00:00:00Z",
            updated_at = null,
            is_challenge = true,
            challenge_start_date = "2026-02-01",
            challenge_end_date = "2026-02-10",
            challenge_status = "active",
            challenge_template_id = "t1"
        )
        val state = ChallengeCardStateMapper.map(group, LocalDate.of(2026, 2, 3))
        assertThat(state).isNotNull()
        assertThat(state!!.statusText).isEqualTo("Active")
        assertThat(state.dayLabel).isEqualTo("Day 3/10")
        assertThat(state.progressPercent).isEqualTo(30)
    }

    @Test
    fun `maps upcoming from dates even when server status says active`() {
        val group = Group(
            id = "g3",
            name = "Challenge",
            description = null,
            icon_emoji = null,
            has_icon = false,
            member_count = 3,
            role = "member",
            joined_at = "2026-02-01T00:00:00Z",
            updated_at = null,
            is_challenge = true,
            challenge_start_date = "2026-02-10",
            challenge_end_date = "2026-02-20",
            challenge_status = "active"
        )

        val state = ChallengeCardStateMapper.map(group, LocalDate.of(2026, 2, 9))
        assertThat(state).isNotNull()
        assertThat(state!!.statusText).isEqualTo("Upcoming")
        assertThat(state.dayLabel).isEqualTo("Day 0/11")
    }

    @Test
    fun `maps upcoming under 24h with hour countdown`() {
        val group = Group(
            id = "g4",
            name = "Challenge",
            description = null,
            icon_emoji = null,
            has_icon = false,
            member_count = 3,
            role = "member",
            joined_at = "2026-02-01T00:00:00Z",
            updated_at = null,
            is_challenge = true,
            challenge_start_date = "2026-03-01",
            challenge_end_date = "2026-03-20",
            challenge_status = "upcoming"
        )

        val state = ChallengeCardStateMapper.map(
            group = group,
            today = LocalDate.of(2026, 2, 28),
            now = LocalDateTime.of(2026, 2, 28, 22, 10)
        )

        assertThat(state).isNotNull()
        assertThat(state!!.daysRemainingLabel).isEqualTo("Starts in 2 hours")
    }
}
