package app.getpursue.data.config

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object PolicyConfigManager {
    private const val TAG = "PolicyConfigManager"
    private const val CONFIG_URL = "https://getpursue.app/config.json"
    private const val PREFS_NAME = "policy_config"
    private const val KEY_TERMS_VERSION = "min_required_terms_version"
    private const val KEY_PRIVACY_VERSION = "min_required_privacy_version"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Volatile
    private var cachedConfig: PolicyConfig? = null

    fun getConfig(context: Context): PolicyConfig? {
        // Try network fetch first
        try {
            val request = Request.Builder()
                .url(CONFIG_URL)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (body != null) {
                    val config = gson.fromJson(body, PolicyConfig::class.java)
                    if (config.min_required_terms_version != null && config.min_required_privacy_version != null) {
                        saveToPrefs(context, config)
                        cachedConfig = config
                        return config
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch config from network", e)
        }

        // Fall back to cached config
        cachedConfig?.let { return it }

        // Fall back to SharedPreferences
        return loadFromPrefs(context)
    }

    private fun saveToPrefs(context: Context, config: PolicyConfig) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TERMS_VERSION, config.min_required_terms_version)
            .putString(KEY_PRIVACY_VERSION, config.min_required_privacy_version)
            .apply()
    }

    private fun loadFromPrefs(context: Context): PolicyConfig? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val terms = prefs.getString(KEY_TERMS_VERSION, null)
        val privacy = prefs.getString(KEY_PRIVACY_VERSION, null)
        if (terms != null && privacy != null) {
            val config = PolicyConfig(terms, privacy)
            cachedConfig = config
            return config
        }
        return null
    }
}
