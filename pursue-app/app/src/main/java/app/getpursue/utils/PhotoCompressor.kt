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
 * Uses Zelory Compressor with spec settings: max 1080px, 80 quality, target 200 KB.
 *
 * @param context Context for content resolver and compressor
 * @param uri URI from camera or gallery picker
 * @return CompressedPhoto with file and dimensions, or null if compression fails
 */
suspend fun compressPhotoForUpload(context: Context, uri: Uri): CompressedPhoto? = withContext(Dispatchers.IO) {
    try {
        val sourceFile = ImageUtils.uriToFileWithNormalizedOrientation(context, uri) ?: return@withContext null
        var compressedFile = Compressor.compress(context, sourceFile) {
            resolution(1080, 1080)
            quality(80)
            format(android.graphics.Bitmap.CompressFormat.JPEG)
            size(200_000)
        }
        // Hard ceiling: backend rejects > 500 KB.
        // If Compressor couldn't reach 200 KB, retry with lower quality.
        if (compressedFile.length() > 500_000) {
            compressedFile = Compressor.compress(context, sourceFile) {
                resolution(1080, 1080)
                quality(60)
                format(android.graphics.Bitmap.CompressFormat.JPEG)
                size(500_000)
            }
        }
        if (compressedFile.length() > 500_000) {
            Log.w("PhotoCompressor", "Compressed file still exceeds 500 KB (${compressedFile.length()} bytes)")
            return@withContext null
        }
        val (width, height) = getImageDimensions(compressedFile) ?: return@withContext null
        CompressedPhoto(file = compressedFile, width = width, height = height)
    } catch (e: Exception) {
        Log.e("PhotoCompressor", "Failed to compress photo", e)
        null
    }
}

private fun getImageDimensions(file: File): Pair<Int, Int>? {
    return try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        Pair(options.outWidth, options.outHeight)
    } catch (e: Exception) {
        Log.e("PhotoCompressor", "Failed to get image dimensions", e)
        null
    }
}
