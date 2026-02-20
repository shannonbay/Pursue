## 5. Animations & Transitions

### 5.1 Animation Philosophy

**Productivity First:**
- Animations serve functional purposes only
- Short durations (150-250ms typical)
- Respect Android's "Reduce Motion" setting

### 5.2 Screen Transitions

- **Forward**: Slide in from right (250ms)
- **Back**: Slide out to right (200ms)
- **Tab Switch**: Crossfade (150ms)

### 5.3 Micro-Interactions

**Goal Card Tap (Log Progress):**
1. Immediate haptic feedback (light tap, ~10ms)
2. Card background flash: white → light blue (#E3F2FD) → white (200ms total)
3. Checkmark animation: ⬜ scale up → ✅ fade in (150ms)
4. Toast slides up from bottom with "Undo" (250ms)
5. Total perceived response: < 50ms (feels instant)

**Goal Card Arrow Tap:**
- Standard ripple effect centered on arrow
- Navigate to Goal Detail (screen transition)

**Button Press:**
- Ripple effect only (Material Design standard)
- No additional animations

**Checkbox/Toggle:**
- Instant state change with subtle scale animation (100ms)

**Numeric Goal Dialog:**
- Slide up from bottom (250ms)
- Backdrop fade in (150ms)
- Dismiss: Slide down (200ms)

**Confirmation Dialog (Binary - Remove Log):**
- Fade in with scale (200ms)
- Backdrop fade in (150ms)
- Dismiss: Fade out (150ms)

**Edit Dialog (Numeric - Edit/Delete):**
- Slide up from bottom (250ms)
- Backdrop fade in (150ms)
- Pre-filled value shown immediately
- Keyboard auto-appears (system controlled)
- Dismiss: Slide down (200ms)

**Toast/Snackbar:**
- Slide up from bottom (250ms)
- Auto-dismiss after 3 seconds
- Slide down on dismiss (200ms)

**Progress Deletion Flow:**
```
Binary Goal (already logged):
Tap card → 
  Confirmation dialog fades in (200ms)
  User sees: "Remove today's log?"
  If [Remove] tapped:
    Dialog fades out (150ms)
    Card updates to uncompleted state
    Toast slides up: "Progress removed"

Numeric Goal (already logged):
Tap card →
  Edit dialog slides up (250ms)
  Value pre-filled: [5.2]
  User can edit value or tap [Delete Entry]
  If [Delete Entry] tapped:
    Nested confirmation: "Delete this entry?"
    If confirmed:
      Dialog slides down (200ms)
      Card updates to not logged
      Toast slides up: "Entry deleted"
```

**Animation Timing Reference:**
```kotlin
// Card highlight animation
val highlightAnimator = ValueAnimator.ofObject(
    ArgbEvaluator(),
    normalColor,      // White
    highlightColor,   // Light blue
    normalColor       // Back to white
).apply {
    duration = 200
    interpolator = FastOutSlowInInterpolator()
}

// Checkmark animation
val checkmarkAnimator = ObjectAnimator.ofFloat(
    checkmark,
    "alpha",
    0f, 1f
).apply {
    duration = 150
    startDelay = 50  // Start after highlight begins
}
```

---

