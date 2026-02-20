# Google Sign-In Backend Implementation Guide

## Overview
This document provides essential information for implementing the `/api/auth/google` endpoint on the pursue-server backend.

## Endpoint Details

**URL:** `POST /api/auth/google`  
**Content-Type:** `application/json`

## Request Format

The Android app sends a POST request with the following JSON body:

```json
{
  "id_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6IjE4MmU0M..."
}
```

The `id_token` is a Google ID token (JWT) obtained from the Google Sign-In SDK after the user successfully authenticates.

## Response Format

**Success (200 OK or 201 Created):**

```json
{
  "access_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "refresh_token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "is_new_user": true,
  "user": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "email": "user@example.com",
    "display_name": "John Doe",
    "avatar_url": "https://lh3.googleusercontent.com/a/..."
  }
}
```

**Error Responses:**
- `400 Bad Request`: Invalid ID token format or missing required fields
- `401 Unauthorized`: Token verification failed (invalid signature, expired, wrong audience, etc.)

## Google Client ID Configuration

**Android Client ID:** `170178530674-hqsukacp5hkk656977l4lhcgd075t56r.apps.googleusercontent.com`

**Important:** The backend needs to verify the ID token's `aud` (audience) claim matches this client ID. However, for server-side verification, you typically need the **Web Application Client ID** (not the Android client ID).

The Android app uses the Android Client ID to request the ID token, but the backend should verify it against the Web Application Client ID that was configured in Google Cloud Console for your backend service.

## Backend Implementation Steps

1. **Receive Request**
   - Extract `id_token` from request body
   - Validate request format (Zod schema recommended)

2. **Verify Google ID Token**
   - Use `google-auth-library` (Node.js) to verify the token
   - Verify:
     - Token signature (cryptographic verification)
     - `aud` (audience) - should match your Web Application Client ID
     - `iss` (issuer) - should be `https://accounts.google.com` or `accounts.google.com`
     - `exp` (expiration) - token must not be expired
     - `sub` (subject) - Google user ID (required)
   - Extract user information:
     - `sub` → `provider_user_id` (Google user ID)
     - `email` → user email
     - `name` → display name
     - `picture` → avatar URL

3. **Handle User Account**
   - Check if `auth_providers` table has entry with:
     - `provider = 'google'`
     - `provider_user_id = sub` (from token)
   - **If exists:** Sign in existing user
   - **If not exists:**
     - Check if user exists by email (from token)
     - **If user exists:** Link Google account to existing user account
     - **If user doesn't exist:** Create new user account
       - Set `password_hash = NULL` (no password for Google-authenticated users)
       - Set `display_name` from token's `name`
       - Set `avatar_url` from token's `picture` (if available)

4. **Create/Update Auth Provider Entry**
   - Create or update entry in `auth_providers` table:
     - `user_id` → user's UUID
     - `provider` → `'google'`
     - `provider_user_id` → `sub` from token
     - `provider_email` → email from token

5. **Generate Tokens**
   - Generate JWT `access_token` (short-lived, e.g., 15 minutes)
   - Generate JWT `refresh_token` (long-lived, e.g., 7 days)
   - Store refresh token in database (for revocation support)

6. **Determine `is_new_user` Flag**
   - `is_new_user = true` if user account was just created in step 3
   - `is_new_user = false` if user account already existed

7. **Return Response**
   - Return tokens, user data, and `is_new_user` flag
   - Use `201 Created` if new user, `200 OK` if existing user

## Error Handling

**400 Bad Request:**
- Missing `id_token` in request body
- Invalid JSON format
- `id_token` is not a valid JWT format

**401 Unauthorized:**
- Token signature verification failed
- Token expired (`exp` claim)
- Wrong audience (`aud` claim doesn't match)
- Wrong issuer (`iss` claim)
- Token revoked or invalid

**500 Internal Server Error:**
- Database connection errors
- Token generation failures
- Unexpected errors during user creation/linking

## Database Schema Reference

**users table:**
- `id` (UUID, primary key)
- `email` (VARCHAR, unique)
- `display_name` (VARCHAR)
- `avatar_url` (TEXT, nullable)
- `password_hash` (TEXT, nullable) - NULL for Google-authenticated users
- `created_at` (TIMESTAMP)
- `updated_at` (TIMESTAMP)

**auth_providers table:**
- `id` (UUID, primary key)
- `user_id` (UUID, foreign key → users.id)
- `provider` (VARCHAR) - `'google'` for Google Sign-In
- `provider_user_id` (VARCHAR) - Google's `sub` claim
- `provider_email` (VARCHAR) - Email from Google token
- `created_at` (TIMESTAMP)

## Testing Considerations

1. **Test with valid Google ID token** - Should return tokens and user data
2. **Test with expired token** - Should return 401
3. **Test with invalid signature** - Should return 401
4. **Test with wrong audience** - Should return 401
5. **Test new user flow** - Should create user, return `is_new_user: true`
6. **Test existing user flow** - Should sign in, return `is_new_user: false`
7. **Test email linking** - If user exists by email but no Google auth_provider, should link accounts
8. **Test duplicate provider_user_id** - Should handle gracefully (sign in existing user)

## Security Notes

1. **Always verify the ID token server-side** - Never trust client-provided tokens without verification
2. **Use HTTPS only** - ID tokens contain sensitive user information
3. **Validate all token claims** - Don't skip any verification steps
4. **Rate limiting** - Consider rate limiting this endpoint to prevent abuse
5. **Logging** - Log authentication attempts (but don't log full tokens in production)

## Additional Resources

- Google ID Token Verification: https://developers.google.com/identity/sign-in/web/backend-auth
- google-auth-library (Node.js): https://github.com/googleapis/google-auth-library-nodejs
- Backend Spec: See `app/Pursue-Backend-Server-Spec.md` Section 3.1, POST /api/auth/google
