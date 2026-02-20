# Pursue - Goal Tracking App



**Version:** 0.2 (Centralized Architecture)  

**Platform:** Android  

**Architecture:** Centralized server with offline support



---



## Project Overview



Pursue is an Android mobile application that enables individuals to form accountability groups for tracking and sharing progress on personal goals (daily, weekly, monthly, yearly). The system uses a **centralized backend server** for data storage and synchronization, prioritizing simplicity, speed, and user experience while maintaining privacy-conscious design.



### Key Features



- **Centralized Architecture**: Server stores all data for instant sync and reliability

- **Simple Authentication**: Email + password OR Google Sign-In

- **Goal Tracking**: Daily, weekly, monthly, and yearly goals with progress tracking

- **Group Accountability**: Share progress with trusted groups, see others' achievements

- **Real-Time Updates**: Instant sync via REST API + push notifications

- **Offline-Capable**: View cached data offline, queue updates for when online

- **Privacy-Conscious**: Clear data policies, no ads, optional future E2E encryption

- **Colorblind-Friendly**: Blue and gold color scheme accessible to all users



---



## Documentation



This project contains two comprehensive specification documents:



### 1. [Spec.md](./Spec.md) - Main Technical Specification

The core architecture and design document covering:

- System architecture (centralized client-server model)

- Identity management (email/password + seed phrase recovery)

- Group management (create, invite, join)

- Goal tracking (cadences, progress entries)

- REST API design and endpoints

- Data storage (PostgreSQL server-side, SQLite client cache)

- Security model (JWT auth, authorization rules)

- Deployment guide (Google Cloud Run + Cloud SQL)



**Read this first** to understand the overall system design.



### 2. [Pursue-UI-Spec.md](specs/Pursue-UI-Spec.md) - User Interface Specification

Complete UI/UX design specification:

- Design system (colors, typography, spacing, components)

- Navigation structure and information architecture

- Detailed screen specifications (30+ screens)

- User flows (sign up, create group, join group, log progress)

- Accessibility guidelines (WCAG 2.1 Level AA)

- Loading and error states

- Push notification designs



**For designers and frontend developers** building the Android app.



---



## Quick Start Guide



### For Product Managers

1. Read [Spec.md](./Spec.md) sections 1-5 for product overview

2. Review [Pursue-UI-Spec.md](specs/Pursue-UI-Spec.md) sections 4-6 for user flows

3. Check Section 14 (Open Questions) in Spec.md for decisions needed



### For Backend Developers

1. Read [Spec.md](./Spec.md) sections 2-9 for complete architecture

2. Start with PostgreSQL schema (Section 4-5)

3. Implement REST API endpoints (Section 9)

4. Set up FCM push notifications (Section 6)

#### PostgreSQL Database Setup

Create the database and run the schema:

```bash
# Create the pursue_db database
psql -U postgres -c "CREATE DATABASE pursue_db;"

# Run the schema.sql file
psql -U postgres -d pursue_db -f schema.sql

# Connect to the database
psql -U postgres -d pursue_db

# List all tables (inside psql)
\dt
```



### For Frontend/Android Developers

1. Read [Spec.md](./Spec.md) sections 1-3 for system understanding

2. Study [Pursue-UI-Spec.md](specs/Pursue-UI-Spec.md) for complete UI specifications

3. Start with authentication screens (Section 4.1)

4. Implement local caching strategy (Section 7 in Spec.md)

#### Google Sign-In Setup

Get the SHA-1 fingerprint for Google Cloud Console:

```bash
# For debug keystore (default location)
keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android

# For Windows (PowerShell)
keytool -list -v -keystore $env:USERPROFILE\.android\debug.keystore -alias androiddebugkey -storepass android -keypass android
```

Copy the SHA-1 fingerprint and add it to your Android OAuth client in Google Cloud Console. Also ensure you're using the **Web Application Client ID** (not Android Client ID) for `requestIdToken()` in Google Sign-In configuration.



### For Designers

1. Review [Pursue-UI-Spec.md](specs/Pursue-UI-Spec.md) in full

2. Focus on design system (Section 2) and color palette

3. Create high-fidelity mockups based on layouts provided

4. Design error and empty states


### Restarting the Android Studio Emulator if it gets stuck
Open Android Studio
Go AVD Manager
Click the red square
If the device is stopped in a bad state, this may persist when relaunching the emulator. To get around this, simply select 'Cold Boot Now' from the virtual device's drop down menu in the manager.



---

### Phase 3: Android App - Core UI (3-4 weeks)

- Authentication screens (sign up, sign in)

- Home/Groups list

- Group detail (goals, members, activity)

- Log progress

- Local caching (SQLite)



### Phase 4: Real-Time & Polish (2-3 weeks)

- FCM push notifications

- Offline queue (pending uploads)

- Activity feed

- Profile settings

- Seed phrase backup flows



### Phase 5: Beta Testing (2-4 weeks)

- Bug fixes

- Performance optimization

- Security audit

- Load testing



**Total MVP Timeline: 3-4 months**



---



## Technology Stack



### Android App

- **Language**: Kotlin

- **UI Framework**: Jetpack Compose (Material Design 3)

- **Database**: SQLite (Room) for local cache

- **Networking**: Retrofit + OkHttp

- **Authentication**: JWT tokens (Android Keystore)

- **Push Notifications**: Firebase Cloud Messaging (FCM)



### Backend Server

- **Runtime**: Node.js 20.x + TypeScript

- **Framework**: Express.js

- **Database**: PostgreSQL (Cloud SQL)

- **Authentication\*\*: JWT (access + refresh tokens)

- **Push**: Firebase Admin SDK

- **Deployment**: Google Cloud Run



### Infrastructure

- **Hosting**: Google Cloud Run (serverless, auto-scaling)

- **Database**: Cloud SQL for PostgreSQL

- **Monitoring**: Google Cloud Logging & Monitoring

- **Cost**: ~$20-30/month for MVP (1K users)



---



## Architecture Principles



### Centralized for Simplicity

- **Server is source of truth**: All data stored in PostgreSQL

- **Simple sync**: Client GET/POST to server, no complex P2P logic

- **Instant updates**: Server pushes to all devices via FCM

- **Offline support**: Local SQLite cache for viewing, queue for uploads



### Privacy & Security

- **HTTPS only**: TLS 1.3 for all API communication

- **JWT authentication**: Secure token-based auth

- **Authorization**: Server enforces group membership and permissions

- **No ads**: Clear data policies, no selling user data

- **Future E2E encryption**: Optional "Privacy Mode" as premium feature



### Scalability

- **Serverless deployment**: Auto-scales with demand

- **Efficient database**: Indexed queries, connection pooling

- **Cost-effective**: Pay per use, scales to zero when idle

- **Future-proof**: Can add E2E encryption without architecture change



---



## Key Design Decisions



### Why Centralized (vs P2P)?



**Pros:**

- ✅ **50% faster development** (3-4 months vs 6-9 months)

- ✅ **Better UX** (instant sync, familiar pattern)

- ✅ **Simpler codebase** (no sync protocol, conflict resolution)

- ✅ **Easy to test** (standard client-server testing)

- ✅ **Easy to add features** (server-side analytics, recommendations)



**Cons:**

- ❌ **Server cost** (~$30/month for MVP vs ~$10/month for P2P relay)

- ❌ **Trust required** (server can see data unless E2E encrypted)

- ❌ **Single point of failure** (server down = app doesn't work)



**Decision:** Benefits outweigh costs. Can add E2E encryption later as "Privacy Mode."



### Why No Seed Phrases in MVP?



**For MVP, we've removed user-level seed phrases** because:

- Email password reset handles account recovery

- Google Sign-In users have Google account recovery

- Simpler sign-up flow = faster development + better UX

- Most users don't need cryptographic recovery phrases



**Post-MVP: Private Groups with Group-Level Seed Phrases**



After MVP, we'll add optional "Private Groups" feature:

- Group creator enables "Privacy Mode" for sensitive groups

- Generate 12-word seed phrase for the GROUP (not user)

- Group data encrypted end-to-end (server cannot read)

- Members share seed phrase for group recovery

- Clear value proposition: "Protect this group's data"

- Premium feature or power-user opt-in



### Why Colorblind-Friendly Colors?



- ~8% of men have color vision deficiency

- Blue/gold distinguishable to all users

- Conveys professionalism and trust

- Aligns with Material Design



---



## API Overview



### Authentication

- `POST /api/auth/register` - Create account

- `POST /api/auth/login` - Sign in

- `POST /api/auth/refresh` - Refresh token

- `POST /api/auth/recover` - Recover with seed phrase



### Groups

- `POST /api/groups` - Create group

- `GET /api/groups/{id}` - Get group details

- `POST /api/groups/{id}/invites` - Generate invite code

- `POST /api/groups/join` - Join via invite code



### Goals & Progress

- `POST /api/groups/{group_id}/goals` - Create goal

- `POST /api/progress` - Log progress

- `GET /api/goals/{id}/progress` - Get progress for goal



See [Spec.md Section 9](./Spec.md#9-api-reference) for complete API reference.



---



## Cost Estimates



### MVP (1,000 users)

- Cloud Run: $5-10/month

- Cloud SQL: $15-20/month

- **Total: $20-30/month**



### Growth (10,000 users)

- Cloud Run: $20-40/month

- Cloud SQL: $35-50/month

- **Total: $55-90/month**



### Scale (100,000 users)

- Cloud Run: $100-200/month

- Cloud SQL: $85-150/month

- **Total: $185-350/month**



---


## Getting line count

```powershell
Get-ChildItem -Path . -Recurse -Filter *.kt |
    Get-Content |
    Measure-Object -Line
```


## Contributing



This is currently a design-phase project. Once development begins:



1. Follow the specifications in this documentation

2. Report unclear/ambiguous requirements as issues

3. Propose changes via pull requests with rationale

4. Update specs when architecture decisions change



---

## How to test weekly recap?

Run server in test with test-internal-job-key

`$env:NODE_ENV='test'; $env:INTERNAL_JOB_KEY='test-internal-job-key'; npm run dev`

Then curl (use most recent Sunday as date YYYY-MM-DD)
```
curl -X POST http://localhost:3000/api/internal/jobs/weekly-recap \
-H "Content-Type: application/json" \
-H "x-internal-job-key: test-internal-job-key" \
-d '{"forceGroupId": "8a752a57-cb94-40eb-a381-97928ea90e60", "forceWeekEnd": "2026-02-16"}'
```

## How to animate Animated Vector Drawings (AVDs)
Draw the before and after .svg in InkScape, then import to shapeshifter
https://shapeshifter.design/

## Archived Documents



- `Spec-P2P-Archive.md` - Original P2P architecture specification

- `Pursue-UI-Spec-P2P-Archive.md` - Original P2P UI specification



These are kept for reference but are not the current design.



---



## License



To be determined (TBD)



---



## Contact



Project Lead: Shannon  

Questions: TBD  

Feedback: TBD



---



**Last Updated**: January 16, 2026  

**Status**: Design & Specification Phase  

**Architecture**: Centralized (v0.2)

