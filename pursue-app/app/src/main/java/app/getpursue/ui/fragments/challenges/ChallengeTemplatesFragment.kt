package app.getpursue.ui.fragments.challenges

import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.commit
import app.getpursue.R
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ChallengeTemplate
import app.getpursue.ui.activities.MainAppActivity
import app.getpursue.ui.adapters.TemplateCardData
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class ChallengeTemplatesFragment : TemplatesBrowserFragment() {

    override val showCustomOption = true
    override val featuredLabelRes = R.string.challenge_featured

    override suspend fun fetchTemplates(token: String): TemplatesBrowserData {
        val resp = ApiClient.getChallengeTemplates(token)
        return TemplatesBrowserData(
            templates = resp.templates.map { it.toCardData() },
            categories = resp.categories
        )
    }

    override fun openSetup(templateId: String) {
        requireActivity().supportFragmentManager.commit {
            replace(R.id.fragment_container, ChallengeSetupFragment.newInstance(templateId))
            addToBackStack(null)
        }
    }

    override fun onCustomOptionSelected() {
        val dialog: AlertDialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.challenge_custom_premium_title))
            .setMessage(getString(R.string.challenge_custom_premium_message))
            .setNegativeButton(getString(R.string.maybe_later), null)
            .setPositiveButton(getString(R.string.upgrade_to_premium)) { _, _ ->
                (requireActivity() as? MainAppActivity)?.showPremiumScreen()
            }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            requireContext().getColor(R.color.secondary)
        )
    }

    companion object {
        fun newInstance(): ChallengeTemplatesFragment = ChallengeTemplatesFragment()
    }
}

private fun ChallengeTemplate.toCardData() = TemplateCardData(
    id = id,
    title = title,
    description = description,
    iconEmoji = icon_emoji ?: "🏆",
    iconUrl = icon_url,
    category = category,
    metaLabel = "${duration_days} days · ${difficulty.replaceFirstChar { it.uppercase() }}",
    isFeatured = is_featured
)
