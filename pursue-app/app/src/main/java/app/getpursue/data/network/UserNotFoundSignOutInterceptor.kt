package app.getpursue.data.network

import android.content.Context
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
        if (requestToken != null && currentToken != null && requestToken != currentToken) {
            return response   // stale request — ignore
        }

        val peekedBody = response.peekBody(Long.MAX_VALUE).string()
        val wrapper = try {
            gson.fromJson(peekedBody, ErrorWrapperParse::class.java)
        } catch (_: Exception) {
            return response
        }

        if (wrapper.error?.code == "NOT_FOUND") {
            AuthRepository.Companion.getInstance(context.applicationContext).signOut()
        }

        return response
    }

    private companion object {
        private val gson = Gson()
    }
}

/** Minimal DTO for parsing backend error JSON; used only by UserNotFoundSignOutInterceptor. */
private data class ErrorWrapperParse(val error: ErrorDetailParse?)

private data class ErrorDetailParse(val message: String?, val code: String?)
