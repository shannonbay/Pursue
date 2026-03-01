# Red Team Security Testing - Manual Checklist

This document contains a comprehensive checklist for manual security testing of the Pursue backend API. These tests require specialized tools like Burp Suite, OWASP ZAP, SQLMap, and other penetration testing utilities.

**Date Started:** 2026-03-01
**API Endpoint:** https://api.getpursue.app
**Scope:** All API endpoints

---

## Prerequisites

Before beginning security testing:

- [ ] Burp Suite Professional installed and configured
- [ ] OWASP ZAP installed (alternatively to Burp)
- [ ] SQLMap installed for automated SQL injection testing
- [ ] testssl.sh downloaded for TLS/SSL testing
- [ ] Nikto installed for web server scanning
- [ ] Dedicated test account created on production (separate from dev)
- [ ] Team notified of testing schedule
- [ ] Testing windows coordinated (off-peak hours recommended)
- [ ] All testing authorized by stakeholders
- [ ] Backup of production database confirmed

---

## OWASP Top 10 2025 Manual Testing

### A01 - Broken Access Control

**Horizontal Privilege Escalation (IDOR)**
- [ ] Use Burp Suite Repeater to access `/api/users/{userA_id}` with userB's token
- [ ] Attempt to modify `/api/groups/{groupA_id}` without being a member
- [ ] Try to delete `/api/groups/{groupA_id}/members/{otherUser_id}` as regular member
- [ ] Test parameter pollution: `/api/users/{userA_id}?user_id={userB_id}`
- [ ] Proxy all requests through Burp and test each ID parameter with other users' IDs
- [ ] Document all accessible resources across multiple user accounts

**Vertical Privilege Escalation**
- [ ] Attempt to promote self from member to admin via API
- [ ] Try to modify group settings as non-admin member
- [ ] Intercept premium feature requests and modify request to simulate premium tier
- [ ] Test permission bypass via HTTP method override (POST to DELETE endpoint)

**Function-Level Access Control**
- [ ] Enumerate all `/api/internal/*` endpoints
- [ ] Attempt to access internal job endpoints without proper authentication
- [ ] Test if internal endpoints can be called by regular authenticated users
- [ ] Verify IP allowlist for Cloud Run internal IPs is enforced

---

### A02 - Cryptographic Failures

**SSL/TLS Configuration**
- [ ] Run `testssl.sh --full https://api.getpursue.app`
  - [ ] Check for TLS 1.0 or 1.1 (should be disabled)
  - [ ] Verify TLS 1.2+ is enforced
  - [ ] Test for weak cipher suites
  - [ ] Verify certificate chain is valid
  - [ ] Check HSTS header is set
  - [ ] Test for certificate pinning implementation
- [ ] Verify no unencrypted HTTP traffic is allowed
- [ ] Test SSL Labs rating (target: A or A+)

**Password Hashing**
- [ ] Extract user from test database and verify password hash
- [ ] Check hash format matches bcrypt pattern: `$2[aby]$\d{2}$...`
- [ ] Verify bcrypt cost factor is between 10-12
- [ ] Verify password cannot be brute-forced with common wordlists

**JWT Security**
- [ ] Decode JWT with jwt.io and analyze claims
- [ ] Verify 'alg' field uses HS256 or RS256 (not 'none')
- [ ] Check token expiration times (access: ~1h, refresh: ~30d)
- [ ] Verify tokens don't contain sensitive data (passwords, tokens, emails)
- [ ] Test if changing algorithm to 'none' bypasses verification
- [ ] Test if modifying payload is caught by signature verification

---

### A03 - Injection

**SQL Injection (SQLMap)**
- [ ] Run SQLMap on all GET parameters: `sqlmap -u "https://api.getpursue.app/api/..." --batch`
- [ ] Test common injection points:
  - [ ] `/api/discover?search=*` (text search)
  - [ ] `/api/groups?category=*` (filters)
  - [ ] `/api/progress?goal_id=*` (foreign keys)
- [ ] Test POST body parameters with SQLMap
- [ ] Verify all queries are parameterized (use Burp response analysis)

**Command Injection**
- [ ] Attempt shell metacharacters in text fields: `; whoami`, `| cat`, `& ls`
- [ ] Test file names: `../../etc/passwd`, `..\\..\\windows\\system32`
- [ ] Payload: `test$(whoami)`, test\`id\`

**NoSQL Injection**
- [ ] Test MongoDB operators: `{$ne: null}`, `{$gt: ""}`
- [ ] Payload: `{"email": {"$ne": null}}`
- [ ] If GraphQL is used, test for injection vectors

**XSS (Cross-Site Scripting)**
- [ ] Test display_name field: `<img src=x onerror="alert(1)">`
- [ ] Test group_name: `<script>alert('xss')</script>`
- [ ] Test in JSON context: verify JSON context prevents execution
- [ ] Use Burp Scanner's XSS detection
- [ ] Payload repository: `/assets/payloads/xss-payloads.txt`

---

### A04 - Insecure Design

**Authentication Bypass**
- [ ] Test password reset token reuse
- [ ] Attempt password reset without token
- [ ] Test if refresh token can be used as access token
- [ ] Verify email verification is required (if applicable)
- [ ] Test brute force on password reset endpoint (timing attack)

**Business Logic Flaws**
- [ ] Create more than 10 groups as free user (should be blocked)
- [ ] Add more than 50 members to group (should be blocked)
- [ ] Create more than 100 goals per group (should be blocked)
- [ ] Downgrade subscription and verify group limits are enforced
- [ ] Test duplicate goal creation (should prevent identical goals)
- [ ] Race condition: simultaneously create two groups and verify limits

**Account Takeover**
- [ ] Verify password reset invalidates all existing sessions
- [ ] Check if deleted user's sessions are invalidated immediately
- [ ] Test session fixation: login before/after CSRF token generation
- [ ] Verify logout removes refresh token from database

---

### A05 - Security Misconfiguration

**CORS Configuration**
- [ ] Test CORS preflight with various origins:
  - [ ] `https://attacker.com` (should fail)
  - [ ] `https://getpursue.app.attacker.com` (should fail, not substring match)
  - [ ] `null` origin from sandboxed iframe
- [ ] Verify `Access-Control-Allow-Credentials: true` not set with wildcard origin
- [ ] Test OPTIONS method responses

**HTTP Security Headers**
- [ ] Verify `X-Content-Type-Options: nosniff`
- [ ] Verify `X-Frame-Options: DENY` or `SAMEORIGIN`
- [ ] Verify `Strict-Transport-Security` header
- [ ] Verify `Content-Security-Policy` header (if frontend)
- [ ] Check `X-XSS-Protection` header (deprecated but useful)
- [ ] Verify no `Server` header leaking software versions
- [ ] Check `Permissions-Policy` header

**HTTP Methods**
- [ ] Test TRACE method: `curl -X TRACE https://api.getpursue.app/`
- [ ] Test OPTIONS method responses
- [ ] Verify PUT, DELETE methods require authentication

**Information Disclosure**
- [ ] Check `/health` endpoint doesn't expose sensitive info
- [ ] Verify error messages don't leak database structure
- [ ] Check `robots.txt` for sensitive paths
- [ ] Look for `.git`, `.env`, `.DS_Store` files
- [ ] Verify no directory listing
- [ ] Check for default credentials (admin/admin, etc.)

---

### A06 - Vulnerable and Outdated Components

**Dependency Analysis**
- [ ] Run `npm audit` and review results
- [ ] Check for known vulnerabilities in dependencies:
  - [ ] Kysely version for known issues
  - [ ] JWT library version security
  - [ ] Express.js version (should be latest)
  - [ ] Bcrypt implementation version
- [ ] Review security advisories for all dependencies
- [ ] Check for typosquatting in package names
- [ ] Verify all dependencies are from official npm registry

**API Versioning**
- [ ] Check if old API versions still exist and accept requests
- [ ] Verify deprecated endpoints are disabled

---

### A07 - Authentication Failures

**Brute Force Testing**
- [ ] Attempt 100 login attempts with wrong password
  - [ ] Check rate limiting triggers after N attempts
  - [ ] Verify account doesn't lock (or logs attempts)
- [ ] Test across multiple accounts (different email addresses)
- [ ] Test with valid/invalid username combinations
- [ ] Measure response time differences for valid/invalid users

**Concurrent Authentication**
- [ ] Use threading tool to send 10 simultaneous refresh token requests
- [ ] Verify only 1 succeeds, others fail gracefully
- [ ] Check database shows single-use token logic enforced
- [ ] Test scenario where both succeed (race condition flaw)

**Session Management**
- [ ] Verify session timeout after inactivity
- [ ] Confirm refresh token expiration after 30 days
- [ ] Test if multiple concurrent sessions are allowed
- [ ] Verify logout from one device invalidates only that session
- [ ] Check if deleted user's session is invalidated immediately

**JWT Manipulation**
- [ ] Change algorithm from HS256 to RS256 (if library allows)
- [ ] Change algorithm to 'none'
- [ ] Modify expiration to far future
- [ ] Modify user_id to different user
- [ ] Remove signature portion
- [ ] Use modified signature with original payload
- [ ] Test JWKS endpoint for signature verification bypass

---

### A08 - Software and Data Integrity

**Data Validation**
- [ ] Test each input field with various data types:
  - [ ] Numbers in string fields
  - [ ] Strings in number fields
  - [ ] Special characters in all fields
  - [ ] Null/undefined values
  - [ ] Very large values (10MB string)
- [ ] Use Burp Scanner's validation bypass module
- [ ] Test field length limits (try exceeding max length)
- [ ] Test required vs optional field enforcement

**Concurrency Tests**
- [ ] Simultaneously modify same group from two users
- [ ] Race: delete user while session is active
- [ ] Create identical goals concurrently
- [ ] Add same member to group twice concurrently
- [ ] Use load testing tool: `ab` or `wrk` for concurrent operations

**Transaction Safety**
- [ ] Verify partial failures don't leave inconsistent state
- [ ] Check if group creation failure doesn't create orphaned data

---

### A09 - Security Logging and Monitoring

**Log Verification**
- [ ] Trigger failed login and verify it's logged
- [ ] Trigger authorization failure and verify logging
- [ ] Confirm logs include:
  - [ ] Timestamp
  - [ ] User ID
  - [ ] Action
  - [ ] Source IP
  - [ ] Result (success/failure)
- [ ] Verify sensitive data not in logs (passwords, tokens)
- [ ] Check log retention policy

**Monitoring**
- [ ] Verify alerts exist for:
  - [ ] Multiple failed login attempts
  - [ ] Repeated authorization failures
  - [ ] Account deletion
  - [ ] Admin actions
  - [ ] Rate limit violations
- [ ] Simulate suspicious activity and verify alerts trigger

---

### A10 - Server-Side Request Forgery & Error Handling

**Error Message Analysis**
- [ ] Trigger 400 errors and review messages for information leakage
- [ ] Trigger 404 errors and verify generic messages
- [ ] Trigger 500 errors and verify no stack traces
- [ ] Intentionally send malformed requests and check error handling
- [ ] Verify all error responses have consistent format

**File Upload Security**
- [ ] Upload image with embedded malicious code (EXIF, polyglot)
- [ ] Upload WebP with embedded JavaScript
- [ ] Upload file with double extension: `image.php.jpg`
- [ ] Test path traversal in filename: `../../etc/passwd`
- [ ] Verify file size limits are enforced
- [ ] Check uploaded files aren't executable

**SSRF Testing** (if applicable)
- [ ] Test any URL input fields with internal IPs
- [ ] Try `http://127.0.0.1:8080`, `http://metadata.google.internal/`
- [ ] Verify server doesn't fetch from private IP ranges
- [ ] Check if redirects are followed

---

## Automated Scanning Tools

### OWASP ZAP Scanning

```bash
# Full scan
zaproxy.sh -cmd \
  -quickurl https://api.getpursue.app \
  -quickout /tmp/zap-report.html

# API scanning
zaproxy.sh -cmd \
  -importurls tests/zap-urls.txt \
  -apikey-scan
```

### Nikto Web Server Scanning

```bash
nikto -h api.getpursue.app -output nikto-report.html
```

### SQLMap SQL Injection Testing

```bash
# Test login endpoint
sqlmap -u "https://api.getpursue.app/api/auth/login" \
  --data='{"email":"test@example.com","password":"password"}' \
  --headers="Content-Type: application/json" \
  --batch

# Test search endpoint
sqlmap -u "https://api.getpursue.app/api/discover?search=*" \
  -H "Authorization: Bearer TOKEN" \
  --batch
```

### testssl.sh TLS Testing

```bash
./testssl.sh --full --json=results.json https://api.getpursue.app
```

---

## Remediation Verification

After each vulnerability is reported and fixed:

- [ ] Verify patch is deployed to production
- [ ] Re-run automated scans to confirm fix
- [ ] Execute manual test case that previously failed
- [ ] Check for regression in related functionality
- [ ] Document fix in security changelog
- [ ] Request code review of fix before deployment

---

## Security Testing Findings Template

For each finding, document:

```markdown
### [VULNERABILITY_NAME]

**Severity:** Critical | High | Medium | Low

**CVSS Score:** X.X

**Description:**
[Detailed explanation of vulnerability]

**Proof of Concept:**
[Steps to reproduce]

**Impact:**
[Potential impact if exploited]

**Remediation:**
[Recommended fix]

**Status:** Open | Fixed | Acknowledged
```

---

## Approval and Sign-Off

- [ ] All tests completed
- [ ] Findings documented
- [ ] Critical vulnerabilities remediated
- [ ] Testing results reviewed by security team
- [ ] Sign-off by security lead: _________________ Date: _______
- [ ] Sign-off by engineering lead: _________________ Date: _______

---

## Continuous Security

After this assessment:

- [ ] Schedule monthly automated scans (OWASP ZAP, npm audit)
- [ ] Integrate security tests into CI/CD pipeline
- [ ] Monthly manual penetration testing review
- [ ] Quarterly full security assessment
- [ ] Annual third-party security audit
- [ ] 24/7 security monitoring and alerting active

---

## Resources

- OWASP Testing Guide: https://owasp.org/www-project-web-security-testing-guide/
- OWASP Top 10 2025: https://owasp.org/Top10/
- Burp Suite Documentation: https://portswigger.net/burp/documentation
- SQLMap User Manual: http://sqlmap.org/
- CWE Top 25: https://cwe.mitre.org/top25/

---

*Last Updated: 2026-03-01*
*Next Review: 2026-04-01*
