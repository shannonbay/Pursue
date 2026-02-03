# Image Processing Flow & Security - Pursue

**Date:** January 22, 2026  
**Topic:** Complete image upload pipeline with security considerations

---

## TL;DR

**Where images are scaled down:** 
✅ **Client-side:** Android resizes to 512x512 + compresses (reduces upload from ~200 KB → ~50 KB)  
✅ **Server-side:** Backend resizes to 256x256 + converts to WebP (final: ~30 KB)

**Why both?**
- Client resize: **Bandwidth optimization** (faster uploads on slow networks)
- Server resize: **Security & consistency** (prevents storing massive images)

**Security:** Server ALWAYS validates and processes - never trusts client

---

## Complete Image Upload Flow

### User Avatar Upload (Detailed)

```
┌──────────────────────────────────────────────────────────────┐
│ ANDROID CLIENT                                               │
└──────────────────────────────────────────────────────────────┘

1. User Action
   ├─ Gallery: Picks existing photo
   │   └─ Could be: 4000x3000, 8 MB, HEIC/JPEG/PNG
   └─ Camera: Takes new photo
       └─ Could be: 4000x3000, 6 MB, JPEG

2. Image Cropper (Android Image Cropper Library)
   ├─ User crops to square
   ├─ Library settings:
   │   ├─ aspectRatioX = 1, aspectRatioY = 1 (square)
   │   ├─ maxOutputSizeX = 512, maxOutputSizeY = 512
   │   ├─ outputCompressFormat = JPEG
   │   └─ outputCompressQuality = 90
   └─ Output: 512x512 JPEG, ~200 KB

3. Client-Side Optimization (ADDED)
   ├─ Load cropped image
   ├─ Check dimensions (if > 512x512, resize)
   ├─ Compress JPEG at quality 85
   └─ Output: 512x512 JPEG, ~50 KB (4x smaller!)

4. Upload to Backend
   └─ POST /api/users/me/avatar
       ├─ Content-Type: multipart/form-data
       ├─ Field: "avatar"
       └─ Size: ~50 KB

┌──────────────────────────────────────────────────────────────┐
│ BACKEND SERVER                                               │
└──────────────────────────────────────────────────────────────┘

5. Multer Validation
   ├─ Check: File exists
   ├─ Check: Size < 5 MB (safety net)
   ├─ Check: MIME type (image/jpeg, image/png, image/webp)
   └─ ✅ PASS (50 KB < 5 MB)

6. Sharp Processing (CRITICAL SECURITY STEP)
   ├─ Load image with sharp (validates it's a real image)
   ├─ Resize: 256x256, fit: cover, position: center
   ├─ Convert: WebP, quality 90%
   ├─ Output: Buffer (BYTEA)
   └─ Final size: ~30 KB

7. Database Storage
   ├─ Store: avatar_data = [30 KB WebP buffer]
   ├─ Store: avatar_mime_type = 'image/webp'
   └─ Update: updated_at (for cache invalidation)

8. Response
   └─ { success: true, has_avatar: true }
```

---

## Why Process on BOTH Client AND Server?

### Client-Side Processing (Android)

**Purpose:** Bandwidth optimization  
**Not for:** Security

**Benefits:**
- ✅ Faster uploads (50 KB vs 200 KB = 4x faster)
- ✅ Lower data usage (important on cellular)
- ✅ Better UX (upload completes faster)
- ✅ Reduces server bandwidth costs

**Example:**
```
Original:  4000x3000 JPEG = 8 MB
Cropped:   512x512 JPEG Q90 = 200 KB
Optimized: 512x512 JPEG Q85 = 50 KB  ← Upload this

Upload time on 4G (5 Mbps):
- 200 KB = 0.3 seconds
- 50 KB = 0.08 seconds (4x faster!)
```

---

### Server-Side Processing (Backend)

**Purpose:** Security & consistency  
**Critical:** NEVER skip this

**Why it's essential:**

#### 1. **Malicious Client Protection**
```typescript
// ❌ What if user bypasses Android app?
// They could use curl or Postman:
curl -X POST https://api.getpursue.app/users/me/avatar \
  -H "Authorization: Bearer TOKEN" \
  -F "avatar=@huge_image.jpg"  // 50 MB bomb!

// ✅ Server validation catches this:
const upload = multer({ 
  limits: { fileSize: 5 * 1024 * 1024 }  // Hard limit: 5 MB
});

// ✅ Sharp processing ensures final size:
const processedImage = await sharp(req.file.buffer)
  .resize(256, 256)  // Force to 256x256
  .webp({ quality: 90 })  // Force WebP format
  .toBuffer();

// Result: Even if 50 MB sent, only 30 KB stored
```

#### 2. **Image Validation (Security)**
```typescript
// Sharp will FAIL if file is not a valid image
try {
  const processedImage = await sharp(req.file.buffer)
    .resize(256, 256)
    .toBuffer();
} catch (error) {
  // File was:
  // - Corrupted
  // - Not actually an image (malicious payload disguised as JPEG)
  // - Contains exploit attempts
  return res.status(400).json({ error: 'Invalid image file' });
}
```

**Real attack example:**
```bash
# Attacker tries to upload malicious file
echo "<?php system($_GET['cmd']); ?>" > hack.jpg
# Sets Content-Type: image/jpeg
# Uploads to server

# ❌ Without sharp validation:
# File stored as-is, could execute if served incorrectly

# ✅ With sharp validation:
# Sharp fails: "Input buffer contains unsupported image format"
# Nothing stored, attack prevented
```

#### 3. **Consistency Guarantee**
```typescript
// ✅ All stored images are:
// - Exactly 256x256
// - WebP format
// - ~30 KB size
// - Validated and safe

// This means:
// - Predictable database size
// - Consistent load times
// - No surprises (20 MB images in database)
```

#### 4. **Format Standardization**
```
User uploads:
- HEIC (iPhone)          →  WebP 256x256 30KB
- PNG with transparency  →  WebP 256x256 30KB
- Animated GIF          →  WebP 256x256 30KB (first frame)
- JPEG 8000x6000        →  WebP 256x256 30KB
- BMP                   →  WebP 256x256 30KB

Everything becomes: WebP 256x256 ~30KB
```

---

## Security Considerations

### 1. File Size Validation (Multiple Layers)

```typescript
// Layer 1: Multer (before processing)
const upload = multer({ 
  limits: { 
    fileSize: 5 * 1024 * 1024,  // 5 MB hard limit
    files: 1                     // Only 1 file
  }
});

// Layer 2: Sharp processing (forces small output)
const processedImage = await sharp(req.file.buffer)
  .resize(256, 256)  // Output always 256x256
  .webp({ quality: 90 })
  .toBuffer();

// Layer 3: Check processed size (paranoid check)
if (processedImage.length > 500 * 1024) {  // 500 KB
  throw new Error('Processed image too large');
}

// Result: Maximum stored = 500 KB (realistically ~30 KB)
```

### 2. File Type Validation

```typescript
// ❌ BAD: Trust client MIME type
const mimeType = req.file.mimetype;  // User can fake this!

// ✅ GOOD: Sharp validates actual content
try {
  const metadata = await sharp(req.file.buffer).metadata();
  // metadata.format = actual format ('jpeg', 'png', 'webp')
  
  if (!['jpeg', 'png', 'webp'].includes(metadata.format)) {
    throw new Error('Unsupported format');
  }
  
  // Process
  const processed = await sharp(req.file.buffer)
    .resize(256, 256)
    .webp({ quality: 90 })
    .toBuffer();
    
} catch (error) {
  // Not a valid image
  return res.status(400).json({ error: 'Invalid image file' });
}
```

### 3. Malicious Payload Protection

**Attack Vector:** Image with embedded exploit

```
Example: JPEG with PHP code in EXIF data
┌─────────────────────────────────────┐
│ JPEG Header                         │
├─────────────────────────────────────┤
│ EXIF Data:                          │
│   <?php system($_GET['cmd']); ?>   │ ← Malicious code
├─────────────────────────────────────┤
│ Image Data (pixels)                 │
└─────────────────────────────────────┘
```

**Protection:**

```typescript
// Sharp STRIPS all metadata during processing
const processedImage = await sharp(req.file.buffer)
  .resize(256, 256)
  .webp({ quality: 90 })
  .toBuffer();

// Output contains ONLY:
// - WebP header
// - Pixel data
// No EXIF, no metadata, no malicious payload
```

---

### 4. Rate Limiting

```typescript
// Prevent abuse (DoS via image uploads)
import rateLimit from 'express-rate-limit';

const uploadLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,  // 15 minutes
  max: 10,                    // Max 10 uploads per 15 min
  message: 'Too many uploads. Please try again later.'
});

app.post('/api/users/me/avatar', 
  authenticate, 
  uploadLimiter,  // ← Rate limit
  upload.single('avatar'), 
  async (req, res) => {
    // Process
  }
);
```

---

### 5. Content Security Policy

**When serving images:**

```typescript
app.get('/api/users/:user_id/avatar', async (req, res) => {
  const user = await db
    .selectFrom('users')
    .select(['avatar_data', 'avatar_mime_type'])
    .where('id', '=', req.params.user_id)
    .executeTakeFirst();
    
  if (!user?.avatar_data) {
    return res.status(404).send();
  }
  
  // Security headers
  res.set('Content-Type', user.avatar_mime_type || 'image/webp');
  res.set('Content-Disposition', 'inline');  // Display, don't download
  res.set('X-Content-Type-Options', 'nosniff');  // Prevent MIME sniffing
  res.set('Cache-Control', 'public, max-age=86400');
  
  res.send(user.avatar_data);
});
```

**Why `X-Content-Type-Options: nosniff`?**

Without it:
```
1. Server sends: Content-Type: image/webp
2. Browser thinks: "Let me check if this is REALLY an image..."
3. Browser detects: "This looks like HTML!"
4. Browser renders: HTML/JavaScript (XSS attack!)
```

With it:
```
1. Server sends: 
   Content-Type: image/webp
   X-Content-Type-Options: nosniff
2. Browser: "Server says image/webp, so it's image/webp"
3. Browser renders: Image only (safe)
```

---

## Complete Security Checklist

### Backend
- [x] File size limit (5 MB)
- [x] MIME type validation
- [x] Sharp processing (validates real image)
- [x] Force resize to 256x256
- [x] Force convert to WebP
- [x] Strip metadata (EXIF, comments)
- [x] Rate limiting (10 uploads / 15 min)
- [x] Authentication required
- [x] Content-Type header set correctly
- [x] X-Content-Type-Options: nosniff
- [x] No path traversal in filenames

### Client
- [x] Optimize bandwidth (resize before upload)
- [x] Show upload progress
- [x] Validate file picker (images only)
- [x] Handle errors gracefully
- [x] Compress before upload

---

## Performance Metrics

### Upload Times (512x512 image)

| Network | No Optimization | With Optimization | Improvement |
|---------|----------------|-------------------|-------------|
| 5G (100 Mbps) | 0.02s | 0.004s | 5x faster |
| 4G LTE (10 Mbps) | 0.16s | 0.04s | 4x faster |
| 4G (5 Mbps) | 0.32s | 0.08s | 4x faster |
| 3G (1 Mbps) | 1.6s | 0.4s | 4x faster |
| Slow 3G (400 Kbps) | 4s | 1s | 4x faster |

**Recommendation:** Always optimize client-side for better UX

---

### Storage Comparison

| Stage | Size | Format |
|-------|------|--------|
| Original (camera) | 8 MB | JPEG 4000x3000 |
| After crop (cropper) | 200 KB | JPEG 512x512 Q90 |
| After client optimize | **50 KB** | JPEG 512x512 Q85 |
| After backend process | **30 KB** | WebP 256x256 Q90 |

**Database impact (100K users, 50% upload avatars):**
- 50,000 avatars × 30 KB = **1.5 GB** (manageable)
- If stored at 200 KB: 50,000 × 200 KB = **10 GB** (wasteful)
- If stored at 8 MB: 50,000 × 8 MB = **400 GB** (disaster!)

---

## Attack Scenarios & Mitigations

### Attack 1: File Bomb (Decompression Attack)

**Attack:**
```
Upload: small_file.jpg (10 KB compressed)
Decompressed: 10,000 x 10,000 = 400 MB in memory!
Goal: Crash server with OOM
```

**Mitigation:**
```typescript
// Sharp limits decompression automatically
const sharp = require('sharp');

// This will fail safely if decompressed size too large
const processed = await sharp(req.file.buffer)
  .resize(256, 256)  // Immediately resize (limits memory)
  .toBuffer();

// If input is malicious bomb, sharp detects and fails gracefully
```

---

### Attack 2: Path Traversal

**Attack:**
```
Upload filename: ../../etc/passwd.jpg
Goal: Overwrite system files
```

**Mitigation:**
```typescript
// ✅ We don't use filenames for storage
// Images stored in BYTEA column, no filesystem involved

// Even if using filesystem:
const safeFilename = `${userId}.webp`;  // Ignore user filename
```

---

### Attack 3: Billion Laughs (XML/SVG)

**Attack:**
```xml
<!-- SVG with nested entities -->
<!DOCTYPE lol [
  <!ENTITY lol "lol">
  <!ENTITY lol1 "&lol;&lol;&lol;&lol;&lol;">
  <!ENTITY lol2 "&lol1;&lol1;&lol1;&lol1;">
  ...
]>
<svg>
  <text>&lol9;</text>  <!-- Expands to gigabytes -->
</svg>
```

**Mitigation:**
```typescript
// Sharp detects and limits expansion
// Also: We don't accept SVG for avatars
const allowedFormats = ['jpeg', 'png', 'webp'];

const metadata = await sharp(buffer).metadata();
if (!allowedFormats.includes(metadata.format)) {
  throw new Error('Format not allowed');
}
```

---

### Attack 4: Image Polyglot (Valid Image + Malicious Code)

**Attack:**
```
File is BOTH:
1. Valid JPEG (displays as image)
2. Valid PHP script (executes as code)
```

**Mitigation:**
```typescript
// 1. Sharp re-encodes the image (strips polyglot)
const clean = await sharp(malicious_polyglot.jpg)
  .webp()
  .toBuffer();
// Output: ONLY valid WebP, no polyglot

// 2. Serve with correct headers
res.set('Content-Type', 'image/webp');
res.set('X-Content-Type-Options', 'nosniff');  // CRITICAL
res.set('Content-Disposition', 'inline');
```

---

## Best Practices Summary

### ✅ DO

1. **Always process images on server** (never trust client)
2. **Use Sharp for validation** (it's battle-tested)
3. **Set file size limits** (5 MB is generous for avatars)
4. **Convert to WebP** (better compression, strips metadata)
5. **Force specific dimensions** (256x256 for avatars)
6. **Add rate limiting** (prevent abuse)
7. **Set security headers** (X-Content-Type-Options)
8. **Optimize on client** (better UX, lower bandwidth)

### ❌ DON'T

1. **Don't trust MIME types** (user can fake)
2. **Don't store original uploads** (always process)
3. **Don't skip validation** (even for "trusted" users)
4. **Don't use user filenames** (path traversal risk)
5. **Don't allow unlimited size** (DoS risk)
6. **Don't skip metadata stripping** (privacy + security)
7. **Don't serve without headers** (XSS risk)
8. **Don't forget rate limiting** (abuse prevention)

---

## Code Examples

### Complete Backend Implementation

```typescript
import express from 'express';
import multer from 'multer';
import sharp from 'sharp';
import rateLimit from 'express-rate-limit';

const app = express();

// Configure multer (in-memory storage)
const upload = multer({
  storage: multer.memoryStorage(),
  limits: {
    fileSize: 5 * 1024 * 1024,  // 5 MB max
    files: 1
  },
  fileFilter: (req, file, cb) => {
    // Basic MIME check (not trusted, but helps UX)
    if (!file.mimetype.startsWith('image/')) {
      return cb(new Error('Only images allowed'));
    }
    cb(null, true);
  }
});

// Rate limiter
const uploadLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 10,
  message: { error: 'Too many uploads. Try again later.' }
});

// Upload endpoint
app.post('/api/users/me/avatar',
  authenticate,
  uploadLimiter,
  upload.single('avatar'),
  async (req, res, next) => {
    try {
      if (!req.file) {
        return res.status(400).json({ error: 'No file uploaded' });
      }
      
      // Validate it's a real image and get metadata
      const metadata = await sharp(req.file.buffer).metadata();
      
      if (!['jpeg', 'png', 'webp'].includes(metadata.format)) {
        return res.status(400).json({ 
          error: 'Invalid format. Use JPEG, PNG, or WebP' 
        });
      }
      
      // Process image (this also validates and strips metadata)
      const processedImage = await sharp(req.file.buffer)
        .resize(256, 256, { 
          fit: 'cover', 
          position: 'center' 
        })
        .webp({ 
          quality: 90,
          effort: 6  // Higher compression effort
        })
        .toBuffer();
        
      // Paranoid check (should never trigger)
      if (processedImage.length > 500 * 1024) {
        throw new Error('Processed image unexpectedly large');
      }
      
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
        
      res.json({ 
        success: true, 
        has_avatar: true 
      });
      
    } catch (error) {
      if (error.message.includes('unsupported image format')) {
        return res.status(400).json({ 
          error: 'Invalid or corrupted image file' 
        });
      }
      next(error);
    }
  }
);

// Serve endpoint
app.get('/api/users/:user_id/avatar', async (req, res) => {
  try {
    const user = await db
      .selectFrom('users')
      .select(['avatar_data', 'avatar_mime_type', 'updated_at'])
      .where('id', '=', req.params.user_id)
      .executeTakeFirst();
      
    if (!user?.avatar_data) {
      return res.status(404).json({ error: 'No avatar' });
    }
    
    // Security headers
    res.set('Content-Type', user.avatar_mime_type || 'image/webp');
    res.set('Content-Disposition', 'inline');
    res.set('X-Content-Type-Options', 'nosniff');
    res.set('Cache-Control', 'public, max-age=86400');
    res.set('ETag', `"${req.params.user_id}-${user.updated_at.getTime()}"`);
    
    // Check ETag
    if (req.get('If-None-Match') === res.get('ETag')) {
      return res.status(304).end();
    }
    
    res.send(user.avatar_data);
    
  } catch (error) {
    res.status(500).json({ error: 'Server error' });
  }
});
```

---

## Summary

**Image scaling happens at TWO points:**

1. **Client (Android):** 
   - Resize to 512x512, compress to ~50 KB
   - **Purpose:** Bandwidth optimization (4x faster uploads)
   - **Security:** None (optimization only)

2. **Server (Node.js):**
   - Resize to 256x256, convert to WebP ~30 KB
   - **Purpose:** Security, validation, consistency
   - **Security:** CRITICAL (never skip this)

**Key Principle:** 
> Trust nothing from the client. Server ALWAYS validates and processes.

**Result:**
- ✅ Safe (validated, no exploits)
- ✅ Fast (optimized uploads)
- ✅ Small (predictable 30 KB storage)
- ✅ Consistent (always WebP 256x256)

