package app.getpursue.data.referral

import android.content.Context
import android.net.Uri
import android.os.Bundle
import app.getpursue.data.analytics.AnalyticsEvents
import app.getpursue.data.analytics.AnalyticsLogger
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener

object InstallReferralHelper {
    private const val KEY_REFERRAL_CHECKED = "referral_checked"

    fun checkAndReport(context: Context) {
        val prefs = context.getSharedPreferences("pursue_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_REFERRAL_CHECKED, false)) return

        val client = InstallReferrerClient.newBuilder(context).build()
        client.startConnection(object : InstallReferrerStateListener {
            override fun onInstallReferrerSetupFinished(responseCode: Int) {
                try {
                    if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                        val referrerStr = client.installReferrer.installReferrer
                        parseAndReport(referrerStr)
                    }
                } finally {
                    prefs.edit().putBoolean(KEY_REFERRAL_CHECKED, true).apply()
                    client.endConnection()
                }
            }

            override fun onInstallReferrerServiceDisconnected() {}
        })
    }

    private fun parseAndReport(referrer: String?) {
        if (referrer.isNullOrBlank()) return
        val uri = Uri.parse("https://x.com/?$referrer")
        if (uri.getQueryParameter("utm_source") != "pursue_card") return
        AnalyticsLogger.logEvent(AnalyticsEvents.REFERRAL_ATTRIBUTED, Bundle().apply {
            uri.getQueryParameter("utm_campaign")?.let { putString(AnalyticsEvents.Param.CARD_TYPE, it) }
            uri.getQueryParameter("utm_content")?.let { putString(AnalyticsEvents.Param.GROUP_ID, it) }
            uri.getQueryParameter("utm_term")?.let { putString(AnalyticsEvents.Param.NOTIFICATION_ID, it) }
        })
    }
}
