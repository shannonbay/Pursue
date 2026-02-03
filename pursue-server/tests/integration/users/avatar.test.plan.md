# Avatar Endpoints Test Plan

**Test plan for `/api/users/me/avatar` (POST, DELETE) and `/api/users/:user_id/avatar` (GET)**

---

## Overview

This test plan covers all three avatar endpoints:
1. **POST /api/users/me/avatar** - Upload avatar
2. **DELETE /api/users/me/avatar** - Delete avatar
3. **GET /api/users/:user_id/avatar** - Get avatar (public endpoint)

---

## Test File Structure

**File:** `tests/integration/users/avatar.test.ts`

**Dependencies:**
- `supertest` for HTTP assertions
- `sharp` for creating test images
- `testDb` for database queries
- `createAuthenticatedUser`, `createGoogleUser` from `tests/helpers`
- `uploadUserAvatar`, `deleteUserAvatar` from `src/services/storage.service`

---

## Test Cases

### 1. POST /api/users/me/avatar

#### 1.1 Happy Path Tests

**Test: Should upload PNG avatar successfully**
- Create authenticated user
- Create test PNG image (10x10, red background) using `sharp`
- POST `/api/users/me/avatar` with `multipart/form-data`, field name `avatar`
- **Assertions:**
  - Status: `200`
  - Body: `{ success: true, has_avatar: true }`
  - Database: `users.avatar_data IS NOT NULL`
  - Database: `users.avatar_mime_type = 'image/webp'`
  - Database: `users.updated_at` updated
  - GET `/api/users/me` returns `has_avatar: true`

**Test: Should upload JPG avatar successfully**
- Same as above, but use JPG format
- Verify it's converted to WebP in database

**Test: Should upload WebP avatar successfully**
- Same as above, but use WebP format
- Verify it's stored as WebP

**Test: Should process and resize large image to 256x256**
- Create large image (1000x1000) using `sharp`
- Upload via POST
- Verify stored image is 256x256 WebP (can check via `sharp` metadata)

**Test: Should crop non-square image to square**
- Create rectangular image (500x200) using `sharp`
- Upload via POST
- Verify stored image is 256x256 (square)

**Test: Should replace existing avatar**
- Create user with existing avatar (via `uploadUserAvatar`)
- Upload new avatar via POST
- Verify old avatar is replaced (check `updated_at` changed)

#### 1.2 Authentication Tests

**Test: Should return 401 without Authorization header**
- POST `/api/users/me/avatar` without `Authorization`
- **Assertions:**
  - Status: `401`
  - Body: `{ error: { code: 'UNAUTHORIZED', ... } }`

**Test: Should return 401 with invalid token**
- POST with `Authorization: Bearer invalid-token`
- **Assertions:**
  - Status: `401`
  - Body: `{ error: { code: 'INVALID_TOKEN', ... } }`

**Test: Should return 401 with expired token**
- Create token, wait for expiration (or mock expired token)
- POST with expired token
- **Assertions:**
  - Status: `401`

**Test: Should return 404 for soft-deleted user**
- Create user, soft delete (`deleted_at = NOW()`)
- POST with valid token for deleted user
- **Assertions:**
  - Status: `404` (user not found)

#### 1.3 Validation Tests

**Test: Should return 400 when no file provided**
- POST `/api/users/me/avatar` with empty body
- **Assertions:**
  - Status: `400`
  - Body: `{ error: { message: 'Avatar file is required', code: 'MISSING_FILE' } }`

**Test: Should return 400 for invalid file type (GIF)**
- Create GIF image using `sharp`
- POST with GIF file
- **Assertions:**
  - Status: `400`
  - Body: `{ error: { code: 'INVALID_FILE_TYPE', ... } }`

**Test: Should return 400 for invalid file type (SVG)**
- Create SVG file (text file with SVG content)
- POST with SVG file
- **Assertions:**
  - Status: `400`
  - Body: `{ error: { code: 'INVALID_FILE_TYPE', ... } }`

**Test: Should return 400 for file exceeding 5MB limit**
- Create large buffer (>5MB) - can use `Buffer.alloc(6 * 1024 * 1024)`
- POST with large file
- **Assertions:**
  - Status: `400` (multer error)
  - Error indicates file size limit exceeded

**Test: Should accept file exactly at 5MB limit**
- Create buffer exactly 5MB
- POST with file
- **Assertions:**
  - Status: `200`
  - Upload succeeds

#### 1.4 Image Processing Tests

**Test: Should convert PNG to WebP**
- Upload PNG image
- Verify database has `avatar_mime_type = 'image/webp'`
- GET avatar and verify `Content-Type: image/webp`

**Test: Should convert JPG to WebP**
- Upload JPG image
- Verify database has `avatar_mime_type = 'image/webp'`
- GET avatar and verify `Content-Type: image/webp`

**Test: Should maintain WebP quality at 90%**
- Upload high-quality image
- Verify processed image quality (can check file size is reasonable)

**Test: Should handle transparent PNG correctly**
- Create PNG with transparency using `sharp`
- Upload PNG
- Verify transparency preserved in WebP

---

### 2. DELETE /api/users/me/avatar

#### 2.1 Happy Path Tests

**Test: Should delete avatar successfully**
- Create user with avatar (via `uploadUserAvatar`)
- DELETE `/api/users/me/avatar` with valid token
- **Assertions:**
  - Status: `200`
  - Body: `{ success: true, has_avatar: false }`
  - Database: `users.avatar_data IS NULL`
  - Database: `users.avatar_mime_type IS NULL`
  - GET `/api/users/me` returns `has_avatar: false`
  - GET `/api/users/:user_id/avatar` returns `404`

**Test: Should handle deleting when no avatar exists**
- Create user without avatar
- DELETE `/api/users/me/avatar`
- **Assertions:**
  - Status: `200` (idempotent - no error)
  - Body: `{ success: true, has_avatar: false }`
  - Database: `users.avatar_data IS NULL` (still null)

#### 2.2 Authentication Tests

**Test: Should return 401 without Authorization header**
- DELETE `/api/users/me/avatar` without `Authorization`
- **Assertions:**
  - Status: `401`
  - Body: `{ error: { code: 'UNAUTHORIZED', ... } }`

**Test: Should return 401 with invalid token**
- DELETE with `Authorization: Bearer invalid-token`
- **Assertions:**
  - Status: `401`
  - Body: `{ error: { code: 'INVALID_TOKEN', ... } }`

**Test: Should return 404 for soft-deleted user**
- Create user, soft delete
- DELETE with valid token for deleted user
- **Assertions:**
  - Status: `404` (user not found)

---

### 3. GET /api/users/:user_id/avatar

#### 3.1 Happy Path Tests

**Test: Should return avatar image for user with avatar**
- Create user with avatar (via `uploadUserAvatar`)
- GET `/api/users/:user_id/avatar` (no auth required)
- **Assertions:**
  - Status: `200`
  - `Content-Type: image/webp` (or stored mime type)
  - `Cache-Control: public, max-age=86400`
  - `ETag: "avatar-{user_id}-{updated_at_timestamp}"` (format matches)
  - Body: Binary image data (Buffer)
  - Body length > 0
  - Body is valid WebP (can verify with `sharp`)

**Test: Should work without authentication (public endpoint)**
- Create user with avatar
- GET `/api/users/:user_id/avatar` without `Authorization` header
- **Assertions:**
  - Status: `200`
  - Image returned successfully

**Test: Should return correct Content-Type for WebP**
- Upload avatar (converts to WebP)
- GET avatar
- **Assertions:**
  - `Content-Type: image/webp`

**Test: Should return correct ETag format**
- Create user with avatar
- GET avatar
- **Assertions:**
  - `ETag` header exists
  - Format: `"avatar-{user_id}-{timestamp}"`
  - Timestamp matches `user.updated_at.getTime()`

#### 3.2 404 Tests

**Test: Should return 404 when user has no avatar**
- Create user without avatar
- GET `/api/users/:user_id/avatar`
- **Assertions:**
  - Status: `404`
  - Body: `{ error: { message: 'User has no avatar', code: 'NO_AVATAR' } }`

**Test: Should return 404 when user does not exist**
- GET `/api/users/invalid-user-id/avatar`
- **Assertions:**
  - Status: `404`
  - Body: `{ error: { message: 'User has no avatar', code: 'NO_AVATAR' } }`

**Test: Should return 404 for soft-deleted user**
- Create user with avatar, soft delete user
- GET `/api/users/:user_id/avatar`
- **Assertions:**
  - Status: `404` (filtered out by `deleted_at IS NULL`)
  - Body: `{ error: { message: 'User has no avatar', code: 'NO_AVATAR' } }`

#### 3.3 ETag / 304 Not Modified Tests

**Test: Should return 304 when If-None-Match matches ETag**
- Create user with avatar
- GET avatar, capture `ETag` from response
- GET avatar again with `If-None-Match: {etag}` header
- **Assertions:**
  - Status: `304 Not Modified`
  - No body (empty response)
  - Headers still include `ETag`, `Cache-Control`

**Test: Should return 200 when If-None-Match does not match**
- Create user with avatar
- GET avatar with `If-None-Match: "wrong-etag"`
- **Assertions:**
  - Status: `200`
  - Full image body returned

**Test: Should return 200 when If-None-Match header is missing**
- Create user with avatar
- GET avatar without `If-None-Match` header
- **Assertions:**
  - Status: `200`
  - Full image body returned

**Test: Should update ETag when avatar is updated**
- Create user with avatar
- GET avatar, capture `ETag1`
- Upload new avatar (POST)
- GET avatar again, capture `ETag2`
- **Assertions:**
  - `ETag1 !== ETag2` (different timestamps)
  - `ETag2` contains new `updated_at` timestamp

#### 3.4 Image Data Tests

**Test: Should return processed 256x256 WebP image**
- Upload large image (1000x1000)
- GET avatar
- Verify image dimensions using `sharp`:
  ```typescript
  const metadata = await sharp(response.body).metadata();
  expect(metadata.width).toBe(256);
  expect(metadata.height).toBe(256);
  expect(metadata.format).toBe('webp');
  ```

**Test: Should return binary data, not JSON**
- Create user with avatar
- GET avatar
- **Assertions:**
  - `Content-Type` starts with `image/`
  - Body is Buffer (not JSON string)
  - `response.type` is `image/webp`

**Test: Should handle multiple concurrent requests**
- Create user with avatar
- Make 5 concurrent GET requests
- **Assertions:**
  - All return `200`
  - All return same image data
  - All have same `ETag`

---

## Test Implementation Notes

### Helper Functions Needed

**Create test image buffer:**
```typescript
async function createTestImage(
  format: 'png' | 'jpeg' | 'webp' = 'png',
  width: number = 10,
  height: number = 10
): Promise<Buffer> {
  return await sharp({
    create: {
      width,
      height,
      channels: 3,
      background: { r: 255, g: 0, b: 0 },
    },
  })
    [format]()
    .toBuffer();
}
```

**Verify image metadata:**
```typescript
async function verifyImageMetadata(
  buffer: Buffer,
  expectedWidth: number,
  expectedHeight: number,
  expectedFormat: string
): Promise<void> {
  const metadata = await sharp(buffer).metadata();
  expect(metadata.width).toBe(expectedWidth);
  expect(metadata.height).toBe(expectedHeight);
  expect(metadata.format).toBe(expectedFormat);
}
```

### Test Data Setup

**Before each test:**
- Clean database (handled by `tests/setup.ts`)
- Create test users as needed

**After each test:**
- No cleanup needed (database is reset between tests)

### Edge Cases to Test

1. **Very small images** (1x1 pixel)
2. **Very large images** (before processing)
3. **Corrupted image files** (should be handled by sharp)
4. **Multiple file uploads in sequence** (replace avatar multiple times)
5. **Unicode in user_id** (should be handled correctly)
6. **Special characters in user_id** (should be handled correctly)

---

## Expected Test Count

- **POST /api/users/me/avatar**: ~15-20 tests
- **DELETE /api/users/me/avatar**: ~5-7 tests
- **GET /api/users/:user_id/avatar**: ~12-15 tests

**Total: ~32-42 test cases**

---

## Success Criteria

All tests should:
- ✅ Pass consistently
- ✅ Have clear, descriptive names
- ✅ Test one behavior per test
- ✅ Use proper assertions (status, body, headers, database state)
- ✅ Follow existing test patterns from `me.test.ts`
- ✅ Handle async operations correctly
- ✅ Clean up test data (via setup/teardown)

---

## Implementation Priority

1. **High Priority** (Core functionality):
   - POST: Happy path, authentication, file validation
   - DELETE: Happy path, authentication
   - GET: Happy path, 404 cases, ETag/304

2. **Medium Priority** (Edge cases):
   - POST: Image processing verification, size limits
   - GET: Image metadata verification, concurrent requests

3. **Low Priority** (Nice to have):
   - POST: Transparency handling, quality verification
   - GET: Performance tests, stress tests
