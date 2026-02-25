package app.getpursue.ui.views

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioGroup
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bottom sheet for reporting a progress entry.
 * Shows 4 reason options; calls POST /api/reports on submission.
 */
class ReportEntryBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_ENTRY_ID = "entry_id"
        const val RESULT_REPORTED = "report_entry_result"
        const val KEY_ENTRY_ID = "reported_entry_id"

        fun show(fm: FragmentManager, entryId: String) {
            ReportEntryBottomSheet().apply {
                arguments = Bundle().apply { putString(ARG_ENTRY_ID, entryId) }
            }.show(fm, "ReportEntryBottomSheet")
        }
    }

    private var entryId: String? = null
    private lateinit var reasonGroup: RadioGroup
    private lateinit var buttonReport: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        entryId = arguments?.getString(ARG_ENTRY_ID)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_report_entry, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        reasonGroup = view.findViewById(R.id.reason_group)
        buttonReport = view.findViewById(R.id.button_report)
        val buttonCancel = view.findViewById<MaterialButton>(R.id.button_cancel)

        buttonCancel.setOnClickListener { dismiss() }

        reasonGroup.setOnCheckedChangeListener { _, checkedId ->
            buttonReport.isEnabled = checkedId != -1
        }

        buttonReport.setOnClickListener { submitReport() }
    }

    private fun submitReport() {
        val id = entryId ?: return
        val reason = when (reasonGroup.checkedRadioButtonId) {
            R.id.reason_inappropriate -> getString(R.string.report_reason_inappropriate)
            R.id.reason_harassment -> getString(R.string.report_reason_harassment)
            R.id.reason_spam -> getString(R.string.report_reason_spam)
            R.id.reason_other -> getString(R.string.report_reason_other)
            else -> return
        }

        buttonReport.isEnabled = false

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val token = SecureTokenManager.getInstance(requireContext()).getAccessToken()
                if (token == null) {
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    buttonReport.isEnabled = true
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    ApiClient.reportContent(token, "progress_entry", id, reason)
                }

                if (isAdded) {
                    parentFragmentManager.setFragmentResult(
                        RESULT_REPORTED,
                        Bundle().apply { putString(KEY_ENTRY_ID, id) }
                    )
                    dismiss()
                    Toast.makeText(requireContext(), getString(R.string.report_submitted), Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
                if (isAdded) {
                    dismiss()
                    if (e.code == 409) {
                        Toast.makeText(requireContext(), getString(R.string.report_already_submitted), Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), getString(R.string.report_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (isAdded) {
                    buttonReport.isEnabled = true
                    Toast.makeText(requireContext(), getString(R.string.report_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
