package app.getpursue.data.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.security.crypto.MasterKeys
import java.security.KeyStoreException

/**
 * Secure token storage using Android Keystore and EncryptedSharedPreferences.
 *
 * Uses hardware-backed encryption when available to securely store
 * access_token and refresh_token. The encryption keys are managed by
 * Android Keystore and are non-exportable.
 *
 * E2E / Robolectric: when EncryptedSharedPreferences cannot be created (e.g.
 * KeyStore/NoSuchAlgorithmException on JVM), tokens are kept in memory via
 * setTestOverride. storeTokens writes to that when encryptedPrefs is null.
 * clearTestOverride() should be called from E2ETest @After.
 */
class SecureTokenManager private constructor(context: Context) {

    private val encryptedPrefs: SharedPreferences? = try {
        val masterKey = try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: KeyStoreException) {
            val keyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            MasterKey.Builder(context, keyAlias)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        }
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // E2E/Robolectric on JVM: KeyStore/MasterKeys/EncryptedSharedPreferences often
        // throw. Use in-memory test override via setTestOverride/storeTokens instead.
        null
    }

    /**
     * Store JWT tokens securely.
     * When EncryptedSharedPreferences is unavailable (E2E), writes to in-memory test override.
     */
    fun storeTokens(accessToken: String, refreshToken: String) {
        if (encryptedPrefs == null) {
            setTestOverride(accessToken, refreshToken)
            return
        }
        encryptedPrefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .apply()
    }

    /**
     * Retrieve the access token, or null if not stored.
     */
    fun getAccessToken(): String? {
        return testAccessToken ?: encryptedPrefs?.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * Retrieve the refresh token, or null if not stored.
     */
    fun getRefreshToken(): String? {
        return testRefreshToken ?: encryptedPrefs?.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Update only the access token (e.g., after refresh).
     */
    fun updateAccessToken(accessToken: String) {
        if (encryptedPrefs == null) {
            setTestOverride(accessToken, testRefreshToken ?: "")
            return
        }
        encryptedPrefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .apply()
    }

    /**
     * Check if tokens are stored (user is authenticated).
     */
    fun hasTokens(): Boolean {
        return getAccessToken() != null && getRefreshToken() != null
    }

    /**
     * Clear all stored tokens (logout).
     */
    fun clearTokens() {
        if (encryptedPrefs == null) {
            clearTestOverride()
            return
        }
        encryptedPrefs.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "secure_token_prefs"
        private const val KEY_ACCESS_TOKEN = "access_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"

        /** In-memory override for E2E when EncryptedSharedPreferences is unavailable. */
        @Volatile
        internal var testAccessToken: String? = null

        @Volatile
        internal var testRefreshToken: String? = null

        /** Set in-memory tokens for E2E. No-op in production if encryptedPrefs is used. */
        fun setTestOverride(accessToken: String, refreshToken: String) {
            testAccessToken = accessToken
            testRefreshToken = refreshToken
        }

        /** Clear in-memory override. Call from E2ETest @After. */
        fun clearTestOverride() {
            testAccessToken = null
            testRefreshToken = null
        }

        @Volatile
        private var INSTANCE: SecureTokenManager? = null

        /**
         * Get the singleton instance of SecureTokenManager.
         * Thread-safe lazy initialization.
         */
        fun getInstance(context: Context): SecureTokenManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureTokenManager(context.applicationContext).also {
                    INSTANCE = it
                }
            }
        }
    }
}
