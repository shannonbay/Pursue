package app.getpursue.ui.fragments.groups

import androidx.fragment.app.commit
import app.getpursue.R
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.GroupTemplate
import app.getpursue.ui.adapters.TemplateCardData
import app.getpursue.ui.fragments.challenges.TemplatesBrowserData
import app.getpursue.ui.fragments.challenges.TemplatesBrowserFragment

class GroupIdeasFragment : TemplatesBrowserFragment() {

    override val showCustomOption = false
    override val featuredLabelRes = R.string.group_ideas_popular
    override val buttonLabelRes = R.string.group_create_button

    override suspend fun fetchTemplates(token: String): TemplatesBrowserData {
        val intro = "${getString(R.string.group_ideas_intro_title)}\n${getString(R.string.group_ideas_intro_body)}"
        val resp = ApiClient.getGroupTemplates(token)
        return TemplatesBrowserData(
            templates = resp.templates.map { it.toCardData() },
            categories = resp.categories,
            introText = intro
        )
    }

    override fun openSetup(templateId: String) {
        requireActivity().supportFragmentManager.commit {
            replace(R.id.fragment_container, GroupSetupFragment.newInstance(templateId))
            addToBackStack(null)
        }
    }

    companion object {
        fun newInstance(): GroupIdeasFragment = GroupIdeasFragment()
    }
}

private fun GroupTemplate.toCardData() = TemplateCardData(
    id = id,
    title = title,
    description = description,
    iconEmoji = icon_emoji,
    iconUrl = icon_url,
    category = category,
    metaLabel = category.replaceFirstChar { it.uppercase() },
    isFeatured = is_featured
)
