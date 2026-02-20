package app.getpursue.ui.fragments.challenges

import android.app.DatePickerDialog
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import app.getpursue.data.network.ChallengeTemplate
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.views.CovenantBottomSheet
import app.getpursue.ui.activities.MainAppActivity
import app.getpursue.utils.EmojiUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

class ChallengeSetupFragment : Fragment() {
    private lateinit var titleText: TextView
    private lateinit var descriptionText: TextView
    private lateinit var nameEdit: TextInputEditText
    private lateinit var startDateButton: MaterialButton
    private lateinit var endDateText: TextView
    private lateinit var goalsContainer: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var startButton: MaterialButton

    private var selectedTemplate: ChallengeTemplate? = null
    private var selectedStartDate: LocalDate = LocalDate.now()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_challenge_setup, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        titleText = view.findViewById(R.id.challenge_setup_title)
        descriptionText = view.findViewById(R.id.challenge_setup_description)
        nameEdit = view.findViewById(R.id.challenge_name_edit)
        startDateButton = view.findViewById(R.id.challenge_start_date_button)
        endDateText = view.findViewById(R.id.challenge_end_date_text)
        goalsContainer = view.findViewById(R.id.challenge_goals_container)
        loading = view.findViewById(R.id.challenge_setup_loading)
        startButton = view.findViewById(R.id.challenge_start_button)

        startDateButton.setOnClickListener { showDatePicker() }
        startButton.setOnClickListener { createChallenge() }
        loadTemplate()
    }

    private fun loadTemplate() {
        val templateId = arguments?.getString(ARG_TEMPLATE_ID) ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                val token = SecureTokenManager.Companion.getInstance(requireContext()).getAccessToken()
                if (token == null) {
                    showError(getString(R.string.error_unauthorized_message))
                    return@launch
                }
                val templates = withContext(Dispatchers.IO) { ApiClient.getChallengeTemplates(token) }
                selectedTemplate = templates.templates.find { it.id == templateId }
                val template = selectedTemplate
                if (template == null) {
                    showError(getString(R.string.challenge_template_not_found))
                    return@launch
                }
                selectedStartDate = LocalDate.now().plusDays(1)
                bindTemplate(template)
            } catch (e: ApiException) {
                showError(e.message ?: getString(R.string.error_loading_challenge_templates))
            } catch (_: Exception) {
                showError(getString(R.string.error_loading_challenge_templates))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun bindTemplate(template: ChallengeTemplate) {
        titleText.text = template.title
        descriptionText.text = template.description
        nameEdit.setText(template.title)
        renderDates(template.duration_days)
        goalsContainer.removeAllViews()
        template.goals.forEach { goal ->
            val goalView = LayoutInflater.from(requireContext())
                .inflate(android.R.layout.simple_list_item_1, goalsContainer, false) as TextView
            goalView.text = "• ${goal.title} (${goal.cadence})"
            goalsContainer.addView(goalView)
        }
    }

    private fun showDatePicker() {
        val today = LocalDate.now()
        val max = today.plusDays(30)
        val current = selectedStartDate
        val picker = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val picked = LocalDate.of(year, month + 1, dayOfMonth)
                if (picked.isBefore(today) || picked.isAfter(max)) {
                    Toast.makeText(requireContext(), getString(R.string.challenge_invalid_start_date), Toast.LENGTH_SHORT).show()
                    return@DatePickerDialog
                }
                selectedStartDate = picked
                selectedTemplate?.let { renderDates(it.duration_days) }
            },
            current.year,
            current.monthValue - 1,
            current.dayOfMonth
        )
        val zoneId = ZoneId.systemDefault()
        picker.datePicker.minDate = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        picker.datePicker.maxDate = max.atStartOfDay(zoneId).toInstant().toEpochMilli()
        picker.show()
    }

    private fun renderDates(durationDays: Int) {
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        startDateButton.text = selectedStartDate.format(formatter)
        val endDate = selectedStartDate.plusDays(durationDays.toLong() - 1L)
        endDateText.text = getString(
            R.string.challenge_end_date_format,
            endDate.format(formatter),
            durationDays
        )
    }

    private fun createChallenge() {
        val template = selectedTemplate ?: return
        val challengeName = nameEdit.text?.toString()?.trim().orEmpty()
        if (challengeName.isBlank()) {
            Toast.makeText(requireContext(), getString(R.string.group_name_error), Toast.LENGTH_SHORT).show()
            return
        }
        val covenant = CovenantBottomSheet.newInstance(isChallenge = true)
        covenant.setCovenantListener(object : CovenantBottomSheet.CovenantListener {
            override fun onCovenantAccepted() {
                performCreateChallenge()
            }
        })
        covenant.show(childFragmentManager, "CovenantBottomSheet")
    }

    private fun performCreateChallenge() {
        val template = selectedTemplate ?: return
        val challengeName = nameEdit.text?.toString()?.trim().orEmpty()
        viewLifecycleOwner.lifecycleScope.launch {
            setLoading(true)
            try {
                val token = SecureTokenManager.Companion.getInstance(requireContext()).getAccessToken()
                if (token == null) {
                    showError(getString(R.string.error_unauthorized_message))
                    return@launch
                }
                val response = withContext(Dispatchers.IO) {
                    ApiClient.createChallenge(
                        accessToken = token,
                        templateId = template.id,
                        startDate = selectedStartDate.toString(),
                        groupName = challengeName
                    )
                }
                val group = response.challenge
                startActivity(
                    Intent(requireContext(), GroupDetailActivity::class.java).apply {
                        putExtra(GroupDetailActivity.EXTRA_GROUP_ID, group.id)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_NAME, group.name)
                        putExtra(GroupDetailActivity.EXTRA_GROUP_HAS_ICON, false)
                        putExtra(
                            GroupDetailActivity.EXTRA_GROUP_ICON_EMOJI,
                            EmojiUtils.normalizeOrFallback(template.icon_emoji, "🏆")
                        )
                    }
                )
                requireActivity().supportFragmentManager.popBackStack(null, 1)
            } catch (e: ApiException) {
                when (e.errorCode) {
                    "PREMIUM_REQUIRED", "GROUP_LIMIT_REACHED" -> showUpgradeDialog(e.message ?: "")
                    else -> showError(e.message ?: getString(R.string.challenge_create_failed))
                }
            } catch (_: Exception) {
                showError(getString(R.string.challenge_create_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(show: Boolean) {
        loading.visibility = if (show) View.VISIBLE else View.GONE
        startButton.isEnabled = !show
        startDateButton.isEnabled = !show
        nameEdit.isEnabled = !show
    }

    private fun showError(message: String) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun showUpgradeDialog(message: String) {
        MaterialAlertDialogBuilder(requireContext())
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

        fun newInstance(templateId: String): ChallengeSetupFragment {
            return ChallengeSetupFragment().apply {
                arguments = Bundle().apply { putString(ARG_TEMPLATE_ID, templateId) }
            }
        }
    }
}
