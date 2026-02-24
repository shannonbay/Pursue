package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.utils.EmojiUtils
import app.getpursue.utils.IconUrlUtils
import com.google.android.material.button.MaterialButton

sealed class TemplateCardListItem {
    data class Template(val data: TemplateCardData) : TemplateCardListItem()
    object CustomCard : TemplateCardListItem()
}

class TemplateCardAdapter(
    private var items: List<TemplateCardListItem>,
    private val featured: Boolean,
    private val onStartClick: (TemplateCardData) -> Unit,
    private val onCustomClick: () -> Unit = {},
    private val buttonLabel: String? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_TEMPLATE = 1
        private const val VIEW_TYPE_CUSTOM = 2
    }

    fun submitTemplates(newTemplates: List<TemplateCardData>) {
        items = newTemplates.map { TemplateCardListItem.Template(it) }
        notifyDataSetChanged()
    }

    fun submitItems(newItems: List<TemplateCardListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is TemplateCardListItem.Template -> VIEW_TYPE_TEMPLATE
        is TemplateCardListItem.CustomCard -> VIEW_TYPE_CUSTOM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_CUSTOM -> {
                val view = inflater.inflate(R.layout.item_challenge_template_custom, parent, false)
                CustomViewHolder(view)
            }
            else -> {
                val resId = if (featured) R.layout.item_challenge_template_featured
                            else R.layout.item_challenge_template
                TemplateViewHolder(inflater.inflate(resId, parent, false))
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is TemplateCardListItem.Template -> (holder as TemplateViewHolder).bind(item.data, onStartClick, buttonLabel)
            is TemplateCardListItem.CustomCard -> (holder as CustomViewHolder).bind(onCustomClick)
        }
    }

    override fun getItemCount(): Int = items.size

    class TemplateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val emoji: TextView = itemView.findViewById(R.id.template_emoji)
        private val iconImage: ImageView = itemView.findViewById(R.id.template_icon_image)
        private val title: TextView = itemView.findViewById(R.id.template_title)
        private val description: TextView = itemView.findViewById(R.id.template_description)
        private val durationDifficulty: TextView = itemView.findViewById(R.id.template_duration_difficulty)
        private val startButton: MaterialButton = itemView.findViewById(R.id.template_start_button)

        fun bind(data: TemplateCardData, onStartClick: (TemplateCardData) -> Unit, buttonLabel: String? = null) {
            if (buttonLabel != null) startButton.text = buttonLabel
            if (data.iconUrl != null) {
                val loaded = IconUrlUtils.loadInto(itemView.context, data.iconUrl, iconImage)
                if (loaded) {
                    iconImage.visibility = View.VISIBLE
                    emoji.visibility = View.GONE
                } else {
                    showEmojiFallback(data.iconEmoji)
                }
            } else {
                showEmojiFallback(data.iconEmoji)
            }

            title.text = data.title
            description.text = data.description
            if (data.metaLabel != null) {
                durationDifficulty.text = data.metaLabel
                durationDifficulty.visibility = View.VISIBLE
            } else {
                durationDifficulty.visibility = View.GONE
            }
            startButton.setOnClickListener { onStartClick(data) }
        }

        private fun showEmojiFallback(iconEmoji: String) {
            iconImage.visibility = View.GONE
            emoji.visibility = View.VISIBLE
            emoji.text = EmojiUtils.normalizeOrFallback(iconEmoji, "🏆")
        }
    }

    class CustomViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val actionButton: MaterialButton = itemView.findViewById(R.id.custom_challenge_button)

        fun bind(onCustomClick: () -> Unit) {
            itemView.setOnClickListener { onCustomClick() }
            actionButton.setOnClickListener { onCustomClick() }
        }
    }
}
