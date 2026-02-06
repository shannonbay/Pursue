package app.getpursue.data.auth

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Repository for managing authentication state.
 * 
 * Provides StateFlow for observing auth state changes and handles sign out.
 */
class AuthRepository private constructor(context: Context) {
    
    private val tokenManager = SecureTokenManager.getInstance(context)
    
    private val _authState = MutableStateFlow<AuthState>(
        if (tokenManager.hasTokens()) AuthState.SignedIn 
        else AuthState.SignedOut
    )
    
    /**
     * Observable auth state.
     * Emits SignedIn when user has valid tokens, SignedOut otherwise.
     */
    val authState: StateFlow<AuthState> = _authState.asStateFlow()
    
    /**
     * Sign out the user by clearing tokens and updating state.
     */
    fun signOut() {
        tokenManager.clearTokens()
        _authState.value = AuthState.SignedOut
    }
    
    /**
     * Check if user is currently signed in.
     */
    fun isSignedIn(): Boolean {
        return tokenManager.hasTokens()
    }
    
    /**
     * Update auth state to SignedIn (called after successful login/register).
     */
    fun setSignedIn() {
        _authState.value = AuthState.SignedIn
    }
    
    companion object {
        @Volatile
        private var INSTANCE: AuthRepository? = null
        
        /**
         * Get the singleton instance of AuthRepository.
         * Thread-safe lazy initialization.
         */
        fun getInstance(context: Context): AuthRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AuthRepository(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}

/**
 * Authentication state.
 */
sealed class AuthState {
    object SignedIn : AuthState()
    object SignedOut : AuthState()
}
