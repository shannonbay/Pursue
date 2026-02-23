package app.getpursue.ui.fragments.groups

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.activities.GroupDetailActivity
import app.getpursue.ui.views.IconPickerBottomSheet
import app.getpursue.utils.IconUrlUtils
import app.getpursue.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edit Group form (UI spec 4.8.1). Admin/creator only.
 * Icon + name + description + visibility + spot limit + category, Delete Group (creator only), Cancel / Save.
 */
class EditGroupFragment : Fragment() {

    companion object {
        private const val ARG_GROUP_ID      = "group_id"
        private const val ARG_NAME          = "name"
        private const val ARG_DESCRIPTION   = "description"
        private const val ARG_ICON_EMOJI    = "icon_emoji"
        private const val ARG_ICON_COLOR    = "icon_color"
        private const val ARG_ICON_URL      = "icon_url"
        private const val ARG_HAS_ICON      = "has_icon"
        private const val ARG_IS_CREATOR    = "is_creator"
        private const val ARG_VISIBILITY    = "visibility"
        private const val ARG_CATEGORY      = "category"
        private const val ARG_SPOT_LIMIT    = "spot_limit"
        private const val ARG_COMM_PLATFORM = "comm_platform"
        private const val ARG_COMM_LINK     = "comm_link"

        /** API value → display label for comm platforms (null = None). */
        val COMM_PLATFORMS: List<Pair<String?, String>> = listOf(
            null        to "None",
            "discord"   to "Discord",
            "whatsapp"  to "WhatsApp",
            "telegram"  to "Telegram"
        )

        /** API value → display label mapping for group categories. */
        val CATEGORIES: List<Pair<String, String>> = listOf(
            "fitness"      to "Fitness & Exercise",
            "nutrition"    to "Nutrition & Diet",
            "mindfulness"  to "Mindfulness & Mental Health",
            "learning"     to "Learning & Skills",
            "creativity"   to "Creativity & Arts",
            "productivity" to "Productivity & Career",
            "finance"      to "Finance & Savings",
            "social"       to "Social & Relationships",
            "lifestyle"    to "Lifestyle & Habits",
            "sports"       to "Sports & Training",
            "other"        to "Other"
        )

        fun newInstance(
            groupId: String,
            name: String,
            description: String?,
            iconEmoji: String?,
            iconColor: String?,
            iconUrl: String?,
            hasIcon: Boolean,
            isCreator: Boolean,
            visibility: String? = null,
            category: String? = null,
            spotLimit: Int? = null,
            commPlatform: String? = null,
            commLink: String? = null
        ): EditGroupFragment {
            return EditGroupFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_NAME, name)
                    putString(ARG_DESCRIPTION, description ?: "")
                    putString(ARG_ICON_EMOJI, iconEmoji ?: "")
                    putString(ARG_ICON_COLOR, iconColor ?: "")
                    putString(ARG_ICON_URL, iconUrl ?: "")
                    putBoolean(ARG_HAS_ICON, hasIcon)
                    putBoolean(ARG_IS_CREATOR, isCreator)
                    putString(ARG_VISIBILITY, visibility ?: "private")
                    putString(ARG_CATEGORY, category ?: "")
                    if (spotLimit != null) putInt(ARG_SPOT_LIMIT, spotLimit)
                    putString(ARG_COMM_PLATFORM, commPlatform ?: "")
                    putString(ARG_COMM_LINK, commLink ?: "")
                }
            }
        }
    }

    private lateinit var iconPreview: TextView
    private lateinit var iconImage: ImageView
    private lateinit var iconContainer: FrameLayout
    private lateinit var chooseIconButton: MaterialButton
    private lateinit var groupNameInput: TextInputLayout
    private lateinit var groupNameEdit: TextInputEditText
    private lateinit var descriptionInput: TextInputLayout
    private lateinit var descriptionEdit: TextInputEditText
    private lateinit var switchPublicListing: SwitchMaterial
    private lateinit var btnVisibilityInfo: ImageView
    private lateinit var containerSpotLimit: LinearLayout
    private lateinit var radioGroupSpotLimit: RadioGroup
    private lateinit var radioUnlimited: RadioButton
    private lateinit var radioCustomLimit: RadioButton
    private lateinit var inputSpotLimit: TextInputLayout
    private lateinit var editSpotLimit: TextInputEditText
    private lateinit var inputCategory: TextInputLayout
    private lateinit var dropdownCategory: AutoCompleteTextView
    private lateinit var inputCommPlatform: TextInputLayout
    private lateinit var dropdownCommPlatform: AutoCompleteTextView
    private lateinit var inputCommLink: TextInputLayout
    private lateinit var editCommLink: TextInputEditText
    private lateinit var deleteGroupButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var loadingIndicator: ProgressBar

    private var selectedEmoji: String? = null
    private var selectedColor: String = IconPickerBottomSheet.Companion.getRandomDefaultColor()
    private var selectedIconUrl: String? = null
    private var selectedVisibility: String = "private"
    private var selectedCategory: String? = null
    private var selectedSpotLimit: Int? = null
    private var selectedCommPlatform: String? = null

    // ─── Arg accessors ────────────────────────────────────────────────────────

    private fun groupId(): String = requireArguments().getString(ARG_GROUP_ID)!!
    private fun initialName(): String = requireArguments().getString(ARG_NAME) ?: ""
    private fun initialDescription(): String = requireArguments().getString(ARG_DESCRIPTION) ?: ""
    private fun initialIconEmoji(): String? =
        requireArguments().getString(ARG_ICON_EMOJI)?.takeIf { it.isNotEmpty() }
    private fun initialIconColor(): String? =
        requireArguments().getString(ARG_ICON_COLOR)?.takeIf { it.isNotEmpty() }
    private fun initialIconUrl(): String? =
        requireArguments().getString(ARG_ICON_URL)?.takeIf { it.isNotEmpty() }
    private fun isCreator(): Boolean = requireArguments().getBoolean(ARG_IS_CREATOR)
    private fun initialVisibility(): String =
        requireArguments().getString(ARG_VISIBILITY) ?: "private"
    private fun initialCategory(): String? =
        requireArguments().getString(ARG_CATEGORY)?.takeIf { it.isNotEmpty() }
    private fun initialSpotLimit(): Int? =
        if (requireArguments().containsKey(ARG_SPOT_LIMIT)) requireArguments().getInt(ARG_SPOT_LIMIT) else null
    private fun initialCommPlatform(): String? =
        requireArguments().getString(ARG_COMM_PLATFORM)?.takeIf { it.isNotEmpty() }
    private fun initialCommLink(): String? =
        requireArguments().getString(ARG_COMM_LINK)?.takeIf { it.isNotEmpty() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_edit_group, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        iconPreview = view.findViewById(R.id.icon_preview)
        iconImage = view.findViewById(R.id.icon_image)
        iconContainer = view.findViewById(R.id.icon_container)
        chooseIconButton = view.findViewById(R.id.button_choose_icon)
        groupNameInput = view.findViewById(R.id.input_group_name)
        groupNameEdit = view.findViewById(R.id.edit_group_name)
        descriptionInput = view.findViewById(R.id.input_description)
        descriptionEdit = view.findViewById(R.id.edit_description)
        switchPublicListing = view.findViewById(R.id.switch_public_listing)
        btnVisibilityInfo = view.findViewById(R.id.btn_visibility_info)
        containerSpotLimit = view.findViewById(R.id.container_spot_limit)
        radioGroupSpotLimit = view.findViewById(R.id.radio_group_spot_limit)
        radioUnlimited = view.findViewById(R.id.radio_unlimited)
        radioCustomLimit = view.findViewById(R.id.radio_custom_limit)
        inputSpotLimit = view.findViewById(R.id.input_spot_limit)
        editSpotLimit = view.findViewById(R.id.edit_spot_limit)
        inputCategory = view.findViewById(R.id.input_category)
        dropdownCategory = view.findViewById(R.id.dropdown_category)
        inputCommPlatform = view.findViewById(R.id.input_comm_platform)
        dropdownCommPlatform = view.findViewById(R.id.dropdown_comm_platform)
        inputCommLink = view.findViewById(R.id.input_comm_link)
        editCommLink = view.findViewById(R.id.edit_comm_link)
        deleteGroupButton = view.findViewById(R.id.button_delete_group)
        cancelButton = view.findViewById(R.id.button_cancel)
        saveButton = view.findViewById(R.id.button_save)
        loadingIndicator = view.findViewById(R.id.loading_indicator)

        // Pre-populate existing values
        selectedEmoji = initialIconEmoji()
        selectedColor = initialIconColor() ?: IconPickerBottomSheet.Companion.getRandomDefaultColor()
        selectedIconUrl = initialIconUrl()
        selectedVisibility = initialVisibility()
        selectedCategory = initialCategory()
        selectedSpotLimit = initialSpotLimit()
        selectedCommPlatform = initialCommPlatform()

        groupNameEdit.setText(initialName())
        descriptionEdit.setText(initialDescription())
        deleteGroupButton.visibility = if (isCreator()) View.VISIBLE else View.GONE
        updateIconPreview()

        setupVisibilitySwitch()
        setupSpotLimitSection()
        setupCategoryDropdown()
        setupCommPlatformDropdown()
        setupTextWatchers()

        chooseIconButton.setOnClickListener { showIconPicker() }
        cancelButton.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }
        saveButton.setOnClickListener { saveGroup() }
        deleteGroupButton.setOnClickListener { showDeleteConfirmation() }
    }

    override fun onResume() {
        super.onResume()
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title =
            getString(R.string.menu_edit_group)
    }

    override fun onStop() {
        super.onStop()
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title = initialName()
    }

    // ─── Setup helpers ────────────────────────────────────────────────────────

    private fun setupVisibilitySwitch() {
        // Set initial state without triggering listener side effects
        switchPublicListing.isChecked = selectedVisibility == "public"
        containerSpotLimit.visibility = if (selectedVisibility == "public") View.VISIBLE else View.GONE

        switchPublicListing.setOnCheckedChangeListener { _, isChecked ->
            selectedVisibility = if (isChecked) "public" else "private"
            containerSpotLimit.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        btnVisibilityInfo.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.label_public_listing))
                .setMessage(getString(R.string.public_listing_tooltip))
                .setPositiveButton(getString(android.R.string.ok), null)
                .show()
        }
    }

    private fun setupSpotLimitSection() {
        val initialLimit = selectedSpotLimit
        if (initialLimit != null) {
            radioCustomLimit.isChecked = true
            inputSpotLimit.visibility = View.VISIBLE
            editSpotLimit.setText(initialLimit.toString())
        } else {
            radioUnlimited.isChecked = true
            inputSpotLimit.visibility = View.GONE
        }

        radioGroupSpotLimit.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.radio_unlimited -> {
                    inputSpotLimit.visibility = View.GONE
                    // Preserve selectedSpotLimit in memory; clear on save if unlimited selected
                }
                R.id.radio_custom_limit -> {
                    inputSpotLimit.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun setupCategoryDropdown() {
        val displayNames = CATEGORIES.map { it.second }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayNames)
        dropdownCategory.setAdapter(adapter)

        // Pre-select if category already set
        val cat = selectedCategory
        if (cat != null) {
            val display = CATEGORIES.find { it.first == cat }?.second
            if (display != null) {
                dropdownCategory.setText(display, false)
            }
        }

        dropdownCategory.setOnItemClickListener { _, _, position, _ ->
            selectedCategory = CATEGORIES[position].first
            inputCategory.error = null
        }
    }

    private fun setupCommPlatformDropdown() {
        val displayNames = COMM_PLATFORMS.map { it.second }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, displayNames)
        dropdownCommPlatform.setAdapter(adapter)

        // Pre-select current platform
        val currentPlatform = selectedCommPlatform
        val currentDisplay = COMM_PLATFORMS.find { it.first == currentPlatform }?.second ?: "None"
        dropdownCommPlatform.setText(currentDisplay, false)

        // Show link input if platform already set
        if (currentPlatform != null) {
            inputCommLink.visibility = View.VISIBLE
            editCommLink.setText(initialCommLink() ?: "")
            updateCommLinkHint(currentPlatform)
        } else {
            inputCommLink.visibility = View.GONE
        }

        dropdownCommPlatform.setOnItemClickListener { _, _, position, _ ->
            val chosenPlatform = COMM_PLATFORMS[position].first
            selectedCommPlatform = chosenPlatform
            inputCommPlatform.error = null
            if (chosenPlatform != null) {
                inputCommLink.visibility = View.VISIBLE
                updateCommLinkHint(chosenPlatform)
            } else {
                inputCommLink.visibility = View.GONE
                editCommLink.setText("")
                inputCommLink.error = null
            }
        }
    }

    private fun updateCommLinkHint(platform: String) {
        inputCommLink.hint = when (platform) {
            "discord"  -> "https://discord.gg/…"
            "whatsapp" -> "https://chat.whatsapp.com/…"
            "telegram" -> "https://t.me/…"
            else       -> getString(R.string.label_comm_link)
        }
    }

    private fun setupTextWatchers() {
        groupNameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateGroupName()
                if (selectedEmoji == null) updateIconPreview()
            }
        })
        descriptionEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { validateDescription() }
        })
    }

    // ─── Icon preview ─────────────────────────────────────────────────────────

    private fun updateIconPreview() {
        if (selectedIconUrl != null) {
            iconImage.visibility = View.VISIBLE
            iconPreview.visibility = View.GONE
            IconUrlUtils.loadInto(requireContext(), selectedIconUrl!!, iconImage)
        } else {
            iconImage.visibility = View.GONE
            iconPreview.visibility = View.VISIBLE
            if (selectedEmoji != null) {
                iconPreview.text = selectedEmoji
            } else {
                val name = groupNameEdit.text?.toString()?.take(1)?.uppercase() ?: "?"
                iconPreview.text = name
            }
        }
        try {
            iconContainer.backgroundTintList = ColorStateList.valueOf(Color.parseColor(selectedColor))
        } catch (_: IllegalArgumentException) {}
    }

    private fun showIconPicker() {
        val sheet = IconPickerBottomSheet.Companion.newInstance(
            R.string.icon_picker_title,
            initialEmoji = selectedEmoji,
            initialColor = selectedColor
        )
        sheet.setIconSelectionListener(object : IconPickerBottomSheet.IconSelectionListener {
            override fun onIconSelected(emoji: String?, color: String?, iconUrl: String?) {
                if (emoji != null) {
                    selectedEmoji = emoji
                    selectedIconUrl = null
                } else if (iconUrl != null) {
                    selectedIconUrl = iconUrl
                    selectedEmoji = null
                }
                if (color != null) selectedColor = color
                updateIconPreview()
            }
        })
        sheet.show(childFragmentManager, "IconPickerBottomSheet")
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    private fun validateGroupName(): Boolean {
        val name = groupNameEdit.text?.toString()?.trim() ?: ""
        return when {
            name.isEmpty() -> {
                groupNameInput.error = getString(R.string.group_name_error)
                false
            }
            name.length > 100 -> {
                groupNameInput.error = getString(R.string.group_name_error)
                false
            }
            else -> {
                groupNameInput.error = null
                if (selectedEmoji == null) updateIconPreview()
                true
            }
        }
    }

    private fun validateDescription(): Boolean {
        val description = descriptionEdit.text?.toString() ?: ""
        return if (description.length > 500) {
            descriptionInput.error = getString(R.string.description_char_counter, description.length)
            false
        } else {
            descriptionInput.error = null
            true
        }
    }

    private fun validateCategory(): Boolean {
        return if (selectedCategory == null) {
            inputCategory.error = getString(R.string.category_required_error)
            false
        } else {
            inputCategory.error = null
            true
        }
    }

    private fun validateSpotLimit(): Boolean {
        if (radioCustomLimit.isChecked) {
            val raw = editSpotLimit.text?.toString()?.trim() ?: ""
            val value = raw.toIntOrNull()
            return if (value == null || value < 2 || value > 500) {
                inputSpotLimit.error = getString(R.string.spot_limit_error)
                false
            } else {
                inputSpotLimit.error = null
                selectedSpotLimit = value
                true
            }
        }
        selectedSpotLimit = null
        return true
    }

    private fun validateCommLink(): Boolean {
        val platform = selectedCommPlatform ?: return true // None selected — always valid
        val link = editCommLink.text?.toString()?.trim() ?: ""
        if (link.isEmpty()) {
            // Allow clearing the link by leaving it blank
            return true
        }
        val valid = when (platform) {
            "discord"  -> link.startsWith("https://discord.gg/") || link.startsWith("https://discord.com/invite/")
            "whatsapp" -> link.startsWith("https://chat.whatsapp.com/")
            "telegram" -> link.startsWith("https://t.me/")
            else       -> false
        }
        return if (!valid) {
            val errorRes = when (platform) {
                "discord"  -> R.string.comm_link_error_discord
                "whatsapp" -> R.string.comm_link_error_whatsapp
                "telegram" -> R.string.comm_link_error_telegram
                else       -> R.string.comm_link_error_discord
            }
            inputCommLink.error = getString(errorRes)
            false
        } else {
            inputCommLink.error = null
            true
        }
    }

    // ─── Save flow ────────────────────────────────────────────────────────────

    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        saveButton.isEnabled = !show
        cancelButton.isEnabled = !show
        deleteGroupButton.isEnabled = !show
        chooseIconButton.isEnabled = !show
        switchPublicListing.isEnabled = !show
        radioGroupSpotLimit.isEnabled = !show
        radioUnlimited.isEnabled = !show
        radioCustomLimit.isEnabled = !show
        editSpotLimit.isEnabled = !show
        dropdownCategory.isEnabled = !show
        dropdownCommPlatform.isEnabled = !show
        editCommLink.isEnabled = !show
    }

    private fun saveGroup() {
        if (!validateGroupName() || !validateDescription()) return
        if (!validateCategory()) return
        if (!validateSpotLimit()) return
        if (!validateCommLink()) return

        val name = groupNameEdit.text?.toString()?.trim() ?: ""
        val description = descriptionEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val iconEmoji = selectedEmoji
        val iconColor = if (selectedEmoji != null) selectedColor else null
        val iconUrl = selectedIconUrl
        val spotLimitToSend = if (radioCustomLimit.isChecked) selectedSpotLimit else null
        val commPlatformToSend = selectedCommPlatform
        val commLinkToSend = editCommLink.text?.toString()?.trim()?.takeIf {
            it.isNotEmpty() && commPlatformToSend != null
        }

        showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val token = tokenManager.getAccessToken()
                if (token == null) {
                    showLoading(false)
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ApiClient.patchGroup(
                        accessToken = token,
                        groupId = groupId(),
                        name = name,
                        description = description,
                        iconEmoji = iconEmoji,
                        iconColor = iconColor,
                        iconUrl = iconUrl,
                        visibility = selectedVisibility,
                        category = selectedCategory,
                        spotLimit = spotLimitToSend,
                        commPlatform = commPlatformToSend,
                        commLink = commLinkToSend
                    )
                }
                if (!isAdded) return@launch
                showLoading(false)
                Toast.makeText(requireContext(), getString(R.string.group_updated_toast), Toast.LENGTH_SHORT).show()
                requireActivity().supportFragmentManager.popBackStack()
            } catch (e: ApiException) {
                if (!isAdded) return@launch
                showLoading(false)
                Toast.makeText(requireContext(), e.message ?: getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                showLoading(false)
                Toast.makeText(requireContext(), getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showDeleteConfirmation() {
        val name = groupNameEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: initialName()
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.delete_group_dialog_title, name))
            .setMessage(getString(R.string.delete_group_dialog_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ -> performDelete() }
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(
            ContextCompat.getColor(requireContext(), R.color.secondary)
        )
    }

    private fun performDelete() {
        showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val token = tokenManager.getAccessToken()
                if (token == null) {
                    showLoading(false)
                    Toast.makeText(requireContext(), getString(R.string.error_unauthorized_message), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    ApiClient.deleteGroup(token, groupId())
                }
                if (!isAdded) return@launch
                showLoading(false)
                Toast.makeText(requireContext(), getString(R.string.group_deleted_toast), Toast.LENGTH_SHORT).show()
                requireActivity().setResult(GroupDetailActivity.Companion.RESULT_GROUP_DELETED)
                requireActivity().finish()
            } catch (e: ApiException) {
                if (!isAdded) return@launch
                showLoading(false)
                Toast.makeText(requireContext(), e.message ?: getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                if (!isAdded) return@launch
                showLoading(false)
                Toast.makeText(requireContext(), getString(R.string.error_failed_to_load_groups), Toast.LENGTH_SHORT).show()
            }
        }
    }
}
