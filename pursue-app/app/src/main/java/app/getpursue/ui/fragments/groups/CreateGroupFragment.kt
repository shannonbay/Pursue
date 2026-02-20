package app.getpursue.ui.fragments.groups

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.os.Handler
import android.os.Looper
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.ui.fragments.home.HomeFragment
import app.getpursue.ui.views.CovenantBottomSheet
import app.getpursue.ui.views.IconPickerBottomSheet
import app.getpursue.utils.IconUrlUtils
import app.getpursue.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fragment for creating a new group (UI spec section 4.8).
 * 
 * Handles form input, validation, icon selection, and API call to create group.
 */
class CreateGroupFragment : Fragment() {

    private lateinit var iconPreview: TextView
    private lateinit var iconImage: ImageView
    private lateinit var iconContainer: FrameLayout
    private lateinit var chooseIconButton: MaterialButton
    private lateinit var groupNameInput: TextInputLayout
    private lateinit var groupNameEdit: TextInputEditText
    private lateinit var descriptionInput: TextInputLayout
    private lateinit var descriptionEdit: TextInputEditText
    private lateinit var createButton: MaterialButton
    private lateinit var cancelButton: MaterialButton
    private lateinit var loadingIndicator: ProgressBar

    private var selectedEmoji: String? = null
    private var selectedColor: String = IconPickerBottomSheet.Companion.getRandomDefaultColor()
    private var selectedIconUrl: String? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.fragment_create_group, container, false)
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
        createButton = view.findViewById(R.id.button_create)
        cancelButton = view.findViewById(R.id.button_cancel)
        loadingIndicator = view.findViewById(R.id.loading_indicator)

        // Setup icon preview
        updateIconPreview()

        // Setup icon picker button
        chooseIconButton.setOnClickListener {
            showIconPicker()
        }

        // Setup form validation
        groupNameEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateGroupName()
                // Update icon preview if no emoji is selected
                if (selectedEmoji == null) {
                    updateIconPreview()
                }
            }
        })

        descriptionEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                validateDescription()
            }
        })

        // Setup buttons
        createButton.setOnClickListener {
            createGroup()
        }

        cancelButton.setOnClickListener {
            requireActivity().supportFragmentManager.popBackStack()
        }
    }

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
                // Default to first letter of group name with random color background
                val name = groupNameEdit.text?.toString()?.take(1)?.uppercase() ?: "?"
                iconPreview.text = name
            }
        }

        // Use backgroundTintList to properly tint the drawable background
        try {
            iconContainer.backgroundTintList = ColorStateList.valueOf(
                Color.parseColor(selectedColor)
            )
        } catch (e: IllegalArgumentException) {
            // Keep default color if parsing fails
        }
    }

    private fun showIconPicker() {
        val bottomSheet = IconPickerBottomSheet.Companion.newInstance(R.string.icon_picker_title)
        bottomSheet.setIconSelectionListener(object : IconPickerBottomSheet.IconSelectionListener {
            override fun onIconSelected(emoji: String?, color: String?, iconUrl: String?) {
                if (emoji != null) {
                    selectedEmoji = emoji
                    selectedIconUrl = null
                } else if (iconUrl != null) {
                    selectedIconUrl = iconUrl
                    selectedEmoji = null
                }
                if (color != null) {
                    selectedColor = color
                }
                updateIconPreview()
            }
        })
        bottomSheet.show(childFragmentManager, "IconPickerBottomSheet")
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
                updateIconPreview() // Update icon preview with first letter
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

    private fun createGroup() {
        if (!validateGroupName()) {
            return
        }

        if (!validateDescription()) {
            return
        }

        val covenant = CovenantBottomSheet.newInstance(isChallenge = false)
        covenant.setCovenantListener(object : CovenantBottomSheet.CovenantListener {
            override fun onCovenantAccepted() {
                performCreateGroup()
            }
        })
        covenant.show(childFragmentManager, "CovenantBottomSheet")
    }

    private fun performCreateGroup() {
        val name = groupNameEdit.text?.toString()?.trim() ?: ""
        val description = descriptionEdit.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val iconEmoji = selectedEmoji
        val iconColor = if (selectedEmoji != null) selectedColor else null

        showLoading(true)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                if (!isAdded) return@launch

                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()

                if (accessToken == null) {
                    Handler(Looper.getMainLooper()).post {
                        showLoading(false)
                        Toast.makeText(requireContext(), "Please sign in", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val response = withContext(Dispatchers.IO) {
                    ApiClient.createGroup(
                        accessToken = accessToken,
                        name = name,
                        description = description,
                        iconEmoji = iconEmoji,
                        iconColor = iconColor,
                        iconUrl = selectedIconUrl
                    )
                }

                if (!isAdded) return@launch

                Handler(Looper.getMainLooper()).post {
                    showLoading(false)
                    Toast.makeText(requireContext(), getString(R.string.group_created), Toast.LENGTH_SHORT).show()

                    // Navigate to GroupDetailActivity for the new group
                    val intent = Intent(requireContext(), app.getpursue.ui.activities.GroupDetailActivity::class.java).apply {
                        putExtra(app.getpursue.ui.activities.GroupDetailActivity.EXTRA_GROUP_ID, response.id)
                        putExtra(app.getpursue.ui.activities.GroupDetailActivity.EXTRA_GROUP_NAME, response.name)
                        putExtra(app.getpursue.ui.activities.GroupDetailActivity.EXTRA_GROUP_HAS_ICON, response.has_icon)
                        putExtra(app.getpursue.ui.activities.GroupDetailActivity.EXTRA_GROUP_ICON_EMOJI, response.icon_emoji)
                        putExtra(app.getpursue.ui.activities.GroupDetailActivity.EXTRA_OPEN_INVITE_SHEET, true)
                    }
                    startActivity(intent)

                    // Remove CreateGroupFragment from backstack so back from Detail goes to Home
                    requireActivity().supportFragmentManager.popBackStack()
                }
            } catch (e: ApiException) {
                if (!isAdded) return@launch

                Handler(Looper.getMainLooper()).post {
                    showLoading(false)
                    val errorMessage = when (e.code) {
                        400 -> "Invalid group data. Please check your input."
                        401 -> "Please sign in again"
                        500, 503 -> "Server error. Please try again later."
                        else -> getString(R.string.group_creation_failed)
                    }
                    Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch

                Handler(Looper.getMainLooper()).post {
                    showLoading(false)
                    Toast.makeText(requireContext(), getString(R.string.group_creation_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun showLoading(show: Boolean) {
        Handler(Looper.getMainLooper()).post {
            loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
            createButton.isEnabled = !show
            cancelButton.isEnabled = !show
            groupNameEdit.isEnabled = !show
            descriptionEdit.isEnabled = !show
            chooseIconButton.isEnabled = !show
        }
    }

    companion object {
        fun newInstance(): CreateGroupFragment {
            return CreateGroupFragment()
        }
    }
}
