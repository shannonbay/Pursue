---
name: project-overview
description: Use when researching or editing the Pursue project which is this folder, a git monorepo containing three sub-projects: the Android app (pursue-app/), backend express.js server (pursue-server/), and marketing website (pursue-web/).
---

# Pursue Project Overview

This monorepo contains three sub-projects that together make up the Pursue application - a group accountability app for tracking personal goals.

## Repository Structure

### 1. pursue-app/ - Android Mobile App

**Purpose:** Native Android application for end users to track goals and share progress with accountability groups.

**Tech Stack:**
- Language: Kotlin
- UI Framework: Jetpack Compose (Material Design 3)
- Local Database: SQLite via Room (for offline caching)
- Networking: Retrofit + OkHttp
- Authentication: JWT tokens stored in Android Keystore
- Push Notifications: Firebase Cloud Messaging (FCM)

**Key Features:**
- Goal tracking with multiple cadences (daily, weekly, monthly, yearly)
- Group accountability - share progress with trusted groups
- Offline-capable with local SQLite cache and upload queue
- Google Sign-In and email/password authentication
- Colorblind-friendly blue and gold color scheme

**Documentation:**
- `specs/Spec.md` - Main technical specification
- `specs/Pursue-UI-Spec.md` - Complete UI/UX specification

### 2. pursue-server/ - Backend API Server

**Purpose:** Centralized backend that stores all data, handles authentication, and manages real-time sync.

**Tech Stack:**
- Runtime: Node.js 20.x
- Language: TypeScript 5.x
- Framework: Express.js
- Database: PostgreSQL 17.x
- ORM: Kysely (type-safe query builder)
- Authentication: JWT + Google OAuth
- Validation: Zod
- Password Hashing: bcrypt

**Key Endpoints:**
- `/api/auth/*` - Authentication (register, login, Google OAuth, password reset)
- `/api/groups/*` - Group management (create, invite, join)
- `/api/goals/*` - Goal CRUD operations
- `/api/progress/*` - Progress tracking
- `/health` - Health check

### 3. pursue-web/ - Marketing Website

**Purpose:** Marketing site to convert visitors to app downloads, explain the product, handle invite deep links, and host legal/support content.

**Tech Stack:**
- Framework: Astro 4.x
- Styling: Tailwind CSS 3.x
- Language: TypeScript
- Hosting: Cloudflare Pages
- Domain: getpursue.app

**Key Pages:**
- Landing page with app store download links
- Invite handler at `/invite/*` for deep linking
- Legal pages (privacy policy, terms of service)
- Support content

## Architecture Overview

The system uses a **centralized client-server architecture**:

1. **Server is source of truth** - All data stored in PostgreSQL
2. **Simple sync** - Android app uses REST API (GET/POST) to server
3. **Real-time updates** - Server pushes notifications via FCM
4. **Offline support** - Android app caches data locally in SQLite

## Development Workflow

When working across repositories:
- Backend changes in `pursue-server/` may require corresponding Android client updates in `pursue-app/`
- API endpoint changes should be reflected in both server routes and Android Retrofit interfaces
- Database schema changes require updating `pursue-server/schema.sql` and creating a migration in `pursue-server/migrations`

## Infrastructure

- **Backend Hosting:** Google Cloud Run (serverless, auto-scaling)
- **Database:** Cloud SQL for PostgreSQL
- **Website Hosting:** Cloudflare Pages
- **Push Notifications:** Firebase Cloud Messaging
