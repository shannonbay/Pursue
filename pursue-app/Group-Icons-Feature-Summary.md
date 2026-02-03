# Group Icons/Avatars Feature - Implementation Summary

**Date:** January 22, 2026  
**Status:** Spec Updated - Ready for Implementation

---

## Overview

Groups now support customizable icons/avatars with three display modes:
1. **Uploaded images** (custom photos)
2. **Emoji icons** with colored backgrounds
3. **Letter avatars** (first letter of group name with colored background)

---

## Database Changes

### `groups` Table - New Fields

```sql
CREATE TABLE groups (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  name VARCHAR(100) NOT NULL,
  description TEXT,
  icon_emoji VARCHAR(10),          -- Optional emoji (e.g., "🏃", "📚")
  icon_color VARCHAR(7),            -- Hex color for emoji background (e.g., "#1976D2")
  icon_url TEXT,                    -- Optional uploaded image URL (Cloud Storage)
  creator_user_id UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
```

**New fields:**
- `icon_color` - 7-character hex color (e.g., "#1976D2")
- `icon_url` - URL to uploaded image in Google Cloud Storage

**Existing fields (clarified):**
- `icon_emoji` - Single emoji character (e.g., "🏃")

---

## Backend API Updates

### Updated Endpoints (11 total)

#### 1. **POST /api/groups** - Create Group
- Added `icon_color` to request/response
- Added `icon_url` to response (always null on create)
- Note: Image upload requires separate PATCH after creation

**Request:**
```json
{
  "name": "Morning Runners",
  "icon_emoji": "🏃",
  "icon_color": "#1976D2"
}
```

**Response:**
```json
{
  "id": "group-uuid",
  "name": "Morning Runners",
  "icon_emoji": "🏃",
  "icon_color": "#1976D2",
  "icon_url": null
}
```

---

#### 2. **GET /api/groups/:group_id** - Get Group Details
- Added `icon_color` and `icon_url` to response

---

#### 3. **GET /api/users/me/groups** - List User's Groups
- Added `icon_color` and `icon_url` to each group in response

**Example Response:**
```json
{
  "groups": [
    {
      "id": "group-1",
      "name": "Morning Runners",
      "icon_emoji": "🏃",
      "icon_color": "#1976D2",
      "icon_url": null
    },
    {
      "id": "group-2",
      "name": "Book Club",
      "icon_emoji": null,
      "icon_color": null,
      "icon_url": "https://storage.googleapis.com/pursue-app/groups/group-2/icon.webp"
    }
  ]
}
```

---

#### 4. **PATCH /api/groups/:group_id** - Update Group (Metadata)
- Added `icon_color` to request/response
- Clarified this endpoint is for metadata only (name, description, emoji, color)

**Request:**
```json
{
  "name": "Dawn Warriors",
  "icon_emoji": "🏃‍♂️",
  "icon_color": "#F9A825"
}
```

---

### New Endpoints (3 total)

#### 5. **PATCH /api/groups/:group_id/icon** - Upload Group Icon Image

**Purpose:** Upload custom image for group icon

**Headers:**
```
Authorization: Bearer {access_token}
Content-Type: multipart/form-data
```

**Request (multipart/form-data):**
```
icon: [image file - PNG, JPG, WebP]
```

**Validation:**
- File type: `image/png`, `image/jpeg`, `image/webp`
- Max file size: 5 MB
- Recommended dimensions: 512x512
- Aspect ratio: Square (will be cropped)

**Response:**
```json
{
  "id": "group-uuid",
  "icon_url": "https://storage.googleapis.com/pursue-app/groups/group-uuid/icon.webp",
  "icon_emoji": null,
  "icon_color": null,
  "updated_at": "2026-01-20T15:30:00Z"
}
```

**Server Logic:**
1. Verify user is admin or creator
2. Validate file (type, size)
3. Resize and crop to 256x256 square
4. Convert to WebP (90% quality)
5. Upload to Google Cloud Storage
6. Update database: set `icon_url`, clear `icon_emoji` and `icon_color`
7. Delete old icon if exists
8. Return updated group

**Image Processing (using `sharp`):**
```typescript
import sharp from 'sharp';

async function processGroupIcon(file: Buffer): Promise<Buffer> {
  return sharp(file)
    .resize(256, 256, { fit: 'cover', position: 'center' })
    .webp({ quality: 90 })
    .toBuffer();
}
```

**Storage Path:**
```
gs://pursue-app/
  groups/
    {group-uuid}/
      icon.webp  ← Group icon (256x256)
```

**Authorization:** Admin or creator only

**Notes:**
- Uploading image clears `icon_emoji` and `icon_color`
- Old icon automatically deleted when new icon uploaded

---

#### 6. **DELETE /api/groups/:group_id/icon** - Delete Group Icon

**Purpose:** Remove uploaded icon (revert to emoji or letter avatar)

**Response:**
```json
{
  "id": "group-uuid",
  "icon_url": null,
  "icon_emoji": "🏃",
  "icon_color": "#1976D2",
  "updated_at": "2026-01-20T15:30:00Z"
}
```

**Server Logic:**
1. Verify user is admin or creator
2. Delete icon from Cloud Storage
3. Update database: `icon_url = NULL`
4. Optionally set default `icon_emoji` and `icon_color` if none exist
5. Return updated group

**Authorization:** Admin or creator only

---

## Android UI Changes

### Icon Display Logic

**Priority Order:**
1. **icon_url** - Display uploaded image (highest priority)
2. **icon_emoji + icon_color** - Display emoji with colored background
3. **Fallback** - Display first letter of name with default blue (#1976D2)

**Implementation (Kotlin pseudocode):**
```kotlin
when {
    group.icon_url != null -> {
        // Display uploaded image (circular)
        Glide.with(context)
            .load(group.icon_url)
            .circleCrop()
            .placeholder(R.drawable.group_icon_placeholder)
            .into(imageView)
    }
    group.icon_emoji != null -> {
        // Display emoji with colored background (circular)
        textView.text = group.icon_emoji  // "🏃"
        container.backgroundColor = Color.parseColor(group.icon_color ?: "#1976D2")
        container.shape = Circle
    }
    else -> {
        // Display first letter with default background (circular)
        val initial = group.name.first().uppercase()
        textView.text = initial  // "M" for "Morning Runners"
        container.backgroundColor = Color.parseColor("#1976D2")
        container.shape = Circle
    }
}
```

---

### Icon Sizes

- **Group list (Home screen):** 48dp diameter circle
- **Group detail header:** 80dp diameter circle
- **Member list (small):** 32dp diameter circle

---

### Create/Edit Group Screens

**Icon Picker (Bottom Sheet)** - 3 Tabs:

#### Tab 1: Emoji (Default)
- Grid of common emojis (fitness 🏃, education 📚, work 💼, etc.)
- Search bar to find specific emoji
- Recently used emojis at top
- Tap to select

#### Tab 2: Upload (Future - Not MVP)
- Upload custom image from gallery
- Take photo with camera
- Max 5 MB, PNG/JPG/WebP
- Auto-crop to square

#### Tab 3: Color
- 8 predefined colors:
  - Blue (#1976D2) - Default, trust
  - Yellow (#F9A825) - Achievement
  - Green (#388E3C) - Health
  - Red (#D32F2F) - Intensity
  - Purple (#7B1FA2) - Creativity
  - Orange (#F57C00) - Enthusiasm
  - Pink (#C2185B) - Community
  - Teal (#00796B) - Focus
- Live preview with selected emoji

---

### Screen Updates

#### 1. **Home Screen - Group List**
- Each group card shows icon (48dp circular)
- Three visual examples:
  - 🏃 Emoji with blue background
  - [📷] Uploaded photo
  - (G) Letter with blue background

#### 2. **Group Detail Screen**
- Header shows larger icon (80dp circular)
- Same display priority logic

#### 3. **Create Group Screen**
- Icon picker accessible via "Choose Icon" button
- Default: Empty (will use first letter)
- Can select emoji + color before creating

#### 4. **Edit Group Screen (NEW)**
- Accessed via Group Detail → Menu (⋮) → "Edit Group"
- Same icon picker as create
- "Change Icon" button to modify
- Delete Group button (creator only)
- Authorization: Admin or creator only

---

## Technology Stack Additions

### New Dependencies

**npm packages:**
```bash
npm install sharp @google-cloud/storage
```

**Libraries:**
- `sharp` - High-performance image processing
  - Resize, crop, format conversion
  - WebP conversion for better compression
- `@google-cloud/storage` - Google Cloud Storage client
  - Upload/delete files
  - Generate signed/public URLs

---

### Google Cloud Storage Setup

**Bucket Configuration:**
```
Bucket: pursue-app
Region: us-central1 (same as Cloud Run)
Storage class: Standard
Access: Fine-grained (IAM)
```

**Folder Structure:**
```
gs://pursue-app/
  groups/
    {group-uuid}/
      icon.webp  ← 256x256 WebP image
```

**IAM Permissions:**
- Cloud Run service account needs `Storage Object Admin` role
- Public read access if using public URLs
- Or signed URLs with 1-hour expiry

---

## Migration Strategy

### Database Migration

**Migration file:** `migrations/XXXXXX_add_group_icons.sql`

```sql
-- Add new columns to groups table
ALTER TABLE groups 
  ADD COLUMN icon_color VARCHAR(7),
  ADD COLUMN icon_url TEXT;

-- Set default color for existing groups with emojis
UPDATE groups 
SET icon_color = '#1976D2' 
WHERE icon_emoji IS NOT NULL AND icon_color IS NULL;

-- No downtime - all fields are nullable
```

**Rollback:**
```sql
ALTER TABLE groups 
  DROP COLUMN icon_color,
  DROP COLUMN icon_url;
```

---

### Backward Compatibility

**API Compatibility:**
- ✅ Old clients (without icon_color/icon_url) - Still works
- ✅ New clients (with icon_color/icon_url) - Full support
- ✅ Mixed versions - Graceful degradation

**Android Compatibility:**
- Old app version: Shows only `icon_emoji` (ignores color)
- New app version: Shows full icon with color/image
- No breaking changes

---

## Testing Requirements

### Backend Tests

1. **Group Creation**
   - ✅ Create with emoji only
   - ✅ Create with emoji + color
   - ✅ Create without icon (defaults work)
   - ✅ Validate hex color format

2. **Icon Upload**
   - ✅ Upload valid PNG/JPG/WebP
   - ✅ Reject files >5MB
   - ✅ Reject non-image files
   - ✅ Verify image resized to 256x256
   - ✅ Verify WebP conversion
   - ✅ Verify Cloud Storage upload
   - ✅ Verify old icon deleted
   - ✅ Verify emoji/color cleared

3. **Icon Delete**
   - ✅ Delete uploaded icon
   - ✅ Verify Cloud Storage deletion
   - ✅ Verify icon_url cleared in database
   - ✅ Verify default emoji/color set (if needed)

4. **Authorization**
   - ✅ Only admin/creator can upload
   - ✅ Only admin/creator can delete
   - ✅ Members cannot modify icon

### Android Tests

1. **Display Logic**
   - ✅ Display uploaded image (icon_url)
   - ✅ Display emoji with color (icon_emoji + icon_color)
   - ✅ Display letter avatar (fallback)
   - ✅ Handle missing/null values gracefully

2. **Icon Picker**
   - ✅ Emoji tab displays grid
   - ✅ Search emoji works
   - ✅ Color tab shows preview
   - ✅ Selected emoji + color applied

3. **Image Loading**
   - ✅ Glide loads image from URL
   - ✅ Placeholder shown while loading
   - ✅ Error state if image fails to load
   - ✅ Circular crop applied

---

## Performance Considerations

### Image Processing
- **Processing time:** ~100-200ms per image (sharp is fast)
- **CPU usage:** Minimal (sharp uses libvips)
- **Memory:** ~10MB per concurrent upload

### Storage Costs
- **Storage:** $0.026/GB/month (Standard class)
- **Network egress:** $0.12/GB (first 1GB free)
- **Estimated:** ~$5/month for 10,000 groups (avg 50KB each)

### CDN Recommendations (Future)
- Use Cloud CDN for faster image delivery
- Cache images at edge locations
- ~70% faster load times globally

---

## Security Considerations

### File Upload Security
- ✅ Validate file type (magic bytes, not just extension)
- ✅ Validate file size (5MB max)
- ✅ Scan for malware (Cloud Storage can integrate ClamAV)
- ✅ Use signed URLs for sensitive images (optional)
- ✅ Unique filenames (no user input in path)

### Access Control
- ✅ Only admin/creator can upload/delete
- ✅ JWT authentication required
- ✅ Verify group membership before operations

### Storage Security
- ✅ Use IAM for access control
- ✅ Enable versioning (recover deleted files)
- ✅ Set lifecycle rules (delete old versions after 30 days)

---

## MVP Scope

### Included in MVP ✅
- [x] Emoji icons with colored backgrounds
- [x] Color picker (8 predefined colors)
- [x] Letter avatars (fallback)
- [x] Database schema (icon_emoji, icon_color, icon_url)
- [x] Backend endpoints (PATCH /icon, DELETE /icon)
- [x] Android display logic (priority: URL → Emoji → Letter)

### Future Enhancements 🔮
- [ ] Upload custom images (PATCH /api/groups/:id/icon)
- [ ] Image upload in Create/Edit Group flow
- [ ] Custom color picker (hex input)
- [ ] GIF/animated icons
- [ ] Icon templates/gallery
- [ ] AI-generated icons
- [ ] Group banner images (separate from icon)

---

## Deployment Checklist

### Backend
- [ ] Add `sharp` and `@google-cloud/storage` to package.json
- [ ] Configure Google Cloud Storage bucket
- [ ] Run database migration (add icon_color, icon_url columns)
- [ ] Deploy PATCH /api/groups/:group_id/icon endpoint
- [ ] Deploy DELETE /api/groups/:group_id/icon endpoint
- [ ] Test image upload/delete in staging
- [ ] Monitor Cloud Storage usage/costs

### Android
- [ ] Add Glide dependency (if not already present)
- [ ] Implement icon display logic (URL → Emoji → Letter)
- [ ] Implement icon picker (Emoji + Color tabs)
- [ ] Update Create Group screen
- [ ] Add Edit Group screen
- [ ] Test on various screen sizes
- [ ] Test with slow network (image loading)
- [ ] Test fallback scenarios (missing icon, failed load)

### Documentation
- [x] Update Pursue-Backend-Server-Spec.md
- [x] Update Pursue-UI-Spec.md
- [ ] Create API documentation (OpenAPI/Swagger)
- [ ] Create developer guide (How to add group icon upload)

---

## Total Changes Summary

### Backend
- **Database:** 2 new columns (icon_color, icon_url)
- **Endpoints:** 2 new endpoints (PATCH /icon, DELETE /icon)
- **Dependencies:** 2 new packages (sharp, @google-cloud/storage)
- **Total endpoints:** 40 (was 29, added 11 for various features)

### Android
- **Screens:** 2 updated (Create Group, Group Detail), 1 new (Edit Group)
- **Components:** 1 new (Icon Picker bottom sheet)
- **Display logic:** 1 new (Icon display priority)

### Infrastructure
- **Google Cloud Storage:** 1 new bucket (pursue-app)
- **IAM:** Service account needs Storage Object Admin role

---

## Estimated Development Time

### Backend (3-4 days)
- Day 1: Database migration + Update existing endpoints
- Day 2: Implement PATCH /icon endpoint (upload + processing)
- Day 3: Implement DELETE /icon endpoint
- Day 4: Testing + Cloud Storage setup

### Android (4-5 days)
- Day 1: Icon display logic + Glide integration
- Day 2: Icon picker (Emoji tab)
- Day 3: Icon picker (Color tab)
- Day 4: Edit Group screen
- Day 5: Testing + polish

**Total:** 7-9 days for full feature

**MVP (Emoji + Color only):** 4-5 days (skip image upload)

---

## Questions & Answers

**Q: Why WebP format?**  
A: 25-35% smaller file size than PNG, better quality than JPEG at same size. Supported on all modern Android versions.

**Q: Why 256x256 resolution?**  
A: Good balance between quality and file size. Large enough for retina displays (80dp × 3 = 240px), small enough to load quickly (~10-50KB).

**Q: Can users upload GIFs/animated icons?**  
A: Not in MVP. Could add in future by detecting animated GIF and keeping as-is instead of converting to WebP.

**Q: What if image upload fails?**  
A: Transaction rolls back, group keeps existing icon. User sees error toast: "Failed to upload icon. Try again."

**Q: Can users revert from uploaded image to emoji?**  
A: Yes! Use PATCH /api/groups/:id with icon_emoji field, or DELETE /api/groups/:id/icon then set emoji.

**Q: Storage costs at scale?**  
A: 100,000 groups × 30KB avg = 3GB = $0.08/month storage + ~$5/month bandwidth. Very affordable.

---

**Status:** ✅ Specs updated, ready for implementation  
**Next Steps:** Implement backend endpoints → Android UI → Testing → Deploy
