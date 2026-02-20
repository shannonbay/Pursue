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

