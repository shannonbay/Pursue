package app.getpursue.ui.fragments.onboarding

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Patterns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import app.getpursue.R
import com.google.android.material.textfield.TextInputEditText

/**
 * 4.1.3 Sign In with Email
 *
 * Allows users to sign in with email and password.
 * Includes option to continue with Google or create account.
 */
class SignInEmailFragment : Fragment() {

    interface Callbacks {
        fun onSignIn(email: String, password: String)
        fun onGoogleSignIn()
        fun onCreateAccount()
        fun onForgotPassword()
    }

    private var callbacks: Callbacks? = null

    private lateinit var emailInput: TextInputEditText
    private lateinit var passwordInput: TextInputEditText
    private lateinit var emailError: View
    private lateinit var passwordError: View
    private lateinit var signInButton: Button
    private lateinit var forgotPasswordButton: Button
    private lateinit var googleSignInButton: Button
    private lateinit var createAccountButton: Button

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
        return inflater.inflate(R.layout.fragment_signin_email, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        emailInput = view.findViewById(R.id.input_email)
        passwordInput = view.findViewById(R.id.input_password)
        emailError = view.findViewById(R.id.text_email_error)
        passwordError = view.findViewById(R.id.text_password_error)
        signInButton = view.findViewById(R.id.button_sign_in)
        forgotPasswordButton = view.findViewById(R.id.button_forgot_password)
        googleSignInButton = view.findViewById(R.id.button_google_signin)
        createAccountButton = view.findViewById(R.id.button_create_account)

        emailInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validateEmail()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        passwordInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                validatePassword()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        signInButton.setOnClickListener {
            if (validate()) {
                val email = emailInput.text?.toString() ?: ""
                val password = passwordInput.text?.toString() ?: ""
                setLoadingState(true)
                callbacks?.onSignIn(email, password)
            }
        }

        forgotPasswordButton.setOnClickListener {
            callbacks?.onForgotPassword()
        }

        googleSignInButton.setOnClickListener {
            callbacks?.onGoogleSignIn()
        }

        createAccountButton.setOnClickListener {
            callbacks?.onCreateAccount()
        }
    }

    private fun validateEmail(): Boolean {
        val email = emailInput.text?.toString() ?: ""
        val isValid = Patterns.EMAIL_ADDRESS.matcher(email).matches()
        emailError.isVisible = !isValid && email.isNotEmpty()
        return isValid
    }

    private fun validatePassword(): Boolean {
        val password = passwordInput.text?.toString() ?: ""
        val isValid = password.isNotEmpty()
        passwordError.isVisible = !isValid && password.isEmpty()
        return isValid
    }

    private fun validate(): Boolean {
        val emailValid = validateEmail()
        val passwordValid = validatePassword()
        return emailValid && passwordValid
    }

    /**
     * Set loading state for sign-in button.
     * 
     * @param isLoading True to show loading state, false to reset
     */
    fun setLoadingState(isLoading: Boolean) {
        signInButton.isEnabled = !isLoading
        signInButton.text = if (isLoading) {
            getString(R.string.signing_in)
        } else {
            getString(R.string.sign_in_button)
        }
    }

    companion object {
        fun newInstance(): SignInEmailFragment = SignInEmailFragment()
    }
}
