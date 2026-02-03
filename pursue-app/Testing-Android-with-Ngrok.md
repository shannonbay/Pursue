# Testing Android App with Local Dev Server using ngrok

**Guide Version:** 1.0  
**Last Updated:** January 22, 2026  
**Purpose:** Test your Android app on a physical device against your locally-running backend server

---

## Table of Contents

1. [Overview](#overview)
2. [Why Use ngrok?](#why-use-ngrok)
3. [Prerequisites](#prerequisites)
4. [Installation](#installation)
5. [Basic Setup](#basic-setup)
6. [Android App Configuration](#android-app-configuration)
7. [Testing Workflow](#testing-workflow)
8. [Common Issues & Solutions](#common-issues--solutions)
9. [Advanced Configuration](#advanced-configuration)
10. [Security Considerations](#security-considerations)
11. [Alternatives to ngrok](#alternatives-to-ngrok)

---

## Overview

**The Problem:**
- Your backend server runs on `http://localhost:3000`
- Your Android phone can't access `localhost` (it's not on the same network interface)
- You want to test on a real device, not an emulator

**The Solution:**
- Use ngrok to create a secure tunnel from the internet to your localhost
- Your Android phone accesses the server via ngrok's public URL
- No need for complex network configuration or port forwarding

**Flow Diagram:**
```
Android Phone (Physical Device)
        ↓
    (Internet)
        ↓
ngrok.io URL (https://abc123.ngrok.io)
        ↓
   ngrok Tunnel
        ↓
Your Computer (localhost:3000)
        ↓
  Backend Server (Node.js/Express)
```

---

## Why Use ngrok?

### ✅ Advantages

1. **Works from anywhere** - Phone doesn't need to be on same Wi-Fi
2. **HTTPS by default** - Tests SSL/TLS connections
3. **Inspect traffic** - Built-in web interface to see all requests
4. **Stable URLs** (paid) - URL doesn't change between restarts
5. **No router config** - No need to open ports or configure firewall
6. **Testing webhooks** - Great for testing Firebase Cloud Messaging, OAuth callbacks, etc.

### ❌ Limitations

1. **Free tier limitations:**
   - URL changes every restart (e.g., `https://abc123.ngrok.io` → `https://xyz789.ngrok.io`)
   - 1 tunnel at a time
   - Rate limited (40 connections/minute)
2. **Not for production** - Only for development/testing
3. **Internet dependency** - Requires internet connection (won't work offline)

---

## Prerequisites

### Required

- ✅ **Backend server** running locally (Node.js, Express, etc.)
- ✅ **Physical Android device** with USB debugging enabled
- ✅ **Internet connection** (both computer and phone)
- ✅ **ngrok account** (free tier is fine)

### Optional

- ⚙️ **Android Studio** (for debugging)
- ⚙️ **adb** (Android Debug Bridge)
- ⚙️ **Git** (for version control)

---

## Installation

### Step 1: Install ngrok

**Option A: Download from Website (Recommended)**

1. Go to https://ngrok.com/download
2. Sign up for a free account
3. Download ngrok for your OS:
   - **Windows:** `ngrok-v3-stable-windows-amd64.zip`
   - **Mac (Intel):** `ngrok-v3-stable-darwin-amd64.zip`
   - **Mac (Apple Silicon):** `ngrok-v3-stable-darwin-arm64.zip`
   - **Linux:** `ngrok-v3-stable-linux-amd64.zip`
4. Extract to a folder (e.g., `C:\ngrok` or `/usr/local/bin`)

**Option B: Package Manager**

```bash
# macOS (Homebrew)
brew install ngrok/ngrok/ngrok

# Windows (Chocolatey)
choco install ngrok

# Linux (Snap)
snap install ngrok
```

### Step 2: Add ngrok to PATH (Windows Only)

**If ngrok is not in PATH:**

```powershell
# Add to PATH temporarily (current session only)
$env:PATH += ";C:\ngrok"

# Or add permanently via System Properties:
# 1. Win + R → "SystemPropertiesAdvanced"
# 2. Environment Variables → Path → Edit
# 3. Add C:\ngrok
```

### Step 3: Authenticate ngrok

1. Get your authtoken from https://dashboard.ngrok.com/get-started/your-authtoken
2. Run authentication command:

```bash
ngrok config add-authtoken YOUR_AUTH_TOKEN_HERE
```

**Example:**
```bash
ngrok config add-authtoken 2aB3cD4eF5gH6iJ7kL8mN9oP0qR1sT2uV3wX4yZ5
```

This saves your authtoken to `~/.ngrok2/ngrok.yml` (Mac/Linux) or `%USERPROFILE%\.ngrok2\ngrok.yml` (Windows).

### Step 4: Verify Installation

```bash
ngrok version
# Output: ngrok version 3.x.x
```

---

## Basic Setup

### Step 1: Start Your Backend Server

**For Pursue backend:**

```bash
cd pursue-server
npm run dev
```

Your server should be running on `http://localhost:3000` (or whatever port you configured).

**Verify it's running:**
```bash
curl http://localhost:3000/health
# Should return: {"status":"ok"}
```

### Step 2: Start ngrok Tunnel

**Basic command:**
```bash
ngrok http 3000
```

**You'll see output like this:**
```
ngrok                                                                 

Session Status                online
Account                       Shannon Thompson (Plan: Free)
Version                       3.6.0
Region                        United States (us)
Latency                       45ms
Web Interface                 http://127.0.0.1:4040
Forwarding                    https://abc123xyz.ngrok.io -> http://localhost:3000

Connections                   ttl     opn     rt1     rt5     p50     p90
                              0       0       0.00    0.00    0.00    0.00
```

**Important URLs:**
- **Public URL:** `https://abc123xyz.ngrok.io` ← Use this in your Android app
- **Web Interface:** `http://127.0.0.1:4040` ← View requests/responses in browser

### Step 3: Test the Tunnel

**From your phone's browser:**
1. Open Chrome/Safari
2. Navigate to `https://abc123xyz.ngrok.io/health`
3. You should see: `{"status":"ok"}`

✅ If this works, your tunnel is set up correctly!

---

## Android App Configuration

### Option 1: Build Variant with ngrok URL (Recommended)

**Create a debug build variant that uses ngrok:**

#### `app/build.gradle.kts`

```kotlin
android {
    // ...
    
    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:3000\"") // Emulator
            isDebuggable = true
        }
        
        // New: ngrok debug variant
        create("ngrok") {
            initWith(getByName("debug"))
            buildConfigField("String", "API_BASE_URL", "\"https://YOUR_NGROK_URL\"")
            applicationIdSuffix = ".ngrok"
            isDebuggable = true
        }
        
        release {
            buildConfigField("String", "API_BASE_URL", "\"https://api.getpursue.app\"")
            isMinifyEnabled = true
            // ...
        }
    }
    
    buildFeatures {
        buildConfig = true
    }
}
```

**Usage in code:**

```kotlin
// ApiClient.kt
object ApiClient {
    private const val BASE_URL = BuildConfig.API_BASE_URL
    
    val retrofit: Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .addConverterFactory(GsonConverterFactory.create())
        .build()
}
```

**Deploy to device:**
```bash
# Build ngrok variant
./gradlew assembleNgrok

# Install on device
adb install app/build/outputs/apk/ngrok/app-ngrok.apk
```

**Problem:** You need to rebuild every time ngrok restarts (URL changes).

---

### Option 2: Runtime Configuration (Flexible)

**Use Android's Debug Settings to change API URL at runtime:**

#### `app/src/debug/res/xml/network_security_config.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Allow cleartext (HTTP) traffic for localhost -->
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">10.0.2.2</domain>
        <domain includeSubdomains="true">localhost</domain>
    </domain-config>
    
    <!-- Trust ngrok certificates -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

#### `app/src/main/AndroidManifest.xml`

```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    ...>
```

#### Create a Debug Settings Screen

```kotlin
// DebugSettingsActivity.kt (only in debug builds)
class DebugSettingsActivity : AppCompatActivity() {
    
    private val prefs by lazy {
        getSharedPreferences("debug_settings", Context.MODE_PRIVATE)
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug_settings)
        
        val urlInput = findViewById<EditText>(R.id.api_url_input)
        val saveButton = findViewById<Button>(R.id.save_button)
        
        // Load current URL
        urlInput.setText(prefs.getString("api_base_url", "http://10.0.2.2:3000"))
        
        saveButton.setOnClickListener {
            val url = urlInput.text.toString()
            prefs.edit().putString("api_base_url", url).apply()
            
            Toast.makeText(this, "API URL updated. Restart app.", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}
```

#### Update ApiClient to read from SharedPreferences

```kotlin
object ApiClient {
    
    private fun getBaseUrl(context: Context): String {
        // In release builds, use production URL
        if (!BuildConfig.DEBUG) {
            return "https://api.getpursue.app"
        }
        
        // In debug builds, check SharedPreferences
        val prefs = context.getSharedPreferences("debug_settings", Context.MODE_PRIVATE)
        return prefs.getString("api_base_url", "http://10.0.2.2:3000") ?: "http://10.0.2.2:3000"
    }
    
    fun create(context: Context): ApiService {
        val retrofit = Retrofit.Builder()
            .baseUrl(getBaseUrl(context))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            
        return retrofit.create(ApiService::class.java)
    }
}
```

#### Add Debug Settings to Drawer Menu (only in debug)

```kotlin
// MainActivity.kt
override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    
    // Add debug menu item (only in debug builds)
    if (BuildConfig.DEBUG) {
        binding.navView.menu.add(Menu.NONE, R.id.debug_settings, Menu.NONE, "🔧 Debug Settings")
    }
}

override fun onNavigationItemSelected(item: MenuItem): Boolean {
    when (item.itemId) {
        R.id.debug_settings -> {
            startActivity(Intent(this, DebugSettingsActivity::class.java))
            return true
        }
        // ... other menu items
    }
}
```

**Usage:**
1. Open app → Drawer menu → "🔧 Debug Settings"
2. Paste ngrok URL: `https://abc123xyz.ngrok.io`
3. Tap "Save"
4. Restart app
5. All API calls now go to ngrok URL

✅ **No rebuild needed when ngrok restarts!**

---

### Option 3: Environment Variable (Build-Time)

**For quick testing without code changes:**

#### `local.properties` (ignored by Git)

```properties
# local.properties
api.base.url=https://abc123xyz.ngrok.io
```

#### `app/build.gradle.kts`

```kotlin
import java.util.Properties

android {
    // ...
    
    defaultConfig {
        // Read from local.properties
        val properties = Properties()
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }
        
        val apiBaseUrl = properties.getProperty("api.base.url", "http://10.0.2.2:3000")
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }
}
```

**Update local.properties every time ngrok restarts:**
```bash
# Update local.properties
echo "api.base.url=https://NEW_NGROK_URL.ngrok.io" > local.properties

# Rebuild and install
./gradlew installDebug
```

---

## Testing Workflow

### Daily Development Workflow

**1. Start Backend Server**
```bash
cd pursue-server
npm run dev
# Server running on http://localhost:3000
```

**2. Start ngrok**
```bash
ngrok http 3000
# Note the URL: https://abc123xyz.ngrok.io
```

**3. Update Android App**

**Option A: Using Debug Settings Screen (no rebuild)**
- Open app → Debug Settings
- Paste `https://abc123xyz.ngrok.io`
- Restart app

**Option B: Using local.properties (requires rebuild)**
```bash
echo "api.base.url=https://abc123xyz.ngrok.io" > local.properties
./gradlew installDebug
```

**4. Test on Physical Device**
- Open app on phone
- Login, create group, log progress, etc.
- Backend receives requests via ngrok tunnel

**5. Monitor Traffic**
- Open http://127.0.0.1:4040 in browser
- See all HTTP requests/responses in real-time
- Replay requests, inspect headers, view timings

**6. Check Backend Logs**
```bash
# In pursue-server terminal
# You'll see logs for each request:
# POST /api/auth/login - 200 - 45ms
# GET /api/users/me/groups - 200 - 12ms
```

### When ngrok Restarts (Free Tier)

**Free tier = URL changes every restart**

1. Stop ngrok (Ctrl+C)
2. Restart: `ngrok http 3000`
3. Note new URL: `https://xyz789new.ngrok.io`
4. Update Android app (using Debug Settings or local.properties)
5. Continue testing

**Annoying?** → Upgrade to ngrok Pro ($10/month) for static URLs.

---

## Common Issues & Solutions

### Issue 1: "Network Security Policy" Error

**Error in Logcat:**
```
CLEARTEXT communication to abc123.ngrok.io not permitted by network security policy
```

**Solution:** ngrok uses HTTPS by default, but if you see this:

1. **Check your ngrok URL** - Should start with `https://`, not `http://`
2. **Update network_security_config.xml:**

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="user" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

---

### Issue 2: "Connection Refused" or "Host Unreachable"

**Error:**
```
java.net.ConnectException: Failed to connect to abc123.ngrok.io
```

**Checklist:**
- ✅ Is ngrok running? (Check terminal)
- ✅ Is backend server running? (`curl http://localhost:3000/health`)
- ✅ Is ngrok URL correct in app? (Check Debug Settings or BuildConfig)
- ✅ Is phone connected to internet? (Try opening ngrok URL in phone browser)
- ✅ Is firewall blocking ngrok? (Windows Defender, antivirus)

**Test in phone browser first:**
```
https://abc123xyz.ngrok.io/health
```

If this works but app doesn't → Problem is in app configuration.

---

### Issue 3: ngrok "Too Many Connections" (Rate Limit)

**Error in ngrok console:**
```
ERR_NGROK_3200: This account is limited to 40 connections per minute
```

**Solutions:**
1. **Wait 1 minute** - Rate limit resets
2. **Reduce API calls** - Check for infinite loops, redundant requests
3. **Upgrade to paid plan** - $10/month removes limit

**Debug high request volume:**
```typescript
// Add request logging middleware (backend)
app.use((req, res, next) => {
    console.log(`${new Date().toISOString()} - ${req.method} ${req.path}`);
    next();
});
```

---

### Issue 4: ngrok URL Changes Every Time

**Problem:** Free tier = new URL every restart

**Solutions:**

**Option 1: Use Debug Settings Screen (Recommended)**
- No rebuild needed
- Update URL in app settings
- Restart app

**Option 2: Upgrade to ngrok Pro ($10/month)**
- Get reserved domain: `https://yourname.ngrok.io`
- Never changes

**Option 3: Use a custom domain (ngrok Pro + DNS)**
- Register domain: `dev.getpursue.app`
- Point CNAME to ngrok
- Use same URL forever

---

### Issue 5: Firebase Cloud Messaging Not Working

**Problem:** FCM callbacks need public HTTPS URL

**Solution:** ngrok is perfect for this!

1. Update Firebase project settings:
   - Go to Firebase Console → Project Settings → Cloud Messaging
   - Add ngrok URL as authorized domain: `abc123xyz.ngrok.io`

2. Update Android app's FCM callback URL:
```kotlin
// In your FCM service
val callbackUrl = "${BuildConfig.API_BASE_URL}/api/fcm/callback"
```

3. Backend receives FCM webhooks via ngrok:
```typescript
app.post('/api/fcm/callback', (req, res) => {
    console.log('FCM webhook received:', req.body);
    // Process notification
});
```

---

### Issue 6: CORS Errors

**Error in ngrok web interface:**
```
Access-Control-Allow-Origin header missing
```

**Solution:** Add CORS middleware to backend

```typescript
// backend/server.ts
import cors from 'cors';

app.use(cors({
    origin: [
        'http://localhost:3000',
        'https://*.ngrok.io',  // Allow all ngrok subdomains
        'https://abc123xyz.ngrok.io'  // Or specific URL
    ],
    credentials: true
}));
```

**Or allow all origins (dev only!):**
```typescript
app.use(cors({ origin: '*' }));
```

---

## Advanced Configuration

### Custom Subdomain (ngrok Pro)

**Reserve a custom subdomain:**
```bash
# Reserve at: https://dashboard.ngrok.com/cloud-edge/domains
# Then use it:
ngrok http --domain=shannon-dev.ngrok.io 3000
```

**Your URL is now always:** `https://shannon-dev.ngrok.io`

---

### ngrok Configuration File

**Create persistent configuration:**

**~/.ngrok2/ngrok.yml** (Mac/Linux) or **%USERPROFILE%\.ngrok2\ngrok.yml** (Windows)

```yaml
version: "2"
authtoken: YOUR_AUTH_TOKEN

tunnels:
  pursue-api:
    proto: http
    addr: 3000
    inspect: true
    # Pro feature: custom domain
    # domain: shannon-dev.ngrok.io
    
  pursue-websocket:
    proto: http
    addr: 3001
    inspect: false
```

**Start by name:**
```bash
ngrok start pursue-api
```

**Start multiple tunnels:**
```bash
ngrok start pursue-api pursue-websocket
```

---

### Inspecting Traffic (ngrok Web Interface)

**Access at:** http://127.0.0.1:4040

**Features:**
- 📋 **Request list** - All HTTP requests with status codes
- 🔍 **Request detail** - Headers, body, query params
- 📊 **Response detail** - Headers, body, timing
- 🔄 **Replay** - Resend any request with modifications
- 📂 **Export** - Save requests as cURL, Postman, etc.

**Use cases:**
- Debug authentication (inspect JWT tokens)
- Check request/response headers
- Verify JSON payloads
- Test edge cases (replay with different data)

---

### Running ngrok in Background

**Option 1: Terminal multiplexer (tmux/screen)**

```bash
# Install tmux (Mac)
brew install tmux

# Create session
tmux new -s ngrok

# Start ngrok
ngrok http 3000

# Detach: Ctrl+B, then D
# Reattach: tmux attach -t ngrok
```

**Option 2: Run as daemon (systemd on Linux)**

```bash
# /etc/systemd/system/ngrok.service
[Unit]
Description=ngrok tunnel
After=network.target

[Service]
ExecStart=/usr/local/bin/ngrok http 3000
Restart=always
User=shannon

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl start ngrok
sudo systemctl enable ngrok  # Start on boot
```

**Option 3: PM2 (Node.js process manager)**

```bash
npm install -g pm2

# Start ngrok via PM2
pm2 start ngrok --name ngrok-tunnel -- http 3000

# View logs
pm2 logs ngrok-tunnel

# Restart on reboot
pm2 startup
pm2 save
```

---

### Using ngrok API

**Programmatically get current ngrok URL:**

```typescript
// scripts/get-ngrok-url.ts
import axios from 'axios';

async function getNgrokUrl(): Promise<string | null> {
    try {
        const response = await axios.get('http://127.0.0.1:4040/api/tunnels');
        const tunnels = response.data.tunnels;
        
        // Find HTTPS tunnel
        const httpsTunnel = tunnels.find((t: any) => t.proto === 'https');
        
        if (httpsTunnel) {
            console.log('ngrok URL:', httpsTunnel.public_url);
            return httpsTunnel.public_url;
        }
        
        return null;
    } catch (error) {
        console.error('ngrok not running or API not accessible');
        return null;
    }
}

getNgrokUrl();
```

**Auto-update local.properties:**

```bash
# scripts/update-ngrok-url.sh
#!/bin/bash

# Get ngrok URL from API
NGROK_URL=$(curl -s http://127.0.0.1:4040/api/tunnels | jq -r '.tunnels[] | select(.proto=="https") | .public_url')

# Update local.properties
echo "api.base.url=$NGROK_URL" > local.properties

echo "✅ Updated local.properties with: $NGROK_URL"
```

---

## Security Considerations

### ⚠️ Important Security Notes

1. **ngrok URLs are public** - Anyone with the URL can access your server
   - Free tier URLs are hard to guess: `https://abc123xyz789.ngrok.io`
   - But don't share them publicly!

2. **Don't commit ngrok URLs to Git**
   - Add to `.gitignore`:
     ```
     local.properties
     ngrok.yml
     ```

3. **Use authentication** - Always require JWT tokens
   ```typescript
   // Backend: Protect all routes
   app.use('/api', authenticateJWT);
   ```

4. **Rate limiting** - Prevent abuse
   ```typescript
   import rateLimit from 'express-rate-limit';
   
   const limiter = rateLimit({
       windowMs: 15 * 60 * 1000, // 15 minutes
       max: 100 // limit each IP to 100 requests per window
   });
   
   app.use('/api', limiter);
   ```

5. **Disable debug endpoints in production**
   ```typescript
   if (process.env.NODE_ENV !== 'development') {
       app.get('/debug/*', (req, res) => res.status(404).send());
   }
   ```

6. **Monitor ngrok dashboard** - Check for suspicious activity
   - https://dashboard.ngrok.com/observability/events

7. **Use HTTPS only** - ngrok provides it by default

8. **Don't use ngrok for production** - It's a development tool

### Best Practices

✅ **Do:**
- Use ngrok only for development/testing
- Require authentication on all API endpoints
- Monitor ngrok traffic via web interface
- Use environment variables for sensitive data
- Rotate ngrok URLs frequently (or use Pro with auth)

❌ **Don't:**
- Share ngrok URLs publicly
- Commit ngrok URLs to Git
- Use ngrok in production
- Store sensitive data in local.properties
- Leave ngrok running when not testing

---

## Alternatives to ngrok

### 1. **Local Network (Same Wi-Fi)**

**If phone and computer are on same Wi-Fi:**

```kotlin
// Android app
buildConfigField("String", "API_BASE_URL", "\"http://YOUR_COMPUTER_IP:3000\"")
```

**Find your computer's IP:**
```bash
# Mac/Linux
ifconfig | grep "inet "

# Windows
ipconfig | findstr IPv4

# Example: 192.168.1.100
```

**Android uses:** `http://192.168.1.100:3000`

**Advantages:**
- ✅ Free
- ✅ Fast (local network)
- ✅ No internet required

**Disadvantages:**
- ❌ Only works on same Wi-Fi
- ❌ IP might change
- ❌ Doesn't work for remote testing

---

### 2. **LocalTunnel (ngrok alternative)**

**Install:**
```bash
npm install -g localtunnel
```

**Usage:**
```bash
lt --port 3000
# URL: https://random-name.loca.lt
```

**Advantages:**
- ✅ Free
- ✅ Open source
- ✅ No account needed

**Disadvantages:**
- ❌ Less reliable than ngrok
- ❌ Slower
- ❌ No traffic inspection UI

---

### 3. **Cloudflare Tunnel (cloudflared)**

**Install:**
```bash
# Mac
brew install cloudflared

# Windows
choco install cloudflared
```

**Usage:**
```bash
cloudflared tunnel --url http://localhost:3000
# URL: https://random.trycloudflare.com
```

**Advantages:**
- ✅ Free
- ✅ Fast (Cloudflare's network)
- ✅ Reliable

**Disadvantages:**
- ❌ No traffic inspection UI
- ❌ URL changes every time

---

### 4. **Tailscale (VPN-based)**

**For advanced users who want a permanent solution:**

1. Install Tailscale on computer and phone
2. Access backend via Tailscale IP (e.g., `http://100.64.0.1:3000`)
3. Works anywhere, even on cellular data

**Advantages:**
- ✅ Permanent IP
- ✅ Very secure (WireGuard VPN)
- ✅ Works on cellular data

**Disadvantages:**
- ❌ More complex setup
- ❌ Requires Tailscale on both devices

---

### 5. **Deploy to Cloud (Staging Environment)**

**For serious testing:**

Deploy backend to Google Cloud Run, Heroku, or Railway:

```bash
# Example: Railway
npm install -g @railway/cli
railway login
railway init
railway up
# URL: https://your-app.railway.app
```

**Advantages:**
- ✅ Permanent URL
- ✅ Real production-like environment
- ✅ Can share with beta testers

**Disadvantages:**
- ❌ Costs money (usually)
- ❌ Slower iteration (need to redeploy)
- ❌ Not suitable for rapid development

---

## Quick Reference

### ngrok Commands

```bash
# Basic tunnel
ngrok http 3000

# Custom subdomain (Pro)
ngrok http --domain=myapp.ngrok.io 3000

# Start tunnel from config
ngrok start pursue-api

# Check version
ngrok version

# Update ngrok
ngrok update
```

### Android Configuration

```kotlin
// Debug build config
buildConfigField("String", "API_BASE_URL", "\"https://abc123.ngrok.io\"")

// Runtime config (SharedPreferences)
val url = prefs.getString("api_base_url", "http://10.0.2.2:3000")
```

### Troubleshooting Checklist

- [ ] ngrok is running (`ngrok http 3000`)
- [ ] Backend server is running (`curl http://localhost:3000/health`)
- [ ] ngrok URL is correct in Android app
- [ ] Phone has internet connection
- [ ] Try ngrok URL in phone browser first
- [ ] Check ngrok web interface for errors (http://127.0.0.1:4040)
- [ ] Check Android Logcat for network errors

---

## Summary

**Recommended Setup for Pursue:**

1. **Install ngrok** (free tier is fine)
2. **Add Debug Settings screen** to Android app
3. **Daily workflow:**
   - Start backend: `npm run dev`
   - Start ngrok: `ngrok http 3000`
   - Copy URL: `https://abc123xyz.ngrok.io`
   - Update in app: Debug Settings → Paste URL → Restart
4. **Test on physical device** - Full end-to-end flows
5. **Monitor traffic** - http://127.0.0.1:4040

**When to upgrade to ngrok Pro ($10/month):**
- You're tired of updating the URL every time
- You need a static subdomain
- You're testing webhooks (Firebase, OAuth)
- You're collaborating with other developers

**Resources:**
- ngrok Docs: https://ngrok.com/docs
- ngrok Dashboard: https://dashboard.ngrok.com
- Android Network Security: https://developer.android.com/training/articles/security-config

---

**Happy testing! 🚀**
