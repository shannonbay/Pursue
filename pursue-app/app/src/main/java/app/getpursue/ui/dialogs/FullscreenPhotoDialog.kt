package app.getpursue.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.DialogFragment
import app.getpursue.R
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.github.chrisbanes.photoview.PhotoView

/**
 * Fullscreen dialog showing a progress photo with pinch-to-zoom and pan.
 * Dismiss via back press, tap on scrim, or tap on photo.
 */
class FullscreenPhotoDialog : DialogFragment() {

    companion object {
        private const val ARG_PHOTO_URL = "photo_url"

        fun newInstance(photoUrl: String): FullscreenPhotoDialog {
            return FullscreenPhotoDialog().apply {
                arguments = Bundle().apply {
                    putString(ARG_PHOTO_URL, photoUrl)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NORMAL, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.window?.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT
        )
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val view = inflater.inflate(R.layout.dialog_fullscreen_photo, container, false)
        val photoView = view.findViewById<PhotoView>(R.id.fullscreen_photo_view)
        val photoUrl = arguments?.getString(ARG_PHOTO_URL) ?: return view

        Glide.with(this)
            .load(photoUrl)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .into(photoView)

        // Tap on scrim (root) dismisses
        view.setOnClickListener { dismiss() }

        // PhotoView tap also dismisses (optional - user can tap photo to close)
        photoView.setOnPhotoTapListener { _, _, _ -> dismiss() }

        return view
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
    }
}
