package com.github.shannonbay.pursue.e2e.helpers

import java.util.Base64

/**
 * Helper class for creating test images in E2E tests.
 * 
 * Creates minimal valid JPEG images without using Android Bitmap APIs
 * (which aren't fully mocked in the test environment).
 */
object TestImageHelper {
    
    /**
     * Create a minimal valid JPEG image.
     * 
     * Uses a base64-encoded minimal valid JPEG that's guaranteed to work with Sharp.
     * This is a real 1x1 pixel JPEG created with standard tools.
     * The backend will resize it to 256x256 anyway, so we don't need to create larger images.
     * 
     * @param width Image width in pixels (unused - kept for API compatibility)
     * @param height Image height in pixels (unused - kept for API compatibility)
     * @return ByteArray containing a valid JPEG image
     */
    fun createTestImage(
        width: Int = 512,
        height: Int = 512
    ): ByteArray {
        // Base64-encoded valid JPEG created with standard image tools
        // This JPEG is guaranteed to work with Sharp and other image processing libraries
        val base64Jpeg = "/9j/4AAQSkZJRgABAQEAeAB4AAD/2wBDAAIBAQIBAQICAgICAgICAwUDAwMDAwYEBAMFBwYHBwcGBwcICQsJCAgKCAcHCg0KCgsMDAwMBwkODw0MDgsMDAz/2wBDAQICAgMDAwYDAwYMCAcIDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAz/wAARCAAOAA8DASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD8vf2Cv2NPEn/Ba39t/V/Dcni638H29rpF1rtxeXVs2oNp1jFNFDFbW8KmJZG825hBy8Q2mWQln+R+f+N3gDxJ/wAEhv29PGvw6u9Vt/FsPhmWOC6NmzWkGs289qlzayMrq5ilVLiJmUbtjeYiyOpLtn33xF+IP/BLf9rWXxF8KPFtx4X1O6spZLGa1jWdfsNw7qbW4hnWSOZVaMECQON0UUg2uq7Z/gfoOu/8FA/2j/FfxB+KOuXHiu9llF5rct5I6z6lNMjpCi+WUEUUYjG1U2qixRxogThfzZUM4jm1XHYivB5O8PFQoqP7xVeZe9flvZx0Xv7te6rcz/WMsy/iKXGUMBhqjjmyxEr1HJcvMm3KTeqcdG5LlfNG8eWV+U//2Q=="
        
        return Base64.getDecoder().decode(base64Jpeg)
    }
    
    /**
     * Create a large test image (for testing size limits).
     * 
     * Creates a valid JPEG larger than 5MB by adding valid APP segments.
     * JPEG APP segments (0xFF 0xE0-0xEF) can contain arbitrary data and are the
     * correct way to create a large valid JPEG file.
     * 
     * @return ByteArray containing a large image (> 5MB)
     */
    fun createLargeTestImage(): ByteArray {
        val targetSize = 5 * 1024 * 1024 + 1024 // 5MB + 1KB
        val minimalJpeg = createTestImage()
        
        // Find position before EOI marker (last 2 bytes)
        val eoiPosition = minimalJpeg.size - 2
        val result = ByteArray(targetSize)
        
        // Copy minimal JPEG up to (but not including) EOI marker
        minimalJpeg.copyInto(result, 0, 0, eoiPosition)
        
        // Add APP segments for padding
        // APP segment format: 0xFF 0xE1 [length_high] [length_low] [data...]
        // Length is big-endian and includes the 2 length bytes
        var pos = eoiPosition
        while (pos < targetSize - 2) {
            val remaining = targetSize - pos - 4 // Space for marker (2 bytes) + length (2 bytes)
            if (remaining <= 0) break
            
            // Max APP segment data size is 65533 (65535 - 2 for length bytes)
            val segmentDataSize = remaining.coerceAtMost(65533)
            
            // APP1 marker (0xFF 0xE1)
            result[pos++] = 0xFF.toByte()
            result[pos++] = 0xE1.toByte()
            
            // Length (big-endian, includes the 2 length bytes)
            val length = segmentDataSize + 2
            result[pos++] = ((length shr 8) and 0xFF).toByte()
            result[pos++] = (length and 0xFF).toByte()
            
            // Fill segment data with zeros (valid in APP segments)
            for (i in 0 until segmentDataSize) {
                if (pos >= targetSize - 2) break
                result[pos++] = 0x00
            }
        }
        
        // EOI marker at the end (0xFF 0xD9)
        result[targetSize - 2] = 0xFF.toByte()
        result[targetSize - 1] = 0xD9.toByte()
        
        return result
    }
    
    /**
     * Create a small test image (for normal uploads).
     *
     * @return ByteArray containing a small valid JPEG image
     */
    fun createSmallTestImage(): ByteArray {
        return createTestImage(256, 256)
    }

    /**
     * Create an oversized progress photo (for testing 500KB limit).
     *
     * Creates a valid JPEG just over 500KB by adding valid APP segments.
     *
     * @return ByteArray containing a ~600KB valid JPEG image
     */
    fun createOversizedProgressPhoto(): ByteArray {
        val targetSize = 600 * 1024 // 600KB (over the 500KB limit)
        val minimalJpeg = createTestImage()

        // Find position before EOI marker (last 2 bytes)
        val eoiPosition = minimalJpeg.size - 2
        val result = ByteArray(targetSize)

        // Copy minimal JPEG up to (but not including) EOI marker
        minimalJpeg.copyInto(result, 0, 0, eoiPosition)

        // Add APP segments for padding
        var pos = eoiPosition
        while (pos < targetSize - 2) {
            val remaining = targetSize - pos - 4
            if (remaining <= 0) break

            val segmentDataSize = remaining.coerceAtMost(65533)

            // APP1 marker (0xFF 0xE1)
            result[pos++] = 0xFF.toByte()
            result[pos++] = 0xE1.toByte()

            // Length (big-endian)
            val length = segmentDataSize + 2
            result[pos++] = ((length shr 8) and 0xFF).toByte()
            result[pos++] = (length and 0xFF).toByte()

            // Fill segment data
            for (i in 0 until segmentDataSize) {
                if (pos >= targetSize - 2) break
                result[pos++] = 0x00
            }
        }

        // EOI marker at the end
        result[targetSize - 2] = 0xFF.toByte()
        result[targetSize - 1] = 0xD9.toByte()

        return result
    }
}
