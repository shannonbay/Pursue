package app.getpursue.data.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import app.getpursue.R
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.api.ApiException

/**
 * Helper class for Google Sign-In authentication.
 * 
 * Handles Google Sign-In SDK integration and provides a simple interface
 * for starting sign-in flow and processing results.
 */
class GoogleSignInHelper(private val context: Context) {
    
    private val googleSignInClient: GoogleSignInClient
    
    init {
        val clientId = context.getString(R.string.google_client_id)
        
        // Log client ID for debugging (first 20 chars only for security)
        Log.d("GoogleSignInHelper", "Initializing with client ID: ${clientId.take(20)}...")
        
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(clientId)
            .requestEmail()
            .requestProfile()
            .build()
            
        googleSignInClient = GoogleSignIn.getClient(context, gso)
    }
    
    /**
     * Get the Intent to start Google Sign-In flow.
     * 
     * @return Intent that can be used with startActivityForResult()
     */
    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    /**
     * Sign out from Google so the next sign-in can choose a different account.
     */
    fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                Log.d(TAG, "Google sign-out completed")
            } else {
                Log.w(TAG, "Google sign-out failed", task.exception)
            }
        }
    }

    /**
     * Handle the result from Google Sign-In activity.
     *
     * @param data Intent data returned from Google Sign-In activity
     * @return GoogleSignInResult indicating success, error, or cancellation
     */
    fun handleSignInResult(data: Intent?): GoogleSignInResult {
        Log.d(TAG, "handleSignInResult called, data: ${data != null}")

        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)

            if (account != null && account.idToken != null) {
                Log.d(TAG, "Sign-in successful, account email: ${account.email}")
                GoogleSignInResult.Success(account)
            } else {
                Log.e(TAG, "Sign-in returned null account or token. Account: ${account != null}, Token: ${account?.idToken != null}")
                GoogleSignInResult.Error("No ID token received from Google")
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Google Sign-In ApiException: statusCode=${e.statusCode}, message=${e.message}", e)

            when (e.statusCode) {
                ConnectionResult.NETWORK_ERROR -> {
                    Log.e(TAG, "Network error (code 7) - emulator may not have internet access or Google services blocked")
                    GoogleSignInResult.Error(
                        "Network error. Please check your internet connection and try again."
                    )
                }
                ConnectionResult.INTERNAL_ERROR -> {
                    Log.e(TAG, "Internal error (code 8)")
                    GoogleSignInResult.Error("Internal error. Please try again.")
                }
                10 -> { // DEVELOPER_ERROR - Configuration issue
                    Log.e(TAG, "Developer error (code 10) - OAuth configuration issue")
                    GoogleSignInResult.Error(
                        "Google Sign-In configuration error. " +
                        "Please check: SHA-1 fingerprint is registered in Google Cloud Console, " +
                        "and you're using the Web Application Client ID (not Android Client ID) for requestIdToken(). " +
                        "Error details: ${e.message ?: "Code 10"}"
                    )
                }
                12501 -> { // SIGN_IN_CANCELLED - User cancelled the sign-in flow
                    Log.d(TAG, "User cancelled sign-in (code 12501)")
                    GoogleSignInResult.Cancelled
                }
                12502 -> { // SIGN_IN_FAILED - Sign-in failed, user may need to try again
                    Log.e(TAG, "Sign-in failed (code 12502)")
                    GoogleSignInResult.Error("Google Sign-In failed. Please try again.")
                }
                12500 -> { // SIGN_IN_REQUIRED - User needs to sign in
                    Log.d(TAG, "Sign-in required (code 12500)")
                    GoogleSignInResult.Error("Please sign in with your Google account.")
                }
                else -> {
                    Log.e(TAG, "Unknown error code: ${e.statusCode}")
                    GoogleSignInResult.Error(
                        "Sign in failed (Error ${e.statusCode}): ${e.message ?: "Unknown error"}"
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected exception during sign-in", e)
            GoogleSignInResult.Error(e.message ?: "Unexpected error during sign-in")
        }
    }

    companion object {
        private const val TAG = "GoogleSignInHelper"
    }
}

/**
 * Sealed class representing the result of Google Sign-In operation.
 */
sealed class GoogleSignInResult {
    /**
     * Sign-in succeeded. Contains the GoogleSignInAccount with ID token.
     */
    data class Success(val account: GoogleSignInAccount) : GoogleSignInResult()
    
    /**
     * Sign-in failed with an error message.
     */
    data class Error(val message: String) : GoogleSignInResult()
    
    /**
     * User cancelled the sign-in flow.
     */
    object Cancelled : GoogleSignInResult()
}
