package app.getpursue.ui.fragments.onboarding

import android.widget.DatePicker
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import androidx.lifecycle.lifecycleScope
import app.getpursue.data.analytics.AnalyticsLogger
import app.getpursue.data.auth.AuthRepository
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.config.PolicyConfigManager
import app.getpursue.data.crashlytics.CrashlyticsPreference
import com.google.firebase.analytics.FirebaseAnalytics
import app.getpursue.data.fcm.FcmRegistrationHelper
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import com.google.firebase.crashlytics.FirebaseCrashlytics
import app.getpursue.ui.activities.MainActivity
import app.getpursue.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

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
    private lateinit var dobInput: TextInputEditText
    private lateinit var dobErrorText: TextView
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
    private lateinit var passwordRequirementsHeader: TextView
    private lateinit var passwordRequirementsLayout: LinearLayout
    private lateinit var reqLengthText: TextView
    private lateinit var reqLowercaseText: TextView
    private lateinit var reqUppercaseText: TextView
    private lateinit var reqNumberText: TextView
    private lateinit var reqSpecialText: TextView

    private var dobIso: String? = null // "YYYY-MM-DD" when a valid 18+ date is chosen
    private var dobSelected = false

    data class PasswordRequirements(
        val hasMinLength: Boolean,
        val hasLowercase: Boolean,
        val hasUppercase: Boolean,
        val hasNumber: Boolean,
        val hasSpecialChar: Boolean
    ) {
        val allMet: Boolean
            get() = hasMinLength && hasLowercase && hasUppercase && hasNumber && hasSpecialChar
    }

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
        dobInput = view.findViewById(R.id.et_dob)
        dobErrorText = view.findViewById(R.id.tv_dob_error)
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
        passwordRequirementsHeader = view.findViewById(R.id.text_password_requirements_header)
        passwordRequirementsLayout = view.findViewById(R.id.layout_password_requirements)
        reqLengthText = view.findViewById(R.id.text_req_length)
        reqLowercaseText = view.findViewById(R.id.text_req_lowercase)
        reqUppercaseText = view.findViewById(R.id.text_req_uppercase)
        reqNumberText = view.findViewById(R.id.text_req_number)
        reqSpecialText = view.findViewById(R.id.text_req_special)

        // Set consent text with clickable links
        val consentText = view.findViewById<TextView>(R.id.text_consent)
        consentText.text = HtmlCompat.fromHtml(
            getString(R.string.consent_checkbox_text),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )
        consentText.movementMethod = LinkMovementMethod.getInstance()

        consentCheckbox.setOnCheckedChangeListener { _, _ -> validate() }

        // DOB picker
        dobInput.setOnClickListener { showDobPicker() }

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
                val dob = dobIso ?: return@setOnClickListener
                registerUser(displayName, email, password, dob)
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
            // Hide everything when password is empty
            passwordRequirementsHeader.isVisible = false
            passwordRequirementsLayout.isVisible = false
            passwordStrength.isVisible = false
            return
        }

        // Show requirements checklist
        passwordRequirementsHeader.isVisible = true
        passwordRequirementsLayout.isVisible = true

        // Check each requirement
        val reqs = checkPasswordRequirements(password)

        // Update each requirement indicator
        updateRequirementView(reqLengthText, reqs.hasMinLength)
        updateRequirementView(reqLowercaseText, reqs.hasLowercase)
        updateRequirementView(reqUppercaseText, reqs.hasUppercase)
        updateRequirementView(reqNumberText, reqs.hasNumber)
        updateRequirementView(reqSpecialText, reqs.hasSpecialChar)

        // Only show strength indicator once all requirements are met
        if (reqs.allMet) {
            val strength = calculatePasswordStrength(password)
            passwordStrength.isVisible = true
            passwordStrength.text = getString(
                R.string.password_strength,
                when (strength) {
                    PasswordStrength.WEAK -> getString(R.string.password_weak)
                    PasswordStrength.MEDIUM -> getString(R.string.password_medium)
                    PasswordStrength.STRONG -> getString(R.string.password_strong)
                }
            )
        } else {
            passwordStrength.isVisible = false
        }
    }

    private fun updateRequirementView(textView: TextView, isMet: Boolean) {
        if (isMet) {
            textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_check_circle, 0, 0, 0
            )
            textView.compoundDrawablesRelative[0]?.mutate()?.setTint(
                ContextCompat.getColor(requireContext(), R.color.approved_green_border)
            )
            textView.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.on_surface)
            )
        } else {
            textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_cancel, 0, 0, 0
            )
            textView.compoundDrawablesRelative[0]?.mutate()?.setTint(
                ContextCompat.getColor(requireContext(), R.color.on_surface_variant)
            )
            textView.setTextColor(
                ContextCompat.getColor(requireContext(), R.color.on_surface_variant)
            )
        }
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

    private fun checkPasswordRequirements(password: String): PasswordRequirements {
        return PasswordRequirements(
            hasMinLength = password.length >= 8,
            hasLowercase = password.any { it.isLowerCase() },
            hasUppercase = password.any { it.isUpperCase() },
            hasNumber = password.any { it.isDigit() },
            hasSpecialChar = password.any { !it.isLetterOrDigit() }
        )
    }

    private fun validate(): Boolean {
        val displayNameValid = validateDisplayName()
        val emailValid = validateEmail()
        val password = passwordInput.text?.toString() ?: ""
        val confirmPassword = confirmPasswordInput.text?.toString() ?: ""

        // Check password requirements
        val reqs = checkPasswordRequirements(password)

        // Update mismatch error visibility
        passwordMismatch.isVisible = password.isNotEmpty() &&
                                       confirmPassword.isNotEmpty() &&
                                       password != confirmPassword

        // Enable submit button only if:
        // - Display name is valid
        // - DOB is selected and age ≥ 18
        // - Email is valid
        // - All password requirements are met
        // - Passwords match
        // - Consent is checked
        createAccountButton.isEnabled = displayNameValid &&
                                         dobSelected &&
                                         emailValid &&
                                         reqs.allMet &&
                                         password == confirmPassword &&
                                         confirmPassword.isNotEmpty() &&
                                         consentCheckbox.isChecked

        return createAccountButton.isEnabled
    }

    private fun showDobPicker() {
        val cal = Calendar.getInstance()
        val todayMillis = cal.timeInMillis
        cal.add(Calendar.YEAR, -120)
        val minMillis = cal.timeInMillis

        val dialogView = layoutInflater.inflate(R.layout.dialog_dob_picker, null)
        val datePicker = dialogView.findViewById<DatePicker>(R.id.date_picker)
        datePicker.maxDate = todayMillis
        datePicker.minDate = minMillis
        val defaultCal = Calendar.getInstance()
        defaultCal.add(Calendar.YEAR, -25)
        datePicker.updateDate(
            defaultCal.get(Calendar.YEAR),
            defaultCal.get(Calendar.MONTH),
            defaultCal.get(Calendar.DAY_OF_MONTH)
        )

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dob_label)
            .setView(dialogView)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val year = datePicker.year
                val month = datePicker.month
                val day = datePicker.dayOfMonth
                val iso = "%04d-%02d-%02d".format(year, month + 1, day)
                val today = Calendar.getInstance()
                var age = today.get(Calendar.YEAR) - year
                val m = today.get(Calendar.MONTH) - month
                if (m < 0 || (m == 0 && today.get(Calendar.DAY_OF_MONTH) < day)) age--

                if (age < 18) {
                    dobIso = null
                    dobSelected = false
                    dobInput.setText("")
                    dobErrorText.setText(R.string.dob_error_under_age)
                    dobErrorText.isVisible = true
                } else {
                    dobIso = iso
                    dobSelected = true
                    val display = "%02d/%02d/%04d".format(month + 1, day, year)
                    dobInput.setText(display)
                    dobErrorText.isVisible = false
                }
                validate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun registerUser(displayName: String, email: String, password: String, dateOfBirth: String) {
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
                        dateOfBirth = dateOfBirth,
                        consentAgreed = true,
                        consentTermsVersion = config?.min_required_terms_version,
                        consentPrivacyVersion = config?.min_required_privacy_version
                    )
                }
                
                // Store JWT tokens securely using Android Keystore
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                tokenManager.storeTokens(response.access_token, response.refresh_token)

                response.user?.id?.let {
                    FirebaseCrashlytics.getInstance().setUserId(it)
                    CrashlyticsPreference.setCurrentUser(requireContext(), it)
                }

                // Update auth state
                val authRepository = AuthRepository.Companion.getInstance(requireContext())
                authRepository.setSignedIn()
                
                // Store authentication state flag (tokens stored securely above)
                requireContext().getSharedPreferences(MainActivity.Companion.PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(MainActivity.Companion.KEY_HAS_IDENTITY, true)
                    .putBoolean(MainActivity.Companion.KEY_HAS_DATE_OF_BIRTH, true)
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
                AnalyticsLogger.logEvent(FirebaseAnalytics.Event.SIGN_UP, android.os.Bundle().apply {
                    putString(FirebaseAnalytics.Param.METHOD, "email")
                })
                callbacks?.onSignUp(displayName, email, password)
            } catch (e: ApiException) {
                val msg = e.message ?: ""
                if (e.code == 400 && msg.contains("18 or older", ignoreCase = true)) {
                    dobErrorText.text = getString(R.string.dob_error_under_age)
                    dobErrorText.isVisible = true
                    dobSelected = false
                    dobIso = null
                } else {
                    val errorMessage = when (e.code) {
                        409 -> "An account with this email already exists"
                        400 -> "Invalid registration data. Please check your inputs."
                        else -> msg.ifBlank { "Registration failed. Please try again." }
                    }
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
                }
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
