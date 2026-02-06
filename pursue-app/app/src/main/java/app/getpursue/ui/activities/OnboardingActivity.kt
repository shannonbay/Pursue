package app.getpursue.ui.activities

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import app.getpursue.data.auth.AuthRepository
import app.getpursue.data.auth.GoogleSignInHelper
import app.getpursue.data.auth.GoogleSignInResult
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.fcm.FcmRegistrationHelper
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.fragments.onboarding.SignInEmailFragment
import app.getpursue.ui.fragments.onboarding.SignUpEmailFragment
import app.getpursue.ui.fragments.onboarding.WelcomeFragment
import com.github.shannonbay.pursue.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts the first‑time user onboarding flow (UI spec section 4.1).
 *
 * This activity swaps simple fragments for each screen in the flow.
 * It deliberately avoids complex navigation libraries to keep the
 * initial implementation lightweight.
 */
class OnboardingActivity : AppCompatActivity(),
    WelcomeFragment.Callbacks,
    SignInEmailFragment.Callbacks,
    SignUpEmailFragment.Callbacks {

    private val RC_GOOGLE_SIGN_IN = 9001
    private lateinit var googleSignInHelper: GoogleSignInHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)

        // Initialize Google Sign-In helper
        googleSignInHelper = GoogleSignInHelper(this)

        if (savedInstanceState == null) {
            supportFragmentManager.commit {
                replace(R.id.onboarding_container, WelcomeFragment.Companion.newInstance())
            }
        }
    }

    // region WelcomeFragment.Callbacks

    override fun onSignInWithEmail() {
        supportFragmentManager.commit {
            replace(R.id.onboarding_container, SignInEmailFragment.Companion.newInstance())
            addToBackStack(null)
        }
    }

    // endregion

    // region SignInEmailFragment.Callbacks

    override fun onSignIn(email: String, password: String) {
        // Get reference to sign-in fragment for loading state management
        val signInFragment = supportFragmentManager.findFragmentById(R.id.onboarding_container)
            as? SignInEmailFragment

        lifecycleScope.launch {
            try {
                // Call login API
                val response = withContext(Dispatchers.IO) {
                    ApiClient.login(email, password)
                }

                // Store JWT tokens securely
                val tokenManager = SecureTokenManager.Companion.getInstance(this@OnboardingActivity)
                tokenManager.storeTokens(response.access_token, response.refresh_token)

                // Update auth state
                val authRepository = AuthRepository.Companion.getInstance(this@OnboardingActivity)
                authRepository.setSignedIn()

                // Update SharedPreferences for backward compatibility
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(MainActivity.KEY_HAS_IDENTITY, true)
                    .apply()

                // Register FCM token (non-blocking - don't fail sign-in if this fails)
                try {
                    FcmRegistrationHelper.registerFcmTokenIfNeeded(
                        this@OnboardingActivity,
                        response.access_token
                    )
                } catch (e: Exception) {
                    // FCM registration failed, but continue with sign-in
                    Log.w("OnboardingActivity", "FCM token registration failed", e)
                }

                // Navigate to main app (ensure UI operations run on main thread)
                runOnUiThread {
                    Toast.makeText(this@OnboardingActivity, "Sign in successful", Toast.LENGTH_SHORT).show()

                    // Start MainAppActivity with flags to ensure fresh instance and clear task stack
                    val intent = Intent(this@OnboardingActivity, MainAppActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    Log.d("OnboardingActivity", "Navigating to MainAppActivity after email sign-in")
                    startActivity(intent)
                    finish()
                }

            } catch (e: ApiException) {
                // Handle API errors (ensure UI operations run on main thread)
                val errorMessage = when (e.code) {
                    401 -> "Invalid email or password"
                    400 -> "Invalid input. Please check your email and password."
                    else -> e.message ?: "Sign in failed. Please try again."
                }
                try {
                    runOnUiThread {
                        Toast.makeText(this@OnboardingActivity, errorMessage, Toast.LENGTH_LONG).show()
                        // Reset loading state
                        signInFragment?.setLoadingState(false)
                    }
                } catch (uiException: Exception) {
                    // If runOnUiThread fails (e.g., in test environment), log and reset loading state directly
                    Log.w("OnboardingActivity", "Failed to show error toast on UI thread", uiException)
                    signInFragment?.setLoadingState(false)
                }

            } catch (e: Exception) {
                // Handle network or other errors (ensure UI operations run on main thread)
                val errorMessage = "Network error: ${e.message ?: "Please check your connection."}"
                Log.e("OnboardingActivity", "Sign-in error", e)
                try {
                    runOnUiThread {
                        Toast.makeText(this@OnboardingActivity, errorMessage, Toast.LENGTH_LONG).show()
                        // Reset loading state
                        signInFragment?.setLoadingState(false)
                    }
                } catch (uiException: Exception) {
                    // If runOnUiThread fails (e.g., in test environment), log and reset loading state directly
                    Log.w("OnboardingActivity", "Failed to show error toast on UI thread", uiException)
                    signInFragment?.setLoadingState(false)
                }
            }
        }
    }

    override fun onForgotPassword() {
        // TODO: Implement forgot password flow
        Toast.makeText(this, "Forgot password coming soon", Toast.LENGTH_SHORT).show()
    }

    // endregion

    // region SignUpEmailFragment.Callbacks

    override fun onSignUp(displayName: String, email: String, password: String) {
        // Registration is handled in SignUpEmailFragment (API call, token storage, FCM registration)
        // Success toast is shown in SignUpEmailFragment, so just navigate to main app
        startActivity(Intent(this, MainAppActivity::class.java))
        finish()
    }

    // endregion

    // Shared callbacks (implemented once for all interfaces)

    override fun onGoogleSignIn() {
        // Start Google Sign-In flow
        val signInIntent = googleSignInHelper.getSignInIntent()
        startActivityForResult(signInIntent, RC_GOOGLE_SIGN_IN)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == RC_GOOGLE_SIGN_IN) {
            Log.d("OnboardingActivity", "Received Google Sign-In result, requestCode: $requestCode, resultCode: $resultCode")

            // Only process if result is OK (resultCode == -1 means RESULT_OK)
            // If resultCode is RESULT_CANCELED (0), the user cancelled or there was an error
            // But we still need to check the actual result to distinguish between cancellation and error
            val result = googleSignInHelper.handleSignInResult(data)

            when (result) {
                is GoogleSignInResult.Success -> {
                    // Extract ID token and sign in with backend
                    val idToken = result.account.idToken
                    if (idToken != null) {
                        Log.d("OnboardingActivity", "Google Sign-In successful, ID token received")
                        handleGoogleSignInSuccess(idToken)
                    } else {
                        Log.e("OnboardingActivity", "Google Sign-In succeeded but ID token is null")
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                getString(R.string.google_sign_in_failed),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
                }
                is GoogleSignInResult.Error -> {
                    // Detect network errors by message content
                    val isNetworkError = result.message.contains("Network error", ignoreCase = true)

                    // Always log the error with details
                    Log.e(
                        "OnboardingActivity",
                        "Google Sign-In error: ${result.message}, resultCode: $resultCode, isNetworkError: $isNetworkError"
                    )

                    // Show toast for:
                    // 1. All errors when resultCode is OK (user completed the flow but it failed)
                    // 2. Network errors regardless of resultCode (user needs to know about connectivity issues)
                    if (resultCode == -1 || isNetworkError) { // RESULT_OK or network error
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                result.message,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    } else {
                        // Result was CANCELED and not a network error - might be transient, log only
                        Log.w(
                            "OnboardingActivity",
                            "Google Sign-In error with CANCELED result (might be transient): ${result.message}"
                        )
                    }
                }
                is GoogleSignInResult.Cancelled -> {
                    // User cancelled - no action needed, stay on current screen
                    Log.d("OnboardingActivity", "Google Sign-In cancelled by user")
                }
            }
        }
    }

    private fun handleGoogleSignInSuccess(idToken: String) {
        Log.d("OnboardingActivity", "Handling Google sign-in success, calling backend API")
        lifecycleScope.launch {
            try {
                // Call Google Sign-In API
                val response = withContext(Dispatchers.IO) {
                    ApiClient.signInWithGoogle(idToken)
                }
                Log.d("OnboardingActivity", "Backend API call successful, is_new_user: ${response.is_new_user}")

                // Store JWT tokens securely
                val tokenManager = SecureTokenManager.Companion.getInstance(this@OnboardingActivity)
                tokenManager.storeTokens(response.access_token, response.refresh_token)

                // Update auth state
                val authRepository = AuthRepository.Companion.getInstance(this@OnboardingActivity)
                authRepository.setSignedIn()

                // Update SharedPreferences for backward compatibility
                getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
                    .edit()
                    .putBoolean(MainActivity.KEY_HAS_IDENTITY, true)
                    .apply()

                // Register FCM token (non-blocking - don't fail sign-in if this fails)
                try {
                    FcmRegistrationHelper.registerFcmTokenIfNeeded(
                        this@OnboardingActivity,
                        response.access_token
                    )
                } catch (e: Exception) {
                    // FCM registration failed, but continue with sign-in
                    Log.w("OnboardingActivity", "FCM token registration failed", e)
                }

                // Navigate to main app (ensure UI operations run on main thread)
                runOnUiThread {
                    val successMessage = if (response.is_new_user) {
                        getString(R.string.account_created_google)
                    } else {
                        getString(R.string.welcome_back)
                    }
                    Toast.makeText(this@OnboardingActivity, successMessage, Toast.LENGTH_SHORT).show()

                    // Start MainAppActivity with flags to ensure fresh instance and clear task stack
                    val intent = Intent(this@OnboardingActivity, MainAppActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    Log.d("OnboardingActivity", "Navigating to MainAppActivity after Google sign-in")
                    startActivity(intent)
                    finish()
                }

            } catch (e: ApiException) {
                // Handle API errors (ensure UI operations run on main thread)
                Log.e("OnboardingActivity", "Google sign-in API error: ${e.code} - ${e.message}")
                val errorMessage = when (e.code) {
                    400 -> "Invalid Google token. Please try again."
                    401 -> "Google sign-in failed. Please try again."
                    else -> e.message ?: getString(R.string.google_sign_in_failed)
                }
                runOnUiThread {
                    Toast.makeText(this@OnboardingActivity, errorMessage, Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                // Handle network or other errors (ensure UI operations run on main thread)
                val errorMessage = "Network error: ${e.message ?: "Please check your connection."}"
                Log.e("OnboardingActivity", "Google sign-in error", e)
                runOnUiThread {
                    Toast.makeText(this@OnboardingActivity, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onCreateAccount() {
        supportFragmentManager.commit {
            replace(R.id.onboarding_container, SignUpEmailFragment.Companion.newInstance())
            addToBackStack(null)
        }
    }
}