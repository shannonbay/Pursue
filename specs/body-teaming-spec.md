# Body-Teaming Spec — Pursue

**Feature:** Group-scoped focus sessions with WebRTC mesh audio, Pomodoro-style focus timer, and a post-session chit-chat window.  
**Codename:** Body-Teaming  
**Status:** Draft v1.0

---

## 1. Overview

Body-teaming transforms a Pursue group into a virtual co-working room. Members join a shared audio session, run a focus timer together, and then drop into a brief chit-chat window when the block ends. Sessions are always scoped to a single group and are invisible to people outside it.

The feature has two modes:

- **Ad-hoc ("Go Live")** — any member starts a session instantly; the group gets a push notification. Others tap to join.
- **Scheduled** — members post availability slots in advance; the calendar aggregates slots across groups.

Sessions are audio-only. No video. This keeps the experience lightweight and less intimidating, and avoids the camera-fatigue problem that tanked a lot of pandemic-era tools.

---

## 2. Why This Fits Pursue

Pursue's existing features (nudges, reactions, photo proof) create accountability around *logging*. Body-teaming creates accountability around *doing the work in real time*. These are complementary, not competing.

Key differentiators from Focusmate/Flow Club:

- **You already know these people.** The social trust is established through the group's ongoing accountability relationship.
- **Goal-aligned.** The group that shares your running goals is the right group to run alongside virtually. Serendipitous matching across unrelated groups would dilute this.
- **Fits existing session patterns.** Members of the same group likely share time zone and schedule overlap, making spontaneous co-working much more likely.

---

## 3. Session Model

### 3.1 Session Phases

```
[LOBBY] → [FOCUS BLOCK] → [CHIT-CHAT] → [END]
         ↑ timer starts                ↑ auto-ends after chit-chat window
```

| Phase | Duration | Audio | Chat |
|-------|----------|-------|------|
| Lobby | Until host starts, or auto-start at scheduled time | Unmuted, casual | Text enabled |
| Focus Block | User-selected: 25 / 45 / 60 / 90 min | Muted (mic disabled) | Text disabled |
| Chit-Chat | Fixed: 10 min | Unmuted | Text enabled |
| End | — | Session closed | — |

During the focus block, microphones are **hard-muted by the session protocol** — not just UI-toggled. This removes the temptation to chat and signals intent. A prominent countdown timer is the primary UI element.

### 3.2 Roles

- **Host** — the member who creates the session (ad-hoc) or whose scheduled slot triggers the session. Can end the session early. If the host leaves, the next earliest-joined member becomes host.
- **Participant** — any group member who joins. Can leave at any time without ending the session.

### 3.3 Session Capacity & Overflow

Maximum 8 participants per session (mesh WebRTC constraint — beyond 6–8 peers, mesh degrades and an SFU would be required, which is out of scope for v1). Multiple concurrent sessions per group are fully supported. When a member joins and the active session already has 8 participants, they automatically become the host of a new parallel session for that group. Other members who arrive later will see both sessions listed and can choose which to join.

The group detail Focus Session card shows all active sessions when more than one exists:

```
┌─────────────────────────────────────────┐
│ 🎯  Focus Session                       │
│                                         │
│  Session 1 · [Avatar][Avatar][Avatar]+5 │
│  Lobby · 8 members                      │
│                              [ Join ]   │
│  ─────────────────────────────────────  │
│  Session 2 · [Avatar]                  │
│  Lobby · 1 member                      │
│                              [ Join ]   │
└─────────────────────────────────────────┘
```

This means no member is ever turned away — a late joiner seeds a new room rather than hitting a wall.

---

## 4. WebRTC Architecture

### 4.1 Library

Use **Stream WebRTC Android** (`io.getstream:stream-webrtc-android`) — a maintained precompiled WebRTC build with Jetpack Compose extensions and Kotlin coroutine support.

```kotlin
// build.gradle.kts
implementation("io.getstream:stream-webrtc-android:1.3.x") // check Maven Central for latest
implementation("io.getstream:stream-webrtc-android-ktx:1.3.x")
```

### 4.2 Signaling Server

Add a WebSocket signaling endpoint to the existing Node.js backend. The signaling server handles:

- Session creation and membership
- SDP offer/answer exchange between peers
- ICE candidate relay
- Presence (who is in the session, what phase is active)

**Signaling events (WebSocket messages):**

```typescript
// Client → Server
{ type: "join", sessionId: string, userId: string }
{ type: "offer", to: string, sdp: string }
{ type: "answer", to: string, sdp: string }
{ type: "ice-candidate", to: string, candidate: RTCIceCandidate }
{ type: "phase-change", phase: "focus" | "chit-chat" | "end" }  // host only
{ type: "leave", sessionId: string }

// Server → Client
{ type: "peer-joined", peerId: string, peerName: string, peerAvatar: string }
{ type: "peer-left", peerId: string }
{ type: "offer", from: string, sdp: string }
{ type: "answer", from: string, sdp: string }
{ type: "ice-candidate", from: string, candidate: RTCIceCandidate }
{ type: "phase-changed", phase: "focus" | "chit-chat" | "end", timer: { endsAt: ISO8601 } }
{ type: "session-ended" }
{ type: "session-full" }
```

The server does **not** relay audio — it only signals. Audio flows peer-to-peer.

### 4.3 STUN/TURN

Use Google's public STUN servers for development. For production, provision a TURN server (e.g. Cloudflare Calls TURN, or coturn on a small GCP VM) to handle NAT traversal for mobile networks. TURN is essential — mobile clients behind carrier-grade NAT will fail P2P without it.

### 4.4 Mesh Topology

Each client opens a `PeerConnection` to every other participant. For N participants: N×(N−1)/2 peer connections total. At 8 participants this is 28 connections, which is manageable for audio-only.

On focus block start, all clients call `audioTrack.enabled = false` locally and the server signals `phase-changed: focus`. The UI enforces the mute state and disables the mic toggle button.

---

## 5. Database Schema

### 5.1 New Tables

```sql
-- A session belongs to exactly one group
CREATE TABLE focus_sessions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  host_user_id UUID NOT NULL REFERENCES users(id),
  status TEXT NOT NULL CHECK (status IN ('lobby', 'focus', 'chit-chat', 'ended')),
  focus_duration_minutes INTEGER NOT NULL CHECK (focus_duration_minutes IN (25, 45, 60, 90)),
  started_at TIMESTAMPTZ,        -- when focus block began
  ended_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_focus_sessions_group ON focus_sessions(group_id, status);

-- Who joined and when
CREATE TABLE focus_session_participants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  session_id UUID NOT NULL REFERENCES focus_sessions(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id),
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  left_at TIMESTAMPTZ,
  UNIQUE(session_id, user_id)
);

-- Scheduled availability slots
CREATE TABLE focus_slots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id UUID NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
  created_by UUID NOT NULL REFERENCES users(id),
  scheduled_start TIMESTAMPTZ NOT NULL,
  focus_duration_minutes INTEGER NOT NULL CHECK (focus_duration_minutes IN (25, 45, 60, 90)),
  note TEXT,                     -- e.g. "Writing sprint 🖊️"
  session_id UUID REFERENCES focus_sessions(id),  -- populated when session starts
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  cancelled_at TIMESTAMPTZ
);

CREATE INDEX idx_focus_slots_group ON focus_slots(group_id, scheduled_start)
  WHERE cancelled_at IS NULL;

-- RSVP to a scheduled slot
CREATE TABLE focus_slot_rsvps (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  slot_id UUID NOT NULL REFERENCES focus_slots(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(slot_id, user_id)
);
```

---

## 6. API Endpoints

All endpoints require auth. All session/slot operations validate group membership.

### 6.1 Sessions

```
POST   /api/groups/:groupId/sessions               — Create ad-hoc session (starts in lobby)
GET    /api/groups/:groupId/sessions/active         — Get active session (if any)
POST   /api/groups/:groupId/sessions/:id/join       — Join session
POST   /api/groups/:groupId/sessions/:id/start      — Host starts focus block
POST   /api/groups/:groupId/sessions/:id/end        — Host ends session early
DELETE /api/groups/:groupId/sessions/:id/leave      — Leave session
```

### 6.2 Scheduled Slots

```
POST   /api/groups/:groupId/slots                  — Create a scheduled slot
GET    /api/groups/:groupId/slots                  — List upcoming slots
DELETE /api/groups/:groupId/slots/:id              — Cancel a slot (creator only)
POST   /api/groups/:groupId/slots/:id/rsvp         — RSVP to a slot
DELETE /api/groups/:groupId/slots/:id/rsvp         — Un-RSVP

GET    /api/me/slots                               — All upcoming slots across my groups (for calendar)
```

### 6.3 WebSocket

```
WS /ws/sessions/:sessionId?token=<jwt>
```

JWT validates group membership on connection. Connection rejected if user is not a group member or session is full.

---

## 7. Android UI

### 7.1 Entry Points

**A. Group Detail Fragment — Focus Session Card**

Appears below the goals list when the group has an active session or upcoming slots within 24 hours:

```
┌─────────────────────────────────────────┐
│ 🎯  Focus Session                       │
│                                         │
│  [Avatar] [Avatar] [Avatar]  in lobby   │
│  Jordan, Mia, +1 are waiting            │
│                                         │
│              [ Join Session ]           │
└─────────────────────────────────────────┘
```

If no active session:

```
┌─────────────────────────────────────────┐
│ 🎯  Focus Session                       │
│                                         │
│  No one's live right now.               │
│                                         │
│   [ Start a Session ]   [ Schedule ]    │
└─────────────────────────────────────────┘
```

If an upcoming slot exists within 24h:

```
┌─────────────────────────────────────────┐
│ 🎯  Focus Session                       │
│                                         │
│  📅  Today at 3:00 PM · 45 min          │
│     Writing sprint 🖊️                   │
│     Jordan + 2 going                   │
│                                         │
│   [ I'm In ]        [ Start a Session ] │
└─────────────────────────────────────────┘
```

**B. Personal Calendar Screen (new)**

A scrollable week-view calendar (or simple list view for v1) showing all upcoming slots across all groups. Each slot shows the group icon, time, and RSVP count. Tapping a slot opens the group detail (one navigation step), which then shows the Join/Start action.

This keeps the booking UX clean: the calendar is read/RSVP-only. Starting a session always happens from within the group context.

### 7.2 Session Screen

Full-screen modal launched from either entry point.

**Lobby Phase:**

```
┌─────────────────────────────────────────┐
│  ← Leave                  [Group Name] │
│                                         │
│         Morning Crew                   │
│      Focus Session · Lobby             │
│                                         │
│  ┌──────┐  ┌──────┐  ┌──────┐         │
│  │ 👤   │  │ 👤   │  │ 👤   │         │
│  │Jordan│  │ Mia  │  │  You  │         │
│  └──────┘  └──────┘  └──────┘         │
│                                         │
│  Duration                               │
│  [25 min]  [45 min]  [60 min]  [90 min]│
│            ↑ selected                   │
│                                         │
│        [ 🎯 Start Focus Block ]         │
│     (host only; others see waiting UI) │
│                                         │
│  🔊  Mic on  ·  3 people here          │
└─────────────────────────────────────────┘
```

**Focus Block Phase:**

```
┌─────────────────────────────────────────┐
│  ← Leave                               │
│                                         │
│            🎯 FOCUS                    │
│                                         │
│              34:12                      │  ← large countdown
│                                         │
│  ┌──────┐  ┌──────┐  ┌──────┐         │
│  │ 🟢   │  │ 🟢   │  │ 🟢   │         │  ← green dots = active
│  │Jordan│  │ Mia  │  │  You  │         │
│  └──────┘  └──────┘  └──────┘         │
│                                         │
│  🔇 Mic muted during focus             │
│                                         │
│  ─────────────────────────────────     │
│  [ End Session Early ]  (host only)    │
└─────────────────────────────────────────┘
```

**Chit-Chat Phase:**

```
┌─────────────────────────────────────────┐
│  ← Leave                               │
│                                         │
│        💬 Chit-Chat · 9:43             │  ← countdown to session end
│                                         │
│  ┌──────┐  ┌──────┐  ┌──────┐         │
│  │ 👤   │  │ 👤   │  │ 👤   │         │
│  │Jordan│  │ Mia  │  │  You  │         │
│  └──────┘  └──────┘  └──────┘         │
│                                         │
│  🔊  Mic on                            │
│                                         │
│  ─── How did your session go? ─────    │
│                                         │
│  ┌─────────────────────────────────┐   │
│  │ Send a message...               │   │
│  └─────────────────────────────────┘   │
└─────────────────────────────────────────┘
```

The text chat in chit-chat is ephemeral — messages are not stored in the group activity feed. This keeps it conversational and low-stakes.

### 7.3 Schedule a Slot Flow

From the group detail Focus Session card → "Schedule":

```
Schedule a Focus Session
────────────────────────
Date & Time    [Today, 3:00 PM      ▼]
Duration       [45 min              ▼]
Note (optional) e.g. "Writing sprint 🖊️"

[ Cancel ]            [ Post Slot ]
```

On post: slot is created, group members receive an FCM notification ("Jordan scheduled a focus session at 3pm — tap to RSVP").

### 7.4 Personal Calendar Screen

Navigation: Bottom nav → (new) Calendar icon, or from profile/settings.

For v1, a simple list grouped by day is sufficient:

```
Focus Sessions
──────────────
Today
  ● 3:00 PM · Morning Crew · 45 min · Writing sprint
    Jordan + 2 going  [ I'm In ]

Tomorrow
  ● 9:00 AM · Study Squad · 25 min
    1 going  [ I'm In ]

Thursday
  ● 7:00 PM · Evening Grind · 60 min
    No RSVPs yet  [ I'm In ]
```

Tapping any row navigates to the group detail. The calendar is supplementary — the core booking action still lives in the group context.

A future v2 calendar can render a proper week-view with time blocks, and export to the system calendar (ICS or Google Calendar API).

---

## 8. Notifications

| Event | Recipients | Mechanism |
|-------|-----------|-----------------|
| Session started (ad-hoc) | All group members not in session | FCM: "Jordan started a focus session in Morning Crew — join now?" |
| Slot posted | All group members | FCM: "Jordan scheduled a focus session for today at 3pm in Morning Crew" |
| RSVP milestone (3+ people) | Slot creator | FCM: "3 people are joining your Morning Crew session at 3pm 🎉" |
| Session about to start (15 min before scheduled) | The RSVPing user only | **Local notification** scheduled on-device at RSVP time via `AlarmManager.setExactAndAllowWhileIdle()` — no server job required |
| New parallel session spawned (group was full) | Member who triggered it | In-app only — they're automatically the host |
| Chit-chat window opened | Participants | In-app only (they're already in the session) |

---

## 9. Scheduling & Calendar Integration

### 9.1 Booking Philosophy

Slots are **soft commitments** — more like "I plan to be here" than a calendar invite. RSVPs signal intent. The session starts when someone actually taps "Start Focus Block," regardless of RSVPs. This reduces friction and respects that life happens.

### 9.2 Auto-Start Option (v2)

A future enhancement could auto-start the session at the scheduled time if ≥2 RSVPs are active in the lobby. Out of scope for v1.

### 9.3 System Calendar Export (v2)

Not in v1, but plan for it: when a user RSVPs, optionally add an event to their device calendar via the Android `CalendarContract` API. The event title would be "Focus Session · [Group Name]" with a deep link back to the app. This would satisfy the "aggregate calendar across groups" need at the system level without building a custom calendar UI.

---

## 10. Freemium Considerations

| Feature | Free | Premium |
|---------|------|---------|
| Join sessions | ✅ | ✅ |
| Start ad-hoc sessions | ✅ | ✅ |
| Schedule slots | ✅ | ✅ |
| RSVP to slots | ✅ | ✅ |
| Personal calendar view | ✅ | ✅ |
| Session history / stats | ❌ | ✅ |
| Longer focus blocks (90 min) | ❌ | ✅ |

The 90-minute block as a premium perk is a light touch — power users doing deep work are the most likely premium subscribers.

---

## 11. Implementation Checklist

### Backend
- [ ] Add `focus_sessions`, `focus_session_participants`, `focus_slots`, `focus_slot_rsvps` tables
- [ ] Implement REST endpoints for sessions and slots
- [ ] Build WebSocket signaling server (session join/leave, SDP relay, ICE relay, phase management)
- [ ] Provision STUN/TURN (Cloudflare Calls or coturn)
- [ ] Add FCM notification triggers for: session started (ad-hoc), slot posted, RSVP milestone
- [ ] ~~Cloud Scheduler job for pre-session reminders~~ — replaced by on-device `AlarmManager` (see Android checklist)

### Android
- [ ] Add Stream WebRTC Android dependency
- [ ] Build `FocusSessionService` (foreground service for audio during session)
- [ ] Build WebSocket signaling client (reconnect logic, exponential backoff)
- [ ] Build `SessionScreen` composable with lobby / focus / chit-chat phases
- [ ] Add Focus Session card to Group Detail Fragment
- [ ] Build Schedule Slot bottom sheet
- [ ] Build Personal Calendar screen (list view, v1)
- [ ] Add Calendar tab to bottom navigation
- [ ] Handle audio focus, BT headset events, wired headset
- [ ] Handle app backgrounding during session (keep audio via foreground service, show persistent notification)
- [ ] On RSVP success: schedule local alarm via `AlarmManager.setExactAndAllowWhileIdle()` at `scheduled_start - 15 min`, keyed by slot UUID
- [ ] On un-RSVP: cancel the corresponding alarm by slot UUID
- [ ] Register `BOOT_COMPLETED` receiver to re-schedule alarms for upcoming RSVPd slots after device reboot
- [ ] Handle `SCHEDULE_EXACT_ALARM` permission gracefully on API 31–32 (requires user grant via system settings); `USE_EXACT_ALARM` on API 33+ requires no runtime grant
- [ ] Add permissions: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `RECEIVE_BOOT_COMPLETED`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`

### Edge Cases
- [ ] Host leaves mid-session → promote next participant
- [ ] All participants leave → auto-end session after 60s grace period
- [ ] Session full (8 participants) → show "Session full" with notify-on-open option
- [ ] Network drop → reconnect to signaling, re-establish peer connections
- [ ] Focus block ends → auto-transition to chit-chat even if host has left
- [ ] Chit-chat timer ends → gracefully close all peer connections and show summary card
- [ ] Concurrent sessions in same group → allowed; auto-spawn a new session when the current one hits 8 participants

---

## 12. Open Questions

- **TURN server:** Cloudflare Calls TURN (free tier) vs self-hosted coturn. Cloudflare is simpler to start but less control. Recommend Cloudflare for v1.
- **Chit-chat text storage:** Currently proposed as ephemeral (in-memory, not persisted). If there's demand, could add a short-lived transcript (e.g. stored for 24h then deleted). Decide based on user feedback.
- **Session stats:** What to show post-session? A simple "You focused for 45 minutes with Jordan and Mia in Morning Crew" summary card could feed into a future "focus streak" metric.
- **Apple / iOS:** Out of scope entirely, or worth considering a future port using `webrtc-kmp` (Kotlin Multiplatform)?

---

## 13. Dependencies

| Dependency | Purpose | Gradle |
|-----------|---------|--------|
| `io.getstream:stream-webrtc-android` | WebRTC peer connections, audio | `1.3.x` |
| `io.getstream:stream-webrtc-android-ktx` | Coroutine extensions | `1.3.x` |
| OkHttp WebSocket | Signaling client | Already in project |
| Existing FCM | Session notifications | Already in project |

No new backend dependencies required — WebSocket support can be added to the existing Node.js server with the `ws` package (already a transitive dependency in most Express setups).
