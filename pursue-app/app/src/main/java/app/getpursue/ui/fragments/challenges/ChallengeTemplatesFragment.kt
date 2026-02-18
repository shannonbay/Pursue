package app.getpursue.ui.fragments.challenges

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.ChallengeTemplate
import app.getpursue.ui.activities.MainAppActivity
import app.getpursue.ui.adapters.ChallengeTemplateListItem
import app.getpursue.ui.adapters.ChallengeTemplateAdapter
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChallengeTemplatesFragment : Fragment() {
    private lateinit var loading: ProgressBar
    private lateinit var featuredLabel: TextView
    private lateinit var featuredRecycler: RecyclerView
    private lateinit var categoryChipGroup: ChipGroup
    private lateinit var categoryScroll: View
    private lateinit var templatesRecycler: RecyclerView
    private lateinit var errorText: TextView

    private lateinit var featuredAdapter: ChallengeTemplateAdapter
    private lateinit var listAdapter: ChallengeTemplateAdapter

    private var allTemplates: List<ChallengeTemplate> = emptyList()
    private var selectedFilter: TemplateFilter = TemplateFilter.All

    private sealed class TemplateFilter {
        object All : TemplateFilter()
        object Custom : TemplateFilter()
        data class Category(val value: String) : TemplateFilter()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_challenge_templates, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loading = view.findViewById(R.id.challenge_loading)
        featuredLabel = view.findViewById(R.id.featured_label)
        featuredRecycler = view.findViewById(R.id.featured_recycler)
        categoryChipGroup = view.findViewById(R.id.category_chip_group)
        categoryScroll = view.findViewById(R.id.category_scroll)
        templatesRecycler = view.findViewById(R.id.templates_recycler)
        errorText = view.findViewById(R.id.challenge_error_text)

        featuredAdapter = ChallengeTemplateAdapter(
            items = emptyList(),
            featured = true,
            onStartClick = { template -> openSetup(template.id) },
            onCustomChallengeClick = { showPremiumUpsell() }
        )
        listAdapter = ChallengeTemplateAdapter(
            items = emptyList(),
            featured = false,
            onStartClick = { template -> openSetup(template.id) },
            onCustomChallengeClick = { showPremiumUpsell() }
        )

        featuredRecycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        featuredRecycler.adapter = featuredAdapter
        featuredRecycler.isNestedScrollingEnabled = false
        templatesRecycler.layoutManager = LinearLayoutManager(requireContext())
        templatesRecycler.adapter = listAdapter
        templatesRecycler.isNestedScrollingEnabled = false
        loadTemplates()
    }

    private fun loadTemplates() {
        viewLifecycleOwner.lifecycleScope.launch {
            showLoading(true)
            try {
                val token = SecureTokenManager.Companion.getInstance(requireContext()).getAccessToken()
                if (token == null) {
                    showError(getString(R.string.error_unauthorized_message))
                    return@launch
                }
                val response = withContext(Dispatchers.IO) {
                    ApiClient.getChallengeTemplates(token)
                }
                allTemplates = response.templates
                renderCategories(response.categories)
                renderTemplateLists()
                showLoading(false)
            } catch (e: ApiException) {
                showError(e.message ?: getString(R.string.error_loading_challenge_templates))
            } catch (_: Exception) {
                showError(getString(R.string.error_loading_challenge_templates))
            }
        }
    }

    private fun renderCategories(categories: List<String>) {
        categoryChipGroup.removeAllViews()
        val allChip = createFilterChip(
            label = getString(R.string.challenge_category_all),
            filter = TemplateFilter.All
        )
        categoryChipGroup.addView(allChip)
        categoryChipGroup.addView(
            createFilterChip(
                label = getString(R.string.challenge_category_custom),
                filter = TemplateFilter.Custom
            )
        )
        categories.sorted().forEach { category ->
            categoryChipGroup.addView(
                createFilterChip(
                    label = category.replaceFirstChar { it.uppercase() },
                    filter = TemplateFilter.Category(category)
                )
            )
        }
        allChip.isChecked = true
        selectedFilter = TemplateFilter.All
    }

    private fun createFilterChip(label: String, filter: TemplateFilter): Chip {
        return Chip(requireContext()).apply {
            text = label
            isCheckable = true
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedFilter = filter
                    renderTemplateLists()
                }
            }
        }
    }

    private fun renderTemplateLists() {
        val featuredItems = allTemplates.filter { it.is_featured }
        val listItems: List<ChallengeTemplateListItem> = when (val filter = selectedFilter) {
            is TemplateFilter.All -> {
                buildList {
                    add(ChallengeTemplateListItem.CustomChallengeCard)
                    addAll(allTemplates.map { ChallengeTemplateListItem.Template(it) })
                }
            }
            is TemplateFilter.Custom -> {
                listOf(ChallengeTemplateListItem.CustomChallengeCard)
            }
            is TemplateFilter.Category -> {
                allTemplates
                    .filter { it.category == filter.value }
                    .map { ChallengeTemplateListItem.Template(it) }
            }
        }

        listAdapter.submitItems(listItems)
        featuredAdapter.submitTemplates(featuredItems)
        templatesRecycler.visibility = if (listItems.isEmpty()) View.GONE else View.VISIBLE
        featuredLabel.visibility = if (featuredItems.isEmpty()) View.GONE else View.VISIBLE
        featuredRecycler.visibility = if (featuredItems.isEmpty()) View.GONE else View.VISIBLE
        categoryScroll.visibility = View.VISIBLE
        errorText.visibility = View.GONE
    }

    private fun showLoading(show: Boolean) {
        loading.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        showLoading(false)
        errorText.visibility = View.VISIBLE
        errorText.text = message
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun openSetup(templateId: String) {
        requireActivity().supportFragmentManager.commit {
            replace(R.id.fragment_container, ChallengeSetupFragment.newInstance(templateId))
            addToBackStack(null)
        }
    }

    private fun showPremiumUpsell() {
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
