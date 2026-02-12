# Pursue: Proof-of-Work Photo Journal — Feature Specification

**Version:** 1.0  
**Status:** Draft  
**Feature:** Ephemeral Photo Attachments on Progress Entries

---

## 1. Overview & Design Philosophy

Photo attachments give Pursue a "you had to be there" quality. When a group member logs a run, a finished chapter, or a healthy meal and attaches a photo, that photo is visible in the group activity feed for exactly one week — then it's gone forever. This ephemerality serves three goals simultaneously:

1. **Storage efficiency.** The GCS bucket stays lean; photos self-destruct before accumulating.
2. **Privacy by design.** Users are far more likely to post candid, authentic proof-of-work when they know it isn't a permanent record on your servers.
3. **Engagement.** The fleeting nature creates a social incentive to check the feed regularly. Miss the week, miss the moment.

Photos are attached to *progress log entries*, not to goals themselves. A photo is proof that *this specific log* happened — a gym selfie attached to today's "30 min run" entry.

---

## 2. UX Design: Minimizing Touch Overhead

The critical design constraint is preserving Pursue's streamlined logging experience. The current flows are:

- **Binary goal:** 1 touch (tap goal card → logged)
- **Numeric goal:** ~3 touches (tap card → enter number → tap Log)

The photo attachment must be **genuinely optional** and must **add zero touches to the standard no-photo workflow**.

### 2.1 Recommended Approach: Post-Log Photo Prompt (Bottom Sheet)

The recommended pattern is a lightweight bottom sheet that appears *after* the log is confirmed, offering the photo step as an additive action rather than an interruption.

**Binary goal flow with photo:**

1. User taps goal card → progress is logged immediately (same as today, no change)
2. A small bottom sheet slides up: `"Logged ✓  Add a photo?"` with two tappable options — **📷 Camera** | **🖼 Gallery** — and a small dismiss handle at the top
3. User taps Camera or Gallery → OS picker opens → photo selected/taken → upload begins in background
4. Sheet auto-dismisses after ~2s if user does nothing (no photo)

**Net additional touches for no-photo path:** Zero. The sheet appears but dismisses on its own if ignored.  
**Net additional touches for photo path:** 2 (tap Camera/Gallery, then select/take photo).

**Numeric goal flow with photo:**

1. User taps goal card → numeric entry dialog appears (same as today)
2. The numeric dialog gets a small camera icon button in the corner (or below the number field)
3. User enters number → taps Log → progress logged
4. Same post-log bottom sheet appears as above

Alternatively, the camera icon in the numeric dialog can be tapped *before* hitting Log, so the photo is selected first and both are submitted together. Either order works; the implementation can support either.

### 2.2 Alternative: Long-Press for Photo Log

A long-press on the goal card could open a richer logging sheet that includes the photo picker inline (rather than standard tap-to-log). This is a valid secondary pattern — surfaced as a "deliberate" photo log — but the post-log sheet approach is preferred because it keeps the primary interaction unchanged and doesn't require users to discover a hidden gesture.

Long-press could remain as a power-user shortcut if desired, but should not be the primary discovery path.

### 2.3 "Add Photo" on Existing Entries

For a short window (e.g., 15 minutes after logging), a user should be able to tap their own log entry in the activity feed and attach a photo retroactively, in case they forgot or the camera picker was dismissed. After the window closes, no editing is allowed.

---

## 3. Image Compression Strategy (Android)

To keep storage usage well under the 5 GB GCS free tier for as long as possible, images are compressed aggressively *on-device before upload*. This means bytes never reach your server in oversized form.

### 3.1 Library Recommendation: Compressor (Zelory)

Use **[Compressor by Zelory](https://github.com/zetbaitsu/Compressor)** rather than CameraX's `ImageAnalysis` use case. `ImageAnalysis` is designed for real-time frame analysis (barcode scanning, ML Kit, etc.) and is inappropriate here. Compressor is purpose-built for compressing static images before upload and integrates cleanly with Kotlin coroutines.

```kotlin
// build.gradle.kts
implementation("id.zelory:compressor:3.0.1")
```

```kotlin
suspend fun compressForUpload(context: Context, imageFile: File): File {
    return Compressor.compress(context, imageFile) {
        resolution(1080, 1080)       // Max dimension; aspect ratio preserved
        quality(72)                   // JPEG quality — sweet spot for size vs. fidelity
        format(Bitmap.CompressFormat.JPEG)
        size(350_000)                 // Target ≤ 350 KB; Compressor iterates to hit this
    }
}
```

**Target output:** ≤ 350 KB per image at up to 1080px on the long edge. At 350 KB average and 70 uploads/week for premium users across the full user base, storage growth remains very manageable until you have a meaningful number of active premium users.

### 3.2 Backend Size Gate

The backend enforces a hard ceiling regardless of client-side compression:

- **Max accepted size:** 800 KB (provides headroom above the 350 KB target for edge cases like screenshots with lots of detail)
- Requests over 800 KB receive `413 Payload Too Large` with a user-friendly error message: *"Photo is too large. Please try a different image."*
- EXIF data is stripped server-side using `sharp` before the image is written to GCS (privacy, and additional size reduction).

---

## 4. Storage: Google Cloud Storage

### 4.1 Bucket Configuration

```
Bucket name:    pursue-photo-journal-australia
Location:       australia-southeast1  (same region as Cloud Run — free intra-region transfer)
Storage class:  Standard
Public access:  Uniform — all objects private (no public ACLs)
Lifecycle rule: Delete objects older than 7 days (see §4.2)
```

### 4.2 Automated 7-Day Deletion via Lifecycle Rule

GCS lifecycle rules handle photo expiry automatically — no cron job needed.

```json
{
  "rule": [
    {
      "action": { "type": "Delete" },
      "condition": { "age": 7 }
    }
  ]
}
```

Apply via `gcloud`:

```bash
gcloud storage buckets update gs://pursue-progress-photos \
  --lifecycle-file=lifecycle.json
```

This is the primary mechanism for keeping storage lean. The database retains the upload metadata row (with `deleted_at` timestamp) even after GCS deletes the object, for rate-limit enforcement (see §6).

### 4.3 Object Naming Convention

```
{user_id}/{year}/{month}/{progress_entry_id}.jpg

Example: a3f8c2d1-…/2026/02/e7b91a04-….jpg
```

User ID prefix allows future per-user deletion (account deletion / GDPR) using a single GCS prefix delete operation.

### 4.4 Serving Images

Photos are served via **signed URLs** generated by the backend at read time, not stored as public URLs. This ensures:

- Deleted/expired photos return 403 (GCS lifecycle may lag up to a day; signed URL generation can check DB `expires_at` first)
- No public enumeration of the bucket
- URL expiry aligned with photo expiry

```typescript
// Generate a signed URL valid for 1 hour
const [signedUrl] = await storage
  .bucket('pursue-progress-photos')
  .file(objectPath)
  .getSignedUrl({
    version: 'v4',
    action: 'read',
    expires: Date.now() + 60 * 60 * 1000, // 1 hour
  });
```

The activity feed endpoint returns `photo_url: string | null` — a fresh signed URL if the photo exists and hasn't expired, or null.

---

## 5. Database Schema

### 5.1 New Table: `progress_photos`

```sql
CREATE TABLE progress_photos (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  progress_entry_id UUID NOT NULL REFERENCES progress_entries(id) ON DELETE CASCADE,
  user_id           UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  -- GCS object path (not the full URL — URLs are generated at read time)
  gcs_object_path   VARCHAR(512) NOT NULL,

  -- Dimensions after compression (for layout pre-allocation in the feed)
  width_px          INTEGER NOT NULL,
  height_px         INTEGER NOT NULL,

  -- Lifecycle
  uploaded_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at        TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '7 days'),
  gcs_deleted_at    TIMESTAMPTZ,          -- Set when GCS lifecycle confirms deletion

  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_photos_entry  ON progress_photos(progress_entry_id);
CREATE INDEX idx_photos_user   ON progress_photos(user_id);
CREATE INDEX idx_photos_expiry ON progress_photos(expires_at) WHERE gcs_deleted_at IS NULL;
```

### 5.2 Upload Quota Tracking Table: `photo_upload_log`

To enforce rate limits and prevent gaming (users cannot circumvent limits by deleting old photos), upload events are recorded permanently — only the timestamp is kept, not the photo itself.

```sql
CREATE TABLE photo_upload_log (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  uploaded_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_upload_log_user_time ON photo_upload_log(user_id, uploaded_at DESC);
```

**Retention:** Rows are kept permanently (they are tiny — just a UUID and a timestamp). When checking quota, the query looks at the last 70 rows for a given user, which is the maximum any user will ever accumulate relevantly. Older rows beyond the rolling window are simply ignored by the query but kept in the table as a permanent audit trail.

### 5.3 Migration: `progress_entries` (No Change Required)

The `progress_entries` table is unchanged. The photo is a separate related row, not a column on the entry. This keeps the schema clean and the logging path fast — photo upload is async relative to progress logging.

---

## 6. Rate Limiting & Freemium Gating

### 6.1 Upload Limits

| Tier     | Uploads per rolling 7-day window |
|----------|----------------------------------|
| Free     | 3                                |
| Premium  | 70                               |

A rolling 7-day window (not a calendar week reset) prevents edge-case gaming at week boundaries.

**Why 70 for premium?** 10/day would be excessive and risks storage bloat. 70/week ≈ 10/day theoretical max but in practice most users won't post daily, let alone multiple times a day. It's generous enough to feel unlimited while still putting a ceiling on abuse.

### 6.2 Quota Check Query

```sql
-- Count uploads in the last 7 days for this user
SELECT COUNT(*) AS recent_uploads
FROM photo_upload_log
WHERE user_id = $1
  AND uploaded_at > NOW() - INTERVAL '7 days';
```

This query hits the `idx_upload_log_user_time` index and runs in microseconds.

### 6.3 Preventing Gaming via Deletion

Deleting a progress entry (and by cascade, the `progress_photos` row) does **not** delete the corresponding row in `photo_upload_log`. The upload event is a permanent record. Users cannot free up quota by deleting old entries.

### 6.4 UX for Limit-Reached State

When the quota is reached, the post-log bottom sheet still appears but the Camera/Gallery options are replaced with an upgrade prompt:

> *"You've used your 3 free photo uploads this week."*  
> **[Upgrade to Premium]** — **[Maybe Later]**

For premium users at 70/week (extremely unlikely in practice), a similar message appears:
> *"You've reached your upload limit for this week."*

---

## 7. API Specification

### 7.1 Upload Flow (Two-Step: Log then Upload)

Photo upload is intentionally decoupled from progress logging. The progress entry is created first (fast, same as today), then the photo uploads in the background. This keeps the logging tap responsive even on slow connections.

#### Step 1: Log Progress (Unchanged)

```
POST /api/progress
```

Returns the `progress_entry_id` as before. No changes to this endpoint.

#### Step 2: Upload Photo

```
POST /api/progress/{progress_entry_id}/photo
Content-Type: multipart/form-data
Authorization: Bearer {access_token}

photo: <binary JPEG data>
width: 1080
height: 810
```

**Validation:**
- `progress_entry_id` must belong to the authenticated user
- Entry must have been created within the last 15 minutes (edit window)
- Entry must not already have a photo (one photo per entry)
- File content-type must be `image/jpeg` or `image/webp`
- File size must be ≤ 800 KB (after client compression)
- User must be within upload quota

**Response (201 Created):**
```json
{
  "photo_id": "uuid",
  "expires_at": "2026-02-19T14:32:00Z"
}
```

**Error Responses:**
- `400 Bad Request` — Invalid file type or missing fields
- `403 Forbidden` — Entry does not belong to user, or edit window expired
- `409 Conflict` — Entry already has a photo
- `413 Payload Too Large` — Image exceeds 800 KB
- `422 Unprocessable Entity` — Quota exceeded (includes `upgrade_required: true` for free users)

#### Step 3 (Internal): Backend GCS Upload

```typescript
async function handlePhotoUpload(req, res) {
  const { user, entryId } = req;

  // 1. Quota check
  const { recent_uploads } = await db.query(quotaCheckSql, [user.id]);
  const limit = user.is_premium ? 70 : 3;
  if (recent_uploads >= limit) {
    return res.status(422).json({
      error: 'Upload quota exceeded',
      upgrade_required: !user.is_premium,
    });
  }

  // 2. Validate entry ownership & edit window
  const entry = await db.query(
    'SELECT id, user_id, created_at FROM progress_entries WHERE id = $1',
    [entryId]
  );
  if (!entry || entry.user_id !== user.id) return res.status(403).json(...);
  const ageMinutes = (Date.now() - entry.created_at) / 60000;
  if (ageMinutes > 15) return res.status(403).json({ error: 'Edit window expired' });

  // 3. Strip EXIF and validate with sharp
  const sharpInstance = sharp(req.file.buffer);
  const metadata = await sharpInstance.metadata();
  if (!['jpeg', 'webp'].includes(metadata.format)) return res.status(400).json(...);

  const processedBuffer = await sharpInstance
    .withMetadata(false)   // Strip EXIF
    .jpeg({ quality: 85 }) // Re-encode (standardize format, minor additional compression)
    .toBuffer();

  if (processedBuffer.byteLength > 800_000) return res.status(413).json(...);

  // 4. Upload to GCS
  const objectPath = `${user.id}/${year}/${month}/${entryId}.jpg`;
  await bucket.file(objectPath).save(processedBuffer, {
    contentType: 'image/jpeg',
    metadata: { cacheControl: 'private, max-age=3600' },
  });

  // 5. Record in DB (atomic: photo row + upload log row)
  await db.transaction(async trx => {
    await trx.query(insertPhotoSql, [entryId, user.id, objectPath, width, height]);
    await trx.query(insertUploadLogSql, [user.id]);
  });

  return res.status(201).json({ photo_id, expires_at });
}
```

### 7.2 Activity Feed (Modified)

The existing `GET /api/groups/{group_id}/activity` endpoint is extended. Each `progress_logged` activity item gains an optional `photo` field:

```json
{
  "type": "progress_logged",
  "user": { "id": "...", "display_name": "Shannon", "avatar_url": "..." },
  "goal": { "id": "...", "title": "30 min run" },
  "value": 1,
  "note": "Rainy but worth it",
  "logged_at": "2026-02-12T07:31:00Z",
  "photo": {
    "id": "uuid",
    "url": "https://storage.googleapis.com/...?X-Goog-Signature=...",
    "width": 1080,
    "height": 810,
    "expires_at": "2026-02-19T07:31:00Z"
  }
}
```

`photo` is `null` if no photo was attached, or if the photo has expired. The `url` is a freshly signed URL valid for 1 hour; the client should not cache it beyond that. The `expires_at` field lets the client show a countdown or a "Photo expires in N days" label if desired.

**Performance note:** Fetching photos for an activity feed page requires one additional JOIN to `progress_photos`. This is a single join, not an N+1 — the activity feed query already pulls multiple entries in one shot.

---

## 8. Android Implementation Details

### 8.1 Post-Log Bottom Sheet

The bottom sheet should be implemented as a `ModalBottomSheetLayout` (Material 3) or the newer `ModalBottomSheet` from `androidx.compose.material3`. It should:

- Appear automatically after a successful log API response
- Auto-dismiss after 3 seconds if the user takes no action (a subtle progress indicator on the dismiss handle communicates this countdown without being intrusive)
- Not block the underlying UI (the goal card should already show as completed behind the sheet)

```kotlin
// Pseudocode — actual implementation in Compose
@Composable
fun PostLogPhotoSheet(
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onDismiss: () -> Unit,
    quotaExceeded: Boolean,
    isPremium: Boolean,
) {
    // Auto-dismiss after 3s
    LaunchedEffect(Unit) {
        delay(3000)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (quotaExceeded) {
            QuotaExceededContent(isPremium)
        } else {
            Row {
                PhotoOptionButton(icon = Icons.Camera, label = "Camera", onClick = onCamera)
                PhotoOptionButton(icon = Icons.Image, label = "Gallery", onClick = onGallery)
            }
        }
    }
}
```

### 8.2 Camera / Gallery Integration

Use `ActivityResultContracts.TakePicture` for camera and `ActivityResultContracts.GetContent` for gallery. Both return a `Uri` which is then passed to Compressor.

```kotlin
val takePictureLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.TakePicture()
) { success ->
    if (success) viewModel.onPhotoSelected(tempPhotoUri)
}

val pickImageLauncher = rememberLauncherForActivityResult(
    ActivityResultContracts.GetContent()
) { uri ->
    uri?.let { viewModel.onPhotoSelected(it) }
}
```

### 8.3 Upload State in ViewModel

The photo upload is fire-and-forget from the user's perspective — they shouldn't have to wait for it. The ViewModel manages a background upload coroutine:

```kotlin
fun onPhotoSelected(uri: Uri) {
    viewModelScope.launch(Dispatchers.IO) {
        _uploadState.value = UploadState.Compressing
        val file = uriToFile(uri, context)
        val compressed = compressForUpload(context, file)
        _uploadState.value = UploadState.Uploading
        val result = photoRepository.uploadPhoto(entryId, compressed)
        _uploadState.value = when (result) {
            is Success -> UploadState.Done
            is Error -> UploadState.Failed(result.message)
        }
    }
}
```

Upload failures are surfaced with a non-blocking snackbar: *"Photo upload failed — tap to retry."* The progress entry itself is already logged; only the photo was lost.

### 8.4 Photo Caching Strategy

Photos are **not** saved to the device's persistent storage. Coil's built-in memory and disk cache is sufficient for the activity feed access pattern, and explicit local caching would create unnecessary complexity:

- Photos are ephemeral by design (7 days). Persisting them locally would require a separate local expiry mechanism and consume device storage for content the user knows is temporary.
- The activity feed access pattern is shallow — users typically view the last day or two of entries. Coil's in-memory cache handles repeat views within a session, and its disk cache (governed by the `Cache-Control: private, max-age=3600` header set at GCS upload time) covers re-opens within the hour.
- If a signed URL expires (after 1 hour) and the user opens the app again, the feed re-fetches from the API and receives a fresh signed URL. Coil then re-downloads the image, which is a rare occurrence in normal use.

If a dedicated photo gallery view is added in future (swiping through all group photos), revisit this decision — that access pattern would benefit from prefetching and more aggressive caching. For the activity feed, Coil's defaults are the right call.

### 8.5 Activity Feed Photo Display

Photos in the feed should use **Coil** (already likely in the project for avatar loading) with a fixed aspect-ratio container. The `width_px`/`height_px` from the API response allows the image container to reserve space before the image loads, preventing layout jumps:

```kotlin
AsyncImage(
    model = photo.url,
    contentDescription = "Progress photo",
    modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(photo.width.toFloat() / photo.height)
        .clip(RoundedCornerShape(8.dp)),
    contentScale = ContentScale.Crop,
)
```

For expired photos (where `url` is null), render a placeholder:

```
┌─────────────────────────────┐
│         📷                  │
│   This photo has expired    │
└─────────────────────────────┘
```

---

## 9. Privacy & GDPR Considerations

**EXIF stripping** is mandatory. Photos taken on mobile devices embed GPS coordinates, device model, and timestamp in EXIF metadata. Strip server-side with `sharp` before writing to GCS — do not rely on client-side stripping.

**Signed URLs** mean photos are never accessible to anyone who doesn't have an active session in the group. There are no shareable public links.

**Account deletion** triggers a GCS prefix delete for all objects under `{user_id}/`, alongside the DB cascade. This satisfies the right to erasure regardless of the 7-day lifecycle rule.

**Group departure** should not immediately delete photos (the log entry belongs to the group's history while it's visible), but once the 7-day window passes and the GCS lifecycle deletes the object, the DB row's `gcs_deleted_at` is updated and the API returns `null` for the URL. An optional design choice: delete photos immediately when a user leaves a group.

---

## 10. Implementation Checklist

### Backend
- [ ] Create `progress_photos` table and `photo_upload_log` table with migrations
- [ ] `POST /api/progress/{id}/photo` endpoint with quota check, sharp processing, GCS upload
- [ ] Modify activity feed query to JOIN `progress_photos` and generate signed URLs
- [ ] Create GCS bucket with 7-day lifecycle rule
- [ ] Add `GOOGLE_CLOUD_STORAGE_BUCKET` and service account credentials to environment config
- [ ] Extend Zod schema for photo upload endpoint
- [ ] Rate limit: 10 photo uploads per 15 minutes (separate from general rate limit) to prevent burst abuse
- [ ] Unit tests for quota logic, expiry logic, signed URL generation

### Android
- [ ] `PostLogPhotoSheet` composable with 3-second auto-dismiss
- [ ] Camera and gallery launchers with permission handling
- [ ] Compressor integration (`id.zelory:compressor:3.0.1`)
- [ ] `PhotoUploadRepository` with multipart upload via Retrofit
- [ ] Upload state management in ViewModel (compress → upload → done/retry)
- [ ] Activity feed: photo display with aspect-ratio reservation and expired placeholder
- [ ] Quota-exceeded state in bottom sheet with upgrade CTA
- [ ] Snackbar for upload failure with retry action
- [ ] "Add Photo" option on own recent entries in the feed (15-min window)

### Infrastructure
- [ ] GCS bucket creation and lifecycle policy applied
- [ ] Service account with `storage.objectCreator` and `storage.objectViewer` on the bucket
- [ ] Confirm GCS free tier monitoring alert at 4 GB usage

---

## 11. Open Questions

**Q: Should other group members see the photo when it's uploading (before upload completes)?**  
Recommendation: No. The activity feed entry for a log without a photo looks the same as one where the photo is still uploading. Once the photo is confirmed in the DB, it starts appearing. This avoids showing broken image states to other users.

**Q: Should the 7-day clock start from upload time or from the period the goal covers?**  
Recommendation: Upload time (`uploaded_at + 7 days`). The goal period is irrelevant — what matters is when the photo was shared with the group.

**Q: One photo per log entry, or multiple?**  
Recommendation: One. Multiple photos per entry would multiply storage and complexity for minimal UX benefit. If users want to share more photos, they can log multiple entries or simply pick their best shot.

**Q: Should admins be able to delete photos from the group feed?**  
Recommendation: Yes, but scope this to a follow-up. For launch, only the uploading user can trigger deletion (by deleting the progress entry). Admin moderation of photos is a meaningful safety feature but adds scope.

**Q: Should the activity feed show a "📷" indicator on entries that *had* a photo but it expired?**  
Recommendation: Yes, optionally. A small greyed-out camera icon with "Photo expired" tooltip gives context without showing the image. This reinforces the ephemeral framing rather than just silently disappearing.
