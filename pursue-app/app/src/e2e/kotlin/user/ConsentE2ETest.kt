package com.github.shannonbay.pursue.e2e.user

import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.google.common.truth.Truth
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for crash-reporting consent endpoints.
 *
 * Covers:
 * - POST /api/users/me/consents (with and without action field)
 * - GET /api/users/me/consents/status
 * - GET /api/users/me/consents (audit log)
 *
 * Backend must be running at localhost:3000.
 */
class ConsentE2ETest : E2ETest() {

    @Test
    fun `record crash_reporting grant and status reflects grant`() = runTest {
        val auth = getOrCreateSharedUser()

        api.recordConsents(auth.access_token, listOf("crash_reporting"), action = "grant")

        val status = api.getConsentStatus(auth.access_token)
        Truth.assertThat(status.status["crash_reporting"]?.action).isEqualTo("grant")
    }

    @Test
    fun `record crash_reporting revoke and status reflects revoke`() = runTest {
        val auth = getOrCreateSharedUser()

        api.recordConsents(auth.access_token, listOf("crash_reporting"), action = "revoke")

        val status = api.getConsentStatus(auth.access_token)
        Truth.assertThat(status.status["crash_reporting"]?.action).isEqualTo("revoke")
    }

    @Test
    fun `status returns latest action after full toggle cycle`() = runTest {
        val auth = getOrCreateSharedUser()

        api.recordConsents(auth.access_token, listOf("crash_reporting"), action = "grant")
        api.recordConsents(auth.access_token, listOf("crash_reporting"), action = "revoke")
        api.recordConsents(auth.access_token, listOf("crash_reporting"), action = "grant")

        val status = api.getConsentStatus(auth.access_token)
        Truth.assertThat(status.status["crash_reporting"]?.action).isEqualTo("grant")
    }

    @Test
    fun `record consent without explicit action defaults to grant`() = runTest {
        val auth = getOrCreateSharedUser()

        // action = null → no field in JSON → backend default should be "grant"
        api.recordConsents(auth.access_token, listOf("crash_reporting"), action = null)

        val status = api.getConsentStatus(auth.access_token)
        Truth.assertThat(status.status["crash_reporting"]?.action).isEqualTo("grant")
    }

    @Test
    fun `get consent status returns 401 without auth`() = runTest {
        getOrCreateSharedUser()
        SecureTokenManager.getInstance(context).clearTokens()

        var ex: Exception? = null
        try {
            api.getConsentStatus("")
        } catch (e: Exception) {
            ex = e
        }

        Truth.assertThat(ex).isInstanceOf(ApiException::class.java)
        Truth.assertThat((ex as ApiException).code).isEqualTo(401)
    }

    @Test
    fun `audit log includes action field`() = runTest {
        val auth = getOrCreateSharedUser()

        api.recordConsents(auth.access_token, listOf("crash_reporting"), action = "grant")

        val consents = api.getMyConsents(auth.access_token)
        val entry = consents.consents.firstOrNull { it.consent_type == "crash_reporting" }
        Truth.assertThat(entry).isNotNull()
        Truth.assertThat(entry!!.action).isEqualTo("grant")
    }
}