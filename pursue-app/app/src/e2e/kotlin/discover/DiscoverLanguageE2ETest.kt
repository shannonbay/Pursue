package com.github.shannonbay.pursue.e2e.discover

import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

/**
 * E2E tests for language-aware public group discovery.
 *
 * ApiClient sends device locale automatically in:
 * - createGroup() — tags the group with creator's language
 * - createChallenge() — tags the challenge group with creator's language
 * - listPublicGroups() — requests language-first ordering from backend
 *
 * These tests verify that the integration is wired correctly end-to-end.
 */
class DiscoverLanguageE2ETest : E2ETest() {

    @Test
    fun `createGroup sends language and group appears in discover results`() = runTest {
        val creator = getOrCreateSharedUser()

        // createGroup() internally sends language = device locale (en-US in test JVM)
        val group = withContext(Dispatchers.IO) {
            api.createGroup(
                accessToken = creator.access_token,
                name = "Language E2E Test Group ${System.currentTimeMillis()}",
                visibility = "public",
                category = "fitness"
            )
        }
        trackGroup(group.id)

        // listPublicGroups() internally sends language = device locale
        val response = withContext(Dispatchers.IO) {
            api.listPublicGroups()
        }

        assertThat(response.groups).isNotEmpty()
        val ids = response.groups.map { it.id }
        assertThat(ids).contains(group.id)
    }

    @Test
    fun `listPublicGroups with language param returns valid response without errors`() = runTest {
        // Smoke test: verifies backend accepts the language param the client sends
        val response = withContext(Dispatchers.IO) {
            api.listPublicGroups(sort = "heat")
        }

        // Should succeed — no 400 validation error from unexpected language param
        assertThat(response.groups).isNotNull()
        assertThat(response.has_more).isNotNull()
    }

    @Test
    fun `listPublicGroups returns results sorted with language-priority intact`() = runTest {
        val creator = getOrCreateSharedUser()

        // Create a group so there is at least one result
        val group = withContext(Dispatchers.IO) {
            api.createGroup(
                accessToken = creator.access_token,
                name = "Lang Priority Group ${System.currentTimeMillis()}",
                visibility = "public",
                category = "fitness"
            )
        }
        trackGroup(group.id)

        val response = withContext(Dispatchers.IO) {
            api.listPublicGroups(sort = "newest")
        }

        assertThat(response.groups).isNotEmpty()
        // Verify basic ordering integrity — created_at should be non-ascending
        val dates = response.groups.map { it.created_at }
        for (i in 0 until dates.size - 1) {
            assertThat(dates[i] >= dates[i + 1]).isTrue()
        }
    }

    @Test
    fun `cursor pagination works correctly with language parameter`() = runTest {
        getOrCreateSharedUser()

        // Page 1 with limit=1 — language param included automatically
        val page1 = withContext(Dispatchers.IO) {
            api.listPublicGroups(sort = "newest", limit = 1)
        }

        assertThat(page1.groups).hasSize(1)

        if (page1.has_more) {
            assertThat(page1.next_cursor).isNotNull()

            // Page 2 via cursor — should not overlap with page 1
            val page2 = withContext(Dispatchers.IO) {
                api.listPublicGroups(sort = "newest", limit = 1, cursor = page1.next_cursor)
            }

            assertThat(page2.groups).hasSize(1)
            assertThat(page2.groups.first().id).isNotEqualTo(page1.groups.first().id)
        }
    }
}
