## 3. Navigation Structure

### 3.1 Information Architecture

```
Pursue App
│
├── [First-Time Setup] ────────────────┐
│   ├── Welcome Screen                 │
│   ├── Sign Up / Sign In              │
│   └── (Optional) Create First Group  │
│                                       │
├── Home / Groups List ────────────────┤
│   ├── Group Detail                   │
│   │   ├── Goals Tab                  │ ← FAB: Add Goal (admin only)
│   │   ├── Members Tab                │ ← FAB: Invite Members (everyone)
│   │   └── Activity Tab               │ ← No FAB
│   └── Create Group (via overflow ⋮)  │
│                                       │
├── Today (Quick daily goals view)     │ ← No FAB (tap cards to log)
│   └── Tap goal cards to log progress │
│                                       │
└── Profile                             │ ← No FAB
    ├── Display Name & Avatar           │
    ├── My Progress Summary             │
    ├── Linked Devices                  │
    └── Account Settings                │
        ├── Linked Accounts             │
        ├── Change Password             │
        └── Privacy & Security          │
```

### 3.2 FAB Strategy Across App

**Floating Action Button (FAB) Usage:**

| Screen | FAB? | Icon | Action | Visibility |
|--------|------|------|--------|-----------|
| Home (Groups List) | ❌ No | - | Create Group in overflow | - |
| Today | ❌ No | - | Tap cards to log | - |
| Profile | ❌ No | - | - | - |
| **Group: Goals Tab** | ✅ **Yes** | `ic_add` (+) | Create Goal | **Admin only** |
| **Group: Members Tab** | ✅ **Yes** | `ic_person_add` (👤+) | Invite Members | **Everyone** |
| Group: Activity Tab | ❌ No | - | - | - |

**Design Rationale:**
- **FAB = Primary creation action** for the current screen
- Goals Tab: Creating goals is primary action (admins)
- Members Tab: Inviting members is primary action (everyone)
- Activity Tab: View-only, no creation action
- Today Screen: Logging is primary action, but uses tap-to-log pattern (more efficient than FAB)

**FAB Specifications:**
- Size: 56dp diameter (standard Material Design)
- Color: Primary blue (#1976D2)
- Elevation: 6dp
- Position: Bottom-right, 16dp margin
- Icon changes based on context (see table above)
- Transitions smoothly when switching tabs (200ms morph animation)

### 3.4 Bottom Navigation Bar

```
┌────────────────────────────────────────────┐
│  [Home]    [Today]    [Profile]            │
│   🏠        📅         👤                   │
└────────────────────────────────────────────┘
```

- **Home**: Groups list (badge for unread updates)
- **Today**: Today's daily goals (badge for incomplete goals)
- **Profile**: User settings

### 3.5 Top App Bar

- Left: Back arrow (when applicable)
- Center: Screen title or group name
- Right: Overflow menu (3 dots)

**No sync status indicator needed** - standard loading states instead

---

