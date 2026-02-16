package app.getpursue.utils

import android.content.Context
import app.getpursue.R

/**
 * Utility functions for Group Heat feature.
 * Maps heat tiers to drawable resources and provides formatting helpers.
 */
object HeatUtils {

    /**
     * Map heat tier (1-7) to the corresponding flame drawable resource.
     * Returns null for tier 0 (Cold) since no icon should be displayed.
     *
     * Tier mapping:
     * - 1: Spark (flame1)
     * - 2: Ember (flame2)
     * - 3: Flicker (flame3)
     * - 4: Steady (flame4)
     * - 5: Blaze (flame5)
     * - 6: Inferno (flame6)
     * - 7: Supernova (flame7)
     */
    fun getTierDrawable(tier: Int): Int? = when (tier) {
        1 -> R.drawable.flame1  // Spark
        2 -> R.drawable.flame2  // Ember
        3 -> R.drawable.flame3  // Flicker
        4 -> R.drawable.flame4  // Steady
        5 -> R.drawable.flame5  // Blaze
        6 -> R.drawable.flame6  // Inferno
        7 -> R.drawable.flame7  // Supernova
        else -> null  // Tier 0 = Cold, no icon
    }

    /**
     * Get accessibility content description for heat icon.
     * Example: "Group heat: Blaze (score 72)"
     */
    fun getContentDescription(context: Context, tierName: String, score: Float): String {
        return context.getString(R.string.heat_content_description, tierName, score.toInt())
    }

    /**
     * Format GCR (Group Completion Rate) as a percentage string.
     * Returns null if gcr is null.
     * Example: 0.92 -> "92%"
     */
    fun formatGcr(gcr: Float?): String? {
        return gcr?.let { "${(it * 100).toInt()}%" }
    }

    /**
     * Check if the tier should display the Supernova animation.
     */
    fun isSupernova(tier: Int): Boolean = tier == 7
}
