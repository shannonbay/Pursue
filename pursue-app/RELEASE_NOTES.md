# Pursue App Release Notes

## Version 1.3.1 (Build 6)
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

#### Expanded Language Support
Pursue now supports five major languages, bringing the app to users worldwide:

**Spanish (Español)**
- Full Spanish localization for all app screens and UI elements
- Supports regional Spanish variants (Spain, Mexico, Argentina, etc.) with automatic fallback to neutral Spanish
- 960+ strings translated covering all goal, group, discovery, profile, and onboarding flows

**French (Français)**
- Full French localization for all app screens and UI elements
- Casual, friendly tone with native French terminology
- 820 app strings + 68 templates + 80 goals translated
- Supports regional French variants (France, Canada, Belgium, Switzerland)

**German (Deutsch)**
- Full German localization across all app features
- Casual, accessible tone for all German speakers
- 820 app strings + 68 templates + 80 goals translated
- Supports regional German variants (Germany, Austria, Switzerland)

**Mandarin Chinese (中文)**
- Full Mandarin Chinese localization for enhanced Asian market reach
- 820 app strings + 68 templates + 80 goals translated
- Supports regional variants with automatic fallback to base Chinese

### 🌍 Supported Languages
- English (en) — default
- Spanish (es)
- French (fr)
- German (de)
- Portuguese Brazilian (pt-BR)
- Mandarin Chinese (zh)

### 🛠️ Technical Details

**Backend**
- 4 new database tables: `focus_sessions`, `focus_session_participants`, `focus_slots`, `focus_slot_rsvps`
- REST endpoints for sessions (create, join, start, end, leave) and slots (create, list, cancel, RSVP, un-RSVP, GET /me/slots)
- WebSocket signaling server for WebRTC peer coordination
- Rate limiting for session endpoints
- 52+ integration tests covering sessions, slots, and signaling
- Language fallback system for regional dialect support (es-ES → es, fr-CA → fr, de-AT → de, zh-TW → zh)
- 204+ new template and goal translations (68 templates × 3 languages, 80 goals × 3 languages)
- Language-aware API responses based on `Accept-Language` headers and app locale
- Added language variant utility (`language.ts`) for handling BCP 47 language tags

**Android**
- New bottom navigation tab: Sessions (FocusSlotsFragment)
- FocusSessionActivity with FLAG_SECURE for screen-recording protection
- FocusSessionFragment with 3-phase UI and WebRTC video grid
- SignalingClient (OkHttp WebSocket) and WebRtcManager (mesh peer connections)
- ScheduleSlotBottomSheet for creating future session slots
- SlotAlarmReceiver for 15-minute pre-session reminders (survives reboot)
- Group detail integration: "Start Session" / "Join Session" button, upcoming slots list
- SignalingE2ETest with end-to-end WebSocket test coverage
- Enhanced locale configuration to support 6 languages with regional fallbacks
- Localized template discovery with language-specific category translations

### 🐛 Bug Fixes

- **Challenge QR code scan** — scanning a Challenge QR code via the Join Group FAB no longer shows "Invalid invite code". The URL path `/challenge/PURSUE-XXX-XXX` is now recognised alongside the existing `/join/` and `/invite/` paths.
- **Fragment not attached to activity** — fixed a crash when navigating away during async operations (photo upload, API calls). Root cause was calling `requireActivity()` after suspension points; fix captures activity reference before async work.
- **Category filter chips** — fixed translated text not displaying in the challenges/templates browser.

### 📱 Requirements

- **Android:** 6.0 (API 24) or higher
- **Backend:** Version 1.3.0 or later (required for focus session tables and WebSocket server)

### 🔄 Migration Notes

- New database migration adds 4 tables — run `20260301_body_teaming.sql`
- No breaking changes to existing functionality
- WebSocket server attaches to the existing HTTP server (no new port required)
- Existing users see content in their device language automatically; language can be changed anytime via device settings

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
