# Pursue — Crash Reporting Spec
## Firebase Crashlytics Integration (Android)

---

## Overview

Pursue uses **Firebase Crashlytics** for real-time crash reporting. Since Pursue already uses Firebase Cloud Messaging (FCM), the Firebase project is already set up — Crashlytics is simply an additional SDK on top of the existing `google-services.json`.

Crashlytics provides:
- Automatic crash and ANR (Application Not Responding) detection
- Grouped, deduplicated crash reports with stack traces
- Device metadata, OS version, and custom keys per report
- Breadcrumb logs showing the user actions leading up to a crash
- Non-fatal error tracking (caught exceptions you still want visibility on)
- Google Play track filtering (internal / alpha / production)

---

## Prerequisites

- Firebase project already exists (from FCM setup)
- `google-services.json` already in `app/`
- AGP (Android Gradle Plugin) 8.1+
- `com.google.gms.google-services` plugin 4.4.1+
- Google Analytics **enabled** in the Firebase project (required for breadcrumb logs)

To enable Google Analytics: Firebase Console → Project Settings → Integrations → Google Analytics → Enable.

---

## 1. Gradle Setup

### `settings.gradle.kts` (root)

```kotlin
plugins {
    id("com.android.application") version "8.1.4" apply false
    id("org.jetbrains.kotlin.android") version "1.9.0" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false     // already present
    id("com.google.firebase.crashlytics") version "3.0.6" apply false    // ADD THIS
}
```

### `app/build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")        // already present
    id("com.google.firebase.crashlytics")       // ADD THIS
}

dependencies {
    // Firebase BoM — manages all Firebase library versions
    implementation(platform("com.google.firebase:firebase-bom:33.9.0"))

    // Already present (FCM):
    implementation("com.google.firebase:firebase-messaging")

    // ADD: Crashlytics + Analytics (Analytics enables breadcrumb logs)
    implementation("com.google.firebase:firebase-crashlytics")
    implementation("com.google.firebase:firebase-analytics")
}
```

> **Note:** Using the Firebase BoM means you don't specify version numbers for individual Firebase libraries — the BoM handles that.

---

## 2. Application Class Setup

Crashlytics initialises automatically once the SDK is on the classpath — no manual `init()` call is needed. However, collection must be gated on the user's explicit opt-in preference (see Section 2a below), and the `Application` class is the right place to apply that preference at startup before any reporting occurs.

```kotlin
// PursueApplication.kt
class PursueApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        setupCrashlytics()
    }

    private fun setupCrashlytics() {
        val crashlytics = FirebaseCrashlytics.getInstance()

        // Apply the user's opt-in preference. This must be called before any
        // other Crashlytics calls so that no data is sent until consent is given.
        val enabled = CrashlyticsPreference.isEnabled(this)
        crashlytics.setCrashlyticsCollectionEnabled(enabled)

        // Only set identifying keys if collection is active
        if (enabled) {
            crashlytics.setCustomKey("app_version_name", BuildConfig.VERSION_NAME)
            crashlytics.setCustomKey("app_version_code", BuildConfig.VERSION_CODE)
            crashlytics.setCustomKey("build_type", BuildConfig.BUILD_TYPE)
        }
    }
}
```

Ensure `PursueApplication` is registered in `AndroidManifest.xml`:

```xml
<application
    android:name=".PursueApplication"
    ...>
```

---

## 2a. User Opt-In Toggle

Under NZ's Privacy Act 2020, crash diagnostics are non-essential data — the app functions without them. The most compliant approach is explicit opt-in, with the preference surfaced in Settings and defaulting to **disabled** until the user actively enables it.

### Preference Helper

```kotlin
// CrashlyticsPreference.kt
object CrashlyticsPreference {
    private const val PREFS_NAME = "pursue_privacy_prefs"
    private const val KEY_CRASHLYTICS_ENABLED = "crashlytics_collection_enabled"

    /** Returns true only if the user has explicitly opted in. Defaults to false. */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CRASHLYTICS_ENABLED, false)
    }

    /**
     * Persist the user's preference and apply it to the live Crashlytics instance.
     * Note: setCrashlyticsCollectionEnabled() takes effect immediately but a full
     * toggle (especially disabling) is cleanest after an app restart. Consider
     * showing a snackbar: "Change takes full effect on next app launch."
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CRASHLYTICS_ENABLED, enabled)
            .apply()

        FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(enabled)

        // If the user is opting out, clear any stored user ID immediately
        if (!enabled) {
            FirebaseCrashlytics.getInstance().setUserId("")
        }
    }
}
```

### Settings UI (Jetpack Compose)

Add this toggle to your existing Settings screen:

```kotlin
@Composable
fun CrashlyticsToggleSetting() {
    val context = LocalContext.current
    var isEnabled by remember {
        mutableStateOf(CrashlyticsPreference.isEnabled(context))
    }

    ListItem(
        headlineContent = { Text("Share usage & crash data") },
        supportingContent = {
            Text("Help us improve Pursue by sharing anonymous usage and crash data.")
        },
        trailingContent = {
            Switch(
                checked = isEnabled,
                onCheckedChange = { newValue ->
                    isEnabled = newValue
                    CrashlyticsPreference.setEnabled(context, newValue)
                }
            )
        }
    )
}
```
Add an info button with the additional information: "Includes which screens you visit and crash reports. Never includes your goal text or notes."

> **Privacy copy guidance:** Keep the label and description honest and plain. Avoid framing like "Help us improve!" that obscures what the toggle actually does. "Send anonymous crash reports" is clearer and more compliant.

### First-Run Prompt (Optional but Recommended)

For the strongest compliance posture, surface the toggle during onboarding so users make an active choice rather than discovering it buried in Settings:

```kotlin
// Show once, after account creation / onboarding completion
@Composable
fun CrashReportingConsentDialog(onDismiss: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = { onDismiss(false) },
        title = { Text("Help improve Pursue?") },
        text = {
            Text(
                "If the app crashes, we can automatically send a report to help us " +
                "fix the issue. Reports contain device info and what you were doing " +
                "in the app — no personal content like your goal text.\n\n" +
                "You can change this at any time in Settings."
            )
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(true) }) { Text("Sure, send reports") }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss(false) }) { Text("No thanks") }
        }
    )
}
```

```kotlin
// In your onboarding ViewModel / completion handler:
fun onCrashConsentResult(enabled: Boolean) {
    CrashlyticsPreference.setEnabled(context, enabled)
    // Mark that we've asked, so we don't show the dialog again
    prefs.edit().putBoolean("crash_consent_asked", true).apply()
}
```

---

## 3. User Identity

Once a user logs in, tag their Crashlytics sessions so you can look up crashes by user ID. **Do not send PII (names, emails) — only the internal user ID.**

```kotlin
// Call this after a successful login / session restore
fun onUserLoggedIn(userId: String) {
    FirebaseCrashlytics.getInstance().setUserId(userId)
}

// Call this on logout / account deletion
fun onUserLoggedOut() {
    FirebaseCrashlytics.getInstance().setUserId("")
}
```

---

## 4. Custom Keys for Context

Set contextual keys at key points in the app to give crash reports more signal:

```kotlin
object CrashlyticsKeys {
    fun setActiveGroup(groupId: String?) {
        FirebaseCrashlytics.getInstance()
            .setCustomKey("active_group_id", groupId ?: "none")
    }

    fun setActiveScreen(screenName: String) {
        FirebaseCrashlytics.getInstance()
            .setCustomKey("current_screen", screenName)
    }

    fun setPremiumStatus(isPremium: Boolean) {
        FirebaseCrashlytics.getInstance()
            .setCustomKey("is_premium", isPremium)
    }
}
```

**Where to call these:**

| Key | When to set |
|-----|-------------|
| `current_screen` | In each Composable's `LaunchedEffect(Unit)` or navigation observer |
| `active_group_id` | When user opens a group detail screen |
| `is_premium` | After login / subscription status check |

---

## 5. Breadcrumb Logging

Log significant **UI events** leading up to a crash. These appear in the "Logs" tab of a crash report.

### The Core Rule: Log Actions, Never Content

Pursue handles sensitive user data — goal titles, progress notes, and group context can all contain personal or health-related information. If a breadcrumb log captures field content (e.g. the text a user is typing), that string ends up stored in Google's Firebase console indefinitely.

**✅ Safe to log — UI events and system events:**
```
"Tapped 'Save Goal' button"
"Navigated to: GroupFeed"
"Progress photo picker opened"
"FCM token refresh completed"
"Subscription check started"
```

**❌ Never log — anything derived from user input or content:**
```
"Goal title changed to: Address my mental health"   // ❌ content
"Progress note: Ran 5km today"                       // ❌ content  
"User searched for: anxiety support groups"          // ❌ content
"Group name: $groupName"                             // ❌ could be sensitive
```

### CrashlyticsLogger

The logger itself is intentionally minimal — a thin wrapper with no filtering logic, because the discipline of what to log lives at the call site:

```kotlin
object CrashlyticsLogger {
    /**
     * Log a UI event breadcrumb for crash context.
     *
     * IMPORTANT: Only log action names and system events. Never log the *content*
     * of user-facing fields (goal text, notes, group names, search queries, etc.).
     * Those strings may contain sensitive personal data and must not be sent to
     * Firebase Crashlytics.
     */
    fun log(event: String) {
        FirebaseCrashlytics.getInstance().log(event)
    }
}
```

### Approved Breadcrumb Call Sites

To make the privacy boundary explicit and reviewable, define your breadcrumbs as constants rather than inline strings. This makes it easy to audit that no content has crept in:

```kotlin
// CrashlyticsEvents.kt — the exhaustive list of what we log
object CrashlyticsEvents {
    // Navigation
    const val NAV_DASHBOARD = "Navigated to: Dashboard"
    const val NAV_GROUP_FEED = "Navigated to: GroupFeed"
    const val NAV_CREATE_GOAL = "Navigated to: CreateGoal"
    const val NAV_SETTINGS = "Navigated to: Settings"

    // Goal actions — note: no goal content, only the action
    const val GOAL_SAVE_TAPPED = "Tapped 'Save Goal'"
    const val GOAL_DELETE_TAPPED = "Tapped 'Delete Goal'"

    // Progress
    const val PROGRESS_POST_TAPPED = "Tapped 'Post Progress'"
    const val PROGRESS_PHOTO_OPENED = "Progress photo picker opened"
    const val PROGRESS_PHOTO_UPLOADED = "Progress photo upload completed"
    const val PROGRESS_PHOTO_FAILED = "Progress photo upload failed"

    // Groups
    const val GROUP_JOIN_TAPPED = "Tapped 'Join Group'"
    const val GROUP_LEAVE_TAPPED = "Tapped 'Leave Group'"
    const val GROUP_INVITE_SENT = "Group invite sent"

    // System
    const val FCM_TOKEN_REFRESHED = "FCM token refresh completed"
    const val SUBSCRIPTION_CHECK_STARTED = "Subscription check started"
    const val SUBSCRIPTION_CHECK_COMPLETED = "Subscription check completed"
    const val USER_LOGGED_IN = "User logged in"
    const val USER_LOGGED_OUT = "User logged out"
}
```

```kotlin
// Usage — always reference the constants, never build strings from user data:
CrashlyticsLogger.log(CrashlyticsEvents.GOAL_SAVE_TAPPED)   // ✅
CrashlyticsLogger.log(CrashlyticsEvents.NAV_GROUP_FEED)     // ✅

// IDs are fine — they're opaque internal identifiers, not user content:
CrashlyticsLogger.log("Loading group feed — groupId=$groupId")  // ✅
```

---

## 6. Non-Fatal Error Reporting

For errors that are caught and handled gracefully but still warrant visibility:

```kotlin
object CrashlyticsReporter {

    /**
     * Report a non-fatal exception to Crashlytics.
     * Use for errors that are caught but indicate something went wrong.
     */
    fun reportNonFatal(throwable: Throwable, context: String? = null) {
        context?.let {
            FirebaseCrashlytics.getInstance().log("Context: $it")
        }
        FirebaseCrashlytics.getInstance().recordException(throwable)
    }
}

// Usage examples:
try {
    val result = api.getGroupFeed(groupId)
    // ...
} catch (e: HttpException) {
    if (e.code() == 500) {
        // Server error — log as non-fatal for visibility
        CrashlyticsReporter.reportNonFatal(e, "GET /groups/$groupId/feed returned 500")
    }
    showErrorState()
}
```

**Good candidates for non-fatal reporting:**
- Server 500 errors
- JSON parsing failures
- Image upload failures
- FCM token registration failures
- Unexpected null states in ViewModels

---

## 7. Screen Tracking with Jetpack Compose

Add a composable helper to automatically set the current screen key on Crashlytics:

```kotlin
@Composable
fun TrackScreen(name: String) {
    LaunchedEffect(name) {
        CrashlyticsKeys.setActiveScreen(name)
        CrashlyticsLogger.log("Navigated to: $name")
    }
}

// Use at the top of each screen composable:
@Composable
fun GroupFeedScreen(groupId: String) {
    TrackScreen("GroupFeed")
    LaunchedEffect(groupId) {
        CrashlyticsKeys.setActiveGroup(groupId)
    }
    // ...
}
```

---

## 8. Build Type Configuration

Disable Crashlytics in debug builds to keep the dashboard clean during development:

```kotlin
// app/build.gradle.kts
android {
    buildTypes {
        debug {
            // Disable Crashlytics reporting in debug
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
            }
            manifestPlaceholders["firebase_crashlytics_collection_enabled"] = false
        }
        release {
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = true  // upload ProGuard mapping for deobfuscated traces
            }
            manifestPlaceholders["firebase_crashlytics_collection_enabled"] = true
        }
    }
}
```

Add to `AndroidManifest.xml` inside `<application>`:

```xml
<meta-data
    android:name="firebase_crashlytics_collection_enabled"
    android:value="${firebase_crashlytics_collection_enabled}" />
```

---

## 9. ProGuard / R8

If you use ProGuard/R8 (you should for release builds), add these rules to `proguard-rules.pro`:

```
# Firebase Crashlytics
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception

# Keep Crashlytics annotations
-keep class com.google.firebase.crashlytics.** { *; }
```

Crashlytics automatically uploads the mapping file at build time when `mappingFileUploadEnabled = true`, which deobfuscates stack traces in the dashboard.

---

## 10. Test Your Setup

After integrating, verify Crashlytics is working:

1. Add a temporary crash button somewhere in a debug screen:

```kotlin
Button(onClick = { throw RuntimeException("Test crash — remove before release") }) {
    Text("Test Crash")
}
```

2. Run the app on a **release** build (or a debug build with collection temporarily enabled).
3. Tap the button to crash the app.
4. **Relaunch the app** — crash reports are sent on the next launch, not immediately.
5. Check the [Firebase Console → Crashlytics](https://console.firebase.google.com/) dashboard. The report should appear within a few minutes.
6. Remove the test crash button before shipping.

---

## 11. Firebase Console — What to Monitor

Once live, check the Crashlytics dashboard regularly:

| Signal | Action |
|--------|--------|
| **Crash-free rate** drops below 99% | Investigate immediately |
| **New issue** flagged | Triage within 24 hours |
| **Velocity alert** (crash spike) | Firebase can email/Slack you automatically — set this up |
| **ANRs** appearing | Usually indicates blocking work on the main thread |

**Set up alerts:** Firebase Console → Crashlytics → (select issue) → Alerts → Add email alert. Also configure a Slack webhook via Firebase Extensions if desired.

**Google Play integration:** In Firebase Console → Crashlytics → settings, connect to Google Play. This lets you filter crash reports by track (internal / alpha / production) — very useful for staged rollouts.

---

## 12. Implementation Checklist

- [ ] Enable Google Analytics in Firebase Console
- [ ] Add Crashlytics Gradle plugin to `settings.gradle.kts` (v3.0.6+)
- [ ] Apply plugin in `app/build.gradle.kts`
- [ ] Add `firebase-crashlytics` and `firebase-analytics` to dependencies via BoM
- [ ] Set `mappingFileUploadEnabled = true` for release builds
- [ ] Disable collection in debug builds via manifest placeholder
- [ ] Add ProGuard rules
- [ ] Implement `CrashlyticsPreference` helper (opt-in, defaults to `false`)
- [ ] Call `setCrashlyticsCollectionEnabled(userPreference)` in `PursueApplication.onCreate()`
- [ ] Add crash reporting toggle to Settings screen (`CrashlyticsToggleSetting`)
- [ ] Add first-run consent dialog to onboarding flow (optional but recommended)
- [ ] Implement `CrashlyticsKeys` helper and call from login flow
- [ ] Define all breadcrumbs as constants in `CrashlyticsEvents.kt`
- [ ] Audit every `CrashlyticsLogger.log()` call site — no user content, IDs only
- [ ] Add `TrackScreen()` composable to all major screens
- [ ] Add `CrashlyticsReporter.reportNonFatal()` around API calls
- [ ] Force a test crash, relaunch, confirm report in console
- [ ] Set up email/Slack velocity alerts in Firebase Console
- [ ] Connect to Google Play in Firebase Console
- [ ] Remove test crash button

---

## Dependencies Reference

| Dependency | Version |
|-----------|---------|
| Firebase BoM | 33.9.0 |
| Crashlytics Gradle plugin | 3.0.6 |
| Google Services Gradle plugin | 4.4.4 |
| AGP minimum | 8.1+ |
| minSdkVersion (Crashlytics requirement) | 23+ |
