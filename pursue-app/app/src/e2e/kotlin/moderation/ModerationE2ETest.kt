package com.github.shannonbay.pursue.e2e.moderation

import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID

/**
 * E2E tests for content moderation features.
 *
 * Covers (no OPENAI_API_KEY required):
 * - Reserved name rejection on register and group create
 * - Report submission (201 + id, 409 duplicate, 403 non-member)
 * - Auto-hide after threshold reports (entry returns 404 for non-authors)
 * - Author can still fetch their own auto-hidden entry
 * - Dispute submission (201 + id, 403 on other user's content)
 */
class ModerationE2ETest : E2ETest() {

    // -------------------------------------------------------------------------
    // Reserved name tests
    // -------------------------------------------------------------------------

    @Test
    fun `register with reserved display name is rejected`() = runTest {
        var exception: ApiException? = null
        try {
            // "pursue" is an exact-match reserved display name
            ApiClient.register(
                displayName = "pursue",
                email = "test-${UUID.randomUUID()}@example.com",
                password = "TestPass123!",
                consentAgreed = true
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
        assertThat(exception.errorCode).isEqualTo("NAME_NOT_AVAILABLE")
    }

    @Test
    fun `create group with reserved brand name is rejected`() = runTest {
        val (token, _, _) = getOrCreateSharedUser()

        var exception: ApiException? = null
        try {
            // "Pursue Official" contains the reserved brand term "pursue"
            api.createGroup(token, "Pursue Official")
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(400)
        assertThat(exception.errorCode).isEqualTo("NAME_NOT_AVAILABLE")
    }

    // -------------------------------------------------------------------------
    // Reporting tests
    // -------------------------------------------------------------------------

    @Test
    fun `reporting a progress entry returns 201 with id`() = runTest {
        // Setup: shared user creates group + goal + progress entry
        val author = getOrCreateSharedUser()
        val group = api.createGroup(author.access_token, "Mod Report Test ${UUID.randomUUID()}")
        trackGroup(group.id)

        val goal = testDataHelper.createTestGoal(api, author.access_token, group.id)

        val entry = api.logProgress(
            accessToken = author.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        // A second user joins the group and reports the entry
        val reporter = testDataHelper.createTestUser(api, displayName = "Reporter")
        trackUser(reporter.user!!.id)

        val invite = api.getGroupInviteCode(author.access_token, group.id)
        val joinResponse = api.joinGroup(reporter.access_token, invite.invite_code)
        // If pending, approve
        if (joinResponse.status == "pending") {
            api.approveMember(author.access_token, group.id, reporter.user.id)
        }

        val reportResponse = api.reportContent(
            accessToken = reporter.access_token,
            contentType = "progress_entry",
            contentId = entry.id,
            reason = "Inappropriate content"
        )

        assertThat(reportResponse.id).isNotEmpty()
    }

    @Test
    fun `duplicate report from same user returns 409`() = runTest {
        // Setup: shared user creates group + goal + entry; reporter reports twice
        val author = getOrCreateSharedUser()
        val group = api.createGroup(author.access_token, "Dup Report Test ${UUID.randomUUID()}")
        trackGroup(group.id)

        val goal = testDataHelper.createTestGoal(api, author.access_token, group.id)
        val entry = api.logProgress(
            accessToken = author.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        val reporter = testDataHelper.createTestUser(api, displayName = "DupReporter")
        trackUser(reporter.user!!.id)

        val invite = api.getGroupInviteCode(author.access_token, group.id)
        val joinResponse = api.joinGroup(reporter.access_token, invite.invite_code)
        if (joinResponse.status == "pending") {
            api.approveMember(author.access_token, group.id, reporter.user.id)
        }

        // First report succeeds
        api.reportContent(reporter.access_token, "progress_entry", entry.id, "reason one")

        // Second report from same user should fail
        var exception: ApiException? = null
        try {
            api.reportContent(reporter.access_token, "progress_entry", entry.id, "reason two")
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("ALREADY_REPORTED")
    }

    @Test
    fun `report by non-member returns 403`() = runTest {
        // Setup: shared user creates group + entry; outsider tries to report
        val author = getOrCreateSharedUser()
        val group = api.createGroup(author.access_token, "NonMember Report Test ${UUID.randomUUID()}")
        trackGroup(group.id)

        val goal = testDataHelper.createTestGoal(api, author.access_token, group.id)
        val entry = api.logProgress(
            accessToken = author.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        // Non-member — not in the group at all
        val outsider = testDataHelper.createTestUser(api, displayName = "Outsider")
        trackUser(outsider.user!!.id)

        var exception: ApiException? = null
        try {
            api.reportContent(outsider.access_token, "progress_entry", entry.id, "reason")
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }

    // -------------------------------------------------------------------------
    // Auto-hide tests (threshold for ≤10 members = 2 reports)
    // -------------------------------------------------------------------------

    @Test
    fun `progress entry is auto-hidden after threshold reports`() = runTest {
        // Setup: author creates group + goal + entry; two other members report it
        val author = getOrCreateSharedUser()
        val group = api.createGroup(author.access_token, "AutoHide Test ${UUID.randomUUID()}")
        trackGroup(group.id)

        val goal = testDataHelper.createTestGoal(api, author.access_token, group.id)
        val entry = api.logProgress(
            accessToken = author.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        val invite = api.getGroupInviteCode(author.access_token, group.id)

        // User B
        val userB = testDataHelper.createTestUser(api, displayName = "Reporter B")
        trackUser(userB.user!!.id)
        val joinB = api.joinGroup(userB.access_token, invite.invite_code)
        if (joinB.status == "pending") {
            api.approveMember(author.access_token, group.id, userB.user.id)
        }

        // User C
        val userC = testDataHelper.createTestUser(api, displayName = "Reporter C")
        trackUser(userC.user!!.id)
        val joinC = api.joinGroup(userC.access_token, invite.invite_code)
        if (joinC.status == "pending") {
            api.approveMember(author.access_token, group.id, userC.user.id)
        }

        // Both report — threshold (2) is now reached; entry should be auto-hidden
        api.reportContent(userB.access_token, "progress_entry", entry.id, "inappropriate")
        api.reportContent(userC.access_token, "progress_entry", entry.id, "inappropriate")

        // Non-author trying to fetch the hidden entry should get 404
        var exception: ApiException? = null
        try {
            api.getProgressEntry(userB.access_token, entry.id)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    @Test
    fun `author can still fetch their own auto-hidden entry`() = runTest {
        // Same auto-hide setup — but author fetches their own entry → 200
        val author = getOrCreateSharedUser()
        val group = api.createGroup(author.access_token, "AutoHide Author Test ${UUID.randomUUID()}")
        trackGroup(group.id)

        val goal = testDataHelper.createTestGoal(api, author.access_token, group.id)
        val entry = api.logProgress(
            accessToken = author.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        val invite = api.getGroupInviteCode(author.access_token, group.id)

        val userB = testDataHelper.createTestUser(api, displayName = "Reporter B2")
        trackUser(userB.user!!.id)
        val joinB = api.joinGroup(userB.access_token, invite.invite_code)
        if (joinB.status == "pending") {
            api.approveMember(author.access_token, group.id, userB.user.id)
        }

        val userC = testDataHelper.createTestUser(api, displayName = "Reporter C2")
        trackUser(userC.user!!.id)
        val joinC = api.joinGroup(userC.access_token, invite.invite_code)
        if (joinC.status == "pending") {
            api.approveMember(author.access_token, group.id, userC.user.id)
        }

        api.reportContent(userB.access_token, "progress_entry", entry.id, "inappropriate")
        api.reportContent(userC.access_token, "progress_entry", entry.id, "inappropriate")

        // Author can still see their own hidden entry
        val fetchedEntry = api.getProgressEntry(author.access_token, entry.id)
        assertThat(fetchedEntry.id).isEqualTo(entry.id)
    }

    // -------------------------------------------------------------------------
    // Dispute tests
    // -------------------------------------------------------------------------

    @Test
    fun `disputing own content returns 201 with id`() = runTest {
        // The dispute endpoint checks ownership only — no hidden status required
        val author = getOrCreateSharedUser()
        val group = api.createGroup(author.access_token, "Dispute Test ${UUID.randomUUID()}")
        trackGroup(group.id)

        val goal = testDataHelper.createTestGoal(api, author.access_token, group.id)
        val entry = api.logProgress(
            accessToken = author.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        val disputeResponse = api.createDispute(
            accessToken = author.access_token,
            contentType = "progress_entry",
            contentId = entry.id,
            userExplanation = "This is legitimate content"
        )

        assertThat(disputeResponse.id).isNotEmpty()
    }

    @Test
    fun `disputing another user's content returns 403`() = runTest {
        // Setup: author has a progress entry; a different user tries to dispute it
        val author = getOrCreateSharedUser()
        val group = api.createGroup(author.access_token, "Dispute 403 Test ${UUID.randomUUID()}")
        trackGroup(group.id)

        val goal = testDataHelper.createTestGoal(api, author.access_token, group.id)
        val entry = api.logProgress(
            accessToken = author.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = "2026-02-01",
            userTimezone = "America/New_York"
        )

        val invite = api.getGroupInviteCode(author.access_token, group.id)

        val otherUser = testDataHelper.createTestUser(api, displayName = "OtherDisputer")
        trackUser(otherUser.user!!.id)
        val join = api.joinGroup(otherUser.access_token, invite.invite_code)
        if (join.status == "pending") {
            api.approveMember(author.access_token, group.id, otherUser.user.id)
        }

        var exception: ApiException? = null
        try {
            api.createDispute(
                accessToken = otherUser.access_token,
                contentType = "progress_entry",
                contentId = entry.id,
                userExplanation = "Not mine to dispute"
            )
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }
}
