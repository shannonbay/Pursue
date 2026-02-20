# Avatar Endpoints Debugging Guide

## Common Issues to Check

### 1. Response Format Mismatch
The Android client expects **exactly** this format:
```json
{
  "has_avatar": true
}
```

**NOT**:
```json
{
  "success": true,
  "has_avatar": true
}
```

### 2. GET Endpoint Returning JSON Instead of Binary
The GET endpoint must return **binary image data** with `Content-Type: image/webp`, NOT JSON.

### 3. Content-Type Header Issues
- Upload: Client sends `Content-Type: image/jpeg` but backend should accept any image type
- GET: Must return `Content-Type: image/webp` (or the stored mime type)

### 4. Multipart Field Name
The multipart form field name must be exactly `"avatar"` (case-sensitive).

---

## Test Case 1: `upload avatar stores image and returns has_avatar true`

**Endpoint to log**: `POST /api/users/me/avatar`

**What to log:**
```javascript
app.post('/api/users/me/avatar', authenticate, upload.single('avatar'), async (req, res) => {
  console.log('=== AVATAR UPLOAD TEST 1 ===');
  console.log('1. Request received');
  console.log('   - User ID:', req.user?.user_id);
  console.log('   - File received:', req.file ? 'YES' : 'NO');
  console.log('   - File field name:', req.file?.fieldname);
  console.log('   - File original name:', req.file?.originalname);
  console.log('   - File mimetype:', req.file?.mimetype);
  console.log('   - File size:', req.file?.size, 'bytes');
  console.log('   - Content-Type header:', req.get('Content-Type'));
  
  if (!req.file) {
    console.log('   ❌ ERROR: No file in request');
    return res.status(400).json({ error: { message: 'No file uploaded', code: 'NO_FILE' } });
  }
  
  try {
    // Log before processing
    console.log('2. Processing image...');
    
    const processedImage = await sharp(req.file.buffer)
      .resize(256, 256, { fit: 'cover', position: 'center' })
      .webp({ quality: 90 })
      .toBuffer();
    
    console.log('   - Processed image size:', processedImage.length, 'bytes');
    
    // Log before database update
    console.log('3. Storing in database...');
    await db
      .updateTable('users')
      .set({
        avatar_data: processedImage,
        avatar_mime_type: 'image/webp',
        updated_at: new Date()
      })
      .where('id', '=', req.user.user_id)
      .execute();
    
    console.log('4. Database update successful');
    
    // Log response
    const response = { has_avatar: true };
    console.log('5. Sending response:', JSON.stringify(response));
    res.json(response);
    
  } catch (error) {
    console.log('   ❌ ERROR during processing:', error.message);
    console.log('   Stack:', error.stack);
    res.status(500).json({ 
      error: { 
        message: 'Failed to process image', 
        code: 'PROCESSING_ERROR' 
      } 
    });
  }
});
```

**Expected logs:**
```
=== AVATAR UPLOAD TEST 1 ===
1. Request received
   - User ID: <uuid>
   - File received: YES
   - File field name: avatar
   - File original name: avatar.jpg
   - File mimetype: image/jpeg
   - File size: ~1024 bytes
   - Content-Type header: multipart/form-data; boundary=...
2. Processing image...
   - Processed image size: <some bytes>
3. Storing in database...
4. Database update successful
5. Sending response: {"has_avatar":true}
```

**Common failures:**
- `File received: NO` → Check multer configuration, field name must be "avatar"
- `ERROR: No file in request` → Multipart parsing issue
- `ERROR during processing` → Sharp library issue or image processing error
- Response format wrong → Must be `{ "has_avatar": true }` not `{ "success": true, "has_avatar": true }`

---

## Test Case 2: `upload avatar processes image to WebP`

**Endpoint to log**: `POST /api/users/me/avatar` and `GET /api/users/:userId/avatar`

**What to log for POST:**
```javascript
// Same as Test 1, but also log:
console.log('   - Original image size:', req.file?.size, 'bytes');
console.log('   - Processed image size:', processedImage.length, 'bytes');
console.log('   - Size reduction:', ((1 - processedImage.length / req.file.size) * 100).toFixed(1) + '%');
```

**What to log for GET:**
```javascript
app.get('/api/users/:user_id/avatar', async (req, res) => {
  console.log('=== AVATAR GET TEST 2 ===');
  console.log('1. Request received');
  console.log('   - User ID:', req.params.user_id);
  console.log('   - Authorization header:', req.get('Authorization') ? 'Present' : 'Missing');
  
  const user = await db
    .selectFrom('users')
    .select(['avatar_data', 'avatar_mime_type', 'updated_at'])
    .where('id', '=', req.params.user_id)
    .executeTakeFirst();
  
  console.log('2. Database query result:');
  console.log('   - User found:', user ? 'YES' : 'NO');
  console.log('   - Avatar data exists:', user?.avatar_data ? 'YES' : 'NO');
  console.log('   - Avatar data size:', user?.avatar_data?.length || 0, 'bytes');
  console.log('   - MIME type:', user?.avatar_mime_type || 'null');
  
  if (!user?.avatar_data) {
    console.log('   ❌ ERROR: No avatar data in database');
    return res.status(404).json({
      error: {
        message: 'User has no avatar',
        code: 'NO_AVATAR'
      }
    });
  }
  
  console.log('3. Sending binary response');
  console.log('   - Content-Type:', user.avatar_mime_type || 'image/webp');
  console.log('   - Body size:', user.avatar_data.length, 'bytes');
  
  res.set('Content-Type', user.avatar_mime_type || 'image/webp');
  res.set('Cache-Control', 'public, max-age=86400');
  res.send(user.avatar_data);
});
```

**Expected logs:**
```
=== AVATAR GET TEST 2 ===
1. Request received
   - User ID: <uuid>
   - Authorization header: Present
2. Database query result:
   - User found: YES
   - Avatar data exists: YES
   - Avatar data size: <some bytes>
   - MIME type: image/webp
3. Sending binary response
   - Content-Type: image/webp
   - Body size: <some bytes>
```

**Common failures:**
- `Avatar data exists: NO` → Upload didn't save to database correctly
- Response is JSON instead of binary → Check `res.send()` vs `res.json()`
- Wrong Content-Type → Must be `image/webp` not `application/json`

---

## Test Case 3: `upload avatar larger than 5MB fails`

**Endpoint to log**: `POST /api/users/me/avatar`

**What to log:**
```javascript
app.post('/api/users/me/avatar', authenticate, upload.single('avatar'), async (req, res) => {
  console.log('=== AVATAR UPLOAD TEST 3 (LARGE FILE) ===');
  console.log('1. Request received');
  console.log('   - File size:', req.file?.size, 'bytes');
  console.log('   - File size in MB:', (req.file?.size / (1024 * 1024)).toFixed(2), 'MB');
  
  // Multer should reject files > 5MB before reaching this handler
  // But if it gets here, check manually:
  if (req.file && req.file.size > 5 * 1024 * 1024) {
    console.log('   ❌ ERROR: File too large (should be caught by multer)');
    return res.status(400).json({
      error: {
        message: 'File too large. Maximum size is 5 MB',
        code: 'FILE_TOO_LARGE'
      }
    });
  }
  
  // ... rest of upload logic
});
```

**Expected logs:**
```
=== AVATAR UPLOAD TEST 3 (LARGE FILE) ===
1. Request received
   - File size: 5242881 bytes
   - File size in MB: 5.00 MB
   ❌ ERROR: File too large (should be caught by multer)
```

**OR** (if multer catches it):
```
MulterError: File too large
```

**Common failures:**
- File gets through multer → Check multer `limits.fileSize` configuration
- Returns 500 instead of 400/413 → Add size validation before processing
- Error message format wrong → Must match `{ error: { message: "...", code: "..." } }`

---

## Test Case 4: `delete avatar removes image and sets has_avatar false`

**Endpoint to log**: `DELETE /api/users/me/avatar`

**What to log:**
```javascript
app.delete('/api/users/me/avatar', authenticate, async (req, res) => {
  console.log('=== AVATAR DELETE TEST 4 ===');
  console.log('1. Request received');
  console.log('   - User ID:', req.user?.user_id);
  
  // Check current state
  const beforeUser = await db
    .selectFrom('users')
    .select(['avatar_data', 'avatar_mime_type'])
    .where('id', '=', req.user.user_id)
    .executeTakeFirst();
  
  console.log('2. Before deletion:');
  console.log('   - Avatar exists:', beforeUser?.avatar_data ? 'YES' : 'NO');
  
  // Delete
  console.log('3. Deleting avatar...');
  const result = await db
    .updateTable('users')
    .set({
      avatar_data: null,
      avatar_mime_type: null,
      updated_at: new Date()
    })
    .where('id', '=', req.user.user_id)
    .execute();
  
  console.log('   - Rows affected:', result.length);
  
  // Verify deletion
  const afterUser = await db
    .selectFrom('users')
    .select(['avatar_data', 'avatar_mime_type'])
    .where('id', '=', req.user.user_id)
    .executeTakeFirst();
  
  console.log('4. After deletion:');
  console.log('   - Avatar exists:', afterUser?.avatar_data ? 'YES' : 'NO');
  
  const response = { has_avatar: false };
  console.log('5. Sending response:', JSON.stringify(response));
  res.json(response);
});
```

**Expected logs:**
```
=== AVATAR DELETE TEST 4 ===
1. Request received
   - User ID: <uuid>
2. Before deletion:
   - Avatar exists: YES
3. Deleting avatar...
   - Rows affected: 1
4. After deletion:
   - Avatar exists: NO
5. Sending response: {"has_avatar":false}
```

**Common failures:**
- `Rows affected: 0` → User ID mismatch or database update failed
- Response format wrong → Must be `{ "has_avatar": false }` not `{ "success": true, "has_avatar": false }`
- Avatar still exists after deletion → Database update not working

---

## Test Case 5: `GET avatar without authentication works for public profiles`

**Endpoint to log**: `GET /api/users/:userId/avatar`

**What to log:**
```javascript
app.get('/api/users/:user_id/avatar', async (req, res) => {
  console.log('=== AVATAR GET TEST 5 (NO AUTH) ===');
  console.log('1. Request received');
  console.log('   - User ID:', req.params.user_id);
  console.log('   - Authorization header:', req.get('Authorization') ? 'Present' : 'Missing (OK for public)');
  
  // ... same as Test 2
});
```

**Expected logs:**
```
=== AVATAR GET TEST 5 (NO AUTH) ===
1. Request received
   - User ID: <uuid>
   - Authorization header: Missing (OK for public)
2. Database query result:
   - User found: YES
   - Avatar data exists: YES
   ...
```

**Common failures:**
- Returns 401 Unauthorized → Endpoint requires authentication, should be optional
- Returns 404 when avatar exists → Check user ID parameter name (`user_id` vs `userId`)

---

## Quick Checklist

For each endpoint, verify:

- [ ] **POST /api/users/me/avatar**
  - [ ] Accepts `multipart/form-data` with field name `"avatar"`
  - [ ] Returns `{ "has_avatar": true }` (NOT `{ "success": true, "has_avatar": true }`)
  - [ ] Processes image to 256x256 WebP
  - [ ] Stores in database correctly
  - [ ] Rejects files > 5MB with 400/413

- [ ] **GET /api/users/:userId/avatar**
  - [ ] Returns binary data (NOT JSON)
  - [ ] Sets `Content-Type: image/webp`
  - [ ] Returns 404 with JSON error when no avatar exists
  - [ ] Works without authentication (optional auth)

- [ ] **DELETE /api/users/me/avatar**
  - [ ] Returns `{ "has_avatar": false }` (NOT `{ "success": true, "has_avatar": false }`)
  - [ ] Actually deletes avatar_data from database
  - [ ] GET returns 404 after deletion

---

## Response Format Examples

**✅ CORRECT Upload Response:**
```json
{
  "has_avatar": true
}
```

**❌ WRONG Upload Response:**
```json
{
  "success": true,
  "has_avatar": true
}
```

**✅ CORRECT Delete Response:**
```json
{
  "has_avatar": false
}
```

**❌ WRONG Delete Response:**
```json
{
  "success": true,
  "has_avatar": false
}
```

**✅ CORRECT GET Response:**
- Status: 200 OK
- Content-Type: `image/webp`
- Body: Binary image data

**❌ WRONG GET Response:**
- Status: 200 OK
- Content-Type: `application/json`
- Body: `{ "avatar": "base64..." }` or similar JSON
