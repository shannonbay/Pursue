package com.github.shannonbay.pursue.data.auth

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for AuthRepository.
 *
 * Verifies that signOut() clears tokens and updates auth state (token cleared from storage after logout).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AuthRepositoryTest {

    private lateinit var context: Context
    private lateinit var mockTokenManager: SecureTokenManager
    private lateinit var authRepository: AuthRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        mockkObject(SecureTokenManager.Companion)
        mockTokenManager = mockk(relaxed = true)
        every { SecureTokenManager.getInstance(any()) } returns mockTokenManager
        every { mockTokenManager.hasTokens() } returns true

        // Reset AuthRepository singleton so getInstance() creates a new instance with mocked SecureTokenManager.
        // INSTANCE is on the outer class (see TESTING.md §4 Singletons).
        val instanceField = AuthRepository::class.java.getDeclaredField("INSTANCE")
        instanceField.isAccessible = true
        instanceField.set(null, null)

        authRepository = AuthRepository.getInstance(context)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `signOut clears tokens and sets SignedOut`() {
        authRepository.signOut()

        verify { mockTokenManager.clearTokens() }
        assertEquals(
            "Auth state should be SignedOut after signOut",
            AuthState.SignedOut,
            authRepository.authState.value
        )
    }
}
