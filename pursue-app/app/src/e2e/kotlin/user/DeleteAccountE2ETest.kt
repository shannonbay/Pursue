package com.github.shannonbay.pursue.e2e.user

import app.getpursue.data.network.ApiException
import com.google.common.truth.Truth.assertThat
import com.github.shannonbay.pursue.e2e.config.E2ETest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.fail
import org.junit.Test

/**
 * E2E tests for account deletion (hard delete).
 *
 * Each test creates a fresh (non-shared) user because deletion is destructive.
 */
class DeleteAccountE2ETest : E2ETest() {

    @Test
    fun `delete account succeeds with correct confirmation`() = runTest {
        val user = testDataHelper.createTestUser(api)
        val accessToken = user.access_token

        // Should not throw
        api.deleteAccount(accessToken, "delete")
    }

    @Test
    fun `login fails after account deletion`() = runTest {
        val password = "TestPass123!"
        val email = "delete-test-${System.currentTimeMillis()}@example.com"
        val user = api.register("Delete Test", email, password)
        val accessToken = user.access_token

        api.deleteAccount(accessToken, "delete")

        try {
            api.login(email, password)
            fail("Expected login to fail after account deletion")
        } catch (e: ApiException) {
            assertThat(e.code).isEqualTo(401)
        }
    }

    @Test
    fun `delete account rejects wrong confirmation`() = runTest {
        val user = testDataHelper.createTestUser(api)
        val accessToken = user.access_token

        try {
            api.deleteAccount(accessToken, "wrong")
            fail("Expected 400 error for wrong confirmation")
        } catch (e: ApiException) {
            assertThat(e.code).isEqualTo(400)
        }
    }
}
