package app.getpursue.utils

import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

object EmojiUtils {
    private val mojibakeMarkers = listOf("Ã", "Â", "ð", "â", "Ÿ")

    fun normalizeOrFallback(raw: String?, fallback: String): String {
        if (raw.isNullOrBlank()) return fallback
        val trimmed = raw.trim()
        if (trimmed == "?" || trimmed == "??" || trimmed.contains('\uFFFD')) return fallback

        val candidate = if (looksLikeMojibake(trimmed)) {
            repairMojibake(trimmed) ?: trimmed
        } else {
            trimmed
        }

        return if (hasEmojiSymbol(candidate)) candidate else fallback
    }

    private fun looksLikeMojibake(value: String): Boolean {
        return mojibakeMarkers.any { value.contains(it) }
    }

    private fun repairMojibake(value: String): String? {
        return try {
            val cp1252 = Charset.forName("windows-1252")
            val repaired = String(value.toByteArray(cp1252), StandardCharsets.UTF_8)
            if (hasEmojiSymbol(repaired)) repaired else null
        } catch (_: Exception) {
            null
        }
    }

    private fun hasEmojiSymbol(value: String): Boolean {
        return value.any { ch ->
            Character.isSurrogate(ch) || Character.getType(ch) == Character.OTHER_SYMBOL.toInt()
        }
    }
}
