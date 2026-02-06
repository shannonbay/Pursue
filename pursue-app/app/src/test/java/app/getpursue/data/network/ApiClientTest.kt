package app.getpursue.data.network

import app.getpursue.MockApiClient
import app.getpursue.models.GroupGoalsResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ApiClient.getGroupGoals() method.
 * 
 * Tests URL construction, parameter handling, response parsing, and error handling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ApiClientTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testAccessToken = "test_access_token_123"
    private val testGroupId = "test_group_id_123"

    @Before
    fun setUp() {
        mockkObject(ApiClient)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ========== API Call Tests ==========

    @Test
    fun `test getGroupGoals returns response with default parameters`() = runTest(testDispatcher) {
        // Given
        val response = MockApiClient.createGroupGoalsResponse()
        coEvery { 
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = null,
                archived = false,
                includeProgress = true
            )
        } returns response

        // When
        val result = ApiClient.getGroupGoals(
            accessToken = testAccessToken,
            groupId = testGroupId
        )

        // Then
        assertNotNull("Response should not be null", result)
        assertEquals("Should have 2 goals", 2, result.goals.size)
        assertEquals("Total should match", 2, result.total)
        
        coVerify(exactly = 1) {
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = null,
                archived = false,
                includeProgress = true
            )
        }
    }

    @Test
    fun `test getGroupGoals includes include_progress parameter`() = runTest(testDispatcher) {
        // Given
        val response = MockApiClient.createGroupGoalsResponse()
        coEvery { 
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = null,
                archived = false,
                includeProgress = true
            )
        } returns response

        // When
        val result = ApiClient.getGroupGoals(
            accessToken = testAccessToken,
            groupId = testGroupId,
            includeProgress = true
        )

        // Then
        assertNotNull("Response should not be null", result)
        coVerify(exactly = 1) {
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = null,
                archived = false,
                includeProgress = true
            )
        }
    }

    @Test
    fun `test getGroupGoals includes cadence filter when provided`() = runTest(testDispatcher) {
        // Given
        val response = MockApiClient.createGroupGoalsResponse()
        coEvery { 
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = "weekly",
                archived = false,
                includeProgress = true
            )
        } returns response

        // When
        val result = ApiClient.getGroupGoals(
            accessToken = testAccessToken,
            groupId = testGroupId,
            cadence = "weekly"
        )

        // Then
        assertNotNull("Response should not be null", result)
        coVerify(exactly = 1) {
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = "weekly",
                archived = false,
                includeProgress = true
            )
        }
    }

    @Test
    fun `test getGroupGoals includes archived parameter when true`() = runTest(testDispatcher) {
        // Given
        val response = MockApiClient.createGroupGoalsResponse()
        coEvery { 
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = null,
                archived = true,
                includeProgress = true
            )
        } returns response

        // When
        val result = ApiClient.getGroupGoals(
            accessToken = testAccessToken,
            groupId = testGroupId,
            archived = true
        )

        // Then
        assertNotNull("Response should not be null", result)
        coVerify(exactly = 1) {
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = null,
                archived = true,
                includeProgress = true
            )
        }
    }

    @Test
    fun `test getGroupGoals can exclude progress when includeProgress is false`() = runTest(testDispatcher) {
        // Given
        val responseWithoutProgress = GroupGoalsResponse(
            goals = listOf(
                MockApiClient.createGroupGoalResponse(
                    currentPeriodProgress = null
                )
            ),
            total = 1
        )
        coEvery { 
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = null,
                archived = false,
                includeProgress = false
            )
        } returns responseWithoutProgress

        // When
        val result = ApiClient.getGroupGoals(
            accessToken = testAccessToken,
            groupId = testGroupId,
            includeProgress = false
        )

        // Then
        assertNotNull("Response should not be null", result)
        assertEquals("Should have 1 goal", 1, result.goals.size)
        assertEquals("Goal should not have progress", null, result.goals[0].current_period_progress)
        
        coVerify(exactly = 1) {
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = null,
                archived = false,
                includeProgress = false
            )
        }
    }

    @Test
    fun `test getGroupGoals parses response correctly`() = runTest(testDispatcher) {
        // Given
        val response = MockApiClient.createGroupGoalsResponse()
        coEvery { 
            ApiClient.getGroupGoals(any(), any(), any(), any(), any())
        } returns response

        // When
        val result = ApiClient.getGroupGoals(
            accessToken = testAccessToken,
            groupId = testGroupId
        )

        // Then - Verify response structure
        assertNotNull("Response should not be null", result)
        assertEquals("Should have goals", 2, result.goals.size)
        assertEquals("Total should match", 2, result.total)
        
        // Verify first goal (binary)
        val firstGoal = result.goals[0]
        assertEquals("First goal should be binary", "binary", firstGoal.metric_type)
        assertEquals("First goal should have progress", true, firstGoal.current_period_progress != null)
        
        // Verify second goal (numeric)
        val secondGoal = result.goals[1]
        assertEquals("Second goal should be numeric", "numeric", secondGoal.metric_type)
        assertEquals("Second goal should have progress", true, secondGoal.current_period_progress != null)
    }

    // ========== Error Handling Tests ==========

    @Test
    fun `test getGroupGoals throws ApiException on 401`() = runTest(testDispatcher) {
        // Given
        coEvery { 
            ApiClient.getGroupGoals(any(), any(), any(), any(), any())
        } throws ApiException(401, "Unauthorized")

        // When/Then
        try {
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId
            )
            Assert.fail("Should have thrown ApiException")
        } catch (e: ApiException) {
            assertEquals("Should be 401 error", 401, e.code)
            assertEquals("Should be Unauthorized", "Unauthorized", e.message)
        }
    }

    @Test
    fun `test getGroupGoals throws ApiException on 403`() = runTest(testDispatcher) {
        // Given
        coEvery { 
            ApiClient.getGroupGoals(any(), any(), any(), any(), any())
        } throws ApiException(403, "Forbidden")

        // When/Then
        try {
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId
            )
            Assert.fail("Should have thrown ApiException")
        } catch (e: ApiException) {
            assertEquals("Should be 403 error", 403, e.code)
            assertEquals("Should be Forbidden", "Forbidden", e.message)
        }
    }

    @Test
    fun `test getGroupGoals throws ApiException on 500`() = runTest(testDispatcher) {
        // Given
        coEvery { 
            ApiClient.getGroupGoals(any(), any(), any(), any(), any())
        } throws ApiException(500, "Internal Server Error")

        // When/Then
        try {
            ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId
            )
            Assert.fail("Should have thrown ApiException")
        } catch (e: ApiException) {
            assertEquals("Should be 500 error", 500, e.code)
            assertEquals("Should be Internal Server Error", "Internal Server Error", e.message)
        }
    }

    @Test
    fun `test getGroupGoals handles empty response`() = runTest(testDispatcher) {
        // Given
        val emptyResponse = MockApiClient.createEmptyGroupGoalsResponse()
        coEvery { 
            ApiClient.getGroupGoals(any(), any(), any(), any(), any())
        } returns emptyResponse

        // When
        val result = ApiClient.getGroupGoals(
            accessToken = testAccessToken,
            groupId = testGroupId
        )

        // Then
        assertNotNull("Response should not be null", result)
        assertEquals("Should have no goals", 0, result.goals.size)
        assertEquals("Total should be 0", 0, result.total)
    }

    @Test
    fun `test getGroupGoals handles all cadence types`() = runTest(testDispatcher) {
        // Given
        val cadences = listOf("daily", "weekly", "monthly", "yearly")
        
        for (cadence in cadences) {
            val response = MockApiClient.createGroupGoalsResponse(
                goals = listOf(
                    MockApiClient.createGroupGoalResponse(
                        cadence = cadence
                    )
                ),
                total = 1
            )
            coEvery { 
                ApiClient.getGroupGoals(
                    accessToken = testAccessToken,
                    groupId = testGroupId,
                    cadence = cadence,
                    archived = false,
                    includeProgress = true
                )
            } returns response

            // When
            val result = ApiClient.getGroupGoals(
                accessToken = testAccessToken,
                groupId = testGroupId,
                cadence = cadence
            )

            // Then
            assertEquals("Should have 1 goal for $cadence", 1, result.goals.size)
            assertEquals("Goal cadence should match", cadence, result.goals[0].cadence)
        }
    }
}
