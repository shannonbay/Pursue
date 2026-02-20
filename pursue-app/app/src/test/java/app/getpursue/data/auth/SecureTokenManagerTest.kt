package app.getpursue.data.auth

import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for SecureTokenManager.
 *
 * NOTE: This test is skipped because Android Keystore is not available in Robolectric tests.
 * SecureTokenManager uses Android Keystore for encryption, which requires a real Android device
 * or emulator. These tests should be converted to instrumented tests (androidTest) to run on
 * a device/emulator where Android Keystore is available.
 *
 * For unit tests, SecureTokenManager is mocked (see SignUpEmailFragmentTest) to test the
 * business logic without requiring Android Keystore.
 *
 * To test SecureTokenManager properly:
 * 1. Convert this test to an instrumented test in androidTest directory
 * 2. Run on a device or emulator where Android Keystore is available
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@Ignore("Requires Android Keystore - convert to instrumented test")
class SecureTokenManagerTest {

    @Test
    fun `test placeholder - SecureTokenManager requires instrumented tests`() {
        // This test is skipped. SecureTokenManager should be tested via instrumented tests
        // because it requires Android Keystore which is not available in Robolectric.
    }
}