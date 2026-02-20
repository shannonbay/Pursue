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

