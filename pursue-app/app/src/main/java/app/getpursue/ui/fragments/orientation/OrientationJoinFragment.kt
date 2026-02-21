package app.getpursue.ui.fragments.orientation

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.fcm.FcmTopicManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.activities.OrientationActivity
import app.getpursue.ui.views.CovenantBottomSheet
import app.getpursue.ui.views.JoinGroupBottomSheet
import app.getpursue.utils.HapticFeedbackUtils
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OrientationJoinFragment : Fragment() {

    private lateinit var inputLayout: TextInputLayout
    private lateinit var editCode: TextInputEditText
    private lateinit var buttonJoin: MaterialButton
    private lateinit var buttonScanQr: MaterialButton
    private lateinit var buttonSkip: MaterialButton
    private lateinit var buttonNoCode: MaterialButton

    private var pendingInviteCode: String? = null
    private var pendingVibrate = false

    override fun onResume() {
        super.onResume()
        if (pendingVibrate) {
            pendingVibrate = false
            view?.let { HapticFeedbackUtils.vibrateSuccess(it) }
        }
        // If we have a code from a scan that happened while we weren't ready, show it now
        pendingInviteCode?.let { code ->
            if (isAdded && !isStateSaved) {
                editCode.setText(code)
                inputLayout.error = null
                showCovenant(code)
                pendingInviteCode = null
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_orientation_join, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        inputLayout = view.findViewById(R.id.input_invite_code)
        editCode = view.findViewById(R.id.edit_invite_code)
        buttonJoin = view.findViewById(R.id.button_join)
        buttonScanQr = view.findViewById(R.id.button_scan_qr)
        buttonSkip = view.findViewById(R.id.button_skip)
        buttonNoCode = view.findViewById(R.id.button_no_code)

        // Setup progress dots for step 1
        setupProgressDots(view.findViewById(R.id.progress_dots), 1)

        // Enable join button when text is entered
        editCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                buttonJoin.isEnabled = !s.isNullOrBlank()
                inputLayout.error = null
            }
        })

        buttonJoin.setOnClickListener { attemptJoin() }

        buttonScanQr.setOnClickListener {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()

            val scanner = GmsBarcodeScanning.getClient(requireContext(), options)

            scanner.startScan()
                .addOnSuccessListener { barcode: Barcode ->
                    val contents = barcode.rawValue
                    if (contents.isNullOrBlank()) return@addOnSuccessListener
                    val code = JoinGroupBottomSheet.parseInviteCodeFromScan(contents)
                    if (code != null) {
                        if (isAdded && !isStateSaved) {
                            view?.let { HapticFeedbackUtils.vibrateSuccess(it) }
                            editCode.setText(code)
                            inputLayout.error = null
                            // Fast-track to covenant affirmation
                            view?.post {
                                if (isAdded) showCovenant(code)
                            }
                        } else {
                            // Fragment is not ready, handled in onResume
                            pendingVibrate = true
                            pendingInviteCode = code
                        }
                    } else {
                        view?.let { HapticFeedbackUtils.vibrateError(it) }
                        Toast.makeText(requireContext(), getString(R.string.invite_code_invalid), Toast.LENGTH_SHORT).show()
                    }
                }
                .addOnFailureListener { e: Exception ->
                    if (!isAdded) return@addOnFailureListener
                    view?.let { HapticFeedbackUtils.vibrateError(it) }
                    Toast.makeText(requireContext(), "Scan failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }

        // Skip and "no code" both go to Step 2
        buttonSkip.setOnClickListener { goToStep2() }
        buttonNoCode.setOnClickListener { goToStep2() }

        // System back exits orientation to home
        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    (requireActivity() as OrientationActivity).completeOrientation()
                }
            }
        )
    }

    private fun attemptJoin() {
        val code = editCode.text?.toString()?.trim() ?: ""
        if (code.isEmpty()) {
            inputLayout.error = getString(R.string.invite_code_required)
            return
        }
        inputLayout.error = null
        showCovenant(code)
    }

    private fun showCovenant(code: String) {
        if (!isAdded || isStateSaved) return

        val covenant = CovenantBottomSheet.newInstance(isChallenge = false)
        covenant.setCovenantListener(object : CovenantBottomSheet.CovenantListener {
            override fun onCovenantAccepted() {
                performJoin(code)
            }
        })
        covenant.show(childFragmentManager, "CovenantBottomSheet")
    }

    private fun performJoin(code: String) {
        buttonJoin.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SecureTokenManager.getInstance(requireContext()).getAccessToken()
                if (token == null) {
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    buttonJoin.isEnabled = true
                    return@launch
                }
                val response = withContext(Dispatchers.IO) {
                    ApiClient.joinGroup(token, code)
                }
                if (!isAdded) return@launch

                // Subscribe to FCM topics
                val groupId = response.group.id
                launch { FcmTopicManager.subscribeToGroupTopics(requireContext(), groupId) }

                val group = response.group
                val intent = Intent(requireContext(), GroupDetailActivity::class.java).apply {
                    putExtra(GroupDetailActivity.EXTRA_GROUP_ID, group.id)
                    putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, group.name)
                    putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, false)
                }
                (requireActivity() as OrientationActivity).completeOrientation(intent)
            } catch (e: ApiException) {
                if (!isAdded) return@launch
                buttonJoin.isEnabled = true
                if (e.code == 409) {
                    Toast.makeText(requireContext(), getString(R.string.join_group_success), Toast.LENGTH_SHORT).show()
                    (requireActivity() as OrientationActivity).completeOrientation()
                } else {
                    inputLayout.error = getString(R.string.orientation_invite_code_error)
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                buttonJoin.isEnabled = true
                inputLayout.error = getString(R.string.join_group_failed)
            }
        }
    }

    private fun goToStep2() {
        requireActivity().supportFragmentManager.commit {
            replace(R.id.fragment_container, OrientationChallengeFragment.newInstance())
            addToBackStack(null)
        }
    }

    companion object {
        fun newInstance(): OrientationJoinFragment = OrientationJoinFragment()
    }
}
