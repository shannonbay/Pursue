package com.github.shannonbay.pursue.e2e.photos

import app.getpursue.data.network.ApiException
import com.github.shannonbay.pursue.e2e.config.E2ETest
import com.github.shannonbay.pursue.e2e.helpers.TestImageHelper
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * E2E tests for progress entry photo upload and retrieval.
 *
 * Tests POST /api/progress/:progress_entry_id/photo and
 * GET /api/progress/:progress_entry_id/photo endpoints.
 *
 * Backend constraints:
 * - 500 KB max file size
 * - JPEG/WebP only (PNG rejected)
 * - 15-minute edit window after logging progress
 * - One photo per progress entry
 * - Quota: 3/week free, 70/week premium
 */
class ProgressPhotoE2ETest : E2ETest() {

    /**
     * Helper to create a progress entry that we can attach a photo to.
     * Returns Pair(accessToken, progressEntryId).
     */
    private suspend fun createProgressEntryForPhoto(): Pair<String, String> {
        val auth = getOrCreateSharedUser()
        val group = getOrCreateSharedGroup()

        // Create a goal
        val goal = api.createGoal(
            accessToken = auth.access_token,
            groupId = group.id,
            title = "Photo Test Goal ${System.currentTimeMillis()}",
            cadence = "daily",
            metricType = "binary"
        )

        // Log progress (creates entry we can attach photo to)
        // Use fixed past date to avoid timezone mismatch (server validates "date cannot be in the future")
        val today = "2026-02-01"
        val entry = api.logProgress(
            accessToken = auth.access_token,
            goalId = goal.id,
            value = 1.0,
            userDate = today,
            userTimezone = "Australia/Sydney"
        )

        return Pair(auth.access_token, entry.id)
    }

    // ============ HAPPY PATH TESTS ============

    @Test
    fun `upload photo to progress entry succeeds`() = runTest {
        val (accessToken, entryId) = createProgressEntryForPhoto()
        val testImage = TestImageHelper.createSmallTestImage()

        val response = api.uploadProgressPhoto(
            accessToken = accessToken,
            progressEntryId = entryId,
            imageBytes = testImage
        )

        assertThat(response.photo_id).isNotEmpty()
        assertThat(response.expires_at).isNotEmpty()
    }

    @Test
    fun `get photo returns signed URL after upload`() = runTest {
        val (accessToken, entryId) = createProgressEntryForPhoto()
        val testImage = TestImageHelper.createSmallTestImage()

        // Upload
        api.uploadProgressPhoto(accessToken, entryId, testImage)

        // Get
        val response = api.getProgressPhoto(accessToken, entryId)

        assertThat(response.photo_id).isNotEmpty()
        assertThat(response.url).startsWith("https://")
        assertThat(response.width).isGreaterThan(0)
        assertThat(response.height).isGreaterThan(0)
        assertThat(response.expires_at).isNotEmpty()
    }

    // ============ ERROR CASE TESTS ============

    @Test
    fun `upload photo to non-existent entry fails with 404`() = runTest {
        val auth = getOrCreateSharedUser()
        val testImage = TestImageHelper.createSmallTestImage()
        val fakeEntryId = "00000000-0000-0000-0000-000000000000"

        var exception: ApiException? = null
        try {
            api.uploadProgressPhoto(auth.access_token, fakeEntryId, testImage)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
    }

    @Test
    fun `upload second photo to same entry fails with 409`() = runTest {
        val (accessToken, entryId) = createProgressEntryForPhoto()
        val testImage = TestImageHelper.createSmallTestImage()

        // First upload succeeds
        api.uploadProgressPhoto(accessToken, entryId, testImage)

        // Second upload fails
        var exception: ApiException? = null
        try {
            api.uploadProgressPhoto(accessToken, entryId, testImage)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(409)
        assertThat(exception.errorCode).isEqualTo("PHOTO_ALREADY_EXISTS")
    }

    @Test
    fun `upload oversized photo fails`() = runTest {
        val (accessToken, entryId) = createProgressEntryForPhoto()
        val largeImage = TestImageHelper.createOversizedProgressPhoto() // >500KB

        var exception: ApiException? = null
        try {
            api.uploadProgressPhoto(accessToken, entryId, largeImage)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        // Backend returns 400 for file too large (multer limit)
        assertThat(exception!!.code).isAnyOf(400, 413)
    }

    @Test
    fun `get photo for entry without photo fails with 404`() = runTest {
        val (accessToken, entryId) = createProgressEntryForPhoto()
        // Don't upload a photo

        var exception: ApiException? = null
        try {
            api.getProgressPhoto(accessToken, entryId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(404)
        assertThat(exception.errorCode).isEqualTo("NO_PHOTO")
    }

    @Test
    fun `non-member cannot view photo`() = runTest {
        val (ownerToken, entryId) = createProgressEntryForPhoto()
        val testImage = TestImageHelper.createSmallTestImage()

        // Upload as owner
        api.uploadProgressPhoto(ownerToken, entryId, testImage)

        // Create a second user who is not a member of the group
        val nonMember = testDataHelper.createTestUser(api, displayName = "Non-member User")
        trackUser(nonMember.user!!.id)

        var exception: ApiException? = null
        try {
            api.getProgressPhoto(nonMember.access_token, entryId)
        } catch (e: ApiException) {
            exception = e
        }

        assertThat(exception).isNotNull()
        assertThat(exception!!.code).isEqualTo(403)
    }
}
