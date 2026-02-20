package app.getpursue.ui.fragments.orientation

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
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
import app.getpursue.ui.adapters.ChallengeTemplateAdapter
import app.getpursue.ui.adapters.ChallengeTemplateListItem
import app.getpursue.ui.fragments.challenges.ChallengeSetupFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class OrientationChallengeFragment : Fragment() {

    private lateinit var loading: ProgressBar
    private lateinit var errorText: TextView
    private lateinit var featuredLabel: TextView
    private lateinit var featuredRecycler: RecyclerView
    private lateinit var templatesRecycler: RecyclerView
    private lateinit var infoCardContent: View
    private lateinit var infoCardChevron: ImageView

    private lateinit var featuredAdapter: ChallengeTemplateAdapter
    private lateinit var listAdapter: ChallengeTemplateAdapter

    private var infoExpanded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_orientation_challenge, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loading = view.findViewById(R.id.templates_loading)
        errorText = view.findViewById(R.id.templates_error)
        featuredLabel = view.findViewById(R.id.featured_label)
        featuredRecycler = view.findViewById(R.id.featured_recycler)
        templatesRecycler = view.findViewById(R.id.templates_recycler)
        infoCardContent = view.findViewById(R.id.info_card_content)
        infoCardChevron = view.findViewById(R.id.info_card_chevron)

        // Setup progress dots for step 2
        setupProgressDots(view.findViewById(R.id.progress_dots), 2)

        // Back / Skip
        view.findViewById<MaterialButton>(R.id.button_back).setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
        view.findViewById<MaterialButton>(R.id.button_skip).setOnClickListener { goToStep3() }
        view.findViewById<MaterialButton>(R.id.button_create_own).setOnClickListener { goToStep3() }

        // Expandable info card
        view.findViewById<View>(R.id.info_card_header).setOnClickListener { toggleInfoCard() }

        // Template adapters
        featuredAdapter = ChallengeTemplateAdapter(
            items = emptyList(),
            featured = true,
            onStartClick = { template -> openSetup(template.id) },
            onCustomChallengeClick = { /* Not shown in orientation */ }
        )
        listAdapter = ChallengeTemplateAdapter(
            items = emptyList(),
            featured = false,
            onStartClick = { template -> openSetup(template.id) },
            onCustomChallengeClick = { /* Not shown in orientation */ }
        )

        featuredRecycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.HORIZONTAL, false)
        featuredRecycler.adapter = featuredAdapter
        featuredRecycler.isNestedScrollingEnabled = false

        templatesRecycler.layoutManager = LinearLayoutManager(requireContext())
        templatesRecycler.adapter = listAdapter
        templatesRecycler.isNestedScrollingEnabled = false

        loadTemplates()
    }

    private fun toggleInfoCard() {
        infoExpanded = !infoExpanded
        infoCardContent.visibility = if (infoExpanded) View.VISIBLE else View.GONE
        infoCardChevron.rotation = if (infoExpanded) 180f else 0f
    }

    private fun loadTemplates() {
        viewLifecycleOwner.lifecycleScope.launch {
            loading.visibility = View.VISIBLE
            try {
                val token = SecureTokenManager.getInstance(requireContext()).getAccessToken()
                if (token == null) {
                    showError(getString(R.string.error_unauthorized_message))
                    return@launch
                }
                val response = withContext(Dispatchers.IO) {
                    ApiClient.getChallengeTemplates(token)
                }
                if (!isAdded) return@launch
                renderTemplates(response.templates)
                loading.visibility = View.GONE
            } catch (e: ApiException) {
                if (isAdded) showError(e.message ?: getString(R.string.error_loading_challenge_templates))
            } catch (_: Exception) {
                if (isAdded) showError(getString(R.string.error_loading_challenge_templates))
            }
        }
    }

    private fun renderTemplates(templates: List<ChallengeTemplate>) {
        val featured = templates.filter { it.is_featured }
        // Show only template items (no custom challenge card in orientation)
        val listItems = templates.map { ChallengeTemplateListItem.Template(it) }

        featuredAdapter.submitTemplates(featured)
        listAdapter.submitItems(listItems)

        featuredLabel.visibility = if (featured.isEmpty()) View.GONE else View.VISIBLE
        featuredRecycler.visibility = if (featured.isEmpty()) View.GONE else View.VISIBLE
        templatesRecycler.visibility = if (listItems.isEmpty()) View.GONE else View.VISIBLE
        errorText.visibility = View.GONE
    }

    private fun showError(message: String) {
        loading.visibility = View.GONE
        errorText.visibility = View.VISIBLE
        errorText.text = message
    }

    private fun openSetup(templateId: String) {
        requireActivity().supportFragmentManager.commit {
            replace(R.id.fragment_container, ChallengeSetupFragment.newInstance(templateId))
            addToBackStack(null)
        }
    }

    private fun goToStep3() {
        requireActivity().supportFragmentManager.commit {
            replace(R.id.fragment_container, OrientationCreateGroupFragment.newInstance())
            addToBackStack(null)
        }
    }

    companion object {
        fun newInstance(): OrientationChallengeFragment = OrientationChallengeFragment()
    }
}
