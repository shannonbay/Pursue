package app.getpursue.models

/**
 * Data model for Group Heat - the momentum indicator for groups.
 * 
 * Heat score is a continuous value from 0 to 100 based on group completion rates.
 * The tier is derived from the score (0=Cold through 7=Supernova).
 */
data class GroupHeat(
    val score: Float = 0f,
    val tier: Int = 0,
    val tier_name: String = "Cold",
    val streak_days: Int = 0,
    val peak_score: Float = 0f,
    val peak_date: String? = null,
    val last_calculated_at: String? = null,
    // Extended data (group detail only)
    val yesterday_gcr: Float? = null,
    val baseline_gcr: Float? = null
)
