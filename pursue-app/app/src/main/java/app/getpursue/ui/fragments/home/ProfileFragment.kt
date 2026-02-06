package app.getpursue.ui.fragments.home

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.signature.ObjectKey
import app.getpursue.R
import app.getpursue.utils.ImageUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import app.getpursue.data.network.ApiException
import app.getpursue.data.network.User
import app.getpursue.data.notifications.NotificationPreferences
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Profile Screen fragment (UI spec section 4.5).
 * 
 * Displays user profile with avatar, supports avatar upload/delete.
 */
class ProfileFragment : Fragment() {

    interface Callbacks {
        fun onViewMyProgress()
        fun onUpgradeToPremium()
        fun onSignOut()
    }

    private var callbacks: Callbacks? = null
    private lateinit var avatarImage: ImageView
    private lateinit var displayName: TextView
    private lateinit var changeAvatarButton: MaterialButton
    private lateinit var removeAvatarButton: MaterialButton
    private lateinit var switchNotifyProgressLogs: MaterialSwitch
    private lateinit var switchNotifyGroupEvents: MaterialSwitch
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var subscriptionStatus: TextView
    private lateinit var subscriptionRenews: TextView
    private lateinit var buttonUpgradePremium: MaterialButton

    private var currentUser: User? = null
    private var photoUri: Uri? = null
    private var userGroupIds: List<String> = emptyList()

    // Image picker launcher
    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { startImageCrop(it) }
    }

    // Camera launcher
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoUri?.let { startImageCrop(it) }
        }
    }

    // Permission launcher for camera
    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            takePhoto()
        } else {
            Toast.makeText(requireContext(), "Camera permission is required to take photos", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        callbacks = context as? Callbacks
    }

    override fun onDetach() {
        super.onDetach()
        callbacks = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.fragment_profile, container, false)
        
        avatarImage = view.findViewById(R.id.avatar_image)
        displayName = view.findViewById(R.id.display_name)
        changeAvatarButton = view.findViewById(R.id.button_change_avatar)
        removeAvatarButton = view.findViewById(R.id.button_remove_avatar)
        switchNotifyProgressLogs = view.findViewById(R.id.switch_notify_progress_logs)
        switchNotifyGroupEvents = view.findViewById(R.id.switch_notify_group_events)
        loadingIndicator = view.findViewById(R.id.loading_indicator)
        subscriptionStatus = view.findViewById(R.id.subscription_status)
        subscriptionRenews = view.findViewById(R.id.subscription_renews)
        buttonUpgradePremium = view.findViewById(R.id.button_upgrade_premium)

        buttonUpgradePremium.setOnClickListener { callbacks?.onUpgradeToPremium() }

        switchNotifyProgressLogs.isChecked = NotificationPreferences.getNotifyProgressLogs(requireContext())
        switchNotifyGroupEvents.isChecked = NotificationPreferences.getNotifyGroupEvents(requireContext())
        switchNotifyProgressLogs.setOnCheckedChangeListener { _, isChecked ->
            NotificationPreferences.setNotifyProgressLogsWithTopics(requireContext(), isChecked, userGroupIds)
        }
        switchNotifyGroupEvents.setOnCheckedChangeListener { _, isChecked ->
            NotificationPreferences.setNotifyGroupEventsWithTopics(requireContext(), isChecked, userGroupIds)
        }
        
        // Set up click listeners
        avatarImage.setOnClickListener {
            showImageSourceDialog()
        }
        
        changeAvatarButton.setOnClickListener {
            showImageSourceDialog()
        }
        
        removeAvatarButton.setOnClickListener {
            deleteAvatar()
        }

        view.findViewById<MaterialButton>(R.id.button_sign_out).setOnClickListener {
            callbacks?.onSignOut()
        }
        
        // Load user data and fetch group IDs for topic management
        loadUserData()
        fetchUserGroupIds()

        return view
    }

    /**
     * Fetch the list of group IDs the user is a member of.
     * Used for managing FCM topic subscriptions when preferences change.
     */
    private fun fetchUserGroupIds() {
        lifecycleScope.launch {
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken() ?: return@launch

                val groupsResponse = withContext(Dispatchers.IO) {
                    ApiClient.getMyGroups(accessToken)
                }
                userGroupIds = groupsResponse.groups.map { it.id }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Failed to fetch user groups for topic management", e)
                // Silent failure - topic management will just use empty list
            }
        }
    }

    private fun loadUserData() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()
                
                if (accessToken == null) {
                    Toast.makeText(requireContext(), "Please sign in", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                val user = withContext(Dispatchers.IO) {
                    ApiClient.getMyUser(accessToken)
                }
                val subscription = try {
                    withContext(Dispatchers.IO) { ApiClient.getSubscription(accessToken) }
                } catch (e: Exception) {
                    null
                }
                
                currentUser = user
                
                // Ensure UI operations run on main thread with looper to avoid issues in tests
                Handler(Looper.getMainLooper()).post {
                    displayName.text = user.display_name
                    
                    // Load avatar
                    loadAvatar(user)
                    
                    // Show/hide remove button based on has_avatar
                    removeAvatarButton.visibility = if (user.has_avatar) View.VISIBLE else View.GONE

                    // Subscription status
                    if (subscription != null) {
                        subscriptionStatus.text = if (subscription.tier == "premium")
                            getString(R.string.subscription_premium_format, subscription.current_group_count, subscription.group_limit)
                        else
                            getString(R.string.subscription_free_format, subscription.current_group_count, subscription.group_limit)
                        val expiresAt = subscription.subscription_expires_at
                        if (subscription.tier == "premium" && !expiresAt.isNullOrBlank()) {
                            subscriptionRenews.visibility = View.VISIBLE
                            subscriptionRenews.text = getString(R.string.subscription_renews_format, formatRenewsDate(expiresAt))
                        } else {
                            subscriptionRenews.visibility = View.GONE
                        }
                        buttonUpgradePremium.visibility = if (subscription.tier == "premium") View.GONE else View.VISIBLE
                    } else {
                        subscriptionStatus.text = getString(R.string.subscription_free_format, 0, 1)
                        subscriptionRenews.visibility = View.GONE
                        buttonUpgradePremium.visibility = View.VISIBLE
                    }
                }
            } catch (e: ApiException) {
                Log.e("ProfileFragment", "Failed to load user data", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), "Failed to load profile: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error loading user data", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), "Error loading profile", Toast.LENGTH_SHORT).show()
                }
            } finally {
                showLoading(false)
            }
        }
    }

    private fun formatRenewsDate(isoDate: String): String {
        return try {
            val inFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            val outFormat = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
            val date = inFormat.parse(isoDate) ?: run {
                SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(isoDate.take(10))
            }
            if (date != null) outFormat.format(date) else isoDate.take(10)
        } catch (e: Exception) {
            isoDate.take(10)
        }
    }

    private fun loadAvatar(user: User? = currentUser) {
        val userToLoad = user ?: currentUser ?: return
        
        if (userToLoad.has_avatar) {
            // Load avatar from API using Glide
            val imageUrl = "${ApiClient.getBaseUrl()}/users/${userToLoad.id}/avatar"
            
            val requestBuilder = Glide.with(requireContext())
                .load(imageUrl)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .circleCrop()
                .placeholder(R.drawable.ic_pursue_logo) // Placeholder while loading
                .error(R.drawable.ic_pursue_logo) // Fallback on error
            
            // Add cache signature based on updated_at for cache invalidation
            if (userToLoad.updated_at != null) {
                requestBuilder.signature(ObjectKey(userToLoad.updated_at))
            }
            
            requestBuilder.into(avatarImage)
        } else {
            // Show letter avatar
            val letterAvatar = ImageUtils.createLetterAvatar(
                requireContext(),
                userToLoad.display_name
            )
            avatarImage.setImageDrawable(letterAvatar)
        }
    }

    private fun showImageSourceDialog() {
        val options = arrayOf(
            getString(R.string.from_gallery),
            getString(R.string.from_camera)
        )
        
        if (currentUser?.has_avatar == true) {
            // Add remove option if user has avatar
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.select_image_source)
                .setItems(options + getString(R.string.remove_photo)) { _, which ->
                    when (which) {
                        0 -> selectFromGallery()
                        1 -> checkCameraPermissionAndTakePhoto()
                        2 -> deleteAvatar()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.select_image_source)
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> selectFromGallery()
                        1 -> checkCameraPermissionAndTakePhoto()
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun selectFromGallery() {
        pickImageLauncher.launch("image/*")
    }

    private fun checkCameraPermissionAndTakePhoto() {
        when {
            ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED -> {
                takePhoto()
            }
            else -> {
                requestCameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }

    private fun takePhoto() {
        try {
            val photoFile = createTempImageFile()
            photoUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                photoFile
            )
            takePictureLauncher.launch(photoUri)
        } catch (e: Exception) {
            Log.e("ProfileFragment", "Failed to create photo file", e)
            Toast.makeText(requireContext(), "Failed to take photo", Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTempImageFile(): File {
        val storageDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "avatar_photo_${System.currentTimeMillis()}",
            ".jpg",
            storageDir
        )
    }

    private fun startImageCrop(imageUri: Uri) {
        // For MVP, we'll upload directly without cropping
        // TODO: Integrate image cropper library for proper cropping UI
        // For now, upload the image as-is (backend will resize to 256x256)
        uploadAvatar(imageUri)
    }

    private fun uploadAvatar(imageUri: Uri) {
        lifecycleScope.launch {
            try {
                showLoading(true)
                
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()
                
                if (accessToken == null) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(requireContext(), "Please sign in", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                // Convert URI to File
                val imageFile = ImageUtils.uriToFile(requireContext(), imageUri)
                    ?: run {
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(requireContext(), "Failed to process image", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                
                // Upload to backend
                val response = withContext(Dispatchers.IO) {
                    ApiClient.uploadAvatar(accessToken, imageFile)
                }
                
                // Update current user state
                currentUser = currentUser?.copy(has_avatar = response.has_avatar)
                
                // Clean up temp file
                imageFile.delete()
                
                // Ensure UI operations run on main thread with looper to avoid issues in tests
                Handler(Looper.getMainLooper()).post {
                    // Reload avatar
                    currentUser?.let { loadAvatar(it) }
                    
                    // Show/hide remove button
                    removeAvatarButton.visibility = if (response.has_avatar) View.VISIBLE else View.GONE
                    
                    // Show success toast
                    Toast.makeText(requireContext(), getString(R.string.profile_picture_updated), Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: ApiException) {
                Log.e("ProfileFragment", "Failed to upload avatar", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), getString(R.string.profile_picture_upload_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error uploading avatar", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), getString(R.string.profile_picture_upload_failed), Toast.LENGTH_SHORT).show()
                }
            } finally {
                showLoading(false)
            }
        }
    }

    private fun deleteAvatar() {
        lifecycleScope.launch {
            try {
                showLoading(true)
                
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val accessToken = tokenManager.getAccessToken()
                
                if (accessToken == null) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(requireContext(), "Please sign in", Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                
                val response = withContext(Dispatchers.IO) {
                    ApiClient.deleteAvatar(accessToken)
                }
                
                // Update current user state
                currentUser = currentUser?.copy(has_avatar = response.has_avatar)
                
                // Ensure UI operations run on main thread with looper to avoid issues in tests
                Handler(Looper.getMainLooper()).post {
                    // Reload avatar (will show letter avatar)
                    currentUser?.let { loadAvatar(it) }
                    
                    // Hide remove button
                    removeAvatarButton.visibility = View.GONE
                    
                    // Show success toast
                    Toast.makeText(requireContext(), getString(R.string.profile_picture_removed), Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: ApiException) {
                Log.e("ProfileFragment", "Failed to delete avatar", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), getString(R.string.profile_picture_delete_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("ProfileFragment", "Error deleting avatar", e)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(requireContext(), getString(R.string.profile_picture_delete_failed), Toast.LENGTH_SHORT).show()
                }
            } finally {
                showLoading(false)
            }
        }
    }

    private fun showLoading(show: Boolean) {
        // Ensure UI operations run on main thread with looper to avoid issues in tests
        Handler(Looper.getMainLooper()).post {
            loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
            changeAvatarButton.isEnabled = !show
            removeAvatarButton.isEnabled = !show
            avatarImage.isEnabled = !show
        }
    }

    companion object {
        fun newInstance(): ProfileFragment = ProfileFragment()
    }
}
