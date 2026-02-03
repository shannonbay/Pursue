package com.github.shannonbay.pursue.ui.views

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.core.content.FileProvider
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import com.github.shannonbay.pursue.R
import com.github.shannonbay.pursue.data.auth.SecureTokenManager
import com.github.shannonbay.pursue.data.network.ApiClient
import com.github.shannonbay.pursue.data.network.ApiException
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.EnumMap

/**
 * Bottom sheet for fetching and sharing invite codes (spec Pursue-Invite-Code-Spec).
 * Uses GET /api/groups/:id/invite. Regenerate (admin/creator only) via POST /invite/regenerate.
 */
class InviteMembersBottomSheet : BottomSheetDialogFragment() {

    companion object {
        private const val ARG_GROUP_ID = "group_id"
        private const val ARG_GROUP_NAME = "group_name"
        private const val ARG_USER_ROLE = "user_role"
        private const val QR_SIZE = 400

        fun newInstance(groupId: String, groupName: String, userRole: String = "member"): InviteMembersBottomSheet {
            return InviteMembersBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_GROUP_ID, groupId)
                    putString(ARG_GROUP_NAME, groupName)
                    putString(ARG_USER_ROLE, userRole)
                }
            }
        }

        fun show(fragmentManager: FragmentManager, groupId: String, groupName: String, userRole: String = "member") {
            newInstance(groupId, groupName, userRole).show(fragmentManager, "InviteMembersBottomSheet")
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var titleText: TextView
    private lateinit var codeCard: View
    private lateinit var inviteCodeText: TextView
    private lateinit var buttonCopy: View
    private lateinit var buttonShare: View
    private lateinit var buttonShareQr: View
    private lateinit var qrImage: ImageView
    private lateinit var buttonDone: View
    private lateinit var buttonRegenerate: View
    private lateinit var loadingView: ProgressBar
    private lateinit var shareCodeLabel: TextView

    private var inviteCode: String? = null
    private var inviteUrl: String? = null

    private val isAdminOrCreator: Boolean
        get() = arguments?.getString(ARG_USER_ROLE) in listOf("admin", "creator")

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_invite_members, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val groupId = arguments?.getString(ARG_GROUP_ID) ?: return
        val groupName = arguments?.getString(ARG_GROUP_NAME) ?: ""

        titleText = view.findViewById(R.id.title)
        codeCard = view.findViewById(R.id.code_card)
        inviteCodeText = view.findViewById(R.id.invite_code_text)
        buttonCopy = view.findViewById(R.id.button_copy)
        buttonShare = view.findViewById(R.id.button_share)
        buttonShareQr = view.findViewById(R.id.button_share_qr)
        qrImage = view.findViewById(R.id.qr_image)
        buttonDone = view.findViewById(R.id.button_done)
        buttonRegenerate = view.findViewById(R.id.button_regenerate)
        loadingView = view.findViewById(R.id.loading_indicator)
        shareCodeLabel = view.findViewById(R.id.share_code_label)

        titleText.text = getString(R.string.invite_to_group_title, groupName)

        shareCodeLabel.visibility = View.GONE
        codeCard.visibility = View.GONE
        qrImage.visibility = View.GONE
        buttonShareQr.visibility = View.GONE
        buttonRegenerate.visibility = View.GONE
        buttonDone.visibility = View.GONE

        scope.launch {
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val token = tokenManager.getAccessToken() ?: return@launch
                val response = withContext(Dispatchers.IO) {
                    ApiClient.getGroupInviteCode(token, groupId)
                }
                if (!isAdded) return@launch
                updateInviteDisplay(response.invite_code, response.share_url)
                loadingView.visibility = View.GONE
                shareCodeLabel.visibility = View.VISIBLE
                codeCard.visibility = View.VISIBLE
                qrImage.visibility = View.VISIBLE
                buttonShareQr.visibility = View.VISIBLE
                buttonDone.visibility = View.VISIBLE
                if (isAdminOrCreator) {
                    buttonRegenerate.visibility = View.VISIBLE
                }
            } catch (e: ApiException) {
                if (isAdded) {
                    Toast.makeText(requireContext(), e.message ?: getString(R.string.invite_generate_failed), Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), getString(R.string.invite_generate_failed), Toast.LENGTH_SHORT).show()
                    dismiss()
                }
            }
        }

        buttonCopy.setOnClickListener {
            val url = inviteUrl ?: inviteCode ?: return@setOnClickListener
            val cm = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("invite_code", url))
            Toast.makeText(requireContext(), getString(R.string.invite_code_copied), Toast.LENGTH_SHORT).show()
        }

        buttonShare.setOnClickListener { performShare() }
        buttonShareQr.setOnClickListener { performShareQrCode() }

        buttonDone.setOnClickListener { dismiss() }

        buttonRegenerate.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.regenerate_code))
                .setMessage(getString(R.string.regenerate_code_confirm))
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(getString(R.string.regenerate_code)) { _, _ -> performRegenerate(groupId) }
                .show()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun updateInviteDisplay(code: String, shareUrl: String) {
        inviteCode = code
        inviteUrl = shareUrl
        inviteCodeText.text = code
        qrImage.setImageBitmap(generateQrBitmap(shareUrl))
    }

    private fun performShare() {
        val url = inviteUrl ?: return
        val code = inviteCode ?: return
        val shareText = getString(R.string.share_invite_message, url, code)
        val sendIntent = Intent().apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share)))
    }

    private fun performShareQrCode() {
        val url = inviteUrl ?: return
        try {
            val qrBitmap = generateQrBitmap(url)
            val qrFile = File(requireContext().cacheDir, "invite_qr").apply { mkdirs() }
            val outputFile = File(qrFile, "invite_${System.currentTimeMillis()}.png")
            FileOutputStream(outputFile).use { out ->
                qrBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            val contentUri = FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                outputFile
            )
            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(sendIntent, getString(R.string.share_qr_code)))
        } catch (e: Exception) {
            Toast.makeText(requireContext(), getString(R.string.invite_generate_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun performRegenerate(groupId: String) {
        scope.launch {
            try {
                val tokenManager = SecureTokenManager.Companion.getInstance(requireContext())
                val token = tokenManager.getAccessToken() ?: return@launch
                val response = withContext(Dispatchers.IO) {
                    ApiClient.regenerateInviteCode(token, groupId)
                }
                if (!isAdded) return@launch
                updateInviteDisplay(response.invite_code, response.share_url)
                Toast.makeText(requireContext(), getString(R.string.regenerate_code_success), Toast.LENGTH_SHORT).show()
            } catch (e: ApiException) {
                if (isAdded) {
                    Toast.makeText(requireContext(), e.message ?: getString(R.string.regenerate_code_failed), Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), getString(R.string.regenerate_code_failed), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun generateQrBitmap(content: String): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.MARGIN, 1)
            put(EncodeHintType.CHARACTER_SET, "UTF-8")
        }
        val bitMatrix = MultiFormatWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            QR_SIZE,
            QR_SIZE,
            hints
        )
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }
}
