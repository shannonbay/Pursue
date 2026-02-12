package app.getpursue.ui.views

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import app.getpursue.R
import java.io.File

/**
 * Bottom sheet shown after progress is logged, offering Camera or Gallery to add a photo.
 * Auto-dismisses after 3 seconds if the user takes no action.
 */
class PostLogPhotoBottomSheet : BottomSheetDialogFragment() {

    interface PhotoSelectedListener {
        fun onPhotoSelected(uri: Uri)
    }

    private var listener: PhotoSelectedListener? = null
    private var photoUri: Uri? = null
    private val autoDismissHandler = Handler(Looper.getMainLooper())
    private var autoDismissRunnable: Runnable? = null

    private val pickImageLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { onPhotoSelected(it) }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            photoUri?.let { onPhotoSelected(it) }
        }
    }

    private val requestCameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            takePhoto()
        } else {
            Toast.makeText(requireContext(), R.string.camera_permission_required, Toast.LENGTH_SHORT).show()
        }
    }

    fun setPhotoSelectedListener(listener: PhotoSelectedListener?) {
        this.listener = listener
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_post_log_photo, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<MaterialButton>(R.id.button_camera).setOnClickListener {
            cancelAutoDismiss()
            checkCameraPermissionAndTakePhoto()
        }

        view.findViewById<MaterialButton>(R.id.button_gallery).setOnClickListener {
            cancelAutoDismiss()
            pickImageLauncher.launch("image/*")
        }

        startAutoDismissTimer()
    }

    override fun onDestroyView() {
        cancelAutoDismiss()
        super.onDestroyView()
    }

    private fun startAutoDismissTimer() {
        autoDismissRunnable = Runnable {
            dismiss()
        }
        autoDismissHandler.postDelayed(autoDismissRunnable!!, 3000)
    }

    private fun cancelAutoDismiss() {
        autoDismissRunnable?.let { autoDismissHandler.removeCallbacks(it) }
        autoDismissRunnable = null
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
            Log.e("PostLogPhotoBottomSheet", "Failed to create photo file", e)
            Toast.makeText(requireContext(), R.string.photo_capture_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun createTempImageFile(): File {
        val storageDir = requireContext().getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "progress_photo_${System.currentTimeMillis()}",
            ".jpg",
            storageDir
        )
    }

    private fun onPhotoSelected(uri: Uri) {
        cancelAutoDismiss()
        listener?.onPhotoSelected(uri)
        dismiss()
    }

    companion object {
        private const val ARG_PROGRESS_ENTRY_ID = "progress_entry_id"

        fun newInstance(progressEntryId: String): PostLogPhotoBottomSheet {
            return PostLogPhotoBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_PROGRESS_ENTRY_ID, progressEntryId)
                }
            }
        }

        fun show(fragmentManager: FragmentManager, progressEntryId: String, listener: PhotoSelectedListener) {
            val sheet = newInstance(progressEntryId).apply {
                setPhotoSelectedListener(listener)
            }
            sheet.show(fragmentManager, "PostLogPhotoBottomSheet")
        }
    }
}
