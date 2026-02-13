package app.getpursue.utils

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import id.zelory.compressor.Compressor
import id.zelory.compressor.constraint.format
import id.zelory.compressor.constraint.quality
import id.zelory.compressor.constraint.resolution
import id.zelory.compressor.constraint.size
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Result of compressing a photo for progress upload.
 * Contains the compressed file and its dimensions (required by the API).
 */
data class CompressedPhoto(
    val file: File,
    val width: Int,
    val height: Int
)

/**
 * Compresses an image from URI for progress photo upload.
 * Uses Zelory Compressor with spec settings: max 1080px, WebP format, quality 85/80/75/60, target 200 KB.
 *
 * @param context Context for content resolver and compressor
 * @param uri URI from camera or gallery picker
 * @return CompressedPhoto with file and dimensions, or null if compression fails
 */
private const val TAG = "PhotoCompressor"

suspend fun compressPhotoForUpload(context: Context, uri: Uri): CompressedPhoto? = withContext(Dispatchers.IO) {
    try {
        val sourceFile = ImageUtils.uriToFileWithNormalizedOrientation(context, uri) ?: return@withContext null
        Log.d(TAG, "Source (after EXIF): ${sourceFile.length() / 1024} KB")

        var compressedFile = Compressor.compress(context, sourceFile) {
            resolution(1080, 1080)
            quality(85)
            format(android.graphics.Bitmap.CompressFormat.WEBP)
            size(200_000)
        }
        Log.d(TAG, "Pass 1 (quality=85, target=200KB): ${compressedFile.length() / 1024} KB")

        // Hard ceiling: backend rejects > 500 KB.
        // Fall back through 80, 75, 60 if needed.
        var qualityUsed = 85
        val fallbacks = listOf(80 to 500_000L, 75 to 500_000L, 60 to 500_000L)
        for ((q, target) in fallbacks) {
            if (compressedFile.length() <= 500_000) break
            compressedFile = Compressor.compress(context, sourceFile) {
                resolution(1080, 1080)
                quality(q)
                format(android.graphics.Bitmap.CompressFormat.WEBP)
                size(target)
            }
            qualityUsed = q
            Log.d(TAG, "Pass fallback (quality=$q, target=500KB): ${compressedFile.length() / 1024} KB")
        }

        if (compressedFile.length() > 500_000) {
            Log.w(TAG, "Compressed file still exceeds 500 KB (${compressedFile.length()} bytes)")
            return@withContext null
        }
        val (width, height) = getImageDimensions(compressedFile) ?: return@withContext null
        Log.d(TAG, "Result: quality=$qualityUsed, ${width}x${height}, ${compressedFile.length() / 1024} KB")
        CompressedPhoto(file = compressedFile, width = width, height = height)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to compress photo", e)
        null
    }
}

private fun getImageDimensions(file: File): Pair<Int, Int>? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        Pair(options.outWidth, options.outHeight)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get image dimensions", e)
        null
    }
}
