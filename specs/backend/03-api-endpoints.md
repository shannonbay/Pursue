## 3. API Endpoints

### 3.1 Authentication Endpoints

#### POST /api/auth/register

Register new user with email/password.

**Request:**
```json
{
  "email": "shannon@example.com",
  "password": "securePassword123",
  "display_name": "Shannon Thompson"
}
```

**Validation:**
- Email: Valid format, not already registered
- Password: Min 8 characters, max 100 characters
- Display name: 1-100 characters

**Response (201 Created):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "shannon@example.com",
    "display_name": "Shannon Thompson",
    "has_avatar": false,
    "created_at": "2026-01-16T10:00:00Z"
  }
}
```

**Server Logic:**
1. Validate input (Zod schema)
2. Check email not already registered
3. Hash password with bcrypt (10 rounds)
4. Create user in database
5. Create auth_providers entry (provider='email')
6. Generate access token (1 hour expiry)
7. Generate refresh token (30 days expiry)
8. Store refresh token hash in database
9. Return tokens + user data

**Errors:**
- 400: Invalid input (validation errors)
- 409: Email already registered

---

#### POST /api/auth/login

Sign in with email/password.

**Request:**
```json
{
  "email": "shannon@example.com",
  "password": "securePassword123"
}
```

**Response (200 OK):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "shannon@example.com",
    "display_name": "Shannon Thompson",
    "has_avatar": false
  }
}
```

**Server Logic:**
1. Validate input
2. Find user by email
3. Verify password with bcrypt
4. Generate new access token
5. Generate new refresh token
6. Store refresh token hash
7. Return tokens + user data

**Errors:**
- 400: Invalid input
- 401: Invalid email or password

---

#### POST /api/auth/google

Sign in or register with Google OAuth.

**Request:**
```json
{
  "id_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6IjE4MmU0M..."
}
```

**Response (200 OK or 201 Created):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "is_new_user": true,
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "shannon@example.com",
    "display_name": "Shannon Thompson",
    "has_avatar": true
  }
}
```

**Server Logic:**
1. Verify Google ID token with Google API
2. Extract: sub (Google user ID), email, name, picture
3. Check if auth_provider exists with provider='google' and provider_user_id=sub
4. If exists: Sign in existing user
5. If not exists:
   - Check if user exists by email
   - If yes: Link Google to existing account
   - If no: Create new user (password_hash=NULL)
   - **Download Google avatar image from picture URL**
   - **Resize to 256x256 and convert to WebP**
   - **Store in avatar_data and avatar_mime_type columns**
6. Create/update auth_providers entry
7. Generate tokens
8. Return tokens + user data + is_new_user flag

**Errors:**
- 400: Invalid ID token
- 401: Token verification failed

---

#### POST /api/auth/refresh

Refresh access token using refresh token.

**Request:**
```json
{
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Response (200 OK):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Server Logic:**
1. Verify refresh token signature
2. Check token not expired
3. Find token in database, check not revoked
4. Generate new access token
5. Return new access token

**Errors:**
- 401: Invalid or expired refresh token

---

#### POST /api/auth/logout

Revoke refresh token (sign out).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Response (204 No Content)**

**Server Logic:**
1. Verify access token (get user_id)
2. Find refresh token in database
3. Set revoked_at = NOW()
4. Return success

---

#### POST /api/auth/forgot-password

Request password reset email.

**Request:**
```json
{
  "email": "shannon@example.com"
}
```

**Response (200 OK):**
```json
{
  "message": "If an account exists with this email, a password reset link has been sent."
}
```

**Server Logic:**
1. Find user by email
2. If not found: Return success anyway (prevent email enumeration)
3. If found:
   - Generate secure random token (32 bytes)
   - Hash token with SHA-256
   - Store in password_reset_tokens (expires in 1 hour)
   - Send email with reset link: `https://getpursue.app/reset-password?token={plain_token}`
4. Return success message

**Errors:**
- 400: Invalid email format

---

#### POST /api/auth/reset-password

Reset password using token from email.

**Request:**
```json
{
  "token": "a1b2c3d4e5f6...",
  "new_password": "newSecurePassword456"
}
```

**Response (200 OK):**
```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
}
```

**Server Logic:**
1. Hash provided token with SHA-256
2. Find password_reset_tokens entry
3. Check token not expired and not used
4. Get user_id from token
5. Hash new password with bcrypt
6. Update user's password_hash
7. Mark token as used
8. Generate new tokens
9. Return tokens

**Errors:**
- 400: Invalid or expired token
- 400: Weak password

---

#### POST /api/auth/link/google

Link Google account to existing user.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "id_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6IjE4MmU0M..."
}
```

**Response (200 OK):**
```json
{
  "success": true,
  "provider": "google",
  "provider_email": "shannon@example.com"
}
```

**Server Logic:**
1. Verify access token (get user_id)
2. Verify Google ID token
3. Extract Google user ID and email
4. Check Google account not already linked to another user
5. Create auth_providers entry
6. Return success

**Errors:**
- 401: Unauthorized (invalid access token)
- 409: Google account already linked to another user

---

#### DELETE /api/auth/unlink/:provider

Unlink auth provider (e.g., google).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Path Parameters:**
- `provider`: 'google' or 'email'

**Response (200 OK):**
```json
{
  "success": true
}
```

**Server Logic:**
1. Verify access token (get user_id)
2. Check user has at least 2 auth providers
3. If only 1 provider: Return error "Cannot unlink last provider"
4. Delete auth_providers entry
5. Return success

**Errors:**
- 401: Unauthorized
- 400: Cannot unlink last provider

---

### 3.2 User Endpoints

#### GET /api/users/me

Get current user profile.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "shannon@example.com",
  "display_name": "Shannon Thompson",
  "has_avatar": true,
  "created_at": "2026-01-01T10:00:00Z"
}
```

**Notes:**
- `has_avatar`: Boolean indicating if user has uploaded/set an avatar
- To fetch avatar image: `GET /api/users/{user_id}/avatar`

---

#### PATCH /api/users/me

Update user profile.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "display_name": "Shannon T."
}
```

**Response (200 OK):**
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "email": "shannon@example.com",
  "display_name": "Shannon T.",
  "has_avatar": true
}
```

**Validation:**
- display_name: Optional, 1-100 characters

---

#### POST /api/users/me/avatar

Upload user avatar image.

**Headers:**
```
Authorization: Bearer {access_token}
Content-Type: multipart/form-data
```

**Request (multipart/form-data):**
```
avatar: [image file - PNG, JPG, WebP]
```

**Validation:**
- File type: image/png, image/jpeg, image/webp
- Max file size: 5 MB
- Recommended: Square images (will be cropped to square)

**Response (200 OK):**
```json
{
  "success": true,
  "has_avatar": true
}
```

**Server Logic:**
1. Verify access token
2. Validate file (type, size)
3. Resize and crop image to 256x256 square using sharp
4. Convert to WebP format (quality: 90%)
5. Store in avatar_data column as BYTEA
6. Set avatar_mime_type to 'image/webp'
7. Return success

**Image Processing:**
```typescript
import sharp from 'sharp';
import multer from 'multer';

const upload = multer({ 
  storage: multer.memoryStorage(),
  limits: { fileSize: 5 * 1024 * 1024 } // 5 MB
});

app.post('/api/users/me/avatar', authenticate, upload.single('avatar'), async (req, res) => {
  if (!req.file) {
    return res.status(400).json({ error: 'No file uploaded' });
  }
  
  // Process image
  const processedImage = await sharp(req.file.buffer)
    .resize(256, 256, { fit: 'cover', position: 'center' })
    .webp({ quality: 90 })
    .toBuffer();
    
  // Store in database
  await db
    .updateTable('users')
    .set({
      avatar_data: processedImage,
      avatar_mime_type: 'image/webp'
    })
    .where('id', '=', req.user.user_id)
    .execute();
    
  res.json({ success: true, has_avatar: true });
});
```

---

#### GET /api/users/:user_id/avatar

Get user avatar image.

**Headers:**
```
Authorization: Bearer {access_token} (optional for public profiles)
```

**Response (200 OK):**
- Content-Type: image/webp (or original mime type)
- Body: Binary image data

**Response Headers:**
```
Content-Type: image/webp
Cache-Control: public, max-age=86400
ETag: "avatar-{user_id}-{updated_at_timestamp}"
```

**Response (404 Not Found):**
```json
{
  "error": {
    "message": "User has no avatar",
    "code": "NO_AVATAR"
  }
}
```

**Server Logic:**
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
  res.set('Content-Type', user.avatar_mime_type || 'image/webp');
  res.set('Cache-Control', 'public, max-age=86400'); // 24 hours
  res.set('ETag', `"avatar-${req.params.user_id}-${user.updated_at.getTime()}"`);
  
  // Check ETag for 304 Not Modified
  if (req.get('If-None-Match') === res.get('ETag')) {
    return res.status(304).end();
  }
  
  res.send(user.avatar_data);
});
```

---

#### DELETE /api/users/me/avatar

Delete user avatar.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "success": true,
  "has_avatar": false
}
```

**Server Logic:**
1. Verify access token
2. Set avatar_data to NULL
3. Set avatar_mime_type to NULL
4. Return success

---

#### POST /api/users/me/password

Change password or set password (for Google-only users).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request (Change Password):**
```json
{
  "current_password": "oldPassword123",
  "new_password": "newPassword456"
}
```

**Request (Set Password - Google-only users):**
```json
{
  "current_password": null,
  "new_password": "newPassword456"
}
```

**Response (200 OK):**
```json
{
  "success": true
}
```

**Server Logic:**
1. Verify access token
2. If current_password provided: Verify it matches
3. If current_password is null: Check user has no password (Google-only)
4. Hash new password
5. Update password_hash
6. Create auth_providers entry if doesn't exist (provider='email')
7. Return success

---

#### GET /api/users/me/providers

List linked auth providers.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "providers": [
    {
      "provider": "email",
      "has_password": true,
      "linked_at": "2026-01-01T10:00:00Z"
    },
    {
      "provider": "google",
      "provider_email": "shannon@example.com",
      "linked_at": "2026-01-15T14:30:00Z"
    }
  ]
}
```

---

#### GET /api/users/me/groups

Get user's groups.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Query Parameters:**
- `limit`: Optional, default 50, max 100
- `offset`: Optional, default 0

**Response (200 OK):**
```json
{
  "groups": [
    {
      "id": "group-uuid",
      "name": "Morning Runners",
      "description": "Daily accountability for morning runs",
      "icon_emoji": "🏃",
      "icon_color": "#1976D2",
      "has_icon": false,
      "member_count": 8,
      "role": "creator",
      "joined_at": "2026-01-01T10:00:00Z"
    },
    {
      "id": "group-uuid-2",
      "name": "Book Club",
      "description": "Reading together",
      "icon_emoji": null,
      "icon_color": null,
      "has_icon": true,
      "member_count": 12,
      "role": "member",
      "joined_at": "2026-01-05T14:30:00Z"
    }
  ],
  "total": 5
}
```

**Notes:**
- `has_icon`: Boolean indicating if group has uploaded image icon
- To fetch icon image: `GET /api/groups/{group_id}/icon`
- If `has_icon` is true, use icon image; otherwise use emoji + color or first letter

---

#### DELETE /api/users/me

Delete user account (GDPR compliance).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "password": "userPassword123" // Required for confirmation
}
```

**Response (204 No Content)**

**Server Logic:**
1. Verify access token
2. Verify password
3. Soft delete: Set deleted_at timestamp
4. Schedule hard delete after 30 days
5. Send confirmation email
6. Return success

**Note:** Hard delete handled by scheduled job after 30-day grace period.

---

### 3.3 Group Endpoints

#### POST /api/groups

Create new group.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "name": "Morning Runners",
  "description": "Daily accountability for morning runs",
  "icon_emoji": "🏃",
  "icon_color": "#1976D2",
  "initial_goals": [
    {
      "title": "30 min run",
      "description": "Run for at least 30 minutes",
      "cadence": "daily",
      "metric_type": "binary"
    }
  ]
}
```

**Validation:**
- name: 1-100 characters, required
- description: Optional, max 500 characters
- icon_emoji: Optional, single emoji character
- icon_color: Optional, hex color code (e.g., "#1976D2")
- initial_goals: Optional array

**Response (201 Created):**
```json
{
  "id": "group-uuid",
  "name": "Morning Runners",
  "description": "Daily accountability for morning runs",
  "icon_emoji": "🏃",
  "icon_color": "#1976D2",
  "has_icon": false,
  "creator_user_id": "user-uuid",
  "member_count": 1,
  "created_at": "2026-01-16T10:00:00Z"
}
```

**Notes:**
- Icon images cannot be uploaded during creation
- Use `PATCH /api/groups/:id/icon` to upload icon image after creation

**Server Logic:**
1. Verify access token
2. Validate input
3. Create group
4. Add creator as member (role='creator')
5. If initial_goals provided: Create goals
6. Create group_activity entry
7. Return group data

---

#### GET /api/groups/:group_id

Get group details.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "id": "group-uuid",
  "name": "Morning Runners",
  "description": "Daily accountability for morning runs",
  "icon_emoji": "🏃",
  "icon_color": "#1976D2",
  "has_icon": false,
  "creator_user_id": "user-uuid",
  "member_count": 8,
  "created_at": "2026-01-16T10:00:00Z",
  "user_role": "member"
}
```

**Authorization:**
- User must be a member of the group

**Performance Note - Avoiding N+1 Queries:**
```typescript
// ❌ BAD: N+1 Query Problem
const group = await db.selectFrom('groups').where('id', '=', groupId).executeTakeFirst();
const memberCount = await db.selectFrom('group_memberships').where('group_id', '=', groupId).execute(); // +1 query

// ✅ GOOD: Single query with JOIN and COUNT
const group = await db
  .selectFrom('groups')
  .leftJoin('group_memberships', 'groups.id', 'group_memberships.group_id')
  .select([
    'groups.id',
    'groups.name',
    'groups.description',
    'groups.icon_emoji',
    'groups.creator_user_id',
    'groups.created_at',
    db.fn.count('group_memberships.id').as('member_count')
  ])
  .where('groups.id', '=', groupId)
  .groupBy('groups.id')
  .executeTakeFirst();
```

For endpoints returning goals with progress:
```typescript
// ✅ GOOD: Fetch goals and today's progress in one query
const goalsWithProgress = await db
  .selectFrom('goals')
  .leftJoin('progress_entries', (join) => join
    .onRef('goals.id', '=', 'progress_entries.goal_id')
    .on('progress_entries.user_id', '=', userId)
    .on('progress_entries.period_start', '=', todayDate)
  )
  .select([
    'goals.id',
    'goals.title',
    'goals.cadence',
    'progress_entries.value as today_value',
    'progress_entries.id as progress_id'
  ])
  .where('goals.group_id', '=', groupId)
  .where('goals.deleted_at', 'is', null)
  .execute();
```

---

#### PATCH /api/groups/:group_id

Update group metadata (admin only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "name": "Dawn Warriors Running Club",
  "description": "Updated description",
  "icon_emoji": "🏃‍♂️",
  "icon_color": "#F9A825"
}
```

**Validation:**
- All fields optional (only update provided fields)
- name: 1-100 characters if provided
- description: Max 500 characters if provided
- icon_emoji: Single emoji character if provided
- icon_color: Valid hex color code if provided

**Response (200 OK):**
```json
{
  "id": "group-uuid",
  "name": "Dawn Warriors Running Club",
  "description": "Updated description",
  "icon_emoji": "🏃‍♂️",
  "icon_color": "#F9A825",
  "has_icon": false,
  "updated_at": "2026-01-20T15:30:00Z"
}
```

**Authorization:**
- User must be admin or creator

---

#### PATCH /api/groups/:group_id/icon

Upload group icon image (admin only).

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
- File type: image/png, image/jpeg, image/webp
- Max file size: 5 MB
- Recommended dimensions: 512x512 (will be resized to 256x256)
- Aspect ratio: Square preferred (will be cropped to square)

**Response (200 OK):**
```json
{
  "id": "group-uuid",
  "has_icon": true,
  "icon_emoji": null,
  "icon_color": null,
  "updated_at": "2026-01-20T15:30:00Z"
}
```

**Server Logic:**
1. Verify user is admin or creator
2. Validate file (type, size)
3. Resize and crop image to 256x256 square using sharp
4. Convert to WebP format (quality: 90%)
5. Store in icon_data column as BYTEA
6. Set icon_mime_type to 'image/webp'
7. Clear icon_emoji and icon_color (uploading image overrides emoji)
8. Return updated group

**Image Processing:**
```typescript
import sharp from 'sharp';
import multer from 'multer';

const upload = multer({ 
  storage: multer.memoryStorage(),
  limits: { fileSize: 5 * 1024 * 1024 } // 5 MB
});

app.patch('/api/groups/:group_id/icon', authenticate, upload.single('icon'), async (req, res) => {
  // Verify user is admin or creator
  const membership = await db
    .selectFrom('group_memberships')
    .select('role')
    .where('group_id', '=', req.params.group_id)
    .where('user_id', '=', req.user.user_id)
    .executeTakeFirst();
    
  if (!membership || !['admin', 'creator'].includes(membership.role)) {
    return res.status(403).json({ error: 'Forbidden' });
  }
  
  if (!req.file) {
    return res.status(400).json({ error: 'No file uploaded' });
  }
  
  // Process image
  const processedImage = await sharp(req.file.buffer)
    .resize(256, 256, { fit: 'cover', position: 'center' })
    .webp({ quality: 90 })
    .toBuffer();
    
  // Store in database
  const group = await db
    .updateTable('groups')
    .set({
      icon_data: processedImage,
      icon_mime_type: 'image/webp',
      icon_emoji: null,      // Clear emoji when uploading image
      icon_color: null,      // Clear color when uploading image
      updated_at: new Date()
    })
    .where('id', '=', req.params.group_id)
    .returning(['id', 'icon_emoji', 'icon_color', 'updated_at'])
    .executeTakeFirst();
    
  res.json({
    id: group.id,
    has_icon: true,
    icon_emoji: group.icon_emoji,
    icon_color: group.icon_color,
    updated_at: group.updated_at
  });
});
```

**Authorization:**
- User must be admin or creator

**Notes:**
- Uploading an icon image clears icon_emoji and icon_color
- To revert to emoji icon, use PATCH /api/groups/:id with icon_emoji
- To fetch icon: GET /api/groups/:group_id/icon

---

#### DELETE /api/groups/:group_id/icon

Delete group icon image (revert to emoji or default).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "id": "group-uuid",
  "has_icon": false,
  "icon_emoji": "🏃",
  "icon_color": "#1976D2",
  "updated_at": "2026-01-20T15:30:00Z"
}
```

**Server Logic:**
1. Verify user is admin or creator
2. Set icon_data = NULL and icon_mime_type = NULL
3. Optionally set default icon_emoji and icon_color if none exist
4. Return updated group

**Authorization:**
- User must be admin or creator

---

#### GET /api/groups/:group_id/icon

Get group icon image.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
- Content-Type: image/webp (or original mime type)
- Body: Binary image data

**Response Headers:**
```
Content-Type: image/webp
Cache-Control: public, max-age=86400
ETag: "icon-{group_id}-{updated_at_timestamp}"
```

**Response (404 Not Found):**
```json
{
  "error": {
    "message": "Group has no icon image",
    "code": "NO_ICON"
  }
}
```

**Server Logic:**
```typescript
app.get('/api/groups/:group_id/icon', async (req, res) => {
  const group = await db
    .selectFrom('groups')
    .select(['icon_data', 'icon_mime_type', 'updated_at'])
    .where('id', '=', req.params.group_id)
    .executeTakeFirst();
    
  if (!group?.icon_data) {
    return res.status(404).json({
      error: {
        message: 'Group has no icon image',
        code: 'NO_ICON'
      }
    });
  }
  
  // Set caching headers
  res.set('Content-Type', group.icon_mime_type || 'image/webp');
  res.set('Cache-Control', 'public, max-age=86400'); // 24 hours
  res.set('ETag', `"icon-${req.params.group_id}-${group.updated_at.getTime()}"`);
  
  // Check ETag for 304 Not Modified
  if (req.get('If-None-Match') === res.get('ETag')) {
    return res.status(304).end();
  }
  
  res.send(group.icon_data);
});
```

---

#### DELETE /api/groups/:group_id

Delete group (creator only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (204 No Content)**

**Server Logic:**
1. Verify user is creator
2. Delete group (CASCADE deletes all related data)
3. Return success

**Authorization:**
- User must be creator

---

#### GET /api/groups/:group_id/members

List group members.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "members": [
    {
      "user_id": "user-uuid",
      "display_name": "Shannon Thompson",
      "has_avatar": true,
      "role": "creator",
      "joined_at": "2026-01-01T10:00:00Z"
    },
    {
      "user_id": "user-uuid-2",
      "display_name": "Alex Johnson",
      "has_avatar": false,
      "role": "member",
      "joined_at": "2026-01-05T14:00:00Z"
    }
  ]
}
```

**Authorization:**
- User must be an **active** member of the group (pending members are restricted)

**Errors:**
- 403: Not a member of this group (`FORBIDDEN`)
- 403: Membership is pending approval (`PENDING_APPROVAL`) – user has joined but not yet approved; client can show "Your join request is pending" messaging

**Notes:**
- To fetch member avatar: `GET /api/users/{user_id}/avatar`

---

#### PATCH /api/groups/:group_id/members/:user_id

Update member role (admin promotion/demotion).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "role": "admin"
}
```

**Response (200 OK):**
```json
{
  "success": true
}
```

**Authorization:**
- User must be creator or admin
- Cannot demote creator
- Cannot change own role

---

#### DELETE /api/groups/:group_id/members/:user_id

Remove member from group (admin only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (204 No Content)**

**Server Logic:**
1. Verify user is admin or creator
2. Cannot remove creator
3. Delete group_membership
4. Create group_activity entry
5. Send FCM to group members
6. Return success

**Authorization:**
- User must be admin or creator
- Cannot remove creator

---

#### DELETE /api/groups/:group_id/members/me

Leave group (self-removal).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (204 No Content)**

**Server Logic:**
1. If user is creator and group has other members: Error
2. If user is creator and sole member: Delete group
3. Otherwise: Delete membership
4. Create activity entry
5. Send FCM to remaining members

---

#### POST /api/groups/:group_id/invites

Generate invite code (admin only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "max_uses": 10,
  "expires_at": "2026-01-23T10:00:00Z"
}
```

**Response (201 Created):**
```json
{
  "code": "PURSUE-ABC123-XYZ789",
  "max_uses": 10,
  "current_uses": 0,
  "expires_at": "2026-01-23T10:00:00Z",
  "created_at": "2026-01-16T10:00:00Z"
}
```

**Server Logic:**
1. Verify user is admin or creator
2. Generate unique code (format: PURSUE-{6 chars}-{6 chars})
3. Store in invite_codes table
4. Return code

**Authorization:**
- User must be admin or creator

---

#### POST /api/groups/join

Join group via invite code.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "invite_code": "PURSUE-ABC123-XYZ789"
}
```

**Response (200 OK):**
```json
{
  "group": {
    "id": "group-uuid",
    "name": "Morning Runners",
    "member_count": 9
  }
}
```

**Server Logic:**
1. Verify access token
2. Find invite code
3. Check code not expired
4. Check max_uses not exceeded (if set)
5. Check user not already member
6. Add user to group (role='member')
7. Increment current_uses
8. Create activity entry
9. Send FCM to group members
10. Return group data

**Errors:**
- 404: Invalid invite code
- 400: Invite expired or max uses exceeded
- 409: Already a member

---

#### GET /api/groups/:group_id/activity

Get group activity feed.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Query Parameters:**
- `limit`: Default 50, max 100
- `offset`: Default 0

**Response (200 OK):**
```json
{
  "activities": [
    {
      "id": "activity-uuid",
      "activity_type": "progress_logged",
      "user": {
        "id": "user-uuid",
        "display_name": "Alex Thompson"
      },
      "metadata": {
        "goal_id": "goal-uuid",
        "goal_title": "30 min run",
        "value": 1
      },
      "created_at": "2026-01-16T07:30:00Z"
    },
    {
      "activity_type": "member_joined",
      "user": {
        "id": "user-uuid-2",
        "display_name": "Jamie Smith"
      },
      "metadata": {},
      "created_at": "2026-01-15T14:00:00Z"
    }
  ],
  "total": 156
}
```

**Authorization:**
- User must be an **active** member of the group (pending members are restricted)

**Errors:**
- 403: Not a member of this group (`FORBIDDEN`)
- 403: Membership is pending approval (`PENDING_APPROVAL`) – user has joined but not yet approved; client can show "Your join request is pending" messaging

**Activity Types:**
- `progress_logged`
- `member_joined`
- `member_left`
- `member_promoted`
- `member_removed`
- `goal_added`
- `goal_archived`
- `group_renamed`

---

### 3.4 Goal Endpoints

#### POST /api/groups/:group_id/goals

Create goal (admin only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "title": "30 min run",
  "description": "Run for at least 30 minutes",
  "cadence": "daily",
  "metric_type": "binary"
}
```

**For numeric goals:**
```json
{
  "title": "Read 50 pages",
  "cadence": "daily",
  "metric_type": "numeric",
  "target_value": 50,
  "unit": "pages"
}
```

**Validation:**
- title: 1-200 characters, required
- description: Optional, max 1000 characters
- cadence: Required, one of: 'daily', 'weekly', 'monthly', 'yearly'
- metric_type: Required, one of: 'binary', 'numeric', 'duration'
- target_value: Required for numeric, optional for binary
- unit: Optional, max 50 characters

**Response (201 Created):**
```json
{
  "id": "goal-uuid",
  "group_id": "group-uuid",
  "title": "30 min run",
  "description": "Run for at least 30 minutes",
  "cadence": "daily",
  "metric_type": "binary",
  "target_value": null,
  "unit": null,
  "created_by_user_id": "user-uuid",
  "created_at": "2026-01-16T10:00:00Z",
  "archived_at": null
}
```

**Server Logic:**
1. Verify user is member and admin/creator
2. Validate input
3. Check resource limits (max 100 goals per group)
4. Create goal
5. Create activity entry
6. Send FCM to group members
7. Return goal data

**Authorization:**
- User must be admin or creator

**Rate Limiting:**
- Max 100 goals per group (resource limit)
- Max 20 goals created per day per group (future)
- Max 5 goals created per hour per group (future)

---

#### GET /api/groups/:group_id/goals

Get all goals for a group, optionally with current period progress.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Query Parameters:**
- `cadence` (optional): Filter by cadence ('daily', 'weekly', 'monthly', 'yearly')
- `archived` (optional): Include archived goals ('true' or 'false', default: 'false')
- `include_progress` (optional): Include current period progress data ('true' or 'false', default: 'false')

**Response (200 OK) - Without Progress:**
```json
{
  "goals": [
    {
      "id": "goal-uuid-1",
      "group_id": "group-uuid",
      "title": "Run 3x per week",
      "description": "Run for at least 30 minutes",
      "cadence": "weekly",
      "metric_type": "binary",
      "target_value": 3,
      "unit": null,
      "created_by_user_id": "user-uuid",
      "created_at": "2026-01-16T10:00:00Z",
      "archived_at": null
    }
  ],
  "total": 1
}
```

**Response (200 OK) - With Progress (include_progress=true):**
```json
{
  "goals": [
    {
      "id": "goal-uuid-1",
      "group_id": "group-uuid",
      "title": "Run 3x per week",
      "description": "Run for at least 30 minutes",
      "cadence": "weekly",
      "metric_type": "binary",
      "target_value": 3,
      "unit": null,
      "created_by_user_id": "user-uuid",
      "created_at": "2026-01-16T10:00:00Z",
      "archived_at": null,
      
      "current_period_progress": {
        "start_date": "2026-01-20T00:00:00Z",
        "end_date": "2026-01-26T23:59:59Z",
        "period_type": "weekly",
        
        "user_progress": {
          "completed": 2,
          "total": 3,
          "percentage": 67,
          "entries": [
            {
              "date": "2026-01-20",
              "value": 1
            },
            {
              "date": "2026-01-22",
              "value": 1
            }
          ]
        },
        
        "member_progress": [
          {
            "user_id": "user-uuid-1",
            "display_name": "Shannon",
            "avatar_url": "https://...",
            "completed": 2,
            "percentage": 67
          },
          {
            "user_id": "user-uuid-2",
            "display_name": "Alex",
            "avatar_url": null,
            "completed": 3,
            "percentage": 100
          },
          {
            "user_id": "user-uuid-3",
            "display_name": "Jamie",
            "avatar_url": null,
            "completed": 1,
            "percentage": 33
          }
        ]
      }
    },
    {
      "id": "goal-uuid-2",
      "title": "Read 50 pages",
      "cadence": "weekly",
      "metric_type": "numeric",
      "target_value": 50,
      "unit": "pages",
      
      "current_period_progress": {
        "start_date": "2026-01-20T00:00:00Z",
        "end_date": "2026-01-26T23:59:59Z",
        "period_type": "weekly",
        
        "user_progress": {
          "completed": 35,
          "total": 50,
          "percentage": 70,
          "entries": [
            {
              "date": "2026-01-20",
              "value": 15
            },
            {
              "date": "2026-01-22",
              "value": 20
            }
          ]
        },
        
        "member_progress": [
          {
            "user_id": "user-uuid-1",
            "display_name": "Shannon",
            "completed": 35,
            "percentage": 70
          },
          {
            "user_id": "user-uuid-2",
            "display_name": "Alex",
            "completed": 50,
            "percentage": 100
          }
        ]
      }
    }
  ],
  "total": 2
}
```

**Server Logic:**
1. Verify user is member of group
2. Query all goals for group
3. Apply filters (cadence, archived status)
4. If `include_progress=true`:
   - Calculate current period bounds for each goal based on cadence
   - Fetch all progress entries for all goals in ONE query (using SQL IN clause)
   - Fetch all group members in ONE query
   - Attach progress data to each goal
5. Order by: archived_at (nulls first), then created_at (newest first)
6. Return goals array with total count

**Authorization:**
- User must be an **active** member of the group (pending members are restricted)

**Errors:**
- 401: Unauthorized (invalid/missing token)
- 403: Not a member of this group (`FORBIDDEN`)
- 403: Membership is pending approval (`PENDING_APPROVAL`) – user has joined but not yet approved; client can show "Your join request is pending" messaging
- 404: Group not found

**Performance - No N+1 Problem:**
- **Without progress**: 1 query (goals only)
- **With progress**: 3 queries total:
  1. Fetch goals
  2. Fetch all progress entries (batch query with IN clause)
  3. Fetch all group members
- Scales efficiently: 10 goals = 3 queries, 100 goals = 3 queries

**Implementation:**
```typescript
import { startOfDay, endOfDay, startOfWeek, endOfWeek, startOfMonth, endOfMonth, startOfYear, endOfYear } from 'date-fns';

app.get('/api/groups/:group_id/goals', authenticateJWT, async (req, res) => {
  const { group_id } = req.params;
  const { cadence, archived, include_progress } = req.query;
  
  // Verify membership
  const membership = await db
    .selectFrom('group_members')
    .select('role')
    .where('group_id', '=', group_id)
    .where('user_id', '=', req.user.user_id)
    .executeTakeFirst();
  
  if (!membership) {
    return res.status(403).json({ error: 'Not a member of this group' });
  }
  
  // Build query
  let query = db
    .selectFrom('goals')
    .selectAll()
    .where('group_id', '=', group_id);
  
  // Filter by cadence if provided
  if (cadence && ['daily', 'weekly', 'monthly', 'yearly'].includes(cadence as string)) {
    query = query.where('cadence', '=', cadence as string);
  }
  
  // Filter archived
  if (archived === 'true') {
    // Include all goals
  } else {
    // Default: only active goals
    query = query.where('archived_at', 'is', null);
  }
  
  // Order: archived goals last, then newest first
  query = query
    .orderBy('archived_at', 'asc nulls first')
    .orderBy('created_at', 'desc');
  
  const goals = await query.execute();
  
  // If include_progress requested, attach progress data
  if (include_progress === 'true' && goals.length > 0) {
    const goalsWithProgress = await attachProgressToGoals(
      goals,
      req.user.user_id,
      group_id
    );
    
    return res.json({
      goals: goalsWithProgress,
      total: goalsWithProgress.length
    });
  }
  
  // Default: return goals without progress
  res.json({
    goals,
    total: goals.length
  });
});

// Helper function to attach progress in 2 additional queries (not N+1!)
async function attachProgressToGoals(
  goals: Goal[],
  currentUserId: string,
  groupId: string
) {
  if (goals.length === 0) return goals;
  
  const goalIds = goals.map(g => g.id);
  const now = new Date();
  
  // Calculate period bounds for each goal
  const periodBounds = goals.map(goal => {
    const bounds = getPeriodBounds(goal.cadence, now);
    return {
      goal_id: goal.id,
      start: bounds.start,
      end: bounds.end
    };
  });
  
  // Find the earliest start date across all periods
  const earliestStart = new Date(
    Math.min(...periodBounds.map(p => p.start.getTime()))
  );
  
  // QUERY 2: Fetch all progress entries for all goals in ONE query
  // Using SQL IN clause - very efficient with proper index
  const allProgressEntries = await db
    .selectFrom('progress_entries')
    .select([
      'progress_entries.id',
      'progress_entries.goal_id',
      'progress_entries.user_id',
      'progress_entries.value',
      'progress_entries.entry_date',
      'progress_entries.note',
      'progress_entries.created_at'
    ])
    .where('progress_entries.goal_id', 'in', goalIds)
    .where('progress_entries.entry_date', '>=', earliestStart.toISOString().split('T')[0])
    .orderBy('progress_entries.entry_date', 'desc')
    .execute();
  
  // QUERY 3: Fetch all group members in ONE query
  const members = await db
    .selectFrom('group_members')
    .innerJoin('users', 'users.id', 'group_members.user_id')
    .select([
      'users.id',
      'users.display_name',
      'users.avatar_url'
    ])
    .where('group_members.group_id', '=', groupId)
    .execute();
  
  // Group progress entries by goal_id (in-memory, O(n) - fast)
  const progressByGoal: Record<string, typeof allProgressEntries> = {};
  for (const entry of allProgressEntries) {
    if (!progressByGoal[entry.goal_id]) {
      progressByGoal[entry.goal_id] = [];
    }
    progressByGoal[entry.goal_id].push(entry);
  }
  
  // Attach progress to each goal
  return goals.map(goal => {
    const periodBound = periodBounds.find(p => p.goal_id === goal.id)!;
    const goalProgressEntries = progressByGoal[goal.id] || [];
    
    // Filter entries for this goal's current period
    const currentPeriodEntries = goalProgressEntries.filter(entry => {
      const entryDate = new Date(entry.entry_date);
      return entryDate >= periodBound.start && entryDate <= periodBound.end;
    });
    
    // Calculate current user's progress
    const userEntries = currentPeriodEntries.filter(
      e => e.user_id === currentUserId
    );
    
    const userCompleted = calculateCompleted(goal, userEntries);
    const total = calculateTotal(goal, periodBound);
    const userPercentage = total > 0 ? Math.round((userCompleted / total) * 100) : 0;
    
    // Calculate each member's progress
    const memberProgress = members.map(member => {
      const memberEntries = currentPeriodEntries.filter(
        e => e.user_id === member.id
      );
      const completed = calculateCompleted(goal, memberEntries);
      const percentage = total > 0 ? Math.round((completed / total) * 100) : 0;
      
      return {
        user_id: member.id,
        display_name: member.display_name,
        avatar_url: member.avatar_url,
        completed,
        percentage
      };
    });
    
    return {
      ...goal,
      current_period_progress: {
        start_date: periodBound.start.toISOString(),
        end_date: periodBound.end.toISOString(),
        period_type: goal.cadence,
        user_progress: {
          completed: userCompleted,
          total,
          percentage: userPercentage,
          entries: userEntries.map(e => ({
            date: e.entry_date,
            value: e.value || 1
          }))
        },
        member_progress: memberProgress
      }
    };
  });
}

// Helper: Get period start/end based on cadence
function getPeriodBounds(cadence: string, date: Date): { start: Date; end: Date } {
  switch (cadence) {
    case 'daily':
      return {
        start: startOfDay(date),
        end: endOfDay(date)
      };
    case 'weekly':
      return {
        start: startOfWeek(date, { weekStartsOn: 1 }), // Monday
        end: endOfWeek(date, { weekStartsOn: 1 })
      };
    case 'monthly':
      return {
        start: startOfMonth(date),
        end: endOfMonth(date)
      };
    case 'yearly':
      return {
        start: startOfYear(date),
        end: endOfYear(date)
      };
    default:
      return {
        start: startOfDay(date),
        end: endOfDay(date)
      };
  }
}

// Helper: Calculate completed count/value
function calculateCompleted(goal: Goal, entries: any[]): number {
  if (goal.metric_type === 'binary') {
    // For binary: count number of entries (days completed)
    return entries.length;
  }
  
  if (goal.metric_type === 'numeric' || goal.metric_type === 'duration') {
    // For numeric: sum of all values
    return entries.reduce((sum, entry) => sum + (entry.value || 0), 0);
  }
  
  return 0;
}

// Helper: Calculate total target for period
function calculateTotal(goal: Goal, period: { start: Date; end: Date }): number {
  if (goal.metric_type === 'binary') {
    // For binary: target is the goal's target_value (e.g., 3x per week)
    return goal.target_value || 1;
  }
  
  if (goal.metric_type === 'numeric' || goal.metric_type === 'duration') {
    // For numeric: target_value is the goal (e.g., 50 pages per week)
    return goal.target_value || 0;
  }
  
  return 0;
}
```

**Database Indexes (Critical for Performance):**
```sql
-- Index for goals query
CREATE INDEX idx_goals_group_id_archived 
ON goals(group_id, archived_at, created_at DESC);

-- Index for progress batch query (CRITICAL - prevents N+1)
CREATE INDEX idx_progress_goal_date 
ON progress_entries(goal_id, entry_date DESC);

-- Composite index for even better performance
CREATE INDEX idx_progress_goal_user_date 
ON progress_entries(goal_id, user_id, entry_date DESC);

-- Index for members query
CREATE INDEX idx_group_members_group_id 
ON group_members(group_id);
```

**SQL Query Examples:**

```sql
-- EFFICIENT: Batch query with IN clause (ONE query for all goals)
SELECT * FROM progress_entries 
WHERE goal_id IN ('uuid1', 'uuid2', 'uuid3', 'uuid4', 'uuid5')
  AND entry_date >= '2026-01-20'
ORDER BY goal_id, entry_date DESC;

-- With proper index, this is O(log n) per goal_id
-- For 10 goals: ~10ms total
-- For 100 goals: ~50ms total

-- INEFFICIENT: N+1 approach (avoid this!)
SELECT * FROM progress_entries WHERE goal_id = 'uuid1' AND entry_date >= '2026-01-20';
SELECT * FROM progress_entries WHERE goal_id = 'uuid2' AND entry_date >= '2026-01-20';
SELECT * FROM progress_entries WHERE goal_id = 'uuid3' AND entry_date >= '2026-01-20';
-- ... (10 queries for 10 goals = ~100ms, 100 queries for 100 goals = ~1000ms)
```

**Performance Comparison:**

| Goals | Without Progress | With Progress (N+1) | With Progress (Batch) |
|-------|------------------|---------------------|----------------------|
| 10    | 1 query (10ms)   | 11 queries (110ms)  | 3 queries (30ms)     |
| 50    | 1 query (15ms)   | 51 queries (510ms)  | 3 queries (50ms)     |
| 100   | 1 query (20ms)   | 101 queries (1010ms)| 3 queries (80ms)     |

**Performance Notes:**
- Most groups will have 5-20 goals (very fast)
- Max 100 goals per group (enforced by resource limits)
- Batch query with IN clause scales linearly with number of goals
- In-memory grouping is O(n) - negligible overhead
- **No N+1 problem** - query count is constant (3) regardless of goal count

**Android Usage:**

```kotlin
// Fetch goals with progress in ONE API call
val response = apiClient.getGroupGoals(
    groupId = groupId,
    includeProgress = true  // ← Include progress data
)

// response.goals already contains:
// - Goal details
// - Current period progress
// - User's progress
// - All members' progress

// No additional API calls needed!
adapter.submitList(response.goals)
```

---

#### GET /api/goals/:goal_id

Get goal details.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "id": "goal-uuid",
  "group_id": "group-uuid",
  "title": "30 min run",
  "description": "Run for at least 30 minutes",
  "cadence": "daily",
  "metric_type": "binary",
  "created_at": "2026-01-16T10:00:00Z"
}
```

**Authorization:**
- User must be member of goal's group

---

#### PATCH /api/goals/:goal_id

Update goal (admin only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "title": "35 min run",
  "description": "Increased to 35 minutes"
}
```

**Response (200 OK):**
```json
{
  "id": "goal-uuid",
  "title": "35 min run",
  "description": "Increased to 35 minutes"
}
```

**Authorization:**
- User must be admin or creator

---

#### DELETE /api/goals/:goal_id

Soft delete goal (admin only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (204 No Content)**

**Server Logic:**
1. Verify user is admin or creator of goal's group
2. Set deleted_at = NOW()
3. Set deleted_by_user_id = current user
4. Goal no longer appears in active goals list
5. **Historical progress preserved** (critical for streaks, analytics)
6. Create group_activity entry: "Goal archived"
7. Send FCM to group members
8. Return success

**Restoration (Future Enhancement):**
- Admin can restore deleted goal by setting deleted_at = NULL
- All historical progress entries remain intact
- Streaks can be recalculated from preserved data

**Why Soft Delete:**
- Preserves accountability history (user had 100-day streak)
- Enables "undo" functionality
- Supports analytics on deleted vs active goals
- Required for proper streak calculations

**Authorization:**
- User must be admin or creator

**Note:** Use `WHERE deleted_at IS NULL` in all queries for active goals

---

#### GET /api/goals/:goal_id/progress

Get all users' progress for a goal.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Query Parameters:**
- `start_date`: ISO date (e.g., '2026-01-01')
- `end_date`: ISO date (e.g., '2026-01-31')

**Response (200 OK):**
```json
{
  "goal": {
    "id": "goal-uuid",
    "title": "30 min run",
    "cadence": "daily"
  },
  "progress": [
    {
      "user_id": "user-uuid",
      "display_name": "Shannon Thompson",
      "entries": [
        {
          "id": "entry-uuid",
          "value": 1,
          "note": "Great run!",
          "period_start": "2026-01-16",
          "logged_at": "2026-01-16T07:30:00Z"
        }
      ]
    }
  ]
}
```

---

#### GET /api/goals/:goal_id/progress/me

Get my progress for a goal.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Query Parameters:**
- `start_date`: ISO date
- `end_date`: ISO date

**Response (200 OK):**
```json
{
  "goal_id": "goal-uuid",
  "entries": [
    {
      "id": "entry-uuid",
      "value": 1,
      "note": "Morning run in the park",
      "period_start": "2026-01-16",
      "logged_at": "2026-01-16T07:30:00Z"
    }
  ]
}
```

---

### 3.5 Progress Endpoints

#### POST /api/progress

Log progress entry.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request (Binary Goal):**
```json
{
  "goal_id": "goal-uuid",
  "value": 1,
  "note": "Great run in the park!",
  "user_date": "2026-01-17",
  "user_timezone": "America/New_York"
}
```

**Request (Numeric Goal):**
```json
{
  "goal_id": "goal-uuid",
  "value": 55,
  "note": "Read 55 pages of 'The Stand'",
  "user_date": "2026-01-17",
  "user_timezone": "America/New_York"
}
```

**Validation (Zod Schema):**
```typescript
import { z } from 'zod';

const progressSchema = z.object({
  goal_id: z.string().uuid(),
  value: z.number(),
  note: z.string().max(500).optional(),
  user_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), // YYYY-MM-DD
  user_timezone: z.string().min(1) // IANA timezone
}).refine(async (data) => {
  // Fetch goal to validate value against metric_type
  const goal = await db
    .selectFrom('goals')
    .select(['metric_type', 'target_value'])
    .where('id', '=', data.goal_id)
    .executeTakeFirst();
    
  if (!goal) throw new Error('Goal not found');
  
  // Validate value based on metric_type
  switch (goal.metric_type) {
    case 'binary':
      if (data.value !== 0 && data.value !== 1) {
        throw new Error('Binary goal value must be 0 or 1');
      }
      break;
      
    case 'numeric':
      if (data.value < 0) {
        throw new Error('Numeric goal value cannot be negative');
      }
      if (data.value > 999999.99) {
        throw new Error('Numeric goal value too large');
      }
      break;
      
    case 'duration':
      if (data.value < 0) {
        throw new Error('Duration cannot be negative');
      }
      if (!Number.isInteger(data.value)) {
        throw new Error('Duration must be an integer (seconds)');
      }
      break;
  }
  
  return true;
});
```

**Validation Rules:**
- goal_id: Valid UUID, goal must exist and not be deleted
- value: 
  - **Binary goals:** Exactly 0 or 1
  - **Numeric goals:** Non-negative, max 999999.99
  - **Duration goals:** Positive integer (seconds)
- note: Max 500 characters
- user_date: ISO date format (YYYY-MM-DD), not future date
- user_timezone: Valid IANA timezone string

**Response (201 Created):**
```json
{
  "id": "entry-uuid",
  "goal_id": "goal-uuid",
  "user_id": "user-uuid",
  "value": 1,
  "note": "Great run in the park!",
  "period_start": "2026-01-17",
  "logged_at": "2026-01-17T23:30:00Z"
}
```

**Server Logic:**
1. Verify user is member of goal's group
2. Validate input with Zod schema
3. **Timezone Handling:**
   - Accept user_date as-is (user's local date)
   - Store as period_start DATE (not converted to UTC)
   - This ensures Friday 11 PM in New York counts as Friday, not Saturday
4. Calculate period_start based on goal cadence:
   - Daily: user_date as-is
   - Weekly: Monday of that week (in user's local calendar)
   - Monthly: First day of month (user's local calendar)
   - Yearly: January 1st (user's local calendar)
5. Check for duplicate entry (same goal_id, user_id, period_start)
6. Create progress_entry
7. Create group_activity entry
8. Send FCM to group members: "{user} completed {goal}"
9. Return entry

**Important Timezone Notes:**
- **Do NOT convert user_date to UTC** - store exactly what user sends
- period_start is a DATE, not TIMESTAMP
- If user travels across timezones, they specify their current timezone
- Historical data preserves original timezone for reference

**Authorization:**
- User must be an **active** member of goal's group (pending members are restricted)

**Errors:**
- 400: Invalid input
- 400: Duplicate entry (already logged for this period)
- 403: Not a member of goal's group (`FORBIDDEN`)
- 403: Membership is pending approval (`PENDING_APPROVAL`) – user has joined but not yet approved
- 404: Goal not found

**Rate Limiting:**
- 60 requests per minute per user (prevents spam/loops)

---

#### GET /api/progress/:entry_id

Get specific progress entry.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "id": "entry-uuid",
  "goal_id": "goal-uuid",
  "user_id": "user-uuid",
  "value": 1,
  "note": "Great run!",
  "period_start": "2026-01-16",
  "logged_at": "2026-01-16T07:30:00Z"
}
```

---

#### DELETE /api/progress/:entry_id

Delete progress entry (own entries only).

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (204 No Content)**

**Server Logic:**
1. Verify user owns the entry
2. Delete entry
3. Return success

**Authorization:**
- User must own the entry

---

### 3.6 Device Endpoints

#### POST /api/devices/register

Register device for push notifications.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Request:**
```json
{
  "fcm_token": "fcm-device-token-here",
  "device_name": "Pixel 8 Pro",
  "platform": "android"
}
```

**Response (200 OK):**
```json
{
  "id": "device-uuid",
  "device_name": "Pixel 8 Pro",
  "platform": "android"
}
```

**Server Logic:**
1. Verify access token
2. Upsert device (update if fcm_token exists)
3. Set last_active = NOW()
4. Return device data

---

#### GET /api/devices

List user's registered devices.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (200 OK):**
```json
{
  "devices": [
    {
      "id": "device-uuid",
      "device_name": "Pixel 8 Pro",
      "platform": "android",
      "last_active": "2026-01-16T10:00:00Z",
      "created_at": "2026-01-01T10:00:00Z"
    }
  ]
}
```

---

#### DELETE /api/devices/:device_id

Unregister device.

**Headers:**
```
Authorization: Bearer {access_token}
```

**Response (204 No Content)**

**Server Logic:**
1. Verify user owns device
2. Delete device
3. Return success

---

