# Pursue Backend Server Specification

**Version:** 1.0 (Centralized Architecture)  
**Last Updated:** January 16, 2026  
**Status:** Implementation Ready  
**Platform:** Node.js 20.x + TypeScript + Express.js

---

## 1. Overview

### 1.1 Purpose
This document provides complete implementation specifications for the Pursue backend server. It covers API endpoints, database schema, authentication, authorization, business logic, deployment configuration, and operational procedures.

**Design Philosophy:** Build a production-grade, scalable system from day one. This specification prioritizes:
- **High Availability**: Zero-downtime deployments, health checks, graceful shutdowns
- **Scalability**: Horizontal scaling, efficient queries, connection pooling
- **Resilience**: Error handling, circuit breakers, rate limiting, retry logic
- **Performance**: Query optimization, caching strategies, N+1 prevention
- **Observability**: Structured logging, metrics, monitoring, alerting
- **Security**: JWT authentication, input validation, SQL injection prevention

### 1.2 Architecture Summary

```
┌─────────────────────────────────────────────────┐
│           Google Cloud Run Container            │
│  ┌───────────────────────────────────────────┐  │
│  │  Express.js Application (TypeScript)      │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │  Routes & Controllers               │  │  │
│  │  │  - /api/auth/*                      │  │  │
│  │  │  - /api/users/*                     │  │  │
│  │  │  - /api/groups/*                    │  │  │
│  │  │  - /api/goals/*                     │  │  │
│  │  │  - /api/progress/*                  │  │  │
│  │  │  - /api/devices/*                   │  │  │
│  │  └─────────────────────────────────────┘  │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │  Middleware                         │  │  │
│  │  │  - Authentication (JWT)             │  │  │
│  │  │  - Authorization                    │  │  │
│  │  │  - Rate Limiting                    │  │  │
│  │  │  - Error Handling                   │  │  │
│  │  │  - Request Validation               │  │  │
│  │  └─────────────────────────────────────┘  │  │
│  │  ┌─────────────────────────────────────┐  │  │
│  │  │  Services                           │  │  │
│  │  │  - Database (PostgreSQL)            │  │  │
│  │  │  - Firebase Admin (FCM)             │  │  │
│  │  │  - Google OAuth                     │  │  │
│  │  │  - Email (SendGrid/Resend)          │  │  │
│  │  └─────────────────────────────────────┘  │  │
│  └───────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
                      │
                      ├──→ Cloud SQL (PostgreSQL)
                      ├──→ Firebase (FCM)
                      ├──→ Google OAuth
                      └──→ Email Service
```

### 1.3 Technology Stack

- **Runtime**: Node.js 20.x LTS
- **Language**: TypeScript 5.x
- **Framework**: Express.js 4.x
- **Database**: PostgreSQL 17.x (Cloud SQL)
  - Latest stable release (Sept 2024)
  - 20-30% faster JSON/JSONB operations
  - Improved VACUUM performance for high-update tables
  - Better connection pooling (ideal for serverless)
  - Supported until November 2029
  - **BYTEA storage** for images (avatars, group icons)
- **ORM**: Kysely (type-safe query builder)
- **Authentication**: JWT (jsonwebtoken)
- **Password Hashing**: bcrypt
- **Validation**: Zod
- **Push Notifications**: Firebase Admin SDK
- **Email**: Resend or SendGrid
- **Google OAuth**: google-auth-library
- **Image Processing**: sharp (resize, crop, convert to WebP)
- **File Storage**: PostgreSQL BYTEA (initial implementation), with clear migration path to Cloudflare R2 for scale
- **Deployment**: Google Cloud Run
- **Logging**: Winston
- **Monitoring**: Google Cloud Monitoring

### 1.4 Scalability & Production Readiness

This system is designed to scale from initial users to hundreds of thousands while maintaining performance and availability.

#### **Horizontal Scalability**
- **Stateless Design**: All instances are identical, no session affinity required
- **Cloud Run Autoscaling**: 0 to 1000+ instances based on request volume
- **Connection Pooling**: Shared connection pool per instance (10 connections each)
- **Database Scaling**: Cloud SQL supports read replicas for query offloading

#### **Performance Optimizations**
- **Query Efficiency**: All queries use proper indexes, no N+1 problems
- **Pagination**: All list endpoints support limit/offset with total counts
- **Selective Loading**: Only load required fields (e.g., exclude BYTEA in lists)
- **Date Range Filters**: Progress queries limited to prevent unbounded scans
- **Image Processing**: Sharp library for fast WebP conversion (~50ms per image)

#### **High Availability**
- **Health Checks**: `/health` endpoint for load balancer probes
- **Graceful Shutdown**: Drain connections before terminating (30s timeout)
- **Zero-Downtime Deployments**: Rolling updates with readiness checks
- **Database Failover**: Cloud SQL automatic failover (< 60s)
- **Multi-Region**: Can deploy in multiple regions for geographic distribution

#### **Resilience**
- **Rate Limiting**: 100 req/min per user, 10 uploads per 15 min
- **Request Timeouts**: 30s max per request
- **Circuit Breakers**: Fail fast on external service failures (FCM, email)
- **Error Recovery**: Retry logic with exponential backoff for transient failures
- **Input Validation**: All requests validated with Zod schemas

#### **Monitoring & Observability**
- **Structured Logging**: JSON logs with request IDs, user IDs, latency
- **Metrics**: Request count, latency (p50/p95/p99), error rate, DB query time
- **Alerts**: Error rate > 1%, p95 latency > 1s, DB connections > 80%
- **Tracing**: Request flow tracking for debugging slow queries

#### **Capacity Planning**

**Expected Performance:**
- **10,000 daily active users**: ~5-10 requests/sec average, ~50 req/sec peak
- **100,000 daily active users**: ~50-100 req/sec average, ~500 req/sec peak
- **Database**: Single Cloud SQL instance handles 10K QPS with proper indexes
- **Storage**: 100K users × 50% avatar upload × 30 KB = 1.5 GB images

**Scaling Triggers:**
- **Scale out Cloud Run**: CPU > 60% or request latency > 500ms
- **Upgrade Cloud SQL**: Connection count > 80% or CPU > 70%
- **Add read replicas**: When read queries > 80% of total queries
- **Migrate images to R2**: When image storage > 50 GB (reduces DB load)

**Cost Efficiency:**
- **Cloud Run**: Pay only for actual usage, scales to zero when idle
- **Connection Pooling**: Minimizes Cloud SQL connection overhead
- **Image Optimization**: WebP compression reduces storage by 70%
- **Pagination**: Prevents expensive full-table scans

---

## 2. Database Schema

### 2.1 Complete PostgreSQL Schema

**PostgreSQL 17.x - Latest Stable Release**

Features used:
- UUID generation (uuid-ossp extension)
- TIMESTAMP WITH TIME ZONE (proper timezone handling)
- DATE type for user-local dates
- JSONB for flexible metadata storage (20-30% faster in PG 17)
- Partial indexes (WHERE clauses)
- Foreign key cascades

**Compatibility:**
- ✅ PostgreSQL 17.x (Recommended)
- ✅ PostgreSQL 16.x (Compatible)
- ⚠️ PostgreSQL 15.x (Compatible but missing performance improvements)
- ❌ PostgreSQL 14.x or older (Not recommended)

```sql
-- PostgreSQL 17.x
-- Enable UUID extension
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- Users table
CREATE TABLE users (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  email VARCHAR(255) UNIQUE NOT NULL,
  display_name VARCHAR(100) NOT NULL,
  avatar_data BYTEA,              -- Stored image data (WebP format, 256x256)
  avatar_mime_type VARCHAR(50),   -- e.g., 'image/webp', 'image/jpeg'
  password_hash VARCHAR(255),     -- NULL for Google-only users
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  deleted_at TIMESTAMP WITH TIME ZONE -- NULL if active, timestamp if soft deleted
);

CREATE INDEX idx_users_email ON users(email);

-- Auth providers table (email, google)
CREATE TABLE auth_providers (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider VARCHAR(50) NOT NULL, -- 'email', 'google'
  provider_user_id VARCHAR(255) NOT NULL, -- Google user ID
  provider_email VARCHAR(255), -- Email from provider
  linked_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  UNIQUE(provider, provider_user_id),
  UNIQUE(user_id, provider)
);

CREATE INDEX idx_auth_providers_user ON auth_providers(user_id);
CREATE INDEX idx_auth_providers_lookup ON auth_providers(provider, provider_user_id);

-- Refresh tokens
CREATE TABLE refresh_tokens (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash VARCHAR(255) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  revoked_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);

-- Password reset tokens
CREATE TABLE password_reset_tokens (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  token_hash VARCHAR(255) NOT NULL,
  expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  used_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX idx_password_reset_user ON password_reset_tokens(user_id);
CREATE INDEX idx_password_reset_token ON password_reset_tokens(token_hash);

-- Devices (for FCM push notifications)
CREATE TABLE devices (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  fcm_token VARCHAR(256) UNIQUE NOT NULL,
  device_name VARCHAR(128),
  platform VARCHAR(20), -- 'android', 'ios', 'web'
  last_active TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_devices_user ON devices(user_id);
CREATE INDEX idx_devices_fcm_token ON devices(fcm_token);

-- Groups
CREATE TABLE groups (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  name VARCHAR(100) NOT NULL,
  description TEXT,
  icon_emoji VARCHAR(10),          -- Optional emoji (e.g., "🏃", "📚")
  icon_color VARCHAR(7),            -- Hex color for emoji background (e.g., "#1976D2")
  icon_data BYTEA,                  -- Stored image data (WebP format, 256x256)
  icon_mime_type VARCHAR(50),       -- e.g., 'image/webp', 'image/jpeg'
  creator_user_id UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  updated_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_groups_creator ON groups(creator_user_id);

-- Group memberships
CREATE TABLE group_memberships (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role VARCHAR(20) NOT NULL, -- 'creator', 'admin', 'member'
  joined_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  UNIQUE(group_id, user_id)
);

CREATE INDEX idx_memberships_group ON group_memberships(group_id);
CREATE INDEX idx_memberships_user ON group_memberships(user_id);

-- Invite codes
CREATE TABLE invite_codes (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  code VARCHAR(50) UNIQUE NOT NULL,
  created_by_user_id UUID NOT NULL REFERENCES users(id),
  max_uses INTEGER, -- NULL = unlimited
  current_uses INTEGER DEFAULT 0,
  expires_at TIMESTAMP WITH TIME ZONE, -- NULL = never expires
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_invite_codes_group ON invite_codes(group_id);
CREATE INDEX idx_invite_codes_code ON invite_codes(code);

-- Goals
CREATE TABLE goals (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  cadence VARCHAR(20) NOT NULL, -- 'daily', 'weekly', 'monthly', 'yearly'
  metric_type VARCHAR(20) NOT NULL, -- 'binary', 'numeric', 'duration'
  target_value DECIMAL(10,2), -- For numeric goals
  unit VARCHAR(50), -- e.g., 'km', 'pages', 'minutes'
  created_by_user_id UUID REFERENCES users(id),
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
  deleted_at TIMESTAMP WITH TIME ZONE, -- Soft delete: NULL if active, timestamp if deleted
  deleted_by_user_id UUID REFERENCES users(id) -- Who deleted it (for audit)
);

CREATE INDEX idx_goals_group ON goals(group_id);
CREATE INDEX idx_goals_active ON goals(group_id) WHERE deleted_at IS NULL;

COMMENT ON COLUMN goals.deleted_at IS 'Soft delete timestamp. NULL = active, non-NULL = deleted. Preserves historical progress data and enables restoration.';

-- Progress entries
CREATE TABLE progress_entries (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  goal_id UUID NOT NULL REFERENCES goals(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  value DECIMAL(10,2) NOT NULL, -- 1 for binary true, 0 for false, numeric for others
  note TEXT,
  logged_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(), -- When user logged it (for feed sorting)
  period_start DATE NOT NULL, -- User's local date: '2026-01-17' (not UTC timestamp!)
  user_timezone VARCHAR(50), -- e.g., 'America/New_York' (for reference)
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_progress_goal_user ON progress_entries(goal_id, user_id, period_start);
CREATE INDEX idx_progress_user_recent ON progress_entries(user_id, logged_at DESC);
CREATE INDEX idx_progress_period ON progress_entries(period_start);

COMMENT ON COLUMN progress_entries.period_start IS 'User local date (DATE not TIMESTAMP). For daily goal, this is the user local day they completed it, e.g., 2026-01-17. Critical for timezone handling - a Friday workout at 11 PM EST should count for Friday, not Saturday UTC.';

-- Group activity feed
CREATE TABLE group_activities (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  user_id UUID REFERENCES users(id) ON DELETE SET NULL, -- NULL if user deleted
  activity_type VARCHAR(50) NOT NULL,
  metadata JSONB,
  created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

CREATE INDEX idx_activities_group ON group_activities(group_id, created_at DESC);
CREATE INDEX idx_activities_type ON group_activities(activity_type);

-- Triggers for updated_at timestamps
CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER update_users_updated_at BEFORE UPDATE ON users
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();

CREATE TRIGGER update_groups_updated_at BEFORE UPDATE ON groups
  FOR EACH ROW EXECUTE FUNCTION update_updated_at_column();
```

---

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
- User must be member of the group

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

**Errors:**
- 401: Unauthorized (invalid/missing token)
- 403: Forbidden (not a member of group)
- 404: Group not found

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
- User must be member of goal's group

**Errors:**
- 400: Invalid input
- 400: Duplicate entry (already logged for this period)
- 403: Not a member of goal's group
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

## 4. Authentication & Authorization

### 4.1 JWT Token Structure

**Access Token (1 hour expiry):**
```typescript
{
  user_id: "550e8400-e29b-41d4-a716-446655440000",
  email: "shannon@example.com",
  iat: 1705334400,
  exp: 1705338000
}
```

**Refresh Token (30 days expiry):**
```typescript
{
  user_id: "550e8400-e29b-41d4-a716-446655440000",
  token_id: "refresh-token-uuid",
  iat: 1705334400,
  exp: 1707926400
}
```

### 4.2 Authentication Middleware

```typescript
import { Request, Response, NextFunction } from 'express';
import jwt from 'jsonwebtoken';

export interface AuthRequest extends Request {
  user?: {
    id: string;
    email: string;
  };
}

export async function authenticate(
  req: AuthRequest,
  res: Response,
  next: NextFunction
) {
  try {
    const authHeader = req.headers.authorization;
    
    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      return res.status(401).json({ error: 'Missing or invalid authorization header' });
    }
    
    const token = authHeader.substring(7);
    
    const payload = jwt.verify(token, process.env.JWT_SECRET!) as {
      user_id: string;
      email: string;
    };
    
    req.user = {
      id: payload.user_id,
      email: payload.email
    };
    
    next();
  } catch (error) {
    return res.status(401).json({ error: 'Invalid or expired token' });
  }
}
```

### 4.3 Authorization Helpers

```typescript
// Check if user is member of group
export async function requireGroupMember(
  userId: string,
  groupId: string
): Promise<{ role: string } | null> {
  const membership = await db
    .selectFrom('group_memberships')
    .select(['role'])
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .executeTakeFirst();
    
  return membership || null;
}

// Check if user is admin or creator
export async function requireGroupAdmin(
  userId: string,
  groupId: string
): Promise<boolean> {
  const membership = await requireGroupMember(userId, groupId);
  return membership?.role === 'admin' || membership?.role === 'creator';
}

// Check if user is creator
export async function requireGroupCreator(
  userId: string,
  groupId: string
): Promise<boolean> {
  const membership = await requireGroupMember(userId, groupId);
  return membership?.role === 'creator';
}
```

---

## 5. Push Notifications (FCM)

### 5.1 Send Notification to Group

```typescript
import admin from 'firebase-admin';

export async function sendGroupNotification(
  groupId: string,
  notification: {
    title: string;
    body: string;
  },
  data: Record<string, string>
) {
  // Get all FCM tokens for group members
  const devices = await db
    .selectFrom('devices')
    .innerJoin('group_memberships', 'devices.user_id', 'group_memberships.user_id')
    .select(['devices.fcm_token'])
    .where('group_memberships.group_id', '=', groupId)
    .execute();
    
  const tokens = devices.map(d => d.fcm_token);
  
  if (tokens.length === 0) {
    return;
  }
  
  // Send multicast message
  const message = {
    notification,
    data,
    tokens
  };
  
  const response = await admin.messaging().sendMulticast(message);
  
  // Handle failed tokens (device uninstalled, invalid token)
  if (response.failureCount > 0) {
    const failedTokens: string[] = [];
    response.responses.forEach((resp, idx) => {
      if (!resp.success) {
        failedTokens.push(tokens[idx]);
      }
    });
    
    // Remove failed tokens from database
    if (failedTokens.length > 0) {
      await db
        .deleteFrom('devices')
        .where('fcm_token', 'in', failedTokens)
        .execute();
    }
  }
}
```

### 5.2 Notification Examples

**Progress Logged:**
```typescript
await sendGroupNotification(
  groupId,
  {
    title: "Morning Runners",
    body: "Shannon completed '30 min run'"
  },
  {
    type: "progress_logged",
    group_id: groupId,
    goal_id: goalId,
    user_id: userId
  }
);
```

**Member Joined:**
```typescript
await sendGroupNotification(
  groupId,
  {
    title: "Morning Runners",
    body: "Jamie joined the group"
  },
  {
    type: "member_joined",
    group_id: groupId,
    user_id: newUserId
  }
);
```

---

## 6. Error Handling

### 6.1 Error Response Format

All errors follow consistent format:

```json
{
  "error": {
    "message": "Human-readable error message",
    "code": "ERROR_CODE",
    "details": {} // Optional, validation errors, etc.
  }
}
```

### 6.2 HTTP Status Codes

| Code | Meaning | Usage |
|------|---------|-------|
| 200 | OK | Successful GET/PATCH/POST |
| 201 | Created | Successful POST (resource created) |
| 204 | No Content | Successful DELETE |
| 400 | Bad Request | Invalid input, validation errors |
| 401 | Unauthorized | Missing/invalid auth token |
| 403 | Forbidden | Authenticated but not authorized |
| 404 | Not Found | Resource doesn't exist |
| 409 | Conflict | Resource already exists, constraint violation |
| 429 | Too Many Requests | Rate limit exceeded |
| 500 | Internal Server Error | Unexpected server error |

### 6.3 Global Error Handler

```typescript
import { Request, Response, NextFunction } from 'express';
import { ZodError } from 'zod';

export function errorHandler(
  error: Error,
  req: Request,
  res: Response,
  next: NextFunction
) {
  console.error('Error:', error);
  
  // Zod validation errors
  if (error instanceof ZodError) {
    return res.status(400).json({
      error: {
        message: 'Validation error',
        code: 'VALIDATION_ERROR',
        details: error.errors
      }
    });
  }
  
  // Custom application errors
  if (error.name === 'ApplicationError') {
    return res.status((error as any).statusCode || 400).json({
      error: {
        message: error.message,
        code: (error as any).code
      }
    });
  }
  
  // Default: Internal server error
  return res.status(500).json({
    error: {
      message: 'Internal server error',
      code: 'INTERNAL_ERROR'
    }
  });
}
```

---

## 7. Rate Limiting

### 7.1 Rate Limit Configuration

```typescript
import rateLimit from 'express-rate-limit';

// General API rate limit
export const apiLimiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 100, // 100 requests per window
  message: {
    error: {
      message: 'Too many requests, please try again later',
      code: 'RATE_LIMIT_EXCEEDED'
    }
  }
});

// Auth endpoints (stricter)
export const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,
  max: 5, // 5 attempts per window
  skipSuccessfulRequests: true
});

// Password reset (very strict)
export const passwordResetLimiter = rateLimit({
  windowMs: 60 * 60 * 1000, // 1 hour
  max: 3 // 3 attempts per hour
});

// Progress logging (prevents spam/loops)
export const progressLimiter = rateLimit({
  windowMs: 60 * 1000, // 1 minute
  max: 60, // 60 requests per minute per user
  message: {
    error: {
      message: 'Too many progress entries. Please slow down.',
      code: 'PROGRESS_RATE_LIMIT'
    }
  },
  keyGenerator: (req) => {
    // Rate limit per user, not per IP
    return req.user?.id || req.ip;
  }
});
```

### 7.2 Apply Rate Limiters

```typescript
app.use('/api/auth/login', authLimiter);
app.use('/api/auth/register', authLimiter);
app.use('/api/auth/forgot-password', passwordResetLimiter);
app.use('/api/progress', progressLimiter); // Critical: prevents spam
app.use('/api', apiLimiter);
```

**Why Progress Rate Limiting:**
- Prevents infinite loops in mobile app code
- Stops accidental duplicate submissions
- Protects against malicious spam
- 60/minute = 1 per second, reasonable for manual entry

---

## 8. Deployment

### 8.1 Environment Variables

```bash
# Database
DATABASE_URL=postgresql://user:password@/dbname?host=/cloudsql/project:region:instance

# JWT
JWT_SECRET=your-super-secret-jwt-key-min-32-chars
JWT_REFRESH_SECRET=your-super-secret-refresh-key-min-32-chars

# Google OAuth
GOOGLE_CLIENT_ID=123456789-abcdefghijklmnop.apps.googleusercontent.com

# Firebase (FCM)
GOOGLE_APPLICATION_CREDENTIALS=/app/service-account.json
FCM_PROJECT_ID=pursue-app

# Email
RESEND_API_KEY=re_...
FROM_EMAIL=noreply@getpursue.app

# App
NODE_ENV=production
PORT=8080
FRONTEND_URL=https://getpursue.app
```

### 8.2 Dockerfile

```dockerfile
FROM node:20-alpine

WORKDIR /app

# Install dependencies
COPY package*.json ./
RUN npm ci --only=production

# Copy source
COPY . .

# Build TypeScript
RUN npm run build

# Expose port
EXPOSE 8080

# Start server
CMD ["node", "dist/server.js"]
```

### 8.3 Cloud Run Configuration (Production-Grade)

**cloudbuild.yaml:**
```yaml
steps:
  # Build Docker image
  - name: 'gcr.io/cloud-builders/docker'
    args: ['build', '-t', 'gcr.io/$PROJECT_ID/pursue-backend', '.']
  
  # Push to Container Registry
  - name: 'gcr.io/cloud-builders/docker'
    args: ['push', 'gcr.io/$PROJECT_ID/pursue-backend']
  
  # Deploy to Cloud Run with zero-downtime rollout
  - name: 'gcr.io/google.com/cloudsdktool/cloud-sdk'
    entrypoint: gcloud
    args:
      - 'run'
      - 'deploy'
      - 'pursue-backend'
      - '--image'
      - 'gcr.io/$PROJECT_ID/pursue-backend'
      - '--region'
      - 'us-central1'
      - '--platform'
      - 'managed'
      - '--allow-unauthenticated'
      - '--no-traffic'  # Deploy new revision without traffic for testing

  # Gradually migrate traffic (canary deployment)
  - name: 'gcr.io/google.com/cloudsdktool/cloud-sdk'
    entrypoint: gcloud
    args:
      - 'run'
      - 'services'
      - 'update-traffic'
      - 'pursue-backend'
      - '--to-latest'
      - '--region'
      - 'us-central1'

images:
  - 'gcr.io/$PROJECT_ID/pursue-backend'
```

**Production Deploy Command:**
```bash
gcloud run deploy pursue-backend \
  --image gcr.io/PROJECT_ID/pursue-backend \
  --region us-central1 \
  --platform managed \
  --allow-unauthenticated \
  \
  # Resource allocation (production-sized)
  --memory 1Gi \
  --cpu 2 \
  --timeout 60 \
  \
  # Autoscaling (handles traffic spikes)
  --concurrency 80 \
  --min-instances 1 \
  --max-instances 100 \
  \
  # High availability
  --cpu-throttling \
  --no-cpu-boost \
  \
  # Health checks
  --startup-cpu-boost \
  --startup-probe-initial-delay 10 \
  --startup-probe-timeout 5 \
  --startup-probe-period 3 \
  --startup-probe-failure-threshold 3 \
  \
  # Environment & secrets
  --set-env-vars NODE_ENV=production \
  --set-secrets DATABASE_URL=database-url:latest,JWT_SECRET=jwt-secret:latest,JWT_REFRESH_SECRET=jwt-refresh-secret:latest \
  \
  # VPC connector for Cloud SQL
  --vpc-connector pursue-vpc-connector \
  --vpc-egress private-ranges-only
```

**Key Production Settings:**
- **min-instances 1**: Keep warm instance for faster response times (eliminates cold starts)
- **max-instances 100**: Can handle sudden traffic spikes
- **memory 1Gi, cpu 2**: Sufficient for image processing and database queries
- **Health checks**: Automatic restarts on unhealthy instances
- **VPC connector**: Private communication with Cloud SQL
- **Secrets**: JWT keys stored in Secret Manager, not env vars

---

## 9. Database Migrations

### 9.1 Migration Tool

Use **node-pg-migrate** for database migrations.

**Install:**
```bash
npm install node-pg-migrate
```

**Create Migration:**
```bash
npx node-pg-migrate create initial-schema
```

**Migration File (migrations/1705334400000_initial-schema.js):**
```javascript
exports.up = (pgm) => {
  // Create users table
  pgm.createTable('users', {
    id: {
      type: 'uuid',
      primaryKey: true,
      default: pgm.func('uuid_generate_v4()')
    },
    email: {
      type: 'varchar(255)',
      notNull: true,
      unique: true
    },
    display_name: {
      type: 'varchar(100)',
      notNull: true
    },
    avatar_data: 'bytea',         // Stored image data (WebP, 256x256)
    avatar_mime_type: 'varchar(50)',  // 'image/webp', 'image/jpeg'
    password_hash: 'varchar(255)',
    created_at: {
      type: 'timestamp with time zone',
      notNull: true,
      default: pgm.func('NOW()')
    },
    updated_at: {
      type: 'timestamp with time zone',
      notNull: true,
      default: pgm.func('NOW()')
    }
  });
  
  // Add more tables...
};

exports.down = (pgm) => {
  pgm.dropTable('users');
  // Drop other tables...
};
```

**Run Migrations:**
```bash
DATABASE_URL=postgresql://... npx node-pg-migrate up
```

---

## 10. Testing

### 10.1 Test Structure

```
tests/
├── unit/
│   ├── auth.test.ts
│   ├── groups.test.ts
│   └── goals.test.ts
├── integration/
│   ├── api/
│   │   ├── auth.test.ts
│   │   ├── groups.test.ts
│   │   └── goals.test.ts
│   └── database/
│       └── queries.test.ts
└── e2e/
    └── user-flows.test.ts
```

### 10.2 Example Integration Test

```typescript
import request from 'supertest';
import { app } from '../src/app';
import { db } from '../src/database';

describe('POST /api/auth/register', () => {
  afterAll(async () => {
    await db.destroy();
  });
  
  it('should create new user', async () => {
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'test@example.com',
        password: 'securePassword123',
        display_name: 'Test User'
      });
      
    expect(response.status).toBe(201);
    expect(response.body).toHaveProperty('access_token');
    expect(response.body).toHaveProperty('refresh_token');
    expect(response.body.user.email).toBe('test@example.com');
  });
  
  it('should reject duplicate email', async () => {
    // Register first user
    await request(app)
      .post('/api/auth/register')
      .send({
        email: 'duplicate@example.com',
        password: 'password123',
        display_name: 'User One'
      });
      
    // Try to register with same email
    const response = await request(app)
      .post('/api/auth/register')
      .send({
        email: 'duplicate@example.com',
        password: 'password456',
        display_name: 'User Two'
      });
      
    expect(response.status).toBe(409);
  });
});
```

---

## 11. Monitoring & Logging (Production-Grade Observability)

### 11.1 Structured Logging with Winston

```typescript
import winston from 'winston';

export const logger = winston.createLogger({
  level: process.env.LOG_LEVEL || 'info',
  format: winston.format.combine(
    winston.format.timestamp(),
    winston.format.errors({ stack: true }),
    winston.format.json()
  ),
  transports: [
    new winston.transports.Console()
  ]
});

// Usage with structured data
logger.info('User registered', {
  user_id: userId,
  email: email,
  method: 'email',
  duration_ms: Date.now() - startTime
});

logger.error('Database error', {
  error: error.message,
  stack: error.stack,
  query: queryName,
  user_id: userId
});

logger.warn('Rate limit exceeded', {
  user_id: userId,
  endpoint: req.path,
  ip: req.ip
});
```

### 11.2 Request Logging Middleware with Metrics

```typescript
import morgan from 'morgan';

// Custom token for response time
morgan.token('response-time-ms', (req, res) => {
  return res.responseTime ? res.responseTime.toFixed(2) : '0';
});

// Track response times
app.use((req, res, next) => {
  const start = Date.now();
  res.on('finish', () => {
    res.responseTime = Date.now() - start;
    
    // Log slow queries (> 1 second)
    if (res.responseTime > 1000) {
      logger.warn('Slow request', {
        method: req.method,
        path: req.path,
        duration_ms: res.responseTime,
        status: res.statusCode,
        user_id: req.user?.user_id
      });
    }
  });
  next();
});

// JSON format for Cloud Logging
app.use(morgan(':method :url :status :response-time-ms ms', {
  stream: {
    write: (message) => {
      logger.info(message.trim(), {
        category: 'http_request'
      });
    }
  }
}));
```

### 11.3 Health Check Endpoint (Production-Ready)

```typescript
app.get('/health', async (req, res) => {
  const healthcheck = {
    status: 'healthy',
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
    checks: {
      database: 'unknown',
      memory: 'unknown'
    }
  };
  
  try {
    // Check database connection with timeout
    const dbCheckStart = Date.now();
    await Promise.race([
      db.selectFrom('users').select('id').limit(1).execute(),
      new Promise((_, reject) => 
        setTimeout(() => reject(new Error('DB timeout')), 5000)
      )
    ]);
    healthcheck.checks.database = 'healthy';
    healthcheck.checks.database_latency_ms = Date.now() - dbCheckStart;
    
    // Check memory usage
    const memUsage = process.memoryUsage();
    const memUsageMB = Math.round(memUsage.heapUsed / 1024 / 1024);
    healthcheck.checks.memory = memUsageMB < 900 ? 'healthy' : 'warning';
    healthcheck.checks.memory_usage_mb = memUsageMB;
    
    res.status(200).json(healthcheck);
  } catch (error) {
    healthcheck.status = 'unhealthy';
    healthcheck.checks.database = 'unhealthy';
    healthcheck.error = error.message;
    
    logger.error('Health check failed', {
      error: error.message,
      checks: healthcheck.checks
    });
    
    res.status(503).json(healthcheck);
  }
});

// Readiness probe (for zero-downtime deployments)
app.get('/ready', async (req, res) => {
  try {
    await db.selectFrom('users').select('id').limit(1).execute();
    res.status(200).send('OK');
  } catch (error) {
    res.status(503).send('NOT READY');
  }
});
```

### 11.4 Cloud Monitoring Metrics

**Key metrics to monitor in Google Cloud Console:**

```typescript
// Custom metric export (optional - Cloud Run auto-tracks these)
interface Metrics {
  request_count: number;
  request_duration_ms: number[];
  error_count: number;
  db_query_count: number;
  db_query_duration_ms: number[];
  active_users: Set<string>;
}

// Track in middleware
app.use((req, res, next) => {
  metrics.request_count++;
  
  const start = Date.now();
  res.on('finish', () => {
    const duration = Date.now() - start;
    metrics.request_duration_ms.push(duration);
    
    if (res.statusCode >= 500) {
      metrics.error_count++;
    }
    
    if (req.user?.user_id) {
      metrics.active_users.add(req.user.user_id);
    }
  });
  
  next();
});
```

**Auto-tracked metrics (Cloud Run):**
- `request_count` - Total requests
- `request_latencies` - p50, p95, p99 latency
- `billable_instance_time` - Container uptime
- `container_cpu_utilization` - CPU usage %
- `container_memory_utilization` - Memory usage %
- `container_instance_count` - Active instances

### 11.5 Alerting Policies (Google Cloud Monitoring)

**Critical Alerts:**

```yaml
# High Error Rate Alert
- name: "High Error Rate"
  condition: error_rate > 1%
  duration: 5 minutes
  notification: PagerDuty, Email
  
# Slow Response Time Alert  
- name: "Slow Response Time"
  condition: p95_latency > 1000ms
  duration: 10 minutes
  notification: Slack, Email
  
# Database Connection Issues
- name: "Database Connection Failures"
  condition: db_connection_errors > 5
  duration: 1 minute
  notification: PagerDuty, Email
  
# High Memory Usage
- name: "Memory Usage Warning"
  condition: memory_usage > 80%
  duration: 15 minutes
  notification: Slack

# Instance Scaling Issues
- name: "Max Instances Reached"
  condition: instance_count >= 95
  duration: 5 minutes
  notification: PagerDuty, Email
```

### 11.6 Graceful Shutdown

```typescript
// Handle SIGTERM for zero-downtime deployments
process.on('SIGTERM', async () => {
  logger.info('SIGTERM received, shutting down gracefully');
  
  // Stop accepting new requests
  server.close(async () => {
    logger.info('HTTP server closed');
    
    // Close database connections
    try {
      await db.destroy();
      logger.info('Database connections closed');
    } catch (error) {
      logger.error('Error closing database', { error: error.message });
    }
    
    process.exit(0);
  });
  
  // Force shutdown after 30 seconds (Cloud Run timeout)
  setTimeout(() => {
    logger.error('Forced shutdown after timeout');
    process.exit(1);
  }, 30000);
});
```

---

## 12. Security Architecture & Best Practices

### 12.1 Security Design Principles

**Defense in Depth:**
- Multiple layers of security controls
- No single point of failure in security architecture
- Assume breach mentality - limit damage if one layer fails

**Principle of Least Privilege:**
- Database users have minimal required permissions
- API endpoints validate authorization for every request
- Service accounts scoped to specific resources

**Secure by Default:**
- HTTPS only, no HTTP fallback
- Strict CSP and security headers
- Authentication required for all data endpoints

### 12.2 Authentication & Authorization

**JWT Token Security:**
```typescript
// Token configuration
const JWT_CONFIG = {
  accessToken: {
    expiresIn: '1h',        // Short-lived for security
    algorithm: 'HS256'
  },
  refreshToken: {
    expiresIn: '30d',       // Longer-lived
    algorithm: 'HS256'
  }
};

// Secure token generation
export function generateTokens(userId: string) {
  const accessToken = jwt.sign(
    { user_id: userId, type: 'access' },
    process.env.JWT_SECRET!,
    { expiresIn: '1h' }
  );
  
  const refreshToken = jwt.sign(
    { user_id: userId, type: 'refresh' },
    process.env.JWT_REFRESH_SECRET!,
    { expiresIn: '30d' }
  );
  
  return { accessToken, refreshToken };
}

// Token verification with error handling
export function verifyAccessToken(token: string): { user_id: string } {
  try {
    const decoded = jwt.verify(token, process.env.JWT_SECRET!) as any;
    
    if (decoded.type !== 'access') {
      throw new Error('Invalid token type');
    }
    
    return { user_id: decoded.user_id };
  } catch (error) {
    if (error.name === 'TokenExpiredError') {
      throw new Error('Token expired');
    }
    throw new Error('Invalid token');
  }
}
```

**Authorization Middleware:**
```typescript
// Verify user owns resource or has permission
export async function requireGroupMembership(
  req: Request,
  res: Response,
  next: NextFunction
) {
  const userId = req.user!.user_id;
  const groupId = req.params.groupId;
  
  const membership = await db
    .selectFrom('group_members')
    .select('role')
    .where('group_id', '=', groupId)
    .where('user_id', '=', userId)
    .executeTakeFirst();
  
  if (!membership) {
    return res.status(403).json({ error: 'Not a group member' });
  }
  
  req.userRole = membership.role;
  next();
}
```

### 12.3 Input Validation & Sanitization

**Zod Schemas for All Endpoints:**
```typescript
// User registration
const RegisterSchema = z.object({
  email: z.string().email().max(255),
  password: z.string().min(8).max(128),
  display_name: z.string().min(1).max(100).trim()
}).strict();

// Progress entry with business logic validation
const ProgressEntrySchema = z.object({
  goal_id: z.string().uuid(),
  value: z.number().min(0).max(1_000_000),
  note: z.string().max(500).trim().optional(),
  user_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
  user_timezone: z.string().min(1).max(50)
}).strict();

// Validation middleware
export function validateBody(schema: z.ZodSchema) {
  return (req: Request, res: Response, next: NextFunction) => {
    try {
      req.body = schema.parse(req.body);
      next();
    } catch (error) {
      if (error instanceof z.ZodError) {
        return res.status(400).json({
          error: 'Validation failed',
          details: error.errors
        });
      }
      next(error);
    }
  };
}
```

### 12.4 SQL Injection Prevention

**Always use parameterized queries** (Kysely does this automatically):

```typescript
// ✅ SAFE - Kysely uses parameterized queries
const user = await db
  .selectFrom('users')
  .selectAll()
  .where('email', '=', userEmail)
  .executeTakeFirst();

// ❌ NEVER DO THIS - SQL injection vulnerability
const user = await db.executeQuery(`
  SELECT * FROM users WHERE email = '${userEmail}'
`);

// ✅ SAFE - Dynamic queries with Kysely
let query = db.selectFrom('progress_entries');

if (startDate) {
  query = query.where('entry_date', '>=', startDate);
}
if (endDate) {
  query = query.where('entry_date', '<=', endDate);
}

const results = await query.execute();
```

### 12.5 Password Security

```typescript
import bcrypt from 'bcrypt';

const BCRYPT_ROUNDS = 10; // Balance security vs performance

// Hash password (10 rounds = ~100ms)
export async function hashPassword(password: string): Promise<string> {
  return bcrypt.hash(password, BCRYPT_ROUNDS);
}

// Verify password with timing-attack protection
export async function verifyPassword(
  password: string,
  hash: string
): Promise<boolean> {
  return bcrypt.compare(password, hash);
}

// Password strength validation
const PasswordSchema = z.string()
  .min(8, 'Password must be at least 8 characters')
  .max(128, 'Password too long')
  .regex(/[a-z]/, 'Must contain lowercase letter')
  .regex(/[A-Z]/, 'Must contain uppercase letter')
  .regex(/[0-9]/, 'Must contain number');
```

### 12.6 Security Headers (Helmet)

```typescript
import helmet from 'helmet';

app.use(helmet({
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'self'"],
      styleSrc: ["'self'", "'unsafe-inline'"],
      scriptSrc: ["'self'"],
      imgSrc: ["'self'", 'data:', 'https:'],
      connectSrc: ["'self'"],
      fontSrc: ["'self'"],
      objectSrc: ["'none'"],
      mediaSrc: ["'self'"],
      frameSrc: ["'none'"]
    }
  },
  hsts: {
    maxAge: 31536000,
    includeSubDomains: true,
    preload: true
  },
  noSniff: true,
  referrerPolicy: { policy: 'strict-origin-when-cross-origin' }
}));
```

### 12.7 CORS Configuration

```typescript
import cors from 'cors';

const allowedOrigins = [
  'https://getpursue.app',
  'https://www.getpursue.app',
  process.env.FRONTEND_URL
].filter(Boolean);

app.use(cors({
  origin: (origin, callback) => {
    // Allow requests with no origin (mobile apps, curl, etc.)
    if (!origin) return callback(null, true);
    
    if (allowedOrigins.includes(origin)) {
      callback(null, true);
    } else {
      callback(new Error('Not allowed by CORS'));
    }
  },
  credentials: true,
  methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'],
  allowedHeaders: ['Content-Type', 'Authorization']
}));
```

### 12.8 Rate Limiting

```typescript
import rateLimit from 'express-rate-limit';

// General API rate limit
const apiLimiter = rateLimit({
  windowMs: 1 * 60 * 1000,        // 1 minute
  max: 100,                        // 100 requests per minute
  message: 'Too many requests, please try again later',
  standardHeaders: true,
  legacyHeaders: false
});

// Stricter limit for authentication endpoints
const authLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,       // 15 minutes
  max: 5,                          // 5 attempts
  message: 'Too many login attempts, please try again later',
  skipSuccessfulRequests: true     // Don't count successful logins
});

// Very strict for file uploads
const uploadLimiter = rateLimit({
  windowMs: 15 * 60 * 1000,       // 15 minutes
  max: 10,                         // 10 uploads
  message: 'Too many uploads, please try again later'
});

app.use('/api/', apiLimiter);
app.use('/api/auth/login', authLimiter);
app.use('/api/auth/register', authLimiter);
app.use('/api/users/me/avatar', uploadLimiter);
app.use('/api/groups/:id/icon', uploadLimiter);
```

**API Rate Limits:**
- General API: 100 requests/minute per user
- Auth endpoints: 5 attempts/15 minutes
- File uploads: 10 uploads/15 minutes

**Resource Creation Limits:**

*Groups:*
- Max created: 10 per user
- Max joined: 10 per user
- Creation rate: 10 per day
- Burst rate: 3 per hour

*Goals:*
- Max per group: 100
- Creation rate: 20 per day per group 
- Burst rate: 5 per hour per group 

*Members:*
- Max per group: 50
- Invites: 20 per day per user 
- Joins: 10 per day per user

*Progress Entries:*
- Max per day: 200 per user
- Burst: 50 per minute

**Implementation:**
```typescript
// Check before creation
const groupCount = await db
  .selectFrom('groups')
  .select(db.fn.count('id').as('count'))
  .where('creator_id', '=', userId)
  .executeTakeFirst();

if (groupCount.count >= 10) {
  throw new Error('Maximum groups reached (50)');
}
```

**Error Responses:**
```json
{
  "error": "Maximum groups reached (10)",
  "limit": 10,
  "current": 10,
  "suggestion": "Consider deleting old groups"
}
```


### 12.9 Resource Limits

To prevent abuse and ensure database stability, enforce maximum limits on resource creation.

**Per-User Limits:**
```typescript
const RESOURCE_LIMITS = {
  USER: {
    MAX_GROUPS_CREATED: 10,     // Total groups created by user
    MAX_GROUPS_JOINED: 10,      // Total groups user is member of
    MAX_PROGRESS_PER_DAY: 200    // Progress entries per day (future)
  }
};
```

**Per-Group Limits:**
```typescript
const RESOURCE_LIMITS = {
  GROUP: {
    MAX_GOALS: 100,              // Goals per group
    MAX_MEMBERS: 50              // Members per group
  }
};
```

**Implementation:**

```typescript
// middleware/resourceLimits.ts
export async function checkResourceLimits(
  userId: string,
  resourceType: 'group' | 'goal' | 'member',
  groupId?: string
): Promise<void> {
  
  if (resourceType === 'group') {
    // Check total groups created by user
    const result = await db
      .selectFrom('groups')
      .select(db.fn.count('id').as('count'))
      .where('creator_id', '=', userId)
      .executeTakeFirst();
    
    const count = Number(result?.count || 0);
    
    if (count >= 50) {
      throw new Error('Maximum groups limit reached (50). Consider archiving old groups.');
    }
  }
  
  if (resourceType === 'goal' && groupId) {
    // Check total goals in group
    const result = await db
      .selectFrom('goals')
      .select(db.fn.count('id').as('count'))
      .where('group_id', '=', groupId)
      .where('archived_at', 'is', null)  // Only count active goals
      .executeTakeFirst();
    
    const count = Number(result?.count || 0);
    
    if (count >= 100) {
      throw new Error('Maximum goals per group reached (100). Consider archiving old goals or creating a new group.');
    }
  }
  
  if (resourceType === 'member' && groupId) {
    // Check total members in group
    const result = await db
      .selectFrom('group_members')
      .select(db.fn.count('user_id').as('count'))
      .where('group_id', '=', groupId)
      .executeTakeFirst();
    
    const count = Number(result?.count || 0);
    
    if (count >= 50) {
      throw new Error('Maximum members per group reached (50). Consider creating a new group.');
    }
  }
}
```

**Usage in Endpoints:**

```typescript
// Create group
app.post('/api/groups', authenticateJWT, async (req, res) => {
  try {
    // Check resource limits BEFORE creating
    await checkResourceLimits(req.user.user_id, 'group');
    
    // Proceed with creation
    const group = await createGroup(req.body);
    res.status(201).json(group);
    
  } catch (error) {
    if (error.message.includes('Maximum')) {
      return res.status(429).json({ 
        error: error.message,
        code: 'RESOURCE_LIMIT_EXCEEDED'
      });
    }
    throw error;
  }
});

// Create goal
app.post('/api/groups/:group_id/goals', authenticateJWT, async (req, res) => {
  try {
    await checkResourceLimits(req.user.user_id, 'goal', req.params.group_id);
    
    const goal = await createGoal(req.params.group_id, req.body);
    res.status(201).json(goal);
    
  } catch (error) {
    if (error.message.includes('Maximum')) {
      return res.status(429).json({ 
        error: error.message,
        code: 'RESOURCE_LIMIT_EXCEEDED'
      });
    }
    throw error;
  }
});

// Add member
app.post('/api/groups/:group_id/members', authenticateJWT, async (req, res) => {
  try {
    await checkResourceLimits(req.user.user_id, 'member', req.params.group_id);
    
    const member = await addMember(req.params.group_id, req.body);
    res.status(201).json(member);
    
  } catch (error) {
    if (error.message.includes('Maximum')) {
      return res.status(429).json({ 
        error: error.message,
        code: 'RESOURCE_LIMIT_EXCEEDED'
      });
    }
    throw error;
  }
});
```

**Error Response Format:**

```json
{
  "error": "Maximum groups limit reached (50). Consider archiving old groups.",
  "code": "RESOURCE_LIMIT_EXCEEDED",
  "limit": 50,
  "current": 50,
  "resource": "groups"
}
```

**Future Rate Limiting (Not Implemented Initially):**

Time-based rate limits can be added later if abuse is detected:

```typescript
// Future: Daily/hourly limits using Redis
const TIME_BASED_LIMITS = {
  GROUP_CREATION: {
    perDay: 10,      // Max 10 groups created per day
    perHour: 3       // Max 3 groups created per hour
  },
  GOAL_CREATION: {
    perDay: 20,      // Max 20 goals per day per group
    perHour: 5       // Max 5 goals per hour per group
  }
};
```

**Rationale:**
- **50 groups created**: Generous for power users, prevents spam
- **100 groups joined**: Supports highly social users
- **100 goals per group**: Covers extreme use cases, most groups have 5-20
- **50 members per group**: Large accountability group, good group dynamics
- **Simple total limits first**: Easy to implement, catches 95% of abuse
- **Defer time-based limits**: Add only if abuse patterns emerge

**Database Indexes for Performance:**

```sql
-- Fast counting for resource limits
CREATE INDEX idx_groups_creator_id ON groups(creator_id);
CREATE INDEX idx_goals_group_id_archived ON goals(group_id, archived_at);
CREATE INDEX idx_group_members_group_id ON group_members(group_id);
```

### 12.10 File Upload Security

```typescript
import multer from 'multer';
import sharp from 'sharp';

// Strict file validation
const upload = multer({
  limits: {
    fileSize: 5 * 1024 * 1024,    // 5 MB max
    files: 1                       // One file at a time
  },
  fileFilter: (req, file, cb) => {
    // Only allow images
    const allowedMimes = ['image/jpeg', 'image/png', 'image/webp'];
    
    if (allowedMimes.includes(file.mimetype)) {
      cb(null, true);
    } else {
      cb(new Error('Invalid file type. Only JPEG, PNG, and WebP allowed'));
    }
  }
});

// Secure image processing (strips metadata, validates content)
export async function processAndValidateImage(buffer: Buffer): Promise<Buffer> {
  try {
    // Validate it's actually an image (sharp will fail on malicious files)
    const metadata = await sharp(buffer).metadata();
    
    // Additional validation
    if (!metadata.width || !metadata.height) {
      throw new Error('Invalid image');
    }
    
    if (metadata.width > 4096 || metadata.height > 4096) {
      throw new Error('Image too large');
    }
    
    // Process: resize, convert to WebP, strip EXIF
    return sharp(buffer)
      .resize(256, 256, { fit: 'cover' })
      .webp({ quality: 90 })
      .toBuffer();
  } catch (error) {
    throw new Error('Invalid or corrupted image file');
  }
}
```

### 12.11 Secrets Management

```typescript
// ❌ NEVER hardcode secrets
const JWT_SECRET = 'my-secret-key';

// ✅ Use environment variables
const JWT_SECRET = process.env.JWT_SECRET;

// ✅ Validate secrets on startup
if (!process.env.JWT_SECRET || process.env.JWT_SECRET.length < 32) {
  throw new Error('JWT_SECRET must be at least 32 characters');
}

if (!process.env.JWT_REFRESH_SECRET || process.env.JWT_REFRESH_SECRET.length < 32) {
  throw new Error('JWT_REFRESH_SECRET must be at least 32 characters');
}

// Use Google Secret Manager in production
import { SecretManagerServiceClient } from '@google-cloud/secret-manager';

const client = new SecretManagerServiceClient();

async function getSecret(name: string): Promise<string> {
  const [version] = await client.accessSecretVersion({
    name: `projects/${PROJECT_ID}/secrets/${name}/versions/latest`
  });
  
  return version.payload!.data!.toString();
}
```

### 12.12 Database Security

```typescript
// Use least-privilege database users
const DB_CONFIG = {
  // Application user (read/write data)
  app: {
    user: 'pursue_app',
    permissions: ['SELECT', 'INSERT', 'UPDATE', 'DELETE'],
    tables: ['users', 'groups', 'goals', 'progress_entries', 'group_members']
  },
  
  // Migration user (schema changes)
  migration: {
    user: 'pursue_migration',
    permissions: ['ALL'],
    tables: ['ALL']
  },
  
  // Read-only user (analytics, reporting)
  readonly: {
    user: 'pursue_readonly',
    permissions: ['SELECT'],
    tables: ['ALL']
  }
};

// Enable SSL for database connections
const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  ssl: {
    rejectUnauthorized: true,
    ca: fs.readFileSync('./certs/server-ca.pem').toString(),
    key: fs.readFileSync('./certs/client-key.pem').toString(),
    cert: fs.readFileSync('./certs/client-cert.pem').toString()
  }
});
```

### 12.13 Audit Logging

```typescript
// Log all sensitive operations
export function auditLog(action: string, userId: string, details: any) {
  logger.info('Audit', {
    action,
    user_id: userId,
    timestamp: new Date().toISOString(),
    details,
    ip: req.ip,
    user_agent: req.get('User-Agent')
  });
}

// Examples
auditLog('user.login', userId, { method: 'email' });
auditLog('group.created', userId, { group_id: groupId });
auditLog('user.deleted', userId, { admin_id: adminId });
auditLog('password.changed', userId, {});
```

### 12.14 Security Monitoring & Alerts

```yaml
# Alert policies
alerts:
  - name: "High Failed Login Rate"
    condition: failed_login_rate > 10/minute
    action: Lock account, notify security team
    
  - name: "Unusual API Access Pattern"
    condition: requests_from_ip > 1000/minute
    action: Rate limit IP, investigate
    
  - name: "SQL Injection Attempt"
    condition: query_contains_sql_keywords
    action: Block request, log incident
    
  - name: "Unauthorized Access Attempt"
    condition: 403_errors > 50/hour from single user
    action: Temporary account lock, investigate
```

### 12.15 Security Checklist

**Development:**
- [ ] All endpoints require authentication (except login/register)
- [ ] All inputs validated with Zod schemas
- [ ] All database queries use parameterized queries
- [ ] Passwords hashed with bcrypt (10+ rounds)
- [ ] JWT secrets are 32+ characters, stored in Secret Manager
- [ ] HTTPS only, HSTS enabled
- [ ] Security headers configured (Helmet)
- [ ] CORS restricted to known origins
- [ ] Rate limiting on all endpoints
- [ ] File uploads validated and sanitized
- [ ] Audit logging for sensitive operations

**Deployment:**
- [ ] Database uses least-privilege users
- [ ] Database connections use SSL/TLS
- [ ] Secrets stored in Google Secret Manager
- [ ] VPC connector for private database access
- [ ] Cloud Armor for DDoS protection
- [ ] Regular security updates (npm audit)
- [ ] Dependency scanning in CI/CD
- [ ] Penetration testing before launch

**Ongoing:**
- [ ] Monitor failed login attempts
- [ ] Review audit logs weekly
- [ ] Update dependencies monthly
- [ ] Security patches applied within 48 hours
- [ ] Annual security audit
- [ ] Incident response plan documented

---

## 13. Performance Optimization

### 13.1 Input Validation (Zod)

**Critical:** Validate all user input to prevent injection, type errors, and invalid data.

```typescript
import { z } from 'zod';

// Progress entry validation
export const ProgressEntrySchema = z.object({
  goal_id: z.string().uuid(),
  value: z.number(),
  note: z.string().max(500).optional(),
  user_date: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), // YYYY-MM-DD
  user_timezone: z.string().min(1).max(50) // IANA timezone
}).strict();

// Validation with metric type checking
export async function validateProgressEntry(data: unknown, goalId: string) {
  // Parse basic structure
  const parsed = ProgressEntrySchema.parse(data);
  
  // Fetch goal to check metric_type
  const goal = await db
    .selectFrom('goals')
    .select(['metric_type', 'target_value'])
    .where('id', '=', goalId)
    .executeTakeFirst();
    
  if (!goal) {
    throw new Error('Goal not found');
  }
  
  // Validate value against metric_type
  if (goal.metric_type === 'binary') {
    if (parsed.value !== 0 && parsed.value !== 1) {
      throw new Error('Binary goal value must be 0 or 1');
    }
  } else if (goal.metric_type === 'numeric') {
    if (parsed.value < 0) {
      throw new Error('Numeric goal value cannot be negative');
    }
    if (parsed.value > 999999.99) {
      throw new Error('Numeric goal value too large');
    }
  } else if (goal.metric_type === 'duration') {
    if (parsed.value < 0) {
      throw new Error('Duration cannot be negative');
    }
    if (!Number.isInteger(parsed.value)) {
      throw new Error('Duration must be integer (seconds)');
    }
  }
  
  return parsed;
}

// Goal creation validation
export const CreateGoalSchema = z.object({
  title: z.string().min(1).max(200),
  description: z.string().max(1000).optional(),
  cadence: z.enum(['daily', 'weekly', 'monthly', 'yearly']),
  metric_type: z.enum(['binary', 'numeric', 'duration']),
  target_value: z.number().positive().max(999999.99).optional(),
  unit: z.string().max(50).optional()
}).strict().refine(
  (data) => {
    // Numeric goals should have target_value
    if (data.metric_type === 'numeric' && !data.target_value) {
      return false;
    }
    return true;
  },
  { message: 'Numeric goals must have target_value' }
);

// User registration validation
export const RegisterSchema = z.object({
  email: z.string().email().max(255),
  password: z.string().min(8).max(100),
  display_name: z.string().min(1).max(100)
}).strict();
```

**Usage in Routes:**
```typescript
app.post('/api/progress', authenticate, async (req, res, next) => {
  try {
    const validated = await validateProgressEntry(req.body, req.body.goal_id);
    // Proceed with validated data
  } catch (error) {
    if (error instanceof z.ZodError) {
      return res.status(400).json({
        error: {
          message: 'Validation error',
          code: 'VALIDATION_ERROR',
          details: error.errors
        }
      });
    }
    next(error);
  }
});
```

### 13.2 Database Connection Pooling

```typescript
import { Pool } from 'pg';
import { Kysely, PostgresDialect } from 'kysely';

const pool = new Pool({
  connectionString: process.env.DATABASE_URL,
  max: 20, // Maximum connections
  idleTimeoutMillis: 30000,
  connectionTimeoutMillis: 2000
});

export const db = new Kysely({
  dialect: new PostgresDialect({ pool })
});
```

### 13.2 Query Optimization

```typescript
// ✅ Good: Use indexes, limit results
const recentProgress = await db
  .selectFrom('progress_entries')
  .selectAll()
  .where('user_id', '=', userId)
  .orderBy('logged_at', 'desc')
  .limit(50)
  .execute();

// ✅ Good: Select only needed columns
const users = await db
  .selectFrom('users')
  .select([
    'id', 
    'display_name',
    db.raw('(avatar_data IS NOT NULL) as has_avatar').as('has_avatar')
  ])
  .where('id', 'in', userIds)
  .execute();
```

**Note:** Don't select `avatar_data` in list queries - it's large binary data. Use `has_avatar` boolean to show if avatar exists, then fetch via GET endpoint if needed.

### 13.3 Query Optimization

```typescript
// ✅ Good: Use indexes, limit results
const recentProgress = await db
  .selectFrom('progress_entries')
  .selectAll()
  .where('user_id', '=', userId)
  .orderBy('logged_at', 'desc')
  .limit(50)
  .execute();

// ✅ Good: Select only needed columns (don't select BYTEA in lists)
const users = await db
  .selectFrom('users')
  .select([
    'id', 
    'display_name',
    db.raw('(avatar_data IS NOT NULL) as has_avatar').as('has_avatar')
  ])
  .where('id', 'in', userIds)
  .execute();
```

### 13.4 Preventing N+1 Queries

**Problem:** The N+1 query problem occurs when you fetch a list of items, then loop through them making additional queries for related data.

**Example - BAD (N+1):**
```typescript
// ❌ BAD: This makes 1 + N queries
app.get('/api/groups/:id', async (req, res) => {
  // 1 query to get group
  const group = await db
    .selectFrom('groups')
    .selectAll()
    .where('id', '=', req.params.id)
    .executeTakeFirst();
    
  // N queries (one per goal)
  const goals = await db
    .selectFrom('goals')
    .selectAll()
    .where('group_id', '=', group.id)
    .execute();
    
  // For each goal, fetch recent progress (N more queries!)
  for (const goal of goals) {
    goal.recentProgress = await db
      .selectFrom('progress_entries')
      .selectAll()
      .where('goal_id', '=', goal.id)
      .limit(5)
      .execute();
  }
  
  return res.json({ group, goals });
});
```

**Solution - GOOD (Single Optimized Query):**
```typescript
// ✅ GOOD: This makes 1 query using JOINs
app.get('/api/groups/:id', async (req, res) => {
  const result = await db
    .selectFrom('groups as g')
    .leftJoin('goals as go', 'go.group_id', 'g.id')
    .leftJoin('progress_entries as p', 'p.goal_id', 'go.id')
    .select([
      'g.id as group_id',
      'g.name as group_name',
      'g.description',
      'go.id as goal_id',
      'go.title as goal_title',
      'go.cadence',
      'p.id as progress_id',
      'p.value',
      'p.user_id',
      'p.logged_at'
    ])
    .where('g.id', '=', req.params.id)
    .where('go.deleted_at', 'is', null) // Only active goals
    .orderBy('p.logged_at', 'desc')
    .execute();
    
  // Transform flat result into nested structure
  const group = {
    id: result[0]?.group_id,
    name: result[0]?.group_name,
    description: result[0]?.description,
    goals: []
  };
  
  // Group by goal_id
  const goalsMap = new Map();
  for (const row of result) {
    if (!goalsMap.has(row.goal_id)) {
      goalsMap.set(row.goal_id, {
        id: row.goal_id,
        title: row.goal_title,
        cadence: row.cadence,
        recentProgress: []
      });
    }
    
    if (row.progress_id) {
      goalsMap.get(row.goal_id).recentProgress.push({
        id: row.progress_id,
        value: row.value,
        user_id: row.user_id,
        logged_at: row.logged_at
      });
    }
  }
  
  group.goals = Array.from(goalsMap.values());
  
  return res.json(group);
});
```

**Alternative: Use Kysely's `selectFrom().selectAll()` with aggregation:**
```typescript
// ✅ ALSO GOOD: Fetch separately but efficiently
const [group, goals, recentProgress] = await Promise.all([
  // Query 1: Get group
  db.selectFrom('groups')
    .selectAll()
    .where('id', '=', groupId)
    .executeTakeFirst(),
    
  // Query 2: Get all goals for this group
  db.selectFrom('goals')
    .selectAll()
    .where('group_id', '=', groupId)
    .where('deleted_at', 'is', null)
    .execute(),
    
  // Query 3: Get recent progress for ALL goals in one query
  db.selectFrom('progress_entries')
    .innerJoin('goals', 'goals.id', 'progress_entries.goal_id')
    .selectAll('progress_entries')
    .where('goals.group_id', '=', groupId)
    .orderBy('progress_entries.logged_at', 'desc')
    .limit(100) // Reasonable limit across all goals
    .execute()
]);

// Group progress by goal_id in memory
const progressByGoal = new Map();
for (const entry of recentProgress) {
  if (!progressByGoal.has(entry.goal_id)) {
    progressByGoal.set(entry.goal_id, []);
  }
  progressByGoal.get(entry.goal_id).push(entry);
}

// Attach progress to goals
const goalsWithProgress = goals.map(goal => ({
  ...goal,
  recentProgress: progressByGoal.get(goal.id) || []
}));

return res.json({ group, goals: goalsWithProgress });
```

**Key Principles:**
1. **Fetch related data in bulk**, not in loops
2. **Use JOINs** for small related datasets
3. **Use separate queries + in-memory grouping** for complex relationships
4. **Always use WHERE clauses** to filter server-side
5. **Add LIMIT** to prevent massive result sets

### 13.5 Caching (Future Enhancement)

```typescript
import Redis from 'ioredis';

const redis = new Redis(process.env.REDIS_URL);

// Cache group data
export async function getCachedGroup(groupId: string) {
  const cached = await redis.get(`group:${groupId}`);
  if (cached) {
    return JSON.parse(cached);
  }
  
  const group = await db
    .selectFrom('groups')
    .selectAll()
    .where('id', '=', groupId)
    .executeTakeFirst();
    
  if (group) {
    await redis.setex(`group:${groupId}`, 300, JSON.stringify(group));
  }
  
  return group;
}
```

---

## 14. Privacy Mode Architecture (Future)

### 14.1 E2E Encryption Limitations

**Group-Level Seed Phrases** enable powerful privacy, but come with tradeoffs:

**What Works:**
- ✅ Client-side encryption of goal titles, descriptions, progress notes
- ✅ Server stores encrypted blobs (cannot read)
- ✅ Group members can decrypt with shared seed phrase
- ✅ True end-to-end encryption

**What Doesn't Work (Server is Blind):**

| Feature | Impact | Workaround |
|---------|--------|------------|
| **Weekly Summary Emails** | Cannot read goal titles to send "You completed 'X'" | Send generic: "You completed 5 of 7 goals" |
| **Server-Side Leaderboards** | Cannot rank by goal completion | Client-side only, shared via encrypted messages |
| **Search** | Cannot search encrypted titles | Client caches decrypted data locally |
| **Admin Moderation** | Cannot review content for abuse | Trust-based system, manual reports only |
| **Analytics** | Cannot aggregate across encrypted groups | Collect only metadata (goal count, not content) |
| **FCM Notifications** | Cannot send "Alex completed '30 min run'" | Send "Alex completed a goal" (generic) |

### 14.2 Implementation Strategy

**Client Responsibilities:**
```typescript
// Client encrypts before sending to server
const encryptedGoal = {
  group_id: groupId,
  encrypted_title: encrypt(seedPhrase, "30 min run"),
  encrypted_description: encrypt(seedPhrase, "Run for at least 30 minutes"),
  cadence: "daily", // NOT encrypted (needed for server logic)
  metric_type: "binary", // NOT encrypted
  // Server can see structure, not content
};

// Client decrypts after receiving from server
const decryptedGoal = {
  ...goalFromServer,
  title: decrypt(seedPhrase, goalFromServer.encrypted_title),
  description: decrypt(seedPhrase, goalFromServer.encrypted_description)
};
```

**Server Schema Changes:**
```sql
ALTER TABLE goals ADD COLUMN encrypted_title TEXT;
ALTER TABLE goals ADD COLUMN encrypted_description TEXT;
ALTER TABLE progress_entries ADD COLUMN encrypted_note TEXT;

-- Metadata still visible for logic
-- title, description, note columns NULL for encrypted groups
```

**Client-Side Aggregation:**
```typescript
// Weekly summary - client calculates
const summary = {
  totalGoals: decryptedGoals.length,
  completed: decryptedGoals.filter(g => g.completed).length,
  streak: calculateStreak(decryptedProgressEntries)
};

// Display locally, optionally share encrypted in group chat
```

### 14.3 Recommendation

**Initial Release:** Privacy Mode not included
- Adds 4-6 weeks development time
- Majority of users don't need E2E encryption for fitness goals
- Focus on core server-side features (emails, notifications, real-time sync)

**Future Enhancement:** Consider as premium feature ($5/month)
- Market to privacy-conscious power users
- Clearly communicate limitations (no email summaries, reduced real-time features)
- Provide "Standard" vs "Private" group toggle
- Disable features gracefully for encrypted groups

---

## 14. Privacy Mode (E2E Encryption) - Architecture Notes

**Future Feature:** Group-level end-to-end encryption using group seed phrases.

### 14.1 How It Works

**Encryption Flow:**
1. Group creator enables "Privacy Mode" when creating group
2. App generates 12-word BIP39 seed phrase
3. Derive AES-256 key from seed phrase using PBKDF2
4. Encrypt group symmetric key with seed-derived key
5. Store encrypted symmetric key on server
6. Encrypt goal titles, descriptions, progress notes client-side
7. Server stores encrypted blobs, cannot read content

**Data Encrypted Client-Side:**
- Goal titles
- Goal descriptions  
- Progress entry notes
- Member display names (optional)

**Data NOT Encrypted (Required for Server Functionality):**
- Group ID, Group name (for routing)
- Goal IDs, metric_type, cadence, target_value (for validation)
- Progress values (numeric data only, no text)
- Timestamps (logged_at, period_start)
- User IDs (for access control)

### 14.2 Critical Limitations

**⚠️ Server Cannot Provide These Features for Encrypted Groups:**

1. **Weekly Summary Emails** 
   - Server blind to goal titles/progress notes
   - Cannot generate "You completed 'Morning Run' 5 times"
   - Would need to show: "You completed Goal #1 5 times" (useless)

2. **Server-Side Leaderboards**
   - Cannot sort by goal title
   - Cannot display achievement names
   - Leaderboards must be computed client-side

3. **Search Functionality**
   - Cannot search goal titles across groups
   - Cannot search progress notes
   - Full-text search impossible

4. **Admin Dashboards**
   - Cannot moderate content (goal titles could be inappropriate)
   - Cannot generate usage analytics (which goals are popular?)
   - Cannot provide support (can't see what user is asking about)

5. **Cross-Group Features**
   - Cannot suggest similar groups
   - Cannot show "trending goals"
   - Cannot detect duplicate group names

### 14.3 Client-Side Requirements

**App Must Handle:**
- All aggregation (weekly totals, streaks, charts)
- All search (within encrypted data)
- All leaderboards (compute locally)
- Export functionality (decrypt and export to CSV/PDF)
- Seed phrase backup and recovery UI

**Example Client-Side Aggregation:**
```typescript
// Client must decrypt all entries, then aggregate
const entries = await fetchProgressEntries(groupId, startDate, endDate);
const decryptedEntries = entries.map(e => ({
  ...e,
  goal_title: decrypt(e.encrypted_goal_title, groupKey),
  note: decrypt(e.encrypted_note, groupKey)
}));

// Now compute weekly total
const weeklyTotal = decryptedEntries.filter(e => e.value === 1).length;
```

### 14.4 Recommended Approach

**Initial Implementation:**
- Standard (unencrypted) groups with server-side features
- Focus on core functionality: real-time sync, notifications, analytics
- Gather user feedback on privacy requirements

**Future Enhancement:**
- Offer "Privacy Mode" as opt-in premium feature if demand exists
- Clearly document limitations (no emails, no server-side aggregations)
- Target audience: Privacy-conscious power users who understand trade-offs

**Alternative Consideration:**
- Server-side encryption at rest (database-level encryption)
- Provides data-at-rest protection without E2E limitations
- Enables all server features while improving security posture

---

## 15. Open Questions

- [x] ~~Timezones: How to handle daily goals across timezones?~~ **RESOLVED:** Store period_start as DATE in user's local timezone
- [x] ~~Soft deletes: Should we preserve deleted goal history?~~ **RESOLVED:** Yes, use soft deletes (deleted_at)
- [x] ~~Input validation: How to validate metric_type-specific values?~~ **RESOLVED:** Zod schemas with metric_type checking
- [x] ~~N+1 queries: How to fetch group data efficiently?~~ **RESOLVED:** Use JOINs or bulk queries with in-memory grouping
- [ ] Email service: Resend vs SendGrid vs AWS SES?
- [ ] WebSocket: Should we add real-time updates beyond FCM?
- [ ] Caching strategy: Redis vs in-memory LRU cache?
- [ ] Background jobs: Bull vs Agenda vs native Cloud Tasks?
- [ ] File uploads: Where to store avatars? (Cloud Storage)
- [ ] Analytics: Track API usage, popular features?
- [ ] Backup strategy: Daily snapshots vs continuous archiving?

---

**End of Backend Server Specification**

**Version:** 1.0  
**Status:** Ready for Implementation  
**Estimated Development Time:** 6-8 weeks
