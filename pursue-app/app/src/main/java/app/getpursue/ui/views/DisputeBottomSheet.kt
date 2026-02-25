package app.getpursue.ui.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet for disputing a content removal.
 * User provides an optional explanation; calls POST /api/disputes on submission.
 */
class DisputeBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_CONTENT_TYPE = "content_type"
        private const val ARG_CONTENT_ID = "content_id"

        fun show(fm: FragmentManager, contentType: String, contentId: String) {
            DisputeBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CONTENT_TYPE, contentType)
                    putString(ARG_CONTENT_ID, contentId)
                }
            }.show(fm, "DisputeBottomSheet")
        }
    }

    private var contentType: String? = null
    private var contentId: String? = null
    private lateinit var explanationInput: TextInputEditText
    private lateinit var buttonSubmit: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        contentType = arguments?.getString(ARG_CONTENT_TYPE)
        contentId = arguments?.getString(ARG_CONTENT_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_dispute, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        explanationInput = view.findViewById(R.id.explanation_input)
        buttonSubmit = view.findViewById(R.id.button_submit)
        val buttonCancel = view.findViewById<MaterialButton>(R.id.button_cancel)

        buttonCancel.setOnClickListener { dismiss() }
        buttonSubmit.setOnClickListener { submitDispute() }
    }

    private fun submitDispute() {
        val type = contentType ?: return
        val id = contentId ?: return
        val explanation = explanationInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }

        buttonSubmit.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SecureTokenManager.getInstance(requireContext()).getAccessToken()
                if (token == null) {
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    buttonSubmit.isEnabled = true
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    ApiClient.createDispute(token, type, id, explanation)
                }

                if (isAdded) {
                    dismiss()
                    Toast.makeText(requireContext(), getString(R.string.dispute_submitted), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    buttonSubmit.isEnabled = true
                    Toast.makeText(requireContext(), getString(R.string.dispute_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
