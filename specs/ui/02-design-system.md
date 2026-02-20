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

