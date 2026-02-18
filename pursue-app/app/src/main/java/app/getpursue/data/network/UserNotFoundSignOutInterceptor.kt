package app.getpursue.data.network

import android.content.Context
import android.util.Log
import app.getpursue.data.auth.AuthRepository
import app.getpursue.data.auth.SecureTokenManager
import com.google.gson.Gson
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Response interceptor that triggers sign-out when the backend returns 404 "User not found"
 * (NOT_FOUND) for current-user endpoints (e.g. GET /users/me). Handles the case where the app
 * points at a different backend (e.g. production) but still has tokens from another (e.g. dev),
 * so the user no longer exists on the current server.
 */
class UserNotFoundSignOutInterceptor(
    private val context: Context
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (response.code != 404) return response

        val path = request.url.encodedPath
        if (!path.contains("users/me")) return response

        // Guard: don't sign out if a new sign-in has replaced the token
        val requestToken = request.header("Authorization")?.removePrefix("Bearer ")?.trim()
        val currentToken = SecureTokenManager.Companion.getInstance(context.applicationContext).getAccessToken()

        Log.w(TAG, "404 on users/me path: $path | " +
                "requestToken=${requestToken?.take(12)}... | " +
                "currentToken=${currentToken?.take(12) ?: "null"} | " +
                "method=${request.method}")

        if (requestToken != null && currentToken != null && requestToken != currentToken) {
            Log.w(TAG, "Guard: stale request (tokens differ) — ignoring, NOT signing out")
            return response   // stale request — ignore
        }

        val peekedBody = response.peekBody(Long.MAX_VALUE).string()
        val wrapper = try {
            gson.fromJson(peekedBody, ErrorWrapperParse::class.java)
        } catch (_: Exception) {
            return response
        }

        if (wrapper.error?.code == "NOT_FOUND") {
            Log.w(TAG, "NOT_FOUND on $path — signing out. currentToken=${if (currentToken != null) "present" else "null"}")
            AuthRepository.Companion.getInstance(context.applicationContext).signOut()
        } else {
            Log.w(TAG, "404 on $path but error code=${wrapper.error?.code} — not signing out")
        }

        return response
    }

    private companion object {
        private const val TAG = "UserNotFoundInterceptor"
        private val gson = Gson()
    }
}

/** Minimal DTO for parsing backend error JSON; used only by UserNotFoundSignOutInterceptor. */
private data class ErrorWrapperParse(val error: ErrorDetailParse?)

private data class ErrorDetailParse(val message: String?, val code: String?)
