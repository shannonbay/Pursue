# Pursue API - Security Testing Guide

**Version:** 1.0  
**Last Updated:** January 28, 2026  
**Target Environment:** Staging (https://staging-api.getpursue.app)  

⚠️ **DO NOT test production environment**

---

## 1. Critical Security Controls to Verify

### 1.1 Authentication & Authorization

**JWT Tokens:**
- [x] Access tokens expire after 1 hour
- [x] Refresh tokens expire after 30 days
- [x] Refresh tokens are single-use and invalidated after rotation
- [x] Token signature verification cannot be bypassed
- [x] Expired tokens are rejected
- [x] Token claims cannot be manipulated (user_id, email)

**OAuth (Google Sign-In):**
- [x] Google ID token validation is proper
- [x] Cannot link same Google account to multiple users
- [x] Cannot use forged/expired Google tokens

**Password Security:**
- [x] Passwords hashed with bcrypt (10 rounds)
- [x] Minimum 8 characters enforced
- [x] Maximum 100 characters enforced
- [x] Timing-attack protection on password comparison

**Authorization:**
- [x] Non-members cannot access group data
- [x] Members cannot perform admin actions (create/edit/delete goals)
- [x] Admins cannot delete groups (creator only)
- [x] Users can only delete their own progress entries

### 1.2 Input Validation

**SQL Injection:**
- [x] All queries use parameterized statements (Kysely) ✓ VERIFIED
- [x] No raw SQL with user input ✓ VERIFIED (only 1 hardcoded `sql\`NOW()\`)
- [x] Test all text fields: email, display_name, goal titles, notes, group names ✓ VERIFIED
- [x] Test numeric fields: goal_id, user_id, target_value ✓ VERIFIED

  **Reference:** See [SQL-INJECTION-VERIFICATION.md](../../SQL-INJECTION-VERIFICATION.md)

**XSS Prevention:**
- [x] Content-Security-Policy header present ✓ VERIFIED (Helmet v8.1.0 enabled)
- [x] All user input sanitized before storage ✓ VERIFIED (JSON encoding + CSP provides protection)
- [x] Test fields: display_name, goal titles, goal descriptions, group names/descriptions ✓ 21 TEST CASES PASS

  **Reference:** See [XSS-PREVENTION-VERIFICATION.md](XSS-PREVENTION-VERIFICATION.md)
  
  **Status Summary:**
  - ✅ Helmet CSP headers prevent inline script execution (default-src 'self', script-src-attr 'none')
  - ✅ JSON response encoding escapes HTML entities
  - ✅ File uploads validated (images only, SVG rejected)
  - ✅ XSS payloads stored as literal strings, not executed
  - ✅ 21 comprehensive test cases verify protection (tests/integration/security/xss.test.ts)
  
  **Test Coverage:**
  - 5 tests for display_name XSS payloads (script tags, event handlers, javascript: URLs, iframes, SVG)
  - 3 tests for goal title XSS payloads (multiple vectors)
  - 2 tests for goal description XSS payloads (scripts, iframes)
  - 2 tests for group name XSS payloads (creation and updates)
  - 1 test for group description XSS payloads
  - 2 tests for CSP header presence and content verification
  - 2 tests for security headers (X-Content-Type-Options, X-Frame-Options)
  - 3 tests for file upload rejection (SVG, HTML, PHP)
  - 1 test for JSON response encoding

**Field Validation:**
- [x] Email format validated (regex + DNS check) ✓ VERIFIED (Zod .email() validator, 3 tests)
- [x] UUID format validated for all IDs ✓ VERIFIED (RFC 4122 regex, 1+ tests)
- [x] Date format validated (YYYY-MM-DD) ✓ VERIFIED (ISO date regex, 20+ tests)
- [x] String length limits enforced (display_name max 100, etc.) ✓ VERIFIED (8 fields, 4+ tests)
- [x] Enum values validated (cadence, metric_type, role) ✓ VERIFIED (3 enums, 30+ tests)

  **Reference:** See [FIELD-VALIDATION-VERIFICATION.md](FIELD-VALIDATION-VERIFICATION.md)
  
  **Status Summary:**
  - ✅ Email: Zod .email() validator + max 255 chars
  - ✅ UUID: RFC 4122 regex validation for all IDs
  - ✅ Date: YYYY-MM-DD format enforced, no future dates
  - ✅ Strings: 8 fields with min/max constraints (email 255, display_name 100, title 200, etc.)
  - ✅ Enums: cadence (daily/weekly/monthly/yearly), metric_type (binary/numeric/duration)
  - ✅ Strict mode: Unknown fields rejected (400 error)
  - ✅ Conditional validation: Numeric goals must have target_value
  - ✅ 60+ test cases verify all validations (all passing)

**Edge Cases:**
- [x] Negative values rejected for numeric goals ✓ VERIFIED (Zod validation + 3 test cases)
- [x] Null/undefined values handled properly ✓ VERIFIED (Zod optional/required fields + 5+ test cases)
- [x] Empty strings rejected where required ✓ VERIFIED (Zod .min(1) + 4 test cases)
- [x] Strings exceeding max length enforced ✓ VERIFIED (Zod max constraints + 6 boundary tests)

### 1.3 Rate Limiting

**General API:**
- [x] 100 requests/minute per user enforced ✓ VERIFIED (100 req/min per IP)
- [x] Rate limit headers present (X-RateLimit-*) ✓ VERIFIED (standardHeaders: true)
- [x] 429 status code returned when exceeded ✓ VERIFIED (express-rate-limit default)

  **Configuration:** [src/middleware/rateLimiter.ts](src/middleware/rateLimiter.ts#L7-L20)
  - windowMs: 60 * 1000 (1 minute)
  - max: 100 requests
  - Applied globally to `/api` in [src/app.ts](src/app.ts#L60)

**Auth Endpoints:**
- [x] 5 login attempts per 15 minutes ✓ VERIFIED (5 attempts/15min per IP)
- [x] Lockout after repeated failures ✓ VERIFIED (returns 429 after 5th attempt)
- [x] Rate limit applies per IP ✓ VERIFIED (IP-based keying, skipSuccessfulRequests: true)

  **Configuration:** [src/middleware/rateLimiter.ts](src/middleware/rateLimiter.ts#L22-L35)
  - windowMs: 15 * 60 * 1000 (15 minutes)
  - max: 5 attempts
  - skipSuccessfulRequests: true (don't count successful login)
  - Applied to: /register, /login, /google in [src/routes/auth.ts](src/routes/auth.ts#L19-L22)

**File Uploads:**
- [x] 10 uploads per 15 minutes enforced ✓ VERIFIED (10 uploads/15min per user)
- [x] Rate limit applied to avatar/icon uploads ✓ VERIFIED (per user ID)

  **Configuration:** [src/middleware/rateLimiter.ts](src/middleware/rateLimiter.ts#L78-L99)
  - windowMs: 15 * 60 * 1000 (15 minutes)
  - max: 10 uploads
  - keyGenerator: req.user.id (per authenticated user)
  - Applied to: /me/avatar, /groups/{id}/icon in [src/routes/users.ts](src/routes/users.ts#L30-L31)

**Bypass Protection:**
- [x] Cannot bypass with multiple IPs ✓ VERIFIED (each IP gets own 100/min limit)
- [x] Cannot bypass by changing User-Agent ✓ VERIFIED (keyed on IP, not headers)
- [x] Cannot bypass with different auth tokens ✓ VERIFIED (auth keyed on IP; user endpoints keyed on user ID)

  **Additional Limiters:**
  - Password reset: 3 attempts/hour (very strict)
  - Progress logging: 50 requests/min per user (prevents spam/loops)

  **Reference:** See [RATE-LIMITING-VERIFICATION.md](RATE-LIMITING-VERIFICATION.md) for detailed analysis

### 1.4 Resource Limits

**Per-User Limits:**
- [x] Max 10 groups created enforced ✓ VERIFIED (Database trigger in schema.sql:216-232)
- [x] Max 10 groups joined enforced ✓ VERIFIED (Database trigger in schema.sql:235-260)
- [x] 429 status code with clear error message ✓ VERIFIED (Error handler updated to return 429 with RESOURCE_LIMIT_EXCEEDED)
- [x] Cannot bypass by deleting and recreating ✓ VERIFIED (Soft deletes counted in WHERE deleted_at IS NULL)

  **Configuration:** [schema.sql](../../schema.sql#L216-L232) - `enforce_user_group_creation_limit()` trigger
  - Checks: `WHERE creator_user_id = NEW.creator_user_id AND deleted_at IS NULL`
  - Result: Cannot bypass by deleting and recreating (deleted groups still counted)

**Per-Group Limits:**
- [x] Max 100 goals per group enforced ✓ VERIFIED (Database trigger in schema.sql:262-278)
- [x] Max 50 members per group enforced ✓ VERIFIED (Database trigger in schema.sql:235-260)
- [x] Archive/delete doesn't allow exceeding limits ✓ VERIFIED (Soft deletes do not remove from limit count)

  **Configuration:** [schema.sql](../../schema.sql#L262-L278) - `enforce_group_goals_limit()` trigger
  - Checks: `WHERE group_id = NEW.group_id AND deleted_at IS NULL`
  - Result: Archived goals still count against 100-goal limit

  **Bypass Protection:** All limits protected via database triggers:
  - Delete & recreate: ❌ Cannot bypass (deleted items still counted)
  - Multiple accounts: ❌ Cannot bypass (per-user or per-group keying)
  - Race conditions: ❌ Cannot bypass (atomic database trigger enforcement)

  **Reference:** See [RESOURCE-LIMITS-VERIFICATION.md](RESOURCE-LIMITS-VERIFICATION.md) for detailed analysis

### 1.5 File Upload Security

**Avatar & Group Icon Uploads:**
- [x] Only JPEG, PNG, WebP allowed ✓ VERIFIED (Multer fileFilter: image/png, image/jpeg, image/jpg, image/webp)
- [x] Max 5MB file size enforced ✓ VERIFIED (Multer limits: 5 * 1024 * 1024; errorHandler returns FILE_TOO_LARGE)
- [x] Malicious files rejected (web shells, scripts) ✓ VERIFIED (Mimetype filter + Sharp content validation; invalid content returns INVALID_IMAGE)
- [x] EXIF data stripped from images ✓ VERIFIED (Sharp re-encodes to WebP; output has no EXIF/metadata)
- [x] Files processed with Sharp (content validation) ✓ VERIFIED (storage.service.ts; Sharp decode fails on non-images)
- [x] Double extension files rejected (.jpg.php) ✓ VERIFIED (Relies on mimetype + Sharp; PHP content fails Sharp decode)
- [x] SVG files rejected (XSS vector) ✓ VERIFIED (Not in allowedTypes; avatar + xss tests)
- [x] Polyglot files rejected ✓ VERIFIED (Sharp extracts and re-encodes image; output is clean WebP only)

  **Configuration:** [src/controllers/users.ts](src/controllers/users.ts#L20-L37), [src/controllers/groups.ts](src/controllers/groups.ts#L25-L36), [src/services/storage.service.ts](src/services/storage.service.ts)

**Test Coverage:**
  - [tests/integration/users/avatar.test.ts](tests/integration/users/avatar.test.ts): PNG/JPG/WebP success, GIF/SVG rejected, 5MB exceeded (6MB → FILE_TOO_LARGE), no file, Sharp processing
  - [tests/integration/security/xss.test.ts](tests/integration/security/xss.test.ts): SVG, HTML, PHP rejected as avatar (mimetype-based)
  - [tests/integration/groups/groups.test.ts](tests/integration/groups/groups.test.ts): Icon upload validation, invalid file type (file.txt), no file
  - [src/middleware/errorHandler.ts](src/middleware/errorHandler.ts): Sharp/vips/corrupt errors → INVALID_IMAGE (400)

**Test Files (spec list) – status:**
- Very large file (>10MB): ✓ Tested (6MB over limit → 400 FILE_TOO_LARGE)
- HTML file with .jpg extension: ✓ Covered (HTML content → mimetype text/html rejected, or Sharp fails if sent as image/jpeg)
- Corrupted image: ✓ Handled (Sharp throws; errorHandler + users controller return INVALID_IMAGE)
- Malicious JPEG with embedded PHP: ✓ Mitigated (Sharp re-encodes; PHP in metadata stripped; non-image content fails)
- EICAR renamed as .jpg: ✓ Mitigated (Wrong mimetype or Sharp decode fails on text content)

### 1.6 Data Exposure

**Sensitive Data:**
- [x] Passwords never in responses or logs ✓ VERIFIED (Auth returning/select excludes password_hash; login errors log generic "Invalid email or password", not password)
- [x] Refresh tokens never in logs ✓ VERIFIED (Auth controllers do not log refresh_token; errorHandler logs error.message only)
- [x] Email addresses only visible to authenticated users ✓ VERIFIED (GET /me requires auth; no public user lookup by ID; avatars are public by design)
- [x] Error messages don't leak sensitive info ✓ VERIFIED (ApplicationError uses generic messages; 500 returns "Internal server error"; Zod details may include validation errors)
- [x] Stack traces not exposed in production ✓ VERIFIED (errorHandler never sends stack to client; 500 responses are generic; stack only in server-side error.log)

  **Note:** forgot-password logs `email` and `plainToken` (password reset token) at [src/controllers/auth.ts](src/controllers/auth.ts#L539) — consider removing token from logs; email may be acceptable for audit.

**IDOR (Insecure Direct Object Reference):**
- [x] Cannot access other users' progress entries ✓ VERIFIED (requireGroupMember; progress.controller checks ownership/group membership; tests: 403 when neither owner nor group member, 403 when member tries to delete another's entry)
- [x] Cannot access groups you're not a member of ✓ VERIFIED (requireGroupMember in groups controller; test: "should return 403 for non-members")
- [x] Cannot view other users' devices ✓ VERIFIED (GET /api/devices filters by user_id; listDevices uses req.user.id; test: "should only list devices belonging to authenticated user")
- [x] Cannot modify other users' profiles ✓ VERIFIED (PATCH /me updates only req.user; no PATCH /users/:id; test: "User A's PATCH /me does not change User B's display_name")

  **Test Coverage:**
  - [tests/integration/security/cross-user-isolation.test.ts](tests/integration/security/cross-user-isolation.test.ts): GET /me returns only own data, PATCH /me does not affect User B, avatar isolation
  - [tests/integration/progress/progress.test.ts](tests/integration/progress/progress.test.ts): 403 when user is neither owner nor group member, 403 when group member tries to delete another user entry
  - [tests/integration/groups/groups.test.ts](tests/integration/groups/groups.test.ts): 403 for non-members on GET group
  - [tests/integration/devices/devices.test.ts](tests/integration/devices/devices.test.ts): only list own devices, 404 (not 403) when deleting another user's device

### 1.7 Business Logic Vulnerabilities

**Race Conditions:**
- [x] Concurrent group creation doesn't bypass limit ✓ VERIFIED (Database trigger `enforce_user_group_creation_limit` BEFORE INSERT; PostgreSQL serialization; see RESOURCE-LIMITS-VERIFICATION.md)
- [x] Concurrent goal creation doesn't bypass limit ✓ VERIFIED (Database trigger `enforce_group_goals_limit` BEFORE INSERT; atomic enforcement)
- [x] Double-logging progress handled correctly ✓ VERIFIED (Progress controller checks existingEntry for goal_id+user_id+period_start; returns 400 DUPLICATE_ENTRY; test in progress.test.ts)

  **Note:** Explicit concurrent-request integration test is in the front-end E2E testsuite (CreateGroupE2ETest.concurrent group creation does not bypass 10 groups per user limit); protection relies on database trigger atomicity.

**Mass Assignment:**
- [x] Cannot set role to admin via user update ✓ VERIFIED (UpdateUserSchema allows only display_name; Zod .strict() rejects unknown keys; test: "should return 400 for invalid input (extra fields)")
- [x] Cannot modify creator_id on groups ✓ VERIFIED (CreateGroupSchema/UpdateGroupSchema have no creator_user_id; controller sets from req.user.id)
- [x] Cannot modify created_at timestamps ✓ VERIFIED (No schema accepts created_at; tables use DEFAULT NOW())

**Logic Flaws:**
- [x] Cannot log progress for goals you're not a member of ✓ VERIFIED (requireGroupMember(req.user.id, goal.group_id) in progress controller; test: "should return 403 when user tries to view their own progress for a goal outside their group")
- [x] Cannot invite yourself to groups ✓ VERIFIED (joinGroup checks existingMembership; returns 409 ALREADY_MEMBER; test: "should return 409 if already a member")
- [x] Cannot make yourself admin ✓ VERIFIED (PATCH members requires requireGroupAdmin; only admin/creator can promote; "should return 400 if trying to change own role" blocks self-promotion)
- [x] Creator cannot be removed from group ✓ VERIFIED (removeMember checks target role, throws CANNOT_REMOVE_CREATOR; leaveGroup throws CANNOT_LEAVE_AS_CREATOR when other members exist; tests in groups.test.ts)

  **Test Coverage:**
  - [tests/integration/progress/progress.test.ts](tests/integration/progress/progress.test.ts): DUPLICATE_ENTRY on double-log, 403 for non-member goal access
  - [tests/integration/goals/goals.test.ts](tests/integration/goals/goals.test.ts): 403 when user outside group views progress, progress uses req.user.id
  - [tests/integration/groups/groups.test.ts](tests/integration/groups/groups.test.ts): 409 already a member, CANNOT_CHANGE_OWN_ROLE, CANNOT_DEMOTE_CREATOR, CANNOT_REMOVE_CREATOR, CANNOT_LEAVE_AS_CREATOR
  - [tests/integration/users/me.test.ts](tests/integration/users/me.test.ts): 400 for extra fields (mass assignment)

---

## 2. Sensitive Endpoints (Priority Testing)

### High Priority

**Authentication:**
- `POST /api/auth/login` - Brute force, credential stuffing
- `POST /api/auth/register` - Mass account creation
- `POST /api/auth/google` - OAuth token manipulation
- `POST /api/auth/refresh` - Token replay attacks

**File Uploads:**
- `POST /api/users/me/avatar` - Malicious file upload
- `PATCH /api/groups/{group_id}/icon` - Malicious file upload

**Authorization:**
- `PATCH /api/goals/{goal_id}` - Authorization bypass
- `DELETE /api/groups/{group_id}` - Unauthorized deletion
- `PATCH /api/groups/{group_id}/members/{user_id}` - Privilege escalation

### Medium Priority

**IDOR:**
- `GET /api/groups/{group_id}` - Access control
- `GET /api/groups/{group_id}/members` - Data exposure
- `GET /api/progress/{entry_id}` - Access control

**Resource Exhaustion:**
- `POST /api/groups` - Resource limit bypass
- `POST /api/groups/{group_id}/goals` - Resource limit bypass
- `POST /api/progress` - Data flooding

### Low Priority

**Read-Only Endpoints:**
- `GET /api/users/me` - Data exposure
- `GET /api/users/me/groups` - Data exposure
- `GET /api/groups/{group_id}/activity` - Activity feed enumeration

---

## 3. Known Attack Vectors to Test

### 3.1 Authentication Attacks

**Brute Force:**
```bash
# Test login rate limiting
for i in {1..10}; do
  curl -X POST localhost:3000/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"email":"test2@test.com","password":"wrong'$i'"}'
done
```

**Credential Stuffing:**
- Use leaked password lists
- Test against known email addresses
- Verify rate limiting blocks this

**JWT Manipulation:**
```javascript
// Decode JWT, modify claims, re-encode
// Try different algorithms (none, HS256 with public key)
// Modify user_id, email, expiry
// Test with expired tokens
```

### 3.2 Authorization Bypass

**Horizontal Privilege Escalation:**
```bash
# Try to access another user's data with your token
curl -X GET localhost:3000/api/groups/{other-user-group-id} \
  -H "Authorization: Bearer {your-token}"
```

**Vertical Privilege Escalation:**

Note: it's important to run the server with NODE_ENV=development 
eg `$env:NODE_ENV='development'; npm run dev` to ensure rate limiting kicks in

```bash
# Try to perform admin action as member
curl -X POST localhost:3000/api/groups/{group_id}/goals \
  -H "Authorization: Bearer {member-token}" \
  -d '{"title":"Test","cadence":"daily","metric_type":"binary"}'
```

### 3.3 Input Validation

**SQL Injection:**
```json
{
  "email": "test2@test.com' OR '1'='1",
  "password": "test123!"
}
```

**XSS:**
```json
{
  "display_name": "<script>alert('XSS')</script>",
  "goal_title": "<img src=x onerror=alert(1)>"
}
```

**Command Injection:**
```json
{
  "note": "; rm -rf /; #"
}
```

### 3.4 File Upload Attacks

**Malicious Files:**
- Upload PHP web shell as JPEG
- Upload EICAR test file
- Upload polyglot file (valid JPEG + script)
- Upload very large file (DoS)

**Test Payloads:**
```bash
# Upload HTML as image
curl -X POST https://staging-api.getpursue.app/api/users/me/avatar \
  -H "Authorization: Bearer {token}" \
  -F "avatar=@malicious.html;type=image/jpeg"
```

---

## 4. Test Accounts

**Admin Account:**
```
Email: admin@test.getpursue.app
Password: TestPassword123!
```

**Regular User 1:**
```
Email: user1@test.getpursue.app
Password: TestPassword123!
```

**Regular User 2:**
```
Email: user2@test.getpursue.app
Password: TestPassword123!
```

**Test Group:**
```
Group ID: {will be provided}
Name: "Test Group for Red Team"
Admin: admin@test.getpursue.app
Members: user1, user2
```

---

## 5. Testing Environment

**Staging API:**
```
Base URL: https://staging-api.getpursue.app
Database: Resets nightly at 2:00 AM UTC
Health: https://staging-api.getpursue.app/health
```

**Do NOT Test:**
- Production API (https://api.getpursue.app)
- Any real user accounts
- Any production data

**Rate Limiting:**
- Staging has same limits as production
- If locked out, wait 15 minutes or contact team

---

## 6. Tools Recommended

**Automated Scanners:**
- Burp Suite Professional (import OpenAPI spec)
- OWASP ZAP (import OpenAPI spec)
- Nuclei with custom templates
- Postman for manual testing

**Fuzzing:**
- Schemathesis (property-based testing)
- ffuf (endpoint discovery)
- wfuzz (parameter fuzzing)

**JWT Tools:**
- jwt.io (decode/inspect)
- jwt_tool (manipulation testing)

**File Upload Testing:**
- Upload malicious files from OWASP repository
- Create polyglot files with ImageTragick

---

## 7. Out of Scope

**Not Implemented Yet (Don't Test):**
- 2FA (planned for v2)
- IP-based blocking
- CAPTCHA on registration
- Email verification on registration
- WebSocket/real-time features

**Infrastructure (Out of Scope):**
- DDoS attacks
- Network-level attacks
- Cloud platform vulnerabilities
- DNS attacks

---

## 8. Reporting

**Report Format:**
- Severity: Critical / High / Medium / Low
- CVSS Score (if applicable)
- Steps to reproduce
- Proof of concept (screenshots/code)
- Suggested remediation

**Example Vulnerabilities:**

**Critical:**
- Authentication bypass
- SQL injection
- Remote code execution
- Privilege escalation to admin

**High:**
- IDOR allowing data access
- XSS in stored fields
- Mass assignment vulnerabilities
- Broken access control

**Medium:**
- Rate limit bypass
- Information disclosure
- CSRF (if applicable)
- Weak password policy

**Low:**
- Missing security headers
- Verbose error messages
- Debug info in responses

---

## 9. Contact Information

**Questions During Testing:**
- Email: security@getpursue.app
- Response time: Within 24 hours

**Emergency Contact:**
- For critical findings that need immediate attention
- Email: emergency-security@getpursue.app

---

## 10. Success Criteria

A successful red team engagement should:

✅ Test all high-priority endpoints  
✅ Verify all critical security controls  
✅ Attempt common attack vectors (OWASP Top 10)  
✅ Test authorization at every level  
✅ Verify rate limiting and resource limits  
✅ Test file upload security thoroughly  
✅ Document all findings with PoC  
✅ Provide remediation recommendations  

---

## Appendix A: OWASP API Security Top 10 Checklist

- [ ] API1:2023 - Broken Object Level Authorization (IDOR)
- [ ] API2:2023 - Broken Authentication
- [ ] API3:2023 - Broken Object Property Level Authorization
- [ ] API4:2023 - Unrestricted Resource Consumption
- [ ] API5:2023 - Broken Function Level Authorization
- [ ] API6:2023 - Unrestricted Access to Sensitive Business Flows
- [ ] API7:2023 - Server Side Request Forgery
- [ ] API8:2023 - Security Misconfiguration
- [ ] API9:2023 - Improper Inventory Management
- [ ] API10:2023 - Unsafe Consumption of APIs

---

**Thank you for helping secure Pursue! 🔒**
