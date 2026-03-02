# Pursue App Release Notes

## Version 1.3.0 (Build 5)
**Release Date:** March 2, 2026

### ✨ New Features

#### Body-Teaming Focus Sessions
Group members can now co-work in real-time video focus sessions — a structured body-doubling experience with three phases:

**Lobby → Focus → Chit-Chat**
1. **Lobby** — participants join and see each other on camera before the timer starts
2. **Focus** — a timed deep-work block where everyone works silently together (mics muted by default)
3. **Chit-Chat** — a casual debrief after the focus timer ends

**Ad-hoc sessions**
- Any group member can start a focus session from the group detail screen
- Other members join the live session; the creator starts the timer when ready
- Session durations: 25, 50, or 90 minutes (90 min requires Premium)

**Scheduled slots**
- Schedule a future focus session with date, time, duration, and an optional note
- Group members can RSVP to upcoming slots
- 15-minute alarm reminder before a scheduled slot begins
- Personal "Sessions" tab in bottom navigation shows all upcoming slots across groups, grouped by day

**Video & audio**
- Live camera video (480×360 @ 15 fps) with a grid layout showing all participants
- Camera and microphone toggle controls during the session
- Graceful degradation if camera permission is denied (audio-only fallback)

**Real-time connectivity**
- WebSocket signaling for peer coordination (offer/answer/ICE relay, phase changes, presence)
- WebRTC mesh topology for direct peer-to-peer media streams

**Notifications**
- Push notification when a session starts in your group
- Push notification when a new slot is posted
- Push notification when 3+ members RSVP to a slot

### 🛠️ Technical Details

**Backend**
- 4 new database tables: `focus_sessions`, `focus_session_participants`, `focus_slots`, `focus_slot_rsvps`
- REST endpoints for sessions (create, join, start, end, leave) and slots (create, list, cancel, RSVP, un-RSVP, GET /me/slots)
- WebSocket signaling server for WebRTC peer coordination
- Rate limiting for session endpoints
- 52+ integration tests covering sessions, slots, and signaling

**Android**
- New bottom navigation tab: Sessions (FocusSlotsFragment)
- FocusSessionActivity with FLAG_SECURE for screen-recording protection
- FocusSessionFragment with 3-phase UI and WebRTC video grid
- SignalingClient (OkHttp WebSocket) and WebRtcManager (mesh peer connections)
- ScheduleSlotBottomSheet for creating future session slots
- SlotAlarmReceiver for 15-minute pre-session reminders (survives reboot)
- Group detail integration: "Start Session" / "Join Session" button, upcoming slots list
- SignalingE2ETest with end-to-end WebSocket test coverage

### 📱 Requirements

- **Android:** 6.0 (API 24) or higher
- **Backend:** Version 1.3.0 or later (required for focus session tables and WebSocket server)

### 🔄 Migration Notes

- New database migration adds 4 tables — run `20260301_body_teaming.sql`
- No breaking changes to existing functionality
- WebSocket server attaches to the existing HTTP server (no new port required)

---

## Version 1.2.1 (Build 4)
**Release Date:** March 1, 2026

### ✨ New Features

#### Password Requirements Checklist
- Sign-up form now shows a real-time checklist as you type your password, indicating which requirements are met:
  - At least 8 characters
  - Lowercase letter (a–z)
  - Uppercase letter (A–Z)
  - Number (0–9)
  - Special character (!@#$%^&*)
- Each requirement displays a green check when satisfied or a grey X when not
- The "Create Account" button remains disabled until all five requirements are met and passwords match
- Strength indicator (Weak / Medium / Strong) now only appears once all requirements are satisfied, avoiding the confusing state where a password reads "Strong" but the form cannot be submitted

### 📱 Requirements

- **Android:** 6.0 (API 24) or higher
- **Backend:** No backend changes required

---

## Version 1.2.0 (Build 3)
**Release Date:** February 28, 2026

### ✨ New Features

#### Expanded Language Support
Pursue now supports five major languages, bringing the app to users worldwide:

**French (Français)**
- Full French localization for all app screens and UI elements
- Casual, friendly tone with native French terminology
- 820 app strings + 68 templates + 80 goals translated
- Supports regional French variants (France, Canada, Belgium, Switzerland) with automatic fallback to base French
- Complete coverage of:
  - Goal creation and progress logging ("Ziel" / "Groupe" terminology)
  - Group management and member collaboration
  - Discover public groups and templates
  - User profile, settings, and notifications

**German (Deutsch)**
- Full German localization across all app features
- Casual, accessible tone for all German speakers
- 820 app strings + 68 templates + 80 goals translated
- Supports regional German variants (Germany, Austria, Switzerland) with automatic fallback to base German
- Complete coverage of:
  - Goal and group workflows with native "Ziel" and "Gruppe" terminology
  - All template categories and challenge setups
  - Community features and notifications
  - User onboarding and authentication

**Mandarin Chinese (中文)**
- Full Mandarin Chinese localization for enhanced Asian market reach
- 820 app strings + 68 templates + 80 goals translated
- Supports regional variants with automatic fallback to base Chinese
- Comprehensive coverage of:
  - Goal setting and progress tracking
  - Group collaboration features
  - Template discovery and challenges
  - All user-facing strings and notifications

#### Backend Language Support
- All three languages available through backend API with language preference detection
- Automatic language fallback for regional variants (fr-CA → fr, de-AT → de, zh-TW → zh)
- 204+ new template and goal translations (68 templates × 3 languages, 80 goals × 3 languages)
- Language-aware API responses based on `Accept-Language` headers and app locale

### 🌍 Supported Languages
- English (en) - default
- Spanish (es)
- French (fr)
- German (de)
- Portuguese Brazilian (pt-BR)
- Mandarin Chinese (zh)

### 🛠️ Technical Improvements

- Enhanced locale configuration to support 6 languages with regional fallbacks
- Localized template discovery with language-specific category translations
- Improved language detection and automatic fallback logic
- Database migrations for multi-language template and goal translations

### 🐛 Bug Fixes

- Fixed "Fragment not attached to activity" crash when navigating away during async operations (photo upload, API calls)
  - Root cause: Calling `requireActivity()` after suspension points in async coroutine blocks
  - Solution: Capture activity reference before async operations, use lifecycle-aware helper function
  - Impact: Prevents crash in photo upload handler, progress logging, and all async UI updates
- Ensured consistent translation behavior across all app screens and languages
- Fixed language fallback chain for regional variants

### 📱 Requirements

- **Android:** 6.0 (API 24) or higher
- **Backend:** Version 1.2.0 or later (required for French, German, and Chinese translations)

### 🔄 Migration Notes

- No breaking changes to existing functionality
- Existing users see content in their device language automatically
- All five supported languages share the same backend and feature set
- Users can switch languages anytime via device settings

---

## Version 1.1.0 (Build 2)
**Release Date:** February 28, 2026

### ✨ New Features

#### Spanish Language Support
- Full Spanish (Español) localization for all app screens and UI elements
- Supports regional Spanish variants (Spain, Mexico, Argentina, etc.) with automatic fallback to neutral Spanish
- 960+ strings translated to Spanish covering:
  - Goal creation and progress logging
  - Group management and member invitations
  - Discover public groups and templates
  - User profile and settings
  - All onboarding and authentication flows
  - Push notifications and app messages

#### Backend i18n Infrastructure
- Language fallback system for regional dialect support (es-ES → es, pt-BR → pt)
- 80 group/goal templates now available in Spanish
- API returns translated content based on device language settings

### 🛠️ Technical Improvements

- Added language variant utility (`language.ts`) for handling BCP 47 language tags
- Enhanced template and goal APIs with language preference fallback logic
- Improved category filter translations in templates browser

### 🐛 Bug Fixes

- Fixed category filter chips not displaying translated text in challenges/templates browser
- Ensured consistent translation behavior across all app screens

### 📱 Requirements

- **Android:** 6.0 (API 24) or higher
- **Backend:** Version 1.1.0 or later (required for Spanish translations)

### 🔄 Migration Notes

- No breaking changes to existing functionality
- English and Portuguese users unaffected
- New Spanish users can use the entire app in their preferred language

---

## Version 1.0 (Build 1)
**Release Date:** February 2026

### Initial Release

First production release of Pursue with core functionality:
- Goal creation and progress tracking
- Group management and member collaboration
- Push notifications (FCM)
- User authentication (email, Google OAuth)
- Public group discovery
- Goal templates and challenges
- Portuguese language support (pt-BR)
