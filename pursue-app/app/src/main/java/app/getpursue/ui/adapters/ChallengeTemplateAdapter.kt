package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.network.ChallengeTemplate
import app.getpursue.utils.EmojiUtils
import app.getpursue.utils.IconUrlUtils
import com.google.android.material.button.MaterialButton

sealed class ChallengeTemplateListItem {
    data class Template(val template: ChallengeTemplate) : ChallengeTemplateListItem()
    object CustomChallengeCard : ChallengeTemplateListItem()
}

class ChallengeTemplateAdapter(
    private var items: List<ChallengeTemplateListItem>,
    private val featured: Boolean,
    private val onStartClick: (ChallengeTemplate) -> Unit,
    private val onCustomChallengeClick: () -> Unit = {}
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_TEMPLATE = 1
        private const val VIEW_TYPE_CUSTOM = 2
    }

    fun submitTemplates(newTemplates: List<ChallengeTemplate>) {
        items = newTemplates.map { ChallengeTemplateListItem.Template(it) }
        notifyDataSetChanged()
    }

    fun submitItems(newItems: List<ChallengeTemplateListItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is ChallengeTemplateListItem.Template -> VIEW_TYPE_TEMPLATE
            is ChallengeTemplateListItem.CustomChallengeCard -> VIEW_TYPE_CUSTOM
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            VIEW_TYPE_CUSTOM -> {
                val view = inflater.inflate(R.layout.item_challenge_template_custom, parent, false)
                CustomTemplateViewHolder(view)
            }
            else -> {
                val resId = if (featured) {
                    R.layout.item_challenge_template_featured
                } else {
                    R.layout.item_challenge_template
                }
                val view = inflater.inflate(resId, parent, false)
                TemplateViewHolder(view)
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is ChallengeTemplateListItem.Template -> (holder as TemplateViewHolder).bind(item.template, onStartClick)
            is ChallengeTemplateListItem.CustomChallengeCard -> (holder as CustomTemplateViewHolder).bind(onCustomChallengeClick)
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

        fun bind(template: ChallengeTemplate, onStartClick: (ChallengeTemplate) -> Unit) {
            // Priority 1: icon_url (bundled or remote)
            if (template.icon_url != null) {
                val loaded = IconUrlUtils.loadInto(itemView.context, template.icon_url, iconImage)
                if (loaded) {
                    iconImage.visibility = View.VISIBLE
                    emoji.visibility = View.GONE
                } else {
                    showEmojiFallback(template.icon_emoji)
                }
            } else {
                showEmojiFallback(template.icon_emoji)
            }

            title.text = template.title
            description.text = template.description
            durationDifficulty.text = itemView.context.getString(
                R.string.challenge_duration_difficulty_format,
                template.duration_days,
                template.difficulty.replaceFirstChar { it.uppercase() }
            )
            startButton.setOnClickListener { onStartClick(template) }
        }

        private fun showEmojiFallback(iconEmoji: String?) {
            iconImage.visibility = View.GONE
            emoji.visibility = View.VISIBLE
            emoji.text = EmojiUtils.normalizeOrFallback(iconEmoji, "🏆")
        }
    }

    class CustomTemplateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val actionButton: MaterialButton = itemView.findViewById(R.id.custom_challenge_button)

        fun bind(onCustomChallengeClick: () -> Unit) {
            itemView.setOnClickListener { onCustomChallengeClick() }
            actionButton.setOnClickListener { onCustomChallengeClick() }
        }
    }
}
