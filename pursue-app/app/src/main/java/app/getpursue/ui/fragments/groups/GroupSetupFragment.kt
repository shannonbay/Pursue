package app.getpursue.ui.fragments.groups

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.GroupTemplate
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.activities.MainAppActivity
import app.getpursue.utils.EmojiUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class GroupSetupFragment : Fragment() {

    private lateinit var titleText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var nameEdit: TextInputEditText
    private lateinit var goalsContainer: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var createButton: MaterialButton
    private lateinit var switchPublicListing: SwitchMaterial
    private lateinit var btnVisibilityInfo: ImageView

    private var selectedTemplate: GroupTemplate? = null
    private var selectedVisibility: String = "private"

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_group_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleText = view.findViewById(R.id.group_setup_title)
        descriptionText = view.findViewById(R.id.group_setup_description)
        nameEdit = view.findViewById(R.id.group_name_edit)
        goalsContainer = view.findViewById(R.id.group_goals_container)
        loading = view.findViewById(R.id.group_setup_loading)
        createButton = view.findViewById(R.id.group_create_button)
        switchPublicListing = view.findViewById(R.id.switch_public_listing)
        btnVisibilityInfo = view.findViewById(R.id.btn_visibility_info)

        switchPublicListing.setOnCheckedChangeListener { _, isChecked ->
            selectedVisibility = if (isChecked) "public" else "private"
        }
        btnVisibilityInfo.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.label_public_listing))
                .setMessage(getString(R.string.public_listing_tooltip))
                .setPositiveButton(getString(android.R.string.ok), null)
                .show()
        }

        createButton.setOnClickListener { createGroup() }
        loadTemplate()
    }

    private fun loadTemplate() {
        val templateId = arguments?.getString(ARG_TEMPLATE_ID) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                val context = context ?: return@launch
                val token = SecureTokenManager.Companion.getInstance(context).getAccessToken()
                if (token == null) {
                    showError(getString(R.string.error_unauthorized_message))
                    return@launch
                }
                val templates = withContext(Dispatchers.IO) { ApiClient.getGroupTemplates(token) }
                if (!isAdded) return@launch
                selectedTemplate = templates.templates.find { it.id == templateId }
                val template = selectedTemplate
                if (template == null) {
                    showError(getString(R.string.challenge_template_not_found))
                    return@launch
                }
                bindTemplate(template)
            } catch (e: ApiException) {
                if (isAdded) showError(e.message ?: getString(R.string.error_loading_challenge_templates))
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isAdded) showError(getString(R.string.error_loading_challenge_templates))
            } finally {
                if (isAdded) setLoading(false)
            }
        }
    }

    private fun bindTemplate(template: GroupTemplate) {
        val currentContext = context ?: return
        titleText.text = template.title
        descriptionText.text = template.description
        nameEdit.setText(template.title)
        goalsContainer.removeAllViews()
        template.goals.forEach { goal ->
            val goalView = LayoutInflater.from(currentContext)
                .inflate(android.R.layout.simple_list_item_1, goalsContainer, false) as TextView
            val activeDaysSuffix = if (goal.cadence == "daily" && goal.active_days_label != null
                && goal.active_days_label != "Every day") {
                " · ${goal.active_days_label}"
            } else ""
            goalView.text = "• ${goal.title} (${goal.cadence}$activeDaysSuffix)"
            goalsContainer.addView(goalView)
        }
    }

    private fun createGroup() {
        val template = selectedTemplate ?: return
        val groupName = nameEdit.text?.toString()?.trim().orEmpty()
        if (groupName.isBlank()) {
            val currentContext = context ?: return
            Toast.makeText(currentContext, getString(R.string.group_name_error), Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                val context = context ?: return@launch
                val token = SecureTokenManager.Companion.getInstance(context).getAccessToken()
                if (token == null) {
                    showError(getString(R.string.error_unauthorized_message))
                    return@launch
                }
                val response = withContext(Dispatchers.IO) {
                    ApiClient.createGroup(
                        accessToken = token,
                        name = groupName,
                        templateId = template.id,
                        visibility = selectedVisibility
                    )
                }
                if (!isAdded) return@launch
                val intent = Intent(context, GroupDetailActivity::class.java).apply {
                    putExtra(GroupDetailActivity.EXTRA_GROUP_ID, response.id)
                    putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, response.name)
                    putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, false)
                    putExtra(
                        GroupDetailActivity.EXTRA_GROUP_ICON_EMOJI,
                        EmojiUtils.normalizeOrFallback(template.icon_emoji, "🏆")
                    )
                    putExtra(GroupDetailActivity.EXTRA_OPEN_INVITE_SHEET, true)
                }
                startActivity(intent)
                requireActivity().supportFragmentManager.popBackStack(null, 1)
            } catch (e: ApiException) {
                if (isAdded) {
                    when (e.errorCode) {
                        "PREMIUM_REQUIRED", "GROUP_LIMIT_REACHED" -> showUpgradeDialog(e.message ?: "")
                        else -> showError(e.message ?: getString(R.string.group_create_failed))
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (isAdded) showError(getString(R.string.group_create_failed))
            } finally {
                if (isAdded) setLoading(false)
            }
        }
    }

    private fun setLoading(show: Boolean) {
        loading.visibility = if (show) View.VISIBLE else View.GONE
        createButton.isEnabled = !show
        nameEdit.isEnabled = !show
        switchPublicListing.isEnabled = !show
    }

    private fun showError(message: String) {
        val currentContext = context ?: return
        Toast.makeText(currentContext, message, Toast.LENGTH_SHORT).show()
    }

    private fun showUpgradeDialog(message: String) {
        val currentContext = context ?: return
        MaterialAlertDialogBuilder(currentContext)
            .setTitle(getString(R.string.upgrade_to_premium))
            .setMessage(if (message.isBlank()) getString(R.string.challenge_custom_premium_message) else message)
            .setNegativeButton(getString(R.string.maybe_later), null)
            .setPositiveButton(getString(R.string.upgrade_to_premium)) { _, _ ->
                (requireActivity() as? MainAppActivity)?.showPremiumScreen()
            }
            .show()
    }

    companion object {
        private const val ARG_TEMPLATE_ID = "template_id"

        fun newInstance(templateId: String): GroupSetupFragment {
            return GroupSetupFragment().apply {
                arguments = Bundle().apply { putString(ARG_TEMPLATE_ID, templateId) }
            }
        }
    }
}
