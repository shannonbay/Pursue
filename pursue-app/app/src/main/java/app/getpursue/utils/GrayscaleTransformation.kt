package app.getpursue.utils

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Glide transformation that converts an image to greyscale (saturation 0).
 * Used for read-only group icons.
 */
class GrayscaleTransformation : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        val result = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(toTransform, 0f, 0f, paint)
        return result
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
    }

    override fun equals(other: Any?) = other is GrayscaleTransformation
    override fun hashCode() = ID.hashCode()

    companion object {
        private const val ID = "com.github.shannonbay.pursue.GrayscaleTransformation"
        private val ID_BYTES = ID.toByteArray(Charsets.UTF_8)
    }
}
