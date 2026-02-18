# Pursue — Shareable Milestone Cards Spec

**Feature:** Shareable Milestone Achievement Cards  
**Version:** 1.0  
**Status:** Draft  
**Platform:** Android (Material Design 3)

---

## 1. Overview

### 1.1 Purpose

Transform milestone notifications from passive inbox items into viral acquisition tools. When users hit meaningful achievements (7-day streak, 30-day streak, 100 total logs, first log), they get a beautiful, branded card they can share to Instagram Stories, text to friends, or save to their camera roll. Every share becomes a targeted advertisement to the sharer's social circle — people who already know them and are interested in similar goals.

### 1.2 Core Insight

The best growth channels are:
1. **Targeted**: Sharers' friends already know their goals and interests
2. **Credible**: Social proof from someone they trust
3. **Timely**: Shared at moments of genuine achievement/excitement
4. **Free**: No ad spend required

### 1.3 Success Metrics

- **Engagement**: % of milestone achievers who open the shareable card
- **Share Rate**: % of card viewers who share externally
- **Acquisition**: New signups attributed to milestone card shares (via UTM tracking)
- **Virality Coefficient**: New users per existing user (target: >0.3)

### 1.4 Design Goals

- **Beautiful**: Instagram-worthy design that users are proud to share
- **Branded**: Subtle "Pursue" branding that doesn't overshadow achievement
- **Authentic**: Celebrates real progress, not fake accomplishments
- **Friction-free**: 2 taps from milestone to shared card
- **Platform-optimized**: Correct aspect ratios for Instagram Stories, text messages

---

## 2. Milestone Types & Triggers

### 2.1 Shareable Milestones

All of these already trigger `milestone_achieved` notifications in the inbox:

| Milestone | Trigger Condition | Card Title | Emotional Hook |
|-----------|------------------|------------|----------------|
| **First Log** | User's very first progress entry in the app | "First step taken" | "Every journey begins somewhere" |
| **7-Day Streak** | 7 consecutive days of logging a specific goal | "7-day streak!" | "Consistency is everything" |
| **30-Day Streak** | 30 consecutive days of logging a specific goal | "30-day streak!" | "A month of dedication" |
| **100 Total Logs** | User has logged 100 total progress entries across all goals | "100 logs milestone" | "Proof that showing up works" |

**Note:** These are the same milestones tracked in `pursue-notification-inbox-spec.md` section 2.7. No new backend milestone detection logic is required.

### 2.2 Non-Shareable Notifications

These inbox notifications do NOT get shareable cards due to privacy concerns:
- Reactions received (contains someone else's emoji/action)
- Nudges received (group member names)
- Membership events (group names, admin names)
- Weekly group recaps (aggregate group data, multiple members' stats)

**Principle:** Only share content that is purely personal. If the card would reveal information about other users or groups, it should not be shareable.

---

## 3. Database Schema Changes

### 3.1 New Column: `user_notifications.shareable_card_data`

Add to existing `user_notifications` table:

```sql
ALTER TABLE user_notifications
ADD COLUMN shareable_card_data JSONB;
```

**Purpose:** Store pre-rendered card metadata at milestone creation time. This prevents data inconsistencies if the user's goal title changes later, or if the goal/group is deleted.

**Schema for `shareable_card_data`:**

```json
{
  "milestone_type": "streak" | "first_log" | "total_logs",
  "title": "7-day streak!",
  "subtitle": "30 min run",
  "stat_value": "7",
  "stat_label": "days in a row",
  "quote": "Consistency is everything",
  "goal_icon_emoji": "🏃",
  "background_color": "#1E88E5",
  "generated_at": "2026-02-18T10:30:00Z"
}
```

**Field definitions:**

| Field | Type | Description | Example |
|-------|------|-------------|---------|
| `milestone_type` | string | Type of milestone | `"streak"` |
| `title` | string | Main headline | `"7-day streak!"` |
| `subtitle` | string | Goal title or context | `"30 min run"` |
| `stat_value` | string | Big number to display | `"7"` |
| `stat_label` | string | What the number means | `"days in a row"` |
| `quote` | string | Inspirational tagline | `"Consistency is everything"` |
| `goal_icon_emoji` | string | Goal's icon (optional) | `"🏃"` |
| `background_color` | string | Card background color (hex) | `"#1E88E5"` |
| `generated_at` | ISO timestamp | When card data was created | `"2026-02-18T10:30:00Z"` |

**Why snapshot at creation time?**
- Goal might be renamed/deleted later
- Group might be renamed/deleted
- User should always be able to regenerate the same card they saw originally

### 3.2 Migration Script

```sql
-- Add column (defaults to NULL for existing rows)
ALTER TABLE user_notifications
ADD COLUMN shareable_card_data JSONB;

-- Create index for filtering shareable milestones
CREATE INDEX idx_user_notifications_shareable 
ON user_notifications(user_id, type) 
WHERE shareable_card_data IS NOT NULL;
```

---

## 4. Backend Changes

### 4.1 Milestone Creation Logic Update

In the existing `checkMilestones()` function (see `pursue-notification-inbox-spec.md` section 5.5), update the notification insertion to include shareable card data:

```typescript
async function checkMilestones(userId: string, goalId: string, groupId: string) {
  const totalLogs = await countUserLogs(userId);
  const streak = await getCurrentStreak(userId, goalId);
  const goal = await getGoalById(goalId); // Fetch goal for title/icon

  const milestones = [
    {
      condition: totalLogs === 1,
      type: 'milestone_achieved',
      metadata: { milestone_type: 'first_log' },
      cardData: generateCardData('first_log', goal, { count: 1 })
    },
    {
      condition: streak === 7,
      type: 'milestone_achieved',
      metadata: { milestone_type: 'streak', streak_count: 7 },
      cardData: generateCardData('streak', goal, { count: 7 })
    },
    {
      condition: streak === 30,
      type: 'milestone_achieved',
      metadata: { milestone_type: 'streak', streak_count: 30 },
      cardData: generateCardData('streak', goal, { count: 30 })
    },
    {
      condition: totalLogs === 100,
      type: 'milestone_achieved',
      metadata: { milestone_type: 'total_logs', count: 100 },
      cardData: generateCardData('total_logs', goal, { count: 100 })
    },
  ];

  for (const m of milestones) {
    if (m.condition) {
      await db.insertInto('user_notifications').values({
        user_id: userId,
        type: m.type,
        actor_user_id: null,
        group_id: groupId,
        goal_id: goalId,
        metadata: m.metadata,
        shareable_card_data: m.cardData // NEW: Store card data
      }).execute();
    }
  }
}
```

### 4.2 Card Data Generation Function

```typescript
function generateCardData(
  milestoneType: 'first_log' | 'streak' | 'total_logs',
  goal: Goal | null,
  params: { count: number }
): object {
  const { count } = params;

  // Card content templates
  const templates = {
    first_log: {
      title: "First step taken",
      quote: "Every journey begins somewhere",
      stat_label: "progress logged"
    },
    streak: {
      title: count === 7 ? "7-day streak!" : "30-day streak!",
      quote: count === 7 ? "Consistency is everything" : "A month of dedication",
      stat_label: "days in a row"
    },
    total_logs: {
      title: "100 logs milestone",
      quote: "Proof that showing up works",
      stat_label: "total logs"
    }
  };

  const template = templates[milestoneType];
  
  // Determine subtitle
  let subtitle = "Pursue Goals";
  if (goal) {
    subtitle = goal.title;
  }

  // Pick background color based on milestone type
  const colors = {
    first_log: "#1E88E5", // Blue
    streak: "#F57C00",    // Orange
    total_logs: "#7B1FA2" // Purple
  };

  return {
    milestone_type: milestoneType,
    title: template.title,
    subtitle: subtitle,
    stat_value: count.toString(),
    stat_label: template.stat_label,
    quote: template.quote,
    goal_icon_emoji: goal?.icon_emoji || "🎯",
    background_color: colors[milestoneType],
    generated_at: new Date().toISOString()
  };
}
```

### 4.3 API Endpoint: Fetch Card Data

Extend the existing notification fetch endpoint to include `shareable_card_data`:

```
GET /api/notifications?limit=30&before_id={cursor_uuid}
Authorization: Bearer {access_token}
```

**Updated Response (200 OK):**

```json
{
  "notifications": [
    {
      "id": "notif-uuid",
      "type": "milestone_achieved",
      "is_read": false,
      "created_at": "2026-02-18T07:45:00Z",
      "actor": null,
      "group": {
        "id": "group-uuid",
        "name": "Morning Runners"
      },
      "goal": {
        "id": "goal-uuid",
        "title": "30 min run"
      },
      "progress_entry_id": null,
      "metadata": {
        "milestone_type": "streak",
        "streak_count": 7
      },
      "shareable_card_data": {
        "milestone_type": "streak",
        "title": "7-day streak!",
        "subtitle": "30 min run",
        "stat_value": "7",
        "stat_label": "days in a row",
        "quote": "Consistency is everything",
        "goal_icon_emoji": "🏃",
        "background_color": "#F57C00",
        "generated_at": "2026-02-18T07:45:00Z"
      }
    }
  ],
  "unread_count": 1,
  "has_more": false
}
```

**Key change:** `shareable_card_data` is now returned for milestone notifications. This field is `null` for non-shareable notification types (reactions, nudges, etc.).

---

## 5. Android UI Implementation

### 5.1 Notification Inbox Row Upgrade

Milestone notification rows need a visual indicator that they're shareable.

**Current design (from `pursue-notification-inbox-spec.md`):**

```
┌─────────────────────────────────────────────────────────┐
│ [🎉]  You hit a 7-day streak on 30 min run!            │
│       Morning Runners · 2 hours ago                 [●] │
└─────────────────────────────────────────────────────────┘
```

**New design (with share indicator):**

```
┌─────────────────────────────────────────────────────────┐
│ [🎉]  You hit a 7-day streak on 30 min run!        [📤] │
│       Morning Runners · 2 hours ago                 [●] │
└─────────────────────────────────────────────────────────┘
```

**Changes:**
- Add a subtle **share icon (📤)** on the right side of shareable milestone rows
- Icon uses secondary color with 70% opacity (not too prominent)
- Tapping the **entire row** opens the shareable card screen
- Tapping the **share icon directly** opens the Android share sheet immediately

**Implementation notes:**
- Check if `notification.shareable_card_data != null` to determine if share icon should show
- Use Material Icon: `Icons.Outlined.Share` (not filled, keeps it subtle)
- Share icon should be 20dp size, aligned to right edge with 8dp margin

### 5.2 Shareable Card Screen

**Route:** `ShareableCardScreen(notificationId: UUID)`  
**Trigger:** Tapping a milestone notification row in the inbox  
**Purpose:** Display the beautiful achievement card with sharing options

#### 5.2.1 Screen Layout

```
┌──────────────────────────────────────────────────────────┐
│  ← Back                                                   │ ← Top App Bar
├──────────────────────────────────────────────────────────┤
│                                                           │
│                                                           │
│  ┌───────────────────────────────────────────────────┐  │
│  │                                                    │  │
│  │              [Milestone Card Preview]             │  │ ← Card Preview
│  │                                                    │  │  (see 5.2.2)
│  │              1080×1920 aspect ratio               │  │
│  │                                                    │  │
│  └───────────────────────────────────────────────────┘  │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  📤  Share to Instagram Stories                     │ │ ← Primary CTA
│  └─────────────────────────────────────────────────────┘ │
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  💬  Share via...                                   │ │ ← Secondary CTA
│  └─────────────────────────────────────────────────────┘ │  (Android Share Sheet)
│                                                           │
│  ┌─────────────────────────────────────────────────────┐ │
│  │  💾  Save to Photos                                 │ │ ← Tertiary CTA
│  └─────────────────────────────────────────────────────┘ │
│                                                           │
└──────────────────────────────────────────────────────────┘
```

**Components:**

1. **Top App Bar**
   - Title: "Your Achievement"
   - Back button (returns to notification inbox)
   - No share icon here (share buttons are in the card itself)

2. **Card Preview**
   - Displays the milestone card (see 5.2.2 for design)
   - Aspect ratio: 9:16 (1080×1920 — Instagram Stories size)
   - Shadow: Material elevation 2dp
   - Padding: 16dp from screen edges

3. **Share to Instagram Stories** (Primary CTA)
   - **Icon:** Instagram logo (use Material Icon `Icons.Outlined.PhotoCamera` or custom Instagram icon)
   - **Text:** "Share to Instagram Stories"
   - **Style:** Filled button, primary color
   - **Behavior:** 
     - Exports card as PNG at 1080×1920
     - Launches Instagram via Android Share Intent with `com.instagram.share.ADD_TO_STORY` action
     - Falls back to generic share sheet if Instagram not installed

4. **Share via...** (Secondary CTA)
   - **Icon:** `Icons.Outlined.Share`
   - **Text:** "Share via..."
   - **Style:** Outlined button, secondary color
   - **Behavior:** Opens Android share sheet with exported PNG
     - User can share to WhatsApp, Messenger, SMS, email, etc.
     - Include share text: "Check out my progress on Pursue! 🎉"

5. **Save to Photos** (Tertiary CTA)
   - **Icon:** `Icons.Outlined.Download`
   - **Text:** "Save to Photos"
   - **Style:** Text button, on-surface color
   - **Behavior:** 
     - Exports card as PNG
     - Saves to device's Pictures/Pursue folder
     - Shows toast: "Saved to Photos ✓"
     - Requests `WRITE_EXTERNAL_STORAGE` permission (Android <13) or `MediaStore` API (Android 13+)

#### 5.2.2 Milestone Card Design

The card itself is a self-contained Compose component rendered as a 1080×1920px image.

**Visual Hierarchy (top to bottom):**

```
┌────────────────────────────────────────────┐
│                                            │ ← Gradient background
│                                            │   (color from shareable_card_data)
│         [Goal Icon Emoji]                  │
│            60dp size                       │
│                                            │
│                                            │
│            7-day streak!                   │ ← Title (36sp, bold)
│            30 min run                      │ ← Subtitle (20sp, medium)
│                                            │
│                                            │
│              ┌──────────┐                  │
│              │    7     │                  │ ← Stat value (96sp, bold)
│              └──────────┘                  │
│            days in a row                   │ ← Stat label (16sp, medium)
│                                            │
│                                            │
│                                            │
│      "Consistency is everything"           │ ← Quote (18sp, italic)
│                                            │
│                                            │
│                                            │
│  ───────────────────────────────────────  │
│                                            │
│  Track goals with friends on Pursue        │ ← Branding (14sp, light)
│  getpursue.app                             │   (white @ 50% opacity)
│                                            │
└────────────────────────────────────────────┘
```

**Specifications:**

| Element | Font | Size | Weight | Color |
|---------|------|------|--------|-------|
| Background | — | Full card | — | Linear gradient (color from API → darker shade) |
| Goal Icon | — | 60dp | — | White emoji |
| Title | Roboto | 36sp | Bold | White |
| Subtitle | Roboto | 20sp | Medium | White @ 90% opacity |
| Stat Value | Roboto Condensed | 96sp | Bold | White |
| Stat Label | Roboto | 16sp | Medium | White @ 80% opacity |
| Quote | Roboto | 18sp | Italic | White @ 70% opacity |
| Branding | Roboto | 14sp | Light | White @ 50% opacity |

**Background Gradient:**
- Start: `background_color` from API (top)
- End: Darken by 30% (bottom)
- Direction: Top-to-bottom linear gradient
- This creates depth and visual interest

**Padding & Spacing:**
- Card edges: 40dp padding
- Icon to title: 48dp spacing
- Title to subtitle: 8dp spacing
- Subtitle to stat value: 80dp spacing
- Stat value to stat label: 8dp spacing
- Stat label to quote: 80dp spacing
- Quote to branding: 120dp spacing (pushes branding to bottom)

**Branding Section:**
- Subtle horizontal divider line (1dp, white @ 20% opacity)
- 16dp above text
- Two lines:
  - "Track goals with friends on Pursue"
  - "getpursue.app"
- Center-aligned
- NOT clickable (this is an exported image)

**Instagram Stories Optimization:**
- Size: 1080×1920px (9:16 aspect ratio)
- Safe zone: Keep all text/logos 180px from top/bottom edges (accounts for Instagram UI overlays)
- Background must fill entire frame (no letterboxing)
- Export as PNG with sRGB color space

#### 5.2.3 Card Rendering Implementation

**Approach:** Use Jetpack Compose's `Canvas` or `View.drawToBitmap()` to render the card as a bitmap.

**Pseudo-code:**

```kotlin
@Composable
fun MilestoneCard(
    cardData: ShareableCardData,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .aspectRatio(9f / 16f) // Instagram Stories ratio
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(cardData.background_color),
                        Color(cardData.background_color).darken(0.3f)
                    )
                )
            )
            .padding(40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Goal Icon
            Text(
                text = cardData.goal_icon_emoji,
                fontSize = 60.sp
            )
            
            Spacer(modifier = Modifier.height(48.dp))
            
            // Title
            Text(
                text = cardData.title,
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Subtitle
            Text(
                text = cardData.subtitle,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.9f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(80.dp))
            
            // Stat Value
            Text(
                text = cardData.stat_value,
                fontSize = 96.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                color = Color.White
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Stat Label
            Text(
                text = cardData.stat_label,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White.copy(alpha = 0.8f)
            )
            
            Spacer(modifier = Modifier.height(80.dp))
            
            // Quote
            Text(
                text = "\"${cardData.quote}\"",
                fontSize = 18.sp,
                fontStyle = FontStyle.Italic,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(120.dp))
            
            // Branding
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Divider(
                    modifier = Modifier
                        .width(200.dp)
                        .padding(bottom = 16.dp),
                    color = Color.White.copy(alpha = 0.2f),
                    thickness = 1.dp
                )
                Text(
                    text = "Track goals with friends on Pursue",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Text(
                    text = "getpursue.app",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White.copy(alpha = 0.5f)
                )
            }
        }
    }
}

fun exportCardAsBitmap(composable: @Composable () -> Unit): Bitmap {
    // Render composable to bitmap at 1080×1920 resolution
    // Use ComposeView.drawToBitmap() or similar approach
}
```

### 5.3 Share Intent Implementation

#### 5.3.1 Share to Instagram Stories

Instagram supports a dedicated Stories sharing intent. This provides a native experience.

```kotlin
fun shareToInstagramStories(context: Context, imageBitmap: Bitmap) {
    // Save bitmap to cache directory
    val imageUri = saveBitmapToCache(context, imageBitmap, "milestone_card.png")
    
    // Create Instagram Stories intent
    val intent = Intent("com.instagram.share.ADD_TO_STORY").apply {
        type = "image/png"
        putExtra("interactive_asset_uri", imageUri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        setPackage("com.instagram.android")
    }
    
    // Check if Instagram is installed
    if (context.packageManager.resolveActivity(intent, 0) != null) {
        context.startActivity(intent)
    } else {
        // Fallback: Show toast and open generic share sheet
        Toast.makeText(context, "Instagram not installed", Toast.LENGTH_SHORT).show()
        shareViaGenericIntent(context, imageBitmap)
    }
}
```

**Instagram Stories Intent Details:**
- Action: `com.instagram.share.ADD_TO_STORY`
- Type: `image/png` or `image/jpeg`
- Extra: `interactive_asset_uri` (URI to image)
- Package: `com.instagram.android`
- Requires: `FLAG_GRANT_READ_URI_PERMISSION`

**User Flow:**
1. User taps "Share to Instagram Stories"
2. App exports card as PNG to cache
3. Instagram app opens with card pre-loaded as a sticker
4. User can position/resize card, add text, and post to their story

#### 5.3.2 Generic Share Intent

```kotlin
fun shareViaGenericIntent(context: Context, imageBitmap: Bitmap) {
    val imageUri = saveBitmapToCache(context, imageBitmap, "milestone_card.png")
    
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, imageUri)
        putExtra(Intent.EXTRA_TEXT, "Check out my progress on Pursue! 🎉 getpursue.app")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    
    context.startActivity(Intent.createChooser(shareIntent, "Share your achievement"))
}
```

**Share Text:**
- "Check out my progress on Pursue! 🎉 getpursue.app"
- Include UTM parameters in URL: `getpursue.app?utm_source=share&utm_medium=milestone_card&utm_campaign=7day_streak`

**Supported platforms via Android Share Sheet:**
- WhatsApp, Messenger, Telegram (messaging)
- Instagram, Facebook, Twitter (social media)
- SMS, Email (traditional)
- Files app (save/send)

#### 5.3.3 Save to Photos

```kotlin
fun saveToPhotos(context: Context, imageBitmap: Bitmap) {
    val filename = "Pursue_${System.currentTimeMillis()}.png"
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        // Use MediaStore API (Android 10+)
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pursue")
        }
        
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        
        uri?.let {
            context.contentResolver.openOutputStream(it).use { out ->
                imageBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Toast.makeText(context, "Saved to Photos ✓", Toast.LENGTH_SHORT).show()
        }
    } else {
        // Legacy external storage (Android <10)
        val imagesDir = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_PICTURES
        )
        val pursueDir = File(imagesDir, "Pursue").apply { mkdirs() }
        val file = File(pursueDir, filename)
        
        FileOutputStream(file).use { out ->
            imageBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        
        // Notify media scanner
        MediaScannerConnection.scanFile(
            context,
            arrayOf(file.absolutePath),
            arrayOf("image/png"),
            null
        )
        
        Toast.makeText(context, "Saved to Photos ✓", Toast.LENGTH_SHORT).show()
    }
}
```

**Permissions:**
- Android 13+: No permission needed (scoped storage)
- Android 10-12: No permission needed (MediaStore API)
- Android <10: Requires `WRITE_EXTERNAL_STORAGE` permission

**Folder Structure:**
- Path: `Pictures/Pursue/`
- Filename: `Pursue_<timestamp>.png`
- This keeps all milestone cards organized in one folder

---

## 6. UTM Tracking & Attribution

### 6.1 Share URL Format

When sharing via text/email/social (not Instagram Stories), include UTM parameters:

```
https://getpursue.app?utm_source=share&utm_medium=milestone_card&utm_campaign={milestone_type}&utm_content={user_id}
```

**Parameters:**
- `utm_source`: Always `"share"`
- `utm_medium`: Always `"milestone_card"`
- `utm_campaign`: Milestone type (`first_log`, `streak_7`, `streak_30`, `total_logs_100`)
- `utm_content`: User ID (for tracking which user generated the share)

**Example:**
```
https://getpursue.app?utm_source=share&utm_medium=milestone_card&utm_campaign=streak_7&utm_content=user-abc123
```

### 6.2 Landing Page Behavior

When a new user clicks the share link:
1. Track UTM parameters in analytics (Google Analytics, Mixpanel, etc.)
2. Show marketing page with social proof: "Your friend just hit a 7-day streak! Join Pursue to track your goals with friends."
3. CTA: "Download on Play Store"
4. Attribute signups to the sharing user for viral coefficient tracking

### 6.3 Analytics Events

Track these events in the app:

| Event Name | When | Properties |
|------------|------|-----------|
| `milestone_card_viewed` | User opens shareable card screen | `notification_id`, `milestone_type` |
| `milestone_card_shared_instagram` | User taps Instagram Stories share | `notification_id`, `milestone_type` |
| `milestone_card_shared_generic` | User shares via Android share sheet | `notification_id`, `milestone_type` |
| `milestone_card_saved` | User saves to photos | `notification_id`, `milestone_type` |

**Key metric:**
- **Share rate** = `milestone_card_shared_*` / `milestone_card_viewed`
- Target: >15% of milestone achievers share their card

---

## 7. Push Notification Update

### 7.1 Enhanced FCM Payload

Update the milestone notification push (from `pursue-notification-inbox-spec.md` section 6.1):

**Current payload:**
```json
{
  "notification": {
    "title": "🎉 7-day streak!",
    "body": "You've logged 30 min run 7 days in a row. Keep it up!"
  },
  "data": {
    "type": "milestone_achieved",
    "notification_id": "notif-uuid"
  }
}
```

**New payload (with share CTA):**
```json
{
  "notification": {
    "title": "🎉 7-day streak!",
    "body": "You've logged 30 min run 7 days in a row. Tap to share!",
    "image": "https://storage.googleapis.com/pursue-assets/milestone-preview-7day.png"
  },
  "data": {
    "type": "milestone_achieved",
    "notification_id": "notif-uuid",
    "shareable": "true"
  }
}
```

**Changes:**
1. **Body text:** Add "Tap to share!" to encourage interaction
2. **Image (optional):** Include a small preview of the milestone card (generated server-side or generic template)
3. **Data flag:** `"shareable": "true"` signals client to show share UI

**Notification tap behavior:**
- Opens shareable card screen directly (not just the inbox)
- Marks notification as read
- Shows card with share buttons

---

## 8. Future Enhancements (v2)

### 8.1 Additional Milestones

- **First group joined**: "You're part of the team!"
- **First goal created**: "Your journey begins"
- **Perfect week**: All goals logged 7 days straight
- **Group milestone**: "Your group hit 1,000 logs together"

### 8.2 Card Customization

- Let users pick from 3-5 background colors/gradients
- Add optional photo overlay (user's progress photo)
- Custom quote input field

### 8.3 Video Cards

- Export as 5-second video with animation
  - Stat number counts up (0 → 7)
  - Confetti animation
  - Background gradient pulses
- Share to Instagram Reels, TikTok

### 8.4 Group Leaderboards

- Weekly/monthly group achievement cards
- "Your group hit 30 days together!"
- Show all members' avatars in a circle
- Privacy: Only shareable by group creator/admins

### 8.5 A/B Testing

Test variations of:
- Card design (minimal vs. bold, light vs. dark backgrounds)
- Share button copy ("Share Your Win" vs. "Tell Your Friends")
- Quote text (motivational vs. factual)
- Branding prominence (large logo vs. small watermark)

Optimize for **share rate** as primary metric.

---

## 9. Privacy & Safety

### 9.1 Content Moderation

- Milestone cards only contain user's own data (no risk of inappropriate content)
- Goal titles are user-generated but limited to 50 characters (already enforced)
- No profanity filter needed (user controls their own achievement messaging)

### 9.2 Opt-Out Mechanism

**Settings Screen:**
- Add toggle: "Show shareable milestone cards" (default: ON)
- If disabled:
  - Milestones still appear in notification inbox
  - No share icon shown
  - No shareable card screen
  - Push notifications say "You hit a 7-day streak!" (no "Tap to share")

**Reasoning:** Some users may not want social sharing pressure. Respect this preference.

### 9.3 Data Sharing

- Shared cards contain NO personal information beyond what the user chooses to share
- No user ID, email, or group names on the card
- Only: milestone type, goal title (user-created), stat, and Pursue branding

---

## 10. Implementation Checklist

### 10.1 Backend

- [ ] Add `shareable_card_data` column to `user_notifications` table
- [ ] Create migration script
- [ ] Update `checkMilestones()` function to populate `shareable_card_data`
- [ ] Create `generateCardData()` helper function
- [ ] Update `GET /api/notifications` to return `shareable_card_data`
- [ ] Update FCM push payload for milestones (add "Tap to share!" and `shareable: true`)
- [ ] Create UTM tracking endpoint (log share attribution)

### 10.2 Android

**Data Layer:**
- [ ] Update `UserNotification` model to include `shareable_card_data: ShareableCardData?`
- [ ] Parse `shareable_card_data` JSON in notification repository

**UI Components:**
- [ ] Add share icon to milestone notification rows (conditional on `shareable_card_data != null`)
- [ ] Create `ShareableCardScreen` composable
- [ ] Create `MilestoneCard` composable (renders card design)
- [ ] Implement `exportCardAsBitmap()` utility function

**Sharing Logic:**
- [ ] Implement `shareToInstagramStories()` intent handler
- [ ] Implement `shareViaGenericIntent()` with share text + UTM URL
- [ ] Implement `saveToPhotos()` with MediaStore API
- [ ] Request storage permissions for Android <13
- [ ] Handle Instagram not installed fallback

**Navigation:**
- [ ] Update notification row tap handler: if shareable milestone, navigate to `ShareableCardScreen`
- [ ] Update FCM push tap handler: if `data.shareable == "true"`, open `ShareableCardScreen` directly

**Analytics:**
- [ ] Track `milestone_card_viewed` event
- [ ] Track `milestone_card_shared_instagram` event
- [ ] Track `milestone_card_shared_generic` event
- [ ] Track `milestone_card_saved` event

**Settings (Optional):**
- [ ] Add "Show shareable milestone cards" toggle to Settings screen
- [ ] Respect toggle in notification row rendering
- [ ] Update FCM push text based on toggle state

### 10.3 Testing

**Unit Tests:**
- [ ] Test `generateCardData()` for all milestone types
- [ ] Test `shareable_card_data` JSON parsing
- [ ] Test share URL generation with UTM parameters

**Integration Tests:**
- [ ] Test milestone notification creation includes `shareable_card_data`
- [ ] Test notification fetch returns `shareable_card_data`
- [ ] Test share icon shows only for shareable milestones

**UI Tests:**
- [ ] Test card rendering at 1080×1920 resolution
- [ ] Test Instagram intent launches correctly
- [ ] Test generic share sheet opens with correct text
- [ ] Test save to photos creates file in Pictures/Pursue folder
- [ ] Test card displays all elements (title, subtitle, stat, quote, branding)

**Manual Tests:**
- [ ] Verify card looks good on real device screens (various sizes)
- [ ] Test actual Instagram Stories sharing end-to-end
- [ ] Test share via WhatsApp, Messages, Email
- [ ] Verify UTM tracking works in Google Analytics
- [ ] Check card rendering performance (should be <2 seconds to export)

---

## 11. Success Criteria

### 11.1 Launch Metrics (First 30 Days)

| Metric | Target | Measurement |
|--------|--------|-------------|
| **Milestone card view rate** | >60% of milestone achievers view card | `milestone_card_viewed` / milestone notifications sent |
| **Share rate** | >15% of card viewers share externally | (`milestone_card_shared_instagram` + `milestone_card_shared_generic`) / `milestone_card_viewed` |
| **Instagram share preference** | >40% of shares go to Instagram | `milestone_card_shared_instagram` / total shares |
| **Attribution signups** | >10 new users from milestone shares | UTM tracking on `getpursue.app` |
| **Viral coefficient** | >0.05 (5 shares per 100 users) | Total shares / total active users |

### 11.2 Long-Term Goals (6 Months)

- Milestone card shares become **top 3** acquisition channel (alongside organic search, word-of-mouth)
- Achieve **virality coefficient >0.3** (30 new users per 100 existing users via shares)
- Instagram Stories shares generate **>500 impressions per share** on average
- Users who share milestone cards have **2x higher retention** than non-sharers (sharing signals engagement)

---

## 12. Open Questions

1. **Card design variations:** Should we offer multiple card templates (minimal, bold, dark mode) or stick with one canonical design for brand consistency?
   - **Recommendation:** Start with one template for v1, add variations in v2 based on user feedback.

2. **Animated cards:** Should milestone cards have subtle animations (stat count-up, confetti) when displayed in-app?
   - **Recommendation:** Not for v1 (adds complexity). Static cards are easier to export and share.

3. **Group milestones:** Should we create shareable cards for group achievements (e.g., "Our group hit 1,000 logs!")?
   - **Concern:** Privacy — card would need to aggregate group data, potentially expose member info.
   - **Recommendation:** Personal milestones only for v1. Group cards require more privacy consideration.

4. **Card regeneration:** If a user loses their milestone notification (90-day purge), can they regenerate the card?
   - **Option A:** Store `shareable_card_data` permanently in a separate `milestone_achievements` table.
   - **Option B:** Notifications are ephemeral; once deleted, card is gone.
   - **Recommendation:** Option B for v1 (simpler). Add Option A if users request "milestone history" feature.

5. **Photo attachments:** Should milestone cards support adding a photo from the progress log (if user attached one)?
   - **Concern:** Adds complexity to card layout, may distract from achievement stat.
   - **Recommendation:** Not for v1. Explore in v2 if users request it.

---

## 13. Appendix: Example Card Outputs

### 13.1 First Log Milestone

```
Background: Blue gradient (#1E88E5 → #1565C0)
Icon: 🎯 (Goal icon or default target)
Title: "First step taken"
Subtitle: "Morning Run"
Stat: 1 | progress logged
Quote: "Every journey begins somewhere"
```

### 13.2 7-Day Streak Milestone

```
Background: Orange gradient (#F57C00 → #E65100)
Icon: 🏃 (Goal icon from "30 min run")
Title: "7-day streak!"
Subtitle: "30 min run"
Stat: 7 | days in a row
Quote: "Consistency is everything"
```

### 13.3 30-Day Streak Milestone

```
Background: Orange gradient (#F57C00 → #E65100)
Icon: 📚 (Goal icon from "Read 50 pages")
Title: "30-day streak!"
Subtitle: "Read 50 pages"
Stat: 30 | days in a row
Quote: "A month of dedication"
```

### 13.4 100 Total Logs Milestone

```
Background: Purple gradient (#7B1FA2 → #4A148C)
Icon: 🎯 (Default Pursue icon)
Title: "100 logs milestone"
Subtitle: "Pursue Goals"
Stat: 100 | total logs
Quote: "Proof that showing up works"
```

---

**End of Specification**
