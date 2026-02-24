package app.getpursue.ui.adapters

data class TemplateCardData(
    val id: String,
    val title: String,
    val description: String,
    val iconEmoji: String,
    val iconUrl: String?,
    val category: String,
    val metaLabel: String?,
    val isFeatured: Boolean
)
