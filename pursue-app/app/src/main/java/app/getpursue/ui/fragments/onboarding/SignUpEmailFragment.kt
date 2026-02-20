package app.getpursue.ui.fragments.onboarding

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.util.Log
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import app.getpursue.data.auth.AuthRepository
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.config.PolicyConfigManager
import app.getpursue.data.fcm.FcmRegistrationHelper
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.activities.MainActivity
import app.getpursue.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 4.1.4 Sign Up with Email
 *
 * Allows users to create an account with display name, email, and password.
 * Includes password strength indicator and confirmation field.
 */
class SignUpEmailFragment : Fragment() {

    interface Callbacks {
        fun onSignUp(displayName: String, email: String, password: String)
        fun onGoogleSignIn()
    }

    private var callbacks: Callbacks? = null

    private lateinit var displayNameInput: TextInputEditText
    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var confirmPasswordInput: TextInputEditText
    private lateinit var charCounter: TextView
    private lateinit var nameError: View
    private lateinit var emailError: View
    private lateinit var passwordStrength: TextView
    private lateinit var passwordMismatch: View
    private lateinit var consentCheckbox: MaterialCheckBox
    private lateinit var createAccountButton: Button
    private lateinit var googleSignInButton: Button

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onDetach() {
        super.onDetach()
        callbacks = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_signup_email, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        displayNameInput = view.findViewById(R.id.input_display_name)
        emailInput = view.findViewById(R.id.input_email)
        passwordInput = view.findViewById(R.id.input_password)
        confirmPasswordInput = view.findViewById(R.id.input_confirm_password)
        charCounter = view.findViewById(R.id.text_char_counter)
        nameError = view.findViewById(R.id.text_name_error)
        emailError = view.findViewById(R.id.text_email_error)
        passwordStrength = view.findViewById(R.id.text_password_strength)
        passwordMismatch = view.findViewById(R.id.text_password_mismatch)
        consentCheckbox = view.findViewById(R.id.checkbox_consent)
        createAccountButton = view.findViewById(R.id.button_create_account)
        googleSignInButton = view.findViewById(R.id.button_google_signin)

        // Set consent text with clickable links
        val consentText = view.findViewById<TextView>(R.id.text_consent)
        consentText.text = HtmlCompat.fromHtml(
            getString(R.string.consent_checkbox_text),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        consentText.movementMethod = LinkMovementMethod.getInstance()

        consentCheckbox.setOnCheckedChangeListener { _, _ -> validate() }

        // Initialize character counter
        charCounter.text = getString(R.string.display_name_char_counter, 0)

        displayNameInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val length = s?.length ?: 0
                charCounter.text = getString(R.string.display_name_char_counter, length)
                validate()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        emailInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validate()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        passwordInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePasswordStrength()
                validate()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        confirmPasswordInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validate()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        createAccountButton.setOnClickListener {
            if (validate()) {
                val displayName = displayNameInput.text?.toString() ?: ""
                val email = emailInput.text?.toString() ?: ""
                val password = passwordInput.text?.toString() ?: ""
                registerUser(displayName, email, password)
            }
        }

        googleSignInButton.setOnClickListener {
            callbacks?.onGoogleSignIn()
        }
    }

    private fun validateDisplayName(): Boolean {
        val length = displayNameInput.text?.length ?: 0
        val valid = length in 1..30
        nameError.isVisible = !valid && length > 0
        return valid
    }

    private fun validateEmail(): Boolean {
        val email = emailInput.text?.toString() ?: ""
        val isValid = Patterns.EMAIL_ADDRESS.matcher(email).matches()
        emailError.isVisible = !isValid && email.isNotEmpty()
        return isValid
    }

    private fun updatePasswordStrength() {
        val password = passwordInput.text?.toString() ?: ""
        if (password.isEmpty()) {
            passwordStrength.visibility = View.GONE
            return
        }

        passwordStrength.visibility = View.VISIBLE
        val strength = calculatePasswordStrength(password)
        val strengthText = when (strength) {
            PasswordStrength.WEAK -> getString(R.string.password_weak)
            PasswordStrength.MEDIUM -> getString(R.string.password_medium)
            PasswordStrength.STRONG -> getString(R.string.password_strong)
        }
        passwordStrength.text = getString(R.string.password_strength, strengthText)
    }

    private fun calculatePasswordStrength(password: String): PasswordStrength {
        if (password.length < 8) return PasswordStrength.WEAK
        
        var strength = 0
        if (password.any { it.isLowerCase() }) strength++
        if (password.any { it.isUpperCase() }) strength++
        if (password.any { it.isDigit() }) strength++
        if (password.any { !it.isLetterOrDigit() }) strength++
        
        return when {
            strength >= 3 && password.length >= 12 -> PasswordStrength.STRONG
            strength >= 2 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.WEAK
        }
    }

    private fun validatePasswordMatch(): Boolean {
        val password = passwordInput.text?.toString() ?: ""
        val confirmPassword = confirmPasswordInput.text?.toString() ?: ""
        
        if (confirmPassword.isEmpty()) {
            passwordMismatch.visibility = View.GONE
            return false  // Confirm password must be entered
        }
        
        val matches = password == confirmPassword
        passwordMismatch.isVisible = !matches
        return matches
    }

    private fun validate(): Boolean {
        val displayNameValid = validateDisplayName()
        val emailValid = validateEmail()
        val password = passwordInput.text?.toString() ?: ""
        val passwordValid = password.length >= 8
        val confirmPassword = confirmPasswordInput.text?.toString() ?: ""
        val confirmPasswordEntered = confirmPassword.isNotEmpty()
        val passwordMatchValid = if (confirmPasswordEntered) validatePasswordMatch() else false
        val consentChecked = consentCheckbox.isChecked

        createAccountButton.isEnabled = displayNameValid && emailValid && passwordValid && confirmPasswordEntered && passwordMatchValid && consentChecked
        return displayNameValid && emailValid && passwordValid && confirmPasswordEntered && passwordMatchValid && consentChecked
    }

    private fun registerUser(displayName: String, email: String, password: String) {
        // Disable button during request
        createAccountButton.isEnabled = false
        createAccountButton.text = getString(R.string.creating_account)

        lifecycleScope.launch {
            try {
                // Fetch policy config for version strings (best-effort)
                val config = try {
                    withContext(Dispatchers.IO) { PolicyConfigManager.getConfig(requireContext()) }
                } catch (e: Exception) { null }

                // Send plain text password - server will hash with bcrypt
                val response = withContext(Dispatchers.IO) {
                    ApiClient.register(
                        displayName, email, password,
                        consentAgreed = true,
                        consentTermsVersion = config?.min_required_terms_version,
                        consentPrivacyVersion = config?.min_required_privacy_version
                    )
                }
                
                // Store JWT tokens securely using Android Keystore
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                tokenManager.storeTokens(response.access_token, response.refresh_token)
                
                // Update auth state
                val authRepository = AuthRepository.Companion.getInstance(requireContext())
                authRepository.setSignedIn()
                
                // Store authentication state flag (tokens stored securely above)
                requireContext().getSharedPreferences(MainActivity.Companion.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(MainActivity.Companion.KEY_HAS_IDENTITY, true)
                    .apply()
                
                // Register FCM token (non-blocking - don't fail registration if this fails)
                try {
                    FcmRegistrationHelper.registerFcmTokenIfNeeded(
                        requireContext(),
                        response.access_token
                    )
                } catch (e: Exception) {
                    // FCM registration failed, but continue with registration success
                    Log.w("SignUpEmailFragment", "FCM token registration failed", e)
                }
                
                Toast.makeText(requireContext(), getString(R.string.account_created), Toast.LENGTH_SHORT).show()
                callbacks?.onSignUp(displayName, email, password)
            } catch (e: ApiException) {
                val errorMessage = when (e.code) {
                    409 -> "An account with this email already exists"
                    400 -> "Invalid registration data. Please check your inputs."
                    else -> e.message ?: "Registration failed. Please try again."
                }
                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
                createAccountButton.isEnabled = true
                createAccountButton.text = getString(R.string.create_account_button)
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Network error: ${e.message}", Toast.LENGTH_LONG).show()
                createAccountButton.isEnabled = true
                createAccountButton.text = getString(R.string.create_account_button)
            }
        }
    }

    private enum class PasswordStrength {
        WEAK, MEDIUM, STRONG
    }

    companion object {
        fun newInstance(): SignUpEmailFragment = SignUpEmailFragment()
    }
}
