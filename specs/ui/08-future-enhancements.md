## 8. Future Enhancements

### 8.1 Android Implementation Notes (Google Sign-In)

**Dependencies (build.gradle):**
```gradle
dependencies {
    // Google Sign-In
    implementation 'com.google.android.gms:play-services-auth:20.7.0'
    
    // Existing dependencies
    implementation 'androidx.compose.material3:material3:1.1.0'
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    // ...
}
```

**Google Sign-In Configuration:**
1. Add SHA-1 fingerprint to Firebase Console
2. Download google-services.json
3. Add to app/ directory

**Kotlin Implementation:**
```kotlin
// GoogleSignInHelper.kt
class GoogleSignInHelper(private val context: Context) {
    
    private val googleSignInClient: GoogleSignInClient
    
    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.google_client_id))
            .requestEmail()
            .build()
            
        googleSignInClient = GoogleSignIn.getClient(context, gso)
    }
    
    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }
    
    suspend fun handleSignInResult(data: Intent?): GoogleSignInResult {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.await()
            GoogleSignInResult.Success(account)
        } catch (e: ApiException) {
            GoogleSignInResult.Error(e.message ?: "Sign in failed")
        }
    }
}

sealed class GoogleSignInResult {
    data class Success(val account: GoogleSignInAccount) : GoogleSignInResult()
    data class Error(val message: String) : GoogleSignInResult()
}
```

**ViewModel Integration:**
```kotlin
// AuthViewModel.kt
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val googleSignInHelper: GoogleSignInHelper
) : ViewModel() {
    
    fun startGoogleSignIn(): Intent {
        return googleSignInHelper.getSignInIntent()
    }
    
    suspend fun handleGoogleSignIn(data: Intent?) {
        val result = googleSignInHelper.handleSignInResult(data)
        
        when (result) {
            is GoogleSignInResult.Success -> {
                val idToken = result.account.idToken
                if (idToken != null) {
                    // Send to backend
                    authRepository.signInWithGoogle(idToken)
                }
            }
            is GoogleSignInResult.Error -> {
                // Show error
            }
        }
    }
}
```

### 8.2 Future Enhancements

- [ ] **Private Groups (End-to-End Encryption)** - Future consideration
  - Group-level seed phrases (not user-level)
  - Enable "Privacy Mode" toggle when creating group
  - Group creator generates and backs up 12-word seed phrase
  - Goal titles, descriptions, notes encrypted client-side
  - Server blind to encrypted group data
  - Potential premium feature or power-user opt-in
- [ ] Dark mode with system theme detection
- [ ] Advanced progress charts and trend visualizations
- [ ] Goal templates library with curated presets
- [ ] Enhanced streaks and achievement system
- [ ] Automated weekly/monthly progress summaries
- [ ] Photo attachments for progress
- [ ] Comments on progress entries
- [ ] Web companion app

---

**End of UI Specification**
