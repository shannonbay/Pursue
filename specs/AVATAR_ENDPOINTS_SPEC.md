# Avatar Endpoints Implementation Specification

## Current Status

The following avatar endpoints are **not implemented** and currently return **500 Internal Server Error**:

1. `POST /api/users/me/avatar` - Upload avatar
2. `DELETE /api/users/me/avatar` - Delete avatar  
3. `GET /api/users/:user_id/avatar` - Get avatar (partially working - returns 404 when no avatar exists, but needs to work with uploaded avatars)

## Endpoint Specifications

### 1. POST /api/users/me/avatar

**Purpose**: Upload user avatar image

**Headers:**
```
Authorization: Bearer {access_token}
Content-Type: multipart/form-data
```

**Request Body (multipart/form-data):**
- Field name: `avatar`
- File type: PNG, JPG, or WebP
- Max file size: **5 MB**
- Recommended: Square images (will be cropped to square)

**Expected Response (200 OK):**
```json
{
  "has_avatar": true
}
```

**Alternative Response Format (also acceptable):**
```json
{
  "success": true,
  "has_avatar": true
}
```

**Server Logic Required:**
1. Verify access token (extract `user_id` from JWT)
2. Validate file:
   - Content-Type: `image/png`, `image/jpeg`, or `image/webp`
   - File size: ≤ 5 MB
3. Process image:
   - Resize and crop to **256x256 square** (use `fit: 'cover', position: 'center'`)
   - Convert to **WebP format** (quality: 90%)
4. Store in database:
   - Update `users` table
   - Set `avatar_data` column to processed image bytes (BYTEA)
   - Set `avatar_mime_type` column to `'image/webp'`
5. Return success response

**Error Responses:**
- **400 Bad Request**: Invalid file type, missing file, or file too large (> 5 MB)
  ```json
  {
    "error": {
      "message": "File too large. Maximum size is 5 MB",
      "code": "FILE_TOO_LARGE"
    }
  }
  ```
- **401 Unauthorized**: Invalid or missing access token
- **413 Payload Too Large**: Alternative status code for file size validation (also acceptable)
- **500 Internal Server Error**: Server error during processing (should be avoided)

**Example Implementation (Node.js/Express with Sharp):**
```typescript
import sharp from 'sharp';
import multer from 'multer';

const upload = multer({ 
  storage: multer.memoryStorage(),
  limits: { fileSize: 5 * 1024 * 1024 }, // 5 MB
  fileFilter: (req, file, cb) => {
    const allowedTypes = ['image/png', 'image/jpeg', 'image/jpg', 'image/webp'];
    if (allowedTypes.includes(file.mimetype)) {
      cb(null, true);
    } else {
      cb(new Error('Invalid file type. Only PNG, JPG, and WebP are allowed.'));
    }
  }
});

app.post('/api/users/me/avatar', authenticate, upload.single('avatar'), async (req, res) => {
  if (!req.file) {
    return res.status(400).json({ 
      error: { 
        message: 'No file uploaded', 
        code: 'NO_FILE' 
      } 
    });
  }
  
  try {
    // Process image: resize to 256x256 and convert to WebP
    const processedImage = await sharp(req.file.buffer)
      .resize(256, 256, { 
        fit: 'cover', 
        position: 'center' 
      })
      .webp({ quality: 90 })
      .toBuffer();
    
    // Store in database
    await db
      .updateTable('users')
      .set({
        avatar_data: processedImage,
        avatar_mime_type: 'image/webp',
        updated_at: new Date()
      })
      .where('id', '=', req.user.user_id)
      .execute();
    
    res.json({ has_avatar: true });
  } catch (error) {
    console.error('Avatar upload error:', error);
    res.status(500).json({ 
      error: { 
        message: 'Failed to process image', 
        code: 'PROCESSING_ERROR' 
      } 
    });
  }
});
```

---

### 2. DELETE /api/users/me/avatar

**Purpose**: Delete user avatar

**Headers:**
```
Authorization: Bearer {access_token}
Content-Type: application/json
```

**Request Body**: None (or empty JSON `{}`)

**Expected Response (200 OK):**
```json
{
  "has_avatar": false
}
```

**Alternative Response Format (also acceptable):**
```json
{
  "success": true,
  "has_avatar": false
}
```

**Server Logic Required:**
1. Verify access token (extract `user_id` from JWT)
2. Update `users` table:
   - Set `avatar_data` to `NULL`
   - Set `avatar_mime_type` to `NULL`
3. Return success response

**Error Responses:**
- **401 Unauthorized**: Invalid or missing access token
- **404 Not Found**: User has no avatar (optional - can also return success with `has_avatar: false`)

**Example Implementation:**
```typescript
app.delete('/api/users/me/avatar', authenticate, async (req, res) => {
  await db
    .updateTable('users')
    .set({
      avatar_data: null,
      avatar_mime_type: null,
      updated_at: new Date()
    })
    .where('id', '=', req.user.user_id)
    .execute();
  
  res.json({ has_avatar: false });
});
```

---

### 3. GET /api/users/:user_id/avatar

**Purpose**: Get user avatar image (binary data)

**Headers:**
```
Authorization: Bearer {access_token} (optional - avatars should be publicly accessible)
```

**Path Parameters:**
- `user_id`: UUID of the user

**Expected Response (200 OK):**
- **Content-Type**: `image/webp` (or original mime type if stored)
- **Body**: Binary image data (BYTEA from database)
- **Response Headers:**
  ```
  Content-Type: image/webp
  Cache-Control: public, max-age=86400
  ETag: "avatar-{user_id}-{updated_at_timestamp}"
  ```

**Expected Response (404 Not Found):**
```json
{
  "error": {
    "message": "User has no avatar",
    "code": "NO_AVATAR"
  }
}
```

**Server Logic Required:**
1. Query `users` table for `avatar_data` and `avatar_mime_type` where `id = user_id`
2. If `avatar_data` is `NULL`:
   - Return 404 with error message
3. If `avatar_data` exists:
   - Set `Content-Type` header to `avatar_mime_type` (or default to `image/webp`)
   - Set caching headers (`Cache-Control`, `ETag`)
   - Check `If-None-Match` header for 304 Not Modified (optional optimization)
   - Return binary image data

**Error Responses:**
- **404 Not Found**: User has no avatar (or user doesn't exist)

**Example Implementation:**
```typescript
app.get('/api/users/:user_id/avatar', async (req, res) => {
  const user = await db
    .selectFrom('users')
    .select(['avatar_data', 'avatar_mime_type', 'updated_at'])
    .where('id', '=', req.params.user_id)
    .executeTakeFirst();
    
  if (!user?.avatar_data) {
    return res.status(404).json({
      error: {
        message: 'User has no avatar',
        code: 'NO_AVATAR'
      }
    });
  }
  
  // Set caching headers
  const mimeType = user.avatar_mime_type || 'image/webp';
  res.set('Content-Type', mimeType);
  res.set('Cache-Control', 'public, max-age=86400'); // 24 hours
  res.set('ETag', `"avatar-${req.params.user_id}-${user.updated_at.getTime()}"`);
  
  // Check ETag for 304 Not Modified (optional optimization)
  if (req.get('If-None-Match') === res.get('ETag')) {
    return res.status(304).end();
  }
  
  res.send(user.avatar_data);
});
```

---

## Database Schema Requirements

The `users` table must have the following columns:

```sql
avatar_data BYTEA,           -- Binary image data (processed WebP)
avatar_mime_type VARCHAR(50), -- MIME type (should be 'image/webp' after processing)
updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
```

**Note**: The `has_avatar` field in user responses should be computed as:
```sql
has_avatar = (avatar_data IS NOT NULL)
```

---

## Testing Requirements

The Android app includes E2E tests that verify:

1. **Upload Success**: Upload returns `has_avatar: true`, and GET endpoint returns the image
2. **Image Processing**: Uploaded image is resized to 256x256 and converted to WebP (size should be < 100 KB)
3. **Size Validation**: Upload of file > 5 MB should fail with 400, 413, or 500
4. **Delete Success**: Delete returns `has_avatar: false`, and GET returns 404
5. **Public Access**: GET endpoint works without authentication (optional - may require auth)

**Test Images:**
- Small test images: 256x256 JPEG (~1 KB)
- Large test images: 4000x3000 JPEG (> 5 MB)

---

## Current Issues

1. **POST /api/users/me/avatar** returns **500 Internal Server Error**
   - Endpoint may not exist or may not be handling multipart/form-data correctly
   - Image processing (Sharp) may not be configured
   - Database columns may not exist

2. **DELETE /api/users/me/avatar** returns **500 Internal Server Error**
   - Endpoint may not exist
   - Database update may be failing

3. **GET /api/users/:user_id/avatar** works for 404 cases but needs to return binary data when avatar exists

---

## Priority

**High Priority**: These endpoints are blocking avatar upload functionality in the Android app. All three endpoints should be implemented together to ensure full functionality.

---

## Additional Notes

- The Android app sends images as `multipart/form-data` with field name `"avatar"`
- The filename sent is `"avatar.jpg"` but the actual file type is detected from Content-Type
- Image processing should use Sharp (Node.js) or equivalent library
- Processed images should be stored as WebP for optimal size/quality balance
- Consider implementing rate limiting for upload endpoint (e.g., 10 uploads per hour per user)
