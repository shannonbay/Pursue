package app.getpursue.ui.activities

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.drawToBitmap
import androidx.lifecycle.lifecycleScope
import app.getpursue.R
import app.getpursue.data.auth.SecureTokenManager
import app.getpursue.data.network.ApiClient
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.EnumMap

class ShareableMilestoneCardActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ShareableMilestoneCard"
        private const val QR_SIZE = 256
        private const val EXPORT_WIDTH = 1080
        private const val EXPORT_HEIGHT = 1920
        private const val ACTION_CHOOSER_TARGET_SELECTED = "app.getpursue.action.CHOOSER_TARGET_SELECTED"

        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
        const val EXTRA_CARD_DATA_JSON = "extra_card_data_json"
        const val EXTRA_AUTO_ACTION = "extra_auto_action"
        const val EXTRA_GROUP_ID = "extra_group_id"

        const val ACTION_GENERIC_SHARE = "generic_share"

        fun createIntent(
            context: Context,
            notificationId: String,
            cardDataJson: String?,
            autoAction: String? = null,
            groupId: String? = null
        ): Intent {
            return Intent(context, ShareableMilestoneCardActivity::class.java).apply {
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
                putExtra(EXTRA_CARD_DATA_JSON, cardDataJson)
                putExtra(EXTRA_AUTO_ACTION, autoAction)
                putExtra(EXTRA_GROUP_ID, groupId)
            }
        }
    }

    private data class ShareableCardData(
        val cardType: String,
        val milestoneType: String,
        val title: String,
        val subtitle: String,
        val statValue: String,
        val statLabel: String,
        val quote: String,
        val goalIconEmoji: String,
        val backgroundGradient: List<String>,
        val backgroundImageUrl: String?,
        val shareUrl: String?,
        val qrUrl: String?
    )

    private lateinit var cardRoot: MaterialCardView
    private lateinit var backgroundImage: ImageView
    private lateinit var gradientLayer: View
    private lateinit var qrImage: ImageView
    private lateinit var titleText: TextView
    private lateinit var subtitleText: TextView
    private lateinit var statValueText: TextView
    private lateinit var statLabelText: TextView
    private lateinit var quoteText: TextView
    private lateinit var goalIconText: TextView

    private lateinit var btnShare: MaterialButton
    private lateinit var btnInstagram: MaterialButton
    private lateinit var btnSave: MaterialButton

    private val gson = Gson()
    private var notificationId: String = ""
    private var groupId: String? = null
    private var cardData: ShareableCardData? = null
    private var chooserReceiverRegistered = false

    private val chooserTargetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val chosen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT, ComponentName::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(Intent.EXTRA_CHOSEN_COMPONENT) as? ComponentName
            }
            val pkg = chosen?.packageName ?: return
            trackEvent(
                "milestone_card_share_target",
                mapOf(
                    "notification_id" to notificationId,
                    "milestone_type" to (cardData?.milestoneType ?: "unknown"),
                    "target_package" to pkg
                )
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_shareable_milestone_card)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.shareable_card_title)

        cardRoot = findViewById(R.id.milestone_card_root)
        backgroundImage = findViewById(R.id.milestone_card_background_image)
        gradientLayer = findViewById(R.id.milestone_card_gradient)
        qrImage = findViewById(R.id.card_qr)
        titleText = findViewById(R.id.card_title)
        subtitleText = findViewById(R.id.card_subtitle)
        statValueText = findViewById(R.id.card_stat_value)
        statLabelText = findViewById(R.id.card_stat_label)
        quoteText = findViewById(R.id.card_quote)
        goalIconText = findViewById(R.id.card_goal_icon)
        btnShare = findViewById(R.id.btn_share_generic)
        btnInstagram = findViewById(R.id.btn_share_instagram)
        btnSave = findViewById(R.id.btn_save_photo)

        btnShare.setOnClickListener { shareGeneric() }
        btnInstagram.setOnClickListener { shareToInstagram() }
        btnSave.setOnClickListener { saveToPhotos() }

        val hasInstagram = isInstagramInstalled()
        btnInstagram.visibility = if (hasInstagram) View.VISIBLE else View.GONE

        ContextCompat.registerReceiver(
            this,
            chooserTargetReceiver,
            IntentFilter(ACTION_CHOOSER_TARGET_SELECTED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        chooserReceiverRegistered = true

        notificationId = intent.getStringExtra(EXTRA_NOTIFICATION_ID).orEmpty()
        groupId = intent.getStringExtra(EXTRA_GROUP_ID)
        val cardJson = intent.getStringExtra(EXTRA_CARD_DATA_JSON)
        val autoAction = intent.getStringExtra(EXTRA_AUTO_ACTION)

        cardData = parseCardData(cardJson)
        if (cardData != null) {
            renderCard(cardData!!)
            maybeRunAutoAction(autoAction)
            return
        }

        if (notificationId.isBlank()) {
            Toast.makeText(this, getString(R.string.shareable_card_unavailable), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        lifecycleScope.launch {
            loadCardDataFromNotificationId(notificationId)
            if (cardData == null) {
                Toast.makeText(
                    this@ShareableMilestoneCardActivity,
                    getString(R.string.shareable_card_unavailable),
                    Toast.LENGTH_SHORT
                ).show()
                finish()
                return@launch
            }
            renderCard(cardData!!)
            maybeRunAutoAction(autoAction)
        }
    }

    override fun onDestroy() {
        if (chooserReceiverRegistered) {
            try {
                unregisterReceiver(chooserTargetReceiver)
            } catch (_: IllegalArgumentException) {
                // already unregistered
            }
        }
        chooserReceiverRegistered = false
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private suspend fun loadCardDataFromNotificationId(id: String) {
        val token = SecureTokenManager.getInstance(this).getAccessToken() ?: return
        val response = withContext(Dispatchers.IO) {
            ApiClient.getNotifications(token, limit = 60, beforeId = null)
        }
        val item = response.notifications.firstOrNull { it.id == id } ?: return
        cardData = parseCardDataFromMap(item.shareable_card_data)
    }

    private fun parseCardData(cardJson: String?): ShareableCardData? {
        if (cardJson.isNullOrBlank()) return null
        val type = object : TypeToken<Map<String, Any>>() {}.type
        val map: Map<String, Any> = gson.fromJson(cardJson, type)
        return parseCardDataFromMap(map)
    }

    private fun parseCardDataFromMap(map: Map<String, Any>?): ShareableCardData? {
        if (map == null) return null
        val cardType = map["card_type"] as? String ?: "milestone"
        val milestoneType = map["milestone_type"] as? String ?: cardType
        val title = map["title"] as? String ?: return null
        val subtitle = map["subtitle"] as? String ?: "Pursue Goals"
        val statValue = map["stat_value"]?.toString() ?: ""
        val statLabel = map["stat_label"] as? String ?: ""
        val quote = map["quote"] as? String ?: (map["cta_text"] as? String ?: "")
        val goalIcon = (map["icon_emoji"] as? String) ?: (map["goal_icon_emoji"] as? String) ?: "\uD83C\uDFAF"
        val shareUrl = map["share_url"] as? String
        val qrUrl = map["qr_url"] as? String
        val backgroundImageUrl = map["background_image_url"] as? String

        val gradientRaw = map["background_gradient"] as? List<*>
        val gradient = if (gradientRaw != null && gradientRaw.size >= 2) {
            listOf(gradientRaw[0].toString(), gradientRaw[1].toString())
        } else {
            val legacy = map["background_color"] as? String ?: "#1E88E5"
            listOf(legacy, "#1565C0")
        }

        return ShareableCardData(
            cardType = cardType,
            milestoneType = milestoneType,
            title = title,
            subtitle = subtitle,
            statValue = statValue,
            statLabel = statLabel,
            quote = quote,
            goalIconEmoji = goalIcon,
            backgroundGradient = gradient,
            backgroundImageUrl = backgroundImageUrl,
            shareUrl = shareUrl,
            qrUrl = qrUrl
        )
    }

    private fun renderCard(data: ShareableCardData) {
        supportActionBar?.title = when (data.cardType) {
            "challenge_invite" -> getString(R.string.challenge_invite_card_title)
            "challenge_completion" -> getString(R.string.challenge_completion_card_title)
            else -> getString(R.string.shareable_card_title)
        }
        btnShare.text = when (data.cardType) {
            "challenge_completion" -> getString(R.string.challenge_completion_btn_share_primary)
            else -> getString(R.string.shareable_card_btn_share_primary)
        }
        titleText.text = data.title
        subtitleText.text = data.subtitle
        statValueText.text = data.statValue
        statLabelText.text = data.statLabel
        if (data.cardType == "challenge_invite") {
            quoteText.text = data.quote
            statValueText.visibility = View.GONE
            statLabelText.visibility = View.GONE
        } else {
            quoteText.text = getString(R.string.shareable_card_quote_template, data.quote)
            statValueText.visibility = View.VISIBLE
            statLabelText.visibility = View.VISIBLE
        }
        goalIconText.text = data.goalIconEmoji

        cardRoot.contentDescription = getString(
            R.string.shareable_card_content_description,
            data.title,
            data.statValue,
            data.statLabel
        )

        val top = parseColorSafe(data.backgroundGradient.getOrNull(0) ?: "#1E88E5")
        val bottom = parseColorSafe(data.backgroundGradient.getOrNull(1) ?: "#1565C0")
        gradientLayer.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, bottom)
        )
        if (!data.backgroundImageUrl.isNullOrBlank()) {
            backgroundImage.visibility = View.VISIBLE
            gradientLayer.visibility = View.VISIBLE
            Glide.with(this)
                .load(data.backgroundImageUrl)
                .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<android.graphics.drawable.Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        gradientLayer.visibility = View.VISIBLE
                        return false
                    }

                    override fun onResourceReady(
                        resource: android.graphics.drawable.Drawable,
                        model: Any,
                        target: Target<android.graphics.drawable.Drawable>,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean {
                        gradientLayer.visibility = View.GONE
                        return false
                    }
                })
                .centerCrop()
                .into(backgroundImage)
        } else {
            backgroundImage.visibility = View.GONE
            gradientLayer.visibility = View.VISIBLE
            backgroundImage.setImageDrawable(null)
        }

        val qrContent = data.qrUrl ?: data.shareUrl
        if (!qrContent.isNullOrBlank()) {
            qrImage.setImageBitmap(generateQrBitmap(qrContent, QR_SIZE))
            qrImage.visibility = View.VISIBLE
        } else {
            qrImage.visibility = View.GONE
        }

        if (data.cardType == "challenge_invite") {
            val props = mutableMapOf("card_type" to data.cardType)
            groupId?.let { props["group_id"] = it }
            trackEvent("challenge_card_viewed", props)
        } else {
            trackEvent(
                "milestone_card_viewed",
                mapOf(
                    "notification_id" to notificationId,
                    "milestone_type" to data.milestoneType
                )
            )
        }
    }

    private fun maybeRunAutoAction(autoAction: String?) {
        if (autoAction == ACTION_GENERIC_SHARE) {
            cardRoot.post { shareGeneric() }
        }
    }

    private fun exportCardBitmap(): Bitmap {
        val raw = cardRoot.drawToBitmap(Bitmap.Config.ARGB_8888)
        return Bitmap.createScaledBitmap(raw, EXPORT_WIDTH, EXPORT_HEIGHT, true)
    }

    private fun writeBitmapToShareCache(bitmap: Bitmap): Uri {
        val dir = File(cacheDir, "milestone_cards")
        if (!dir.exists()) dir.mkdirs()

        val output = File(dir, "milestone_card_${System.currentTimeMillis()}.png")
        FileOutputStream(output).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return FileProvider.getUriForFile(this, "${packageName}.fileprovider", output)
    }

    private fun shareGeneric() {
        val data = cardData ?: return
        val uri = writeBitmapToShareCache(exportCardBitmap())
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, buildShareText(data))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooserIntent = Intent.createChooser(
            shareIntent,
            getString(R.string.shareable_card_share_chooser_title)
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val callbackIntent = Intent(ACTION_CHOOSER_TARGET_SELECTED).setPackage(packageName)
            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pending = PendingIntent.getBroadcast(this, 1002, callbackIntent, pendingFlags)
            chooserIntent.putExtra(Intent.EXTRA_CHOSEN_COMPONENT_INTENT_SENDER, pending.intentSender)
        }

        try {
            startActivity(chooserIntent)
            if (data.cardType == "challenge_invite") {
                val props = mutableMapOf(
                    "share_method" to "generic",
                    "card_type" to data.cardType
                )
                groupId?.let { props["group_id"] = it }
                trackEvent("challenge_invite_shared", props)
            } else if (data.cardType == "challenge_completion") {
                val props = mutableMapOf(
                    "share_method" to "generic",
                    "card_type" to data.cardType
                )
                groupId?.let { props["group_id"] = it }
                completionRatePercent(data)?.let { props["completion_rate"] = it }
                trackEvent("challenge_completed_card_shared", props)
            } else {
                trackEvent(
                    "milestone_card_shared_generic",
                    mapOf(
                        "notification_id" to notificationId,
                        "milestone_type" to data.milestoneType
                    )
                )
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.shareable_card_share_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareToInstagram() {
        val data = cardData ?: return
        if (!isInstagramInstalled()) return

        val uri = writeBitmapToShareCache(exportCardBitmap())
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            setPackage("com.instagram.android")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        try {
            startActivity(intent)
            if (data.cardType == "challenge_invite") {
                val props = mutableMapOf(
                    "share_method" to "instagram",
                    "card_type" to data.cardType
                )
                groupId?.let { props["group_id"] = it }
                trackEvent("challenge_invite_shared", props)
            } else if (data.cardType == "challenge_completion") {
                val props = mutableMapOf(
                    "share_method" to "instagram",
                    "card_type" to data.cardType
                )
                groupId?.let { props["group_id"] = it }
                completionRatePercent(data)?.let { props["completion_rate"] = it }
                trackEvent("challenge_completed_card_shared", props)
            } else {
                trackEvent(
                    "milestone_card_shared_instagram",
                    mapOf(
                        "notification_id" to notificationId,
                        "milestone_type" to data.milestoneType
                    )
                )
            }
        } catch (_: Exception) {
            shareGeneric()
            if (data.cardType != "challenge_completion") {
                trackEvent(
                    "milestone_card_shared_instagram_fallback",
                    mapOf(
                        "notification_id" to notificationId,
                        "milestone_type" to data.milestoneType
                    )
                )
            }
        }
    }

    private fun saveToPhotos() {
        val data = cardData ?: return
        val bitmap = exportCardBitmap()
        val filename = "Pursue_${System.currentTimeMillis()}.png"

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = android.content.ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, filename)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Pursue")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri).use { out ->
                        if (out != null) {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                        } else {
                            throw IllegalStateException("MediaStore output stream was null")
                        }
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val baseDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                val pursueDir = File(baseDir, "Pursue")
                if (!pursueDir.exists()) pursueDir.mkdirs()
                val file = File(pursueDir, filename)
                FileOutputStream(file).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }

            Toast.makeText(this, getString(R.string.shareable_card_saved), Toast.LENGTH_SHORT).show()
            trackEvent(
                "milestone_card_saved",
                mapOf(
                    "notification_id" to notificationId,
                    "milestone_type" to data.milestoneType
                )
            )
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.shareable_card_save_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildShareText(data: ShareableCardData): String {
        val url = data.shareUrl ?: "https://getpursue.app"
        return when (data.cardType) {
            "challenge_invite" -> getString(R.string.challenge_share_text, data.title, url)
            "challenge_completion" -> getString(R.string.challenge_completion_share_text, data.subtitle, url)
            else -> getString(R.string.shareable_card_share_text, url)
        }
    }

    private fun completionRatePercent(data: ShareableCardData): String? {
        val digits = data.statValue.filter { it.isDigit() }
        return if (digits.isNotEmpty()) digits else null
    }

    private fun isInstagramInstalled(): Boolean {
        return try {
            packageManager.getPackageInfo("com.instagram.android", 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parseColorSafe(hex: String): Int {
        return try {
            Color.parseColor(hex)
        } catch (_: IllegalArgumentException) {
            Color.parseColor("#1E88E5")
        }
    }

    private fun generateQrBitmap(content: String, sizePx: Int): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
            put(EncodeHintType.MARGIN, 1)
        }
        val matrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        for (x in 0 until sizePx) {
            for (y in 0 until sizePx) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.WHITE else Color.TRANSPARENT)
            }
        }
        return bitmap
    }

    private fun trackEvent(name: String, properties: Map<String, String>) {
        Log.i(TAG, "event=$name properties=$properties")
    }
}
