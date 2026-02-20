package app.getpursue.ui.fragments.onboarding

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import app.getpursue.R

/**
 * 4.1.1 Welcome / Splash Screen
 *
 * Static layout, no animations, with:
 * - App logo / title
 * - Subtitle “Achieve goals together”
 * - Primary “Get Started” button
 * - Tertiary “Restore Account” text button
 */
class WelcomeFragment : Fragment() {

    interface Callbacks {
        fun onGoogleSignIn()
        fun onSignInWithEmail()
        fun onCreateAccount()
    }

    private var callbacks: Callbacks? = null

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
        return inflater.inflate(R.layout.fragment_welcome, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<View>(R.id.button_google_signin).setOnClickListener {
            callbacks?.onGoogleSignIn()
        }

        view.findViewById<View>(R.id.button_signin_email).setOnClickListener {
            callbacks?.onSignInWithEmail()
        }

        view.findViewById<View>(R.id.button_create_account).setOnClickListener {
            callbacks?.onCreateAccount()
        }
    }

    companion object {
        fun newInstance(): WelcomeFragment = WelcomeFragment()
    }
}