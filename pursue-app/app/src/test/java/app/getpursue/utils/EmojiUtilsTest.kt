package app.getpursue.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class EmojiUtilsTest {

    @Test
    fun `returns fallback for null or question marks`() {
        assertEquals("🏆", EmojiUtils.normalizeOrFallback(null, "🏆"))
        assertEquals("🏆", EmojiUtils.normalizeOrFallback("??", "🏆"))
    }

    @Test
    fun `repairs mojibake emoji`() {
        assertEquals("🚶", EmojiUtils.normalizeOrFallback("ðŸš¶", "🏆"))
    }

    @Test
    fun `keeps valid emoji as is`() {
        assertEquals("📚", EmojiUtils.normalizeOrFallback("📚", "🏆"))
    }
}

