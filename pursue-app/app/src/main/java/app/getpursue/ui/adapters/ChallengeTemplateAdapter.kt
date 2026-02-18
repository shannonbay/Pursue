package app.getpursue.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.network.ChallengeTemplate
import app.getpursue.utils.EmojiUtils
import com.google.android.material.button.MaterialButton

class ChallengeTemplateAdapter(
    private var templates: List<ChallengeTemplate>,
    private val featured: Boolean,
    private val onStartClick: (ChallengeTemplate) -> Unit
) : RecyclerView.Adapter<ChallengeTemplateAdapter.TemplateViewHolder>() {

    fun submit(newTemplates: List<ChallengeTemplate>) {
        templates = newTemplates
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TemplateViewHolder {
        val resId = if (featured) {
            R.layout.item_challenge_template_featured
        } else {
            R.layout.item_challenge_template
        }
        val view = LayoutInflater.from(parent.context).inflate(resId, parent, false)
        return TemplateViewHolder(view)
    }

    override fun onBindViewHolder(holder: TemplateViewHolder, position: Int) {
        holder.bind(templates[position], onStartClick)
    }

    override fun getItemCount(): Int = templates.size

    class TemplateViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val emoji: TextView = itemView.findViewById(R.id.template_emoji)
        private val title: TextView = itemView.findViewById(R.id.template_title)
        private val description: TextView = itemView.findViewById(R.id.template_description)
        private val durationDifficulty: TextView = itemView.findViewById(R.id.template_duration_difficulty)
        private val startButton: MaterialButton = itemView.findViewById(R.id.template_start_button)

        fun bind(template: ChallengeTemplate, onStartClick: (ChallengeTemplate) -> Unit) {
            emoji.text = EmojiUtils.normalizeOrFallback(template.icon_emoji, "🏆")
            title.text = template.title
            description.text = template.description
            durationDifficulty.text = itemView.context.getString(
                R.string.challenge_duration_difficulty_format,
                template.duration_days,
                template.difficulty.replaceFirstChar { it.uppercase() }
            )
            startButton.setOnClickListener { onStartClick(template) }
        }
    }
}
