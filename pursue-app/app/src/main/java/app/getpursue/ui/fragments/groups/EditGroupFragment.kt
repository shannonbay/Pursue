package app.getpursue.ui.fragments.groups

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
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
import app.getpursue.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Edit Group form (UI spec 4.8.1). Admin/creator only.
 * Icon + name + description, Delete Group (creator only), Cancel / Save.
 */
class EditGroupFragment : Fragment() {

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_NAME = "name"
        private const val ARG_DESCRIPTION = "description"
        private const val ARG_ICON_EMOJI = "icon_emoji"
        private const val ARG_ICON_COLOR = "icon_color"
        private const val ARG_HAS_ICON = "has_icon"
        private const val ARG_IS_CREATOR = "is_creator"

        fun newInstance(
            groupId: String,
            name: String,
            description: String?,
            iconEmoji: String?,
            iconColor: String?,
            hasIcon: Boolean,
            isCreator: Boolean
        ): EditGroupFragment {
            return EditGroupFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_NAME, name)
                    putString(ARG_DESCRIPTION, description ?: "")
                    putString(ARG_ICON_EMOJI, iconEmoji ?: "")
                    putString(ARG_ICON_COLOR, iconColor ?: "")
                    putBoolean(ARG_HAS_ICON, hasIcon)
                    putBoolean(ARG_IS_CREATOR, isCreator)
                }
            }
        }
    }

    private lateinit var iconPreview: TextView
    private lateinit var iconContainer: FrameLayout
    private lateinit var chooseIconButton: MaterialButton
    private lateinit var groupNameInput: TextInputLayout
    private lateinit var groupNameEdit: TextInputEditText
    private lateinit var descriptionInput: TextInputLayout
    private lateinit var descriptionEdit: TextInputEditText
    private lateinit var deleteGroupButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var loadingIndicator: ProgressBar

    private var selectedEmoji: String? = null
    private var selectedColor: String = IconPickerBottomSheet.Companion.getRandomDefaultColor()

    private fun groupId(): String = requireArguments().getString(ARG_GROUP_ID)!!
    private fun initialName(): String = requireArguments().getString(ARG_NAME) ?: ""
    private fun initialDescription(): String = requireArguments().getString(ARG_DESCRIPTION) ?: ""
    private fun initialIconEmoji(): String? =
        requireArguments().getString(ARG_ICON_EMOJI)?.takeIf { it.isNotEmpty() }
    private fun initialIconColor(): String? =
        requireArguments().getString(ARG_ICON_COLOR)?.takeIf { it.isNotEmpty() }
    private fun isCreator(): Boolean = requireArguments().getBoolean(ARG_IS_CREATOR)

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
        iconContainer = view.findViewById(R.id.icon_container)
        chooseIconButton = view.findViewById(R.id.button_choose_icon)
        groupNameInput = view.findViewById(R.id.input_group_name)
        groupNameEdit = view.findViewById(R.id.edit_group_name)
        descriptionInput = view.findViewById(R.id.input_description)
        descriptionEdit = view.findViewById(R.id.edit_description)
        deleteGroupButton = view.findViewById(R.id.button_delete_group)
        cancelButton = view.findViewById(R.id.button_cancel)
        saveButton = view.findViewById(R.id.button_save)
        loadingIndicator = view.findViewById(R.id.loading_indicator)

        selectedEmoji = initialIconEmoji()
        selectedColor = initialIconColor() ?: IconPickerBottomSheet.Companion.getRandomDefaultColor()
        groupNameEdit.setText(initialName())
        descriptionEdit.setText(initialDescription())

        deleteGroupButton.visibility = if (isCreator()) View.VISIBLE else View.GONE
        updateIconPreview()

        chooseIconButton.setOnClickListener { showIconPicker() }
        cancelButton.setOnClickListener { requireActivity().supportFragmentManager.popBackStack() }
        saveButton.setOnClickListener { saveGroup() }
        deleteGroupButton.setOnClickListener { showDeleteConfirmation() }

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

    override fun onResume() {
        super.onResume()
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title =
            getString(R.string.menu_edit_group)
    }

    override fun onStop() {
        super.onStop()
        (requireActivity() as? AppCompatActivity)?.supportActionBar?.title =
            initialName()
    }

    private fun updateIconPreview() {
        if (selectedEmoji != null) {
            iconPreview.text = selectedEmoji
        } else {
            val name = groupNameEdit.text?.toString()?.take(1)?.uppercase() ?: "?"
            iconPreview.text = name
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
            override fun onIconSelected(emoji: String?, color: String?) {
                if (emoji != null) selectedEmoji = emoji
                if (color != null) selectedColor = color
                updateIconPreview()
            }
        })
        sheet.show(childFragmentManager, "IconPickerBottomSheet")
    }

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

    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        saveButton.isEnabled = !show
        cancelButton.isEnabled = !show
        deleteGroupButton.isEnabled = !show
        chooseIconButton.isEnabled = !show
    }

    private fun saveGroup() {
        if (!validateGroupName() || !validateDescription()) return

        val name = groupNameEdit.text?.toString()?.trim() ?: ""
        val description = descriptionEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val iconEmoji = selectedEmoji
        val iconColor = if (selectedEmoji != null) selectedColor else null

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
                        iconColor = iconColor
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
