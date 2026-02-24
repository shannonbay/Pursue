package app.getpursue.ui.fragments.challenges

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiException
import app.getpursue.ui.adapters.TemplateCardAdapter
import app.getpursue.ui.adapters.TemplateCardData
import app.getpursue.ui.adapters.TemplateCardListItem
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TemplatesBrowserData(
    val templates: List<TemplateCardData>,
    val categories: List<String>,
    val introText: String? = null
)

abstract class TemplatesBrowserFragment : Fragment() {

    protected abstract suspend fun fetchTemplates(token: String): TemplatesBrowserData
    protected abstract fun openSetup(templateId: String)
    protected open val showCustomOption: Boolean = false
    protected open val featuredLabelRes: Int get() = R.string.challenge_featured
    protected open val buttonLabelRes: Int? get() = null
    protected open fun onCustomOptionSelected() {}

    private lateinit var loading: ProgressBar
    private lateinit var featuredLabel: TextView
    private lateinit var featuredRecycler: RecyclerView
    private lateinit var categoryChipGroup: ChipGroup
    private lateinit var categoryScroll: View
    private lateinit var templatesRecycler: RecyclerView
    private lateinit var errorText: TextView
    private lateinit var introTextView: TextView

    private lateinit var featuredAdapter: TemplateCardAdapter
    private lateinit var listAdapter: TemplateCardAdapter

    private var allTemplates: List<TemplateCardData> = emptyList()
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
        introTextView = view.findViewById(R.id.group_ideas_intro)

        val resolvedButtonLabel = buttonLabelRes?.let { getString(it) }
        featuredAdapter = TemplateCardAdapter(
            items = emptyList(),
            featured = true,
            onStartClick = { data -> openSetup(data.id) },
            onCustomClick = { onCustomOptionSelected() },
            buttonLabel = resolvedButtonLabel
        )
        listAdapter = TemplateCardAdapter(
            items = emptyList(),
            featured = false,
            onStartClick = { data -> openSetup(data.id) },
            onCustomClick = { onCustomOptionSelected() },
            buttonLabel = resolvedButtonLabel
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
                val context = context ?: return@launch
                val token = SecureTokenManager.Companion.getInstance(context).getAccessToken()
                if (token == null) {
                    showError(getString(R.string.error_unauthorized_message))
                    return@launch
                }
                val data = withContext(Dispatchers.IO) { fetchTemplates(token) }
                if (!isAdded) return@launch
                allTemplates = data.templates
                renderIntro(data.introText)
                renderCategories(data.categories)
                renderTemplateLists()
                showLoading(false)
            } catch (e: ApiException) {
                if (isAdded) showError(e.message ?: getString(R.string.error_loading_challenge_templates))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isAdded) showError(getString(R.string.error_loading_challenge_templates))
            }
        }
    }

    private fun renderIntro(text: String?) {
        if (!isAdded) return
        if (text != null) {
            introTextView.text = text
            introTextView.visibility = View.VISIBLE
        } else {
            introTextView.visibility = View.GONE
        }
    }

    private fun renderCategories(categories: List<String>) {
        if (!isAdded) return
        categoryChipGroup.removeAllViews()
        val allChip = createFilterChip(
            label = getString(R.string.challenge_category_all),
            filter = TemplateFilter.All
        ) ?: return
        categoryChipGroup.addView(allChip)
        if (showCustomOption) {
            categoryChipGroup.addView(
                createFilterChip(
                    label = getString(R.string.challenge_category_custom),
                    filter = TemplateFilter.Custom
                ) ?: return
            )
        }
        categories.sorted().forEach { category ->
            createFilterChip(
                label = category.replaceFirstChar { it.uppercase() },
                filter = TemplateFilter.Category(category)
            )?.let { categoryChipGroup.addView(it) }
        }
        allChip.isChecked = true
        selectedFilter = TemplateFilter.All
    }

    private fun createFilterChip(label: String, filter: TemplateFilter): Chip? {
        val currentContext = context ?: return null
        return Chip(currentContext).apply {
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
        val featuredItems = allTemplates.filter { it.isFeatured }
        val listItems: List<TemplateCardListItem> = when (val filter = selectedFilter) {
            is TemplateFilter.All -> buildList {
                if (showCustomOption) add(TemplateCardListItem.CustomCard)
                addAll(allTemplates.map { TemplateCardListItem.Template(it) })
            }
            is TemplateFilter.Custom -> {
                if (showCustomOption) listOf(TemplateCardListItem.CustomCard) else emptyList()
            }
            is TemplateFilter.Category -> {
                allTemplates.filter { it.category == filter.value }
                    .map { TemplateCardListItem.Template(it) }
            }
        }

        listAdapter.submitItems(listItems)
        featuredAdapter.submitTemplates(featuredItems)
        templatesRecycler.visibility = if (listItems.isEmpty()) View.GONE else View.VISIBLE
        featuredLabel.text = getString(featuredLabelRes)
        featuredLabel.visibility = if (featuredItems.isEmpty()) View.GONE else View.VISIBLE
        featuredRecycler.visibility = if (featuredItems.isEmpty()) View.GONE else View.VISIBLE
        categoryScroll.visibility = View.VISIBLE
        errorText.visibility = View.GONE
    }

    private fun showLoading(show: Boolean) {
        loading.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        val currentContext = context ?: return
        showLoading(false)
        errorText.visibility = View.VISIBLE
        errorText.text = message
        Toast.makeText(currentContext, message, Toast.LENGTH_SHORT).show()
    }
}
