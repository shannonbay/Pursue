---
name: project-overview
description: Pursue project - monorepo with Android app (pursue-app/), Express backend (pursue-server/), and marketing site (pursue-web/ submodule).
---

# Pursue Project Overview

Group accountability app for tracking personal goals. Three sub-projects:

## pursue-app/ - Android App (Kotlin)
- Jetpack Compose, Material Design 3
- Retrofit + OkHttp for networking
- Room/SQLite for offline caching
- Firebase Cloud Messaging for push notifications
- JWT auth stored in Android Keystore

## pursue-server/ - Backend API (Node.js/TypeScript)
- Express.js + PostgreSQL 17 (Kysely ORM)
- JWT + Google OAuth authentication
- Zod validation, bcrypt password hashing
- Endpoints: `/api/auth/*`, `/api/groups/*`, `/api/goals/*`, `/api/progress/*`

## pursue-web/ - Marketing Website (Git Submodule)
- Repo: https://github.com/shannonbay/pursue-web.git
- Astro + Tailwind CSS, hosted on Cloudflare Pages
- Domain: getpursue.app

## Key Documentation
- `specs/Spec.md` - Main spec
- `specs/Pursue-UI-Spec.md` - UI spec
- `specs/Pursue-Backend-Server-Spec.md` - Backend spec

## Infrastructure
- Backend: Google Cloud Run + Supabase
- Website: Cloudflare Pages
- Push: Firebase Cloud Messaging
