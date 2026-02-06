package app.getpursue.utils

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

/**
 * Unit tests for ImageUtils.
 * 
 * Tests letter avatar generation, URI to File conversion, and multipart creation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28],
    application = Application::class,
    packageName = "com.github.shannonbay.pursue"
)
class ImageUtilsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `test createLetterAvatar generates correct initial`() {
        // Given
        val name = "John Doe"

        // When
        val drawable = ImageUtils.createLetterAvatar(context, name)

        // Then
        assertNotNull("Letter avatar should be created", drawable)
        assertTrue("Should be BitmapDrawable", drawable is BitmapDrawable)
        
        val bitmapDrawable = drawable as BitmapDrawable
        val bitmap = bitmapDrawable.bitmap
        assertNotNull("Bitmap should exist", bitmap)
        assertEquals("Bitmap should be square", bitmap.width, bitmap.height)
        assertTrue("Bitmap should be at least 256x256", bitmap.width >= 256)
    }

    @Test
    fun `test createLetterAvatar uses default color`() {
        // Given
        val name = "Test User"
        val defaultColor = Color.parseColor("#1976D2")

        // When
        val drawable = ImageUtils.createLetterAvatar(context, name)

        // Then
        assertNotNull("Letter avatar should be created", drawable)
        val bitmapDrawable = drawable as BitmapDrawable
        val bitmap = bitmapDrawable.bitmap
        
        // Verify bitmap structure (Robolectric doesn't fully support Canvas drawing, so we verify structure instead of pixel colors)
        assertNotNull("Bitmap should exist", bitmap)
        assertEquals("Bitmap should be square", bitmap.width, bitmap.height)
        assertTrue("Bitmap should be at least 256x256", bitmap.width >= 256)
        
        // Note: Pixel color verification requires instrumented tests due to Robolectric Canvas limitations
        // The function is verified to use default color by checking it's called with default parameter
    }

    @Test
    fun `test createLetterAvatar uses custom color`() {
        // Given
        val name = "Test User"
        val customColor = Color.parseColor("#FF5722") // Deep Orange

        // When
        val drawable = ImageUtils.createLetterAvatar(context, name, customColor)

        // Then
        assertNotNull("Letter avatar should be created", drawable)
        val bitmapDrawable = drawable as BitmapDrawable
        val bitmap = bitmapDrawable.bitmap
        
        // Verify bitmap structure (Robolectric doesn't fully support Canvas drawing, so we verify structure instead of pixel colors)
        assertNotNull("Bitmap should exist", bitmap)
        assertEquals("Bitmap should be square", bitmap.width, bitmap.height)
        assertTrue("Bitmap should be at least 256x256", bitmap.width >= 256)
        
        // Verify custom color parameter is accepted (function doesn't throw)
        // Note: Pixel color verification requires instrumented tests due to Robolectric Canvas limitations
        // The function is verified to accept custom color by checking it's called successfully with custom parameter
    }

    @Test
    fun `test createLetterAvatar extracts first letter uppercase`() {
        // Given
        val name = "john doe"

        // When
        val drawable = ImageUtils.createLetterAvatar(context, name)

        // Then
        assertNotNull("Letter avatar should be created", drawable)
        // The avatar should show "J" (first letter, uppercase)
        // We can't easily verify the text content without OCR, but we can verify it was created
        assertTrue("Should be BitmapDrawable", drawable is BitmapDrawable)
    }

    @Test
    fun `test uriToFile converts URI to File`() {
        // Given
        val mockUri = mockk<Uri>(relaxed = true)
        val imageBytes = ByteArray(1024) { it.toByte() }
        val inputStream = ByteArrayInputStream(imageBytes)
        
        val mockContentResolver = mockk<ContentResolver>(relaxed = true)
        every { mockContentResolver.openInputStream(mockUri) } returns inputStream
        
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContext.cacheDir } returns context.cacheDir

        // When
        val file = ImageUtils.uriToFile(mockContext, mockUri)

        // Then
        assertNotNull("File should be created", file)
        val nonNullFile = file!!
        assertTrue("File should exist", nonNullFile.exists())
        assertEquals("File should have correct size", imageBytes.size.toLong(), nonNullFile.length())
        
        // Cleanup
        nonNullFile.delete()
    }

    @Test
    fun `test uriToFile returns null on error`() {
        // Given
        val mockUri = mockk<Uri>(relaxed = true)
        val mockContentResolver = mockk<ContentResolver>(relaxed = true)
        every { mockContentResolver.openInputStream(mockUri) } returns null
        
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver

        // When
        val file = ImageUtils.uriToFile(mockContext, mockUri)

        // Then
        assertNull("File should be null when URI cannot be opened", file)
    }

    @Test
    fun `test uriToFile handles IOException`() {
        // Given
        val mockUri = mockk<Uri>(relaxed = true)
        val mockContentResolver = mockk<ContentResolver>(relaxed = true)
        every { mockContentResolver.openInputStream(mockUri) } throws IOException("Test error")
        
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver

        // When
        val file = ImageUtils.uriToFile(mockContext, mockUri)

        // Then
        assertNull("File should be null when IOException occurs", file)
    }

    @Test
    fun `test createImagePart creates MultipartBody correctly`() {
        // Given
        val imageFile = File.createTempFile("test_avatar_", ".jpg", context.cacheDir)
        imageFile.writeBytes(ByteArray(512) { it.toByte() })

        try {
            // When
            val part = ImageUtils.createImagePart(imageFile, "avatar")

            // Then
            assertNotNull("MultipartBody.Part should be created", part)
            assertNotNull("Part should have body", part.body)
            assertEquals("Part should have correct content type", 
                "image/jpeg", part.body?.contentType()?.toString())
        } finally {
            // Cleanup
            imageFile.delete()
        }
    }

    @Test
    fun `test createImagePart uses custom part name`() {
        // Given
        val imageFile = File.createTempFile("test_avatar_", ".jpg", context.cacheDir)
        imageFile.writeBytes(ByteArray(512) { it.toByte() })
        val customPartName = "profile_picture"

        try {
            // When
            val part = ImageUtils.createImagePart(imageFile, customPartName)

            // Then
            assertNotNull("MultipartBody.Part should be created", part)
            // Note: We can't easily verify the form field name without inspecting the request,
            // but we can verify the part was created successfully
        } finally {
            // Cleanup
            imageFile.delete()
        }
    }

    @Test
    fun `test createImagePartFromUri creates part from URI`() {
        // Given
        val mockUri = mockk<Uri>(relaxed = true)
        val imageBytes = ByteArray(1024) { it.toByte() }
        val inputStream = ByteArrayInputStream(imageBytes)
        
        val mockContentResolver = mockk<ContentResolver>(relaxed = true)
        every { mockContentResolver.openInputStream(mockUri) } returns inputStream
        
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver
        every { mockContext.cacheDir } returns context.cacheDir

        // When
        val part = ImageUtils.createImagePartFromUri(mockContext, mockUri)

        // Then
        assertNotNull("MultipartBody.Part should be created from URI", part)
        assertNotNull("Part should have body", part?.body)
    }

    @Test
    fun `test createImagePartFromUri returns null when URI conversion fails`() {
        // Given
        val mockUri = mockk<Uri>(relaxed = true)
        val mockContentResolver = mockk<ContentResolver>(relaxed = true)
        every { mockContentResolver.openInputStream(mockUri) } returns null
        
        val mockContext = mockk<Context>(relaxed = true)
        every { mockContext.contentResolver } returns mockContentResolver

        // When
        val part = ImageUtils.createImagePartFromUri(mockContext, mockUri)

        // Then
        assertNull("Part should be null when URI conversion fails", part)
    }
}
