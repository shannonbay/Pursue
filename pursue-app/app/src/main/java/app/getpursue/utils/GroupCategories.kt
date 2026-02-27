package app.getpursue.utils

import android.content.Context
import app.getpursue.R

object GroupCategories {

    /** API slugs in canonical order (matches backend VALID_CATEGORIES). */
    val SLUGS = listOf(
        "fitness", "nutrition", "mindfulness", "learning",
        "creativity", "productivity", "finance", "social",
        "lifestyle", "sports", "other"
    )

    /** Localized display name for a category slug. */
    fun displayName(context: Context, slug: String): String = when (slug) {
        "fitness"      -> context.getString(R.string.category_fitness)
        "nutrition"    -> context.getString(R.string.category_nutrition)
        "mindfulness"  -> context.getString(R.string.category_mindfulness)
        "learning"     -> context.getString(R.string.category_learning)
        "creativity"   -> context.getString(R.string.category_creativity)
        "productivity" -> context.getString(R.string.category_productivity)
        "finance"      -> context.getString(R.string.category_finance)
        "social"       -> context.getString(R.string.category_social)
        "lifestyle"    -> context.getString(R.string.category_lifestyle)
        "sports"       -> context.getString(R.string.category_sports)
        "other"        -> context.getString(R.string.category_other)
        else           -> slug.replaceFirstChar { it.titlecase() }
    }

    /** All entries as (slug, localizedLabel) pairs for use in dropdowns. */
    fun entries(context: Context): List<Pair<String, String>> =
        SLUGS.map { it to displayName(context, it) }
}
