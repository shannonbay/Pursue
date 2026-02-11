package app.getpursue.utils

import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object PolicyDateUtils {
    private val dateFormat = SimpleDateFormat("MMM dd, yyyy", Locale.US)

    fun parseDate(dateStr: String): Date? {
        return try {
            dateFormat.parse(dateStr)
        } catch (e: ParseException) {
            null
        }
    }

    fun extractVersionDate(consentType: String): String? {
        // "terms Feb 11, 2026" -> "Feb 11, 2026"
        // "privacy policy Feb 11, 2026" -> "Feb 11, 2026"
        val termsPrefix = "terms "
        val privacyPrefix = "privacy policy "
        return when {
            consentType.startsWith(termsPrefix) -> consentType.removePrefix(termsPrefix)
            consentType.startsWith(privacyPrefix) -> consentType.removePrefix(privacyPrefix)
            else -> null
        }
    }

    fun needsReconsent(requiredVersion: String, consentTypes: List<String>, prefix: String): Boolean {
        val requiredDate = parseDate(requiredVersion) ?: return false

        val matchingEntries = consentTypes
            .filter { it.startsWith(prefix) }
            .mapNotNull { entry ->
                val dateStr = entry.removePrefix(prefix)
                parseDate(dateStr)
            }

        if (matchingEntries.isEmpty()) return true

        // User needs re-consent if their latest consent date is older than required
        val latestConsent = matchingEntries.max()
        return latestConsent.before(requiredDate)
    }
}
