# Pursue UI Specification

**Version:** 0.3 (UI States & Error Handling)  
**Last Updated:** January 22, 2026  
**Status:** Implementation Ready  
**Platform:** Android (Material Design 3)

---

## 1. Overview

### 1.1 Purpose
This document defines the user interface, user experience, and visual design for the Pursue mobile application. It covers screen layouts, navigation patterns, interaction flows, and design principles for the centralized architecture.

**Design Goals:** Build a production-quality mobile experience that delights users and scales gracefully. This specification prioritizes:
- **User Retention**: Smooth onboarding, instant feedback, rewarding interactions
- **Performance**: Fast startup (<2s), smooth scrolling (60fps), efficient memory usage
- **Reliability**: Graceful offline handling, robust error recovery, data persistence
- **Accessibility**: WCAG 2.1 AA compliance, colorblind-friendly, screen reader support
- **Polish**: Material Design 3, fluid animations, thoughtful micro-interactions
- **Scalability**: Efficient list rendering, pagination, image caching

### 1.2 Design Philosophy
- **Clear and Focused**: Minimize distractions, highlight what matters (today's goals and progress)
- **Efficient**: Fast app startup, minimal animations, direct access to core functions
- **Encouraging**: Use positive language, celebrate achievements, gentle nudges
- **Instant Sync**: Leverage centralized server for real-time updates (like WhatsApp, Slack)
- **Accessible**: Large touch targets, high contrast, readable fonts, colorblind-friendly palette
- **Privacy-Conscious**: Clear data policies, no ads, local data encryption
- **Professional**: Blue and gold palette conveys trust, achievement, and reliability

### 1.3 Key UX Improvements (vs P2P)
- ✅ No "pending sync" states (instant server updates)
- ✅ No complex sync status indicators (simple loading states)
- ✅ Standard email/password login (familiar pattern)
- ✅ Faster group joins (server has all data)
- ✅ Real-time updates via push notifications

### 1.4 UI State Management

All data-loading screens (Home, Today, Profile, My Progress) implement consistent 5-state pattern:

**State 1: Loading**
- Shimmer/skeleton screens during API calls
- No blank white screens or spinners
- Provides visual feedback that content is loading

**State 2: Success - With Data**
- Display content normally
- RecyclerView for lists, cards for details

**State 3: Success - Empty**
- Show ONLY when API succeeds but returns 0 items
- Friendly illustration + helpful message
- Clear CTAs (Join Group, Create Goal, etc.)
- Different from Error State

**State 4: Error**
- Show when API call fails (network, server, timeout, unauthorized)
- Error icon varies by error type (📶 ⚠️ ⏱️ 🔒)
- Clear error title and message
- "Retry" button to attempt reload
- Toast notification for immediate feedback

**State 5: Offline - Cached Data**
- Show when network fails BUT cached data exists
- Display cached content (slightly dimmed)
- Persistent banner: "Offline - showing cached data"
- Snackbar with "Retry" action

**State Priority Decision Tree:**
```
API Request
  ↓
Loading? → Show Shimmer
  ↓
Success?
  ├─ YES → Has Data?
  │         ├─ YES → Show List/Content
  │         └─ NO → Show Empty State (friendly, CTAs)
  └─ NO → Has Cache?
           ├─ YES → Show Offline State (cache + banner)
           └─ NO → Show Error State (retry button)
```

**Key Principle:** Empty State ≠ Error State
- Empty = API worked, user has 0 items (needs onboarding)
- Error = API failed, can't load data (needs retry)

### 1.5 Target Users
- **Primary**: Adults 25-45 seeking accountability for personal goals
- **Secondary**: Students, fitness enthusiasts, professionals with work goals
- **Technical Level**: Range from basic smartphone users to tech-savvy power users

---

## 2. Design System

### 2.1 Color Palette

**Primary Colors:**
```
Primary (Brand):     #1976D2 (Blue 700) - Trust, progress, clarity
Primary Variant:     #1565C0 (Blue 800) - Darker accent
On Primary:          #FFFFFF (White) - Text on primary

Secondary:           #F9A825 (Yellow 800) - Achievement, energy, warmth
Secondary Variant:   #F57F17 (Yellow 900) - Darker accent
On Secondary:        #000000 (Black) - Text on secondary
```

**Surface Colors:**
```
Background:          #FAFAFA (Light grey) - Main app background
Surface:             #FFFFFF (White) - Cards, dialogs
Surface Variant:     #F5F5F5 (Lighter grey) - Secondary surfaces
On Surface:          #212121 (Almost black) - Primary text
On Surface Variant:  #757575 (Grey 600) - Secondary text
```

**Semantic Colors:**
```
Success:             #1976D2 (Blue 700) - Completed goals
Warning:             #F9A825 (Yellow 800) - Attention needed
Error:               #D32F2F (Red 700) - Failed operations, errors
Info:                #0288D1 (Light Blue 700) - Information, tips

Goal Completed:      #1976D2 (Blue)
Goal Incomplete:     #E0E0E0 (Grey 300)
Goal Overdue:        #FFE082 (Yellow 200)
```

**Group Member Colors (Colorblind-Friendly Palette):**
- Blue: #1976D2, Gold: #F9A825, Teal: #00897B, Purple: #7B1FA2
- Orange: #F57C00, Pink: #C2185B, Brown: #5D4037, Grey: #616161

### 2.2 Typography

**Font Family:** Roboto (Android system default)

**Text Styles:**
```
Headline Large:      32sp, Medium (500)
Headline Medium:     28sp, Medium (500)
Title Large:         22sp, Medium (500)
Title Medium:        16sp, Medium (500)
Body Large:          16sp, Regular (400)
Body Medium:         14sp, Regular (400)
Body Small:          12sp, Regular (400)
Label Medium:        12sp, Medium (500)
```

### 2.3 Spacing & Layout

**Spacing Scale (8dp grid):**
```
XS:  8dp   - Small gaps
S:   12dp  - Compact spacing
M:   16dp  - Standard spacing (most common)
L:   24dp  - Large spacing
XL:  32dp  - Section separation
```

**Touch Targets:** Minimum 48dp × 48dp

**FAB (Floating Action Button):**
- Size: 56dp diameter
- Position: 16dp from bottom-right edge
- **CRITICAL:** Add 80dp bottom padding to scrollable lists (LazyColumn)
  - Prevents FAB from obscuring last item
  - Add subtle scrim (gradient shadow) above FAB for better contrast
- Icon: 24dp, centered
- Elevation: 6dp

### 2.5 Dark Mode

**Enabled:** Yes, follows system setting

**Color Adaptations:**
```
Surface:             #121212 (Dark background)
Surface Variant:     #1E1E1E (Cards, elevated surfaces)
Primary Blue:        #42A5F5 (Lighter blue, better readability)
Primary Gold:        #FFB74D (Lighter gold, better readability)
On Surface:          #E0E0E0 (High contrast text)
On Surface Variant:  #B0B0B0 (Medium contrast text)
Dividers:            #2C2C2C (Subtle separation)
```

**Progress Bars (Dark Mode):**
- Completed: #42A5F5 (Light blue)
- Track: #42A5F5 20% opacity
- Ensure 4.5:1 contrast ratio minimum

**Heatmap (Dark Mode):**
- 80-100%: #42A5F5 (bright blue)
- 50-79%: #42A5F5 60% opacity
- 20-49%: #42A5F5 30% opacity
- 0-19%: #2C2C2C (dark grey)

**Testing:**
- Test all screens in both light and dark modes
- Use Material 3 dynamic color system
- Run accessibility scanner to verify contrast ratios

### 2.5 Performance & Quality Standards

This app is designed to provide a smooth, responsive experience that retains users and handles growth gracefully.

#### **Performance Targets**
- **Cold Start**: < 2 seconds to first screen
- **Warm Start**: < 500ms to restored state
- **Frame Rate**: Maintain 60fps during scrolling and animations
- **Network Requests**: < 500ms for typical API calls
- **Image Loading**: Progressive loading with placeholders
- **List Scrolling**: Smooth with 1000+ items (RecyclerView pagination)

#### **Memory Management**
- **Image Caching**: Glide with LRU cache (50MB max)
- **List Rendering**: ViewHolder pattern with DiffUtil for efficient updates
- **Leak Prevention**: Proper lifecycle management, WeakReferences for listeners
- **Background Processing**: WorkManager for scheduled tasks, not foreground services

#### **Offline Support**
- **Local Database**: Room with SQLite for offline-first experience
- **Sync Strategy**: Pull-to-refresh, background sync when online
- **Cached Data**: Display stale data with visual indicator
- **Conflict Resolution**: Last-write-wins with server timestamp

#### **Accessibility (WCAG 2.1 AA)**
- **Touch Targets**: Minimum 48x48dp for all interactive elements
- **Contrast Ratios**: 4.5:1 for text, 3:1 for UI components
- **Screen Readers**: TalkBack support with content descriptions
- **Text Scaling**: Support up to 200% text size
- **Color Independence**: Don't rely solely on color for information
- **Focus Indicators**: Clear focus states for keyboard navigation

#### **Error Handling**
- **Network Errors**: Graceful degradation, retry with exponential backoff
- **Server Errors**: User-friendly messages, log details for debugging
- **Validation Errors**: Inline feedback before submission
- **Crash Recovery**: Preserve state across app restarts

#### **Security Architecture**

Mobile apps face unique security challenges: device theft, network interception, malicious apps, and local data access. This section defines comprehensive security measures.

**Security Principles:**
- **Defense in Depth**: Multiple layers of protection
- **Assume Compromise**: Design for graceful degradation if one layer fails
- **Least Privilege**: Only request necessary permissions
- **Secure by Default**: HTTPS only, encryption at rest, no plaintext secrets

**Authentication Security:**
```kotlin
// ✅ CORRECT: Store tokens encrypted
class TokenManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    
    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "auth_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )
    
    fun saveTokens(accessToken: String, refreshToken: String) {
        encryptedPrefs.edit()
            .putString("access_token", accessToken)
            .putString("refresh_token", refreshToken)
            .apply()
    }
}

// ❌ WRONG: Plain SharedPreferences
prefs.edit().putString("access_token", token).apply()
```

**Network Security:**
```kotlin
// HTTPS only - reject cleartext traffic
// AndroidManifest.xml
<application android:usesCleartextTraffic="false">

// Network security config
// res/xml/network_security_config.xml
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    
    <!-- Certificate pinning (optional but recommended) -->
    <domain-config>
        <domain includeSubdomains="true">api.getpursue.app</domain>
        <pin-set expiration="2027-01-01">
            <pin digest="SHA-256">AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=</pin>
            <pin digest="SHA-256">BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=</pin>
        </pin-set>
    </domain-config>
</network-security-config>
```

**SSL/TLS Certificate Pinning:**
```kotlin
// OkHttp client with certificate pinning
val certificatePinner = CertificatePinner.Builder()
    .add("api.getpursue.app", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
    .add("api.getpursue.app", "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
    .build()

val client = OkHttpClient.Builder()
    .certificatePinner(certificatePinner)
    .build()
```

**Input Validation:**
```kotlin
// Validate all user input before sending to server
object InputValidator {
    fun validateEmail(email: String): Boolean {
        val emailPattern = "[a-zA-Z0-9._-]+@[a-z]+\\.+[a-z]+"
        return email.matches(emailPattern.toRegex()) && email.length <= 255
    }
    
    fun validateDisplayName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && trimmed.length <= 100
    }
    
    fun validatePassword(password: String): Boolean {
        return password.length in 8..128 &&
               password.contains(Regex("[a-z]")) &&
               password.contains(Regex("[A-Z]")) &&
               password.contains(Regex("[0-9]"))
    }
    
    fun sanitizeNote(note: String): String {
        return note.trim().take(500) // Max 500 chars
    }
}
```

**Secure Data Storage:**
```kotlin
// Use Room with SQLCipher for encrypted local database
dependencies {
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("net.zetetic:android-database-sqlcipher:4.5.4")
}

// Initialize encrypted database
val passphrase = getSecurePassphrase(context)

val database = Room.databaseBuilder(
    context,
    AppDatabase::class.java,
    "pursue_db"
).openHelperFactory(SupportFactory(passphrase))
    .build()
```

**Prevent Screenshot Leaks:**
```kotlin
// Prevent screenshots in sensitive screens
class LoginActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Prevent screenshots/screen recording
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }
}
```

**Logging Security:**
```kotlin
// ❌ NEVER log sensitive data
Log.d("Auth", "User logged in with token: $accessToken")
Log.d("Auth", "Password: $password")

// ✅ Log safely
Log.d("Auth", "User logged in successfully")
Log.d("Auth", "Login attempt for user_id: $userId")
```

**Code Obfuscation:**
```gradle
// Enable ProGuard/R8 in release builds
buildTypes {
    release {
        minifyEnabled true
        shrinkResources true
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

**Root/Jailbreak Detection:**
```kotlin
// Detect compromised devices
object SecurityChecks {
    fun isDeviceSecure(context: Context): Boolean {
        return !isRooted() && !isDeveloperModeEnabled(context)
    }
    
    private fun isRooted(): Boolean {
        val paths = arrayOf(
            "/system/app/Superuser.apk",
            "/sbin/su",
            "/system/bin/su",
            "/system/xbin/su"
        )
        
        return paths.any { File(it).exists() }
    }
    
    private fun isDeveloperModeEnabled(context: Context): Boolean {
        return Settings.Secure.getInt(
            context.contentResolver,
            Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
            0
        ) != 0
    }
}
```

**API Key Protection:**
```kotlin
// ❌ NEVER hardcode API keys
const val GOOGLE_CLIENT_ID = "123456789-abcdef.apps.googleusercontent.com"

// ✅ Store in gradle.properties (not in version control)
// gradle.properties
GOOGLE_CLIENT_ID=123456789-abcdef.apps.googleusercontent.com

// build.gradle.kts
android {
    defaultConfig {
        buildConfigField("String", "GOOGLE_CLIENT_ID", "\"${project.property("GOOGLE_CLIENT_ID")}\"")
    }
}

// Access in code
val clientId = BuildConfig.GOOGLE_CLIENT_ID
```

**WebView Security (if needed):**
```kotlin
webView.settings.apply {
    javaScriptEnabled = false      // Disable unless absolutely necessary
    allowFileAccess = false
    allowContentAccess = false
    allowFileAccessFromFileURLs = false
    allowUniversalAccessFromFileURLs = false
}
```

**Security Checklist:**
- [ ] Tokens stored in EncryptedSharedPreferences
- [ ] Network security config enforces HTTPS
- [ ] Certificate pinning implemented
- [ ] All user input validated client-side
- [ ] No sensitive data in logs
- [ ] ProGuard/R8 enabled for release builds
- [ ] FLAG_SECURE on sensitive screens
- [ ] Room database encrypted with SQLCipher
- [ ] API keys in gradle.properties (not hardcoded)
- [ ] Root detection implemented
- [ ] No hardcoded secrets in code
- [ ] Backup disabled for sensitive data (AndroidManifest.xml: allowBackup="false")

#### **Quality Metrics**
- **Crash Rate**: < 0.5% sessions
- **ANR Rate**: < 0.1% sessions  
- **Network Success Rate**: > 99% for valid requests
- **User Retention**: Day 1: 40%, Day 7: 25%, Day 30: 15%
- **Session Duration**: Average 3-5 minutes per session

### 2.6 Components

**Buttons:**
- **Primary (Filled)**: Blue background, white text
- **Secondary (Outlined)**: Blue border, blue text
- **Tertiary (Text)**: Blue text only
- Height: 40dp minimum, Corner radius: 20dp

**Cards:**
- White background, 1dp elevation
- Corner radius: 12dp, Padding: 16dp

**Progress Indicators:**
- Linear: 4dp height, blue fill
- Circular: 48dp diameter (prominent), 24dp (inline)

---

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
│   │   ├── Goals Tab                  │
│   │   ├── Members Tab                │
│   │   └── Activity Tab               │
│   └── [FAB: Create New Group]        │
│                                       │
├── Today (Quick daily goals view)     │
│   └── [FAB: Log Progress]            │
│                                       │
└── Profile                             │
    ├── Display Name & Avatar           │
    ├── My Progress Summary             │
    ├── Linked Devices                  │
    └── Account Settings                │
        ├── Linked Accounts             │
        ├── Change Password             │
        └── Privacy & Security          │
```

### 3.2 Bottom Navigation Bar

```
┌────────────────────────────────────────────┐
│  [Home]    [Today]    [Profile]            │
│   🏠        📅         👤                   │
└────────────────────────────────────────────┘
```

- **Home**: Groups list (badge for unread updates)
- **Today**: Today's daily goals (badge for incomplete goals)
- **Profile**: User settings

### 3.3 Top App Bar

- Left: Back arrow (when applicable)
- Center: Screen title or group name
- Right: Overflow menu (3 dots)

**No sync status indicator needed** - standard loading states instead

---

## 4. Screen Specifications

### 4.1 First-Time User Experience

#### 4.1.1 Welcome Screen (New Users Only)

**Display Logic:**
- Show only if no account exists locally
- No animations (instant display)
- Skip for returning users (go directly to Home)

**Layout:**
```
┌─────────────────────────────────────┐
│                                     │
│          [Pursue Logo]              │
│       Large blue/gold icon          │
│                                     │
│   "Achieve goals together"          │
│   Subtitle: Body Large (grey)       │
│                                     │
│                                     │
│   ┌─────────────────────────────┐  │
│   │ [G] Continue with Google    │  │ ← Primary button
│   └─────────────────────────────┘  │
│                                     │
│   ────────── or ──────────          │
│                                     │
│   [Sign in with Email] ─ Text btn   │
│                                     │
│   Don't have an account?            │
│   [Create Account] ──── Text btn    │
│                                     │
└─────────────────────────────────────┘
```

**Behavior:**
- "Continue with Google" → Opens Google Sign-In flow (Section 4.1.2)
- "Sign in with Email" → Navigate to Email Sign In screen (Section 4.1.4)
- "Create Account" → Navigate to Email Sign Up screen (Section 4.1.5)

#### 4.1.2 Google Sign-In Flow

**User Flow:**
1. User taps "Continue with Google" from Welcome screen
2. Google Sign-In SDK opens (system account picker)
3. User selects Google account
4. Google returns to app with ID token
5. App sends token to backend
6. Backend verifies and creates/signs in user
7. Navigate to Home screen
8. Show success toast: "Welcome back!" (existing) or "Account created!" (new)

**Google Account Picker (System UI):**
```
┌─────────────────────────────────────┐
│ Choose an account                   │
├─────────────────────────────────────┤
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 👤 Shannon Thompson             ││
│ │ shannon@example.com             ││
│ └─────────────────────────────────┘│
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 👤 Work Account                 ││
│ │ work@company.com                ││
│ └─────────────────────────────────┘│
│                                     │
│ ┌─────────────────────────────────┐│
│ │ + Add another account           ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
```

**Loading State (After Selection):**
```
┌─────────────────────────────────────┐
│                                     │
│          ⟳                          │
│    Signing in with Google...        │
│                                     │
└─────────────────────────────────────┘
```

**Implementation Notes:**
- Use Google Sign-In SDK for Android
- Request scopes: email, profile
- Request ID token for backend verification
- Handle cancellation gracefully (return to Welcome)

#### 4.1.3 Sign In with Email

**Layout:**
```
┌─────────────────────────────────────┐
│ ← Back                              │
│                                     │
│ Welcome back!                       │
│ ─────────────────────                │
│                                     │
│ Email                               │
│ ┌─────────────────────────────────┐│
│ │ [your@email.com]                ││
│ └─────────────────────────────────┘│
│                                     │
│ Password                            │
│ ┌─────────────────────────────────┐│
│ │ [••••••••]              👁      ││
│ └─────────────────────────────────┘│
│                                     │
│ [Forgot password?] ─ Text button    │
│                                     │
│ [Sign In] ───────── Primary button  │
│                                     │
│   ────────── or ──────────          │
│                                     │
│   ┌─────────────────────────────┐  │
│   │ [G] Continue with Google    │  │ ← Outlined btn
│   └─────────────────────────────┘  │
│                                     │
│ Don't have an account?              │
│ [Create Account] ── Text button     │
│                                     │
└─────────────────────────────────────┘
```

**On Sign In:**
- Hash password client-side
- POST to /api/auth/login
- Store JWT token
- Register FCM token with server
- Navigate to Home screen

**On "Continue with Google":**
- Start Google Sign-In flow (Section 4.1.2)

**Validation:**
- Email: Valid format
- Password: Required
- Show inline errors below fields

#### 4.1.4 Sign Up with Email

**Layout:**
```
┌─────────────────────────────────────┐
│ ← Back                              │
│                                     │
│ Create Your Account                 │
│ ─────────────────────                │
│                                     │
│ Display Name *                      │
│ ┌─────────────────────────────────┐│
│ │ [Enter your name]               ││
│ └─────────────────────────────────┘│
│                                     │
│ Email *                             │
│ ┌─────────────────────────────────┐│
│ │ [your@email.com]                ││
│ └─────────────────────────────────┘│
│                                     │
│ Password *                          │
│ ┌─────────────────────────────────┐│
│ │ [••••••••]              👁      ││
│ └─────────────────────────────────┘│
│ Strength: ████░░ Medium             │
│                                     │
│ Confirm Password *                  │
│ ┌─────────────────────────────────┐│
│ │ [••••••••]              👁      ││
│ └─────────────────────────────────┘│
│                                     │
│ [Create Account] ── Primary button  │
│                                     │
│   ────────── or ──────────          │
│                                     │
│   ┌─────────────────────────────┐  │
│   │ [G] Continue with Google    │  │
│   └─────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

**Validation:**
- Display name: 1-30 characters
- Email: Valid email format
- Password: Min 8 characters, strength indicator
- Confirm password: Must match
- All fields required

**On Create Account:**
- Client hashes password with salt
- POST to /api/auth/register
- Server creates account and returns JWT
- Store JWT in Android Keystore
- Register FCM token
- Navigate to Home screen
- Show success toast: "Account created!"

**On "Continue with Google":**
- Start Google Sign-In flow (Section 4.1.2)

---

### 4.2 Home Screen (Groups List)

**Layout:**
```
┌─────────────────────────────────────┐
│ ☰  Groups                       ⋮   │ ← Top bar
├─────────────────────────────────────┤
│                                     │
│ My Groups                           │
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 🏃 Morning Runners      →       ││ ← Emoji icon with blue background
│ │ 8 members · 5 active goals      ││
│ │ ──────────────────── 80%        ││ ← Progress bar
│ │ 2 hours ago                     ││
│ └─────────────────────────────────┘│
│                                     │
│ ┌─────────────────────────────────┐│
│ │ [📷] Book Club          →       ││ ← Uploaded image icon (circular photo)
│ │ 12 members · 3 active goals     ││
│ │ ──────────────────── 60%        ││
│ │ 1 day ago                       ││
│ └─────────────────────────────────┘│
│                                     │
│ ┌─────────────────────────────────┐│
│ │ (G) Gym Accountability  →       ││ ← Letter "G" with default blue background
│ │ 5 members · 4 active goals      ││
│ │ ──────────────────── 45%        ││
│ │ 3 hours ago                     ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
                                   [+] ← FAB
```

**Group Card:**
- **Icon/Avatar (circular, 48dp):**
  - **Priority 1:** Display `icon_url` if present (uploaded image)
  - **Priority 2:** Display `icon_emoji` with `icon_color` background if present
  - **Priority 3:** Display first letter of group name with default blue background
- Name (Title Medium, bold)
- Member count, goal count (Body Medium, grey)
- Progress bar: Today's completion % across all members
- Last activity timestamp (relative: "2 hours ago")
- Tap to navigate to Group Detail

**Icon Display Logic:**

```kotlin
// Icon display priority: uploaded image > emoji + color > first letter
when {
    group.has_icon -> {
        // Display uploaded image from backend endpoint (circular)
        Glide.with(context)
            .load("${apiBaseUrl}/groups/${group.id}/icon")
            .circleCrop()
            .placeholder(R.drawable.group_icon_placeholder)
            .error(R.drawable.group_icon_error)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .signature(ObjectKey(group.updated_at)) // Cache invalidation
            .into(imageView)
    }
    group.icon_emoji != null -> {
        // Display emoji with colored background (circular)
        textView.text = group.icon_emoji  // "🏃"
        container.backgroundColor = Color.parseColor(group.icon_color ?: "#1976D2")
        container.shape = Circle
    }
    else -> {
        // Display first letter with default background (circular)
        val initial = group.name.first().uppercase()
        textView.text = initial  // "M" for "Morning Runners"
        container.backgroundColor = Color.parseColor("#1976D2")  // Default blue
        container.shape = Circle
    }
}
```

**Backend Endpoints:**
- Upload icon: `POST /api/groups/{group_id}/icon` (multipart/form-data)
- Fetch icon: `GET /api/groups/{group_id}/icon` (returns image/webp binary)
- Delete icon: `DELETE /api/groups/{group_id}/icon`

**Icon Sizes:**
- List view (Home screen): 48dp diameter circle
- Group detail header: 80dp diameter circle
- Member list (small avatar): 32dp diameter circle

**Pull to Refresh:**
- Standard Material Design pull-to-refresh
- Fetches latest data from server

#### 4.2.1 UI States

**State 1: Loading (Skeleton)**

Display on initial load and pull-to-refresh:

```
┌─────────────────────────────────────┐
│ ☰  Home                         👤  │
├─────────────────────────────────────┤
│ ┌─┐ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │  ← Shimmer skeleton
│ └─┘ ▓▓▓▓▓▓▓▓▓▓                   │
├─────────────────────────────────────┤
│ ┌─┐ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
│ └─┘ ▓▓▓▓▓▓▓▓▓▓                   │
├─────────────────────────────────────┤
│ ┌─┐ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓ │
│ └─┘ ▓▓▓▓▓▓▓▓▓▓                   │
└─────────────────────────────────────┘
```

**Behavior:**
- Show shimmer animation (Facebook Shimmer library)
- Display 3-4 skeleton group cards
- Hide RecyclerView, Empty State, and Error State
- Auto-dismiss when data loads or error occurs

**State 2: Success - With Groups**

Display when API returns groups successfully:

```
┌─────────────────────────────────────┐
│ ☰  Home                         👤  │
├─────────────────────────────────────┤
│ 🏃 Morning Runners                  │
│ 3 members · 2 goals                 │
│ ████████░░░░░░░░ 50%                │
│ Last active: 2 hours ago            │
├─────────────────────────────────────┤
│ 📚 Book Club                        │
│ 5 members · 1 goal                  │
│ ██████████████░░ 80%                │
│ Last active: 1 hour ago             │
└─────────────────────────────────────┘
                                   [+] ← FAB
```

**State 3: Success - Empty (No Groups)**

Display when API succeeds but user has 0 groups:

```
┌─────────────────────────────────────┐
│ ☰  Home                         👤  │
├─────────────────────────────────────┤
│                                     │
│          [Illustration]             │
│        People with goals            │
│                                     │
│   No groups yet!                    │
│                                     │
│   Join a group to get started or    │
│   create your own accountability    │
│   group with friends.               │
│                                     │
│   ┌─────────────────────────────┐  │
│   │      Join Group             │  │
│   └─────────────────────────────┘  │
│   ┌─────────────────────────────┐  │
│   │      Create Group           │  │
│   └─────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

**Empty State Behavior:**
- Show ONLY if API succeeds AND returns 0 groups
- "Join Group" → Navigate to join screen with invite code input
- "Create Group" → Navigate to group creation flow
- Illustration: Friendly characters collaborating on goals
- Hide empty state once user joins/creates first group

**State 4: Error - Network/Server Failure**

Display when API call fails (network error, timeout, 500, etc.):

```
┌─────────────────────────────────────┐
│ ☰  Home                         👤  │
├─────────────────────────────────────┤
│                                     │
│            ⚠️                        │
│                                     │
│    Failed to load groups            │
│                                     │
│    Check your connection            │
│    and try again                    │
│                                     │
│   ┌─────────────────────────────┐  │
│   │         Retry               │  │
│   └─────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

**Error State Variations:**

Network Error (No Connection):
- Icon: 📶 Wi-Fi off icon
- Title: "No internet connection"
- Message: "Check your connection and try again"

Server Error (500, 503):
- Icon: ⚠️ Warning icon
- Title: "Server error"
- Message: "Our servers are having issues. Please try again later"

Timeout:
- Icon: ⏱️ Clock icon
- Title: "Request timed out"
- Message: "The request took too long. Please try again"

Unauthorized (401):
- Icon: 🔒 Lock icon
- Title: "Session expired"
- Message: "Please log in again"
- Action: Navigate to login screen

**Error State Behavior:**
- Show when API call fails
- Display appropriate icon and message based on error type
- "Retry" button calls API again (shows loading state)
- Also show toast: "Failed to load groups" (immediate feedback)
- Do NOT show RecyclerView, Empty State, or Shimmer

**State 5: Offline - With Cached Data**

Display when network unavailable but cached groups exist:

```
┌─────────────────────────────────────┐
│ ☰  Home                         👤  │
├─────────────────────────────────────┤
│ ⚠️ Offline - showing cached data    │  ← Persistent banner/snackbar
├─────────────────────────────────────┤
│ 🏃 Morning Runners                  │  ← Slightly grayed out
│ 3 members · 2 goals                 │
│ ████████░░░░░░░░ 50%                │
│ Last updated: 2 hours ago           │
├─────────────────────────────────────┤
│ 📚 Book Club                        │
│ 5 members · 1 goal                  │
│ ██████████████░░ 80%                │
│ Last updated: 2 hours ago           │
└─────────────────────────────────────┘
   [Retry]                          [+] ← FAB + Retry action in Snackbar
```

**Offline State Behavior:**
- Show when network fails BUT have cached groups from previous load
- Display cached groups (slightly dimmed or grayed)
- Show persistent Snackbar: "Offline - showing cached data" with "Retry" action
- Update "Last active" to "Last updated" with timestamp
- If no cache available, show Error State instead

**State Priority (Decision Tree):**

```
API Request Made
     ↓
Is Loading?
  ├─ YES → Show Loading State (shimmer)
  └─ NO → Request Complete
            ↓
       Success?
         ├─ YES → Has groups?
         │          ├─ YES → Show Success State (list)
         │          └─ NO → Show Empty State
         └─ NO → Network Error?
                   ├─ YES → Has cache?
                   │          ├─ YES → Show Offline State (cache + banner)
                   │          └─ NO → Show Error State (retry button)
                   └─ NO (Server Error) → Show Error State (retry button)
```

---

### 4.3 Group Detail Screen

**Header Layout:**
```
┌─────────────────────────────────────┐
│ ←  [🏃]  Morning Runners         ⋮  │ ← Icon (80dp) + Name + Menu
├─────────────────────────────────────┤
│ 8 members · 5 active goals          │ ← Subtitle
│ Created by Shannon Thompson         │
├─────────────────────────────────────┤
│ [Goals]  [Members]  [Activity]      │ ← Tabs
├─────────────────────────────────────┤
│ [Tab Content]                       │
└─────────────────────────────────────┘
```

**Header Elements:**
- Back button (←)
- **Group icon (80dp circular):**
  - Uploaded image if `icon_url` exists
  - Emoji with colored background if `icon_emoji` exists
  - First letter with blue background otherwise
- Group name (Headline Small, bold)
- Overflow menu (⋮):
  - Edit Group (admin/creator only)
  - Manage Members (admin/creator only)
  - Invite Members
  - Leave Group
  - Delete Group (creator only, confirmation required)

**Tabbed Layout:**

#### 4.3.1 Goals Tab

**Layout:**
```
┌─────────────────────────────────────┐
│ Daily Goals                         │
│ ┌─────────────────────────────────┐│
│ │ ✓ 30 min run                    ││
│ │ Shannon ✓ Alex ✓ Jamie ○        ││
│ └─────────────────────────────────┘│
│                                     │
│ Weekly Goals                        │
│ ┌─────────────────────────────────┐│
│ │ ○ Read 2 books                  ││
│ │ ████████░░░░░░░░ 50% (1/2)      ││
│ │ Shannon ✓ Alex ○ Jamie ✓        ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
                                   [+] ← FAB (Log Progress)
```

**Goal Card:**
- Status icon: ✓ (completed) or ○ (incomplete)
- Goal title
- Member status dots (✓ blue checkmark, ○ grey circle)
- Progress bar for numeric goals

**Pull to Refresh:** Fetches latest progress from server

#### 4.3.2 Members Tab

**Layout:**
```
┌─────────────────────────────────────┐
│ Admins                              │
│ ┌─────────────────────────────────┐│
│ │ 👤 Shannon (You)          🛡     ││
│ │ Last active: Now                ││
│ └─────────────────────────────────┘│
│                                     │
│ Members (6)                         │
│ ┌─────────────────────────────────┐│
│ │ 👤 Alex Thompson                ││
│ │ Last active: 2 hours ago        ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
                                   [+] ← FAB (Invite)
```

**Member Card:**
- Avatar, display name
- Admin badge (🛡) if admin
- Last active timestamp
- Tap to view Member Profile

#### 4.3.3 Activity Tab

**Layout:**
```
┌─────────────────────────────────────┐
│ Today                               │
│ ┌─────────────────────────────────┐│
│ │ ✓ Alex completed "30 min run"   ││
│ │ 2 hours ago                     ││
│ └─────────────────────────────────┘│
│                                     │
│ Yesterday                           │
│ ┌─────────────────────────────────┐│
│ │ ✓ You completed "Meditate"      ││
│ │ Yesterday at 7:30 AM            ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
```

**Activity Types:**
- Progress logged, member joined/left, goal added, group renamed

**Pull to Refresh:** Fetches latest activity from server

---

### 4.4 Today Screen

**Purpose:** Quick access to today's daily goals

**Layout:**
```
┌─────────────────────────────────────┐
│ ☰  Today                        ⋮   │
├─────────────────────────────────────┤
│                                     │
│ Friday, January 16                  │
│ ──────────────── 40% complete       │
│                                     │
│ Morning Runners (2/5)               │
│ ┌─────────────────────────────────┐│
│ │ ✓ 30 min run                    ││
│ │ ○ Meditate 10 min               ││
│ └─────────────────────────────────┘│
│                                     │
│ Book Club (0/1)                     │
│ ┌─────────────────────────────────┐│
│ │ ○ Read 30 pages                 ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
                                   [+] ← FAB (Log Progress)
```

**Pull to Refresh:** Fetches today's progress from server

#### 4.4.1 UI States

**State 1: Loading (Skeleton)**

```
┌─────────────────────────────────────┐
│ ☰  Today                        ⋮   │
├─────────────────────────────────────┤
│                                     │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓                      │  ← Date shimmer
│ ──────────────── ▓▓▓                │  ← Progress shimmer
│                                     │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓                    │  ← Group name shimmer
│ ┌─────────────────────────────────┐│
│ │ ▓ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓                ││  ← Goal shimmer
│ │ ▓ ▓▓▓▓▓▓▓▓▓▓▓                   ││
│ └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

**Behavior:**
- Show on initial load and pull-to-refresh
- Display shimmer for date, progress bar, and 2-3 goal skeletons
- Auto-dismiss when data loads

**State 2: Success - With Goals**

```
┌─────────────────────────────────────┐
│ ☰  Today                        ⋮   │
├─────────────────────────────────────┤
│                                     │
│ Friday, January 16                  │
│ ──────────────── 40% complete       │
│                                     │
│ Morning Runners (2/5)               │
│ ┌─────────────────────────────────┐│
│ │ ✓ 30 min run                    ││
│ │ ○ Meditate 10 min               ││
│ └─────────────────────────────────┘│
│                                     │
│ Book Club (0/1)                     │
│ ┌─────────────────────────────────┐│
│ │ ○ Read 30 pages                 ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
                                   [+] ← FAB (Log Progress)
```

**State 3: Success - Empty (No Daily Goals)**

```
┌─────────────────────────────────────┐
│ ☰  Today                        ⋮   │
├─────────────────────────────────────┤
│                                     │
│          [Illustration]             │
│       Calendar with checkmark       │
│                                     │
│   No daily goals yet                │
│                                     │
│   Add daily goals to your groups    │
│   to track them here.               │
│                                     │
│   ┌─────────────────────────────┐  │
│   │     Browse Groups           │  │
│   └─────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

**Empty State Behavior:**
- Show ONLY if API succeeds AND user has 0 daily goals across all groups
- Note: User might have groups with only weekly/monthly goals
- "Browse Groups" → Navigate to Home screen

**State 4: Error - Failed to Load**

```
┌─────────────────────────────────────┐
│ ☰  Today                        ⋮   │
├─────────────────────────────────────┤
│                                     │
│            ⚠️                        │
│                                     │
│    Failed to load today's goals     │
│                                     │
│    Check your connection            │
│    and try again                    │
│                                     │
│   ┌─────────────────────────────┐  │
│   │         Retry               │  │
│   └─────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

**Error State Behavior:**
- Same error variations as Home screen (network, server, timeout, unauthorized)
- "Retry" button reloads today's goals
- Show toast: "Failed to load goals"

**State 5: Offline - With Cached Goals**

```
┌─────────────────────────────────────┐
│ ☰  Today                        ⋮   │
├─────────────────────────────────────┤
│ ⚠️ Offline - showing cached data    │
├─────────────────────────────────────┤
│ Friday, January 16                  │
│ ──────────────── 40% complete       │
│                                     │
│ Morning Runners (2/5)               │
│ ┌─────────────────────────────────┐│
│ │ ✓ 30 min run                    ││
│ │ ○ Meditate 10 min               ││
│ └─────────────────────────────────┘│
└─────────────────────────────────────┘
   [Retry]                          [+]
```

**Offline State Behavior:**
- Show cached goals if available
- Display Snackbar with "Retry" action
- Progress can be logged offline (queued for sync)

---

### 4.5 Profile Screen

**Layout:**
```
┌─────────────────────────────────────┐
│ ☰  Profile                      ⋮   │
├─────────────────────────────────────┤
│        ┌─────────┐                  │
│        │   👤    │                  │
│        │ Shannon │                  │
│        └─────────┘                  │
│                                     │
│     Shannon Thompson                │
│     shannon@example.com             │
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 📊 My Progress                  ││
│ │ Total goals: 15                 ││
│ │ Completed this week: 12         ││
│ │ Current streak: 7 days 🔥       ││
│ │                     [View All →]││
│ └─────────────────────────────────┘│
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 📱 Linked Devices               ││
│ │ 2 devices signed in             ││
│ │                     [Manage →]  ││
│ └─────────────────────────────────┘│
│                                     │
│ ┌─────────────────────────────────┐│
│ │ ⚙️ Account Settings             ││
│ │                     [Open →]    ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
```

#### 4.5.0 Profile Screen UI States

**State 1: Loading (Skeleton)**

```
┌─────────────────────────────────────┐
│ ☰  Profile                      ⋮   │
├─────────────────────────────────────┤
│        ┌─────────┐                  │
│        │   👤    │                  │
│        │▓▓▓▓▓▓▓▓▓│                  │
│        └─────────┘                  │
│                                     │
│     ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓              │
│     ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓             │
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 📊 ▓▓▓▓▓▓▓▓▓                    ││
│ │ ▓▓▓▓▓▓▓▓▓▓▓▓▓                   ││
│ │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓               ││
│ └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

**Behavior:**
- Show on initial load
- Display shimmer for name, email, and stats
- User info (name, email) loads from local cache if available

**State 2: Success - Data Loaded**

Normal profile display with all data populated.

**State 3: Error - Failed to Load Stats**

```
┌─────────────────────────────────────┐
│ ☰  Profile                      ⋮   │
├─────────────────────────────────────┤
│        ┌─────────┐                  │
│        │   👤    │                  │
│        │ Shannon │                  │
│        └─────────┘                  │
│                                     │
│     Shannon Thompson                │
│     shannon@example.com             │
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 📊 My Progress                  ││
│ │ ⚠️ Failed to load stats         ││
│ │                     [Retry]     ││
│ └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

**Error State Behavior:**
- User info always displays (from local cache or auth token)
- Only stats card shows error
- Inline "Retry" button in stats card
- Other sections (Linked Devices, Settings) remain accessible

**State 4: Offline - Cached Stats**

```
┌─────────────────────────────────────┐
│ ☰  Profile                      ⋮   │
├─────────────────────────────────────┤
│        ┌─────────┐                  │
│        │   👤    │                  │
│        │ Shannon │                  │
│        └─────────┘                  │
│                                     │
│     Shannon Thompson                │
│     shannon@example.com             │
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 📊 My Progress (Offline)        ││
│ │ Total goals: 15                 ││
│ │ Completed this week: 12         ││
│ │ Last updated: 2 hours ago       ││
│ │                     [View All →]││
│ └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

**Offline State Behavior:**
- Show cached stats with "(Offline)" indicator
- Display "Last updated" timestamp instead of current streak
- Stats card slightly dimmed

---

### 4.5.1 My Progress (Detail Screen)

**Accessed via:** Profile → "My Progress" → [View All →]

**Layout:**
```
┌─────────────────────────────────────┐
│ ← My Progress                       │
├─────────────────────────────────────┤
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 🔥 Current Streak                ││
│ │                                  ││
│ │          7 Days                  ││
│ │      ━━━━━━━━━━━━                ││
│ │   Goal: 30 days (23%)            ││
│ │                                  ││
│ │ Longest streak: 14 days          ││
│ └─────────────────────────────────┘│
│                                     │
│ This Week's Activity                │
│ ┌─────────────────────────────────┐│
│ │ M  T  W  T  F  S  S              ││
│ │ ✓  ✓  ✓  ○  ✓  ○  ✓             ││
│ │                                  ││
│ │ 12 of 15 goals completed         ││
│ └─────────────────────────────────┘│
│                                     │
│ 30-Day Heatmap                      │
│ ┌─────────────────────────────────┐│
│ │   Jan  │  Feb  │                ││
│ │ M █ █ █ ░ █ █ █                  ││
│ │ T █ ▓ █ █ █ ▒ █                  ││
│ │ W █ █ ▓ █ ░ █ █                  ││
│ │ T █ █ █ █ █ █ █                  ││
│ │ F ▓ █ █ ░ █ █ █                  ││
│ │ S █ ░ █ █ █ ▓ █                  ││
│ │ S █ █ █ █ █ █ ░                  ││
│ │                                  ││
│ │ ██ 80-100%  ▓ 50-79%             ││
│ │ ▒ 20-49%    ░ 0-19%              ││
│ └─────────────────────────────────┘│
│                                     │
│ Goal Breakdown                      │
│ ┌─────────────────────────────────┐│
│ │ 30 min run                       ││
│ │ ████████████████░░ 80% (24/30)   ││
│ └─────────────────────────────────┘│
│ ┌─────────────────────────────────┐│
│ │ Read 50 pages                    ││
│ │ ████████████░░░░░░ 60% (18/30)   ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
```

#### 4.5.1.1 My Progress UI States

**State 1: Loading (Skeleton)**

```
┌─────────────────────────────────────┐
│ ← My Progress                       │
├─────────────────────────────────────┤
│ ┌─────────────────────────────────┐│
│ │ 🔥 ▓▓▓▓▓▓▓▓▓                    ││
│ │    ▓▓▓▓▓▓▓▓                      ││
│ │    ▓▓▓▓▓▓▓▓▓▓▓▓▓                ││
│ └─────────────────────────────────┘│
│                                     │
│ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓                      │
│ ┌─────────────────────────────────┐│
│ │ ▓ ▓ ▓ ▓ ▓ ▓ ▓                   ││
│ │ ▓ ▓ ▓ ▓ ▓ ▓ ▓                   ││
│ └─────────────────────────────────┘│
│                                     │
│ ▓▓▓▓▓▓▓▓▓▓▓▓                        │
│ ┌─────────────────────────────────┐│
│ │ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓   ││
│ └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

**Behavior:**
- Show shimmer for streak card, weekly activity, heatmap, and goal breakdown
- Display on initial load
- Pull-to-refresh supported

**State 2: Success - Data Loaded**

Normal display with all progress data.

**State 3: Success - Empty (No Progress Data)**

```
┌─────────────────────────────────────┐
│ ← My Progress                       │
├─────────────────────────────────────┤
│                                     │
│          [Illustration]             │
│       Chart with upward arrow       │
│                                     │
│   No progress data yet              │
│                                     │
│   Complete goals in your groups     │
│   to see your progress here.        │
│                                     │
│   ┌─────────────────────────────┐  │
│   │     View Today's Goals      │  │
│   └─────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

**Empty State Behavior:**
- Show if user has goals but no logged progress
- "View Today's Goals" → Navigate to Today screen

**State 4: Error - Failed to Load**

```
┌─────────────────────────────────────┐
│ ← My Progress                       │
├─────────────────────────────────────┤
│                                     │
│            ⚠️                        │
│                                     │
│    Failed to load progress          │
│                                     │
│    Check your connection            │
│    and try again                    │
│                                     │
│   ┌─────────────────────────────┐  │
│   │         Retry               │  │
│   └─────────────────────────────┘  │
│                                     │
└─────────────────────────────────────┘
```

**Error State Behavior:**
- Same error type variations (network, server, timeout)
- "Retry" button reloads progress data
- Show toast: "Failed to load progress"

**State 5: Offline - Cached Data**

```
┌─────────────────────────────────────┐
│ ← My Progress                       │
├─────────────────────────────────────┤
│ ⚠️ Offline - showing cached data    │
├─────────────────────────────────────┤
│ ┌─────────────────────────────────┐│
│ │ 🔥 Current Streak                ││
│ │          7 Days                  ││
│ │   Last updated: 3 hours ago     ││
│ └─────────────────────────────────┘│
│                                     │
│ This Week's Activity                │
│ (Data may be outdated)              │
│ ┌─────────────────────────────────┐│
│ │ M  T  W  T  F  S  S              ││
│ │ ✓  ✓  ✓  ○  ✓  ○  ○             ││
│ └─────────────────────────────────┘│
└─────────────────────────────────────┘
   [Retry]
```

**Offline State Behavior:**
- Show cached progress data
- Display warning: "Data may be outdated"
- Snackbar with "Retry" action
- Streak count shows "Last updated" instead of live data
│ This Week's Activity                │
│ ┌─────────────────────────────────┐│
│ │ 📅 Completion Heatmap            ││
│ │                                  ││
│ │  M  T  W  T  F  S  S             ││
│ │  ✓  ✓  ✓  ○  ✓  ✓  ✓            ││
│ │                                  ││
│ │  12 of 15 goals completed        ││
│ └─────────────────────────────────┘│
│                                     │
│ Last 30 Days                        │
│ ┌─────────────────────────────────┐│
│ │     Jan 2026                     ││
│ │ S M T W T F S                    ││
│ │       1 2 3 4                    ││
│ │ █ █ █ ░ █ █ █                    ││
│ │ 5 6 7 8 9 10 11                  ││
│ │ █ █ █ █ ░ █ █                    ││
│ │ 12 13 14 15 16 17 18             ││
│ │ █ █ █ █ █ ░ ░                    ││
│ │                                  ││
│ │ ██ 80-100%  ▓ 50-79%             ││
│ │ ▒ 20-49%    ░ 0-19%              ││
│ └─────────────────────────────────┘│
│                                     │
│ Goal Breakdown                      │
│ ┌─────────────────────────────────┐│
│ │ 30 min run                       ││
│ │ ████████████████░░ 80% (24/30)   ││
│ └─────────────────────────────────┘│
│ ┌─────────────────────────────────┐│
│ │ Read 50 pages                    ││
│ │ ████████████░░░░░░ 60% (18/30)   ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
```

**Heatmap Design:**
- **GitHub-style contribution grid**
- Each cell represents one day
- Color intensity = completion percentage for that day
- Tap cell → Show detail: "Jan 15: 4 of 5 goals (80%)"
- Current day has subtle outline
- Sunday starts week (configurable in settings)

**Streak Calculation:**
- Count consecutive days with >50% goal completion
- Reset on day with <50% completion
- "Current Streak" badge shown prominently
- Fire emoji 🔥 intensity increases with streak length

**Color Palette (Colorblind-Friendly):**
- 80-100%: Blue #1976D2 (full opacity)
- 50-79%: Blue #1976D2 (60% opacity)
- 20-49%: Blue #1976D2 (30% opacity)
- 0-19%: Grey #E0E0E0 (empty)
- Avoid red/green combinations

**Goal Breakdown:**
- Shows all active goals
- Progress bar with percentage
- Tap to view detailed history for that goal
- Sorted by completion percentage (highest first)

---

### 4.5.3 Profile Picture (Avatar)

**Current Profile Display:**
```
┌─────────────────────────────────────┐
│        ┌─────────┐                  │
│        │  [IMG]  │  ← Tap to change │
│        │ Shannon │                  │
│        └─────────┘                  │
│                                     │
│     Shannon Thompson                │
│     [Edit Profile Picture]          │
└─────────────────────────────────────┘
```

**Avatar Display Logic:**
```kotlin
// Load avatar from backend
if (user.has_avatar) {
    Glide.with(context)
        .load("${apiBaseUrl}/users/${user.id}/avatar")
        .circleCrop()
        .placeholder(R.drawable.default_avatar)
        .error(R.drawable.default_avatar)
        .into(avatarImageView)
} else {
    // Show default avatar (first letter of name)
    val initial = user.display_name.first().uppercase()
    avatarImageView.setImageDrawable(
        LetterAvatarDrawable(initial, Color.parseColor("#1976D2"))
    )
}
```

**Upload Avatar Flow:**

**Step 1: Select Source**
```
┌─────────────────────────────────────┐
│ Change Profile Picture              │
├─────────────────────────────────────┤
│ ┌─────────────────────────────────┐│
│ │ 📷 Take Photo                   ││
│ └─────────────────────────────────┘│
│ ┌─────────────────────────────────┐│
│ │ 🖼️  Choose from Gallery         ││
│ └─────────────────────────────────┘│
│ ┌─────────────────────────────────┐│
│ │ 🗑️  Remove Photo                ││ ← Only if has_avatar = true
│ └─────────────────────────────────┘│
│                                     │
│ [Cancel]                            │
└─────────────────────────────────────┘
```

**Step 2: Crop Image (Android Image Cropper Library)**
```
┌─────────────────────────────────────┐
│ ← Crop Image                 [Done] │
├─────────────────────────────────────┤
│                                     │
│      ┌───────────────────┐          │
│      │                   │          │
│      │   [IMAGE PREVIEW] │          │
│      │   with crop box   │          │
│      │                   │          │
│      └───────────────────┘          │
│                                     │
│   [Rotate]  [Flip]  [Reset]         │
│                                     │
└─────────────────────────────────────┘
```

**Step 3: Upload**
```
┌─────────────────────────────────────┐
│ Uploading...                        │
│                                     │
│     ⏳  Processing image             │
│                                     │
│     [Progress bar 60%]              │
│                                     │
└─────────────────────────────────────┘
```

**Implementation (Android):**

```kotlin
// Select image from gallery
val pickImageLauncher = registerForActivityResult(
    ActivityResultContracts.GetContent()
) { uri: Uri? ->
    uri?.let { startImageCrop(it) }
}

fun selectFromGallery() {
    pickImageLauncher.launch("image/*")
}

// Take photo with camera
val takePictureLauncher = registerForActivityResult(
    ActivityResultContracts.TakePicture()
) { success ->
    if (success) {
        photoUri?.let { startImageCrop(it) }
    }
}

fun takePhoto() {
    val photoFile = createTempImageFile()
    photoUri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        photoFile
    )
    takePictureLauncher.launch(photoUri)
}

// Crop image (using Android Image Cropper)
val cropImageLauncher = registerForActivityResult(
    CropImageContract()
) { result ->
    if (result.isSuccessful) {
        result.uriContent?.let { uploadAvatar(it) }
    }
}

fun startImageCrop(imageUri: Uri) {
    val cropOptions = CropImageContractOptions(imageUri, CropImageOptions(
        aspectRatioX = 1,
        aspectRatioY = 1,
        fixAspectRatio = true,
        outputCompressFormat = Bitmap.CompressFormat.JPEG,
        outputCompressQuality = 90,
        maxOutputSizeX = 512,
        maxOutputSizeY = 512
    ))
    cropImageLauncher.launch(cropOptions)
}

// Upload to backend
suspend fun uploadAvatar(imageUri: Uri) {
    try {
        showLoading(true)
        
        // Compress image before upload to reduce bandwidth
        // Backend will resize again to 256x256, but this saves upload time
        val bitmap = BitmapFactory.decodeStream(
            context.contentResolver.openInputStream(imageUri)
        )
        
        // Resize to max 512x512 if larger (backend resizes to 256x256 anyway)
        val resizedBitmap = if (bitmap.width > 512 || bitmap.height > 512) {
            Bitmap.createScaledBitmap(bitmap, 512, 512, true)
        } else {
            bitmap
        }
        
        // Compress to JPEG (quality 85) - reduces file size for upload
        val outputStream = ByteArrayOutputStream()
        resizedBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val imageBytes = outputStream.toByteArray()
        
        // Clean up
        if (resizedBitmap != bitmap) {
            resizedBitmap.recycle()
        }
        bitmap.recycle()
        
        val requestBody = imageBytes.toRequestBody("image/jpeg".toMediaType())
        
        val multipartBody = MultipartBody.Part.createFormData(
            "avatar",
            "avatar.jpg",
            requestBody
        )
        
        val response = apiClient.uploadAvatar(multipartBody)
        
        if (response.isSuccessful) {
            // Update local user state
            userViewModel.updateHasAvatar(true)
            
            // Reload avatar
            loadAvatar()
            
            Toast.makeText(context, "Profile picture updated!", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Upload failed", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
    } finally {
        showLoading(false)
    }
}

/**
 * Why resize on client?
 * - Original photo: 4000x3000, ~8 MB
 * - After crop: 512x512, ~200 KB JPEG
 * - After client resize + compress: 512x512, ~50 KB JPEG ← Upload this
 * - Backend resizes to: 256x256, ~30 KB WebP
 * 
 * Result: 4x smaller upload (200 KB → 50 KB), faster upload on slow networks
 */

// Remove avatar
suspend fun removeAvatar() {
    try {
        val response = apiClient.deleteAvatar()
        
        if (response.isSuccessful) {
            userViewModel.updateHasAvatar(false)
            loadAvatar() // Will show letter avatar
            Toast.makeText(context, "Profile picture removed", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
```

**API Interface (Retrofit):**
```kotlin
interface ApiService {
    @Multipart
    @POST("users/me/avatar")
    suspend fun uploadAvatar(
        @Part avatar: MultipartBody.Part
    ): Response<UploadAvatarResponse>
    
    @GET("users/{userId}/avatar")
    suspend fun getAvatar(
        @Path("userId") userId: String
    ): Response<ResponseBody>
    
    @DELETE("users/me/avatar")
    suspend fun deleteAvatar(): Response<DeleteAvatarResponse>
}

data class UploadAvatarResponse(
    val success: Boolean,
    val has_avatar: Boolean
)

data class DeleteAvatarResponse(
    val success: Boolean,
    val has_avatar: Boolean
)
```

**Caching Strategy:**

```kotlin
// Use Glide's disk cache
Glide.with(context)
    .load("${apiBaseUrl}/users/${userId}/avatar")
    .diskCacheStrategy(DiskCacheStrategy.ALL) // Cache original + transformed
    .signature(ObjectKey(user.updated_at)) // Invalidate when user updates
    .circleCrop()
    .into(imageView)
```

**Dependencies (build.gradle.kts):**
```kotlin
dependencies {
    // Image loading
    implementation("com.github.bumptech.glide:glide:4.16.0")
    
    // Image cropping
    implementation("com.vanniktech:android-image-cropper:4.5.0")
    
    // Multipart upload
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
}
```

**File Provider (res/xml/file_paths.xml):**
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="avatar_cache" path="avatars/" />
</paths>
```

**AndroidManifest.xml:**
```xml
<provider
    android:name="androidx.core.content.FileProvider"
    android:authorities="${applicationId}.fileprovider"
    android:exported="false"
    android:grantUriPermissions="true">
    <meta-data
        android:name="android.support.FILE_PROVIDER_PATHS"
        android:resource="@xml/file_paths" />
</provider>

<uses-permission android:name="android.permission.CAMERA" />
<uses-feature android:name="android.hardware.camera" android:required="false" />
```

**Error Handling:**

- File too large (>5 MB): "Image too large. Please choose a smaller image."
- Invalid file type: "Please select a valid image (PNG, JPG, WebP)"
- Network error: "Upload failed. Check your connection and try again."
- Server error: "Unable to upload. Please try again later."

**Notes:**
- Backend automatically resizes to 256x256 and converts to WebP
- Android can send any size/format - backend handles processing
- Use ETag caching to avoid re-downloading unchanged avatars
- Letter avatars shown as fallback when no image uploaded

---

### 4.6 Account Settings

**Layout:**
```
┌─────────────────────────────────────┐
│ ← Account Settings                  │
├─────────────────────────────────────┤
│ Profile                             │
│ ┌─────────────────────────────────┐│
│ │ Display Name                    ││
│ │ Shannon Thompson    [Edit]      ││
│ └─────────────────────────────────┘│
│ ┌─────────────────────────────────┐│
│ │ Email                           ││
│ │ shannon@example.com  [Edit]     ││
│ └─────────────────────────────────┘│
│ ┌─────────────────────────────────┐│
│ │ Password                        ││
│ │ ••••••••            [Change]    ││ ← "Set Password" if Google-only
│ └─────────────────────────────────┘│
│                                     │
│ Sign-In Methods                     │
│ ┌─────────────────────────────────┐│
│ │ 🔐 Linked Accounts              ││
│ │ Email, Google                   ││
│ │                     [Manage →]  ││
│ └─────────────────────────────────┘│
│                                     │
│ Account Actions                     │
│ ┌─────────────────────────────────┐│
│ │ Sign Out                        ││
│ └─────────────────────────────────┘│
│ ┌─────────────────────────────────┐│
│ │ Delete Account (Red text)       ││
│ └─────────────────────────────────┘│
└─────────────────────────────────────┘
```

**Notes:**
- Google-only users see "Set Password" instead of "Change Password"
- Adding password enables recovery via email reset
- Encourages linking multiple auth methods for redundancy

---

### 4.6.1 Linked Accounts

**Layout:**
```
┌─────────────────────────────────────┐
│ ← Linked Accounts                   │
├─────────────────────────────────────┤
│ You can sign in with any of these:  │
│                                     │
│ ┌─────────────────────────────────┐│
│ │ ✓ Email & Password              ││
│ │ shannon@example.com             ││
│ │ Linked: Jan 1, 2026             ││
│ │                                 ││ ← No unlink (has password)
│ └─────────────────────────────────┘│
│                                     │
│ ┌─────────────────────────────────┐│
│ │ ✓ Google                        ││
│ │ shannon@example.com             ││
│ │ Linked: Jan 15, 2026            ││
│ │                     [Unlink]    ││
│ └─────────────────────────────────┘│
│                                     │
│ ────────────────────                │
│                                     │
│ Available to Link                   │
│ ┌─────────────────────────────────┐│
│ │ [G] Link Google Account         ││
│ └─────────────────────────────────┘│
│                                     │
└─────────────────────────────────────┘
```

**Link Google Account:**
- Taps "Link Google Account"
- Google Sign-In SDK opens
- User selects account
- App sends ID token to backend
- POST /api/auth/link/google
- Show success: "Google account linked!"

**Unlink Account:**
- Taps "Unlink" on Google
- Show confirmation dialog
- DELETE /api/auth/unlink/google
- Refresh list

**Unlink Confirmation:**
```
┌─────────────────────────────────────┐
│   Unlink Google account?            │
│                                     │
│   You'll no longer be able to sign  │
│   in with Google.                   │
│                                     │
│   You can still sign in with:      │
│   • Email & Password                │
│                                     │
│   [Cancel]              [Unlink]    │
│                                     │
└─────────────────────────────────────┘
```

**Cannot Unlink Last Method:**
```
┌─────────────────────────────────────┐
│   ⚠️ Cannot unlink                   │
│                                     │
│   This is your only sign-in method. │
│                                     │
│   Add another sign-in method before │
│   unlinking Google.                 │
│                                     │
│           [OK]                      │
│                                     │
└─────────────────────────────────────┘
```

---

### 4.7 Linked Devices

**Layout:**
```
┌─────────────────────────────────────┐
│ ← Linked Devices                    │
├─────────────────────────────────────┤
│ Devices signed into your account    │
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 📱 iPhone 15 Pro (This device)  ││
│ │ Last active: Now                ││
│ │ Signed in: Jan 1, 2026          ││
│ └─────────────────────────────────┘│
│                                     │
│ ┌─────────────────────────────────┐│
│ │ 💻 Work Laptop                  ││
│ │ Last active: 2 hours ago        ││
│ │ Signed in: Jan 10, 2026         ││
│ │                     [Sign Out]  ││
│ └─────────────────────────────────┘│
│                                     │
│ All devices share the same account. │
│ Sign out a device to revoke access. │
│                                     │
└─────────────────────────────────────┘
```

**Sign Out Device:**
- Confirmation dialog
- Deletes device's FCM token from server
- Device must sign in again to access account

---

### 4.8 Create Group Flow

**Layout:**
```
┌─────────────────────────────────────┐
│ ✕  Create New Group                 │
├─────────────────────────────────────┤
│ Group Icon (optional)               │
│ ┌───────┐                           │
│ │  🏃   │ [Choose Icon]             │ ← Tap to open icon picker
│ └───────┘                           │
│                                     │
│ Group Name *                        │
│ ┌─────────────────────────────────┐│
│ │ Morning Runners                 ││
│ └─────────────────────────────────┘│
│                                     │
│ Description (optional)              │
│ ┌─────────────────────────────────┐│
│ │ Daily accountability for        ││
│ │ morning runs                    ││
│ └─────────────────────────────────┘│
│                                     │
│ Initial Goals (optional)            │
│ ┌─────────────────────────────────┐│
│ │ + Add a goal                    ││
│ └─────────────────────────────────┘│
│                                     │
│ [Cancel]              [Create]      │
│                                     │
└─────────────────────────────────────┘
```

**Icon Picker (Bottom Sheet):**
```
┌─────────────────────────────────────┐
│ Choose Group Icon                   │
├─────────────────────────────────────┤
│ [Emoji]  [Upload]  [Color]          │ ← Tabs
├─────────────────────────────────────┤
│                                     │
│ 🏃 💪 📚 🎯 🎨 💻 🍎 ⚽            │
│ 🏋️ 🧘 🚴 🏊 🎵 ✍️ 🌟 🔥            │
│ 💼 📊 🎓 🌱 ☕ 🏡 ✨ 🎉            │
│                                     │
│ [Search emoji...]                   │
│                                     │
│ [Close]                             │
└─────────────────────────────────────┘

Tab: [Emoji] - Currently selected
Tab: [Upload] - Upload custom image (available in initial release)
Tab: [Color] - Choose background color for emoji
```

**Emoji Tab (Default):**
- Grid of common emojis (fitness, education, work, hobbies)
- Search bar to find specific emoji
- Recently used emojis at top
- Tap emoji to select
- Default color: Blue (#1976D2)

**Upload Tab (Initial Release):**
```
┌─────────────────────────────────────┐
│ Upload Custom Icon                  │
├─────────────────────────────────────┤
│                                     │
│   ┌─────────────────────────┐      │
│   │                         │      │
│   │    [Camera Icon]        │      │
│   │                         │      │
│   │  Tap to upload image    │      │
│   │                         │      │
│   └─────────────────────────┘      │
│                                     │
│ • Square images work best           │
│ • Max file size: 5 MB               │
│ • PNG, JPG, or WebP                 │
│                                     │
│ [Choose from Gallery]               │
│ [Take Photo]                        │
│                                     │
└─────────────────────────────────────┘
```

**Color Tab:**
```
┌─────────────────────────────────────┐
│ Background Color                    │
├─────────────────────────────────────┤
│                                     │
│ 🏃  ← Preview with selected emoji   │
│                                     │
│ ● ● ● ● ● ● ● ●                    │ ← Color palette (8 colors)
│ ● ● ● ● ● ● ● ●                    │
│                                     │
│ Blue    Yellow   Green   Red        │
│ Purple  Orange   Pink    Teal       │
│                                     │
└─────────────────────────────────────┘
```

**Predefined Color Palette:**
- Blue: #1976D2 (default, trust)
- Yellow: #F9A825 (achievement, energy)
- Green: #388E3C (growth, health)
- Red: #D32F2F (intensity, passion)
- Purple: #7B1FA2 (creativity)
- Orange: #F57C00 (enthusiasm)
- Pink: #C2185B (community)
- Teal: #00796B (calm, focus)

**Icon Selection Logic:**
```kotlin
// User taps icon area
showIconPickerBottomSheet()

// User selects emoji
onEmojiSelected(emoji: String) {
    groupIcon.emoji = emoji
    groupIcon.color = selectedColor ?: "#1976D2"  // Default blue
    groupIcon.url = null
}

// User uploads image (future)
onImageUploaded(imageUri: Uri) {
    groupIcon.emoji = null
    groupIcon.color = null
    groupIcon.url = imageUri.toString()  // Will be uploaded on create
}
```

**On Create:**
- POST to /api/groups with icon_emoji and icon_color
- If image uploaded (future): POST to /api/groups, then PATCH /api/groups/:id/icon
- Server creates group, adds user as creator
- Navigate to Group Detail
- Show success: "Group created!"

**Validation:**
- Group name: Required, 1-100 characters
- Description: Optional, max 500 characters
- Icon: Optional (defaults to first letter of name with blue background)
- Initial goals: Optional

---

### 4.8.1 Edit Group (Admin Only)

**Accessed via:** Group Detail → Overflow menu (⋮) → "Edit Group"

**Layout:**
```
┌─────────────────────────────────────┐
│ ✕  Edit Group                       │
├─────────────────────────────────────┤
│ Group Icon                          │
│ ┌───────┐                           │
│ │  🏃   │ [Change Icon]             │ ← Tap to open icon picker
│ └───────┘                           │
│                                     │
│ Group Name *                        │
│ ┌─────────────────────────────────┐│
│ │ Morning Runners                 ││
│ └─────────────────────────────────┘│
│                                     │
│ Description (optional)              │
│ ┌─────────────────────────────────┐│
│ │ Daily accountability for        ││
│ │ morning runs                    ││
│ └─────────────────────────────────┘│
│                                     │
│ [Delete Group]   ← Red, creator only│
│                                     │
│ [Cancel]              [Save]        │
│                                     │
└─────────────────────────────────────┘
```

**Icon Change Options:**

When user taps "Change Icon", show same icon picker as Create Group:
1. **Emoji tab** - Select new emoji and color
2. **Upload tab** - Upload custom image (available in initial release)
3. **Color tab** - Change background color for current emoji
4. **Remove** - Delete uploaded icon (reverts to emoji or letter)

**On Save:**
- PATCH /api/groups/:id with updated fields
- If emoji changed: Update `icon_emoji` and `icon_color`
- If image uploaded (future): PATCH /api/groups/:id/icon
- If image removed: DELETE /api/groups/:id/icon
- Show success: "Group updated!"
- Update group detail screen

**Authorization:**
- Only admins and creator can edit group
- Only creator can delete group

**Delete Group Confirmation:**
```
┌─────────────────────────────────────┐
│   Delete "Morning Runners"?         │
│                                     │
│   This will permanently delete:     │
│   • All group goals                 │
│   • All member progress             │
│   • All group activity              │
│                                     │
│   This cannot be undone.            │
│                                     │
│   [Cancel]              [Delete]    │ ← Red, destructive
└─────────────────────────────────────┘
```

**On Delete:**
- DELETE /api/groups/:id
- Show success: "Group deleted"
- Navigate back to Home screen
- Remove group from user's list

**Validation:**
- Same as Create Group
- Cannot save without group name

---

### 4.9 Invite Members Flow

#### 4.9.1 Generate Invite

**Layout:**
```
┌─────────────────────────────────────┐
│ ✕  Invite to Morning Runners        │
├─────────────────────────────────────┤
│ Share invite code:                  │
│                                     │
│ ┌─────────────────────────────────┐│
│ │ PURSUE-ABC123-XYZ789            ││
│ │ [📋 Copy]          [Share]      ││
│ └─────────────────────────────────┘│
│                                     │
│   ┌─────────────────────────────┐  │
│   │                             │  │
│   │      [QR CODE IMAGE]        │  │
│   │                             │  │
│   └─────────────────────────────┘  │
│                                     │
│ Expires: Never                      │
│ Max uses: Unlimited                 │
│                                     │
│ [Change Settings]      [Done]       │
│                                     │
└─────────────────────────────────────┘
```

**On Generate:**
- POST to /api/groups/{id}/invites
- Server generates invite code
- QR code contains: `https://getpursue.app/invite/PURSUE-ABC123-XYZ789`

#### 4.9.2 Join Group

**Via Invite Code:**
```
┌─────────────────────────────────────┐
│ ✕  Join Group                       │
├─────────────────────────────────────┤
│ Enter invite code:                  │
│                                     │
│ ┌─────────────────────────────────┐│
│ │ PURSUE-ABC123-XYZ789            ││
│ └─────────────────────────────────┘│
│ [📋 Paste]                          │
│                                     │
│ Or scan QR code:                    │
│ [Scan Code]                         │
│                                     │
│ [Cancel]              [Join]        │
│                                     │
└─────────────────────────────────────┘
```

**Confirmation:**
```
┌─────────────────────────────────────┐
│   Join this group?                  │
│                                     │
│   🏃 Morning Runners                │
│   8 members · 5 active goals        │
│                                     │
│   Created by Shannon Thompson       │
│                                     │
│   [Cancel]              [Join]      │
│                                     │
└─────────────────────────────────────┘
```

**On Join:**
- POST to /api/groups/join
- Server adds user to group
- Server sends FCM push to all members
- Navigate to Group Detail

---

### 4.10 Log Progress Flow

**Bottom Sheet:**
```
┌─────────────────────────────────────┐
│ ───                                 │
│ Log Progress                        │
├─────────────────────────────────────┤
│ Select Goal:                        │
│ ┌─────────────────────────────────┐│
│ │ 30 min run (Daily) ▼            ││
│ └─────────────────────────────────┘│
│                                     │
│ Did you complete this today?        │
│   ┌──────────┐  ┌──────────┐       │
│   │   Yes    │  │    No    │       │
│   └──────────┘  └──────────┘       │
│                                     │
│ Add Note (optional)                 │
│ ┌─────────────────────────────────┐│
│ │ Great run in the park!          ││
│ └─────────────────────────────────┘│
│                                     │
│ [Cancel]                 [Log]      │
│                                     │
└─────────────────────────────────────┘
```

**On Log:**
- Validate input
- POST to /api/progress
- **Success Feedback (Binary Goal - "Yes"):**
  1. Dismiss bottom sheet with smooth slide-down (200ms)
  2. Brief celebration animation (300ms):
     - Option A: Checkmark icon bounces and scales 1.0 → 1.3 → 1.0
     - Option B: Subtle confetti burst from FAB (3-5 particles)
     - Use Lottie animation for high quality
  3. Medium haptic feedback (single "tap")
  4. Success snackbar: "Progress logged! 🎉" (2 seconds)
  5. Update goal status indicator immediately
- **Success Feedback (Numeric Goal):**
  - Same as above but without celebration animation
  - Snackbar: "55 pages logged!"
- **Success Feedback (Binary Goal - "No"):**
  - No celebration animation
  - Gentle haptic (light impact)
  - Snackbar: "Logged for today"
- Server sends FCM push to group members
- Group activity feed updates automatically

**Design Note:**
- Keep celebrations subtle and fast (300ms max)
- Don't overuse confetti - reserve for genuine achievements
- Haptics should feel satisfying but not jarring
- Use system haptic patterns (UIImpactFeedbackGenerator on iOS-like)

---

### 4.11 Loading & Error States

#### 4.11.1 Loading States

**Full Screen Loading (Initial Data):**
```
┌─────────────────────────────────────┐
│                                     │
│          ⟳                          │
│    Loading your groups...           │
│                                     │
└─────────────────────────────────────┘
```

**Inline Loading (Refresh):**
- Standard Material pull-to-refresh spinner at top
- Linear progress bar below top app bar

**Button Loading:**
- Replace button text with small circular spinner
- Disable button during operation

#### 4.11.2 Error States

**Network Error (Snackbar):**
```
┌─────────────────────────────────────┐
│ ⚠ No internet connection            │
│ Check your connection and try again │
│                          [Dismiss]  │
└─────────────────────────────────────┘
```

**Failed Operation (Dialog):**
```
┌─────────────────────────────────────┐
│   ⚠️ Something went wrong            │
│                                     │
│   We couldn't complete that action. │
│   Please try again.                 │
│                                     │
│           [Try Again]               │
│                                     │
└─────────────────────────────────────┘
```

**Offline Mode (Banner):**
```
┌─────────────────────────────────────┐
│ ○ Offline                           │
│ Viewing cached data                 │
│                             [✕]     │
└─────────────────────────────────────┘
```

When offline:
- Show cached data with "Last updated: X ago"
- Queue new progress entries
- Show "Pending upload" indicator
- Auto-upload when connection returns

---

### 4.12 Push Notifications

**Progress Update:**
```
🔔 Morning Runners
Alex completed "30 min run"
Tap to view
```

**New Member:**
```
🔔 Morning Runners
Jamie joined the group
Tap to welcome them
```

**Group Renamed:**
```
🔔 Group Renamed
"Runners" → "Morning Runners"
by Shannon
```

---

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

- **Button Press**: Ripple effect only (Material Design)
- **Card Tap**: Ripple effect only
- **Checkbox/Toggle**: Instant state change
- **Progress Logged**: Checkmark appears instantly

---

## 6. Accessibility

### 6.1 Requirements

**WCAG 2.1 Level AA:**
- Color contrast ratio ≥ 4.5:1 for text
- Touch targets ≥ 48dp × 48dp
- Screen reader support (TalkBack)
- Dynamic text sizing

### 6.2 Colorblind Support

- Blue and gold palette optimized for deuteranopia/protanopia
- No reliance on color alone for information
- Icons and text labels supplement color coding
- Heatmap uses intensity (opacity) not just color
- Progress indicators show percentage text alongside bar

### 6.3 Screen Reader Support (TalkBack)

**Content Descriptions:**
- All interactive elements have meaningful labels
- Progress bars: "30 min run, completed today"
- FAB: "Log progress for today's goals"
- Member avatars: "Shannon Thompson, last active 2 hours ago"
- Heatmap cells: "January 15th, 4 of 5 goals completed"

**Semantic Markup:**
- Use heading hierarchy (h1, h2, h3) for sections
- Group related controls (RadioGroup for Yes/No)
- Mark decorative images as decorative

**Navigation:**
- Bottom nav items read as "Home, tab 1 of 4, selected"
- FAB announced when focused
- Swipe gestures for next/previous item work correctly

### 6.4 Dynamic Text Sizing

**Support up to 200% text size:**
- All text wraps properly, no truncation
- UI adapts to larger text (cards expand vertically)
- Touch targets maintain 48dp minimum even with large text
- Test at 100%, 150%, 200% scale

### 6.5 Haptic Feedback

**Appropriate Use:**
- Goal logged successfully: Medium impact (one tap)
- Goal completed (binary "Yes"): Medium impact + celebration
- Button press: Light impact (optional, can be disabled)
- Error: Warning haptic pattern
- Settings toggle to disable all haptics

**Accessibility Note:**
- Never rely on haptics alone for critical feedback
- Always pair with visual confirmation (toast, animation)

---

## 7. Performance Targets & Quality Benchmarks

### 7.1 Performance Metrics

**Startup Performance:**
- **Cold Start**: < 2 seconds from launch to first interactive screen
- **Warm Start**: < 500ms to restored state
- **Hot Start**: < 300ms (app in background)

**Runtime Performance:**
- **Screen Transitions**: Maintain 60fps (16.67ms per frame)
- **List Scrolling**: Smooth scrolling with 1000+ items
- **Image Loading**: < 200ms to show placeholder, progressive loading
- **API Calls**: p95 < 500ms, p99 < 1000ms
- **Touch Response**: < 100ms latency from tap to visual feedback

**Resource Usage:**
- **Memory**: < 150MB typical usage, < 300MB peak
- **Battery**: < 2% drain per hour of active use
- **Network**: Efficient request batching, automatic retry with backoff
- **Storage**: < 100MB app size, < 500MB with cached data

### 7.2 Quality Metrics

**Stability:**
- **Crash-Free Rate**: > 99.5% of sessions
- **ANR Rate**: < 0.1% of sessions
- **Network Success Rate**: > 99% for valid API requests

**User Experience:**
- **Time to First Interaction**: < 2 seconds
- **Success Rate**: > 95% for core user flows (login, log progress, create goal)
- **Error Recovery**: 100% of errors have clear messaging and recovery path

**User Retention Targets:**
- **Day 1**: 40% of new users return
- **Day 7**: 25% of new users still active
- **Day 30**: 15% of new users still active
- **Monthly Active Users**: 60% of installed base

### 7.3 Testing Strategy

**Unit Tests (70% coverage minimum):**
- ViewModels: All business logic, state management
- Repositories: API calls, data transformations
- Utilities: Date formatting, validation, calculations

**Integration Tests (E2E):**
- Authentication flows (register, login, Google sign-in)
- Core user journeys (create group, log progress, view stats)
- Offline/online transitions
- Token refresh handling

**UI Tests (Espresso):**
- Critical user flows (smoke tests)
- Form validation and error states
- Navigation between screens
- Pull-to-refresh, pagination

**Manual QA Checklist:**
- [ ] Test on low-end devices (2GB RAM, old Android versions)
- [ ] Test with poor network conditions (airplane mode, slow 3G)
- [ ] Test accessibility with TalkBack enabled
- [ ] Test text scaling at 200%
- [ ] Test in landscape orientation
- [ ] Test with various timezones
- [ ] Test color blindness modes

**Performance Profiling:**
- Android Studio Profiler for memory leaks
- Systrace for frame drops and jank
- Network profiler for API efficiency
- APK Analyzer for app size optimization

---

## 8. Future Enhancements

### 8.1 Android Implementation Notes (Google Sign-In)

**Dependencies (build.gradle):**
```gradle
dependencies {
    // Google Sign-In
    implementation 'com.google.android.gms:play-services-auth:20.7.0'
    
    // Existing dependencies
    implementation 'androidx.compose.material3:material3:1.1.0'
    implementation 'com.squareup.retrofit2:retrofit:2.9.0'
    // ...
}
```

**Google Sign-In Configuration:**
1. Add SHA-1 fingerprint to Firebase Console
2. Download google-services.json
3. Add to app/ directory

**Kotlin Implementation:**
```kotlin
// GoogleSignInHelper.kt
class GoogleSignInHelper(private val context: Context) {
    
    private val googleSignInClient: GoogleSignInClient
    
    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(context.getString(R.string.google_client_id))
            .requestEmail()
            .build()
            
        googleSignInClient = GoogleSignIn.getClient(context, gso)
    }
    
    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }
    
    suspend fun handleSignInResult(data: Intent?): GoogleSignInResult {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.await()
            GoogleSignInResult.Success(account)
        } catch (e: ApiException) {
            GoogleSignInResult.Error(e.message ?: "Sign in failed")
        }
    }
}

sealed class GoogleSignInResult {
    data class Success(val account: GoogleSignInAccount) : GoogleSignInResult()
    data class Error(val message: String) : GoogleSignInResult()
}
```

**ViewModel Integration:**
```kotlin
// AuthViewModel.kt
class AuthViewModel(
    private val authRepository: AuthRepository,
    private val googleSignInHelper: GoogleSignInHelper
) : ViewModel() {
    
    fun startGoogleSignIn(): Intent {
        return googleSignInHelper.getSignInIntent()
    }
    
    suspend fun handleGoogleSignIn(data: Intent?) {
        val result = googleSignInHelper.handleSignInResult(data)
        
        when (result) {
            is GoogleSignInResult.Success -> {
                val idToken = result.account.idToken
                if (idToken != null) {
                    // Send to backend
                    authRepository.signInWithGoogle(idToken)
                }
            }
            is GoogleSignInResult.Error -> {
                // Show error
            }
        }
    }
}
```

### 8.2 Future Enhancements

- [ ] **Private Groups (End-to-End Encryption)** - Future consideration
  - Group-level seed phrases (not user-level)
  - Enable "Privacy Mode" toggle when creating group
  - Group creator generates and backs up 12-word seed phrase
  - Goal titles, descriptions, notes encrypted client-side
  - Server blind to encrypted group data
  - Potential premium feature or power-user opt-in
- [ ] Dark mode with system theme detection
- [ ] Advanced progress charts and trend visualizations
- [ ] Goal templates library with curated presets
- [ ] Enhanced streaks and achievement system
- [ ] Automated weekly/monthly progress summaries
- [ ] Photo attachments for progress
- [ ] Comments on progress entries
- [ ] Web companion app

---

**End of UI Specification**
