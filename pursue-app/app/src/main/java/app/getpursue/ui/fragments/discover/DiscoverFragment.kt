package app.getpursue.ui.fragments.discover

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import app.getpursue.R
import app.getpursue.data.analytics.AnalyticsEvents
import app.getpursue.data.analytics.AnalyticsLogger
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.DiscoverGroupItem
import app.getpursue.ui.adapters.DiscoverGroupAdapter
import app.getpursue.ui.views.ErrorStateView
import app.getpursue.ui.views.PublicGroupDetailBottomSheet
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DiscoverFragment : Fragment() {

    private enum class UiState { LOADING, SUCCESS_WITH_DATA, SUCCESS_EMPTY, ERROR }

    // Filter/sort state
    private val categoryValues = listOf(
        null, "fitness", "nutrition", "mindfulness", "learning",
        "creativity", "productivity", "finance", "social", "lifestyle", "sports", "other"
    )
    private val categoryLabels = listOf(
        "All", "Fitness", "Nutrition", "Mindfulness", "Learning",
        "Creativity", "Productivity", "Finance", "Social", "Lifestyle", "Sports", "Other"
    )
    private val sortValues = listOf("heat", "newest", "members")
    private val sortLabels = listOf("Heat", "Newest", "Members")

    private var selectedCategoryIndex = 0
    private var selectedSortIndex = 0
    private var searchQuery = ""

    // Pagination
    private var nextCursor: String? = null
    private var hasMore = false
    private var isLoadingMore = false
    private val groups = mutableListOf<DiscoverGroupItem>()

    // Views
    private lateinit var recyclerView: RecyclerView
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout
    private lateinit var searchInput: TextInputEditText
    private lateinit var sortSpinner: Spinner
    private lateinit var categoryChipGroup: ChipGroup
    private lateinit var loadingContainer: FrameLayout
    private lateinit var emptyStateContainer: LinearLayout
    private lateinit var errorStateContainer: FrameLayout

    private var adapter: DiscoverGroupAdapter? = null
    private var errorStateView: ErrorStateView? = null
    private var spinnerInitialized = false

    private val searchHandler = Handler(Looper.getMainLooper())
    private var searchRunnable: Runnable? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_discover, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.discover_recycler_view)
        swipeRefreshLayout = view.findViewById(R.id.swipe_refresh)
        searchInput = view.findViewById(R.id.search_input)
        sortSpinner = view.findViewById(R.id.sort_spinner)
        categoryChipGroup = view.findViewById(R.id.category_chip_group)
        loadingContainer = view.findViewById(R.id.loading_container)
        emptyStateContainer = view.findViewById(R.id.empty_state_container)
        errorStateContainer = view.findViewById(R.id.error_state_container)

        setupCategoryChips()
        setupSortSpinner()
        setupSearch()
        setupRecyclerView()
        swipeRefreshLayout.setOnRefreshListener { loadGroups(reset = true) }

        loadGroups(reset = true)
    }

    private fun setupCategoryChips() {
        categoryValues.forEachIndexed { index, _ ->
            val chip = Chip(requireContext()).apply {
                text = categoryLabels[index]
                isCheckable = true
                isChecked = (index == selectedCategoryIndex)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked && selectedCategoryIndex != index) {
                        selectedCategoryIndex = index
                        AnalyticsLogger.logEvent(AnalyticsEvents.DISCOVER_FILTER_CATEGORY, android.os.Bundle().apply {
                            putString(AnalyticsEvents.Param.CATEGORY, categoryValues[index] ?: "all")
                        })
                        loadGroups(reset = true)
                    }
                }
            }
            categoryChipGroup.addView(chip)
        }
    }

    private fun setupSortSpinner() {
        val spinnerAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            sortLabels
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        sortSpinner.adapter = spinnerAdapter
        sortSpinner.setSelection(selectedSortIndex, false)
        sortSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!spinnerInitialized) {
                    spinnerInitialized = true
                    return
                }
                if (selectedSortIndex != position) {
                    selectedSortIndex = position
                    AnalyticsLogger.logEvent(AnalyticsEvents.DISCOVER_SORT_CHANGED, android.os.Bundle().apply {
                        putString(AnalyticsEvents.Param.SORT, sortValues[position])
                    })
                    loadGroups(reset = true)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        spinnerInitialized = false
    }

    private fun setupSearch() {
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                searchRunnable?.let { searchHandler.removeCallbacks(it) }
                val query = s?.toString() ?: ""
                searchRunnable = Runnable {
                    if (searchQuery != query) {
                        searchQuery = query
                        if (query.isNotBlank()) {
                            AnalyticsLogger.logEvent(AnalyticsEvents.DISCOVER_SEARCH, android.os.Bundle().apply {
                                putString(AnalyticsEvents.Param.QUERY, query)
                            })
                        }
                        loadGroups(reset = true)
                    }
                }
                searchHandler.postDelayed(searchRunnable!!, 400L)
            }
        })
    }

    private fun setupRecyclerView() {
        val layoutManager = LinearLayoutManager(requireContext())
        recyclerView.layoutManager = layoutManager

        val newAdapter = DiscoverGroupAdapter(groups) { group ->
            AnalyticsLogger.logEvent(AnalyticsEvents.DISCOVER_GROUP_TAPPED, android.os.Bundle().apply {
                putString(AnalyticsEvents.Param.GROUP_ID, group.id)
            })
            PublicGroupDetailBottomSheet.show(childFragmentManager, group.id)
        }
        adapter = newAdapter
        recyclerView.adapter = newAdapter

        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (!hasMore || isLoadingMore || dy <= 0) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                val total = layoutManager.itemCount
                if (total > 0 && lastVisible >= total - 4) {
                    isLoadingMore = true
                    loadGroups(reset = false)
                }
            }
        })
    }

    private fun loadGroups(reset: Boolean) {
        if (reset) {
            nextCursor = null
            groups.clear()
            adapter?.notifyDataSetChanged()
        }

        lifecycleScope.launch {
            if (!swipeRefreshLayout.isRefreshing && reset) {
                updateUiState(UiState.LOADING)
            }

            try {
                val sort = sortValues[selectedSortIndex]
                val categorySlug = categoryValues[selectedCategoryIndex]
                val q = searchQuery.trim().takeIf { it.isNotBlank() }

                val response = withContext(Dispatchers.IO) {
                    ApiClient.listPublicGroups(
                        sort = sort,
                        categories = categorySlug,
                        q = q,
                        cursor = nextCursor,
                        limit = 20
                    )
                }

                if (!isAdded) return@launch

                val prevSize = groups.size
                groups.addAll(response.groups)
                nextCursor = response.next_cursor
                hasMore = response.has_more

                if (reset) {
                    adapter?.notifyDataSetChanged()
                } else {
                    adapter?.notifyItemRangeInserted(prevSize, response.groups.size)
                }

                updateUiState(if (groups.isEmpty()) UiState.SUCCESS_EMPTY else UiState.SUCCESS_WITH_DATA)

            } catch (e: ApiException) {
                if (!isAdded) return@launch
                if (reset) {
                    updateUiState(UiState.ERROR)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        if (isAdded) Toast.makeText(requireContext(), getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                if (reset) {
                    updateUiState(UiState.ERROR)
                } else {
                    Handler(Looper.getMainLooper()).post {
                        if (isAdded) Toast.makeText(requireContext(), getString(R.string.error_generic), Toast.LENGTH_SHORT).show()
                    }
                }
            } finally {
                Handler(Looper.getMainLooper()).post {
                    if (!isAdded) return@post
                    swipeRefreshLayout.isRefreshing = false
                    isLoadingMore = false
                }
            }
        }
    }

    private fun updateUiState(state: UiState) {
        if (!isAdded) return
        when (state) {
            UiState.LOADING -> {
                loadingContainer.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            UiState.SUCCESS_WITH_DATA -> {
                loadingContainer.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.GONE
            }
            UiState.SUCCESS_EMPTY -> {
                loadingContainer.visibility = View.GONE
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.VISIBLE
                errorStateContainer.visibility = View.GONE
            }
            UiState.ERROR -> {
                loadingContainer.visibility = View.GONE
                recyclerView.visibility = View.GONE
                emptyStateContainer.visibility = View.GONE
                errorStateContainer.visibility = View.VISIBLE
                if (errorStateView == null) {
                    errorStateView = ErrorStateView.inflate(
                        LayoutInflater.from(requireContext()),
                        errorStateContainer,
                        false
                    )
                    errorStateContainer.addView(errorStateView?.view)
                    errorStateView?.setOnRetryClickListener { loadGroups(reset = true) }
                }
                errorStateView?.setErrorType(ErrorStateView.ErrorType.NETWORK)
            }
        }
    }

    companion object {
        fun newInstance(): DiscoverFragment = DiscoverFragment()
    }
}
