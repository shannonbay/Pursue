# Speed Dial FAB Update Summary

## Overview

Updated the Pursue UI Specification to implement a Speed Dial Floating Action Button on the Home (Groups List) screen, providing users with quick access to both "Join Group" and "Create Group" actions.

---

## Key Changes

### 1. FAB Strategy Table (Section 3.2)

**Before:**
- Home screen: ❌ No FAB
- Create Group action was relegated to overflow menu

**After:**
- Home screen: ✅ **Speed Dial FAB**
- Primary actions: "Join Group" and "Create Group"
- Visibility: Everyone
- Rationale: Both joining and creating groups are primary entry points into the app

### 2. Comprehensive Speed Dial Implementation (Section 4.2.0)

Added complete specification including:

#### Visual States
- **Collapsed:** Single + button FAB (56dp diameter)
- **Expanded:** × button with two mini FABs stacked above
  - "Create Group" mini FAB (40dp)
  - "Join Group" mini FAB (40dp)
  - Text labels appear beside each mini FAB
  - Semi-transparent scrim overlay (30% opacity)

#### Animations
- **Expand:** 200ms total
  - Scrim fades in
  - Main FAB rotates 45° (+ becomes ×)
  - Mini FABs slide up and fade in
  - Labels fade in after mini FABs
  
- **Collapse:** 200ms total (reverse sequence)

#### User Interactions
- Tap main FAB (collapsed) → Expands speed dial
- Tap "Create Group" → Navigate to group creation
- Tap "Join Group" → Open join dialog
- Tap scrim or × → Collapse speed dial
- Haptic feedback on all interactions

### 3. Join Group Dialog (Section 4.2.0.1)

Added detailed specification for the dialog that appears when tapping "Join Group":

#### Dialog Features
- Manual invite code entry field
  - Uppercase transformation
  - Max 10 characters
  - Alphanumeric validation
- "Scan QR Code" button for camera scanning
- Full API integration with error handling

#### Error States
- Invalid code (400): "Invalid invite code"
- Expired code (410): "This invite code has expired"
- Already member (409): "You're already in this group"
- Network error: "Connection error. Try again"

#### Success Flow
- Dialog dismisses
- Toast notification: "Joined [Group Name]!"
- Navigate to group detail screen
- Refresh groups list

### 4. Updated Empty State (Section 4.2.1 State 3)

**Before:**
- Two buttons in the empty state:
  - "Join Group" button
  - "Create Group" button
- Message: "Join a group to get started or create your own accountability group with friends."

**After:**
- No buttons (cleaner design)
- Speed Dial FAB remains visible
- Message: "Use the + button below to join a group or create your own accountability group."
- Users directed to the FAB for all actions

### 5. FAB Visibility Across UI States

Updated all 5 UI states with FAB visibility rules:

| State | FAB Visible? | Notes |
|-------|-------------|-------|
| **Loading** | ❌ Hidden | Prevents premature interaction during shimmer |
| **Success - With Groups** | ✅ Visible | Standard placement, bottom-right |
| **Success - Empty** | ✅ Visible | Primary onboarding mechanism |
| **Error** | ✅ Visible | User can still create/join groups despite error |
| **Offline** | ✅ Visible | Displayed with cached data + retry snackbar |

### 6. Complete Kotlin Implementation

Added production-ready Jetpack Compose code:
- `SpeedDialFAB` composable with full animation support
- `MiniFABWithLabel` component for labeled mini FABs
- `JoinGroupDialog` with validation and API integration
- Proper Material Design 3 styling
- Error handling and loading states
- Accessibility support (TalkBack, content descriptions)

---

## Design Benefits

### User Experience
✅ **Discoverable** - Prominent + button immediately visible  
✅ **Efficient** - One tap expands to show both primary actions  
✅ **Clean** - Doesn't clutter interface when collapsed  
✅ **Consistent** - Follows Material Design 3 patterns  
✅ **Accessible** - Each option separately focusable for screen readers

### Technical Benefits
✅ **Material Design 3 Compliant** - Uses standard FAB patterns  
✅ **Smooth Animations** - 200ms transitions feel polished  
✅ **Proper State Management** - Clear visibility rules across all states  
✅ **Error Resilient** - Comprehensive error handling in join flow  
✅ **Mobile-First** - Optimized for thumb-friendly touch targets

---

## Code Highlights

### Speed Dial FAB Component
```kotlin
@Composable
fun SpeedDialFAB(
    isExpanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    modifier: Modifier = Modifier
)
```

### Join Group Dialog
```kotlin
@Composable
fun JoinGroupDialog(
    onDismiss: () -> Unit,
    onJoinSuccess: (Group) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
)
```

### API Integration
```kotlin
suspend fun joinGroupByCode(inviteCode: String): Result<Group>
```

---

## Updated Sections

1. **Section 3.2:** FAB Strategy Table
2. **Section 4.2.0:** Speed Dial FAB Specification (NEW)
3. **Section 4.2.0.1:** Join Group Dialog (NEW)
4. **Section 4.2.1:** UI States (all 5 states updated)

---

## Next Steps for Implementation

1. **UI Components:**
   - Implement `SpeedDialFAB` composable
   - Create `MiniFABWithLabel` component
   - Build `JoinGroupDialog` with validation
   - Add animations and transitions

2. **API Integration:**
   - Implement `joinGroupByCode()` function
   - Add error handling for all error codes
   - Wire up success callbacks
   - Implement QR code scanning

3. **Testing:**
   - Test all 5 UI states with FAB
   - Verify animations are smooth (60fps)
   - Test accessibility with TalkBack
   - Validate error states with various codes
   - Test join flow end-to-end

4. **Polish:**
   - Add haptic feedback to all interactions
   - Ensure proper keyboard dismissal
   - Test on various screen sizes
   - Verify Material Design 3 compliance

---

## Files Modified

- `Pursue-UI-Spec.md`
  - Section 3.2: FAB Strategy Across App
  - Section 4.2: Home Screen (Groups List)
    - Added 4.2.0: Speed Dial FAB
    - Added 4.2.0.1: Join Group Dialog
    - Updated 4.2.1: All UI States
