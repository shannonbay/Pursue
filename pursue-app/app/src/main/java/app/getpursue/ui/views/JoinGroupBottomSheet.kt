package app.getpursue.ui.views

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.fcm.FcmTopicManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.utils.HapticFeedbackUtils
import app.getpursue.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet for joining a group via invite code (spec 4.9.2).
 * Supports manual entry, paste, and scan (QR format: https://getpursue.app/invite/PURSUE-XXX-XXX).
 */
class JoinGroupBottomSheet : BottomSheetDialogFragment() {

    companion object {
        fun show(fragmentManager: FragmentManager) {
            JoinGroupBottomSheet().show(fragmentManager, "JoinGroupBottomSheet")
        }

        /**
         * Extracts PURSUE invite code from a scanned string (URL or bare code).
         * Supports: https://getpursue.app/join/PURSUE-XXX-XXX, https://getpursue.app/invite/PURSUE-XXX-XXX, or bare code.
         */
        internal fun parseInviteCodeFromScan(raw: String?): String? {
            val s = raw?.trim() ?: return null
            if (s.isEmpty()) return null
            if (s.contains("/join/")) {
                val afterJoin = s.substringAfterLast("/join/").substringBefore("?").trim()
                if (isValidPursueCode(afterJoin)) return afterJoin
                return null
            }
            if (s.contains("/invite/")) {
                val afterInvite = s.substringAfterLast("/invite/").substringBefore("?").trim()
                if (isValidPursueCode(afterInvite)) return afterInvite
                return null
            }
            return if (isValidPursueCode(s)) s else null
        }

        private fun isValidPursueCode(code: String): Boolean {
            if (!code.startsWith("PURSUE-")) return false
            return PURSUE_CODE_PATTERN.matches(code)
        }

        private val PURSUE_CODE_PATTERN = Regex("^PURSUE-[A-Za-z0-9]+(-[A-Za-z0-9]+)*$")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var inputLayout: TextInputLayout
    private lateinit var editCode: TextInputEditText
    private lateinit var buttonJoin: View

    private var pendingInviteCode: String? = null

    override fun onResume() {
        super.onResume()
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
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_join_group, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        inputLayout = view.findViewById(R.id.input_code)
        editCode = view.findViewById(R.id.edit_code)
        buttonJoin = view.findViewById(R.id.button_join)

        view.findViewById<View>(R.id.button_paste).setOnClickListener {
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip ?: return@setOnClickListener
            if (clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString() ?: return@setOnClickListener
                editCode.setText(text)
            }
        }

        view.findViewById<View>(R.id.button_scan).setOnClickListener {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()

            val scanner = GmsBarcodeScanning.getClient(requireContext(), options)

            scanner.startScan()
                .addOnSuccessListener { barcode: Barcode ->
                    val contents = barcode.rawValue
                    if (contents.isNullOrBlank()) return@addOnSuccessListener

                    val code = parseInviteCodeFromScan(contents)

                    if (code != null) {
                        HapticFeedbackUtils.vibrateClick(requireContext())
                        if (isAdded && !isStateSaved) {
                            editCode.setText(code)
                            inputLayout.error = null
                            // Fast-track to covenant affirmation
                            view?.post {
                                if (isAdded) showCovenant(code)
                            }
                        } else {
                            // Fragment is not ready, handled in onResume
                            pendingInviteCode = code
                        }
                    } else {
                        context?.let {
                            Toast.makeText(it, R.string.invite_code_invalid, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
        }

        view.findViewById<View>(R.id.button_cancel).setOnClickListener { dismiss() }

        buttonJoin.setOnClickListener { attemptJoin() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
        scope.launch {
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val token = tokenManager.getAccessToken()
                if (token == null) {
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    buttonJoin.isEnabled = true
                    return@launch
                }
                val response = withContext(Dispatchers.IO) {
                    ApiClient.joinGroup(token, code)
                }
                if (!isAdded) return@launch
                when (response.status) {
                    "pending" -> {
                        Toast.makeText(requireContext(), response.message, Toast.LENGTH_LONG).show()
                        parentFragmentManager.setFragmentResult("join_group_result", Bundle().apply {
                            putBoolean("refresh_needed", true)
                        })
                        dismiss()
                    }
                    else -> {
                        Toast.makeText(requireContext(), getString(R.string.join_group_success), Toast.LENGTH_SHORT).show()
                        // Subscribe to FCM topics for the new group
                        val groupId = response.group.id
                        scope.launch {
                            FcmTopicManager.subscribeToGroupTopics(requireContext(), groupId)
                        }
                        dismiss()
                        val group = response.group
                        val intent = Intent(requireContext(), GroupDetailActivity::class.java).apply {
                            putExtra(GroupDetailActivity.Companion.EXTRA_GROUP_ID, group.id)
                            putExtra(GroupDetailActivity.Companion.EXTRA_GROUP_NAME, group.name)
                            putExtra(GroupDetailActivity.Companion.EXTRA_GROUP_HAS_ICON, false)
                        }
                        startActivity(intent)
                    }
                }
            } catch (e: ApiException) {
                if (isAdded) {
                    buttonJoin.isEnabled = true
                    Toast.makeText(requireContext(), e.message ?: getString(R.string.join_group_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    buttonJoin.isEnabled = true
                    Toast.makeText(requireContext(), getString(R.string.join_group_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
