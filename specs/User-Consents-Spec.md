# User Consents Specification

## Overview

This specification defines how Pursue collects, stores, versions, and retains user consent for the Terms of Service and Privacy Policy. The system must satisfy three requirements: (1) block sign-up without explicit consent, (2) prompt existing users to re-consent when policies update, and (3) retain proof of consent after account deletion without storing PII.

---

## Design Principles

1. **No Account Without Consent**: Registration (email or Google) is rejected unless the user agrees to current policies
2. **Versioned Records**: Each consent row stores the policy version it applies to, not just a boolean
3. **Re-Consent on Update**: When policy versions change, existing users are prompted on next app launch
4. **Ghost Link on Deletion**: When a user deletes their account, a SHA-256 hash of their email is written into consent records before the user row is removed — preserving a verifiable link without PII
5. **Single Source of Truth for Versions**: Policy versions are defined in `pursue-web/public/config.json` and consumed by both the backend and the Android app

---

## Policy Version Source

A single JSON file hosted on the marketing site controls the minimum required policy versions:

```
pursue-web/public/config.json
```

```json
{
  "min_required_terms_version": "Feb 11, 2026",
  "min_required_privacy_version": "Feb 11, 2026"
}
```

Version strings use the format `MMM dd, yyyy` (e.g., `Feb 11, 2026`). This is a human-readable date representing when the policy was last updated. Comparison is done by parsing the date — a newer date means a newer version.

### Who reads this file

| Consumer | How | When |
|----------|-----|------|
| Backend (`policyConfig.ts`) | HTTP GET `https://getpursue.app/config.json`, falls back to local `../pursue-web/public/config.json` | On first use (cached in memory for process lifetime) |
| Android (`PolicyConfigManager`) | HTTP GET `https://getpursue.app/config.json`, falls back to SharedPreferences cache | On app launch (background thread) |

---

## Database Schema

```sql
CREATE TABLE user_consents (
  id UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
  user_id UUID REFERENCES users(id) ON DELETE SET NULL,
  consent_type VARCHAR(50) NOT NULL,
  agreed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
  ip_address VARCHAR(45),
  email_hash VARCHAR(64)  -- SHA-256 of email + salt, set on account deletion
);

CREATE INDEX idx_user_consents_user_id ON user_consents(user_id);
```

### Column details

| Column | Purpose |
|--------|---------|
| `user_id` | FK to `users(id)` with `ON DELETE SET NULL` — becomes NULL after account deletion |
| `consent_type` | Freeform string encoding the policy type and version, e.g. `terms Feb 11, 2026` or `privacy policy Feb 11, 2026` |
| `agreed_at` | Timestamp when the user agreed |
| `ip_address` | IP address at time of consent (from `req.ip`) |
| `email_hash` | NULL while the account exists; populated with `SHA-256(email + CONSENT_HASH_SALT)` just before account deletion |

### consent_type format

The `consent_type` column uses a prefix + version date format:

- `terms <version>` — e.g. `terms Feb 11, 2026`
- `privacy policy <version>` — e.g. `privacy policy Feb 11, 2026`

This allows querying all consent records for a specific policy type and comparing versions by parsing the date suffix.

---

## Consent at Sign-Up

### Email registration (`POST /api/auth/register`)

The registration request requires `consent_agreed: true` (enforced by Zod `z.literal(true)`). Optional fields `consent_terms_version` and `consent_privacy_version` are accepted but the backend always stamps the current server-side version from `config.json`.

On successful registration, two `user_consents` rows are inserted atomically within the same transaction that creates the user:

```
terms <current_terms_version>
privacy policy <current_privacy_version>
```

If `consent_agreed` is not `true`, the request fails validation before any database writes.

### Google OAuth registration (`POST /api/auth/google`)

For new users (no existing account found by Google sub or email), the request must include `consent_agreed: true`. If missing, the backend returns `422 CONSENT_REQUIRED`.

For existing users signing in or linking Google to an existing account, consent is not re-required — they already consented during their original registration.

### Android sign-up UI

The `SignUpEmailFragment` displays a consent checkbox:

> I agree to the Terms of Service and Privacy Policy

The checkbox must be checked before the sign-up button is enabled. The links open the respective policy pages in a browser. On submission, the app sends `consent_agreed: true` along with the current policy versions from `PolicyConfigManager`.

---

## Re-Consent Flow

When policy versions in `config.json` are bumped, existing users who consented to an older version need to re-consent.

### Detection (Android)

On app launch, `MainAppActivity` runs a background check:

1. Fetches current policy versions from `PolicyConfigManager.getConfig()` (network with SharedPreferences fallback)
2. Fetches the user's consent records from `GET /api/users/me/consents`
3. Uses `PolicyDateUtils.needsReconsent()` to compare:
   - Filters consent records by prefix (`terms ` or `privacy policy `)
   - Parses the date from each matching record
   - If the user's latest consent date is **before** the required version date, re-consent is needed
   - If no matching records exist at all, re-consent is needed

### Prompt

If re-consent is needed, a blocking dialog (`dialog_consent_confirm.xml`) is shown:

> We've updated our Terms of Service and/or Privacy Policy. Please review and accept to continue.

The dialog has links to the updated policies and an "I Agree" button. Dismissing without agreeing exits the app.

### Recording

When the user taps "I Agree", the app calls `POST /api/users/me/consents` with the new consent types:

```json
{
  "consent_types": [
    "terms Feb 11, 2026",
    "privacy policy Feb 11, 2026"
  ]
}
```

New rows are appended to `user_consents` — old consent records are never deleted or modified.

---

## Consent API Endpoints

### `GET /api/users/me/consents`

Returns all consent records for the authenticated user, ordered by `agreed_at` descending.

**Response:**

```json
{
  "consents": [
    { "consent_type": "terms Feb 11, 2026", "agreed_at": "2026-02-11T..." },
    { "consent_type": "privacy policy Feb 11, 2026", "agreed_at": "2026-02-11T..." }
  ]
}
```

### `POST /api/users/me/consents`

Records new consent entries for the authenticated user.

**Request:**

```json
{
  "consent_types": ["terms Feb 11, 2026", "privacy policy Feb 11, 2026"]
}
```

**Validation:** Array of 1-10 strings, each max 50 characters. Strict mode (no extra fields).

**Response:** `201 { "success": true }`

### `POST /api/users/consent-hash` (test-only)

Computes the SHA-256 ghost link hash for a given email address. Used to look up consent records after account deletion (e.g., when handling a complaint).

**Guard:** Returns `404 NOT_FOUND` unless `NODE_ENV=test`. This endpoint is never exposed in production. To use it, run a local server instance with `NODE_ENV=test` and the same `CONSENT_HASH_SALT` as production, then call the endpoint without authentication.

**Request:**

```json
{
  "email": "user@example.com"
}
```

**Validation:** Valid email, max 255 characters. Strict mode (no extra fields).

**Response:**

```json
{
  "email_hash": "a1b2c3d4..."
}
```

**Usage:**

```bash
# Start a local server with NODE_ENV=test and production CONSENT_HASH_SALT
NODE_ENV=test CONSENT_HASH_SALT=<production-salt> npm start

# Look up the hash (no auth required)
curl -X POST http://localhost:3000/api/users/consent-hash \
  -H "Content-Type: application/json" \
  -d '{"email": "user@example.com"}'
```

The returned hash can be used to query orphaned consent records:

```sql
SELECT * FROM user_consents
WHERE email_hash = '<returned_hash>'
  AND user_id IS NULL;
```

---

## Ghost Link on Account Deletion

When a user deletes their account (`DELETE /api/users/me`), the following happens inside a single database transaction:

### Step 1: Hash email into consent records

```typescript
const emailHash = crypto
  .createHash('sha256')
  .update(user.email + process.env.CONSENT_HASH_SALT)
  .digest('hex');

await trx
  .updateTable('user_consents')
  .set({ email_hash: emailHash })
  .where('user_id', '=', userId)
  .execute();
```

### Step 2: Delete user row

```sql
SELECT delete_user_data(user_id);
```

The FK constraint `ON DELETE SET NULL` nullifies `user_id` on all consent rows. The result is consent records with:

- `user_id = NULL` (no FK link to any user)
- `email_hash = <64-char hex>` (verifiable ghost link)
- `consent_type`, `agreed_at`, `ip_address` preserved

### Verification use case

To check whether a given email address ever consented, use the `POST /api/users/consent-hash` endpoint (test-only) to compute the hash, then query:

```sql
SELECT * FROM user_consents
WHERE email_hash = '<hash from endpoint>'
  AND user_id IS NULL;
```

### Environment variable

`CONSENT_HASH_SALT` is required in all environments (enforced in `server.ts` `requiredEnvVars`). It must be a secret value that is never exposed to clients. Rotating the salt invalidates the ability to look up old ghost links, so it should be treated as permanent.

---

## Consent Record Lifecycle

```
Sign-up ──────────► [user_id=X, consent_type="terms Feb 11, 2026", email_hash=NULL]
                     [user_id=X, consent_type="privacy policy Feb 11, 2026", email_hash=NULL]

Policy updated ───► [user_id=X, consent_type="terms Mar 15, 2026", email_hash=NULL]  (appended)
(re-consent)        [user_id=X, consent_type="privacy policy Mar 15, 2026", email_hash=NULL]  (appended)

Account deleted ──► [user_id=NULL, consent_type="terms Feb 11, 2026", email_hash="a1b2c3..."]
                     [user_id=NULL, consent_type="privacy policy Feb 11, 2026", email_hash="a1b2c3..."]
                     [user_id=NULL, consent_type="terms Mar 15, 2026", email_hash="a1b2c3..."]
                     [user_id=NULL, consent_type="privacy policy Mar 15, 2026", email_hash="a1b2c3..."]
```

Old consent records are never deleted or overwritten. Each re-consent adds new rows. On deletion, all rows for that user receive the same `email_hash`.

---

## File Inventory

| File | Role |
|------|------|
| `pursue-web/public/config.json` | Single source of truth for policy versions |
| `pursue-server/src/utils/policyConfig.ts` | Async config reader — fetches from public site, local file fallback (cached for process lifetime) |
| `pursue-server/src/controllers/auth.ts` | Records consent during registration (email + Google) |
| `pursue-server/src/controllers/users.ts` | GET/POST consent endpoints, ghost link on deletion |
| `pursue-server/src/validations/auth.ts` | `consent_agreed`, version fields on register/google schemas |
| `pursue-server/src/validations/users.ts` | `RecordConsentsSchema` for POST consent endpoint |
| `pursue-server/src/routes/users.ts` | `/me/consents` route definitions |
| `pursue-server/schema.sql` | `user_consents` table with `email_hash` column |
| `pursue-server/migrations/add_user_consents.sql` | Migration for existing databases |
| `pursue-app/.../data/config/PolicyConfig.kt` | Data class for policy version config |
| `pursue-app/.../data/config/PolicyConfigManager.kt` | Fetches config from network with SharedPreferences fallback |
| `pursue-app/.../utils/PolicyDateUtils.kt` | Parses consent versions, compares dates for re-consent |
| `pursue-app/.../data/network/ApiClient.kt` | `getMyConsents()`, `recordConsents()` API methods |
| `pursue-app/.../ui/activities/MainAppActivity.kt` | Re-consent check on app launch |
| `pursue-app/.../ui/activities/OnboardingActivity.kt` | Consent during sign-up flow |
| `pursue-app/.../ui/fragments/onboarding/SignUpEmailFragment.kt` | Consent checkbox UI |
| `pursue-app/.../res/layout/dialog_consent_confirm.xml` | Re-consent dialog layout |
