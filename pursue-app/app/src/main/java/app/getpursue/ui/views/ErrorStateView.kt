package app.getpursue.ui.views

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.annotation.StringRes
import app.getpursue.data.network.ApiException
import app.getpursue.R
import com.google.android.material.button.MaterialButton

/**
 * Reusable error state view component.
 * Supports different error types: network, server, timeout, unauthorized.
 */
class ErrorStateView private constructor(private val rootView: View) {
    
    private val errorIcon: TextView = rootView.findViewById(R.id.error_icon)
    private val errorTitle: TextView = rootView.findViewById(R.id.error_title)
    private val errorMessage: TextView = rootView.findViewById(R.id.error_message)
    private val retryButton: MaterialButton =
        rootView.findViewById(R.id.retry_button)
    
    fun setErrorType(errorType: ErrorType) {
        when (errorType) {
            ErrorType.NETWORK -> {
                errorIcon.text = "📶"
                errorTitle.setText(R.string.error_network_title)
                errorMessage.setText(R.string.error_network_message)
            }
            ErrorType.SERVER -> {
                errorIcon.text = "⚠️"
                errorTitle.setText(R.string.error_server_title)
                errorMessage.setText(R.string.error_server_message)
            }
            ErrorType.TIMEOUT -> {
                errorIcon.text = "⏱️"
                errorTitle.setText(R.string.error_timeout_title)
                errorMessage.setText(R.string.error_timeout_message)
            }
            ErrorType.UNAUTHORIZED -> {
                errorIcon.text = "🔒"
                errorTitle.setText(R.string.error_unauthorized_title)
                errorMessage.setText(R.string.error_unauthorized_message)
            }
            ErrorType.GENERIC -> {
                errorIcon.text = "⚠️"
                errorTitle.setText(R.string.error_failed_to_load_groups)
                errorMessage.text = ""
            }
            ErrorType.PENDING_APPROVAL -> {
                errorIcon.text = "⏳"
                errorTitle.setText(R.string.error_pending_approval_title)
                errorMessage.setText(R.string.error_pending_approval_message)
            }
            ErrorType.FORBIDDEN -> {
                errorIcon.text = "🔒"
                errorTitle.setText(R.string.error_forbidden_title)
                errorMessage.setText(R.string.error_forbidden_message)
            }
        }
    }
    
    fun setOnRetryClickListener(listener: View.OnClickListener) {
        retryButton.setOnClickListener(listener)
    }
    
    fun setCustomMessage(@StringRes titleRes: Int, @StringRes messageRes: Int) {
        errorTitle.setText(titleRes)
        errorMessage.setText(messageRes)
    }
    
    enum class ErrorType {
        NETWORK,
        SERVER,
        TIMEOUT,
        UNAUTHORIZED,
        GENERIC,
        PENDING_APPROVAL,
        FORBIDDEN
    }

    companion object {
        /**
         * Maps ApiException to ErrorType. For 403, distinguishes PENDING_APPROVAL (pending member)
         * from FORBIDDEN (non-member) via backend errorCode or message content.
         */
        fun errorTypeFromApiException(e: ApiException): ErrorType = when (e.code) {
            401 -> ErrorType.UNAUTHORIZED
            403 -> {
                if (e.errorCode == "PENDING_APPROVAL") {
                    ErrorType.PENDING_APPROVAL
                } else {
                    val msg = e.message?.lowercase() ?: ""
                    if (msg.contains("pending_approval") || msg.contains("pending approval")) {
                        ErrorType.PENDING_APPROVAL
                    } else {
                        ErrorType.FORBIDDEN
                    }
                }
            }
            0 -> ErrorType.NETWORK
            500, 502, 503 -> ErrorType.SERVER
            504 -> ErrorType.TIMEOUT
            else -> ErrorType.SERVER
        }

        fun inflate(inflater: LayoutInflater, parent: ViewGroup): ErrorStateView {
            val view = inflater.inflate(R.layout.error_state, parent, false)
            return ErrorStateView(view)
        }
        
        fun inflate(inflater: LayoutInflater, parent: ViewGroup, attachToParent: Boolean): ErrorStateView {
            val view = inflater.inflate(R.layout.error_state, parent, attachToParent)
            return ErrorStateView(view)
        }
    }
    
    val view: View
        get() = rootView
}
