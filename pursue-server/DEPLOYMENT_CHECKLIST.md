# Security Updates Deployment Checklist

**Version:** 1.2.1+security
**Date:** 2026-03-01
**Reviewer:** _____________
**Approver:** _____________

---

## Pre-Deployment (48 hours before)

- [ ] **Notify team** of security deployment window
- [ ] **Schedule maintenance window** (if needed) - typically 15-30 minutes
- [ ] **Backup production database**
  ```bash
  gcloud sql backups create --instance=pursue-db --description="Pre-security-update backup"
  ```
- [ ] **Review all changes** - Read SECURITY_IMPLEMENTATION.md
- [ ] **Verify environment variables** are set correctly on Cloud Run

---

## Pre-Deployment Testing (24 hours before)

### Local Testing ✅
- [ ] Run all tests pass: `npm test`
- [ ] Security tests pass: `npm test -- tests/security/`
- [ ] TypeScript compilation succeeds: `npm run build`
- [ ] No npm audit vulnerabilities: `npm audit`

### Staging Deployment
- [ ] Deploy to staging environment
- [ ] Run smoke tests on staging
- [ ] Test all 7 internal job endpoints manually
- [ ] Verify password complexity enforcement
- [ ] Test token invalidation on user deletion

### Code Review Approval
- [ ] Security lead approval
- [ ] Engineering lead approval
- [ ] QA sign-off

---

## Deployment Day - Pre-Production

### 1. Final Verification (T-30min)
- [ ] Check current production metrics
  ```bash
  gcloud monitoring dashboards list | grep pursue
  gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=api" \
    --limit=10 --format=json
  ```
- [ ] Verify database is healthy: `gcloud sql instances describe pursue-db`
- [ ] Check Cloud Scheduler jobs are running: `gcloud scheduler jobs list`

### 2. Database Migrations (if any)
- [ ] Review schema changes: `cat pursue-server/migrations/*` (if any new migrations)
- [ ] All migrations are forward/backward compatible
- [ ] Rollback plan documented

### 3. Environment Variables (T-15min)
Verify these are set on Cloud Run:
- [ ] `INTERNAL_JOB_KEY` - Verified and secure
- [ ] `JWT_SECRET` - ≥32 characters
- [ ] `JWT_REFRESH_SECRET` - ≥32 characters
- [ ] `DATABASE_URL` - Points to correct production DB
- [ ] All other standard env vars present

Check:
```bash
gcloud run services describe api --region=us-central1 --format='value(spec.template.spec.containers[0].env)'
```

---

## Production Deployment

### Step 1: Deploy Backend (T-0)
```bash
cd pursue-server

# Build and test locally first
npm run build
npm test

# Deploy to production
gcloud run deploy api \
  --source . \
  --region=us-central1 \
  --platform managed \
  --allow-unauthenticated \
  --memory 512Mi \
  --cpu 1 \
  --timeout 60s \
  --max-instances 100 \
  --set-env-vars INTERNAL_JOB_KEY=[your-key]
```

- [ ] Deployment initiated at: ___________
- [ ] Deployment completed successfully

### Step 2: Verify Deployment (T+5min)
- [ ] Cloud Run service is running and healthy
- [ ] No errors in Cloud Run logs:
  ```bash
  gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=api AND severity=ERROR" \
    --limit=10
  ```
- [ ] Health check endpoint responds:
  ```bash
  curl https://api.getpursue.app/health
  # Should return: {"status":"healthy",...}
  ```

### Step 3: Test Critical Endpoints (T+10min)

**Authentication Tests:**
- [ ] Registration with weak password fails:
  ```bash
  curl -X POST https://api.getpursue.app/api/auth/register \
    -H "Content-Type: application/json" \
    -d '{"email":"test@example.com","password":"weak",...}' \
    # Should return 400 with password complexity error
  ```

- [ ] Rate limiting on refresh:
  ```bash
  for i in {1..7}; do curl -X POST https://api.getpursue.app/api/auth/refresh \
    -H "Content-Type: application/json" \
    -d '{"refresh_token":"fake"}'; done
  # Should see 429 after attempt 5-6
  ```

**Authorization Tests:**
- [ ] Avatar endpoint requires auth:
  ```bash
  curl https://api.getpursue.app/api/users/random-id/avatar
  # Should return 401 (not 404)
  ```

- [ ] Subscription verify requires auth:
  ```bash
  curl -X POST https://api.getpursue.app/api/subscriptions/verify \
    -H "Content-Type: application/json" \
    -d '{"platform":"google_play","purchase_token":"test","product_id":"pursue_premium_annual"}'
  # Should return 401 (not 200)
  ```

**Internal Job Tests:**
- [ ] Calculate heat job works with valid key:
  ```bash
  curl -X POST https://api.getpursue.app/api/internal/jobs/calculate-heat \
    -H "X-Internal-Job-Key: [your-key]"
  # Should return 200 (or job-specific response)
  ```

- [ ] Heat job fails without key:
  ```bash
  curl -X POST https://api.getpursue.app/api/internal/jobs/calculate-heat
  # Should return 401
  ```

---

## Cloud Scheduler Configuration (T+15min)

**Update all 7 Cloud Scheduler jobs** with the X-Internal-Job-Key header:

Follow **CLOUD_SCHEDULER_SETUP.md** for detailed instructions.

Jobs to update:
- [ ] Calculate Heat - Add header
- [ ] Process Reminders - Add header
- [ ] Recalculate Patterns - Add header
- [ ] Update Effectiveness - Add header
- [ ] Challenge Statuses - Add header
- [ ] Challenge Pushes - Add header
- [ ] Weekly Recap - Add header

Verification:
```bash
# Check that jobs completed successfully after deployment
gcloud scheduler jobs describe calculate-heat-job --format='value(lastExecution)'
```

---

## Post-Deployment Monitoring (First Hour)

### Real-Time Monitoring (T+0 to T+60min)

**Cloud Run Logs:**
- [ ] No ERROR level logs
- [ ] No spike in error rate
- [ ] Response times normal

```bash
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=api" \
  --limit=100 --format=json | jq '.[] | select(.severity=="ERROR")'
```

**Cloud Monitoring:**
- [ ] CPU usage < 50%
- [ ] Memory usage < 70%
- [ ] Request latency < 1000ms
- [ ] Error rate < 0.1%

**Application Health:**
- [ ] User sign-ups working
- [ ] User authentication working
- [ ] Group operations working
- [ ] Goal logging working

### User-Facing Verification
- [ ] Mobile app can authenticate
- [ ] Web app can authenticate
- [ ] Desktop app can authenticate
- [ ] Users can create/join groups
- [ ] Users can log progress

---

## Post-Deployment (Next 24 Hours)

### Day 1 Monitoring
- [ ] Check every 4 hours for issues
- [ ] Monitor error rate trends
- [ ] Verify internal jobs are running on schedule:
  ```bash
  # Check each job ran successfully
  gcloud logging read "textPayload=~'calculate-heat' AND resource.type=cloud_run_revision" \
    --limit=5
  ```
- [ ] No user complaints reported

### Security Verification
- [ ] Password complexity enforcement working:
  - [ ] Test registration with weak password → fails
  - [ ] Test registration with strong password → succeeds

- [ ] Token invalidation working:
  - [ ] Delete user account
  - [ ] Try to use old token → 401

- [ ] Rate limiting working:
  - [ ] Rapid auth attempts → 429 after limit

- [ ] Internal jobs secure:
  - [ ] Job without header → 401
  - [ ] Job with header → succeeds

### Performance Baseline
- [ ] Document response times:
  - [ ] User login: _________ ms
  - [ ] Group creation: _________ ms
  - [ ] Goal logging: _________ ms

- [ ] Compare to pre-deployment baseline
- [ ] Accept < 5% performance degradation

---

## Rollback Plan

If critical issues occur:

### Immediate Rollback (If Needed)
1. **Identify the issue:**
   ```bash
   gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=api AND severity=ERROR"
   ```

2. **Rollback to previous version:**
   ```bash
   gcloud run deploy api --image gcr.io/pursue-prod/api:previous-stable-version
   ```

3. **Restore from backup (if data issues):**
   ```bash
   gcloud sql backups restore [BACKUP-ID] --backup-instance=pursue-db
   ```

4. **Notify team and users**

### Issues Requiring Rollback
- [ ] Complete API outage (no responses)
- [ ] Database corruption detected
- [ ] Security breach detected
- [ ] > 50% error rate
- [ ] Auth system completely broken

### Non-Rollback Issues
- [ ] Minor performance degradation → Monitor and optimize
- [ ] Specific endpoint issues → Targeted fix and redeploy
- [ ] User confusion → Documentation and support

---

## Sign-Off

**Deployment Completed:**
- Date: _________________
- Time: _________________
- Deployed by: _________________
- Verified by: _________________

**Post-Deployment Monitoring (24h):**
- Completed by: _________________
- Issues found: ☐ None ☐ Minor ☐ Critical
- Notes: _________________________________________________________

**Final Approval:**
- Engineering Lead: _________________ Date: _______
- Ops/DevOps: _________________ Date: _______
- Security Lead: _________________ Date: _______

---

## Appendix: Quick Commands

### Check Deployment Status
```bash
gcloud run describe api --region=us-central1
gcloud logging read "resource.type=cloud_run_revision" --limit=20
```

### Monitor Performance
```bash
# View dashboard
gcloud monitoring dashboards list
gcloud monitoring time-series list --filter='resource.type="cloud_run_revision"'
```

### Test Endpoints
```bash
# Health check
curl https://api.getpursue.app/health

# Register
curl -X POST https://api.getpursue.app/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Test123!@#","display_name":"Test","consent_agreed":true}'

# Login
curl -X POST https://api.getpursue.app/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"Test123!@#"}'
```

### View Logs
```bash
# Real-time logs
gcloud logging read "resource.type=cloud_run_revision AND resource.labels.service_name=api" \
  --limit=50 --follow

# Error logs only
gcloud logging read "resource.type=cloud_run_revision AND severity=ERROR" \
  --limit=20

# Search for specific text
gcloud logging read "resource.type=cloud_run_revision AND textPayload=~'ERROR_MESSAGE'" \
  --limit=10
```

---

*Deployment Checklist v1.0*
*Last Updated: 2026-03-01*
