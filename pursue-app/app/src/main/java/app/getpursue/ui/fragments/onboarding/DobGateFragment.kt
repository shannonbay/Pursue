package app.getpursue.ui.fragments.onboarding

import android.widget.DatePicker
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.network.ApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

/**
 * Age gate fragment shown when a user has no date of birth on file.
 *
 * Used in two contexts:
 *  - Inside OnboardingActivity (after Google sign-in with no DOB)
 *  - Inside DobGateActivity (for already-signed-in users on app update)
 */
class DobGateFragment : Fragment() {

    interface Callbacks {
        /** Called when user proves they are 18+ and DOB is stored on the server. */
        fun onDobVerified()
        /** Called when user is under 18. The host should sign out and show a message. */
        fun onDobUnderAge()
    }

    private var callbacks: Callbacks? = null

    private lateinit var dobPicker: DatePicker
    private lateinit var dobErrorText: TextView
    private lateinit var continueButton: Button

    private var dobIso: String? = null
    private var dobValid = false

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
    ): View = inflater.inflate(R.layout.fragment_dob_gate, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        dobPicker = view.findViewById(R.id.date_picker_dob_gate)
        dobErrorText = view.findViewById(R.id.tv_dob_gate_error)
        continueButton = view.findViewById(R.id.btn_dob_gate_continue)

        val today = Calendar.getInstance()
        val minCal = Calendar.getInstance().also { it.add(Calendar.YEAR, -120) }
        dobPicker.maxDate = today.timeInMillis
        dobPicker.minDate = minCal.timeInMillis

        val defaultCal = Calendar.getInstance().also { it.add(Calendar.YEAR, -17) }
        dobPicker.init(
            defaultCal.get(Calendar.YEAR),
            defaultCal.get(Calendar.MONTH),
            defaultCal.get(Calendar.DAY_OF_MONTH)
        ) { _, year, month, day ->
            val iso = "%04d-%02d-%02d".format(year, month + 1, day)
            val now = Calendar.getInstance()
            var age = now.get(Calendar.YEAR) - year
            val m = now.get(Calendar.MONTH) - month
            if (m < 0 || (m == 0 && now.get(Calendar.DAY_OF_MONTH) < day)) age--

            if (age < 18) {
                dobIso = null
                dobValid = false
                dobErrorText.setText(R.string.dob_error_under_age)
                dobErrorText.isVisible = true
                continueButton.isEnabled = false
            } else {
                dobIso = iso
                dobValid = true
                dobErrorText.isVisible = false
                continueButton.isEnabled = true
            }
        }

        // Initial state: 17-year-old default → invalid, button disabled
        dobIso = null
        dobValid = false
        continueButton.isEnabled = false

        continueButton.setOnClickListener {
            val iso = dobIso ?: return@setOnClickListener
            submitDob(iso)
        }
    }

    private fun submitDob(iso: String) {
        continueButton.isEnabled = false
        continueButton.text = getString(R.string.dob_gate_continue)

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ApiClient.updateDateOfBirth(iso)
                }
                // Persist flag so this gate is not shown again on subsequent launches
                requireContext().getSharedPreferences(
                    app.getpursue.ui.activities.MainActivity.PREFS_NAME,
                    Context.MODE_PRIVATE
                ).edit().putBoolean(
                    app.getpursue.ui.activities.MainActivity.KEY_HAS_DATE_OF_BIRTH, true
                ).apply()

                callbacks?.onDobVerified()
            } catch (e: Exception) {
                Log.e("DobGateFragment", "Failed to submit DOB", e)
                Toast.makeText(requireContext(), "Failed to save date of birth. Please try again.", Toast.LENGTH_LONG).show()
                continueButton.isEnabled = dobValid
            }
        }
    }

    companion object {
        fun newInstance(): DobGateFragment = DobGateFragment()
    }
}
