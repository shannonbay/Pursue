# Pursue - Technical Specification

**Version:** 0.2 (Centralized Architecture)  
**Last Updated:** January 16, 2026  
**Status:** Implementation Ready

---

## 1. Overview

### 1.1 Purpose
**Pursue** is an Android mobile application that enables individuals to form accountability groups for tracking and sharing progress on personal goals (daily, weekly, monthly, yearly). The system uses a centralized backend server for data storage and synchronization, designed for scalability, reliability, and exceptional user experience.

### 1.2 Core Principles
- **Production-Grade Quality**: Built to scale from initial users to hundreds of thousands
- **High Availability**: Zero-downtime deployments, automatic failover, health monitoring
- **Performance**: Fast startup (< 2s), smooth interactions (60fps), efficient API calls (< 500ms)
- **Privacy-Conscious**: Clear data policies, no ads, no data selling, encrypted token storage
- **Offline-Capable**: View existing data offline, queue updates for when online
- **Secure**: JWT authentication, input validation, SQL injection prevention
- **Accessible**: Colorblind-friendly design, WCAG 2.1 Level AA compliance
- **Resilient**: Graceful error handling, automatic retry logic, comprehensive monitoring

### 1.3 Architecture Overview
```
┌─────────────────────────────────────┐
│     Android App (Multiple Devices)   │
│  ┌──────────┐      ┌──────────┐    │
│  │  Phone   │      │    PC    │    │
│  └────┬─────┘      └────┬─────┘    │
└───────┼──────────────────┼──────────┘
        │                  │
        │   HTTPS/REST     │
        │                  │
┌───────▼──────────────────▼──────────┐
│    Backend Server (Cloud Run)       │
│  ┌─────────────────────────────┐   │
│  │   Express.js REST API       │   │
│  │   - Authentication (JWT)    │   │
│  │   - Groups & Goals CRUD     │   │
│  │   - Progress Tracking       │   │
│  │   - FCM Push Notifications  │   │
│  └──────────┬──────────────────┘   │
└─────────────┼──────────────────────┘
              │
┌─────────────▼──────────────────────┐
│   PostgreSQL Database (Cloud SQL)  │
│   - Users & Devices                │
│   - Groups & Memberships           │
│   - Goals & Progress Entries       │
└────────────────────────────────────┘
```

**Data Flow:**
1. User logs progress on phone
2. App sends HTTPS POST to backend server
3. Server validates, stores in PostgreSQL
4. Server sends FCM push to other group members
5. Other devices receive push, refresh data from server
6. **Result**: Instant sync across all devices

---

## 2. System Architecture

### 2.1 Technology Stack

**Mobile App:**
- Platform: Android (Kotlin + Jetpack Compose)
- Database: SQLite (local cache only)
- Networking: Retrofit + OkHttp
- Authentication: JWT tokens stored securely
- Push: Firebase Cloud Messaging (FCM)

**Backend Server:**
- Runtime: Node.js 20.x + TypeScript
- Framework: Express.js
- Database: PostgreSQL (Cloud SQL)
- Deployment: Google Cloud Run
- Authentication: JWT (JSON Web Tokens)
- Push: Firebase Admin SDK

**Infrastructure:**
- Hosting: Google Cloud Run (serverless, auto-scaling)
- Database: Cloud SQL for PostgreSQL
- Storage: PostgreSQL BYTEA (initial implementation), with migration path to object storage
- Monitoring: Cloud Logging, Monitoring, and Alerting

### 2.2 Data Storage Strategy

**Server (Source of Truth):**
- All users, groups, goals, progress stored in PostgreSQL
- Server is authoritative for all data
- Clients must sync with server to stay updated

**Client (Local Cache):**
- SQLite caches recently viewed data for offline viewing
- Cache invalidated on server updates (via push notifications)
- Queues updates when offline, uploads when connected

### 2.3 Sync Model

**Simple Client-Server Sync:**
1. Client requests data: `GET /api/groups/{id}`
2. Server returns latest data with `last_modified` timestamp
3. Client caches locally
4. On update: Client `POST /api/progress` → Server stores → Server pushes to peers
5. Peers receive FCM → Peers refresh from server

**No complex sync protocol** - just standard REST API + push notifications.

---

## 3. Identity Management

### 3.1 Account Model

**User Identity:**
- **User ID**: Server-generated UUID
- **Email**: Required, unique identifier for account
- **Password**: Optional - hashed with bcrypt if user signs up via email
- **Display Name**: User's chosen name
- **Avatar**: Optional profile picture (from Google or uploaded)

**Authentication Methods:**
1. **Email + Password**: Traditional authentication
2. **Google Sign-In**: OAuth 2.0 via Google (recommended for Android users)

**Account Recovery:**
- Email password reset (for email/password accounts)
- Google account recovery (for Google Sign-In accounts)
- Can link both methods for redundancy

**Multi-Provider Support:**
- One Pursue account can have multiple auth providers linked
- Example: User can sign in with Google OR email/password
- Email is the unique key - linking providers with same email auto-merges

### 3.2 Registration Flow

#### 3.2.1 Sign Up with Email/Password

1. User opens app
2. Taps "Create Account with Email"
3. Enters display name, email, password
4. App sends registration to server:
   ```json
   POST /api/auth/register
   {
     "display_name": "Shannon Thompson",
     "email": "shannon@example.com",
     "password": "hashed-client-side-with-salt"
   }
   ```
5. Server creates user account with password_hash set
6. Server creates auth_providers entry with provider='email'
7. Server returns JWT token
8. App stores JWT securely (Android Keystore)
9. Navigate to Home screen

**Account Recovery:**
- Standard "forgot password" email flow
- POST /api/auth/forgot-password → sends reset link to email

#### 3.2.2 Sign Up with Google

1. User opens app
2. Taps "Continue with Google"
3. Google Sign-In SDK opens (Google account picker)
4. User selects Google account
5. Google returns ID token to app
6. App sends to server:
   ```json
   POST /api/auth/google
   {
     "id_token": "google-jwt-token-here"
   }
   ```
7. Server verifies token with Google API
8. Server extracts: google_user_id, email, name, picture
9. **If email doesn't exist:**
   - Create new user (password_hash = NULL)
   - Create auth_providers entry with provider='google'
10. **If email exists:**
    - Link Google to existing account (add auth_providers entry)
11. Server returns JWT token
12. App stores JWT securely
13. Navigate to Home screen

**Response (New User):**
```json
{
  "access_token": "jwt-token",
  "refresh_token": "refresh-jwt",
  "is_new_user": true,
  "user": {
    "id": "uuid",
    "display_name": "Shannon Thompson",
    "email": "shannon@example.com",
    "avatar_url": "https://lh3.googleusercontent.com/..."
  }
}
```

**Response (Existing User):**
```json
{
  "access_token": "jwt-token",
  "refresh_token": "refresh-jwt",
  "is_new_user": false,
  "user": {
    "id": "uuid",
    "display_name": "Shannon Thompson",
    "email": "shannon@example.com"
  }
}
```

**Account Recovery:**
- Use Google account recovery (Google's responsibility)

### 3.3 Authentication Flow

#### 3.3.1 Sign In with Email/Password

**Login:**
```json
POST /api/auth/login
{
  "email": "shannon@example.com",
  "password": "hashed-password"
}

Response:
{
  "access_token": "jwt-token",
  "refresh_token": "refresh-jwt",
  "user": {
    "id": "uuid",
    "display_name": "Shannon Thompson",
    "email": "shannon@example.com"
  }
}
```

**Token Storage:**
- Access token (JWT): 1 hour expiry
- Refresh token: 30 days expiry
- Stored in Android Keystore (hardware-backed encryption)

**Token Refresh:**
```json
POST /api/auth/refresh
{
  "refresh_token": "refresh-jwt"
}
```

#### 3.3.2 Sign In with Google

**Flow:**
1. User taps "Continue with Google"
2. Google Sign-In SDK opens
3. User selects Google account
4. App receives ID token from Google
5. App sends to server:
   ```json
   POST /api/auth/google
   {
     "id_token": "google-jwt-token"
   }
   ```
6. Server verifies token, finds existing user by Google ID
7. Server returns JWT tokens (same as email login)

**Server-Side Google Token Verification:**
```typescript
import { OAuth2Client } from 'google-auth-library';

const client = new OAuth2Client(process.env.GOOGLE_CLIENT_ID);

async function verifyGoogleToken(idToken: string) {
  const ticket = await client.verifyIdToken({
    idToken: idToken,
    audience: process.env.GOOGLE_CLIENT_ID,
  });
  
  const payload = ticket.getPayload();
  return {
    googleUserId: payload.sub,
    email: payload.email,
    name: payload.name,
    picture: payload.picture,
    emailVerified: payload.email_verified
  };
}
```

### 3.4 Account Recovery

#### Email/Password Accounts

**Forgot Password Flow:**
```json
POST /api/auth/forgot-password
{
  "email": "shannon@example.com"
}

Response:
{
  "message": "If an account exists with this email, a password reset link has been sent."
}
```

**Server Actions:**
1. Check if user exists with email
2. Generate password reset token (expires in 1 hour)
3. Send email with reset link: `https://getpursue.app/reset-password?token=...`
4. User clicks link, enters new password
5. Update password_hash in database

**Reset Password:**
```json
POST /api/auth/reset-password
{
  "token": "reset-token-from-email",
  "new_password": "hashed-new-password"
}

Response:
{
  "access_token": "jwt-token",
  "refresh_token": "refresh-jwt"
}
```

#### Google Sign-In Accounts

**Recovery Method:**
- Use Google's account recovery (Google's responsibility)
- User recovers Google account → can sign into Pursue

#### Accounts with Multiple Providers

**Best Recovery:**
- Link both email/password AND Google
- Forgot email password? → Sign in with Google
- Lost Google access? → Sign in with email/password

### 3.5 Multi-Device Support

**Simple Model:**
- User signs in on multiple devices with email/password OR Google
- Each device gets its own JWT token
- Each device registers FCM token with server
- Server sends push to all user's devices when updates occur

**No complex device linking:**
- Just sign in normally (like Gmail, Slack, etc.)
- Use email password reset if needed

### 3.6 Linking Auth Providers

**Users can link multiple sign-in methods to same account:**

**Link Google to Existing Email Account:**
```json
POST /api/auth/link/google
Authorization: Bearer {jwt-token}

{
  "id_token": "google-jwt-token"
}

Response:
{
  "success": true,
  "provider": "google",
  "provider_email": "shannon@example.com"
}
```

**Server Logic:**
1. Verify user is authenticated (JWT)
2. Verify Google token
3. Check if Google account already linked to another user (error if yes)
4. Create auth_providers entry
5. User can now sign in with either method

**Unlink Provider:**
```json
DELETE /api/auth/unlink/google
Authorization: Bearer {jwt-token}

Response:
{
  "success": true
}
```

**Server Logic:**
1. Check user has at least one other auth method
2. If last method, return error: "Cannot unlink last sign-in method"
3. Delete auth_providers entry

**Get Linked Providers:**
```json
GET /api/users/me/providers
Authorization: Bearer {jwt-token}

Response:
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

### 3.7 Password Management

**Change Password (Email/Password Users):**
```json
POST /api/users/me/password
Authorization: Bearer {jwt-token}

{
  "current_password": "hashed-current-password",
  "new_password": "hashed-new-password"
}

Response:
{
  "success": true
}
```

**Set Password (Google-Only Users):**
- Google-only users can add password for redundancy
- Same endpoint, but current_password is null

```json
POST /api/users/me/password
Authorization: Bearer {jwt-token}

{
  "current_password": null,
  "new_password": "hashed-new-password"
}
```

---

## 4. Group Management

### 4.0 Database Schema - Users & Auth

**Users Table:**
```sql
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  email VARCHAR(255) UNIQUE NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  avatar_url TEXT,
  password_hash VARCHAR(255), -- NULL if user only uses Google Sign-In
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
```

**Auth Providers Table:**
```sql
CREATE TABLE auth_providers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider VARCHAR(50) NOT NULL, -- 'email', 'google'
  provider_user_id VARCHAR(255) NOT NULL, -- Google user ID
  provider_email VARCHAR(255), -- Email from Google (may differ from users.email)
  linked_at TIMESTAMP DEFAULT NOW(),
  UNIQUE(provider, provider_user_id),
  UNIQUE(user_id, provider) -- Each user can link each provider once
);

CREATE INDEX idx_auth_providers_user ON auth_providers(user_id);
CREATE INDEX idx_auth_providers_lookup ON auth_providers(provider, provider_user_id);
```

**Why This Design:**
- Users can sign in via email/password OR Google
- Same email = same account (auto-link on first Google sign-in)
- Password is optional (NULL if user only uses Google)
- Simple, clean schema focused on essential authentication

---

### 4.1 Group Structure

**Database Schema (PostgreSQL):**
```sql
CREATE TABLE groups (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(100) NOT NULL,
  description TEXT,
  icon_emoji VARCHAR(10),
  creator_user_id UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMP DEFAULT NOW(),
  updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE group_memberships (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role VARCHAR(20) NOT NULL, -- 'creator', 'admin', 'member'
  joined_at TIMESTAMP DEFAULT NOW(),
  UNIQUE(group_id, user_id)
);

CREATE INDEX idx_memberships_group ON group_memberships(group_id);
CREATE INDEX idx_memberships_user ON group_memberships(user_id);
```

### 4.2 Group Roles

- **Creator**: Original group creator, permanent admin, can delete group
- **Admin**: Can add/remove members, manage goals, promote/demote admins
- **Member**: Can track progress, view group data

### 4.3 Create Group Flow

**API Call:**
```json
POST /api/groups
Authorization: Bearer {jwt-token}

{
  "name": "Morning Runners",
  "description": "Daily accountability for morning runs",
  "icon_emoji": "🏃",
  "initial_goals": [
    {
      "title": "30 min run",
      "cadence": "daily",
      "type": "binary"
    }
  ]
}

Response:
{
  "group": {
    "id": "group-uuid",
    "name": "Morning Runners",
    "creator_user_id": "user-uuid",
    "member_count": 1,
    "created_at": "2026-01-16T10:00:00Z"
  }
}
```

**Server Actions:**
1. Validate user is authenticated
2. Create group in database
3. Add creator as member with role='creator'
4. Create initial goals if provided
5. Return group data

### 4.4 Invite Members

**Invite Code Generation:**
```json
POST /api/groups/{group_id}/invites
Authorization: Bearer {jwt-token}

{
  "expires_at": "2026-01-23T10:00:00Z", // optional
  "max_uses": 10 // optional
}

Response:
{
  "invite_code": "PURSUE-ABC123-XYZ789",
  "qr_code_data": "https://getpursue.app/invite/PURSUE-ABC123-XYZ789",
  "expires_at": "2026-01-23T10:00:00Z"
}
```

**Join via Invite Code:**
```json
POST /api/groups/join
Authorization: Bearer {jwt-token}

{
  "invite_code": "PURSUE-ABC123-XYZ789"
}

Response:
{
  "group": {
    "id": "group-uuid",
    "name": "Morning Runners",
    "member_count": 8
  },
  "pending_approval": false // true if admin approval required
}
```

**Server Logic:**
1. Validate invite code exists and not expired
2. Check max uses not exceeded
3. Add user to group_memberships with role='member'
4. Send FCM push to all group members: "Shannon joined the group"
5. Return group data

### 4.5 Group Administration

**Rename Group:**
```json
PATCH /api/groups/{group_id}
{
  "name": "Dawn Warriors Running Club"
}
```

**Remove Member (Admin only):**
```json
DELETE /api/groups/{group_id}/members/{user_id}
```

**Promote to Admin (Creator/Admin only):**
```json
PATCH /api/groups/{group_id}/members/{user_id}
{
  "role": "admin"
}
```

**Leave Group:**
```json
DELETE /api/groups/{group_id}/members/me
```

### 4.6 Group Activity Feed

**Server stores activity events:**
```sql
CREATE TABLE group_activities (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  activity_type VARCHAR(50) NOT NULL,
  metadata JSONB,
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_activities_group ON group_activities(group_id, created_at DESC);
```

**Activity Types:**
- `member_joined`
- `member_left`
- `member_promoted`
- `member_removed`
- `group_renamed`
- `goal_added`
- `goal_completed` (daily summary, not per-entry)

**Fetch Activity:**
```json
GET /api/groups/{group_id}/activity?limit=50&offset=0
```

---

## 5. Goal Tracking

### 5.1 Goal Structure

**Database Schema:**
```sql
CREATE TABLE goals (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  cadence VARCHAR(20) NOT NULL, -- 'daily', 'weekly', 'monthly', 'yearly'
  metric_type VARCHAR(20) NOT NULL, -- 'binary', 'numeric', 'duration'
  target_value DECIMAL(10,2), -- for numeric goals
  unit VARCHAR(50), -- e.g., 'km', 'pages', 'minutes'
  created_by_user_id UUID REFERENCES users(id),
  created_at TIMESTAMP DEFAULT NOW(),
  archived_at TIMESTAMP -- NULL if active
);

CREATE INDEX idx_goals_group ON goals(group_id);
CREATE INDEX idx_goals_active ON goals(group_id) WHERE archived_at IS NULL;
```

### 5.2 Progress Tracking

**Database Schema:**
```sql
CREATE TABLE progress_entries (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  goal_id UUID NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  value DECIMAL(10,2) NOT NULL, -- 1 for binary true, 0 for binary false
  note TEXT,
  logged_at TIMESTAMP DEFAULT NOW(),
  period_start DATE NOT NULL, -- e.g., '2026-01-16' for daily goals
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_progress_goal_user ON progress_entries(goal_id, user_id, period_start);
CREATE INDEX idx_progress_user_recent ON progress_entries(user_id, logged_at DESC);
```

**Period Calculation:**
- Daily: `period_start` = date of logged_at
- Weekly: `period_start` = Monday of that week
- Monthly: `period_start` = first day of that month
- Yearly: `period_start` = January 1st of that year

### 5.3 Log Progress

**API Call:**
```json
POST /api/progress
Authorization: Bearer {jwt-token}

{
  "goal_id": "goal-uuid",
  "value": 1, // or numeric value for numeric goals
  "note": "Great run in the park!",
  "logged_at": "2026-01-16T07:30:00Z" // optional, defaults to now
}

Response:
{
  "progress_entry": {
    "id": "entry-uuid",
    "goal_id": "goal-uuid",
    "user_id": "user-uuid",
    "value": 1,
    "period_start": "2026-01-16",
    "logged_at": "2026-01-16T07:30:00Z"
  }
}
```

**Server Actions:**
1. Validate user is member of goal's group
2. Calculate period_start based on goal cadence
3. Insert into progress_entries
4. Send FCM push to group members: "Shannon completed '30 min run'"
5. Return progress entry

### 5.4 Fetch Progress

**User's Progress for a Goal:**
```json
GET /api/goals/{goal_id}/progress/me?start_date=2026-01-01&end_date=2026-01-31
```

**All Users' Progress for a Goal:**
```json
GET /api/goals/{goal_id}/progress?start_date=2026-01-01&end_date=2026-01-31
```

**User's Progress Across All Goals:**
```json
GET /api/users/me/progress?start_date=2026-01-16&end_date=2026-01-16
```

---

## 6. Real-Time Updates

### 6.1 Push Notifications (FCM)

**When to Send Push:**
- New progress entry logged
- New member joins group
- Group renamed
- New goal added
- Member promoted/removed

**FCM Payload:**
```json
{
  "notification": {
    "title": "Morning Runners",
    "body": "Shannon completed '30 min run'"
  },
  "data": {
    "type": "progress_logged",
    "group_id": "group-uuid",
    "goal_id": "goal-uuid",
    "user_id": "user-uuid"
  }
}
```

**Client Handling:**
1. Receive FCM push
2. Wake app (or show notification if app closed)
3. Refresh affected data from server:
   - `GET /api/goals/{goal_id}/progress`
   - Update UI

### 6.2 FCM Token Management

**Register Device:**
```json
POST /api/devices/register
Authorization: Bearer {jwt-token}

{
  "fcm_token": "fcm-device-token",
  "device_name": "iPhone 15 Pro",
  "platform": "android"
}
```

**Server Schema:**
```sql
CREATE TABLE devices (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  fcm_token VARCHAR(256) UNIQUE NOT NULL,
  device_name VARCHAR(128),
  platform VARCHAR(20),
  last_active TIMESTAMP DEFAULT NOW(),
  created_at TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_devices_user ON devices(user_id);
```

### 6.3 Polling Fallback (Optional)

For users who disable notifications:
```json
GET /api/users/me/updates?since=2026-01-16T10:00:00Z
```

Returns all relevant updates since timestamp.

---

## 7. Data Storage & Caching

### 7.1 Server Database (PostgreSQL)

**Tables:**
- `users` - User accounts
- `devices` - FCM tokens per device
- `groups` - Group metadata
- `group_memberships` - User-group relationships
- `goals` - Goal definitions
- `progress_entries` - All progress data
- `group_activities` - Activity feed events
- `invite_codes` - Active invite codes

**Indexes:**
- All foreign keys
- Composite indexes for common queries (e.g., group_id + user_id)
- Partial indexes for active goals (WHERE archived_at IS NULL)

**Retention:**
- No automatic deletion (users own their data)
- Optional: Archive old progress after 2 years (soft delete)

### 7.2 Client Database (SQLite)

**Purpose:** Cache only, not source of truth

**Tables:**
- `cached_groups` - Groups user belongs to
- `cached_goals` - Goals from user's groups
- `cached_progress` - Recent progress entries
- `pending_uploads` - Queued updates when offline

**Cache Invalidation:**
- On FCM push: Invalidate affected data, refetch from server
- On app open: Check for updates via `/api/users/me/updates`
- Periodically: Clear cache older than 30 days

### 7.3 Offline Support

**Read Operations (Offline):**
- Show cached data from SQLite
- Display "Last updated: 2 hours ago" indicator

**Write Operations (Offline):**
1. User logs progress
2. Insert into `pending_uploads` table
3. Show as "Pending upload" in UI
4. When network returns:
   - Upload to server via `POST /api/progress`
   - Remove from `pending_uploads`
   - Fetch latest data to ensure consistency

---

## 8. Security Architecture

Security is a core design principle, not an afterthought. This architecture implements defense-in-depth with multiple layers of protection.

### 8.1 Security Design Principles

**Defense in Depth:**
- Multiple independent security layers
- Failure of one layer doesn't compromise system
- Backend validation even with client-side checks

**Principle of Least Privilege:**
- Users access only their groups
- Database users have minimal permissions
- API tokens scoped to specific operations

**Secure by Default:**
- HTTPS enforced, no HTTP fallback
- Authentication required by default
- Encryption at rest and in transit

### 8.2 Authentication & Authorization

**JWT Token Architecture:**
```
Access Token (1 hour):
{
  "user_id": "uuid",
  "email": "user@example.com",
  "iat": 1705334400,
  "exp": 1705338000,
  "type": "access"
}

Refresh Token (30 days):
{
  "user_id": "uuid",
  "iat": 1705334400,
  "exp": 1707926400,
  "type": "refresh"
}
```

**Token Security:**
- Access tokens short-lived (1 hour) - limits damage if stolen
- Refresh tokens long-lived (30 days) - reduces re-authentication
- Separate secrets for access and refresh (32+ characters each)
- Refresh token rotation on each use
- Tokens stored encrypted on client (EncryptedSharedPreferences)
- Automatic token refresh on 401 errors

**Authorization Rules:**
- User can only access groups they're a member of
- Only admins can add/remove members, manage goals
- Only creator can delete group or demote other admins
- Users can only log progress for themselves

**Server Enforcement:**
```typescript
async function requireGroupMember(req, res, next) {
  const groupId = req.params.group_id;
  const userId = req.user.id; // from JWT
  
  const membership = await db
    .selectFrom('group_members')
    .select('role')
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .executeTakeFirst();
  
  if (!membership) {
    logger.warn('Unauthorized access attempt', {
      user_id: userId,
      group_id: groupId
    });
    return res.status(403).json({ error: 'Not authorized' });
  }
  
  next();
}
```

### 8.3 Input Validation & Network Security

**Validation Strategy:**
- Client-side: Email regex, password complexity, length limits
- Server-side: Zod schemas for ALL endpoints, parameterized queries

**Network Security:**
- TLS 1.3 only, HTTPS enforced (HSTS headers)
- Certificate pinning on mobile app
- CORS restricted to known origins
- Security headers (Helmet): CSP, X-Frame-Options, etc.

**Rate Limiting:**
- General API: 100 requests/minute per user
- Authentication: 5 attempts/15 minutes
- File uploads: 10 uploads/15 minutes

**File Upload Security:**
- Max size: 5 MB
- Allowed types: JPEG, PNG, WebP only
- Content validation with sharp library
- Metadata stripped (EXIF removed)
- Resize to 256x256, convert to WebP

### 8.4 Data Privacy & GDPR

**What Server Can See:**
- All user data (names, emails, group memberships)
- All goals and progress entries
- All activity (when users log progress, etc.)

**Privacy Protections:**
- HTTPS only (TLS 1.3)
- Database encrypted at rest (default Cloud SQL)
- JWT tokens stored in EncryptedSharedPreferences
- No third-party analytics in initial release
- No ads, no data selling
- Clear privacy policy and data retention policies

**Future Privacy Mode (Group-Level E2E Encryption):**
- Groups can opt into "Privacy Mode"
- Group seed phrase derives encryption keys
- Goal titles, progress notes encrypted client-side
- Server stores encrypted blobs, cannot read content
- Premium feature for privacy-conscious groups

### 8.4 GDPR Compliance

**User Rights:**
- **Right to Access**: `GET /api/users/me/data` - export all data as JSON
- **Right to Deletion**: `DELETE /api/users/me` - delete account and all data
- **Right to Portability**: Export data in JSON format

**Data Retention:**
- Active accounts: Retained while account active
- Deleted accounts: 30-day grace period, then permanent deletion
- Server logs: 90 days maximum
- Backups: 7-day point-in-time recovery
- Audit logs: 1 year for security incidents

**Consent Management:**
- Clear privacy policy during registration
- Opt-in for push notifications
- Opt-in for email communications

### 8.5 Security Monitoring & Incident Response

**Security Monitoring:**
- Failed login attempts: >10 per minute triggers alert
- Unusual API patterns: >1000 requests/minute from single IP
- Authorization failures: >50 per hour from single user
- Account lockout after 5 failed login attempts

**Audit Logging:**
- User authentication events (login, logout, token refresh)
- Account changes (password reset, email change, deletion)
- Permission changes (role updates, group membership)
- Failed authorization attempts

**Incident Response:**
1. **Detection**: Automated alerts, user reports
2. **Containment**: Lock affected accounts, revoke tokens
3. **Investigation**: Audit log review, scope assessment
4. **Remediation**: Patch vulnerability, reset credentials
5. **Communication**: Notify affected users within 72 hours
6. **Post-Mortem**: Document and improve

### 8.6 Security Checklist

**Backend:**
- [ ] All endpoints require authentication
- [ ] All endpoints validate authorization
- [ ] All inputs validated with Zod schemas
- [ ] Passwords hashed with bcrypt (10+ rounds)
- [ ] JWT secrets 32+ characters
- [ ] HTTPS enforced with HSTS
- [ ] Rate limiting on all endpoints
- [ ] File uploads validated
- [ ] Sensitive data never logged

**Mobile App:**
- [ ] Tokens in EncryptedSharedPreferences
- [ ] Certificate pinning implemented
- [ ] HTTPS only (no cleartext)
- [ ] ProGuard enabled for release
- [ ] No hardcoded secrets

**Infrastructure:**
- [ ] Database least-privilege users
- [ ] Secrets in Secret Manager
- [ ] VPC connector for private DB access
- [ ] Automated security updates

### 8.7 Resource Limits

To prevent abuse and ensure system stability:

**Per-User Limits:**
- Groups created: 10 maximum
- Groups joined: 10 maximum
- Progress entries: 200 per day

**Per-Group Limits:**
- Goals: 100 maximum
- Members: 50 maximum

**Rationale:**
- Prevents database flooding
- Ensures reasonable resource usage
- Protects against automated abuse
- Generous enough for legitimate power users

**User Experience:**
- Clear error messages when limits hit
- Suggestions for what to do (archive old groups)
- UI disables create buttons when at limit

---

## 9. API Reference

### 9.1 Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/auth/register` | Create account with email/password |
| POST | `/api/auth/login` | Sign in with email/password |
| POST | `/api/auth/google` | Sign up or sign in with Google |
| POST | `/api/auth/refresh` | Refresh access token |
| POST | `/api/auth/forgot-password` | Send password reset email |
| POST | `/api/auth/reset-password` | Reset password with token |
| POST | `/api/auth/logout` | Invalidate refresh token |
| POST | `/api/auth/link/google` | Link Google to existing account |
| DELETE | `/api/auth/unlink/{provider}` | Unlink auth provider |
| GET | `/api/users/me/providers` | List linked auth providers |

### 9.2 Users

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/users/me` | Get current user profile |
| PATCH | `/api/users/me` | Update profile |
| GET | `/api/users/me/groups` | Get user's groups |
| GET | `/api/users/me/progress` | Get user's progress |
| DELETE | `/api/users/me` | Delete account |

### 9.3 Groups

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/groups` | Create group |
| GET | `/api/groups/{id}` | Get group details |
| PATCH | `/api/groups/{id}` | Update group (admin) |
| DELETE | `/api/groups/{id}` | Delete group (creator) |
| GET | `/api/groups/{id}/members` | List members |
| DELETE | `/api/groups/{id}/members/{user_id}` | Remove member (admin) |
| POST | `/api/groups/{id}/invites` | Create invite code (admin) |
| POST | `/api/groups/join` | Join via invite code |
| GET | `/api/groups/{id}/activity` | Get activity feed |

### 9.4 Goals

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/groups/{group_id}/goals` | Create goal (admin) |
| GET | `/api/goals/{id}` | Get goal details |
| PATCH | `/api/goals/{id}` | Update goal (admin) |
| DELETE | `/api/goals/{id}` | Archive goal (admin) |
| GET | `/api/goals/{id}/progress` | Get all users' progress |
| GET | `/api/goals/{id}/progress/me` | Get my progress |

### 9.5 Progress

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/progress` | Log progress |
| GET | `/api/progress/{id}` | Get specific entry |
| DELETE | `/api/progress/{id}` | Delete entry (own only) |

### 9.6 Devices

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/devices/register` | Register FCM token |
| GET | `/api/devices` | List user's devices |
| DELETE | `/api/devices/{id}` | Unregister device |

---

## 10. Deployment

### 10.1 Backend Server (Google Cloud Run)

**Production Configuration:**
- Runtime: Node.js 20.x
- Memory: 1 GB (handles image processing efficiently)
- CPU: 2 vCPU (responsive under load)
- Concurrency: 80 requests per instance
- Min instances: 1 (eliminates cold starts, ensures availability)
- Max instances: 100 (handles traffic spikes gracefully)
- Timeout: 60 seconds
- Health checks: Enabled with /health and /ready endpoints
- VPC connector: Private communication with Cloud SQL

**Environment Variables:**
```
DATABASE_URL=postgresql://...
JWT_SECRET=...
JWT_REFRESH_SECRET=...
FCM_PROJECT_ID=pursue-app
GOOGLE_CLIENT_ID=123456789-abcdefg.apps.googleusercontent.com
NODE_ENV=production
```

**Dependencies (package.json):**
```json
{
  "dependencies": {
    "express": "^4.18.0",
    "pg": "^8.11.0",
    "bcrypt": "^5.1.0",
    "jsonwebtoken": "^9.0.0",
    "firebase-admin": "^12.0.0",
    "google-auth-library": "^9.0.0",
    "cors": "^2.8.5",
    "helmet": "^7.1.0"
  }
}
```

**Google OAuth Setup:**
1. Go to [Google Cloud Console](https://console.cloud.google.com)
2. Create project or select existing
3. Enable "Google+ API"
4. Create OAuth 2.0 credentials
5. Add authorized origins: `https://pursue-app.com`
6. Add authorized redirect URIs (for web): `https://pursue-app.com/auth/google/callback`
7. Copy Client ID → Add to environment variables
8. For Android: Add SHA-1 fingerprint of signing certificate

**Cost Estimate:**
- 1,000 users: ~$5-10/month
- 10,000 users: ~$20-40/month
- 100,000 users: ~$100-200/month

### 10.2 Database (Cloud SQL PostgreSQL)

**Production Configuration:**
- Version: PostgreSQL 17.x (latest stable)
- Tier: db-g1-small (shared CPU, 1.7 GB RAM, good for initial production load)
- Storage: 20 GB SSD (auto-expand enabled)
- Backups: Daily automated at 3 AM UTC (7-day retention)
- High Availability: Enabled (automatic failover < 60s)
- Point-in-time Recovery: Enabled (recover to any point in last 7 days)
- Connection Pooling: Built-in with Cloud SQL Proxy

**Cost Estimate:**
- db-g1-small: ~$35/month
- 20 GB storage: ~$3/month
- Backups: ~$2/month
- High Availability: +100% (~$40/month additional)
- **Total: ~$80/month** for production-grade database

**Scaling Path:**
- 1K-10K users: db-g1-small ($35/month base + HA)
- 10K-50K users: db-n1-standard-1 ($85/month base + HA)
- 50K-100K users: db-n1-standard-2 ($170/month base + HA)
- 100K+ users: Consider read replicas for query offloading

### 10.3 Total Infrastructure Cost

**Initial Production (1,000 users):**
- Cloud Run: $10-20/month (min instances: 1)
- Cloud SQL: $80/month (with HA)
- **Total: $90-100/month**

**Growth Phase (10,000 users):**
- Cloud Run: $30-60/month
- Cloud SQL: $120-150/month (upgraded tier)
- **Total: $150-210/month**

**Scale Phase (100,000 users):**
- Cloud Run: $150-300/month
- Cloud SQL: $250-400/month (with read replicas)
- **Total: $400-700/month**

**Notes:**
- Costs include high availability and production-grade configuration
- Initial investment in HA prevents expensive downtime incidents
- Cloud Run scales to zero when idle (cost savings during off-peak)
- Database is the primary cost driver; scales with user growth

---

## 11. Development & Launch Phases

### Phase 1: Core Backend + Auth (3-4 weeks)
- PostgreSQL 17 schema with proper indexes
- User registration/login with email verification
- JWT authentication with refresh tokens
- Google OAuth integration
- Token refresh handling (automatic 401 recovery)
- Health check and monitoring setup
- Basic API endpoints (users, groups)

### Phase 2: Groups & Goals (3-4 weeks)
- Group CRUD operations with authorization
- Invite codes and member management
- Goals CRUD with validation
- Progress tracking API with date handling
- FCM integration and device registration
- Image upload (avatars, group icons)
- Rate limiting and security hardening

### Phase 3: Android App - Core UI (4-5 weeks)
- Authentication screens (email + Google)
- Home/Groups list with 5-state pattern
- Group detail (goals, members, activity)
- Log progress with offline queue
- Local caching (Room database)
- Token refresh handling
- Image loading with Glide

### Phase 4: Real-Time & Polish (3-4 weeks)
- FCM push notifications (progress updates, invites)
- Offline queue with sync
- Activity feed with pagination
- Profile settings and avatar upload
- Password management and account deletion
- Error handling and retry logic
- Accessibility improvements

### Phase 5: Testing & Production Prep (3-4 weeks)
- Comprehensive testing (unit, integration, E2E)
- Performance optimization and profiling
- Security audit and penetration testing
- Load testing (1K, 10K, 100K users)
- Monitoring and alerting setup
- Documentation and runbooks
- Beta testing with real users

**Total Development Timeline: 4-5 months**

This timeline builds a production-ready system from day one, avoiding technical debt that would require expensive rewrites later.

---

## 12. Future Enhancements

### 12.1 Private Groups (End-to-End Encryption)

**Potential Premium Feature - Group-Level Encryption**

For privacy-conscious users who want additional data protection beyond server-side encryption:

**Architecture:**
- Group creator enables "Privacy Mode" when creating group
- Generate 12-word seed phrase for the GROUP (not user)
- Seed phrase derives AES-256 key for encrypting group data
- Goal titles, descriptions, progress notes encrypted client-side
- Server stores encrypted blobs, cannot read content
- Group members receive encrypted group key (decrypted with their password)
- Any member with seed phrase can recover group access

**Benefits:**
- Clear purpose: "This phrase protects your group's data"
- Social trust model: Members share seed phrase with trusted group members
- Opt-in: Only privacy-focused groups pay the complexity cost
- Revenue opportunity: Premium feature ($2-5/month)
- Better than user-level: Groups share data anyway, group-level encryption makes sense

**Considerations:**
- Requires careful UX design to avoid data loss
- Reduces server-side analytics and recommendations
- May limit features like server-side search
- Consider if market demand justifies development cost

**Database Schema (If Implemented):**
```sql
ALTER TABLE groups ADD COLUMN encryption_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE groups ADD COLUMN encrypted_symmetric_key TEXT;
ALTER TABLE groups ADD COLUMN seed_phrase_verification TEXT;

CREATE TABLE group_member_keys (
  id UUID PRIMARY KEY,
  group_id UUID REFERENCES groups(id),
  user_id UUID REFERENCES users(id),
  encrypted_group_key TEXT NOT NULL,
  has_seed_phrase BOOLEAN DEFAULT FALSE
);
```

**UI Flow:**
- Create group → Toggle "Private Group (Encrypted)"
- Show seed phrase backup screen
- Share seed phrase with trusted members out-of-band
- Premium badge on encrypted groups

### 12.2 Additional Feature Ideas

- [ ] Goal templates library with curated presets
- [ ] Advanced progress charts and trend analytics
- [ ] Achievement system with streaks and badges
- [ ] Automated weekly/monthly summary emails
- [ ] Web app (view-only or full functionality)
- [ ] iOS app
- [ ] Integrations (fitness trackers, calendar)
- [ ] Public/discoverable groups
- [ ] Comments on progress entries
- [ ] Photo attachments

---

## 13. Success Metrics

- **Performance**: API response time <200ms (p95)
- **Reliability**: 99.9% uptime
- **Engagement**: >70% of users log progress daily
- **Retention**: >60% DAU/MAU ratio
- **Growth**: Viral coefficient >1.0 (each user invites >1 friend)

---

## 14. Open Questions

- [ ] Should we allow editing progress entries after logging? A: Yes
- [ ] How to handle time zones for daily goals? 
- [ ] Should group creator be able to transfer ownership? A: Automatic when they leave the group
- [ ] Rate limit for progress logging (prevent spam)? A: 50 requests per minute per user
- [ ] Maximum group size? A: 50 members
- [ ] Should we support nested/hierarchical goals? A: No
- [ ] Email notifications in addition to push? A: No

---

**End of Main Specification**
