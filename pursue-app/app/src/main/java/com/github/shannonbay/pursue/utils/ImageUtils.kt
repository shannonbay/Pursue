package com.github.shannonbay.pursue.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.MediaStore
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Utility functions for image handling, including letter avatars and multipart creation.
 */
object ImageUtils {
    
    /**
     * Create a letter avatar drawable from the first letter of a name.
     * 
     * @param name Display name to extract initial from
     * @param backgroundColor Background color for the avatar (default: primary blue)
     * @param size Size of the avatar in pixels (default: 256)
     * @return Drawable containing the letter avatar
     */
    fun createLetterAvatar(
        context: Context,
        name: String,
        backgroundColor: Int = Color.parseColor("#1976D2"),
        size: Int = 256
    ): Drawable {
        // Extract first letter (uppercase)
        val initial = name.take(1).uppercase()
        
        // Create bitmap
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        
        // Draw background circle
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = backgroundColor
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        
        // Draw text
        paint.color = Color.WHITE
        paint.textSize = size * 0.5f
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        paint.textAlign = Paint.Align.CENTER
        
        // Center text vertically
        val textY = size / 2f - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(initial, size / 2f, textY, paint)
        
        return BitmapDrawable(context.resources, bitmap)
    }
    
    /**
     * Convert a URI to a File for multipart upload.
     * 
     * @param context Context for content resolver
     * @param uri URI of the image (from gallery or camera)
     * @return File object, or null if conversion fails
     */
    fun uriToFile(context: Context, uri: Uri): File? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return null
            
            // Create temp file
            val tempFile = File.createTempFile("avatar_", ".jpg", context.cacheDir)
            val outputStream = FileOutputStream(tempFile)
            
            inputStream.use { input ->
                outputStream.use { output ->
                    input.copyTo(output)
                }
            }
            
            tempFile
        } catch (e: IOException) {
            android.util.Log.e("ImageUtils", "Failed to convert URI to file", e)
            null
        }
    }
    
    /**
     * Create a MultipartBody.Part from an image file for avatar upload.
     * 
     * @param imageFile File containing the image
     * @param partName Name of the form field (default: "avatar")
     * @return MultipartBody.Part ready for upload
     */
    fun createImagePart(
        imageFile: File,
        partName: String = "avatar"
    ): MultipartBody.Part {
        val requestFile = imageFile.asRequestBody("image/jpeg".toMediaType())
        return MultipartBody.Part.createFormData(partName, "avatar.jpg", requestFile)
    }
    
    /**
     * Create a MultipartBody.Part from a URI for avatar upload.
     * 
     * @param context Context for content resolver
     * @param uri URI of the image
     * @param partName Name of the form field (default: "avatar")
     * @return MultipartBody.Part ready for upload, or null if conversion fails
     */
    fun createImagePartFromUri(
        context: Context,
        uri: Uri,
        partName: String = "avatar"
    ): MultipartBody.Part? {
        val file = uriToFile(context, uri) ?: return null
        return createImagePart(file, partName)
    }
}
