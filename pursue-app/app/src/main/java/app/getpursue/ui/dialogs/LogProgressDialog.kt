package app.getpursue.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import app.getpursue.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Dialog for logging or editing progress on numeric goals.
 * 
 * Implements the number entry dialog from section 4.4 of the UI spec.
 * Supports both logging new progress and editing existing progress entries.
 */
class LogProgressDialog : DialogFragment() {

    interface LogProgressListener {
        fun onLogProgress(value: Double, note: String?)
    }

    interface DeleteProgressListener {
        fun onDeleteProgress()
    }

    interface JournalLogProgressListener {
        fun onLogJournalProgress(logTitle: String, note: String?)
    }

    private var listener: LogProgressListener? = null
    private var deleteListener: DeleteProgressListener? = null
    private var journalListener: JournalLogProgressListener? = null
    private var goalTitle: String = ""
    private var unit: String? = null
    private var isEditMode: Boolean = false
    private var currentValue: Double? = null
    private var currentNote: String? = null
    private var isJournal: Boolean = false
    private var logTitlePrompt: String? = null
    private var currentLogTitle: String? = null

    companion object {
        private const val ARG_GOAL_TITLE = "goal_title"
        private const val ARG_UNIT = "unit"
        private const val ARG_IS_EDIT_MODE = "is_edit_mode"
        private const val ARG_CURRENT_VALUE = "current_value"
        private const val ARG_CURRENT_NOTE = "current_note"
        private const val ARG_IS_JOURNAL = "is_journal"
        private const val ARG_LOG_TITLE_PROMPT = "log_title_prompt"
        private const val ARG_CURRENT_LOG_TITLE = "current_log_title"

        fun newInstance(
            goalTitle: String,
            unit: String? = null,
            isEditMode: Boolean = false,
            currentValue: Double? = null,
            currentNote: String? = null
        ): LogProgressDialog {
            return LogProgressDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_GOAL_TITLE, goalTitle)
                    putString(ARG_UNIT, unit)
                    putBoolean(ARG_IS_EDIT_MODE, isEditMode)
                    currentValue?.let { putDouble(ARG_CURRENT_VALUE, it) }
                    putString(ARG_CURRENT_NOTE, currentNote)
                }
            }
        }

        fun newJournalInstance(
            goalTitle: String,
            logTitlePrompt: String? = null,
            isEditMode: Boolean = false,
            currentLogTitle: String? = null,
            currentNote: String? = null
        ): LogProgressDialog {
            return LogProgressDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_GOAL_TITLE, goalTitle)
                    putBoolean(ARG_IS_JOURNAL, true)
                    putBoolean(ARG_IS_EDIT_MODE, isEditMode)
                    putString(ARG_LOG_TITLE_PROMPT, logTitlePrompt)
                    putString(ARG_CURRENT_LOG_TITLE, currentLogTitle)
                    putString(ARG_CURRENT_NOTE, currentNote)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        goalTitle = arguments?.getString(ARG_GOAL_TITLE) ?: ""
        unit = arguments?.getString(ARG_UNIT)
        isEditMode = arguments?.getBoolean(ARG_IS_EDIT_MODE, false) ?: false
        currentValue = arguments?.getDouble(ARG_CURRENT_VALUE)?.takeIf { !it.isNaN() }
        currentNote = arguments?.getString(ARG_CURRENT_NOTE)
        isJournal = arguments?.getBoolean(ARG_IS_JOURNAL, false) ?: false
        logTitlePrompt = arguments?.getString(ARG_LOG_TITLE_PROMPT)
        currentLogTitle = arguments?.getString(ARG_CURRENT_LOG_TITLE)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_log_progress, null)
        
        setupDialogContent(view)
        
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .create()
        
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        // Return null since we're using onCreateDialog
        return null
    }
    
    private fun setupDialogContent(view: View) {
        val titleText = view.findViewById<TextView>(R.id.dialog_title)
        val journalPromptText = view.findViewById<TextView>(R.id.text_journal_prompt)
        val logTitleInputLayout = view.findViewById<TextInputLayout>(R.id.input_log_title_layout)
        val logTitleInput = view.findViewById<TextInputEditText>(R.id.input_log_title)
        val valueInputLayout = view.findViewById<TextInputLayout>(R.id.value_input_layout)
        val valueInput = view.findViewById<TextInputEditText>(R.id.value_input)
        val noteInputLayout = view.findViewById<TextInputLayout>(R.id.note_input_layout)
        val noteInput = view.findViewById<TextInputEditText>(R.id.note_input)
        val cancelButton = view.findViewById<MaterialButton>(R.id.button_cancel)
        val logButton = view.findViewById<MaterialButton>(R.id.button_log)
        val deleteButton = view.findViewById<MaterialButton>(R.id.button_delete)

        // Set title based on mode
        titleText.text = if (isEditMode) {
            getString(R.string.edit_progress_title)
        } else {
            goalTitle
        }

        // Set button text based on mode
        logButton.text = if (isEditMode) {
            getString(R.string.save)
        } else {
            getString(R.string.log)
        }

        // Show/hide delete button based on edit mode
        deleteButton.visibility = if (isEditMode) View.VISIBLE else View.GONE

        // Note field (optional) - shared across both modes
        noteInputLayout.hint = getString(R.string.log_progress_note_label)

        if (isJournal) {
            // Journal mode: show journal section, hide value input
            valueInputLayout.visibility = View.GONE

            // Show prompt heading
            val promptText = logTitlePrompt?.takeIf { it.isNotBlank() }
                ?: getString(R.string.log_title_hint)
            journalPromptText.text = promptText
            journalPromptText.visibility = View.VISIBLE
            logTitleInputLayout.visibility = View.VISIBLE

            // Pre-fill title if in edit mode
            if (isEditMode && !currentLogTitle.isNullOrEmpty()) {
                logTitleInput.setText(currentLogTitle)
                logButton.isEnabled = true
            } else {
                logButton.isEnabled = false
            }

            // Pre-fill note if in edit mode
            if (isEditMode && currentNote != null) {
                noteInput.setText(currentNote)
            }

            // Enable log button only when title has content
            logTitleInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    logButton.isEnabled = !s.isNullOrBlank()
                }
            })

            logTitleInput.requestFocus()

            logButton.setOnClickListener {
                val titleText2 = logTitleInput.text?.toString()?.trim()
                if (titleText2.isNullOrEmpty()) {
                    logTitleInputLayout.error = getString(R.string.log_title_required)
                    return@setOnClickListener
                }
                logTitleInputLayout.error = null
                val note = noteInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                journalListener?.onLogJournalProgress(titleText2, note)
                dialog?.dismiss()
            }
        } else {
            // Numeric/binary/duration mode
            journalPromptText.visibility = View.GONE
            logTitleInputLayout.visibility = View.GONE

            // Pre-fill values if in edit mode
            if (isEditMode && currentValue != null) {
                valueInput.setText(currentValue.toString())
                logButton.isEnabled = true
            }
            if (isEditMode && currentNote != null) {
                noteInput.setText(currentNote)
            }

            // Set up unit label if provided
            val unitLabel = if (unit != null) {
                getString(R.string.log_progress_unit_label, unit)
            } else {
                getString(R.string.log_progress_value_label)
            }
            valueInputLayout.hint = unitLabel

            // Auto-focus and show keyboard
            valueInput.requestFocus()
            valueInput.setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    logButton.performClick()
                    true
                } else {
                    false
                }
            }

            // Validate input
            valueInput.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    validateInput(valueInput, valueInputLayout)
                    updateLogButtonState(valueInput, logButton)
                }
            })

            // Initially disable log button (unless in edit mode with pre-filled value)
            if (!isEditMode || currentValue == null) {
                logButton.isEnabled = false
            }

            logButton.setOnClickListener {
                val valueText = valueInput.text?.toString()?.trim()
                if (valueText.isNullOrEmpty()) {
                    valueInputLayout.error = getString(R.string.log_progress_value_required)
                    return@setOnClickListener
                }

                val value = try {
                    val parsed = valueText.toDouble()
                    if (parsed < 0) {
                        valueInputLayout.error = getString(R.string.log_progress_value_negative)
                        return@setOnClickListener
                    }
                    if (parsed > 999999.99) {
                        valueInputLayout.error = getString(R.string.log_progress_value_too_large)
                        return@setOnClickListener
                    }
                    parsed
                } catch (e: NumberFormatException) {
                    valueInputLayout.error = getString(R.string.log_progress_value_invalid)
                    return@setOnClickListener
                }

                val note = noteInput.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
                listener?.onLogProgress(value, note)
                dialog?.dismiss()
            }
        }

        cancelButton.setOnClickListener {
            dialog?.dismiss()
        }

        // Delete button click handler
        deleteButton.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.delete_progress_confirm))
                .setMessage(getString(R.string.delete_progress_message))
                .setPositiveButton(getString(R.string.delete_progress_confirm)) { _, _ ->
                    deleteListener?.onDeleteProgress()
                    dialog?.dismiss()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
                .apply {
                    getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
                        ContextCompat.getColor(requireContext(), R.color.secondary)
                    )
                }
        }
    }

    private fun validateInput(input: TextInputEditText, layout: TextInputLayout) {
        val text = input.text?.toString()?.trim()
        if (text.isNullOrEmpty()) {
            layout.error = null
            return
        }

        try {
            val value = text.toDouble()
            when {
                value < 0 -> layout.error = getString(R.string.log_progress_value_negative)
                value > 999999.99 -> layout.error = getString(R.string.log_progress_value_too_large)
                else -> layout.error = null
            }
        } catch (e: NumberFormatException) {
            layout.error = getString(R.string.log_progress_value_invalid)
        }
    }

    private fun updateLogButtonState(valueInput: TextInputEditText, logButton: MaterialButton) {
        val text = valueInput.text?.toString()?.trim()
        val isValid = !text.isNullOrEmpty() && try {
            val value = text.toDouble()
            value >= 0 && value <= 999999.99
        } catch (e: NumberFormatException) {
            false
        }
        logButton.isEnabled = isValid
    }

    fun setLogProgressListener(listener: LogProgressListener) {
        this.listener = listener
    }

    fun setDeleteProgressListener(listener: DeleteProgressListener) {
        this.deleteListener = listener
    }

    fun setJournalLogProgressListener(listener: JournalLogProgressListener) {
        this.journalListener = listener
    }
}
