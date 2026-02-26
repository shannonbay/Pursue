package app.getpursue.ui.activities

import android.content.Intent
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.text.HtmlCompat
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import app.getpursue.data.auth.AuthRepository
import app.getpursue.data.auth.GoogleSignInHelper
import app.getpursue.data.auth.GoogleSignInResult
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.analytics.AnalyticsLogger
import app.getpursue.data.config.PolicyConfigManager
import app.getpursue.data.crashlytics.CrashlyticsEvents
import app.getpursue.data.crashlytics.CrashlyticsLogger
import app.getpursue.data.crashlytics.CrashlyticsPreference
import app.getpursue.data.fcm.FcmRegistrationHelper
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import app.getpursue.ui.fragments.onboarding.SignInEmailFragment
import app.getpursue.ui.fragments.onboarding.SignUpEmailFragment
import app.getpursue.ui.fragments.onboarding.WelcomeFragment
import app.getpursue.R
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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

                response.user?.id?.let {
                    FirebaseCrashlytics.getInstance().setUserId(it)
                    CrashlyticsPreference.setCurrentUser(this@OnboardingActivity, it)
                }
                CrashlyticsLogger.log(CrashlyticsEvents.USER_LOGGED_IN)
                AnalyticsLogger.logEvent(FirebaseAnalytics.Event.LOGIN, android.os.Bundle().apply {
                    putString(FirebaseAnalytics.Param.METHOD, "email")
                })

                // Navigate to main app (ensure UI operations run on main thread)
                runOnUiThread {
                    Toast.makeText(this@OnboardingActivity, "Sign in successful", Toast.LENGTH_SHORT).show()
                    Log.d("OnboardingActivity", "Navigating to MainAppActivity after email sign-in")

                    if (!CrashlyticsPreference.isConsentAsked(this@OnboardingActivity)) {
                        showCrashConsentDialog(response.access_token)
                    } else {
                        navigateToMainApp()
                    }
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
        // Success toast is shown in SignUpEmailFragment, so navigate to orientation for new users
        val intent = OrientationActivity.newIntent(this)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
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

    private fun handleGoogleSignInSuccess(idToken: String, consentAgreed: Boolean = false) {
        Log.d("OnboardingActivity", "Handling Google sign-in success, calling backend API")
        lifecycleScope.launch {
            try {
                // Fetch policy config for version strings (best-effort)
                val config = try {
                    withContext(Dispatchers.IO) { PolicyConfigManager.getConfig(this@OnboardingActivity) }
                } catch (e: Exception) { null }

                // Call Google Sign-In API
                val response = withContext(Dispatchers.IO) {
                    ApiClient.signInWithGoogle(
                        idToken,
                        if (consentAgreed) true else null,
                        consentTermsVersion = if (consentAgreed) config?.min_required_terms_version else null,
                        consentPrivacyVersion = if (consentAgreed) config?.min_required_privacy_version else null
                    )
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

                response.user?.id?.let {
                    FirebaseCrashlytics.getInstance().setUserId(it)
                    CrashlyticsPreference.setCurrentUser(this@OnboardingActivity, it)
                }
                CrashlyticsLogger.log(CrashlyticsEvents.USER_LOGGED_IN)
                val googleEvent = if (response.is_new_user) FirebaseAnalytics.Event.SIGN_UP else FirebaseAnalytics.Event.LOGIN
                AnalyticsLogger.logEvent(googleEvent, android.os.Bundle().apply {
                    putString(FirebaseAnalytics.Param.METHOD, "google")
                })

                // Navigate to main app or orientation (ensure UI operations run on main thread)
                runOnUiThread {
                    val successMessage = if (response.is_new_user) {
                        getString(R.string.account_created_google)
                    } else {
                        getString(R.string.welcome_back)
                    }
                    Toast.makeText(this@OnboardingActivity, successMessage, Toast.LENGTH_SHORT).show()

                    if (response.is_new_user) {
                        Log.d("OnboardingActivity", "New user — navigating to OrientationActivity")
                        val intent = OrientationActivity.newIntent(this@OnboardingActivity)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    } else {
                        Log.d("OnboardingActivity", "Returning user — navigating to MainAppActivity")
                        if (!CrashlyticsPreference.isConsentAsked(this@OnboardingActivity)) {
                            showCrashConsentDialog(response.access_token)
                        } else {
                            navigateToMainApp()
                        }
                    }
                }

            } catch (e: ApiException) {
                // Handle consent required for new Google users
                if (e.code == 422 && e.errorCode == "CONSENT_REQUIRED") {
                    runOnUiThread { showGoogleConsentDialog(idToken) }
                    return@launch
                }

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

    private fun navigateToMainApp() {
        val intent = Intent(this, MainAppActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun showCrashConsentDialog(accessToken: String) {
        CrashlyticsPreference.markConsentAsked(this)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.crash_reporting_consent_title)
            .setMessage(R.string.crash_reporting_consent_message)
            .setPositiveButton(R.string.crash_reporting_consent_positive) { _, _ ->
                CrashlyticsPreference.setEnabled(this, true)
                syncCrashConsent(accessToken, "grant")
                navigateToMainApp()
            }
            .setNegativeButton(R.string.crash_reporting_consent_negative) { _, _ ->
                navigateToMainApp()
            }
            .setCancelable(false)
            .show()
    }

    private fun syncCrashConsent(accessToken: String, action: String) {
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                ApiClient.recordConsents(accessToken, listOf("analytics", "crash_reporting"), action)
            } catch (e: Exception) {
                // Non-critical
            }
        }
    }

    private fun showGoogleConsentDialog(idToken: String) {
        val view = layoutInflater.inflate(R.layout.dialog_consent_confirm, null)
        val consentCheckbox = view.findViewById<MaterialCheckBox>(R.id.consent_checkbox)
        val consentText = view.findViewById<TextView>(R.id.consent_text)

        consentText.text = HtmlCompat.fromHtml(
            getString(R.string.consent_checkbox_text),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        consentText.movementMethod = LinkMovementMethod.getInstance()

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.consent_dialog_title))
            .setView(view)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.consent_continue)) { _, _ ->
                handleGoogleSignInSuccess(idToken, consentAgreed = true)
            }
            .create()

        dialog.show()

        val positiveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        positiveButton?.isEnabled = false

        consentCheckbox.setOnCheckedChangeListener { _, isChecked ->
            positiveButton?.isEnabled = isChecked
        }
    }

    override fun onCreateAccount() {
        supportFragmentManager.commit {
            replace(R.id.onboarding_container, SignUpEmailFragment.Companion.newInstance())
            addToBackStack(null)
        }
    }
}