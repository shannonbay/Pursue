# Cloud Scheduler Setup for Internal Job Authentication

## Overview

The security update adds IP allowlist and header-based authentication to internal job endpoints. Cloud Scheduler jobs need a **single configuration change** to continue working.

**Change Required:** Add `X-Internal-Job-Key` header to all Cloud Scheduler jobs

---

## Configuration Changes

### What Changed in the Backend

Added `internalJobAuth` middleware to all `/api/internal/jobs/*` endpoints:

```typescript
// Requires both:
1. X-Internal-Job-Key header matching INTERNAL_JOB_KEY
2. Request from allowed IP (automatic for Cloud Run)
```

**Affected Endpoints:**
- `/api/internal/jobs/calculate-heat`
- `/api/internal/jobs/process-reminders`
- `/api/internal/jobs/recalculate-patterns`
- `/api/internal/jobs/update-effectiveness`
- `/api/internal/jobs/update-challenge-statuses`
- `/api/internal/jobs/process-challenge-completion-pushes`
- `/api/internal/jobs/weekly-recap`

---

## How to Update Cloud Scheduler

### Step 1: Get Your Internal Job Key

The `INTERNAL_JOB_KEY` is defined in your Cloud Run environment variables:

```bash
# View in Cloud Run service settings
# Or in your .env file: INTERNAL_JOB_KEY=your-key-here
```

### Step 2: Update Each Cloud Scheduler Job

For each job in [Google Cloud Console](https://console.cloud.google.com/cloudscheduler):

**1. Click the job name to edit it**

**2. Expand "Auth header" section**

**3. Configure custom header:**

- **OIDC token**: ✅ Keep enabled (for service account auth)
- **Add HTTP header**: Toggle **ON**
- **Header name**: `X-Internal-Job-Key`
- **Header value**: (your INTERNAL_JOB_KEY value)

**4. Click "Update"**

---

## Configuration Template

### Example: Calculate Heat Job

```yaml
Name: calculate-heat-job
Frequency: 0 * * * * (every hour)
Timezone: America/Los_Angeles
HTTP Target:
  URL: https://api.getpursue.app/api/internal/jobs/calculate-heat
  HTTP Method: POST
  Auth Header:
    - OIDC Token: ✅ Enabled
    - Add HTTP header:
      - Name: X-Internal-Job-Key
      - Value: [your-key]
```

### Example: Process Reminders Job

```yaml
Name: process-reminders-job
Frequency: */15 * * * * (every 15 minutes)
Timezone: America/Los_Angeles
HTTP Target:
  URL: https://api.getpursue.app/api/internal/jobs/process-reminders
  HTTP Method: POST
  Auth Header:
    - OIDC Token: ✅ Enabled
    - Add HTTP header:
      - Name: X-Internal-Job-Key
      - Value: [your-key]
```

---

## Complete Job List

Update these jobs (replace `[KEY]` with your INTERNAL_JOB_KEY):

| Job Name | URL | Frequency | Header |
|---|---|---|---|
| Heat Calculation | `/api/internal/jobs/calculate-heat` | Hourly | X-Internal-Job-Key: [KEY] |
| Process Reminders | `/api/internal/jobs/process-reminders` | Every 15 min | X-Internal-Job-Key: [KEY] |
| Recalculate Patterns | `/api/internal/jobs/recalculate-patterns` | Weekly | X-Internal-Job-Key: [KEY] |
| Update Effectiveness | `/api/internal/jobs/update-effectiveness` | Daily | X-Internal-Job-Key: [KEY] |
| Challenge Statuses | `/api/internal/jobs/update-challenge-statuses` | Every 30 min | X-Internal-Job-Key: [KEY] |
| Challenge Pushes | `/api/internal/jobs/process-challenge-completion-pushes` | Hourly | X-Internal-Job-Key: [KEY] |
| Weekly Recap | `/api/internal/jobs/weekly-recap` | Weekly | X-Internal-Job-Key: [KEY] |

---

## Verification

After updating your Cloud Scheduler jobs:

### 1. Check Job Execution Logs

```bash
# View Cloud Run logs
gcloud run services describe api --region=us-central1 --format='value(status.url)'

# Check for successful job executions in Cloud Run logs
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=api" \
  --limit=50 --format=json
```

### 2. Test a Job Manually

```bash
# Trigger a job manually from Cloud Scheduler
# Or use curl with the auth header:
curl -X POST https://api.getpursue.app/api/internal/jobs/calculate-heat \
  -H "X-Internal-Job-Key: your-key-here"
```

### 3. Monitor Job Metrics

In Cloud Console:
- Cloud Scheduler → Select job → View execution history
- Check "Last Execution" status
- Review execution details and logs

---

## IP Allowlist (Automatic)

**No changes needed for IP allowlist** - it's automatically enforced:

- Cloud Scheduler → Cloud Run uses Google Cloud internal network
- Requests come from IP range: `10.0.0.0/8` (automatically allowed)
- No additional configuration required

---

## Troubleshooting

### Job Returns 401 Unauthorized

**Cause:** Missing or incorrect `X-Internal-Job-Key` header

**Fix:**
1. Verify header is configured in Cloud Scheduler job
2. Check that header value matches `INTERNAL_JOB_KEY` in Cloud Run env vars
3. Restart Cloud Run service after env var changes

```bash
# Redeploy service to apply env changes
gcloud run deploy api --region=us-central1
```

### Job Returns 403 Forbidden

**Cause:** Request is not from Google Cloud internal network

**Fix:**
- Only Cloud Scheduler (within Google Cloud) can call these endpoints
- Public internet requests are blocked by design
- If testing locally, use the header-only approach (no IP check)

### Job Returns 404 Not Found

**Cause:** Endpoint URL is incorrect or endpoint doesn't exist

**Fix:**
- Verify URL matches exactly: `/api/internal/jobs/[jobname]`
- Check spelling and capitalization
- Ensure Cloud Run service is running and accessible

### Job Returns 500 Server Error

**Cause:** Issue with job processing, not authentication

**Fix:**
- Check Cloud Run logs for error details
- Review job-specific logic (heat calculation, reminder processing, etc.)
- Check database connectivity in Cloud Run logs

---

## Environment Variables Reference

Key environment variables for internal jobs:

```bash
# Required for authentication
INTERNAL_JOB_KEY=your-secure-key-here

# Database
DATABASE_URL=postgresql://user:pass@host:5432/pursue_db

# JWT (for generating tokens)
JWT_SECRET=your-jwt-secret
JWT_REFRESH_SECRET=your-jwt-refresh-secret

# GCP Services
GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account-key.json
```

---

## Rollback / Revert Changes

If you need to revert the security changes:

1. **Remove middleware from routes** (not recommended)
2. **Deploy previous backend version** with security changes removed
3. **Cloud Scheduler jobs will work without header** (but less secure)

**We recommend keeping the security enhancement** - it protects internal endpoints from unauthorized access.

---

## Questions?

For support with Cloud Scheduler configuration:

1. **Google Cloud Scheduler docs:** https://cloud.google.com/scheduler/docs/http-target-authentication
2. **Cloud Run security:** https://cloud.google.com/run/docs/securing/service-identity
3. **Internal endpoints best practices:** https://cloud.google.com/run/docs/securing/identities

---

*Last Updated: 2026-03-01*
*Backend Version: 1.2.0+*
