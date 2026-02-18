# Pursue — Shareable Milestone Cards Enhancement Spec

**Feature:** Shareable Milestone Cards v1.1 Enhancements  
**Version:** 1.1  
**Status:** Draft  
**Depends on:** `shareable-milestone-cards-spec.md` v1.0  
**Platform:** Android (Material Design 3)

---

## 1. Overview

### 1.1 Purpose

This document captures 13 improvements to the Shareable Milestone Cards v1.0 spec. Each enhancement is categorised by priority and can be implemented independently. The goal is to fix schema inconsistencies, close privacy gaps, improve viral effectiveness in the NZ/AU launch market, and fill functional gaps before the feature ships.

### 1.2 Priority Tiers

| Priority | Label | Meaning |
|----------|-------|---------|
| **P0** | Must-fix | Spec bugs or privacy issues — implement before v1 ships |
| **P1** | Should-have | Significant UX or growth improvements for launch |
| **P2** | Nice-to-have | Enhancements that can follow shortly after launch |

### 1.3 Enhancement Summary

| # | Enhancement | Priority | Section |
|---|-------------|----------|---------|
| 1 | Fix gradient schema mismatch | P0 | §2 |
| 2 | Remove user ID from UTM links | P0 | §3 |
| 3 | Add milestone deduplication policy | P0 | §4 |
| 4 | Add freemium gating section | P0 | §5 |
| 5 | Expand milestone tiers | P1 | §6 |
| 6 | Adapt share UX for NZ/AU market | P1 | §7 |
| 7 | Add QR code / visible URL to card image | P1 | §8 |
| 8 | Specify `exportCardAsBitmap()` implementation | P1 | §9 |
| 9 | Clarify push notification image strategy | P1 | §10 |
| 10 | Add offline handling | P2 | §11 |
| 11 | Calibrate success metrics for launch scale | P2 | §12 |
| 12 | Add card accessibility requirements | P2 | §13 |
| 13 | Harden Instagram sharing fallback logic | P2 | §14 |

---

## 2. Fix Gradient Schema Mismatch (P0)

### 2.1 Problem

The v1.0 card design (§5.2.2) and appendix examples both show gradient backgrounds (e.g., `#1E88E5 → #1565C0`), but the `shareable_card_data` JSON schema only stores a single `background_color` field. The client has no way to render the intended gradient without guessing the second colour.

### 2.2 Solution

Replace `background_color` with `background_gradient` — an array of exactly two hex colour strings representing the top and bottom of a vertical linear gradient.

**Updated JSON schema field:**

```json
{
  "background_gradient": ["#1E88E5", "#1565C0"]
}
```

**Updated field definition:**

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `background_gradient` | string[2] | Top and bottom gradient hex colours | `["#1E88E5", "#1565C0"]` |

Remove the `background_color` field from the schema entirely.

### 2.3 Backend Change

Update `generateCardData()` colour map:

```typescript
const gradients: Record<string, [string, string]> = {
  first_log: ["#1E88E5", "#1565C0"],   // Blue
  streak:    ["#F57C00", "#E65100"],    // Orange
  total_logs: ["#7B1FA2", "#4A148C"],   // Purple
};

return {
  // ...other fields
  background_gradient: gradients[milestoneType],
};
```

### 2.4 Android Change

Update `MilestoneCard` composable background:

```kotlin
val gradientBrush = Brush.verticalGradient(
    colors = listOf(
        Color(android.graphics.Color.parseColor(cardData.backgroundGradient[0])),
        Color(android.graphics.Color.parseColor(cardData.backgroundGradient[1]))
    )
)

Box(
    modifier = Modifier
        .fillMaxSize()
        .background(gradientBrush)
)
```

### 2.5 Migration

The `shareable_card_data` column is JSONB and nullable. Existing rows with the old `background_color` field will either not exist yet (feature hasn't shipped) or can be handled with a client-side fallback:

```kotlin
// Client fallback for legacy data
val gradient = cardData.backgroundGradient
    ?: listOf(cardData.backgroundColorLegacy ?: "#1E88E5", "#000000")
```

---

## 3. Remove User ID from UTM Links (P0)

### 3.1 Problem

Section 6.1 of the v1.0 spec places the raw `user_id` in the `utm_content` parameter:

```
https://getpursue.app?utm_source=share&utm_medium=milestone_card&utm_campaign=streak_7&utm_content=user-abc123
```

Anyone who receives or inspects the share link can see the user's internal ID. This contradicts §9.3's promise that "shared cards contain NO personal information."

### 3.2 Solution

Replace `utm_content` with an opaque referral token.

**Option A — Hash-based (recommended for v1):**

Generate a one-way, short referral code at share time using a truncated hash:

```typescript
import crypto from 'crypto';

function generateReferralToken(userId: string): string {
  const hash = crypto.createHmac('sha256', process.env.REFERRAL_SECRET!)
    .update(userId)
    .digest('hex');
  return hash.substring(0, 12); // 12-char opaque token
}
```

The backend maintains a lookup `referral_tokens(token, user_id)` to attribute signups.

**Option B — Dedicated referral code (if referral system is planned):**

Add a `referral_code` column to the `users` table (generated on account creation). Reuse it across all share surfaces, not just milestone cards.

### 3.3 Updated URL Format

```
https://getpursue.app?utm_source=share&utm_medium=milestone_card&utm_campaign=streak_7&ref=a1b2c3d4e5f6
```

Use a dedicated `ref` parameter instead of `utm_content` — this keeps UTM parameters clean for analytics while the `ref` parameter handles attribution.

### 3.4 Database Schema

```sql
CREATE TABLE referral_tokens (
  token VARCHAR(12) PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_referral_tokens_user ON referral_tokens(user_id);
```

**Note:** Each user gets one stable token. The same token is reused across all milestone shares.

---

## 4. Add Milestone Deduplication Policy (P0)

### 4.1 Problem

The v1.0 `checkMilestones()` function checks `streak === 7` on every progress log. If a user breaks their streak and later re-achieves a 7-day streak, the function triggers again. The spec doesn't state whether milestones should be once-ever, once-per-goal, or repeatable — and the notification inbox spec's open question #3 (milestone deduplication table) is unresolved.

### 4.2 Decision

**Milestones are repeatable per goal, with a cooldown.**

Rationale:
- Re-achieving a streak after breaking it is a genuine accomplishment worth celebrating
- A user who restarts after falling off is exactly the person you want to share their comeback story
- But firing on every re-achievement without a gap feels spammy

### 4.3 Deduplication Rules

| Milestone | Repeatable? | Cooldown | Key |
|-----------|-------------|----------|-----|
| First Log | Once ever | N/A | `first_log:{userId}` |
| 7-Day Streak | Per goal, with cooldown | 14 days since last 7-day card for same goal | `streak_7:{userId}:{goalId}` |
| 30-Day Streak | Per goal, with cooldown | 60 days since last 30-day card for same goal | `streak_30:{userId}:{goalId}` |
| 100 Total Logs | Once ever | N/A | `total_logs_100:{userId}` |

### 4.4 Database Schema

Adopt the `user_milestone_grants` table recommended in `pursue-notification-inbox-spec.md` open question #3:

```sql
CREATE TABLE user_milestone_grants (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id),
  milestone_key VARCHAR(100) NOT NULL,
  goal_id UUID REFERENCES goals(id) ON DELETE SET NULL,
  granted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(user_id, milestone_key)
);
```

### 4.5 Updated Logic

```typescript
async function canAwardMilestone(
  userId: string,
  milestoneKey: string,
  cooldownDays: number | null
): Promise<boolean> {
  const existing = await db
    .selectFrom('user_milestone_grants')
    .where('user_id', '=', userId)
    .where('milestone_key', '=', milestoneKey)
    .selectAll()
    .executeTakeFirst();

  // Never awarded — always allow
  if (!existing) return true;

  // No cooldown — once-ever milestone
  if (cooldownDays === null) return false;

  // Check cooldown
  const daysSinceGrant = differenceInDays(new Date(), existing.granted_at);
  return daysSinceGrant >= cooldownDays;
}

// Usage in checkMilestones():
if (streak === 7 && await canAwardMilestone(userId, `streak_7:${goalId}`, 14)) {
  // Award milestone and upsert grant record
  await upsertMilestoneGrant(userId, `streak_7:${goalId}`, goalId);
  // Insert notification with shareable_card_data...
}
```

The `UNIQUE` constraint uses an upsert to update `granted_at` on re-achievement:

```typescript
async function upsertMilestoneGrant(userId: string, key: string, goalId: string | null) {
  await db
    .insertInto('user_milestone_grants')
    .values({ user_id: userId, milestone_key: key, goal_id: goalId, granted_at: new Date() })
    .onConflict((oc) =>
      oc.columns(['user_id', 'milestone_key']).doUpdateSet({ granted_at: new Date() })
    )
    .execute();
}
```

---

## 5. Add Freemium Gating Section (P0)

### 5.1 Problem

Every other major Pursue feature spec includes a freemium gating table (see `group-heat-spec.md` §10, `smart-reminders-spec.md` §1.4). The milestone cards spec has no such section, creating ambiguity during implementation.

### 5.2 Decision

Shareable milestone cards are a **core feature available to all users** (free and premium).

Rationale:
- Milestone cards are a viral acquisition tool — gating them behind premium defeats the purpose
- Free users sharing cards drives organic growth, which benefits the entire ecosystem
- Consistent with smart reminders (also ungated) — engagement features should never be paywalled

### 5.3 Gating Table

| Feature | Free | Premium |
|---------|------|---------|
| Milestone card generation | ✅ | ✅ |
| Share to Instagram Stories | ✅ | ✅ |
| Share via generic share sheet | ✅ | ✅ |
| Save to Photos | ✅ | ✅ |
| Opt-out toggle in Settings | ✅ | ✅ |
| Card design customisation (v2) | ❌ | ✅ |
| Milestone achievement history (v2) | ❌ | ✅ |

**v2 premium upsell opportunity:** When a premium user taps "Share", offer card template customisation (colour/style). This creates a natural "your friend's card looked cooler" effect that drives premium interest without blocking sharing.

---

## 6. Expand Milestone Tiers (P1)

### 6.1 Problem

The v1.0 spec has a significant engagement gap: after the 7-day streak card, users get nothing until day 30. That's 23 days of silence during the most critical period of habit formation. Similarly, long-term power users (your brand evangelists) have no milestones after 100 total logs.

### 6.2 New Milestone Tiers

**Add for v1:**

| Milestone | Trigger | Card Title | Emotional Hook | Background Gradient |
|-----------|---------|------------|----------------|---------------------|
| **14-Day Streak** | 14 consecutive days | "Two weeks strong!" | "Habits are forming" | `["#00897B", "#00695C"]` (Teal) |
| **365-Day Streak** | 365 consecutive days | "One year. Every day." | "This is who you are now" | `["#FF6F00", "#E65100"]` (Amber) |
| **500 Total Logs** | 500 total entries | "500 logs milestone" | "Dedication has a number" | `["#6A1B9A", "#4A148C"]` (Deep Purple) |

**Defer to v2:**

| Milestone | Rationale for deferral |
|-----------|----------------------|
| 90-Day Streak | Low priority — 30 → 365 gap is acceptable |
| 1,000 Total Logs | Wait for user base to mature |
| Goal Completed | Requires "goal completion" feature (not yet designed) |

### 6.3 Backend Changes

Add new entries to the `checkMilestones()` milestones array and the `generateCardData()` templates. Follow the same pattern as existing milestones.

Update `canAwardMilestone()` cooldowns:

| Milestone | Cooldown |
|-----------|----------|
| 14-Day Streak | 30 days |
| 365-Day Streak | 365 days (effectively once per year) |
| 500 Total Logs | Once ever |

### 6.4 Card Data Templates

```typescript
// Add to templates in generateCardData():
streak: {
  7:   { title: "7-day streak!",           quote: "Consistency is everything" },
  14:  { title: "Two weeks strong!",       quote: "Habits are forming" },
  30:  { title: "30-day streak!",          quote: "A month of dedication" },
  365: { title: "One year. Every day.",    quote: "This is who you are now" },
},
total_logs: {
  100: { title: "100 logs milestone",  quote: "Proof that showing up works" },
  500: { title: "500 logs milestone",  quote: "Dedication has a number" },
}
```

### 6.5 Gradient Additions

```typescript
// Add to gradients map, keyed by milestone_key:
const gradients: Record<string, [string, string]> = {
  first_log:      ["#1E88E5", "#1565C0"],
  streak_7:       ["#F57C00", "#E65100"],
  streak_14:      ["#00897B", "#00695C"],
  streak_30:      ["#F57C00", "#E65100"],
  streak_365:     ["#FF6F00", "#E65100"],
  total_logs_100: ["#7B1FA2", "#4A148C"],
  total_logs_500: ["#6A1B9A", "#4A148C"],
};
```

---

## 7. Adapt Share UX for NZ/AU Market (P1)

### 7.1 Problem

The v1.0 spec positions "Share to Instagram Stories" as the primary CTA. Pursue launches first in New Zealand and Australia, where WhatsApp, iMessage, and Facebook Messenger have significant usage alongside Instagram. Leading with an Instagram-specific button risks lower share rates if users' preferred sharing app is something else.

### 7.2 Solution

Swap the button hierarchy: make the generic share sheet the primary action and Instagram Stories a secondary option.

**Updated button order:**

```
┌─────────────────────────────────────────────────────────┐
│  [Card Preview]                                         │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │  📤  Share Your Achievement                       │  │ ← Primary CTA (generic share sheet)
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │  📷  Share to Instagram Stories                   │  │ ← Secondary CTA
│  └───────────────────────────────────────────────────┘  │
│                                                         │
│  ┌───────────────────────────────────────────────────┐  │
│  │  💾  Save to Photos                               │  │ ← Tertiary CTA
│  └───────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────┘
```

**Styling changes:**
- "Share Your Achievement" — filled button, primary colour (was the Instagram button's style)
- "Share to Instagram Stories" — outlined button, secondary colour
- "Save to Photos" — text button, unchanged

### 7.3 Analytics Enhancement

Track which app users select in the generic share sheet by logging the resolved activity's package name:

```kotlin
// New analytics event
data class ShareTargetEvent(
    val notificationId: String,
    val milestoneType: String,
    val targetPackage: String  // e.g., "com.whatsapp", "com.instagram.android"
)
```

Add a new event:

| Event Name | When | Properties |
|------------|------|-----------|
| `milestone_card_share_target` | User selects app from share sheet | `notification_id`, `milestone_type`, `target_package` |

This data will validate or invalidate the Instagram-first assumption after 30 days of real usage, and inform whether to re-promote Instagram to primary in a future update.

### 7.4 Conditional Instagram Button

If Instagram is not installed, hide the "Share to Instagram Stories" button entirely rather than showing it and falling back. This avoids user confusion:

```kotlin
val isInstagramInstalled = remember {
    try {
        context.packageManager.getPackageInfo("com.instagram.android", 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }
}

if (isInstagramInstalled) {
    // Show Instagram Stories button
}
```

---

## 8. Add QR Code to Card Image (P1)

### 8.1 Problem

When a milestone card image is forwarded without the accompanying share text (common on messaging apps), the viewer has no actionable way to find Pursue. The existing `getpursue.app` text at the bottom is good but not scannable.

### 8.2 Solution

Add a small QR code in the bottom-right corner of the card image, encoding the full referral URL.

**QR code specifications:**
- Size: 80×80dp on the card (small enough to be unobtrusive)
- Position: Bottom-right corner, 24dp margin from edges
- Colour: White with 15% opacity background circle behind it for contrast on any gradient
- Encoded URL: `https://getpursue.app?ref={referral_token}&utm_source=qr&utm_medium=milestone_card&utm_campaign={milestone_type}`
- Error correction: Level M (15%)

### 8.3 Android Implementation

Use the `com.google.zxing:core` library (already commonly used on Android):

```kotlin
// Add to build.gradle
implementation("com.google.zxing:core:3.5.3")

fun generateQrBitmap(url: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1
    )
    val matrix = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.White.toArgb() else Color.Transparent.toArgb())
        }
    }
    return bitmap
}
```

### 8.4 Card Layout Update

Add to the bottom-right of the `MilestoneCard` composable, below the branding divider:

```
┌────────────────────────────────────────────┐
│                                            │
│   ...existing card content...              │
│                                            │
│   ─────────────────────────                │
│   Track goals with friends on Pursue       │
│   getpursue.app                    [QR]    │ ← QR in bottom-right
│                                            │
└────────────────────────────────────────────┘
```

### 8.5 Analytics

Add a distinct UTM source (`utm_source=qr`) to distinguish QR-driven signups from text-link-driven signups. This measures how often cards are shared without the accompanying text.

---

## 9. Specify `exportCardAsBitmap()` Implementation (P1)

### 9.1 Problem

The v1.0 spec stubs `exportCardAsBitmap()` with a comment saying "use ComposeView.drawToBitmap() or similar approach." Rendering a Compose composable to a fixed-resolution bitmap (1080×1920) without displaying it on screen is non-trivial and a common source of blank or distorted exports.

### 9.2 Recommended Approach

Use `GraphicsLayer` capture (available in Compose 1.7+):

```kotlin
@Composable
fun ShareableCardScreen(cardData: ShareableCardData) {
    val graphicsLayer = rememberGraphicsLayer()
    val coroutineScope = rememberCoroutineScope()

    // Card rendered on screen (also used for export)
    Box(
        modifier = Modifier
            .aspectRatio(9f / 16f)
            .drawWithContent {
                graphicsLayer.record {
                    this@drawWithContent.drawContent()
                }
                drawLayer(graphicsLayer)
            }
    ) {
        MilestoneCard(cardData = cardData)
    }

    // Export function
    fun exportAsBitmap(): Bitmap {
        return coroutineScope.async {
            graphicsLayer.toImageBitmap().asAndroidBitmap()
        }
    }
}
```

### 9.3 Resolution Handling

The on-screen card renders at the device's natural resolution. For export, scale the bitmap to exactly 1080×1920:

```kotlin
suspend fun exportCardAtTargetResolution(graphicsLayer: GraphicsLayer): Bitmap {
    val rawBitmap = graphicsLayer.toImageBitmap().asAndroidBitmap()
    return Bitmap.createScaledBitmap(rawBitmap, 1080, 1920, true)
}
```

### 9.4 Fallback for Older Compose Versions

If targeting Compose < 1.7, use `AndroidView` with `View.drawToBitmap()`:

```kotlin
fun exportCardAsBitmapLegacy(context: Context, cardData: ShareableCardData): Bitmap {
    val composeView = ComposeView(context).apply {
        setContent { MilestoneCard(cardData = cardData) }
    }

    // Measure and layout at target resolution
    composeView.measure(
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
    )
    composeView.layout(0, 0, 1080, 1920)

    return composeView.drawToBitmap(Bitmap.Config.ARGB_8888)
}
```

### 9.5 Performance Target

Card export should complete in under 500ms. Profile on a mid-range device (e.g., Samsung Galaxy A54). If the QR code generation (§8) adds noticeable latency, pre-generate the QR bitmap when the screen opens and cache it.

---

## 10. Clarify Push Notification Image Strategy (P1)

### 10.1 Problem

Section 7.1 of the v1.0 spec adds an `image` field to the FCM payload:

```json
"image": "https://storage.googleapis.com/pursue-assets/milestone-preview-7day.png"
```

But the actual card content is dynamic (goal title, icon, stat value). Either the server needs to render images on the fly (significant scope), or the images are static templates (which don't match the actual card).

### 10.2 Decision

**Use static, milestone-type-specific preview images for v1.** Do not implement server-side dynamic image generation.

### 10.3 Implementation

Create 4 static preview images (one per milestone type) and host them in Cloud Storage:

| Milestone | Asset URL | Description |
|-----------|-----------|-------------|
| First Log | `pursue-assets/milestone-preview-first-log.png` | Blue gradient, generic "First step taken" |
| 7-Day Streak | `pursue-assets/milestone-preview-streak-7.png` | Orange gradient, generic "7-day streak!" |
| 30-Day Streak | `pursue-assets/milestone-preview-streak-30.png` | Orange gradient, generic "30-day streak!" |
| 100 Total Logs | `pursue-assets/milestone-preview-total-100.png` | Purple gradient, generic "100 logs milestone" |

Add new milestone types from §6 as they're implemented.

These images show the card design aesthetic without any personalised data. Their purpose is to increase push notification tap-through rates, not to replace the actual card.

### 10.4 FCM Payload Update

```typescript
const previewImages: Record<string, string> = {
  first_log: `${ASSET_BASE_URL}/milestone-preview-first-log.png`,
  streak_7: `${ASSET_BASE_URL}/milestone-preview-streak-7.png`,
  streak_14: `${ASSET_BASE_URL}/milestone-preview-streak-14.png`,
  streak_30: `${ASSET_BASE_URL}/milestone-preview-streak-30.png`,
  total_logs_100: `${ASSET_BASE_URL}/milestone-preview-total-100.png`,
};
```

### 10.5 Future Enhancement (v2)

Server-side dynamic image generation (using Puppeteer, Sharp, or a Canvas-based renderer) to create personalised push preview images. Only pursue this if the static images show measurably lower tap-through rates than desired.

---

## 11. Add Offline Handling (P2)

### 11.1 Problem

The v1.0 spec doesn't address what happens when a user opens a milestone notification while offline. While `shareable_card_data` is stored locally (so the card can render), the share actions and save-to-photos have nuances.

### 11.2 Offline Behaviour Matrix

| Action | Offline Behaviour | Notes |
|--------|-------------------|-------|
| View card | ✅ Works | `shareable_card_data` is cached in local notification store |
| Share to Instagram | ⚠️ Partially works | Image export works, but Instagram may fail to post without connectivity |
| Share via generic sheet | ✅ Works | Android share sheet opens; the target app handles its own connectivity |
| Save to Photos | ✅ Works | MediaStore write is local |
| QR code generation | ✅ Works | Generated client-side from referral token |
| Analytics events | ⚠️ Queued | Queue events locally, flush when connectivity returns |
| UTM link resolution | N/A | The link is for the *recipient*, not the sharer |

### 11.3 Implementation

No special offline handling is needed for v1 beyond what the app already does. The card renders from local data, exports happen locally, and share intents are delegated to the target app. Analytics events should use the existing event queue (fire-and-forget with local buffer).

If the user is offline when they tap "Share to Instagram Stories", the Instagram app itself will show an appropriate error. Pursue does not need to intercept this.

---

## 12. Calibrate Success Metrics for Launch Scale (P2)

### 12.1 Problem

The v1.0 success metrics assume a mature user base. For a brand-new app launching in NZ/AU, targets like ">10 new users from milestone shares" in 30 days and a 6-month virality coefficient of 0.3 may not reflect realistic early-stage numbers, making it hard to evaluate whether the feature is actually working.

### 12.2 Revised Metrics — Tiered Targets

**30-Day Metrics (First 30 Days Post-Launch):**

| Metric | Minimum (viable) | Target (good) | Stretch (great) |
|--------|-------------------|---------------|-----------------|
| Card view rate | >40% | >60% | >75% |
| Share rate (of viewers) | >8% | >15% | >25% |
| Instagram share % | Track only | Track only | Track only |
| Attribution signups | >2 | >10 | >25 |
| Viral coefficient | >0.01 | >0.05 | >0.1 |

**Key change:** Remove the Instagram-specific target — measure organically which platforms users prefer (per §7.3) rather than assuming Instagram dominance.

**6-Month Metrics:**

| Metric | Minimum | Target | Stretch |
|--------|---------|--------|---------|
| Virality coefficient | >0.05 | >0.15 | >0.3 |
| Milestone shares as acquisition channel | Top 5 | Top 3 | #1 |
| Sharer retention uplift | >1.3x | >1.5x | >2x |

### 12.3 Decision Framework

After 30 days, evaluate:
- **Below minimum on share rate:** The card design or UX needs rework — users see the card but don't share. Investigate friction points.
- **At minimum on share rate, below minimum on attribution:** Cards are being shared but not converting. Investigate the landing page experience and QR code effectiveness.
- **At target across the board:** Feature is working as designed. Proceed with v2 enhancements.

---

## 13. Add Card Accessibility Requirements (P2)

### 13.1 Problem

The v1.0 spec has no mention of accessibility for the card composable or the shareable card screen. White text on gradient backgrounds may not meet WCAG contrast requirements, and the in-app preview has no semantic content descriptions.

### 13.2 Requirements

**In-App Card Preview (ShareableCardScreen):**

- Add `contentDescription` to the card preview image: `"Milestone card showing {title} — {stat_value} {stat_label}"`
- All share buttons must have content descriptions (Material 3 buttons handle this automatically with their text labels)
- Card screen title ("Your Achievement") announced by TalkBack on navigation

**Colour Contrast:**

Verify all gradient pairs meet WCAG AA contrast ratio (≥4.5:1) for normal text against white foreground:

| Gradient Start | Gradient End | White Text Contrast (against darkest) | Passes AA? |
|----------------|-------------|---------------------------------------|-----------|
| `#1E88E5` | `#1565C0` | ~4.3:1 | ⚠️ Borderline |
| `#F57C00` | `#E65100` | ~3.8:1 | ❌ Fails |
| `#7B1FA2` | `#4A148C` | ~10.2:1 | ✅ |
| `#00897B` | `#00695C` | ~4.8:1 | ✅ |
| `#FF6F00` | `#E65100` | ~3.6:1 | ❌ Fails |
| `#6A1B9A` | `#4A148C` | ~10.5:1 | ✅ |

**Action needed:** The orange/amber gradients (streak milestones) fail WCAG AA. Options:
- Darken the gradient end colours (e.g., `#BF360C` instead of `#E65100`)
- Add a semi-transparent dark overlay behind text regions
- Use a darker text colour for these specific gradients

**Recommendation:** Add a `Color.Black.copy(alpha = 0.15f)` scrim behind the text content area. This preserves the vibrant gradient appearance while improving contrast universally across all colour schemes.

**Exported Image:**

Exported PNG images are inherently not accessible (no alt text in image files). This is acceptable — the in-app experience is where accessibility matters. The card's visible text (`getpursue.app`, stat, title) serves as the accessible content for sighted recipients.

---

## 14. Harden Instagram Sharing Fallback Logic (P2)

### 14.1 Problem

The v1.0 spec relies on `com.instagram.share.ADD_TO_STORY`, which is the Facebook/Meta Content Sharing SDK intent. This requires a registered Facebook App ID and only supports Stories (not grid posts or DMs). The fallback to generic share sheet is mentioned but the decision tree isn't clear.

### 14.2 Updated Decision Tree

```
User taps "Share to Instagram Stories"
├── Instagram installed?
│   ├── YES → Try ADD_TO_STORY intent
│   │   ├── Success → Track milestone_card_shared_instagram
│   │   └── Failure (ActivityNotFoundException, SecurityException)
│   │       → Fall back to generic share sheet targeting Instagram package
│   │       → Track milestone_card_shared_instagram_fallback
│   └── NO → Button hidden (per §7.4)
```

### 14.3 Facebook App ID Requirement

The `com.instagram.share.ADD_TO_STORY` intent requires:
- A registered Facebook App ID in the app's manifest
- The `com.facebook.sdk.ApplicationId` meta-data entry

**If Pursue does not have a Facebook App ID:**

Skip the Stories-specific intent entirely. Use a targeted generic share intent instead:

```kotlin
fun shareToInstagram(context: Context, imageUri: Uri) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/*"
        putExtra(Intent.EXTRA_STREAM, imageUri)
        setPackage("com.instagram.android") // Target Instagram specifically
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // Instagram not installed or intent blocked — shouldn't happen
        // since we check installation before showing button
        Toast.makeText(context, "Unable to open Instagram", Toast.LENGTH_SHORT).show()
    }
}
```

This opens Instagram's built-in share flow (which lets the user choose Stories, Feed, or DM) without requiring a Facebook App ID. It's less seamless than the dedicated Stories intent but more reliable and doesn't require Meta SDK integration.

### 14.4 Recommendation

For v1, use the generic `ACTION_SEND` with `setPackage("com.instagram.android")`. This avoids the Facebook SDK dependency entirely and still provides a good Instagram sharing experience. Evaluate the Stories-specific intent for v2 if analytics show that most Instagram shares go to Stories.

---

## 15. Implementation Checklist

### P0 — Must ship with v1

- [ ] Update `shareable_card_data` schema: replace `background_color` with `background_gradient` (§2)
- [ ] Update `generateCardData()` to emit gradient arrays (§2)
- [ ] Update `MilestoneCard` composable to render gradient brush (§2)
- [ ] Create `referral_tokens` table and `generateReferralToken()` function (§3)
- [ ] Replace `utm_content=user_id` with `ref=token` in share URLs (§3)
- [ ] Create `user_milestone_grants` table (§4)
- [ ] Implement `canAwardMilestone()` with cooldown logic (§4)
- [ ] Update `checkMilestones()` to check grants before awarding (§4)
- [ ] Add freemium gating comment/constant confirming cards are available to all users (§5)

### P1 — Ship at launch or shortly after

- [ ] Add 14-day streak, 365-day streak, and 500 total logs milestones (§6)
- [ ] Create card templates and gradient colours for new milestones (§6)
- [ ] Swap share button hierarchy: generic share as primary, Instagram as secondary (§7)
- [ ] Add `milestone_card_share_target` analytics event tracking target package (§7)
- [ ] Hide Instagram button when not installed (§7)
- [ ] Integrate ZXing and add QR code to card image (§8)
- [ ] Implement `exportCardAsBitmap()` using `GraphicsLayer` capture (§9)
- [ ] Create and upload static milestone preview images for FCM (§10)
- [ ] Update FCM payload to include appropriate preview image URL (§10)

### P2 — Follow-up after launch

- [ ] Add content descriptions to ShareableCardScreen composables (§13)
- [ ] Audit gradient contrast ratios and add scrim if needed (§13)
- [ ] Implement tiered success metrics dashboard (§12)
- [ ] Use `ACTION_SEND` with Instagram package targeting instead of Stories-specific intent (§14)

---

## 16. Cross-Reference to Open Questions

Several items in this spec resolve open questions from other specs:

| Source | Open Question | Resolution |
|--------|--------------|------------|
| `pursue-notification-inbox-spec.md` Q3 | Milestone deduplication table design | §4 — adopt `user_milestone_grants` table with unique constraint |
| `shareable-milestone-cards-spec.md` Q1 | Card design variations | Defer to v2; premium-gated per §5.3 |
| `shareable-milestone-cards-spec.md` Q4 | Card regeneration after purge | Unchanged — Option B (ephemeral) for v1 |

---

**End of Enhancement Specification**
